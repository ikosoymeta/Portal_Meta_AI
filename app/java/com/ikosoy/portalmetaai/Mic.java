package com.ikosoy.portalmetaai;

import android.os.SystemClock;

/**
 * Process-wide microphone lock. The foreground Activity's voice worker and the
 * background OverlayService wake-listener both record audio in the same process;
 * only one may hold an AudioRecord at a time (AudioFlinger errors otherwise).
 * Each side calls acquire() before creating its AudioRecord and release() after.
 */
final class Mic {
    private static final Object LOCK = new Object();
    private static boolean busy = false;

    static boolean acquire(long timeoutMs) {
        long end = SystemClock.elapsedRealtime() + timeoutMs;
        synchronized (LOCK) {
            while (busy) {
                long rem = end - SystemClock.elapsedRealtime();
                if (rem <= 0) return false;
                try { LOCK.wait(rem); } catch (InterruptedException e) { return false; }
            }
            busy = true;
            return true;
        }
    }

    static void release() {
        synchronized (LOCK) {
            busy = false;
            LOCK.notifyAll();
        }
    }

    private Mic() {}
}
