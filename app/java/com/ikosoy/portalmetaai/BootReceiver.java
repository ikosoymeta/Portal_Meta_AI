package com.ikosoy.portalmetaai;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Restarts the floating-orb overlay after a reboot. The app has no launcher icon
 * (it's launched from the orb), so the overlay is its entry point and must come
 * back on boot.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        try { c.startForegroundService(new Intent(c, OverlayService.class)); }
        catch (Exception ignored) {}
    }
}
