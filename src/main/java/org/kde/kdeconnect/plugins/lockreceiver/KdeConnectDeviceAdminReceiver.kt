/*
 * SPDX-FileCopyrightText: 2026 Sannidhya Roy <sannidhya@thenoton.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.lockreceiver

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context

class KdeConnectDeviceAdminReceiver : DeviceAdminReceiver() {
    companion object {
        fun getComponentName(context: Context): ComponentName =
            ComponentName(context, KdeConnectDeviceAdminReceiver::class.java)
    }
}
