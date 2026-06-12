/*
 * SPDX-FileCopyrightText: 2026 Sannidhya Roy <sannidhya@thenoton.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.sms

import android.content.Context
import android.os.PowerManager
import com.klinker.android.logger.Log

/**
 * Soduto extension: wrap SMS database queries in a partial wake lock so that doze mode
 * doesn't stall conversation fetches for minutes.
 *
 * **The problem.** KDE Connect's background service is a foreground service, so the
 * process itself isn't killed in doze. But the worker threads spawned via
 * `ThreadHelper.execute { ... }` run at NORMAL priority, and in doze the kernel
 * aggressively throttles non-foreground-priority CPU + I/O. The SMS conversation handler
 * in [SMSPlugin.handleRequestAllConversations] runs as a sequence of one-DB-query-per-
 * thread — heavy SMS users (8+ years of history, OTPs, DLT sender IDs, marketing) can
 * easily have 50+ unique threads, so 50+ throttled queries serially can take minutes.
 *
 * Symptomatically, the desktop sits on its "Loading conversations…" spinner indefinitely
 * until the user wakes the phone screen (which exits doze and unblocks the queries).
 *
 * **The fix.** Acquire a `PARTIAL_WAKE_LOCK` for the duration of the query work. Partial
 * wake locks force the CPU awake (no screen) and lift the throttling, letting the
 * sequence of queries complete in the seconds it would normally take.
 *
 * **Cost.** Negligible. The lock is held only for the duration of the request (typically
 * a few seconds), is auto-released by [withWakeLock]'s `finally` block, and has a hard
 * timeout so a stuck handler can't drain the battery. Apps that already use partial
 * wake locks responsibly (e.g. media playback, navigation) demonstrate this pattern.
 *
 * **Reversibility.** Single-purpose helper used at three call sites in `SMSPlugin.kt`.
 * When upstream KDE Connect Android adopts an equivalent mechanism, delete this file
 * and unwrap the three call sites.
 */
internal object SodutoSMSDozeHelper {
    /**
     * Diagnostic tag visible in `dumpsys power`. Must start with the app's package
     * identifier per Android's WakeLock tag conventions, so it's clearly attributable
     * to Soduto in system logs.
     */
    private const val WAKE_LOCK_TAG: String = "Soduto:SMSQuery"

    /**
     * Safety cap on how long any single SMS-query block can hold the wake lock. The
     * normal case completes in well under a second; this is a defence against a stuck
     * DB call accidentally pinning the CPU awake forever. If a real query ever exceeds
     * this, the lock is force-released and the query continues at degraded (throttled)
     * priority — same behaviour as before this fix, so no regression.
     */
    private const val WAKE_LOCK_TIMEOUT_MS: Long = 30_000L

    /**
     * Execute [block] with a partial wake lock held for its duration. The lock is
     * always released — including on exception — so callers don't need a `try/finally`.
     *
     * Inlined so the lambda allocation disappears and the call site reads as if the
     * lock acquire/release were inlined manually. Safe to call from any thread.
     */
    inline fun <T> withWakeLock(context: Context, block: () -> T): T {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        try {
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        } catch (t: Throwable) {
            // Acquiring should never fail with a valid PowerManager, but be defensive:
            // if it somehow does, run the block without the lock rather than crashing
            // the SMS plugin (degrades to pre-fix behaviour).
            Log.e("SodutoSMSDoze", "Failed to acquire partial wake lock; running without doze protection", t)
            return block()
        }
        try {
            return block()
        } finally {
            if (wakeLock.isHeld) {
                try {
                    wakeLock.release()
                } catch (t: Throwable) {
                    // Already-released or null state — log and move on.
                    Log.e("SodutoSMSDoze", "Failed to release partial wake lock", t)
                }
            }
        }
    }
}
