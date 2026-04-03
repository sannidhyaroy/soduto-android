/*
 * SPDX-FileCopyrightText: 2018 Nicolas Fella <nicolas.fella@gmx.de>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.mprisreceiver

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import org.apache.commons.lang3.StringUtils

internal class MprisReceiverPlayer(
    val controller: MediaController,
    val name: String?,
    context: Context,
) {
    private val compatController: MediaControllerCompat = MediaControllerCompat(
        context,
        MediaSessionCompat.Token.fromToken(controller.sessionToken)
    )

    fun isPlaying(): Boolean {
        val state = controller.playbackState ?: return false
        return state.state == PlaybackState.STATE_PLAYING
    }

    fun canPlay(): Boolean {
        val state = controller.playbackState ?: return false
        if (state.state == PlaybackState.STATE_PLAYING) return true
        return (state.actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE)) != 0L
    }

    fun canPause(): Boolean {
        val state = controller.playbackState ?: return false
        if (state.state == PlaybackState.STATE_PAUSED) return true
        return (state.actions and (PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)) != 0L
    }

    fun canGoPrevious(): Boolean {
        val state = controller.playbackState ?: return false
        return (state.actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L
    }

    fun canGoNext(): Boolean {
        val state = controller.playbackState ?: return false
        return (state.actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L
    }

    fun canSeek(): Boolean {
        val state = controller.playbackState ?: return false
        return (state.actions and PlaybackState.ACTION_SEEK_TO) != 0L
    }

    fun playPause() {
        if (this.isPlaying()) {
            controller.transportControls.pause()
        } else {
            controller.transportControls.play()
        }
    }

    val album: String
        get() {
            val metadata = controller.metadata ?: return ""
            return metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        }

    val artist: String
        get() {
            val metadata = controller.metadata ?: return ""
            return StringUtils.firstNonEmpty<String?>(
                metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                metadata.getString(MediaMetadata.METADATA_KEY_AUTHOR),
                metadata.getString(MediaMetadata.METADATA_KEY_WRITER)
            ) ?: ""
        }

    val title: String
        get() {
            val metadata = controller.metadata ?: return ""
            return StringUtils.firstNonEmpty<String?>(
                metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ) ?: ""
        }

    fun previous() {
        controller.transportControls.skipToPrevious()
    }

    fun next() {
        controller.transportControls.skipToNext()
    }

    fun play() {
        controller.transportControls.play()
    }

    fun pause() {
        controller.transportControls.pause()
    }

    fun stop() {
        controller.transportControls.stop()
    }

    var volume: Int
        get() {
            val info = controller.playbackInfo
            if (info.maxVolume == 0) return 0
            return 100 * info.currentVolume / info.maxVolume
        }
        set(volume) {
            val info = controller.playbackInfo
            // Use rounding for the volume, since most devices don't have a very large range
            val unroundedVolume = info.maxVolume * volume / 100.0 + 0.5
            controller.setVolumeTo(unroundedVolume.toInt(), 0)
        }

    var position: Long
        get() {
            val state = controller.playbackState ?: return 0
            return state.position
        }
        set(position) {
            controller.transportControls.seekTo(position)
        }

    fun seek(offsetUs: Long) {
        val newPosition = (position + offsetUs / 1000).coerceAtLeast(0)
        controller.transportControls.seekTo(newPosition)
    }

    fun setShuffle(shuffle: Boolean) {
        compatController.transportControls.setShuffleMode(
            if (shuffle) PlaybackStateCompat.SHUFFLE_MODE_ALL else PlaybackStateCompat.SHUFFLE_MODE_NONE
        )
    }

    fun setLoopStatus(status: String) {
        val repeatMode = when (status) {
            "Track" -> PlaybackStateCompat.REPEAT_MODE_ONE
            "Playlist" -> PlaybackStateCompat.REPEAT_MODE_ALL
            else -> PlaybackStateCompat.REPEAT_MODE_NONE
        }
        compatController.transportControls.setRepeatMode(repeatMode)
    }

    val shuffle: Boolean
        get() {
            val mode = compatController.shuffleMode
            return mode == PlaybackStateCompat.SHUFFLE_MODE_ALL || mode == PlaybackStateCompat.SHUFFLE_MODE_GROUP
        }

    val loopStatus: String
        get() = when (compatController.repeatMode) {
            PlaybackStateCompat.REPEAT_MODE_ONE -> "Track"
            PlaybackStateCompat.REPEAT_MODE_ALL, PlaybackStateCompat.REPEAT_MODE_GROUP -> "Playlist"
            else -> "None"
        }

    val length: Long
        get() {
            val metadata = controller.metadata ?: return 0
            return metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        }

    val metadata: MediaMetadata?
        get() = controller.metadata
}
