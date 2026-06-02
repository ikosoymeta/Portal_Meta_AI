package com.ikosoy.portalmetaai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * A floating "Meta AI" orb drawn over the Home / App pages (a system overlay).
 * Tapping it launches the assistant and starts a session; it's draggable. The
 * orb is shown when the assistant activity is in the background (ACTION_SHOW from
 * MainActivity.onPause) and hidden when the activity is foreground (ACTION_HIDE).
 *
 * Needs the SYSTEM_ALERT_WINDOW app-op (granted via adb in scripts/deploy.sh).
 */
public class OverlayService extends Service {

    public static final String ACTION_SHOW = "show";
    public static final String ACTION_HIDE = "hide";
    private static final String CHANNEL = "metaai_overlay";

    private WindowManager wm;
    private ImageView orb;
    private WindowManager.LayoutParams lp;
    private boolean added = false;

    // --- background "Hi Meta" wake-listener (runs while the app is backgrounded) ---
    private static final String BASE = "http://127.0.0.1:8765";
    private static final int SAMPLE_RATE = 16000, FRAME = 320;
    private static final int END_SILENCE_MS = 700, MIN_UTT_MS = 350, MAX_UTT_MS = 6000;
    private static final int WAKE_WAIT_MS = 8000, MIN_ABS_RMS = 550;
    private static final double SPEECH_MULT = 3.0;
    private Thread wakeThread;
    private volatile boolean wakeRunning = false;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        startForeground(7, buildNotification());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        buildOrb();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if (ACTION_HIDE.equals(a)) {        // app is foreground: it owns the mic
            removeOrb();
            stopWake();
        } else {                            // backgrounded / boot: show orb + listen for "Hi Meta"
            addOrb();
            startWake();
        }
        return START_STICKY;
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "Meta AI orb", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("Meta AI")
                .setContentText("Tap the orb to talk")
                .setSmallIcon(iconId())
                .build();
    }

    private int iconId() {
        int id = getResources().getIdentifier("ic_launcher", "mipmap", getPackageName());
        return id != 0 ? id : android.R.drawable.ic_btn_speak_now;
    }

    private void buildOrb() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int size = Math.round(78 * dm.density);

        orb = new ImageView(this);
        int round = getResources().getIdentifier("ic_launcher_round", "mipmap", getPackageName());
        orb.setImageResource(round != 0 ? round : iconId());
        orb.setScaleType(ImageView.ScaleType.FIT_CENTER);

        lp = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        // Default position: top-right, just left of the launcher's profile bubble.
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = Math.max(0, Math.round(dm.widthPixels * 0.892f) - size);
        lp.y = Math.max(0, Math.round(dm.heightPixels * 0.089f - size / 2f));

        orb.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY; int baseX, baseY; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY();
                        baseX = lp.x; baseY = lp.y; moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - downX), dy = (int) (e.getRawY() - downY);
                        if (Math.abs(dx) + Math.abs(dy) > 16) moved = true;
                        lp.x = baseX + dx; lp.y = baseY + dy;
                        try { wm.updateViewLayout(orb, lp); } catch (Exception ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) launchSession();
                        return true;
                }
                return false;
            }
        });
    }

    private void launchSession() {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.putExtra(MainActivity.EXTRA_AUTOSTART, true);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Exception ignored) {}
    }

    private void addOrb() {
        if (added || orb == null) return;
        try { wm.addView(orb, lp); added = true; } catch (Exception ignored) {}
    }

    private void removeOrb() {
        if (!added || orb == null) return;
        try { wm.removeView(orb); } catch (Exception ignored) {}
        added = false;
    }

    @Override public void onDestroy() {
        stopWake();
        removeOrb();
        super.onDestroy();
    }

    /* ---------------- "Hi Meta" wake-listener (background) ---------------- */
    private void startWake() {
        if (wakeRunning) return;
        wakeRunning = true;
        wakeThread = new Thread(new Runnable() { @Override public void run() { wakeLoop(); } }, "wake");
        wakeThread.start();
    }

    private void stopWake() {
        wakeRunning = false;
        Thread t = wakeThread; wakeThread = null;
        if (t != null) t.interrupt();
    }

    private void wakeLoop() {
        if (!Mic.acquire(6000)) return;        // wait for the activity worker to release
        AudioRecord rec = null;
        try {
            int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min, FRAME * 16));
            rec.startRecording();
            android.util.Log.i("PortalMetaAI", "wake: listening (background)");
            while (wakeRunning) {
                short[] utt = record(rec);
                if (!wakeRunning || utt == null) continue;
                String t = stt(utt);
                android.util.Log.i("PortalMetaAI", "wake heard: " + t);
                if (t != null && isWake(t.toLowerCase(Locale.US))) {
                    launchSession();        // hand off to the activity (it sends HIDE -> stopWake)
                    return;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (rec != null) { try { rec.stop(); } catch (Exception e) {} rec.release(); }
            Mic.release();
        }
    }

    // Energy-VAD: capture one utterance, or null on silence/abort.
    private short[] record(AudioRecord rec) {
        short[] frame = new short[FRAME];
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        short[][] pre = new short[10][]; int preIdx = 0, preCount = 0;
        double noise = MIN_ABS_RMS; boolean speech = false;
        int waited = 0, sil = 0, sp = 0;
        while (wakeRunning) {
            int n = rec.read(frame, 0, FRAME);
            if (n <= 0) continue;
            double rms = rms(frame, n), th = Math.max(MIN_ABS_RMS, noise * SPEECH_MULT);
            if (!speech) {
                noise = noise * 0.95 + rms * 0.05;
                short[] cp = new short[n]; System.arraycopy(frame, 0, cp, 0, n);
                pre[preIdx] = cp; preIdx = (preIdx + 1) % pre.length; if (preCount < pre.length) preCount++;
                waited += 20;
                if (rms > th) {
                    speech = true; sil = 0; sp = 0;
                    for (int k = 0; k < preCount; k++) { short[] f = pre[(preIdx + k) % pre.length]; if (f != null) ws(pcm, f, f.length); }
                } else if (waited >= WAKE_WAIT_MS) {
                    return null;
                }
            } else {
                ws(pcm, frame, n); sp += 20;
                if (rms > th) sil = 0; else sil += 20;
                if (sil >= END_SILENCE_MS || sp >= MAX_UTT_MS) break;
            }
        }
        if (sp < MIN_UTT_MS) return null;
        byte[] b = pcm.toByteArray(); short[] out = new short[b.length / 2];
        for (int i = 0; i < out.length; i++) out[i] = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8));
        return out;
    }

    private static double rms(short[] f, int n) {
        long s = 0; for (int i = 0; i < n; i++) s += (long) f[i] * f[i];
        return Math.sqrt((double) s / Math.max(1, n));
    }
    private static void ws(ByteArrayOutputStream o, short[] f, int n) {
        for (int i = 0; i < n; i++) { o.write(f[i] & 0xff); o.write((f[i] >> 8) & 0xff); }
    }
    private static boolean isWake(String s) {
        return s.matches(".*\\b(hi|hey|high)[,\\s]+(meta|metta|mehta|meda|metre)\\b.*");
    }

    private String stt(short[] pcm) {
        try {
            int dataLen = pcm.length * 2, total = 36 + dataLen;
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.write(new byte[]{'R','I','F','F'}); le32(o, total);
            o.write(new byte[]{'W','A','V','E','f','m','t',' '});
            le32(o, 16); le16(o, 1); le16(o, 1);
            le32(o, SAMPLE_RATE); le32(o, SAMPLE_RATE * 2); le16(o, 2); le16(o, 16);
            o.write(new byte[]{'d','a','t','a'}); le32(o, dataLen);
            for (short v : pcm) { o.write(v & 0xff); o.write((v >> 8) & 0xff); }
            HttpURLConnection c = (HttpURLConnection) new URL(BASE + "/stt").openConnection();
            c.setConnectTimeout(4000); c.setReadTimeout(60000);
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "audio/wav");
            OutputStream os = c.getOutputStream(); os.write(o.toByteArray()); os.close();
            java.io.InputStream in = (c.getResponseCode() < 400) ? c.getInputStream() : c.getErrorStream();
            ByteArrayOutputStream r = new ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int k;
            while ((k = in.read(buf)) != -1) r.write(buf, 0, k);
            in.close();
            return new JSONObject(new String(r.toByteArray(), "UTF-8")).optString("text", "");
        } catch (Exception e) { return ""; }
    }
    private static void le32(ByteArrayOutputStream o, int v) { o.write(v); o.write(v>>8); o.write(v>>16); o.write(v>>24); }
    private static void le16(ByteArrayOutputStream o, int v) { o.write(v); o.write(v>>8); }
}
