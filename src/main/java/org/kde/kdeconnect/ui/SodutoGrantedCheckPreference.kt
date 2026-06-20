/*
 * SPDX-FileCopyrightText: 2026 Sannidhya Roy <sannidhya@thenoton.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import org.kde.kdeconnect_tp.R

/**
 * A settings row that shows a muted trailing checkmark once its action is already granted, while
 * staying tappable so the user can re-open system settings to review or revoke it. Meant for
 * "special access" permissions (full-screen intent, battery optimization) that the runtime-permission
 * dashboard can't represent.
 */
class SodutoGrantedCheckPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs) {

    var isGranted: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyChanged()
            }
        }

    init {
        widgetLayoutResource = R.layout.preference_widget_granted_check
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.findViewById(R.id.granted_check)?.visibility = if (isGranted) View.VISIBLE else View.GONE
    }
}
