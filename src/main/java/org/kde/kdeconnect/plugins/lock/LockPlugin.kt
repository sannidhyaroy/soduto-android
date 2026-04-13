/*
 * SPDX-FileCopyrightText: 2026 Sannidhya Roy <sannidhya@thenoton.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.lock

import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect_tp.R

@LoadablePlugin
class LockPlugin : Plugin() {

    var remoteIsLocked: Boolean? = null
        private set

    var remoteCanLock: Boolean? = null
        private set

    var remoteCanUnlock: Boolean? = null
        private set

    override val displayName: String
        get() = context.getString(R.string.pref_plugin_lock)

    override val description: String
        get() = context.getString(R.string.pref_plugin_lock_desc)

    override fun onCreate(): Boolean {
        val np = NetworkPacket(PACKET_TYPE_LOCK_REQUEST)
        np["requestLocked"] = true
        device.sendPacket(np)
        return true
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        remoteIsLocked = np.getBoolean("isLocked")
        if (np.has("canLock")) remoteCanLock = np.getBoolean("canLock")
        if (np.has("canUnlock")) remoteCanUnlock = np.getBoolean("canUnlock")
        device.onPluginsChanged()
        return true
    }

    fun sendSetLocked(locked: Boolean) {
        val np = NetworkPacket(PACKET_TYPE_LOCK_REQUEST)
        np["setLocked"] = locked
        device.sendPacket(np)
    }

    override fun getUiMenuEntries(): List<PluginUiMenuEntry> {
        val entries = mutableListOf<PluginUiMenuEntry>()
        // Show Lock only when the remote is not already locked AND it can be locked.
        // canLock absent → capability unknown → show (existing behaviour per protocol spec).
        if (remoteIsLocked != true && remoteCanLock != false) {
            entries.add(PluginUiMenuEntry(context.getString(R.string.lock_plugin_action_lock)) {
                if (isDeviceInitialized) sendSetLocked(true)
            })
        }
        // Show Unlock only when the remote is not already unlocked AND it can be unlocked.
        // Android (and most platforms) report canUnlock = false, so this entry is normally hidden.
        if (remoteIsLocked != false && remoteCanUnlock != false) {
            entries.add(PluginUiMenuEntry(context.getString(R.string.lock_plugin_action_unlock)) {
                if (isDeviceInitialized) sendSetLocked(false)
            })
        }
        return entries
    }

    override val supportedPacketTypes: Array<String> = arrayOf(PACKET_TYPE_LOCK)
    override val outgoingPacketTypes: Array<String> = arrayOf(PACKET_TYPE_LOCK_REQUEST)

    companion object {
        private const val PACKET_TYPE_LOCK = "kdeconnect.lock"
        private const val PACKET_TYPE_LOCK_REQUEST = "kdeconnect.lock.request"
    }
}
