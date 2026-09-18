package com.ikosoy.portalmetaai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

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
 * It also swaps itself for a Home button over apps that have no way out. Muse is
 * the case that forced this: it is built from fbsource rather than being one of
 * our tiles, so there is nowhere to add a Home button inside it, and the Portal
 * has no system back affordance in a fullscreen app. Over those apps the orb is
 * the wrong offer anyway, since Meta AI is not what you want from inside Muse.
 *
 * Needs the SYSTEM_ALERT_WINDOW app-op, plus GET_USAGE_STATS for the foreground
 * watch (both granted via adb in scripts/deploy.sh).
 */
public class OverlayService extends Service {

    public static final String ACTION_SHOW = "show";
    public static final String ACTION_HIDE = "hide";
    private static final String CHANNEL = "metaai_overlay";

    /** Apps where a Home button replaces the orb. */
    private static final Set<String> HOME_PILL_PKGS =
            new HashSet<>(Arrays.asList("com.facebook.aura"));
    private static final long WATCH_MS = 2000;
    /** Remembers where you dragged the pill to. */
    private static final String PREFS = "home_overlay";
    private static final String KEY_X = "pill_x";
    private static final String KEY_Y = "pill_y";
    /** Past this much travel it is a drag, not a tap. */
    private static final int DRAG_SLOP = 14;

    private WindowManager wm;
    private ImageView orb;
    private WindowManager.LayoutParams lp;
    private boolean added = false;

    private View pill;
    /** True while the assistant screen itself is in front, which hides both. */
    private boolean assistantForeground = false;
    private final Handler watch = new Handler(Looper.getMainLooper());

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        startForeground(7, buildNotification());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        buildOrb();
        watch.postDelayed(watcher, WATCH_MS);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if (ACTION_HIDE.equals(a)) {
            assistantForeground = true;
            removeOrb();                          // assistant is foreground
            removePill();
        } else {
            assistantForeground = false;
            addOrb();                             // backgrounded / boot
        }
        return START_STICKY;
    }

    /**
     * Decide which overlay belongs on screen, from whichever app is in front.
     *
     * An unreadable foreground is treated as no news rather than a reason to
     * change anything: without the usage-stats grant this returns null forever,
     * and flipping on that would strip the orb off every screen.
     */
    private final Runnable watcher = new Runnable() {
        @Override
        public void run() {
            String fg = foregroundPackage();
            if (fg != null && !assistantForeground) {
                if (HOME_PILL_PKGS.contains(fg)) {
                    removeOrb();
                    addPill();
                } else {
                    removePill();
                    addOrb();
                }
            }
            watch.postDelayed(this, WATCH_MS);
        }
    };

    private String foregroundPackage() {
        try {
            UsageStatsManager usm =
                    (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) {
                return null;
            }
            long now = System.currentTimeMillis();
            UsageEvents ev = usm.queryEvents(now - 10000, now);
            UsageEvents.Event e = new UsageEvents.Event();
            String last = null;
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    last = e.getPackageName();
                }
            }
            return last;
        } catch (Exception ex) {
            return null; // no permission: leave whatever is showing alone
        }
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

    /** The Home button shown in place of the orb. Same look as the tile pills. */
    private void addPill() {
        if (pill != null) {
            return;
        }
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();

            TextView tv = new TextView(this);
            tv.setText("⌂  Home");
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(15);
            tv.setPadding(34, 18, 34, 18);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#cc0a0a14"));
            bg.setCornerRadius(28f);
            bg.setStroke(2, Color.parseColor("#55ffffff"));
            tv.setBackground(bg);

            final WindowManager.LayoutParams plp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    // Not focusable, so it never steals input from the app
                    // underneath, but still tappable itself.
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            plp.gravity = Gravity.TOP | Gravity.START;
            final SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            plp.x = prefs.getInt(KEY_X, Math.round(dm.widthPixels * 0.80f));
            plp.y = prefs.getInt(KEY_Y, Math.round(dm.heightPixels * 0.11f));

            makeDraggable(tv, plp, prefs);

            wm.addView(tv, plp);
            pill = tv;
            // Nudge a first-run pill fully inside the right edge once its width
            // is known; before layout there is nothing to subtract.
            if (!prefs.contains(KEY_X)) {
                tv.post(new Runnable() {
                    @Override public void run() {
                        try {
                            plp.x = Math.max(0, dm.widthPixels - pill.getWidth()
                                    - Math.round(dm.widthPixels * 0.012f));
                            wm.updateViewLayout(pill, plp);
                        } catch (Exception ignored) {}
                    }
                });
            }
        } catch (Exception e) {
            // Almost always a missing SYSTEM_ALERT_WINDOW grant. Losing the
            // button is survivable; taking the service down is not, because the
            // orb still has work to do on every other screen.
            pill = null;
        }
    }

    private void removePill() {
        if (pill == null) return;
        try { wm.removeView(pill); } catch (Exception ignored) {}
        pill = null;
    }

    /**
     * Tap to go home, drag to reposition.
     *
     * One touch listener rather than a click listener plus a drag listener,
     * because the two have to agree on which gesture happened: anything that
     * moves less than DRAG_SLOP counts as a tap, everything else moves the pill
     * and is remembered.
     */
    private void makeDraggable(final View v, final WindowManager.LayoutParams plp,
            final SharedPreferences prefs) {
        v.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY; int baseX, baseY; boolean moved;
            @Override public boolean onTouch(View view, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY();
                        baseX = plp.x; baseY = plp.y; moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - downX), dy = (int) (e.getRawY() - downY);
                        if (Math.abs(dx) + Math.abs(dy) > DRAG_SLOP) moved = true;
                        plp.x = baseX + dx; plp.y = baseY + dy;
                        try { wm.updateViewLayout(view, plp); } catch (Exception ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) {
                            prefs.edit().putInt(KEY_X, plp.x).putInt(KEY_Y, plp.y).apply();
                        } else {
                            goHome();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void goHome() {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {}
    }

    @Override public void onDestroy() {
        watch.removeCallbacksAndMessages(null);
        removeOrb();
        removePill();
        super.onDestroy();
    }
}
