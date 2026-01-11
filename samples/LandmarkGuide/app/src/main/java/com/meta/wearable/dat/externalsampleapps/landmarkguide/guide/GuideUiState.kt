/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// GuideUiState - Landmark Guide UI State

package com.meta.wearable.dat.externalsampleapps.landmarkguide.guide

import android.graphics.Bitmap

/**
 * Represents a saved scene with its analysis result
 */
data class SavedScene(
    val id: Long = System.currentTimeMillis(),
    val thumbnail: Bitmap,
    val guideText: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Guide mode for AI analysis
 */
enum class GuideMode {
    TOUR,      // Saudi Arabia landmarks
    GENERAL,   // Describe everything
    TRANSLATE  // Arabic to English translation
}

data class GuideUiState(
    val isAnalyzing: Boolean = false,
    val isAutoAnalyzeEnabled: Boolean = false,
    val autoAnalyzeCountdown: Int = 0,  // Countdown in seconds (0-10)
    val lastGuideText: String? = null,
    val lastError: String? = null,
    val analyzedThumbnail: Bitmap? = null,  // Thumbnail of last analyzed frame
    val savedScenes: List<SavedScene> = emptyList(),  // Gallery of saved scenes
    val isGalleryVisible: Boolean = false,  // Show/hide saved scenes gallery
    val guideMode: GuideMode = GuideMode.TOUR,  // Current guide mode
)
