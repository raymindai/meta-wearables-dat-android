/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// GuideUiState - Landmark Guide UI State

package com.meta.wearable.dat.externalsampleapps.landmarkguide.guide

data class GuideUiState(
    val isAnalyzing: Boolean = false,
    val isAutoAnalyzeEnabled: Boolean = false,
    val lastGuideText: String? = null,
    val lastError: String? = null,
)
