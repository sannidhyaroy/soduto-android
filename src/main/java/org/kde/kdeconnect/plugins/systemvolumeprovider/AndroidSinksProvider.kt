/*
 * SPDX-FileCopyrightText: 2026 Sannidhya Roy <sannidhya@thenoton.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.systemvolumeprovider

import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import org.kde.kdeconnect.NetworkPacket

internal data class StreamInfo(
    val streamType: Int,
    val name: String,
    val defaultDescription: String,
    val isKnown: Boolean,
)

internal class AndroidSinksProvider(
    private val context: Context,
    private val sendPacket: (NetworkPacket) -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private lateinit var allStreams: List<StreamInfo>
    private lateinit var lastVolumes: MutableMap<Int, Int>
    private var volumeObserver: ContentObserver? = null

    // Held as a field — SharedPreferences uses weak references internally.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        sendPacket(buildSinkListPacket())
    }

    private val enabledStreams: List<StreamInfo>
        get() = allStreams.filter { isStreamEnabled(it) }

    private fun isStreamEnabled(stream: StreamInfo): Boolean =
        prefs.getBoolean(prefKeyEnabled(stream.name), stream.streamType in DEFAULT_ENABLED_TYPES)

    private fun getStreamDescription(stream: StreamInfo): String =
        prefs.getString(prefKeyLabel(stream.name), null)?.takeIf { it.isNotEmpty() }
            ?: stream.defaultDescription

    fun start() {
        allStreams = discoverStreams(context)
        lastVolumes = allStreams.associate {
            it.streamType to audioManager.getStreamVolume(it.streamType)
        }.toMutableMap()

        volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                for (stream in allStreams) {
                    val current = audioManager.getStreamVolume(stream.streamType)
                    if (current != lastVolumes[stream.streamType]) {
                        lastVolumes[stream.streamType] = current
                        if (isStreamEnabled(stream)) {
                            sendPacket(buildSingleUpdatePacket(stream))
                        }
                    }
                }
            }
        }
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver!!)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    fun stop() {
        volumeObserver?.let { context.contentResolver.unregisterContentObserver(it) }
        volumeObserver = null
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    fun buildSinkListPacket(): NetworkPacket {
        val np = NetworkPacket(PACKET_TYPE_SYSTEMVOLUME)
        val array = JSONArray()
        for (stream in enabledStreams) {
            array.put(buildSinkJson(stream))
        }
        np["sinkList"] = array
        return np
    }

    fun handleVolumeChange(name: String, volume: Int) {
        val stream = allStreams.firstOrNull { it.name == name } ?: return
        val clamped = volume.coerceIn(0, audioManager.getStreamMaxVolume(stream.streamType))
        audioManager.setStreamVolume(stream.streamType, clamped, 0)
    }

    fun handleMuteChange(name: String, muted: Boolean) {
        val stream = allStreams.firstOrNull { it.name == name } ?: return
        audioManager.adjustStreamVolume(
            stream.streamType,
            if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
            0
        )
    }

    private fun buildSinkJson(stream: StreamInfo): JSONObject = JSONObject().apply {
        put("name", stream.name)
        put("description", getStreamDescription(stream))
        put("volume", audioManager.getStreamVolume(stream.streamType))
        put("maxVolume", audioManager.getStreamMaxVolume(stream.streamType))
        put("muted", audioManager.isStreamMute(stream.streamType))
        put("enabled", stream.streamType == AudioManager.STREAM_MUSIC)
    }

    private fun buildSingleUpdatePacket(stream: StreamInfo): NetworkPacket {
        val np = NetworkPacket(PACKET_TYPE_SYSTEMVOLUME)
        np["name"] = stream.name
        np["volume"] = audioManager.getStreamVolume(stream.streamType)
        np["muted"] = audioManager.isStreamMute(stream.streamType)
        np["enabled"] = stream.streamType == AudioManager.STREAM_MUSIC
        return np
    }

    companion object {
        const val PREFS_NAME = "soduto_systemvolume_provider"
        private const val PACKET_TYPE_SYSTEMVOLUME = "kdeconnect.systemvolume"

        val DEFAULT_ENABLED_TYPES: Set<Int> = setOf(
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_SYSTEM,
        )

        private val KNOWN_STREAMS: Map<Int, Pair<String, String>> = mapOf(
            AudioManager.STREAM_MUSIC         to ("music"         to "Media"),
            AudioManager.STREAM_RING          to ("ring"          to "Ring"),
            AudioManager.STREAM_NOTIFICATION  to ("notification"  to "Notifications"),
            AudioManager.STREAM_ALARM         to ("alarm"         to "Alarm"),
            AudioManager.STREAM_SYSTEM        to ("system"        to "System"),
            AudioManager.STREAM_VOICE_CALL    to ("voice_call"    to "Voice Call"),
            AudioManager.STREAM_DTMF          to ("dtmf"          to "DTMF"),
            AudioManager.STREAM_ACCESSIBILITY to ("accessibility" to "Accessibility"),
        )

        fun discoverStreams(context: Context): List<StreamInfo> {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val result = mutableListOf<StreamInfo>()
            for (type in 0..256) {
                val maxVolume = try {
                    audioManager.getStreamMaxVolume(type)
                } catch (_: Exception) {
                    continue
                }
                if (maxVolume <= 0) continue
                val known = KNOWN_STREAMS[type]
                result.add(StreamInfo(
                    streamType = type,
                    name = known?.first ?: "stream_$type",
                    defaultDescription = known?.second ?: "Stream $type",
                    isKnown = known != null,
                ))
            }
            return result
        }

        fun prefKeyEnabled(streamName: String) = "stream_${streamName}_enabled"
        fun prefKeyLabel(streamName: String) = "stream_${streamName}_label"
    }
}
