/*
 * SPDX-FileCopyrightText: 2026 Sannidhya Roy <sannidhya@thenoton.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.lockreceiver

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.fragment.app.DialogFragment
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect_tp.R

@LoadablePlugin
class LockReceiverPlugin : Plugin() {

    private val keyguardManager by lazy {
        context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    }

    private val lockStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            sendLockState()
        }
    }

    override val displayName: String
        get() = context.getString(R.string.pref_plugin_lockreceiver)

    override val description: String
        get() = context.getString(R.string.pref_plugin_lockreceiver_desc)

    override fun onCreate(): Boolean {
        sendLockState()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        context.registerReceiver(lockStateReceiver, filter)
        return true
    }

    override fun onDestroy() {
        context.unregisterReceiver(lockStateReceiver)
        super.onDestroy()
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        if (np.getBoolean("requestLocked", false)) {
            sendLockState()
        }
        if (np.has("setLocked") && np.getBoolean("setLocked")) {
            lockDevice()
        }
        return true
    }

    private fun sendLockState() {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val np = NetworkPacket(PACKET_TYPE_LOCK)
        np["isLocked"] = keyguardManager.isDeviceLocked
        np["canLock"] = dpm.isAdminActive(KdeConnectDeviceAdminReceiver.getComponentName(context))
        np["canUnlock"] = false
        device.sendPacket(np)
    }

    private fun lockDevice() {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isAdminActive(KdeConnectDeviceAdminReceiver.getComponentName(context))) {
            dpm.lockNow()
        }
    }

    override fun checkRequiredPermissions(): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(KdeConnectDeviceAdminReceiver.getComponentName(context))
    }

    override fun loadPluginWhenRequiredPermissionsMissing(): Boolean = true

    override val permissionExplanationDialog: DialogFragment
        get() = DeviceAdminAlertDialogFragment.create()

    override val supportedPacketTypes: Array<String> = arrayOf(PACKET_TYPE_LOCK_REQUEST)
    override val outgoingPacketTypes: Array<String> = arrayOf(PACKET_TYPE_LOCK)

    companion object {
        private const val PACKET_TYPE_LOCK = "kdeconnect.lock"
        private const val PACKET_TYPE_LOCK_REQUEST = "kdeconnect.lock.request"
    }
}
