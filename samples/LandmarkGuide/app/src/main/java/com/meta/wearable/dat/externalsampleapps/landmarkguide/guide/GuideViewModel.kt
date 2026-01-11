/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// GuideViewModel - Landmark Guide Logic
//
// This ViewModel coordinates the camera stream, AI vision analysis,
// and voice guidance for the landmark guide feature.

package com.meta.wearable.dat.externalsampleapps.landmarkguide.guide

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.externalsampleapps.landmarkguide.ai.VisionAnalyzer
import com.meta.wearable.dat.externalsampleapps.landmarkguide.audio.VoiceGuidePlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class GuideViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "GuideViewModel"
        private const val AUTO_ANALYZE_INTERVAL_MS = 10_000L  // 10 seconds
    }

    private val visionAnalyzer = VisionAnalyzer()
    private val voicePlayer = VoiceGuidePlayer(application)

    private val _uiState = MutableStateFlow(GuideUiState())
    val uiState: StateFlow<GuideUiState> = _uiState.asStateFlow()

    private var autoAnalyzeJob: Job? = null
    private var currentFrame: Bitmap? = null
    private var lastGuideText: String? = null

    val isSpeaking = voicePlayer.isSpeaking

    /**
     * Updates the current frame from the camera stream
     */
    fun updateCurrentFrame(bitmap: Bitmap) {
        currentFrame = bitmap
    }

    /**
     * Analyzes the current frame and speaks the result
     */
    fun analyzeNow() {
        val frame = currentFrame
        if (frame == null) {
            Log.w(TAG, "No frame available for analysis")
            return
        }

        if (_uiState.value.isAnalyzing) {
            Log.d(TAG, "Analysis already in progress")
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isAnalyzing = true, lastError = null) }

            val result = visionAnalyzer.analyzeScene(frame)

            result.onSuccess { guideText ->
                // Avoid repeating the same guide
                if (guideText != lastGuideText) {
                    lastGuideText = guideText
                    _uiState.update { it.copy(
                        isAnalyzing = false,
                        lastGuideText = guideText
                    )}
                    voicePlayer.speak(guideText)
                } else {
                    _uiState.update { it.copy(isAnalyzing = false) }
                    Log.d(TAG, "Same guide text, skipping TTS")
                }
            }.onFailure { error ->
                Log.e(TAG, "Analysis failed", error)
                _uiState.update { it.copy(
                    isAnalyzing = false,
                    lastError = error.message ?: "Analysis failed"
                )}
            }
        }
    }

    /**
     * Starts automatic periodic analysis
     */
    fun startAutoAnalyze() {
        if (autoAnalyzeJob?.isActive == true) {
            return
        }

        _uiState.update { it.copy(isAutoAnalyzeEnabled = true) }

        autoAnalyzeJob = viewModelScope.launch {
            while (isActive) {
                delay(AUTO_ANALYZE_INTERVAL_MS)
                if (_uiState.value.isAutoAnalyzeEnabled && currentFrame != null) {
                    analyzeNow()
                }
            }
        }
        Log.d(TAG, "Auto-analyze started")
    }

    /**
     * Stops automatic periodic analysis
     */
    fun stopAutoAnalyze() {
        autoAnalyzeJob?.cancel()
        autoAnalyzeJob = null
        _uiState.update { it.copy(isAutoAnalyzeEnabled = false) }
        Log.d(TAG, "Auto-analyze stopped")
    }

    /**
     * Toggles auto-analyze mode
     */
    fun toggleAutoAnalyze() {
        if (_uiState.value.isAutoAnalyzeEnabled) {
            stopAutoAnalyze()
        } else {
            startAutoAnalyze()
        }
    }

    /**
     * Stops ongoing speech
     */
    fun stopSpeaking() {
        voicePlayer.stop()
    }

    /**
     * Clears the last guide text
     */
    fun clearLastGuide() {
        lastGuideText = null
        _uiState.update { it.copy(lastGuideText = null) }
    }

    override fun onCleared() {
        super.onCleared()
        autoAnalyzeJob?.cancel()
        voicePlayer.shutdown()
    }
}
