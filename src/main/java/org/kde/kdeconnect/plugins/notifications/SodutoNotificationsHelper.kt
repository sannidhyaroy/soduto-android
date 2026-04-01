/*
 * SPDX-FileCopyrightText: 2026 Sannidhya Roy <sannidhya@thenoton.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.notifications

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.graphics.createBitmap

internal object SodutoNotificationsHelper {

    private const val TAG = "Soduto/NotificationsPlugin"

    const val PREFS_ICON = "soduto_notification_icons"
    const val PREF_ICON_ENABLED = "icon_enabled"
    const val PREF_ICON_LARGE = "icon_large"
    const val PREF_ICON_LAUNCHER = "icon_launcher"
    const val PREF_ICON_SMALL = "icon_small"
    const val PREF_ICON_DEVICE_SHAPE = "icon_device_shape"

    /**
     * Extracts the best available icon for a notification, respecting user preferences
     * for which hierarchy levels are active and in what order:
     * 1. Large icon (contact photo, album art) — most contextually rich
     * 2. App launcher icon — full-color, always recognizable
     * 3. Small icon — last resort; monochrome status bar template
     *
     * Each level can be disabled individually via notification filter settings.
     * Icon rendering style (layered rounded square vs device shape) is also user-controlled.
     *
     * @param drawableToBitmap passed in from NotificationsPlugin so we never duplicate
     *                         that logic — upstream improvements flow through automatically
     */
    @JvmStatic
    fun extractIcon(
        context: Context,
        statusBarNotification: StatusBarNotification,
        notification: Notification,
        drawableToBitmap: (Drawable) -> Bitmap?
    ): Bitmap? {
        val prefs = context.getSharedPreferences(PREFS_ICON, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_ICON_ENABLED, true)) return null
        val includeLarge = prefs.getBoolean(PREF_ICON_LARGE, true)
        val includeLauncher = prefs.getBoolean(PREF_ICON_LAUNCHER, true)
        val includeSmall = prefs.getBoolean(PREF_ICON_SMALL, true)
        val useDeviceShape = prefs.getBoolean(PREF_ICON_DEVICE_SHAPE, false)

        val packageName = statusBarNotification.packageName
        return try {
            val foreignContext = context.createPackageContext(packageName, 0)

            if (includeLarge) {
                notification.getLargeIcon()?.loadDrawable(foreignContext)
                    ?.let { drawableToBitmap(it) }
                    ?.let { return it }
            }

            if (includeLauncher) {
                try {
                    val launcherIcon = context.packageManager.getApplicationIcon(packageName)
                    (adaptiveToBitmap(launcherIcon, useDeviceShape) ?: drawableToBitmap(launcherIcon))
                        ?.let { return it }
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "Could not load launcher icon for $packageName", e)
                }
            }

            if (includeSmall) {
                notification.smallIcon?.loadDrawable(foreignContext)
                    ?.let { drawableToBitmap(it) }
            } else null

        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Package not found: $packageName", e)
            null
        }
    }

    /**
     * Layered compositing for AdaptiveIconDrawable.
     * Returns null when useDeviceShape is true (caller falls back to drawableToBitmap,
     * which applies the device's system icon mask via AdaptiveIconDrawable.draw()).
     *
     * Foreground is scaled up by 108/72 so its safe-zone content fills the full canvas,
     * matching the background layer visually.
     */
    private fun adaptiveToBitmap(drawable: Drawable, useDeviceShape: Boolean): Bitmap? {
        if (useDeviceShape) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || drawable !is AdaptiveIconDrawable) return null
        val size = 96
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        canvas.clipPath(Path().apply {
            addRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.2f, size * 0.2f, Path.Direction.CW)
        })
        drawable.background?.apply { setBounds(0, 0, size, size); draw(canvas) }
        val inset = (size * (108f / 72f - 1f) / 2).toInt()
        drawable.foreground?.apply { setBounds(-inset, -inset, size + inset, size + inset); draw(canvas) }
        return bitmap
    }
}
