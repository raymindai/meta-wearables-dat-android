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
        private const val COUNTDOWN_INTERVAL_MS = 1_000L  // 1 second
    }

    private val visionAnalyzer = VisionAnalyzer()
    private val voicePlayer = VoiceGuidePlayer(application)

    private val _uiState = MutableStateFlow(GuideUiState())
    val uiState: StateFlow<GuideUiState> = _uiState.asStateFlow()

    private var autoAnalyzeJob: Job? = null
    private var countdownJob: Job? = null
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
     * @param saveScene If true, saves the scene to gallery
     */
    fun analyzeNow(saveScene: Boolean = false) {
        val frame = currentFrame?.copy(Bitmap.Config.ARGB_8888, false)
        if (frame == null) {
            Log.w(TAG, "No frame available for analysis")
            return
        }

        if (_uiState.value.isAnalyzing) {
            Log.d(TAG, "Analysis already in progress")
            return
        }

        // Create thumbnail for display
        val thumbnail = createThumbnail(frame)

        viewModelScope.launch {
            _uiState.update { it.copy(
                isAnalyzing = true, 
                lastError = null,
                analyzedThumbnail = thumbnail
            )}

            val result = visionAnalyzer.analyzeScene(
                frame,
                mode = when (_uiState.value.guideMode) {
                    GuideMode.TOUR -> VisionAnalyzer.GuideMode.TOUR
                    GuideMode.GENERAL -> VisionAnalyzer.GuideMode.GENERAL
                    GuideMode.TRANSLATE -> VisionAnalyzer.GuideMode.TRANSLATE
                }
            )

            result.onSuccess { guideText ->
                // Avoid repeating the same guide (only for auto-analyze)
                val shouldSpeak = saveScene || guideText != lastGuideText
                
                if (shouldSpeak) {
                    lastGuideText = guideText
                    _uiState.update { it.copy(
                        isAnalyzing = false,
                        lastGuideText = guideText
                    )}
                    voicePlayer.speak(guideText)
                    
                    // Save scene if requested (manual analyze)
                    if (saveScene) {
                        val savedScene = SavedScene(
                            thumbnail = thumbnail,
                            guideText = guideText
                        )
                        _uiState.update { state ->
                            state.copy(
                                savedScenes = listOf(savedScene) + state.savedScenes
                            )
                        }
                        Log.d(TAG, "Scene saved to gallery")
                    }
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
     * Creates a small thumbnail from a bitmap
     */
    private fun createThumbnail(bitmap: Bitmap): Bitmap {
        val maxSize = 200
        val scale = maxSize.toFloat() / maxOf(bitmap.width, bitmap.height)
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt(),
            (bitmap.height * scale).toInt(),
            true
        )
    }

    /**
     * Starts automatic periodic analysis with countdown
     */
    fun startAutoAnalyze() {
        if (autoAnalyzeJob?.isActive == true) {
            return
        }

        _uiState.update { it.copy(isAutoAnalyzeEnabled = true, autoAnalyzeCountdown = 10) }

        // Countdown timer job
        countdownJob = viewModelScope.launch {
            while (isActive && _uiState.value.isAutoAnalyzeEnabled) {
                for (i in 10 downTo 1) {
                    if (!_uiState.value.isAutoAnalyzeEnabled) break
                    _uiState.update { it.copy(autoAnalyzeCountdown = i) }
                    delay(COUNTDOWN_INTERVAL_MS)
                }
                
                // Trigger analysis when countdown reaches 0
                if (_uiState.value.isAutoAnalyzeEnabled && currentFrame != null) {
                    _uiState.update { it.copy(autoAnalyzeCountdown = 0) }
                    analyzeNow(saveScene = false)
                    // Wait for analysis to complete before restarting countdown
                    while (_uiState.value.isAnalyzing) {
                        delay(100)
                    }
                }
            }
        }
        
        Log.d(TAG, "Auto-analyze started with countdown")
    }

    /**
     * Stops automatic periodic analysis
     */
    fun stopAutoAnalyze() {
        autoAnalyzeJob?.cancel()
        autoAnalyzeJob = null
        countdownJob?.cancel()
        countdownJob = null
        _uiState.update { it.copy(isAutoAnalyzeEnabled = false, autoAnalyzeCountdown = 0) }
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
     * Manual analyze - saves the scene to gallery
     */
    fun analyzeAndSave() {
        analyzeNow(saveScene = true)
    }

    /**
     * Shows the saved scenes gallery
     */
    fun showGallery() {
        _uiState.update { it.copy(isGalleryVisible = true) }
    }

    /**
     * Hides the saved scenes gallery
     */
    fun hideGallery() {
        _uiState.update { it.copy(isGalleryVisible = false) }
    }

    /**
     * Deletes a saved scene
     */
    fun deleteScene(sceneId: Long) {
        _uiState.update { state ->
            state.copy(
                savedScenes = state.savedScenes.filter { it.id != sceneId }
            )
        }
    }

    /**
     * Speaks a saved scene's guide text
     */
    fun speakScene(scene: SavedScene) {
        voicePlayer.speak(scene.guideText)
    }

    /**
     * Toggles between Tour Mode and General Mode
     */
    fun toggleMode() {
        _uiState.update { state ->
            val nextMode = when (state.guideMode) {
                GuideMode.TOUR -> GuideMode.GENERAL
                GuideMode.GENERAL -> GuideMode.TRANSLATE
                GuideMode.TRANSLATE -> GuideMode.TOUR
            }
            state.copy(guideMode = nextMode)
        }
        // Clear last guide when switching modes
        lastGuideText = null
    }

    /**
     * Sets a specific guide mode
     */
    fun setMode(mode: GuideMode) {
        if (_uiState.value.guideMode != mode) {
            _uiState.update { it.copy(guideMode = mode) }
            lastGuideText = null
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
        _uiState.update { it.copy(lastGuideText = null, analyzedThumbnail = null) }
    }

    /**
     * Cleanup all resources - call this when leaving the guide screen
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up GuideViewModel resources")
        stopAutoAnalyze()
        stopSpeaking()
        clearLastGuide()
        currentFrame = null
    }

    override fun onCleared() {
        super.onCleared()
        cleanup()
        voicePlayer.shutdown()
    }
}
