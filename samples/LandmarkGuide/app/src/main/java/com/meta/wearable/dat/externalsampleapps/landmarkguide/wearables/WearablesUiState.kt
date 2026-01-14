/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// WearablesUiState - DAT API State Management
//
// This data class aggregates DAT API state for the UI layer

package com.meta.wearable.dat.externalsampleapps.landmarkguide.wearables

import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

// Global App Modes (controlled by voice commands)
enum class AppMode {
    CONVERSATION,  // Majlis - Multi-user translation
    TOUR,          // Tour guide mode
    GENERIC        // Default - Landmark recognition
}

data class WearablesUiState(
    val registrationState: RegistrationState = RegistrationState.Unavailable(),
    val devices: ImmutableList<DeviceIdentifier> = persistentListOf(),
    val recentError: String? = null,
    val isStreaming: Boolean = false,
    val hasMockDevices: Boolean = false,
    val isDebugMenuVisible: Boolean = false,
    val isGettingStartedSheetVisible: Boolean = false,
    val hasActiveDevice: Boolean = false,
    val isResolutionTestVisible: Boolean = false,
    val isLiveTranslationVisible: Boolean = false,
    val isMajlisVisible: Boolean = false,
    // Mode screens
    val isTourModeVisible: Boolean = false,
    val isGenericModeVisible: Boolean = false,
    // Wake word & mode states
    val isWakeWordListening: Boolean = false,
    val wakeWordStatus: String = "IDLE",
    val isVoiceCommandMode: Boolean = false,  // True after "Halla Walla"
    val currentMode: AppMode = AppMode.GENERIC,
) {
  val isRegistered: Boolean = registrationState is RegistrationState.Registered || hasMockDevices
}

