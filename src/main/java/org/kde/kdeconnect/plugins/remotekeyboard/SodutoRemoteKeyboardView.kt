/*
 * SPDX-FileCopyrightText: 2026 Sannidhya Roy <sannidhya@thenoton.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.remotekeyboard

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import org.kde.kdeconnect_tp.R

/**
 * Material 3 replacement for the deprecated KeyboardView control strip.
 * Renders 4 icon buttons: hide keyboard, settings, switch keyboard, connection status.
 * Context is wrapped with KdeConnectTheme so Material theme attributes resolve correctly
 * inside the InputMethodService window.
 */
internal class SodutoRemoteKeyboardView(context: Context) :
    LinearLayout(ContextThemeWrapper(context, R.style.KdeConnectTheme)) {

    fun interface OnKeyPressListener {
        fun onPress(code: Int)
    }

    private val btnConnectionStatus: MaterialButton

    var onKeyPressListener: OnKeyPressListener? = null

    init {
        LayoutInflater.from(getContext()).inflate(R.layout.soduto_remote_keyboard_input_view, this, true)

        findViewById<MaterialButton>(R.id.btn_hide_keyboard).setOnClickListener { onKeyPressListener?.onPress(0) }
        findViewById<MaterialButton>(R.id.btn_settings).setOnClickListener { onKeyPressListener?.onPress(1) }
        findViewById<MaterialButton>(R.id.btn_switch_keyboard).setOnClickListener { onKeyPressListener?.onPress(2) }

        btnConnectionStatus = findViewById(R.id.btn_connection_status)
        btnConnectionStatus.setOnClickListener { onKeyPressListener?.onPress(3) }
    }

    fun updateConnectionStatus(connected: Boolean) {
        btnConnectionStatus.setIconResource(
            if (connected) R.drawable.ic_phonelink_36dp else R.drawable.ic_phonelink_off_36dp
        )
    }
}
