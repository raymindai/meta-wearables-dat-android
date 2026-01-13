/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.landmarkguide.ui

import android.app.Application
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.externalsampleapps.landmarkguide.audio.BluetoothScoAudioCapture
import com.meta.wearable.dat.externalsampleapps.landmarkguide.stream.StreamState
import com.meta.wearable.dat.externalsampleapps.landmarkguide.stream.StreamViewModel
import com.meta.wearable.dat.externalsampleapps.landmarkguide.wearables.WearablesViewModel
import com.meta.wearable.dat.externalsampleapps.landmarkguide.voice.WakeWordService
import com.meta.wearable.dat.externalsampleapps.landmarkguide.voice.VoiceCommandProcessor
import com.meta.wearable.dat.externalsampleapps.landmarkguide.mode.AppModeManager
import com.meta.wearable.dat.externalsampleapps.landmarkguide.BuildConfig
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Test screen for measuring streaming resolution and photo capture performance
 * This screen creates its own StreamViewModel, independent of GuideScreen
 */
@Composable
fun ResolutionTestScreen(
    wearablesViewModel: WearablesViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    
    // Create own StreamViewModel - independent of GuideScreen
    val streamViewModel: StreamViewModel = viewModel(
        factory = StreamViewModel.Factory(application, wearablesViewModel),
        key = "ResolutionTestStreamViewModel" // Unique key to ensure separate instance
    )
    // Disable auto-navigation for test screen - we manually control everything
    streamViewModel.disableNavigation = true
    val streamUiState by streamViewModel.uiState.collectAsStateWithLifecycle()
    
    // Test results
    var testResults by remember { mutableStateOf(listOf<String>()) }
    
    // Microphone state
    var micStatus by remember { mutableStateOf("OFF") }
    var micVolume by remember { mutableStateOf(0) }
    var scoCapture by remember { mutableStateOf<BluetoothScoAudioCapture?>(null) }
    var micSource by remember { mutableStateOf("SCO") } // "SCO" or "Phone"
    var micDropdownExpanded by remember { mutableStateOf(false) }
    
    // Device info state
    var deviceName by remember { mutableStateOf<String?>(null) }
    var batteryLevel by remember { mutableStateOf<Int?>(null) }
    
    // Voice Command state
    var wakeWordStatus by remember { mutableStateOf("OFF") }
    var lastCommand by remember { mutableStateOf<String?>(null) }
    var voiceVolume by remember { mutableStateOf(0) }
    val appModeManager = remember { AppModeManager() }
    val currentMode by appModeManager.currentMode.collectAsStateWithLifecycle()
    
    // Wake Word and Voice Command services
    var wakeWordService by remember { mutableStateOf<WakeWordService?>(null) }
    var voiceCommandProcessor by remember { mutableStateOf<VoiceCommandProcessor?>(null) }
    
    // Collect device info from DAT SDK
    LaunchedEffect(Unit) {
        // Start monitoring devices AND device session
        wearablesViewModel.startMonitoring()
        
        wearablesViewModel.deviceSelector.activeDevice(com.meta.wearable.dat.core.Wearables.devices).collect { device ->
            device?.let { deviceId ->
                com.meta.wearable.dat.core.Wearables.devicesMetadata[deviceId]?.collect { metadata ->
                    deviceName = metadata.name.ifEmpty { deviceId.toString() }
                    // Note: batteryLevel not available in public DAT SDK metadata
                }
            } ?: run {
                deviceName = null
                batteryLevel = null
            }
        }
    }
    
    // Log Active device changes to on-screen log
    val wearablesState = wearablesViewModel.uiState.collectAsStateWithLifecycle().value
    val hasActive = wearablesState.hasActiveDevice
    LaunchedEffect(hasActive) {
        testResults = testResults + "🔌 Active: ${if (hasActive) "YES" else "NO"}"
    }
    
    // Log Session state changes to on-screen log
    val sessionState = wearablesViewModel.deviceSessionState.collectAsStateWithLifecycle(
        initialValue = com.meta.wearable.dat.core.session.DeviceSessionState.STOPPED
    ).value
    LaunchedEffect(sessionState) {
        testResults = testResults + "🔗 Session: ${if (sessionState == com.meta.wearable.dat.core.session.DeviceSessionState.STARTED) "ON" else "OFF"}"
    }
    
    fun log(message: String) {
        testResults = testResults + message
    }
    
    // Helper function to start SCO microphone
    fun startScoMic() {
        scoCapture?.stop() // Stop existing if any
        val capture = BluetoothScoAudioCapture(context)
        capture.setListener(object : BluetoothScoAudioCapture.AudioCaptureListener {
            override fun onAudioData(data: ByteArray, size: Int) {
                var sum = 0.0
                for (i in 0 until minOf(size, 100) step 2) {
                    val sample = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
                    sum += sample * sample
                }
                micVolume = kotlin.math.sqrt(sum / 50).toInt()
            }
            override fun onScoConnected() {
                micStatus = "CONNECTED"
                capture.startRecording()
            }
            override fun onScoDisconnected() {
                micStatus = "DISCONNECTED"
            }
            override fun onError(message: String) {
                micStatus = "ERROR"
            }
        })
        capture.startScoConnection()
        scoCapture = capture
        micStatus = "CONNECTING..."
    }
    
    // Cleanup on dispose only (don't auto-start)
    DisposableEffect(Unit) {
        onDispose {
            scoCapture?.stop()
            wakeWordService?.destroy()
            voiceCommandProcessor?.destroy()
        }
    }
    
    // Auto-log capture timing when photo captured
    LaunchedEffect(streamUiState.photoCaptureTime) {
        if (streamUiState.photoCaptureTime > 0) {
            val state = streamUiState.currentStreamState?.displayName ?: "?"
            log("✅ $state → Capture: ${streamUiState.photoCaptureTime}ms")
        } else if (streamUiState.photoCaptureTime < 0) {
            log("❌ Capture FAILED")
        }
    }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .padding(12.dp)
    ) {
        // Header with Back button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "📊 Test Mode",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("← Back", fontSize = 12.sp)
            }
            
            // Reset button - stops stream and SCO mic
            Button(
                onClick = {
                    log("🔄 RESET")
                    scoCapture?.stop()
                    micStatus = "OFF"
                    micVolume = 0
                    streamViewModel.stopStream()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5722)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("🔄 Reset", fontSize = 12.sp)
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Video Preview + Captured Photo Row
        Row(
            modifier = Modifier.fillMaxWidth().height(120.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Stream Thumbnail
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("📹 Stream", color = Color.Gray, fontSize = 10.sp)
                Spacer(modifier = Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E1E1E))
                        .border(2.dp, Color(0xFF4CAF50), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (streamUiState.videoFrame != null) {
                        Image(
                            bitmap = streamUiState.videoFrame!!.asImageBitmap(),
                            contentDescription = "Stream preview",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        // Show resolution overlay
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${streamUiState.videoFrame!!.width}×${streamUiState.videoFrame!!.height}",
                                color = Color.White,
                                fontSize = 8.sp
                            )
                        }
                    } else {
                        Text("No Video", color = Color.Gray, fontSize = 10.sp)
                    }
                }
            }
            
            // Captured Photo Thumbnail
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("📸 Photo", color = Color.Gray, fontSize = 10.sp)
                Spacer(modifier = Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E1E1E))
                        .border(2.dp, Color(0xFFE91E63), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (streamUiState.capturedPhoto != null) {
                        Image(
                            bitmap = streamUiState.capturedPhoto!!.asImageBitmap(),
                            contentDescription = "Captured photo",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        // Show resolution overlay
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${streamUiState.capturedPhoto!!.width}×${streamUiState.capturedPhoto!!.height}",
                                color = Color.White,
                                fontSize = 8.sp
                            )
                        }
                    } else {
                        Text("No Photo", color = Color.Gray, fontSize = 10.sp)
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(6.dp))
        
        // Status Row
        Row(
            modifier = Modifier.fillMaxWidth().height(60.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // State
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E1E1E))
                    .padding(6.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("State", color = Color.Gray, fontSize = 9.sp)
                Text(
                    text = streamUiState.currentStreamState?.displayName ?: "Off",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            
            // Status
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E1E1E))
                    .padding(6.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("📹 Streaming", color = Color.Gray, fontSize = 9.sp)
                Text(
                    text = streamUiState.streamSessionState.name.take(8),
                    color = if (streamUiState.streamSessionState == StreamSessionState.STREAMING) 
                        Color.Green else Color.Yellow,
                    fontSize = 11.sp
                )
            }
            
            // Device
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E1E1E))
                    .padding(6.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("📱 Device", color = Color.Gray, fontSize = 9.sp)
                Text(
                    text = deviceName?.take(8) ?: "None",
                    color = if (deviceName != null) Color.Cyan else Color.Gray,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            
            // Active Device Status (replaces broken battery)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E1E1E))
                    .padding(6.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("🔌 Active", color = Color.Gray, fontSize = 9.sp)
                val wearablesState = wearablesViewModel.uiState.collectAsStateWithLifecycle().value
                val hasActive = wearablesState.hasActiveDevice
                Text(
                    text = if (hasActive) "YES" else "NO",
                    color = if (hasActive) Color.Green else Color.Red,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            
            // Session (DeviceSessionState: STARTED/STOPPED)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E1E1E))
                    .padding(6.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("🔗 Session", color = Color.Gray, fontSize = 9.sp)
                val sessionState = wearablesViewModel.deviceSessionState.collectAsStateWithLifecycle(
                    initialValue = com.meta.wearable.dat.core.session.DeviceSessionState.STOPPED
                ).value
                Text(
                    text = when (sessionState) {
                        com.meta.wearable.dat.core.session.DeviceSessionState.STARTED -> "ON"
                        com.meta.wearable.dat.core.session.DeviceSessionState.STOPPED -> "OFF"
                    },
                    color = when (sessionState) {
                        com.meta.wearable.dat.core.session.DeviceSessionState.STARTED -> Color.Green
                        com.meta.wearable.dat.core.session.DeviceSessionState.STOPPED -> Color.Gray
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            
            // Stream Error
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E1E1E))
                    .padding(6.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("⚠️ Error", color = Color.Gray, fontSize = 9.sp)
                val lastError = streamUiState.lastStreamError
                Text(
                    text = lastError?.take(6) ?: "None",
                    color = if (lastError != null) Color.Red else Color.Green,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Separate Mic Controls (independent of video streaming)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🎤 Microphone", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            // Glasses status
            Text(
                text = if (deviceName != null) "👓 ${deviceName?.take(6)}" else "👓 None",
                color = if (deviceName != null) Color.Green else Color.Gray,
                fontSize = 11.sp
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    log("🎤 SCO Mic START ($micSource)")
                    startScoMic()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                modifier = Modifier.weight(0.7f),
                contentPadding = PaddingValues(6.dp)
            ) {
                Text("ON", fontSize = 10.sp)
            }
            
            Button(
                onClick = {
                    log("🎤 SCO Mic STOP")
                    scoCapture?.stop()
                    micStatus = "OFF"
                    micVolume = 0
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                modifier = Modifier.weight(0.7f),
                contentPadding = PaddingValues(6.dp)
            ) {
                Text("OFF", fontSize = 10.sp)
            }
            
            // SCO Mic Status and Volume
            Row(
                modifier = Modifier
                    .weight(1.2f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF1E1E1E))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(micStatus, color = if (micStatus == "CONNECTED") Color.Green else Color.Gray, fontSize = 9.sp)
                Text("🔊$micVolume", color = if (micVolume > 100) Color.Green else Color.Gray, fontSize = 9.sp)
            }
            
            // Mic Source Dropdown
            Box(modifier = Modifier.weight(0.9f)) {
                Button(
                    onClick = { micDropdownExpanded = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(6.dp)
                ) {
                    Text(micSource, fontSize = 9.sp)
                }
                DropdownMenu(
                    expanded = micDropdownExpanded,
                    onDismissRequest = { micDropdownExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("SCO (Glasses)", fontSize = 11.sp) },
                        onClick = {
                            micSource = "SCO"
                            micDropdownExpanded = false
                            log("🎤 Mic source: SCO")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Phone", fontSize = 11.sp) },
                        onClick = {
                            micSource = "Phone"
                            micDropdownExpanded = false
                            log("🎤 Mic source: Phone")
                        }
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Voice Command Section
        Text("🎙️ Voice Command", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        
        // Status display - single line
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1E1E1E))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(wakeWordStatus, color = if (wakeWordStatus.contains("LISTENING")) Color.Green else Color.White, fontSize = 10.sp)
            Text("${currentMode.name}", color = Color.Cyan, fontSize = 10.sp)
            Text("🔊$voiceVolume", color = if (voiceVolume > 100) Color.Green else Color.Gray, fontSize = 10.sp)
            lastCommand?.let { Text(it, color = Color.Yellow, fontSize = 10.sp) }
        }
        
        // Voice Command buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = {
                    log("🎙️ Starting Wake Word listener...")
                    
                    // Initialize Voice Command Processor first
                    val cmdProcessor = VoiceCommandProcessor(context) { command ->
                        lastCommand = command.name
                        log("📣 Command: ${command.name}")
                        when (command) {
                            VoiceCommandProcessor.VoiceCommand.TRANSLATION_MODE -> {
                                appModeManager.switchMode(AppModeManager.AppMode.TRANSLATION_MODE)
                            }
                            VoiceCommandProcessor.VoiceCommand.GUIDE_MODE -> {
                                appModeManager.switchMode(AppModeManager.AppMode.GUIDE_MODE)
                            }
                            VoiceCommandProcessor.VoiceCommand.STOP -> {
                                appModeManager.reset()
                            }
                            else -> {}
                        }
                        // Resume wake word listening after command
                        wakeWordService?.startListening()
                        wakeWordStatus = "LISTENING"
                    }
                    cmdProcessor.initialize()
                    voiceCommandProcessor = cmdProcessor
                    
                    // Initialize Wake Word Service (Vosk - no API key needed)
                    val wakeWord = WakeWordService(
                        context = context,
                        onWakeWordDetected = {
                            log("🎤 Hey Human detected!")
                            wakeWordStatus = "✅ DETECTED!"
                            // Stop wake word, start command listening
                            wakeWordService?.stopListening()
                            voiceCommandProcessor?.startListening()
                        },
                        onVolumeLevel = { volume ->
                            voiceVolume = volume
                        },
                        onStatusChange = { status ->
                            wakeWordStatus = status
                            log("🎙️ $status")
                        }
                    )
                    wakeWord.initialize()
                    wakeWord.startListening()
                    wakeWordService = wakeWord
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0)),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(8.dp)
            ) {
                Text("🎙️ Start", fontSize = 11.sp)
            }
            
            Button(
                onClick = {
                    log("🎙️ Stopping Wake Word listener")
                    wakeWordService?.destroy()
                    wakeWordService = null
                    voiceCommandProcessor?.destroy()
                    voiceCommandProcessor = null
                    wakeWordStatus = "OFF"
                    appModeManager.reset()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(8.dp)
            ) {
                Text("🎙️ Stop", fontSize = 11.sp)
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Video Stream Header with Silent Mode toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("📹 Video Stream", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            
            // Silent Mode Change toggle
            var silentMode by remember { mutableStateOf(streamViewModel.silentModeChange) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "🔇 Silent",
                    color = if (silentMode) Color.Cyan else Color.Gray,
                    fontSize = 10.sp
                )
                Switch(
                    checked = silentMode,
                    onCheckedChange = { 
                        silentMode = it
                        streamViewModel.silentModeChange = it
                        log(if (it) "🔇 Silent Mode ON" else "🔊 Silent Mode OFF")
                    },
                    modifier = Modifier.height(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Helper function: start if not running, switch if running
            fun startOrSwitch(state: StreamState) {
                if (streamUiState.streamSessionState == StreamSessionState.STREAMING) {
                    log("🔄 Switch to ${state.displayName}")
                    streamViewModel.switchToState(state)
                } else {
                    log("▶️ Start ${state.displayName}")
                    streamViewModel.startStreamWithState(state)
                }
            }
            
            // Standby (LOW @ 10fps)
            Button(
                onClick = { startOrSwitch(StreamState.STANDBY) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(4.dp)
            ) {
                Text("LOW\n10fps", fontSize = 9.sp, lineHeight = 10.sp)
            }
            
            // AI Recognition (MEDIUM @ 30fps)
            Button(
                onClick = { startOrSwitch(StreamState.AI_RECOGNITION) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(4.dp)
            ) {
                Text("MED\n30fps", fontSize = 9.sp, lineHeight = 10.sp)
            }
            
            // Photo Capture (HIGH @ 30fps)
            Button(
                onClick = { startOrSwitch(StreamState.PHOTO_CAPTURE) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336)),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(4.dp)
            ) {
                Text("HIGH\n30fps", fontSize = 9.sp, lineHeight = 10.sp)
            }
            
            // STOP - works like Reset (stops everything)
            Button(
                onClick = {
                    log("⏹️ STOP (Reset All)")
                    scoCapture?.stop()
                    micStatus = "OFF"
                    micVolume = 0
                    streamViewModel.stopStream()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(4.dp)
            ) {
                Text("STOP", fontSize = 9.sp)
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Capture Section
        Text("📸 Capture", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Capture Button
            Button(
                onClick = {
                    val currentState = streamUiState.currentStreamState
                    val isStreaming = streamUiState.streamSessionState == StreamSessionState.STREAMING
                    
                    if (isStreaming && currentState?.videoEnabled == true) {
                        log("📸 Capture (current: ${currentState.displayName})")
                        streamViewModel.capturePhoto()
                    } else {
                        log("📸 Capture (starting LOW stream...)")
                        streamViewModel.captureWithReturn(StreamState.STANDBY)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BCD4)),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(8.dp)
            ) {
                Text("Capture", fontSize = 11.sp)
            }
            
            // Continuous Capture Button (no session close)
            Button(
                onClick = {
                    log("📸 Continuous Capture (session stays open)")
                    streamViewModel.continuousCapture()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(4.dp)
            ) {
                Text("Cont.", fontSize = 9.sp)
            }
            
            // Stop button (closes session and resets DeviceSession)
            Button(
                onClick = {
                    log("⏹️ Stop Stream")
                    streamViewModel.stopStream()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5722)),
                modifier = Modifier.weight(0.7f),
                contentPadding = PaddingValues(4.dp)
            ) {
                Text("Stop", fontSize = 9.sp)
            }
            
            // Capture time display
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF1E1E1E))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                val captureTime = streamUiState.photoCaptureTime
                Text(
                    text = when {
                        captureTime > 0 -> "✅ ${captureTime}ms"
                        captureTime < 0 -> "❌ Failed"
                        else -> "⏱️ --"
                    },
                    color = when {
                        captureTime > 0 -> Color.Green
                        captureTime < 0 -> Color.Red
                        else -> Color.Gray
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Log Section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Log", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            TextButton(
                onClick = { testResults = emptyList() },
                contentPadding = PaddingValues(4.dp)
            ) {
                Text("Clear", color = Color.Gray, fontSize = 12.sp)
            }
        }
        
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1E1E1E))
                .padding(8.dp)
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                testResults.reversed().forEach { result ->
                    Text(
                        text = result,
                        color = Color.White,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
                if (testResults.isEmpty()) {
                    Text("Tap buttons to test...", color = Color.Gray, fontSize = 11.sp)
                }
            }
        }
    }
}
