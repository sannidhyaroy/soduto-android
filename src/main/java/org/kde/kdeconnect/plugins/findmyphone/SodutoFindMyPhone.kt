/*
 * SPDX-FileCopyrightText: 2026 Sannidhya Roy <sannidhya@thenoton.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.findmyphone

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Soduto-specific Find My Phone helpers, kept out of the upstream plugin so they can be dropped
 * cleanly if upstream ever ships equivalents.
 *
 * Full-screen-intent access (Android 14+) is what makes the screen-off ring behave like an
 * incoming call: without it the locked-screen ring silently degrades to an ordinary notification.
 * (The other reliability lever — exempting the app from battery optimization so the connection
 * survives Doze — is app-wide, so it lives in the global Settings screen, not here.)
 */
object SodutoFindMyPhone {
    /** How long the device keeps ringing before stopping itself, à la Google Find My Device. */
    const val AUTO_STOP_MS: Long = 5 * 60 * 1000L

    /** Full-screen-intent access is auto-granted before Android 14; after that it can be revoked. */
    @JvmStatic
    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val nm = ContextCompat.getSystemService(context, NotificationManager::class.java)
        return nm == null || nm.canUseFullScreenIntent()
    }

    @JvmStatic
    fun fullScreenIntentSettings(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            Uri.fromParts("package", context.packageName, null),
        )
}

/**
 * Stops the ring after [SodutoFindMyPhone.AUTO_STOP_MS] so a lost device can't ring forever,
 * mirroring Google Find My Device. Posts on the main thread; safe to schedule/cancel repeatedly.
 */
class SodutoRingTimeout(private val onElapsed: Runnable) {
    private val handler = Handler(Looper.getMainLooper())
    private val runnable = Runnable { onElapsed.run() }

    fun schedule() {
        cancel()
        handler.postDelayed(runnable, SodutoFindMyPhone.AUTO_STOP_MS)
    }

    fun cancel() {
        handler.removeCallbacks(runnable)
    }
}

/**
 * Silences the ring when the user attends to the device, the way pressing power silences an
 * incoming call. Watches the screen power state (and unlocks) while ringing; pressing power toggles
 * the screen, which is the one button press we can observe from the background.
 *
 * The alarm's own wake lock turns the screen on the moment ringing starts, so we ignore screen
 * events for a brief grace period before arming, otherwise we'd silence ourselves instantly.
 */
class SodutoRingStopper(
    private val context: Context,
    private val onStop: Runnable,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var armed = false
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (armed) onStop.run()
        }
    }

    fun start() {
        if (registered) return
        armed = false
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        handler.postDelayed({ armed = true }, GRACE_MS)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        armed = false
        if (registered) {
            context.unregisterReceiver(receiver)
            registered = false
        }
    }

    private companion object {
        /** Long enough to swallow the wake lock's initial ACTION_SCREEN_ON, short enough to feel instant. */
        const val GRACE_MS = 1000L
    }
}
