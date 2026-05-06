/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 * SPDX-FileCopyrightText: 2021 Ilmaz Gumerov <ilmaz1309@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/
package org.kde.kdeconnect.plugins.clipboard

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.kde.kdeconnect.helpers.ThreadHelper.execute
import org.kde.kdeconnect_tp.BuildConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ClipboardListener {
    interface ClipboardObserver {
        fun clipboardChanged(content: String)
    }

    private val observers: HashSet<ClipboardObserver> = HashSet()

    private val context: Context
    private lateinit var prefs: SharedPreferences

    var currentContent: String? = null
        private set
    var updateTimestamp: Long = 0
        private set

    // cm is initialised synchronously so setText() can use it immediately.
    // Only the listener registration is deferred to the main thread (so callbacks
    // fire there), which avoids a timing race where packets arrive before cm is ready.
    private lateinit var cm: ClipboardManager
    @Volatile private var logcatMonitoringStarted = false

    private constructor(ctx: Context) {
        context = ctx.applicationContext
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Restore last known state from persistence instead of reading the system clipboard.
        // Reading cm.primaryClip from a background context triggers:
        //   ClipboardService: Denying clipboard access to <app>
        // in system logcat. Our own logcat monitor then picks that up and launches
        // ClipboardFloatingActivity, which shows the "Copied to Clipboard." system toast
        // even though nothing actually changed. SharedPreferences has no such restriction.
        currentContent = prefs.getString(KEY_CONTENT, null)
        updateTimestamp = prefs.getLong(KEY_TIMESTAMP, 0L)

        cm = ContextCompat.getSystemService<ClipboardManager>(context, ClipboardManager::class.java)!!
        Handler(Looper.getMainLooper()).post {
            cm.addPrimaryClipChangedListener { this.onClipboardChanged() }
        }
        startLogcatMonitoringIfNeeded()
    }

    fun startLogcatMonitoringIfNeeded() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) return
        if (logcatMonitoringStarted) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) return
        logcatMonitoringStarted = true
        execute {
            try {
                val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                // Listen only ClipboardService errors after now
                val process = Runtime.getRuntime().exec(arrayOf<String>("logcat", "-T", timeStamp, "ClipboardService:E", "*:S"))
                val bufferedReader = BufferedReader(InputStreamReader(process.inputStream))
                bufferedReader.forEachLine { line ->
                    if (line.contains(BuildConfig.APPLICATION_ID)) {
                        context.startActivity(ClipboardFloatingActivity.getIntent(context, false))
                    }
                }
            } catch (_: Exception) {
                logcatMonitoringStarted = false
            }
        }
    }

    fun registerObserver(observer: ClipboardObserver) {
        observers.add(observer)
    }

    fun removeObserver(observer: ClipboardObserver) {
        observers.remove(observer)
    }

    fun onClipboardChanged() {
        try {
            val item = cm.primaryClip!!.getItemAt(0)
            val content = item.coerceToText(context).toString()

            if (content == currentContent) {
                return
            }
            updateTimestamp = System.currentTimeMillis()
            currentContent = content
            persistState()

            for (observer in observers) {
                observer.clipboardChanged(content)
            }
        } catch (_: Exception) {
            //Probably clipboard was not text
        }
    }

    @Suppress("deprecation")
    fun setText(text: String?, senderTimestamp: Long = 0) {
        if (!this::cm.isInitialized) return
        if (text == currentContent) return
        updateTimestamp = if (senderTimestamp > 0) senderTimestamp else System.currentTimeMillis()
        currentContent = text
        // Persist before the cm.text write. Even if the write is blocked by the OS
        // (Android 12+ background restriction), future sessions will load the correct
        // state and skip re-writing identical content, avoiding the denial log and toast.
        persistState()
        cm.text = text
    }

    private fun persistState() {
        prefs.edit()
            .putString(KEY_CONTENT, currentContent)
            .putLong(KEY_TIMESTAMP, updateTimestamp)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "clipboard_sync_state"
        private const val KEY_CONTENT = "content"
        private const val KEY_TIMESTAMP = "timestamp"
        private var _instance: ClipboardListener? = null

        @JvmStatic
        fun instance(context: Context): ClipboardListener {
            return _instance ?: ClipboardListener(context).also { _instance = it }
        }
    }
}
