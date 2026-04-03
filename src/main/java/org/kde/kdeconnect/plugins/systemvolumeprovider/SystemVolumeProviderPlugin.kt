/*
 * SPDX-FileCopyrightText: 2026 Sannidhya Roy <sannidhya@thenoton.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.systemvolumeprovider

import android.app.Activity
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect.ui.PluginSettingsFragment
import org.kde.kdeconnect_tp.R

@LoadablePlugin
class SystemVolumeProviderPlugin : Plugin() {

    private val PACKET_TYPE_SYSTEMVOLUME = "kdeconnect.systemvolume"
    private val PACKET_TYPE_SYSTEMVOLUME_REQUEST = "kdeconnect.systemvolume.request"

    private lateinit var sinksProvider: AndroidSinksProvider

    override fun onCreate(): Boolean {
        sinksProvider = AndroidSinksProvider(context) { np -> device.sendPacket(np) }
        sinksProvider.start()
        device.sendPacket(sinksProvider.buildSinkListPacket())
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::sinksProvider.isInitialized) sinksProvider.stop()
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        if (np.getBoolean("requestSinks", false)) {
            device.sendPacket(sinksProvider.buildSinkListPacket())
            return true
        }
        if (np.has("name")) {
            val name = np.getString("name", "")
            if (np.has("volume")) sinksProvider.handleVolumeChange(name, np.getInt("volume"))
            if (np.has("muted")) sinksProvider.handleMuteChange(name, np.getBoolean("muted"))
        }
        return true
    }

    override fun hasSettings(): Boolean = true

    override fun getSettingsFragment(activity: Activity): PluginSettingsFragment =
        SystemVolumeProviderSettingsFragment.newInstance(pluginKey)

    override val displayName: String
        get() = context.resources.getString(R.string.pref_plugin_systemvolumeprovider)

    override val description: String
        get() = context.resources.getString(R.string.pref_plugin_systemvolumeprovider_desc)

    override val supportedPacketTypes: Array<String> = arrayOf(PACKET_TYPE_SYSTEMVOLUME_REQUEST)
    override val outgoingPacketTypes: Array<String> = arrayOf(PACKET_TYPE_SYSTEMVOLUME)
}
