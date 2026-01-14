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
    
    // NOTE: deviceSession.start() is NOT called automatically anymore
    // User must explicitly start the glasses session when needed
    // This prevents "Experience Started" from triggering on app launch
    android.util.Log.e(TAG, "⏸️ DeviceSession NOT auto-started (manual trigger only)")
    
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

  fun showLiveTranslation() {
    _uiState.update { it.copy(isLiveTranslationVisible = true) }
  }

  fun hideLiveTranslation() {
    _uiState.update { it.copy(isLiveTranslationVisible = false) }
  }

  fun showMajlis() {
    _uiState.update { it.copy(isMajlisVisible = true) }
  }

  fun hideMajlis() {
    _uiState.update { it.copy(isMajlisVisible = false) }
  }
  
  // =============================================
  // GLOBAL WAKE WORD SYSTEM
  // =============================================
  
  private var wakeWordService: com.meta.wearable.dat.externalsampleapps.landmarkguide.voice.PorcupineWakeWordService? = null
  private var openAIService: com.meta.wearable.dat.externalsampleapps.landmarkguide.translation.OpenAITranslationService? = null
  
  /**
   * Initialize global wake word detection using Picovoice Porcupine.
   * Call this once at app startup.
   */
  fun initializeWakeWord() {
    val context = getApplication<Application>().applicationContext
    
    // Initialize TTS for responses
    openAIService = com.meta.wearable.dat.externalsampleapps.landmarkguide.translation.OpenAITranslationService(context)
    
    // Initialize Porcupine wake word service (accurate, offline)
    wakeWordService = com.meta.wearable.dat.externalsampleapps.landmarkguide.voice.PorcupineWakeWordService(
      context = context,
      onWakeWordDetected = {
        android.util.Log.d(TAG, "🎤 Wake word detected! Responding with Halla Walla")
        handleWakeWordDetected()
      },
      onStatusChange = { status ->
        _uiState.update { it.copy(wakeWordStatus = status) }
      }
    )
    
    // Initialize Porcupine
    wakeWordService?.initialize()
  }
  
  /**
   * Start listening for wake word globally.
   */
  fun startWakeWordListening() {
    wakeWordService?.startListening()
    _uiState.update { it.copy(isWakeWordListening = true) }
  }
  
  /**
   * Stop wake word listening.
   */
  fun stopWakeWordListening() {
    wakeWordService?.stopListening()
    _uiState.update { it.copy(isWakeWordListening = false) }
  }
  
  // Voice command STT service (use Deepgram for commands)
  private var commandSTT: com.meta.wearable.dat.externalsampleapps.landmarkguide.translation.StreamingSpeechService? = null
  private var commandAudioCapture: com.meta.wearable.dat.externalsampleapps.landmarkguide.audio.BluetoothScoAudioCapture? = null
  private var commandTimeoutJob: kotlinx.coroutines.Job? = null
  
  // Wake word extension phrases (saying these extends the 4-second timeout)
  private val EXTEND_PHRASES = listOf("halla walla", "hallawalla", "hello", "hey", "wait", "hold on")
  
  /**
   * Handle wake word detection - stop all other tasks, play listening sound, wait for command.
   */
  private fun handleWakeWordDetected() {
    // Stop wake word listening during response
    wakeWordService?.stopListening()
    _uiState.update { it.copy(isVoiceCommandMode = true) }
    
    // Log for debugging
    android.util.Log.d(TAG, "🎤 Wake word triggered - entering command mode")
    
    viewModelScope.launch {
      // Play short acknowledgment: "Halla Walla!" (listening sound)
      openAIService?.speak(
        text = "Halla Walla!",
        languageCode = "en",
        useBluetooth = true
      )
      
      // Small pause to indicate listening
      kotlinx.coroutines.delay(300)
      
      // Start voice command recognition
      startVoiceCommandRecognition()
    }
  }
  
  /**
   * Start listening for voice commands (Conversation Mode / Tour Mode / Generic Mode)
   * This is EXCLUSIVE - no other STT/translation should run during this time.
   */
  private fun startVoiceCommandRecognition() {
    val context = getApplication<Application>().applicationContext
    
    android.util.Log.d(TAG, "🎧 Starting command recognition (4-sec timeout)...")
    
    // Initialize Deepgram for command recognition
    commandSTT = com.meta.wearable.dat.externalsampleapps.landmarkguide.translation.StreamingSpeechService()
    
    commandSTT?.onTranscript = { text, isFinal ->
      if (text.isNotBlank()) {
        val lowerText = text.lowercase()
        
        // Check if user said an extension phrase - reset timeout
        if (EXTEND_PHRASES.any { lowerText.contains(it) }) {
          android.util.Log.d(TAG, "🔄 Timeout extended - heard: '$text'")
          resetCommandTimeout()
        }
        
        if (isFinal) {
          android.util.Log.d(TAG, "🎯 Voice command: $text")
          processVoiceCommand(text)
        }
      }
    }
    
    commandSTT?.startListening("en")
    
    // Start audio capture
    commandAudioCapture = com.meta.wearable.dat.externalsampleapps.landmarkguide.audio.BluetoothScoAudioCapture(context)
    commandAudioCapture?.setListener(object : com.meta.wearable.dat.externalsampleapps.landmarkguide.audio.BluetoothScoAudioCapture.AudioCaptureListener {
      override fun onAudioData(data: ByteArray, size: Int) {
        commandSTT?.sendAudio(data.copyOf(size))
      }
      override fun onScoConnected() {}
      override fun onScoDisconnected() {}
      override fun onError(message: String) {}
    })
    commandAudioCapture?.startRecording()
    
    // Start timeout
    resetCommandTimeout()
  }
  
  /**
   * Reset/extend the command timeout to 4 seconds from now.
   */
  private fun resetCommandTimeout() {
    commandTimeoutJob?.cancel()
    commandTimeoutJob = viewModelScope.launch {
      kotlinx.coroutines.delay(4000)
      if (_uiState.value.isVoiceCommandMode) {
        android.util.Log.d(TAG, "⏱️ Voice command timeout - playing exit sound")
        
        // Stop command recognition
        stopVoiceCommandRecognition()
        
        // Play exit beep sound (short "hmm" or similar)
        openAIService?.speak(
          text = "Hmm",
          languageCode = "en",
          useBluetooth = true
        )
        
        // Small delay then resume wake word listening
        kotlinx.coroutines.delay(500)
        wakeWordService?.startListening()
      }
    }
  }
  
  /**
   * Process recognized voice command and switch mode.
   */
  private fun processVoiceCommand(command: String) {
    val lowerCommand = command.lowercase()
    android.util.Log.d(TAG, "🎯 Processing command: $lowerCommand")
    
    val mode = when {
      // Conversation Mode variants
      lowerCommand.contains("conversation") || 
      lowerCommand.contains("majlis") ||
      lowerCommand.contains("chat") -> AppMode.CONVERSATION
      
      // Tour Mode variants  
      lowerCommand.contains("tour") ||
      lowerCommand.contains("guide") ||
      lowerCommand.contains("travel") -> AppMode.TOUR
      
      // Generic Mode variants
      lowerCommand.contains("generic") ||
      lowerCommand.contains("normal") ||
      lowerCommand.contains("home") ||
      lowerCommand.contains("main") -> AppMode.GENERIC
      
      else -> null
    }
    
    if (mode != null) {
      // Stop voice command listening
      stopVoiceCommandRecognition()
      
      // Switch mode
      switchMode(mode)
      
      // Announce mode change
      viewModelScope.launch {
        val modeName = when (mode) {
          AppMode.CONVERSATION -> "Conversation Mode activated"
          AppMode.TOUR -> "Tour Mode activated"
          AppMode.GENERIC -> "Generic Mode activated"
        }
        openAIService?.speak(modeName, "en", useBluetooth = true)
        
        // Resume wake word listening after announcement
        kotlinx.coroutines.delay(500)
        wakeWordService?.startListening()
      }
    }
  }
  
  /**
   * Stop voice command recognition.
   */
  private fun stopVoiceCommandRecognition() {
    commandSTT?.stopListening()
    commandSTT = null
    commandAudioCapture?.stop()
    commandAudioCapture = null
    _uiState.update { it.copy(isVoiceCommandMode = false) }
  }
  
  /**
   * Switch app mode via voice command.
   */
  fun switchMode(mode: AppMode) {
    _uiState.update { it.copy(currentMode = mode) }
    
    // Close all mode screens first
    _uiState.update { 
      it.copy(
        isMajlisVisible = false, 
        isTourModeVisible = false, 
        isGenericModeVisible = false,
        isLiveTranslationVisible = false
      ) 
    }
    
    // Navigate to appropriate screen
    when (mode) {
      AppMode.CONVERSATION -> {
        // Show Majlis for conversation mode
        _uiState.update { it.copy(isMajlisVisible = true) }
      }
      AppMode.TOUR -> {
        // Tour mode - show tour guide screen
        _uiState.update { it.copy(isTourModeVisible = true) }
      }
      AppMode.GENERIC -> {
        // Generic mode - show generic AI screen
        _uiState.update { it.copy(isGenericModeVisible = true) }
      }
    }
    
    android.util.Log.d(TAG, "🔄 Mode switched to: $mode")
  }
  
  fun hideTourMode() {
    _uiState.update { it.copy(isTourModeVisible = false) }
  }
  
  fun hideGenericMode() {
    _uiState.update { it.copy(isGenericModeVisible = false) }
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
    
    // Cleanup wake word service
    wakeWordService?.destroy()
    wakeWordService = null
  }
}

