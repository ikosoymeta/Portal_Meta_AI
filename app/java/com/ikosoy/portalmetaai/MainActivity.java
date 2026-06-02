package com.ikosoy.portalmetaai;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebSettings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Portal Meta AI — a hands-free voice assistant.
 *
 * Pipeline (single worker thread for natural turn-taking, no echo):
 *   AudioRecord + energy VAD  ->  POST /stt (whisper.cpp on the Mac)
 *   -> match "Hi Meta" / "Meta Stop", else POST /ask (Metamate on the devserver)
 *   -> speak the reply with Android TextToSpeech (Portal's Meta TTS engine).
 *
 * The WebView (assets/index.html) is just the visual orb; native code drives it
 * via window.setOrb(state) / showUser(text) / showMeta(text). Taps on the orb
 * come back through the Android JS bridge (onOrbTap).
 *
 * Network: the proxy is reached at 127.0.0.1:PORT via `adb reverse` (see
 * scripts/run.sh), so no on-device network config is needed.
 */
public class MainActivity extends Activity {

    private static final String BASE = "http://127.0.0.1:8765";
    public static final String EXTRA_AUTOSTART = "autostart";  // set by the overlay orb tap
    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME = 320;            // 20 ms @ 16 kHz
    private static final int END_SILENCE_MS = 600;   // trailing silence ends an utterance
    private static final int MIN_UTT_MS = 300;       // ignore shorter blips
    private static final int MAX_UTT_MS = 12000;
    private static final int IDLE_WAIT_MS = 8000;    // idle: re-poll flags every 8 s
    private static final int ACTIVE_WAIT_MS = 20000; // active: silence this long -> end reminder
    private static final int MIN_ABS_RMS = 400;      // absolute speech floor
    private static final double SPEECH_MULT = 2.0;   // speech if rms > noiseFloor * this
    // Portal's default TTS engine (FbGiga5) has no third-party voice model
    // ("empty language model path"); this Nuance-backed engine has voice "Zoe".
    private static final String TTS_ENGINE = "com.facebook.aloha.fbttsservice";

    private WebView webView;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private volatile boolean ttsReady = false;

    private Thread worker;
    private volatile boolean running = false;
    private volatile int mode = 0;             // 0 = idle, 1 = active conversation
    private volatile boolean startReq = false; // tap-to-start
    private volatile boolean stopReq = false;  // tap-to-stop
    private volatile boolean awaitingEnd = false;
    private volatile boolean speaking = false;          // TTS is talking
    private volatile CountDownLatch speakLatch;         // unblock speakBlocking on interrupt
    private String convUuid = null;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        webView.addJavascriptInterface(new Bridge(), "Android");
        setContentView(webView);
        enterImmersive();
        webView.loadUrl("file:///android_asset/index.html");

        TextToSpeech.OnInitListener init = new TextToSpeech.OnInitListener() {
            @Override public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    tts.setLanguage(Locale.US);
                    ttsReady = true;
                } else {
                    // fall back to the system default engine if the pinned one fails
                    tts = new TextToSpeech(MainActivity.this, new TextToSpeech.OnInitListener() {
                        @Override public void onInit(int s2) {
                            if (s2 == TextToSpeech.SUCCESS) { tts.setLanguage(Locale.US); ttsReady = true; }
                        }
                    });
                }
            }
        };
        tts = new TextToSpeech(this, init, TTS_ENGINE);

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }
        // Floating orb overlay over Home / App pages (tap to start a session).
        try { startForegroundService(new Intent(this, OverlayService.class)); } catch (Exception ignored) {}
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);   // so onResume sees a fresh EXTRA_AUTOSTART
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) startWorker();
        else setOrb("nomic");
    }

    @Override protected void onResume() {
        super.onResume();
        enterImmersive();
        setOverlay(false);                       // we're foreground: hide the floating orb
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startWorker();
            Intent it = getIntent();
            if (it != null && it.getBooleanExtra(EXTRA_AUTOSTART, false)) {
                it.removeExtra(EXTRA_AUTOSTART);
                startReq = true;                 // overlay tap -> begin a session
            }
        }
    }

    @Override protected void onPause() {
        super.onPause();
        stopWorker();                            // stop listening when backgrounded
        setOverlay(true);                        // show the floating orb over Home / App pages
    }

    @Override protected void onDestroy() {
        stopWorker();
        if (tts != null) { tts.shutdown(); }
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) enterImmersive();
    }

    private void setOverlay(boolean show) {
        try {
            Intent i = new Intent(this, OverlayService.class);
            i.setAction(show ? OverlayService.ACTION_SHOW : OverlayService.ACTION_HIDE);
            startService(i);
        } catch (Exception ignored) {}
    }

    /** End any session and return to the Portal launcher (Home / App page). */
    private void endAndGoHome() {
        stopReq = true;
        mode = 0;
        try { if (tts != null) tts.stop(); } catch (Exception ignored) {}
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {}
    }

    /* ----------------------------- JS bridge ----------------------------- */
    private class Bridge {
        @JavascriptInterface public void onOrbTap() {
            if (mode == 0) startReq = true;            // idle -> start
            else if (speaking) interruptSpeech();       // talking -> stop & listen (barge-in)
            else stopReq = true;                        // listening -> end session
        }
        /** Apps quick-link: stop the session and return to the Portal launcher. */
        @JavascriptInterface public void goAppHome() {
            ui.post(new Runnable() { @Override public void run() { endAndGoHome(); } });
        }
    }

    /* --------------------------- worker thread --------------------------- */
    private void startWorker() {
        if (running) return;
        running = true;
        setOrb("idle");
        worker = new Thread(new Runnable() { @Override public void run() { loop(); } }, "voice");
        worker.start();
    }

    private void stopWorker() {
        running = false;
        Thread w = worker; worker = null;
        if (w != null) w.interrupt();
        mode = 0; awaitingEnd = false; startReq = false; stopReq = false;
    }

    private void loop() {
        if (!Mic.acquire(6000)) { setOrb("nomic"); return; }   // wait for the wake-listener to release
        AudioRecord rec = null;
        try {
            int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int buf = Math.max(min, FRAME * 8 * 2);
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf);
            rec.startRecording();

            while (running) {
                if (startReq && mode == 0) { startReq = false; enterActive(); }
                if (stopReq && mode == 1) { stopReq = false; endActive("Okay, ending our chat. Bye."); continue; }

                int waitMs = (mode == 1) ? ACTIVE_WAIT_MS : IDLE_WAIT_MS;
                short[] utt = recordUtterance(rec, waitMs);
                if (!running) break;
                if (startReq && mode == 0) { startReq = false; enterActive(); continue; }
                if (stopReq && mode == 1) { stopReq = false; endActive("Okay, ending our chat. Bye."); continue; }

                if (utt == null) {                         // silence / timeout
                    if (mode == 1) {
                        if (awaitingEnd) { awaitingEnd = false; endActive("Okay, I'll let you go. Goodbye."); }
                        else { awaitingEnd = true; setOrb("speaking");
                               speakBlocking("Do you want to end our Meta AI conversation?");
                               setOrb("listening"); }
                    }
                    continue;
                }

                setOrb("thinking");
                String text = stt(utt);
                if (text == null || text.trim().isEmpty()) {
                    android.util.Log.i("PortalMetaAI", "stt empty (mode=" + mode + ")");
                    setOrb(mode == 1 ? "listening" : "idle"); continue;
                }
                android.util.Log.i("PortalMetaAI", "heard[mode=" + mode + "]: \"" + text.trim()
                        + "\" wake=" + isWake(text.trim().toLowerCase(Locale.US)));
                handleTranscript(text.trim());
            }
        } catch (Throwable t) {
            setOrb("nomic");
        } finally {
            if (rec != null) { try { rec.stop(); } catch (Exception e) {} rec.release(); }
            Mic.release();
        }
    }

    private void handleTranscript(String text) {
        String low = text.toLowerCase(Locale.US);
        // "Meta Go Home" works in any state: stop the session and go to the App page.
        if (isGoHome(low)) {
            showUser(text);
            if (mode == 1) { setOrb("speaking"); speakBlocking("Going home."); }
            mode = 0; awaitingEnd = false;
            ui.post(new Runnable() { @Override public void run() { endAndGoHome(); } });
            return;
        }
        if (mode == 0) {
            showUser(text);
            if (isWake(low)) enterActive(); else setOrb("idle");
            return;
        }
        // active
        if (isStop(low)) { showUser(text); endActive("Goodbye."); return; }
        if (awaitingEnd) {
            awaitingEnd = false;
            if (low.matches(".*\\b(yes|yeah|yep|sure|end|stop|done)\\b.*")) { showUser(text); endActive("Goodbye."); return; }
            if (low.matches(".*\\b(no|nope|keep going|continue|stay)\\b.*")) { showUser(text); setOrb("speaking"); speakBlocking("Okay, I'm still here."); setOrb("listening"); return; }
            // otherwise treat as a normal question
        }
        showUser(text);
        setOrb("thinking");
        String reply = ask(text);
        setOrb("speaking");
        showMeta(reply);
        speakBlocking(reply);
        setOrb("listening");
    }

    private void enterActive() {
        mode = 1; convUuid = null; awaitingEnd = false;
        setOrb("speaking");
        speakBlocking("Hi, go ahead.");
        setOrb("listening");
    }

    private void endActive(String bye) {
        setOrb("speaking");
        speakBlocking(bye);
        mode = 0; convUuid = null; awaitingEnd = false;
        setOrb("idle");
    }

    private static boolean isWake(String s) {
        // common whisper spellings of "Hi Meta"
        if (s.matches(".*\\b(hi|hey|high|hello|ok|okay)[,\\s]+(meta|metta|mehta|meda|metre|meeta|mira|meadow|matter|motto|mehra|beta|murra)\\b.*"))
            return true;
        // fuzzy: short utterance that sounds like hi/hey/hello + met...
        String d = s.replaceAll("[^a-z]", "");
        if (d.length() <= 18 && (d.contains("himeta") || d.contains("heymeta") || d.contains("hellometa")
                || ((d.startsWith("hi") || d.startsWith("hey") || d.startsWith("hello")) && d.contains("met"))))
            return true;
        return false;
    }
    private static boolean isStop(String s) {
        return s.matches(".*\\b(meta|metta|mehta)[,\\s]+stop\\b.*")
            || s.matches(".*\\bstop[,\\s]+(meta|metta|mehta)\\b.*");
    }
    // "Meta Go Home" / "go home" / "go to apps" -> stop session, open the launcher.
    // Also fuzzy-matches short mishears like "Metadohum" / "meta go hum".
    private static boolean isGoHome(String s) {
        if (s.matches(".*\\bgo\\s+home\\b.*")
            || s.matches(".*\\b(meta|metta|mehta)[,\\s]+home\\b.*")
            || s.matches(".*\\bgo\\s+(to\\s+)?(the\\s+)?apps?\\b.*")
            || s.matches(".*\\bopen\\s+apps?\\b.*")
            || s.matches(".*\\bgo\\s+back\\b.*")
            || s.matches(".*\\bhome\\s+screen\\b.*")) return true;
        // fuzzy: short single-token mishears of "meta go home"
        String d = s.replaceAll("[^a-z]", "");
        if (d.length() <= 16 && (d.contains("gohome") || d.contains("metahome")
                || d.contains("metagohome")
                || (d.startsWith("meta") && (d.contains("home") || d.contains("hum") || d.contains("hom")))))
            return true;
        return false;
    }

    /* ----------------------------- VAD capture ---------------------------- */
    // Returns captured PCM for one utterance, or null on silence/timeout/abort.
    private short[] recordUtterance(AudioRecord rec, int maxWaitMs) {
        short[] frame = new short[FRAME];
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        // small pre-roll ring so we don't clip the first word
        short[][] pre = new short[10][]; int preIdx = 0, preCount = 0;
        double noiseFloor = MIN_ABS_RMS;
        boolean inSpeech = false;
        int waited = 0, trailingSil = 0, speechMs = 0; double peak = 0;

        while (running) {
            if ((mode == 0 && startReq) || (mode == 1 && stopReq)) return null;
            int n = rec.read(frame, 0, FRAME);
            if (n <= 0) continue;
            double rms = rmsOf(frame, n);
            if (rms > peak) peak = rms;
            if (!inSpeech && waited > 0 && waited % 2000 == 0)
                android.util.Log.i("PortalMetaAI", "fg mic peak rms=" + (int) peak);
            double thresh = Math.max(MIN_ABS_RMS, noiseFloor * SPEECH_MULT);

            if (!inSpeech) {
                noiseFloor = noiseFloor * 0.95 + rms * 0.05;     // adapt to ambient
                short[] cp = new short[n]; System.arraycopy(frame, 0, cp, 0, n);
                pre[preIdx] = cp; preIdx = (preIdx + 1) % pre.length; if (preCount < pre.length) preCount++;
                waited += 20;
                if (rms > thresh) {                               // speech started
                    inSpeech = true; trailingSil = 0; speechMs = 0;
                    for (int k = 0; k < preCount; k++) {          // flush pre-roll in order
                        short[] f = pre[(preIdx + k) % pre.length];
                        if (f != null) writeShorts(pcm, f, f.length);
                    }
                } else if (waited >= maxWaitMs) {
                    return null;
                }
            } else {
                writeShorts(pcm, frame, n);
                speechMs += 20;
                if (rms > thresh) trailingSil = 0; else trailingSil += 20;
                if (trailingSil >= END_SILENCE_MS) break;
                if (speechMs >= MAX_UTT_MS) break;
            }
        }
        if (speechMs < MIN_UTT_MS) {
            if (inSpeech) android.util.Log.i("PortalMetaAI", "utt too short (" + speechMs + "ms, peak " + (int) peak + ")");
            return null;
        }
        android.util.Log.i("PortalMetaAI", "utt captured " + speechMs + "ms peak=" + (int) peak);
        byte[] bytes = pcm.toByteArray();
        short[] out = new short[bytes.length / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (short) ((bytes[2 * i] & 0xff) | (bytes[2 * i + 1] << 8));
        return out;
    }

    private static double rmsOf(short[] f, int n) {
        long sum = 0; for (int i = 0; i < n; i++) sum += (long) f[i] * f[i];
        return Math.sqrt((double) sum / Math.max(1, n));
    }
    private static void writeShorts(ByteArrayOutputStream o, short[] f, int n) {
        for (int i = 0; i < n; i++) { o.write(f[i] & 0xff); o.write((f[i] >> 8) & 0xff); }
    }

    /* ------------------------------- network ------------------------------ */
    private String stt(short[] pcm) {
        try {
            byte[] wav = wav(pcm);
            HttpURLConnection c = (HttpURLConnection) new URL(BASE + "/stt").openConnection();
            c.setConnectTimeout(4000); c.setReadTimeout(60000);
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "audio/wav");
            OutputStream os = c.getOutputStream(); os.write(wav); os.close();
            JSONObject j = new JSONObject(readAll(c));
            return j.optString("text", "");
        } catch (Exception e) { return ""; }
    }

    private String ask(String text) {
        try {
            JSONObject body = new JSONObject();
            body.put("text", text);
            if (convUuid != null) body.put("uuid", convUuid);
            HttpURLConnection c = (HttpURLConnection) new URL(BASE + "/ask").openConnection();
            c.setConnectTimeout(4000); c.setReadTimeout(120000);
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            DataOutputStream os = new DataOutputStream(c.getOutputStream());
            os.write(body.toString().getBytes("UTF-8")); os.close();
            JSONObject j = new JSONObject(readAll(c));
            String u = j.optString("uuid", null);
            if (u != null && !u.isEmpty() && !u.equals("null")) convUuid = u;
            String reply = j.optString("reply", "");
            return reply.isEmpty() ? "Sorry, I didn't get an answer." : reply;
        } catch (Exception e) {
            return "I couldn't reach Meta AI. Check that the Mac proxy is running.";
        }
    }

    private static String readAll(HttpURLConnection c) throws Exception {
        java.io.InputStream in = (c.getResponseCode() < 400) ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int n;
        while ((n = in.read(buf)) != -1) b.write(buf, 0, n);
        in.close();
        return new String(b.toByteArray(), "UTF-8");
    }

    private static byte[] wav(short[] pcm) {
        int dataLen = pcm.length * 2, total = 36 + dataLen;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try {
            o.write(new byte[]{'R','I','F','F'}); le32(o, total);
            o.write(new byte[]{'W','A','V','E','f','m','t',' '});
            le32(o, 16); le16(o, 1); le16(o, 1);            // PCM, mono
            le32(o, SAMPLE_RATE); le32(o, SAMPLE_RATE * 2); // byte rate
            le16(o, 2); le16(o, 16);                        // block align, bits
            o.write(new byte[]{'d','a','t','a'}); le32(o, dataLen);
            for (short v : pcm) { o.write(v & 0xff); o.write((v >> 8) & 0xff); }
        } catch (Exception e) {}
        return o.toByteArray();
    }
    private static void le32(ByteArrayOutputStream o, int v) { o.write(v); o.write(v>>8); o.write(v>>16); o.write(v>>24); }
    private static void le16(ByteArrayOutputStream o, int v) { o.write(v); o.write(v>>8); }

    /* --------------------------------- TTS -------------------------------- */
    private void speakBlocking(String text) {
        if (text == null || text.isEmpty()) return;
        if (!ttsReady) { try { Thread.sleep(Math.min(4000, 300 + text.length() * 35)); } catch (Exception e) {} return; }
        final CountDownLatch latch = new CountDownLatch(1);
        speakLatch = latch;
        final String id = "u" + System.nanoTime();
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String s) {}
            @Override public void onDone(String s) { latch.countDown(); }
            @Override public void onError(String s) { latch.countDown(); }
        });
        speaking = true;
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id);
        try { latch.await(Math.max(8, text.length() / 8) + 8, TimeUnit.SECONDS); } catch (Exception e) {}
        speaking = false;
        speakLatch = null;
    }

    /** Tap-to-interrupt: stop the assistant talking and return to listening. */
    private void interruptSpeech() {
        try { if (tts != null) tts.stop(); } catch (Exception ignored) {}
        CountDownLatch l = speakLatch;
        if (l != null) l.countDown();   // unblock speakBlocking immediately
    }

    /* ------------------------------ UI bridge ----------------------------- */
    private void setOrb(final String state) { evalJs("window.setOrb && window.setOrb('" + state + "')"); }
    private void showUser(final String t) { evalJs("window.showUser && window.showUser(" + jsStr(t) + ")"); }
    private void showMeta(final String t) { evalJs("window.showMeta && window.showMeta(" + jsStr(t) + ")"); }
    private void evalJs(final String js) {
        ui.post(new Runnable() { @Override public void run() { if (webView != null) webView.evaluateJavascript(js, null); } });
    }
    private static String jsStr(String s) {
        if (s == null) s = "";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "") + "\"";
    }

    private void enterImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
}
