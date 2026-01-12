/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.landmarkguide.stream

import com.meta.wearable.dat.camera.types.VideoQuality

/**
 * Application modes that define different use cases
 */
enum class AppMode {
    /** Tour mode: Walking around, asking questions about environment */
    TOUR,
    /** Focus mode: Close-up objects, text translation */
    FOCUS,
    /** Conversation mode: Live translation between languages */
    CONVERSATION
}

/**
 * Stream states that define video/audio configuration
 */
enum class StreamState(
    val videoEnabled: Boolean,
    val audioEnabled: Boolean,
    val videoQuality: VideoQuality?,
    val frameRate: Int?,
    val displayName: String  // Friendly name for UI/logs
) {
    /** Everything off - minimum power */
    OFF(
        videoEnabled = false,
        audioEnabled = false,
        videoQuality = null,
        frameRate = null,
        displayName = "Off"
    ),
    
    /** Audio only - no video streaming */
    AUDIO_ONLY(
        videoEnabled = false,
        audioEnabled = true,
        videoQuality = null,
        frameRate = null,
        displayName = "Audio"
    ),
    
    /** Standby - low power video ready for quick capture */
    STANDBY(
        videoEnabled = true,
        audioEnabled = true,
        videoQuality = VideoQuality.LOW,
        frameRate = 10,  // Minimum supported fps (1fps not supported)
        displayName = "Low"
    ),
    
    /** AI Recognition - medium quality for analysis */
    AI_RECOGNITION(
        videoEnabled = true,
        audioEnabled = true,
        videoQuality = VideoQuality.MEDIUM,
        frameRate = 24,  // Changed from 30 to 24
        displayName = "Med"
    ),
    
    /** Photo Capture - high quality for saving */
    PHOTO_CAPTURE(
        videoEnabled = true,
        audioEnabled = true,
        videoQuality = VideoQuality.HIGH,
        frameRate = 30,
        displayName = "High"
    )
}

/**
 * Mode-to-state mapping for default and transition states
 */
object ModeStateMapping {
    
    /** Get default state for each mode */
    fun getDefaultState(mode: AppMode): StreamState = when (mode) {
        AppMode.TOUR -> StreamState.AUDIO_ONLY
        AppMode.FOCUS -> StreamState.STANDBY
        AppMode.CONVERSATION -> StreamState.AUDIO_ONLY
    }
    
    /** Get state for AI analysis in each mode */
    fun getAnalysisState(mode: AppMode): StreamState = when (mode) {
        AppMode.TOUR -> StreamState.AI_RECOGNITION
        AppMode.FOCUS -> StreamState.AI_RECOGNITION
        AppMode.CONVERSATION -> StreamState.AUDIO_ONLY // No video for conversation
    }
    
    /** Get state for photo capture */
    fun getPhotoCaptureState(): StreamState = StreamState.PHOTO_CAPTURE
    
    /** Get return state after action completes */
    fun getReturnState(mode: AppMode): StreamState = getDefaultState(mode)
}
