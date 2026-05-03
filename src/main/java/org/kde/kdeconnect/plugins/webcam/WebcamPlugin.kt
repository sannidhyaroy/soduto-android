/*
 * SPDX-FileCopyrightText: 2026 Sannidhya Roy <sannidhya@thenoton.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.webcam

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.util.Log
import org.json.JSONArray
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect.ui.PluginSettingsFragment
import org.kde.kdeconnect_tp.R

/**
 * Webcam plugin — streams the device camera and microphone to a connected desktop
 * as a virtual webcam over UDP.
 *
 * Protocol (Soduto extensions):
 *   Receives: kdeconnect.webcam.request_stream — start or stop streaming
 *             kdeconnect.webcam.switch_camera  — switch active camera mid-stream
 *   Sends:    kdeconnect.webcam.stream_status  — reports streaming state, codec,
 *             sensor rotation, and the list of available cameras
 */
@LoadablePlugin
class WebcamPlugin : Plugin() {

    private var isStreaming = false

    override val displayName: String
        get() = context.getString(R.string.pref_plugin_webcam)

    override val description: String
        get() = context.getString(R.string.pref_plugin_webcam_desc)

    override val requiredPermissions: Array<String>
        get() = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    override val permissionExplanation: Int
        get() = R.string.webcam_permission_explanation

    override fun hasSettings(): Boolean = true

    override fun supportsDeviceSpecificSettings(): Boolean = true

    override fun getSettingsFragment(activity: Activity): PluginSettingsFragment =
        PluginSettingsFragment.newInstance(pluginKey, R.xml.webcamplugin_preferences)

    override fun onCreate(): Boolean {
        // Listener setup moved to handleRequestStream() to ensure per-device ownership
        return true
    }

    override fun onDestroy() {
        WebcamStreamingService.listener = null
        if (isStreaming) stopStreaming()
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        return when (np.type) {
            PACKET_TYPE_WEBCAM_REQUEST -> handleRequestStream(np)
            PACKET_TYPE_WEBCAM_CONTROL -> handleCameraControl(np)
            else -> false
        }
    }

    override fun getUiMenuEntries(): List<PluginUiMenuEntry> {
        return if (isStreaming) {
            listOf(PluginUiMenuEntry(context.getString(R.string.webcam_stop_streaming)) {
                if (isDeviceInitialized) stopStreaming()
            })
        } else {
            emptyList()
        }
    }

    override val supportedPacketTypes: Array<String> = arrayOf(
        PACKET_TYPE_WEBCAM_REQUEST,
        PACKET_TYPE_WEBCAM_CONTROL
    )
    override val outgoingPacketTypes: Array<String> = arrayOf(PACKET_TYPE_WEBCAM_STATUS)

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun handleRequestStream(np: NetworkPacket): Boolean {
        if (np.getBoolean("stop", false)) {
            stopStreaming()
            return true
        }

        val addressesJson = np.getJSONArray("addresses") ?: return false
        val addresses = Array(addressesJson.length()) { addressesJson.getString(it) }
        val port = np.getInt("port", -1)
        if (port < 0) return false

        val width   = np.getInt("width", 1280)
        val height  = np.getInt("height", 720)
        val fps     = np.getInt("fps", 30)
        val bitrate = np.getInt("bitrate", -1)
        val codec   = if (np.has("codec")) np.getString("codec") else null
        val camera  = if (np.has("camera")) {
            np.getString("camera")
        } else {
            val useFront = preferences?.getBoolean(context.getString(R.string.webcam_use_front_camera_key), false) ?: false
            if (useFront) "front" else "back"
        }

        // Check if another device is already streaming
        if (WebcamStreamingService.activeDeviceId != null && WebcamStreamingService.activeDeviceId != device.deviceId) {
            Log.w(TAG, "Webcam already in use by another device")
            val response = NetworkPacket(PACKET_TYPE_WEBCAM_STATUS)
            response["streaming"] = false
            response["error"] = "Webcam already in use by another device"
            device.sendPacket(response)
            return true
        }

        // Set up listener for this device before starting stream
        WebcamStreamingService.listener = { status ->
            isStreaming = status.streaming
            sendStreamStatus(status)
            if (isDeviceInitialized) device.onPluginsChanged()
        }

        Intent(context, WebcamStreamingService::class.java).also { intent ->
            intent.action = WebcamStreamingService.Actions.START.name
            intent.putExtra(WebcamStreamingService.EXTRA_ADDRESSES, addresses)
            intent.putExtra(WebcamStreamingService.EXTRA_PORT, port)
            intent.putExtra(WebcamStreamingService.EXTRA_WIDTH, width)
            intent.putExtra(WebcamStreamingService.EXTRA_HEIGHT, height)
            intent.putExtra(WebcamStreamingService.EXTRA_FPS, fps)
            intent.putExtra(WebcamStreamingService.EXTRA_BITRATE, bitrate)
            if (codec != null) intent.putExtra(WebcamStreamingService.EXTRA_CODEC, codec)
            intent.putExtra(WebcamStreamingService.EXTRA_CAMERA, camera)
            context.startForegroundService(intent)
        }
        return true
    }

    private fun handleCameraControl(np: NetworkPacket): Boolean {
        if (!isStreaming) return true   // ignore if not currently streaming

        val hasCamera         = np.has("camera")
        val hasZoom           = np.has("zoom")
        val hasFlash          = np.has("flash")
        val hasRequestKeyframe= np.has("requestKeyframe")

        if (!hasCamera && !hasZoom && !hasFlash && !hasRequestKeyframe) return false

        val camera          = if (hasCamera)          np.getString("camera")                  else null
        val zoom            = if (hasZoom)            np.getDouble("zoom", 1.0).toFloat()      else null
        val flash           = if (hasFlash)           np.getBoolean("flash", false)            else null
        val requestKeyframe = if (hasRequestKeyframe) np.getBoolean("requestKeyframe", false)  else null

        Log.i(TAG, "handleCameraControl: camera=$camera zoom=$zoom flash=$flash requestKeyframe=$requestKeyframe")
        Intent(context, WebcamStreamingService::class.java).also { intent ->
            intent.action = WebcamStreamingService.Actions.CONTROL_CAMERA.name
            if (camera != null)          intent.putExtra(WebcamStreamingService.EXTRA_CAMERA, camera)
            if (zoom != null)            intent.putExtra(WebcamStreamingService.EXTRA_ZOOM, zoom)
            if (flash != null)           intent.putExtra(WebcamStreamingService.EXTRA_FLASH, flash)
            if (requestKeyframe != null) intent.putExtra(WebcamStreamingService.EXTRA_REQUEST_KEYFRAME, requestKeyframe)
            context.startService(intent)
        }
        return true
    }

    private fun stopStreaming() {
        Intent(context, WebcamStreamingService::class.java).also { intent ->
            intent.action = WebcamStreamingService.Actions.STOP.name
            context.startService(intent)
        }
    }

    private fun sendStreamStatus(status: WebcamStreamStatus) {
        Log.i(TAG, "sendStreamStatus: streaming=${status.streaming} codec=${status.codec} rotation=${status.rotation} cameras=${status.cameras} error=${status.error}")
        val np = NetworkPacket(PACKET_TYPE_WEBCAM_STATUS)
        np["streaming"] = status.streaming
        if (status.codec != null) np["codec"] = status.codec
        if (status.rotation != 0) np["rotation"] = status.rotation
        if (status.cameras.isNotEmpty()) {
            val arr = JSONArray()
            status.cameras.forEach { arr.put(it) }
            np["cameras"] = arr
        }
        if (status.activeCamera != null) np["activeCamera"] = status.activeCamera
        if (status.zoomRange != null) {
            val arr = JSONArray()
            arr.put(status.zoomRange.first.toDouble())
            arr.put(status.zoomRange.second.toDouble())
            np["zoomRange"] = arr
        }
        if (status.opticalZooms.isNotEmpty()) {
            val arr = JSONArray()
            status.opticalZooms.forEach { arr.put(it.toDouble()) }
            np["opticalZooms"] = arr
        }
        if (status.activeZoom != 1.0f) np["activeZoom"] = status.activeZoom.toDouble()
        if (status.flashAvailable) np["flashAvailable"] = true
        if (status.flashActive) np["flashActive"] = true
        if (status.error != null) np["error"] = status.error
        device.sendPacket(np)
        Log.i(TAG, "sendStreamStatus: packet sent")
    }

    companion object {
        private const val TAG = "WebcamPlugin"
        private const val PACKET_TYPE_WEBCAM_REQUEST = "kdeconnect.webcam.request_stream"
        private const val PACKET_TYPE_WEBCAM_STATUS  = "kdeconnect.webcam.stream_status"
        private const val PACKET_TYPE_WEBCAM_CONTROL = "kdeconnect.webcam.camera_control"
    }
}
