/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// StreamUiState - DAT Camera Streaming UI State
//
// This data class manages UI state for camera streaming operations using the DAT API.

package com.meta.wearable.dat.externalsampleapps.landmarkguide.stream

import android.graphics.Bitmap
import com.meta.wearable.dat.camera.types.StreamSessionState

data class StreamUiState(
    val streamSessionState: StreamSessionState = StreamSessionState.STOPPED,
    val videoFrame: Bitmap? = null,
    val capturedPhoto: Bitmap? = null,
    val isShareDialogVisible: Boolean = false,
    val isCapturing: Boolean = false,
    // State management
    val currentStreamState: StreamState? = null,
    val streamStartTime: Long = 0L, // Time in ms from stream start to first frame
    val photoCaptureTime: Long = 0L, // Time in ms from capture request to photo received
    val micToCaptureTime: Long = 0L, // Time in ms for full Mic→Capture→Mic workflow
    // DAT Audio
    val datAudioVolume: Int = 0, // DAT audio volume (RMS)
    // Stream Errors
    val lastStreamError: String? = null, // Last stream error (STREAM_ERROR, HINGE_CLOSED, PERMISSIONS_DENIED)
)
