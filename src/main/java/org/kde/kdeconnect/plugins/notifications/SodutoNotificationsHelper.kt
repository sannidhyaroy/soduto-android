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

    /**
     * true  → layered: solid background + scaled foreground + rounded rect clip.
     *         Looks like a macOS app icon — opaque, clean shape.
     * false → system mask: AdaptiveIconDrawable.draw() applies the device's icon shape
     *         (Samsung squircle, stock squircle, etc.) with transparent corners.
     *         Matches exactly what the Android launcher shows.
     */
    private const val USE_LAYERED_ADAPTIVE_ICONS = true

    /**
     * Extracts the best available icon for a notification, in priority order:
     * 1. Large icon (contact photo, album art) — most contextually rich
     * 2. App launcher icon — full-color, always recognizable
     * 3. Small icon — last resort; monochrome status bar template
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
        val packageName = statusBarNotification.packageName
        return try {
            val foreignContext = context.createPackageContext(packageName, 0)

            notification.getLargeIcon()?.loadDrawable(foreignContext)
                ?.let { drawableToBitmap(it) }
                ?.let { return it }

            try {
                val launcherIcon = context.packageManager.getApplicationIcon(packageName)
                (adaptiveToBitmap(launcherIcon) ?: drawableToBitmap(launcherIcon))
                    ?.let { return it }
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Could not load launcher icon for $packageName", e)
            }

            notification.smallIcon?.loadDrawable(foreignContext)
                ?.let { drawableToBitmap(it) }

        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Package not found: $packageName", e)
            null
        }
    }

    /**
     * Layered compositing for AdaptiveIconDrawable.
     * Returns null when USE_LAYERED_ADAPTIVE_ICONS is false or the drawable is not adaptive,
     * so the caller falls back to drawableToBitmap (system mask path).
     *
     * Foreground is scaled up by 108/72 so its safe-zone content fills the full canvas,
     * matching the background layer visually.
     */
    private fun adaptiveToBitmap(drawable: Drawable): Bitmap? {
        if (!USE_LAYERED_ADAPTIVE_ICONS) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || drawable !is AdaptiveIconDrawable) return null
        val size = 96
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        canvas.clipPath(Path().apply {
            addRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.2f, size * 0.2f, Path.Direction.CW)
        })
        drawable.background?.apply { setBounds(0, 0, size, size); draw(canvas) }
        // Scale foreground up by 108/72 so the safe-zone content fills the background
        val inset = (size * (108f / 72f - 1f) / 2).toInt()
        drawable.foreground?.apply { setBounds(-inset, -inset, size + inset, size + inset); draw(canvas) }
        return bitmap
    }
}
