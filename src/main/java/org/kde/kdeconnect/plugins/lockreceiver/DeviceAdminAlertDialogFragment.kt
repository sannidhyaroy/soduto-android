/*
 * SPDX-FileCopyrightText: 2026 Sannidhya Roy <sannidhya@thenoton.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.lockreceiver

import android.app.Dialog
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.kde.kdeconnect.ui.MainActivity
import org.kde.kdeconnect_tp.R

class DeviceAdminAlertDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.pref_plugin_lockreceiver)
            .setMessage(R.string.lockreceiver_device_admin_permission_explanation)
            .setPositiveButton(R.string.lockreceiver_device_admin_grant) { _, _ ->
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(
                        DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                        KdeConnectDeviceAdminReceiver.getComponentName(requireContext())
                    )
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        getString(R.string.lockreceiver_device_admin_permission_explanation)
                    )
                }
                @Suppress("DEPRECATION")
                requireActivity().startActivityForResult(intent, MainActivity.RESULT_NEEDS_RELOAD)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
    }

    companion object {
        fun create(): DeviceAdminAlertDialogFragment = DeviceAdminAlertDialogFragment()
    }
}
