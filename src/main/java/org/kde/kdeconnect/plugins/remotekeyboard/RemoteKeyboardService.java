/*
 * SPDX-FileCopyrightText: 2017 Holger Kaelberer <holger.k@elberer.de>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.remotekeyboard;

import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import org.kde.kdeconnect.ui.MainActivity;
import org.kde.kdeconnect.ui.PluginSettingsActivity;
import org.kde.kdeconnect_tp.R;

import java.util.ArrayList;

public class RemoteKeyboardService extends InputMethodService {

    /**
     * Reference to our instance
     * null if this InputMethod is not currently selected.
     */
    public static RemoteKeyboardService instance = null;

    /**
     * Whether this InputMethod is currently visible.
     */
    public boolean visible = false;

    private SodutoRemoteKeyboardView inputView = null;

    Handler handler;

    void updateInputView() {
        if (inputView == null)
            return;
        inputView.updateConnectionStatus(RemoteKeyboardPlugin.isConnected());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        visible = false;
        instance = this;
        handler = new Handler();
        Log.d("RemoteKeyboardService", "Remote keyboard initialized");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        Log.d("RemoteKeyboardService", "Destroyed");
    }

    @Override
    public View onCreateInputView() {
        inputView = new SodutoRemoteKeyboardView(this);
        inputView.setOnKeyPressListener(this::onPress);
        updateInputView();
        return inputView;
    }

    @Override
    public void onStartInputView(EditorInfo attribute, boolean restarting) {
//        Log.d("RemoteKeyboardService", "onStartInputView");
        super.onStartInputView(attribute, restarting);
        visible = true;
        ArrayList<RemoteKeyboardPlugin> instances = RemoteKeyboardPlugin.acquireInstances();
        try {
            for (RemoteKeyboardPlugin i : instances)
                i.notifyKeyboardState(true);
        } finally {
            RemoteKeyboardPlugin.releaseInstances();
        }

        getWindow().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
//        Log.d("RemoteKeyboardService", "onFinishInputView");
        super.onFinishInputView(finishingInput);
        visible = false;
        ArrayList<RemoteKeyboardPlugin> instances = RemoteKeyboardPlugin.acquireInstances();
        try {
            for (RemoteKeyboardPlugin i : instances)
                i.notifyKeyboardState(false);
        } finally {
            RemoteKeyboardPlugin.releaseInstances();
        }

        getWindow().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    public void onPress(int primaryCode) {
        switch (primaryCode) {
            case 0: {  // "hide keyboard"
                requestHideSelf(0);
                break;
            }
            case 1: { // "settings"
                ArrayList<RemoteKeyboardPlugin> instances = RemoteKeyboardPlugin.acquireInstances();
                try {
                    if (instances.size() == 1) {  // single instance of RemoteKeyboardPlugin -> access its settings
                        RemoteKeyboardPlugin plugin = instances.get(0);
                        if (plugin != null) {
                            Intent intent = new Intent(this, PluginSettingsActivity.class);
                            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            intent.putExtra(PluginSettingsActivity.EXTRA_DEVICE_ID, plugin.getDeviceId());
                            intent.putExtra(PluginSettingsActivity.EXTRA_PLUGIN_KEY, plugin.getPluginKey());
                            startActivity(intent);
                        }
                    } else { // != 1 instance of plugin -> show main activity view
                        Intent intent = new Intent(this, MainActivity.class);
                        intent.putExtra(MainActivity.FLAG_FORCE_OVERVIEW, true);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        if (instances.isEmpty())
                            Toast.makeText(this, R.string.remotekeyboard_not_connected, Toast.LENGTH_SHORT).show();
                        else // instances.size() > 1
                            Toast.makeText(this, R.string.remotekeyboard_multiple_connections, Toast.LENGTH_SHORT).show();
                    }
                } finally {
                    RemoteKeyboardPlugin.releaseInstances();
                }
                break;
            }
            case 2: { // "keyboard"
                InputMethodManager imm = ContextCompat.getSystemService(this, InputMethodManager.class);
                imm.showInputMethodPicker();
                break;
            }
            case 3: { // "connected"?
                if (RemoteKeyboardPlugin.isConnected())
                    Toast.makeText(this, R.string.remotekeyboard_connected, Toast.LENGTH_SHORT).show();
                else
                    Toast.makeText(this, R.string.remotekeyboard_not_connected, Toast.LENGTH_SHORT).show();
                break;
            }
        }
    }

}
