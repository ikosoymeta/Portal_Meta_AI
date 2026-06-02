package com.ikosoy.portalmetaai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
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

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        startForeground(7, buildNotification());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        buildOrb();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if (ACTION_SHOW.equals(a)) addOrb();
        else if (ACTION_HIDE.equals(a)) removeOrb();
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
        int margin = Math.round(18 * dm.density);

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
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = Math.max(0, dm.widthPixels - size - margin);
        lp.y = Math.max(0, dm.heightPixels - size - margin * 3);

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
        removeOrb();
        super.onDestroy();
    }
}
