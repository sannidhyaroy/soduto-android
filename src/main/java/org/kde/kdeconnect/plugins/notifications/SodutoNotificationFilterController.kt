/*
 * SPDX-FileCopyrightText: 2026 Sannidhya Roy <sannidhya@thenoton.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.notifications

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.util.TypedValue
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.materialswitch.MaterialSwitch
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect_tp.databinding.ActivityNotificationFilterBinding

/**
 * Owns the notification icon two-target row and its bottom sheet configuration UI.
 * Extracted here to keep NotificationFilterActivity.java changes minimal for rebasing.
 */
internal class SodutoNotificationFilterController(
    private val activity: Activity,
    private val binding: ActivityNotificationFilterBinding
) {

    // Held while the icon settings bottom sheet is open; null otherwise.
    private var sheetIconLarge: MaterialSwitch? = null
    private var sheetIconLauncher: MaterialSwitch? = null
    private var sheetIconSmall: MaterialSwitch? = null

    // True while syncMasterSwitch is programmatically setting the master switch.
    private var syncingMaster = false

    fun configure() {
        val prefs = activity.getSharedPreferences(SodutoNotificationsHelper.PREFS_ICON, Context.MODE_PRIVATE)
        val initialEnabled = prefs.getBoolean(SodutoNotificationsHelper.PREF_ICON_ENABLED, true)
        binding.smIconsEnabled.isChecked = initialEnabled
        setIconRowDividerColor(initialEnabled)
        binding.smIconsEnabled.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(SodutoNotificationsHelper.PREF_ICON_ENABLED, isChecked).apply()
            setIconRowDividerColor(isChecked)
            if (!syncingMaster) {
                // User manually toggled master — push same state to all 3 children.
                prefs.edit()
                    .putBoolean(SodutoNotificationsHelper.PREF_ICON_LARGE, isChecked)
                    .putBoolean(SodutoNotificationsHelper.PREF_ICON_LAUNCHER, isChecked)
                    .putBoolean(SodutoNotificationsHelper.PREF_ICON_SMALL, isChecked)
                    .apply()
                sheetIconLarge?.isChecked = isChecked
                sheetIconLauncher?.isChecked = isChecked
                sheetIconSmall?.isChecked = isChecked
            }
        }
        binding.iconSettingsTitleArea.setOnClickListener { showIconSettingsBottomSheet() }
    }

    private fun setIconRowDividerColor(enabled: Boolean) {
        val tv = TypedValue()
        activity.theme.resolveAttribute(
            if (enabled) androidx.appcompat.R.attr.colorPrimary
            else com.google.android.material.R.attr.colorOutlineVariant,
            tv, true
        )
        binding.iconRowDivider.setBackgroundColor(tv.data)
    }

    private fun showIconSettingsBottomSheet() {
        val dialog = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_notification_icon_settings, null)
        dialog.setContentView(view)

        val prefs = activity.getSharedPreferences(SodutoNotificationsHelper.PREFS_ICON, Context.MODE_PRIVATE)

        sheetIconLarge = view.findViewById(R.id.smIconLarge)
        sheetIconLauncher = view.findViewById(R.id.smIconLauncher)
        sheetIconSmall = view.findViewById(R.id.smIconSmall)
        val smIconDeviceShape = view.findViewById<MaterialSwitch>(R.id.smIconDeviceShape)

        sheetIconLarge!!.isChecked = prefs.getBoolean(SodutoNotificationsHelper.PREF_ICON_LARGE, true)
        sheetIconLauncher!!.isChecked = prefs.getBoolean(SodutoNotificationsHelper.PREF_ICON_LAUNCHER, true)
        sheetIconSmall!!.isChecked = prefs.getBoolean(SodutoNotificationsHelper.PREF_ICON_SMALL, true)
        smIconDeviceShape.isChecked = prefs.getBoolean(SodutoNotificationsHelper.PREF_ICON_DEVICE_SHAPE, false)

        sheetIconLarge!!.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(SodutoNotificationsHelper.PREF_ICON_LARGE, isChecked).apply()
            syncMasterSwitch(prefs)
        }
        sheetIconLauncher!!.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(SodutoNotificationsHelper.PREF_ICON_LAUNCHER, isChecked).apply()
            syncMasterSwitch(prefs)
        }
        sheetIconSmall!!.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(SodutoNotificationsHelper.PREF_ICON_SMALL, isChecked).apply()
            syncMasterSwitch(prefs)
        }
        smIconDeviceShape.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(SodutoNotificationsHelper.PREF_ICON_DEVICE_SHAPE, isChecked).apply()
        }

        dialog.setOnDismissListener {
            sheetIconLarge = null
            sheetIconLauncher = null
            sheetIconSmall = null
        }

        dialog.show()
    }

    private fun syncMasterSwitch(prefs: SharedPreferences) {
        val anyEnabled = prefs.getBoolean(SodutoNotificationsHelper.PREF_ICON_LARGE, true)
                || prefs.getBoolean(SodutoNotificationsHelper.PREF_ICON_LAUNCHER, true)
                || prefs.getBoolean(SodutoNotificationsHelper.PREF_ICON_SMALL, true)
        val masterCurrently = binding.smIconsEnabled.isChecked
        if (anyEnabled == masterCurrently) return
        syncingMaster = true
        binding.smIconsEnabled.isChecked = anyEnabled
        syncingMaster = false
        prefs.edit().putBoolean(SodutoNotificationsHelper.PREF_ICON_ENABLED, anyEnabled).apply()
    }
}
