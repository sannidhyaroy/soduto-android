/*
 * SPDX-FileCopyrightText: 2026 Sannidhya Roy <sannidhya@thenoton.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.webcam

/**
 * Immutable snapshot of all streaming state, passed to the listener callback on every update.
 * Fields relevant only during active streaming are null / empty / default when [streaming] is false.
 */
data class WebcamStreamStatus(
    val streaming:      Boolean,
    val codec:          String? = null,
    val rotation:       Int     = 0,
    val cameras:        List<String>  = emptyList(),
    val activeCamera:   String? = null,
    val zoomRange:      Pair<Float, Float>? = null,
    val opticalZooms:   List<Float>   = emptyList(),
    val activeZoom:     Float   = 1.0f,
    val flashAvailable: Boolean = false,
    val flashActive:    Boolean = false,
    val fps:            Int?    = null,
    val error:          String? = null
)
