package com.ikosoy.portalmetaai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

/**
 * A floating "Meta AI" orb drawn over the Home / App pages (a system overlay).
 * Tapping it OPENS the assistant foreground in idle-listening mode, where you can
 * say "Hi Meta" (foreground mic) or tap the orb to start. It's draggable and shows
 * when the assistant is backgrounded (ACTION_SHOW), hidden when foreground (HIDE).
 *
 * Background "Hi Meta" wake is NOT possible on the Portal: it delivers silence
 * (rms=0) to a backgrounded third-party app's mic and reserves background hotword
 * for its privileged built-in assistant. So the orb is a one-tap launcher; once the
 * assistant screen is foreground, voice ("Hi Meta" / "Meta Stop" / "Meta Go Home")
 * works hands-free.
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

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        startForeground(7, buildNotification());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        buildOrb();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if (ACTION_HIDE.equals(a)) removeOrb();   // assistant is foreground
        else addOrb();                            // backgrounded / boot
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
                        if (!moved) openAssistant();
                        return true;
                }
                return false;
            }
        });
    }

    // Open the assistant foreground in idle-listening mode (say "Hi Meta" or tap the orb).
    private void openAssistant() {
        try {
            Intent i = new Intent(this, MainActivity.class);
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
        removeOrb();
        super.onDestroy();
    }
}
