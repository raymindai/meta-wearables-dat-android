/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// WearablesViewModel - Core DAT SDK Integration
//
// This ViewModel demonstrates the core DAT API patterns for:
// - Device registration and unregistration using the DAT SDK
// - Permission management for wearable devices
// - Device discovery and state management
// - Integration with MockDeviceKit for testing

package com.meta.wearable.dat.externalsampleapps.landmarkguide.wearables

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.meta.wearable.dat.externalsampleapps.landmarkguide.bluetooth.BluetoothBatteryService

class WearablesViewModel(application: Application) : AndroidViewModel(application) {
  companion object {
    private const val TAG = "WearablesViewModel"
  }

  init {
    android.util.Log.e(TAG, "🆕 WearablesViewModel created!")
  }

  private val _uiState = MutableStateFlow(WearablesUiState())
  val uiState: StateFlow<WearablesUiState> = _uiState.asStateFlow()

  // AutoDeviceSelector automatically selects the first available wearable device
  val deviceSelector: DeviceSelector = AutoDeviceSelector()
  private var deviceSelectorJob: Job? = null

  // Device Session for monitoring device connection state
  private val deviceSession = DeviceSession(deviceSelector)
  val deviceSessionState: StateFlow<DeviceSessionState> = deviceSession.state

  // Bluetooth Battery Service
  private val bluetoothBatteryService = BluetoothBatteryService(application.applicationContext)
  val batteryLevel: StateFlow<Int?> = bluetoothBatteryService.batteryLevel
  
  fun refreshBattery() {
    bluetoothBatteryService.refreshBattery()
  }
  
  /**
   * Manually refresh device session - call this when user taps Active/Session box
   * This restarts the DeviceSession to pick up new connection state
   */
  fun refreshSession() {
    android.util.Log.e(TAG, "🔄 Manual session refresh requested")
    deviceSession.stop()
    deviceSession.start()
    android.util.Log.e(TAG, "🔄 DeviceSession restarted")
  }
  
  /**
   * Stop DeviceSession completely (no restart)
   * Call this when user presses Stop button
   */
  fun stopDeviceSession() {
    android.util.Log.e(TAG, "⏹️ Stopping DeviceSession completely")
    deviceSession.stop()
  }

  private var monitoringStarted = false
  private val deviceMonitoringJobs = mutableMapOf<DeviceIdentifier, Job>()

  fun startMonitoring() {
    if (monitoringStarted) {
      android.util.Log.e(TAG, "🟡 startMonitoring() already started, skipping")
      return
    }
    monitoringStarted = true
    android.util.Log.e(TAG, "🟢 startMonitoring() called - starting device monitoring")

    // Start Bluetooth battery monitoring
    bluetoothBatteryService.start()
    
    // Start device session to monitor connection state (STARTED/STOPPED)
    deviceSession.start()
    android.util.Log.e(TAG, "🔗 DeviceSession.start() called")
    
    // Poll device session state every 5 seconds (just read, don't restart)
    viewModelScope.launch {
      while (true) {
        kotlinx.coroutines.delay(5000)
        val currentState = deviceSession.state.value
        android.util.Log.d(TAG, "🔄 DeviceSessionState poll: $currentState")
      }
    }
    
    // Monitor device session state changes and log them
    viewModelScope.launch {
      deviceSession.state.collect { state ->
        android.util.Log.e(TAG, "📡 DeviceSessionState changed: $state")
      }
    }

    // Monitor device selector for active device
    deviceSelectorJob =
        viewModelScope.launch {
          deviceSelector.activeDevice(Wearables.devices).collect { device ->
            android.util.Log.e(TAG, "📱 Active device changed: ${device != null}")
            _uiState.update { it.copy(hasActiveDevice = device != null) }
          }
        }

    // This allows the app to react to registration changes (registered, unregistered, etc.)
    viewModelScope.launch {
      Wearables.registrationState.collect { value ->
        val previousState = _uiState.value.registrationState
        val showGettingStartedSheet =
            value is RegistrationState.Registered && previousState is RegistrationState.Registering
        _uiState.update {
          it.copy(registrationState = value, isGettingStartedSheetVisible = showGettingStartedSheet)
        }
      }
    }
    // This automatically updates when devices are discovered, connected, or disconnected
    viewModelScope.launch {
      Wearables.devices.collect { value ->
        val hasMockDevices = MockDeviceKit.getInstance(getApplication()).pairedDevices.isNotEmpty()
        _uiState.update {
          it.copy(devices = value.toList().toImmutableList(), hasMockDevices = hasMockDevices)
        }
        // Monitor device metadata for compatibility issues
        monitorDeviceCompatibility(value)
      }
    }
  }

  private fun monitorDeviceCompatibility(devices: Set<DeviceIdentifier>) {
    // Cancel monitoring jobs for devices that are no longer in the list
    val removedDevices = deviceMonitoringJobs.keys - devices
    removedDevices.forEach { deviceId ->
      deviceMonitoringJobs[deviceId]?.cancel()
      deviceMonitoringJobs.remove(deviceId)
    }

    // Start monitoring jobs only for new devices (not already being monitored)
    val newDevices = devices - deviceMonitoringJobs.keys
    newDevices.forEach { deviceId ->
      val job =
          viewModelScope.launch {
            Wearables.devicesMetadata[deviceId]?.collect { metadata ->
              if (
                  metadata.compatibility ==
                      com.meta.wearable.dat.core.types.DeviceCompatibility.DEVICE_UPDATE_REQUIRED
              ) {
                val deviceName = metadata.name.ifEmpty { deviceId }
                setRecentError("Device '$deviceName' requires an update to work with this app")
              }
            }
          }
      deviceMonitoringJobs[deviceId] = job
    }
  }

  fun startRegistration() {
    Wearables.startRegistration(getApplication())
  }

  fun startUnregistration() {
    Wearables.startUnregistration(getApplication())
  }

  fun navigateToStreaming(onRequestWearablesPermission: suspend (Permission) -> PermissionStatus) {
    viewModelScope.launch {
      val permission = Permission.CAMERA // Camera permission is required for streaming
      val result = Wearables.checkPermissionStatus(permission)

      // Handle the result
      result.onFailure { error, _ ->
        setRecentError("Permission check error: ${error.description}")
        return@launch
      }

      val permissionStatus = result.getOrNull()
      if (permissionStatus == PermissionStatus.Granted) {
        _uiState.update { it.copy(isStreaming = true) }
        return@launch
      }

      // Request permission
      val requestedPermissionStatus = onRequestWearablesPermission(permission)
      when (requestedPermissionStatus) {
        PermissionStatus.Denied -> {
          setRecentError("Permission denied")
        }
        PermissionStatus.Granted -> {
          _uiState.update { it.copy(isStreaming = true) }
        }
      }
    }
  }

  fun navigateToDeviceSelection() {
    _uiState.update { it.copy(isStreaming = false) }
  }

  fun showDebugMenu() {
    _uiState.update { it.copy(isDebugMenuVisible = true) }
  }

  fun hideDebugMenu() {
    _uiState.update { it.copy(isDebugMenuVisible = false) }
  }

  fun clearCameraPermissionError() {
    _uiState.update { it.copy(recentError = null) }
  }

  fun setRecentError(error: String) {
    _uiState.update { it.copy(recentError = error) }
  }

  fun showGettingStartedSheet() {
    _uiState.update { it.copy(isGettingStartedSheetVisible = true) }
  }

  fun hideGettingStartedSheet() {
    _uiState.update { it.copy(isGettingStartedSheetVisible = false) }
  }

  fun showResolutionTest() {
    _uiState.update { it.copy(isResolutionTestVisible = true) }
  }

  fun hideResolutionTest() {
    _uiState.update { it.copy(isResolutionTestVisible = false) }
  }

  override fun onCleared() {
    super.onCleared()
    // Cancel all device monitoring jobs when ViewModel is cleared
    deviceMonitoringJobs.values.forEach { it.cancel() }
    deviceMonitoringJobs.clear()
    deviceSelectorJob?.cancel()
    
    // Stop device session and Bluetooth battery service
    deviceSession.stop()
    bluetoothBatteryService.stop()
  }
}
