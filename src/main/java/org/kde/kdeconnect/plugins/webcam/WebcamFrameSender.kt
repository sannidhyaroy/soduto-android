/*
 * SPDX-FileCopyrightText: 2026 Sannidhya Roy <sannidhya@thenoton.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.webcam

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sends video and audio frames over UDP using an 18-byte framing header.
 *
 * Large frames are fragmented into MAX_PAYLOAD-byte chunks. The receiver
 * reassembles using [frame_total_size] and detects completion via the
 * [FLAG_LAST_FRAGMENT] flag bit.
 *
 * Header layout (18 bytes, all fields little-endian):
 *   [0..3]   uint32  sequence number (monotonic, per datagram)
 *   [4..7]   uint32  pts_ms (presentation timestamp, ms since stream start)
 *   [8..11]  uint32  frame_total_size
 *   [12..15] uint32  fragment_byte_offset
 *   [16]     uint8   flags (see FLAG_* constants)
 *   [17]     uint8   stream_type (0=primary video, 1=desk video, 2=audio)
 */
class WebcamFrameSender(address: InetAddress, private val port: Int) : Closeable {

    private val socket = DatagramSocket()
    private val remoteAddress: InetAddress = address
    private var sequenceNumber: Int = 0

    fun sendVideoFrame(data: ByteArray, ptsMs: Long, isKeyframe: Boolean) {
        val flags = if (isKeyframe) FLAG_KEYFRAME else 0
        sendFragmented(data, ptsMs, flags, STREAM_TYPE_VIDEO)
    }

    fun sendAudioFrame(data: ByteArray, ptsMs: Long) {
        sendFragmented(data, ptsMs, 0, STREAM_TYPE_AUDIO)
    }

    override fun close() {
        socket.close()
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun sendFragmented(data: ByteArray, ptsMs: Long, baseFlags: Int, streamType: Int) {
        val totalSize = data.size
        var offset = 0
        while (offset < totalSize) {
            val chunkSize = minOf(MAX_PAYLOAD, totalSize - offset)
            val isLast = (offset + chunkSize) >= totalSize
            val flags = baseFlags or (if (isLast) FLAG_LAST_FRAGMENT else 0)
            sendDatagram(data, offset, chunkSize, ptsMs, totalSize, offset, flags, streamType)
            offset += chunkSize
        }
    }

    private fun sendDatagram(
        data: ByteArray,
        dataOffset: Int,
        dataLength: Int,
        ptsMs: Long,
        frameTotalSize: Int,
        fragmentByteOffset: Int,
        flags: Int,
        streamType: Int
    ) {
        val packet = ByteArray(HEADER_SIZE + dataLength)
        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(sequenceNumber++)
        buf.putInt((ptsMs and 0xFFFFFFFFL).toInt())
        buf.putInt(frameTotalSize)
        buf.putInt(fragmentByteOffset)
        buf.put(flags.toByte())
        buf.put(streamType.toByte())
        buf.put(data, dataOffset, dataLength)
        val dgPacket = DatagramPacket(packet, packet.size, remoteAddress, port)
        socket.send(dgPacket)
    }

    companion object {
        const val MAX_PAYLOAD = 1400
        private const val HEADER_SIZE = 18

        // Flag bits
        private const val FLAG_KEYFRAME: Int = 0x01
        private const val FLAG_LAST_FRAGMENT: Int = 0x02

        // Stream type constants (byte 17)
        private const val STREAM_TYPE_VIDEO: Int = 0
        private const val STREAM_TYPE_AUDIO: Int = 2
    }
}
