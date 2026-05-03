/*
 * SPDX-FileCopyrightText: 2026 Sannidhya Roy <sannidhya@thenoton.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.webcam

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.Range
import android.view.OrientationEventListener
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.kde.kdeconnect.helpers.NotificationHelper
import org.kde.kdeconnect_tp.R
import java.net.InetAddress

/**
 * Foreground service that captures camera + microphone and streams encoded
 * H.265/H.264 video and AAC audio over UDP via [WebcamFrameSender].
 *
 * Started by [WebcamPlugin] in response to a kdeconnect.webcam.request_stream
 * packet. Handles mid-stream camera control (camera switch, zoom, flash) via
 * kdeconnect.webcam.camera_control without restarting the encoder.
 * Sends rotation updates via [OrientationEventListener] whenever the device
 * orientation changes, regardless of which app is in the foreground.
 * Reports state back to the plugin via [listener].
 */
class WebcamStreamingService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var encoderSurface: android.view.Surface? = null
    private var sender: WebcamFrameSender? = null

    private var streamStartTimeMs: Long = 0L

    // Camera state
    private var sensorOrientation: Int = 0
    private var currentCameraFacing: Int = CameraCharacteristics.LENS_FACING_BACK
    private var currentCamera: String = "back"
    private var currentCameraChars: CameraCharacteristics? = null

    // Zoom state
    private var currentZoom: Float = 1.0f
    private var zoomRangePair: Pair<Float, Float>? = null
    private var opticalZoomLevels: List<Float> = emptyList()

    // Flash state
    private var flashAvailable: Boolean = false
    private var flashActive: Boolean = false

    // Cached camera list for status updates
    private var cachedCameras: List<String> = emptyList()

    // Stream parameters — persisted so the encoder can be rebuilt on FPS change
    private var currentWidth: Int = 1280
    private var currentHeight: Int = 720
    private var currentFps: Int = 30
    private var currentBitrate: Int = -1
    private var currentCodecPref: String? = null
    private var currentCodecName: String = "h265"

    /** Set to true before releaseResources() so encode loops exit cleanly. */
    @Volatile private var stopRequested = false

    /** Set by applyCameraControl when an FPS change requires encoder restart. */
    @Volatile private var encoderRestarting = false

    // ── Device orientation listener ───────────────────────────────────────────

    /**
     * Fires on device orientation changes via the accelerometer — works even
     * when the screen is off or a non-rotating app is in the foreground.
     * Only fires the listener when the snapped 90° bucket actually changes.
     */
    private var lastSnappedOrientation: Int = -1

    private val orientationListener: OrientationEventListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN || stopRequested) return
                val snapped = when {
                    orientation >= 315 || orientation < 45  ->   0
                    orientation in 45 until 135             ->  90
                    orientation in 135 until 225            -> 180
                    else                                    -> 270
                }
                if (snapped == lastSnappedOrientation) return
                lastSnappedOrientation = snapped
                deviceOrientationDeg = snapped
                val rotation = calculateStreamRotation()
                Log.d(TAG, "Orientation snapped to $snapped°, stream rotation=$rotation")
                listener?.invoke(buildWebcamStreamStatus(rotation = rotation))
            }
        }
    }
    private var deviceOrientationDeg: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresPermission(allOf = [Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO])
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Actions.START.name -> {
                val addresses = intent.getStringArrayExtra(EXTRA_ADDRESSES) ?: run {
                    Log.e(TAG, "No addresses provided"); stopSelf()
                    return super.onStartCommand(intent, flags, startId)
                }
                val port = intent.getIntExtra(EXTRA_PORT, -1)
                if (port < 0) {
                    Log.e(TAG, "Invalid port"); stopSelf()
                    return super.onStartCommand(intent, flags, startId)
                }
                val width     = intent.getIntExtra(EXTRA_WIDTH, 1280)
                val height    = intent.getIntExtra(EXTRA_HEIGHT, 720)
                val fps       = intent.getIntExtra(EXTRA_FPS, 30)
                val bitrate   = intent.getIntExtra(EXTRA_BITRATE, -1)
                val codecPref = intent.getStringExtra(EXTRA_CODEC)
                val cameraPref = intent.getStringExtra(EXTRA_CAMERA) ?: "back"

                startForegroundCompat()
                serviceScope.launch {
                    startStreaming(addresses, port, width, height, fps, bitrate, codecPref, cameraPref)
                }
            }
            Actions.STOP.name -> stopStreaming()
            Actions.CONTROL_CAMERA.name -> {
                val camera          = intent.getStringExtra(EXTRA_CAMERA)
                val zoom            = if (intent.hasExtra(EXTRA_ZOOM))             intent.getFloatExtra(EXTRA_ZOOM, 1.0f)            else null
                val flash           = if (intent.hasExtra(EXTRA_FLASH))            intent.getBooleanExtra(EXTRA_FLASH, false)         else null
                val requestKeyframe = if (intent.hasExtra(EXTRA_REQUEST_KEYFRAME)) intent.getBooleanExtra(EXTRA_REQUEST_KEYFRAME, false) else null
                val fps             = if (intent.hasExtra(EXTRA_FPS))              intent.getIntExtra(EXTRA_FPS, 30)                  else null
                applyCameraControl(camera, zoom, flash, requestKeyframe, fps)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseResources()
        serviceScope.cancel()
    }

    // ── Streaming ─────────────────────────────────────────────────────────────

    @RequiresPermission(allOf = [Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO])
    private suspend fun startStreaming(
        addresses: Array<String>,
        port: Int,
        width: Int, height: Int, fps: Int, bitrate: Int,
        codecPref: String?, cameraPref: String
    ) {
        stopRequested = false
        try {
            val targetAddress = addresses.firstNotNullOfOrNull { addr ->
                try { InetAddress.getByName(addr) } catch (_: Exception) { null }
            } ?: run { reportError("Could not resolve any provided address"); return }

            val frameSender = WebcamFrameSender(targetAddress, port)
            sender = frameSender
            streamStartTimeMs = System.currentTimeMillis()

            val (videoEncoder, codecName) = createVideoEncoder(width, height, fps, bitrate, codecPref)
            videoCodec = videoEncoder
            currentWidth = width; currentHeight = height; currentFps = fps
            currentBitrate = bitrate; currentCodecPref = codecPref; currentCodecName = codecName
            encoderSurface = videoEncoder.createInputSurface()
            videoEncoder.start()

            val cameraManager = getSystemService<CameraManager>()
                ?: throw IllegalStateException("No CameraManager")

            currentCamera = cameraPref
            val cameraId = resolveCamera(cameraManager, cameraPref)
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            currentCameraChars = chars
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            currentCameraFacing = chars.get(CameraCharacteristics.LENS_FACING)
                ?: CameraCharacteristics.LENS_FACING_BACK
            flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
            currentZoom = 1.0f

            if (currentCameraFacing == CameraCharacteristics.LENS_FACING_BACK) {
                val (zr, oz) = queryZoomLevels(chars)
                zoomRangePair = zr
                opticalZoomLevels = oz
            } else {
                zoomRangePair = null
                opticalZoomLevels = emptyList()
            }

            cachedCameras = buildCameraList(cameraManager)
            openCamera(cameraId, encoderSurface!!)

            val audioJob = serviceScope.launch { startAudioEncoding(frameSender) }

            // Register orientation listener — fires regardless of foreground app or screen state.
            orientationListener.enable()

            val rotation = calculateStreamRotation()
            Log.i(TAG, "Streaming started: codec=$codecName rotation=$rotation cameras=$cachedCameras")
            listener?.invoke(WebcamStreamStatus(
                streaming = true,
                codec = codecName,
                rotation = rotation,
                cameras = cachedCameras,
                activeCamera = currentCamera,
                zoomRange = zoomRangePair,
                opticalZooms = opticalZoomLevels,
                activeZoom = currentZoom,
                flashAvailable = flashAvailable,
                flashActive = flashActive
            ))

            runVideoEncodeLoop(videoEncoder, frameSender)

            // FPS-change restart loop — rebuilds encoder and camera session without
            // stopping the service or sending streaming=false to the plugin.
            while (encoderRestarting && !stopRequested) {
                encoderRestarting = false
                try {
                    closeCamera()
                    try { videoCodec?.stop(); videoCodec?.release() } catch (_: Exception) {}
                    encoderSurface?.release()
                    videoCodec = null; encoderSurface = null

                    val cm = getSystemService<CameraManager>()
                        ?: throw IllegalStateException("No CameraManager")
                    val (newEncoder, newCodec) = createVideoEncoder(
                        currentWidth, currentHeight, currentFps, currentBitrate, currentCodecPref)
                    videoCodec = newEncoder
                    currentCodecName = newCodec
                    encoderSurface = newEncoder.createInputSurface()
                    newEncoder.start()

                    val cameraId = resolveCamera(cm, currentCamera)
                    val chars = cm.getCameraCharacteristics(cameraId)
                    currentCameraChars = chars
                    sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                    currentCameraFacing = chars.get(CameraCharacteristics.LENS_FACING)
                        ?: CameraCharacteristics.LENS_FACING_BACK
                    flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                    if (currentCameraFacing == CameraCharacteristics.LENS_FACING_BACK) {
                        val (zr, oz) = queryZoomLevels(chars)
                        zoomRangePair = zr; opticalZoomLevels = oz
                    } else {
                        zoomRangePair = null; opticalZoomLevels = emptyList()
                    }
                    cachedCameras = buildCameraList(cm)
                    openCamera(cameraId, encoderSurface!!)

                    Log.i(TAG, "Encoder restarted: fps=$currentFps codec=$currentCodecName")
                    listener?.invoke(buildWebcamStreamStatus())
                    runVideoEncodeLoop(videoCodec!!, frameSender)
                } catch (e: Exception) {
                    Log.e(TAG, "Encoder restart failed", e)
                    break
                }
            }

            audioJob.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Streaming error", e)
            reportError(e.message ?: "Unknown error")
        } finally {
            releaseResources()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopStreaming() {
        stopRequested = true
        releaseResources()
        activeDeviceId = null
        listener?.invoke(WebcamStreamStatus(streaming = false))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Applies camera control changes mid-stream: camera switch, zoom, or flash.
     * Camera switches close and reopen the camera session while the encoder continues.
     * Zoom and flash changes update the repeating capture request in-place.
     */
    @SuppressLint("MissingPermission")
    private fun applyCameraControl(
        camera: String?, zoom: Float?, flash: Boolean?,
        requestKeyframe: Boolean? = null, fps: Int? = null
    ) {
        if (stopRequested || encoderSurface == null) return
        serviceScope.launch {
            try {
                if (requestKeyframe == true) {
                    videoCodec?.setParameters(Bundle().apply {
                        putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                    })
                    Log.d(TAG, "Keyframe requested by remote")
                    if (camera == null && zoom == null && flash == null && fps == null) return@launch
                }

                if (fps != null && fps != currentFps) {
                    // Apply any co-requested camera change to current* state so the
                    // restart loop picks it up when it rebuilds the session.
                    if (camera != null && camera != currentCamera) {
                        val cm = getSystemService<CameraManager>()
                            ?: throw IllegalStateException("No CameraManager")
                        val chars = cm.getCameraCharacteristics(resolveCamera(cm, camera))
                        currentCameraChars = chars
                        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                        currentCameraFacing = chars.get(CameraCharacteristics.LENS_FACING)
                            ?: CameraCharacteristics.LENS_FACING_BACK
                        flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                        flashActive = false
                        currentCamera = camera
                        currentZoom = zoom ?: 1.0f
                        if (currentCameraFacing == CameraCharacteristics.LENS_FACING_BACK) {
                            val (zr, oz) = queryZoomLevels(chars)
                            zoomRangePair = zr; opticalZoomLevels = oz
                        } else {
                            zoomRangePair = null; opticalZoomLevels = emptyList()
                        }
                    } else {
                        if (zoom != null) currentZoom = zoom.coerceIn(
                            zoomRangePair?.first ?: 1.0f, zoomRangePair?.second ?: 1.0f)
                    }
                    if (flash != null) flashActive = flash && flashAvailable
                    currentFps = fps
                    encoderRestarting = true
                    Log.i(TAG, "FPS change: $fps — encoder restart pending")
                    return@launch
                }
                val cameraManager = getSystemService<CameraManager>()
                    ?: throw IllegalStateException("No CameraManager")

                if (camera != null && camera != currentCamera) {
                    // Switch logical camera (back ↔ front)
                    closeCamera()
                    val cameraId = resolveCamera(cameraManager, camera)
                    val chars = cameraManager.getCameraCharacteristics(cameraId)
                    currentCameraChars = chars
                    sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                    currentCameraFacing = chars.get(CameraCharacteristics.LENS_FACING)
                        ?: CameraCharacteristics.LENS_FACING_BACK
                    flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                    flashActive = false  // reset flash on camera switch
                    currentCamera = camera
                    currentZoom = zoom ?: 1.0f  // apply zoom if specified along with switch

                    if (currentCameraFacing == CameraCharacteristics.LENS_FACING_BACK) {
                        val (zr, oz) = queryZoomLevels(chars)
                        zoomRangePair = zr
                        opticalZoomLevels = oz
                    } else {
                        zoomRangePair = null
                        opticalZoomLevels = emptyList()
                    }
                    cachedCameras = buildCameraList(cameraManager)
                    openCamera(cameraId, encoderSurface!!)

                } else {
                    // No camera switch — apply zoom and/or flash in-place
                    if (zoom != null) {
                        val (min, max) = zoomRangePair ?: Pair(1.0f, 1.0f)
                        currentZoom = zoom.coerceIn(min, max)
                    }
                    if (flash != null) {
                        flashActive = flash && flashAvailable
                    }
                    updateCaptureRequest()
                }

                val rotation = calculateStreamRotation()
                Log.i(TAG, "Camera control applied: camera=$currentCamera zoom=$currentZoom flash=$flashActive")
                listener?.invoke(buildWebcamStreamStatus(rotation = rotation))

            } catch (e: Exception) {
                Log.e(TAG, "Camera control error", e)
            }
        }
    }

    private fun reportError(message: String) {
        Log.e(TAG, message)
        activeDeviceId = null
        listener?.invoke(WebcamStreamStatus(streaming = false, error = message))
    }

    // ── Camera map ────────────────────────────────────────────────────────────

    /**
     * Returns a map of semantic label → Android camera ID for logical cameras only.
     * Uses the first back-facing and first front-facing camera the OS exposes.
     * Zoom-level access to ultrawide/telephoto physical sensors is handled via
     * [CONTROL_ZOOM_RATIO] on the logical back camera — no separate enumeration needed.
     */
    private fun buildCameraMap(manager: CameraManager): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        for (id in manager.cameraIdList) {
            val facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            when {
                facing == CameraCharacteristics.LENS_FACING_BACK  && !result.containsKey("back")  -> result["back"]  = id
                facing == CameraCharacteristics.LENS_FACING_FRONT && !result.containsKey("front") -> result["front"] = id
            }
            if (result.size == 2) break
        }
        return result
    }

    private fun buildCameraList(manager: CameraManager): List<String> =
        buildCameraMap(manager).keys.toList()

    private fun resolveCamera(manager: CameraManager, pref: String): String {
        val map = buildCameraMap(manager)
        return map[pref]
            ?: map["back"]
            ?: manager.cameraIdList.firstOrNull()
            ?: throw CameraAccessException(CameraAccessException.CAMERA_DISABLED, "No camera")
    }

    // ── Zoom ──────────────────────────────────────────────────────────────────

    /**
     * Queries the zoom range and preferred zoom levels for the given camera.
     *
     * Range: [CONTROL_ZOOM_RATIO_RANGE] on API 30+; [SCALER_AVAILABLE_MAX_DIGITAL_ZOOM] fallback.
     *
     * Preferred levels are derived from the reported range, not from focal-length data.
     * Logical cameras (common on modern multi-sensor phones) typically report only a single
     * focal length, making focal-length normalisation unreliable. Instead we pick standard
     * "tap-to-zoom" breakpoints that fall within the device's actual zoom range.
     */
    private fun queryZoomLevels(
        chars: CameraCharacteristics
    ): Pair<Pair<Float, Float>, List<Float>> {
        val (minZoom, maxZoom) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val range = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            Pair(range?.lower ?: 1.0f, range?.upper ?: 1.0f)
        } else {
            val maxDigital = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
            Pair(1.0f, maxDigital)
        }
        return Pair(Pair(minZoom, maxZoom), preferredZoomLevels(minZoom, maxZoom))
    }

    /**
     * Generates a list of preferred tap-to-zoom levels within [minZoom, maxZoom].
     *
     * The ultra-wide entry (when minZoom < 1) is derived from the reported minimum,
     * rounded to one decimal place. Telephoto entries use standard breakpoints
     * (2×, 3×, 5×, 10×, 30×) that are included only if supported by the device.
     *
     * Examples:
     *   Samsung S24 (0.6 – 30):  [0.6, 1.0, 2.0, 3.0, 5.0, 10.0, 30.0]
     *   Pixel 7 (0.7 – 7):       [0.7, 1.0, 2.0, 3.0, 5.0]
     *   Basic phone (1.0 – 4):   [1.0, 2.0, 3.0]
     */
    private fun preferredZoomLevels(minZoom: Float, maxZoom: Float): List<Float> {
        val result = mutableListOf<Float>()
        // Ultra-wide: include the minimum zoom if the device supports sub-1× zoom
        if (minZoom < 0.95f) {
            val rounded = (minZoom * 10f + 0.5f).toInt() / 10f
            result.add(rounded.coerceAtLeast(0.5f))
        }
        // Main camera (always 1×)
        if (maxZoom >= 1.0f) result.add(1.0f)
        // Telephoto breakpoints — include if within the device's range
        for (level in listOf(2.0f, 3.0f, 5.0f, 10.0f, 30.0f)) {
            if (level <= maxZoom + 0.5f) result.add(level)
        }
        return result
    }

    // ── Rotation ──────────────────────────────────────────────────────────────

    /**
     * Calculates the clockwise rotation in degrees that the receiver must apply to
     * display the video upright, given the current device orientation and camera facing.
     *
     *   back camera:  (SENSOR_ORIENTATION + deviceOrientationDeg) % 360
     *   front camera: (SENSOR_ORIENTATION - deviceOrientationDeg + 360) % 360
     */
    private fun calculateStreamRotation(): Int =
        if (currentCameraFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation - deviceOrientationDeg + 360) % 360
        } else {
            (sensorOrientation + deviceOrientationDeg) % 360
        }

    // ── Video encoder ─────────────────────────────────────────────────────────

    private fun createVideoEncoder(
        width: Int, height: Int, fps: Int, bitrate: Int, codecPref: String?
    ): Pair<MediaCodec, String> {
        val tryH265 = codecPref == null || codecPref == "h265"
        val hasH265 = tryH265 && MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && MIME_H265 in info.supportedTypes
        }
        val (mime, codecName) = if (hasH265) MIME_H265 to "h265" else MIME_H264 to "h264"

        val effectiveBitrate = if (bitrate > 0) bitrate else (width * height * fps / 8)
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, effectiveBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            // Embed SPS/PPS in every keyframe so the receiver can initialize or
            // re-initialize the decoder at any IDR without needing the codec config
            // buffer. Makes BUFFER_FLAG_CODEC_CONFIG redundant and safe to skip.
            setInteger("prepend-sps-pps-to-idr-frames", 1)
        }

        val encoder = MediaCodec.createEncoderByType(mime)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return encoder to codecName
    }

    private fun runVideoEncodeLoop(encoder: MediaCodec, frameSender: WebcamFrameSender) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (!stopRequested && !encoderRestarting) {
            try {
                val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                if (outputIndex < 0) continue
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    encoder.releaseOutputBuffer(outputIndex, false)
                    continue
                }

                val buffer = encoder.getOutputBuffer(outputIndex) ?: continue
                buffer.position(bufferInfo.offset)
                buffer.limit(bufferInfo.offset + bufferInfo.size)
                val data = ByteArray(bufferInfo.size)
                buffer.get(data)
                encoder.releaseOutputBuffer(outputIndex, false)

                val isKeyframe = bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                val ptsMs = bufferInfo.presentationTimeUs / 1000
                frameSender.sendVideoFrame(data, ptsMs, isKeyframe)
            } catch (e: IllegalStateException) {
                Log.d(TAG, "Video encode loop: codec stopped (${e.message})")
                break
            }
        }
    }

    // ── Camera2 ───────────────────────────────────────────────────────────────

    /**
     * Opens the camera with the given Android camera ID and starts a repeating
     * capture request targeting [surface]. Applies current zoom and flash state.
     * Blocks until the session is configured or times out.
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    private fun openCamera(cameraId: String, surface: android.view.Surface) {
        val cameraManager = getSystemService<CameraManager>()
            ?: throw IllegalStateException("No CameraManager")

        val openLatch = java.util.concurrent.CountDownLatch(1)
        var openError: Exception? = null

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                try {
                    val outputs = listOf(android.hardware.camera2.params.OutputConfiguration(surface))
                    val sessionConfig = android.hardware.camera2.params.SessionConfiguration(
                        android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                        outputs,
                        mainExecutor,
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                captureSession = session
                                try {
                                    updateCaptureRequest()
                                    Log.i(TAG, "Capture session configured")
                                } catch (e: Exception) {
                                    Log.e(TAG, "setRepeatingRequest failed", e)
                                    openError = e
                                } finally {
                                    openLatch.countDown()
                                }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                openError = Exception("CaptureSession configuration failed")
                                openLatch.countDown()
                            }
                        }
                    )
                    camera.createCaptureSession(sessionConfig)
                } catch (e: Exception) {
                    Log.e(TAG, "createCaptureSession failed", e)
                    openError = e
                    openLatch.countDown()
                }
            }

            override fun onDisconnected(camera: CameraDevice) {
                Log.w(TAG, "Camera disconnected")
                camera.close()
                openLatch.countDown()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                openError = Exception("Camera error: $error")
                camera.close()
                openLatch.countDown()
            }
        }, Handler(Looper.getMainLooper()))

        if (!openLatch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
            throw Exception("Camera open timed out after 10 s")
        }
        openError?.let { throw it }
    }

    /**
     * Updates the repeating capture request with the current zoom and flash state.
     * Safe to call any time after the capture session is open.
     */
    private fun updateCaptureRequest() {
        val session = captureSession ?: return
        val device  = cameraDevice  ?: return
        val surface = encoderSurface ?: return
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(currentFps, currentFps))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoom)
                } else if (currentZoom > 1.0f) {
                    // Legacy crop-region zoom for API < 30
                    currentCameraChars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                        ?.let { sensor ->
                            val cropW = (sensor.width()  / currentZoom).toInt().coerceAtLeast(1)
                            val cropH = (sensor.height() / currentZoom).toInt().coerceAtLeast(1)
                            val left  = (sensor.width()  - cropW) / 2
                            val top   = (sensor.height() - cropH) / 2
                            set(CaptureRequest.SCALER_CROP_REGION,
                                android.graphics.Rect(left, top, left + cropW, top + cropH))
                        }
                }
                set(CaptureRequest.FLASH_MODE,
                    if (flashActive && flashAvailable) CaptureRequest.FLASH_MODE_TORCH
                    else CaptureRequest.FLASH_MODE_OFF)
            }
            session.setRepeatingRequest(builder.build(), null, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            Log.e(TAG, "updateCaptureRequest failed: ${e.message}")
        }
    }

    /**
     * Closes only the camera session and device, leaving the encoder and UDP
     * sender running. Used during seamless camera switches.
     */
    private fun closeCamera() {
        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        try { captureSession?.close() }         catch (_: Exception) {}
        try { cameraDevice?.close() }           catch (_: Exception) {}
        captureSession = null
        cameraDevice   = null
    }

    // ── Audio encoder ─────────────────────────────────────────────────────────

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startAudioEncoding(frameSender: WebcamFrameSender) {
        val sampleRate    = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat   = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer     = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate, channelConfig, audioFormat,
            maxOf(minBuffer, 4096)
        )
        audioRecord = record

        val audioFmt = MediaFormat.createAudioFormat(MIME_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val aacEncoder = MediaCodec.createEncoderByType(MIME_AAC)
        aacEncoder.configure(audioFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        aacEncoder.start()
        audioCodec = aacEncoder

        record.startRecording()

        val inputBuffer = ByteArray(4096)
        val bufferInfo  = MediaCodec.BufferInfo()
        var inputDone   = false

        while (!Thread.currentThread().isInterrupted && !stopRequested) {
            try {
                if (!inputDone) {
                    val inputIdx = aacEncoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputIdx >= 0) {
                        val read = record.read(inputBuffer, 0, inputBuffer.size)
                        if (read > 0) {
                            val buf = aacEncoder.getInputBuffer(inputIdx)!!
                            buf.clear()
                            buf.put(inputBuffer, 0, read)
                            val ptsUs = (System.currentTimeMillis() - streamStartTimeMs) * 1000
                            aacEncoder.queueInputBuffer(inputIdx, 0, read, ptsUs, 0)
                        } else if (read == AudioRecord.ERROR_INVALID_OPERATION) {
                            inputDone = true
                        }
                    }
                }

                val outputIdx = aacEncoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                if (outputIdx >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        aacEncoder.releaseOutputBuffer(outputIdx, false)
                        continue
                    }
                    val buf = aacEncoder.getOutputBuffer(outputIdx) ?: continue
                    buf.position(bufferInfo.offset)
                    buf.limit(bufferInfo.offset + bufferInfo.size)
                    val data = ByteArray(bufferInfo.size)
                    buf.get(data)
                    aacEncoder.releaseOutputBuffer(outputIdx, false)
                    val ptsMs = bufferInfo.presentationTimeUs / 1000
                    frameSender.sendAudioFrame(data, ptsMs)
                }
            } catch (e: IllegalStateException) {
                Log.d(TAG, "Audio encode loop: codec stopped (${e.message})")
                break
            }
        }

        record.stop()
        record.release()
        aacEncoder.stop()
        aacEncoder.release()
    }

    // ── Resource management ───────────────────────────────────────────────────

    private fun releaseResources() {
        orientationListener.disable()
        lastSnappedOrientation = -1

        closeCamera()
        try { videoCodec?.stop(); videoCodec?.release() }   catch (_: Exception) {}
        try { encoderSurface?.release() }                   catch (_: Exception) {}
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        try { audioCodec?.stop(); audioCodec?.release() }   catch (_: Exception) {}
        try { sender?.close() }                             catch (_: Exception) {}
        videoCodec     = null
        encoderSurface = null
        audioRecord    = null
        audioCodec     = null
        sender         = null
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Builds a WebcamStreamStatus reflecting the current streaming state. */
    private fun buildWebcamStreamStatus(rotation: Int = calculateStreamRotation()) = WebcamStreamStatus(
        streaming      = true,
        codec          = currentCodecName,
        fps            = currentFps,
        rotation       = rotation,
        cameras        = cachedCameras,
        activeCamera   = currentCamera,
        zoomRange      = zoomRangePair,
        opticalZooms   = opticalZoomLevels,
        activeZoom     = currentZoom,
        flashAvailable = flashAvailable,
        flashActive    = flashActive
    )

    // ── Foreground notification ───────────────────────────────────────────────

    private fun startForegroundCompat() {
        val stopIntent = Intent(this, WebcamStreamingService::class.java).apply {
            action = Actions.STOP.name
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NotificationHelper.Channels.WEBCAM)
            .setContentTitle(getString(R.string.webcam_notification_title))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_notification, getString(R.string.webcam_notification_stop), stopPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    enum class Actions { START, STOP, CONTROL_CAMERA }

    companion object {
        private const val TAG = "WebcamStreamingService"

        const val EXTRA_ADDRESSES = "addresses"
        const val EXTRA_PORT      = "port"
        const val EXTRA_WIDTH     = "width"
        const val EXTRA_HEIGHT    = "height"
        const val EXTRA_FPS       = "fps"
        const val EXTRA_BITRATE   = "bitrate"
        const val EXTRA_CODEC     = "codec"
        const val EXTRA_CAMERA           = "camera"
        const val EXTRA_ZOOM             = "zoom"
        const val EXTRA_FLASH            = "flash"
        const val EXTRA_REQUEST_KEYFRAME = "requestKeyframe"

        const val NOTIFICATION_ID = 20241

        private const val MIME_H265 = "video/hevc"
        private const val MIME_H264 = "video/avc"
        private const val MIME_AAC  = "audio/mp4a-latm"

        private const val DEQUEUE_TIMEOUT_US = 10_000L

        /**
         * Tracks which device ID currently owns the streaming session.
         * Only one device may stream at a time. Set in [WebcamPlugin.handleRequestStream]
         * when a stream starts; cleared on stream stop or error.
         */
        @Volatile var activeDeviceId: String? = null

        /**
         * Callback invoked by the service to report streaming state changes.
         * Set by [WebcamPlugin] in [handleRequestStream] — ownership transfers to
         * whichever device last requested a stream, so multi-device ambiguity is eliminated.
         *
         * Called from a background coroutine or the main thread — must be thread-safe.
         */
        @Volatile
        var listener: ((WebcamStreamStatus) -> Unit)? = null
    }
}
