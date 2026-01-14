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
import androidx.compose.ui.draw.scale
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
import com.meta.wearable.dat.externalsampleapps.landmarkguide.translation.RealTimeTranslator
import com.meta.wearable.dat.externalsampleapps.landmarkguide.translation.TranslationService
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

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
    
    // Translation state
    val translator = remember { RealTimeTranslator(context) }
    val translatorState by translator.state.collectAsStateWithLifecycle()
    var myLanguage by remember { mutableStateOf(TranslationService.LANG_KOREAN) }
    var partnerLanguage by remember { mutableStateOf(TranslationService.LANG_ENGLISH) }
    var myLangDropdownExpanded by remember { mutableStateOf(false) }
    var partnerLangDropdownExpanded by remember { mutableStateOf(false) }
    var translationTestText by remember { mutableStateOf("") }
    val translationScope = rememberCoroutineScope()
    var isRecordingForTranslation by remember { mutableStateOf(false) }
    var translationAudioBuffer by remember { mutableStateOf<ByteArray?>(null) }
    
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
            .verticalScroll(rememberScrollState())
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
                        .border(2.dp, if (streamViewModel.noVideoPreview) Color.Yellow else Color(0xFF4CAF50), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (streamViewModel.noVideoPreview) {
                        // No Preview mode - show indicator only
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("📵", fontSize = 24.sp)
                            Text("Preview OFF", color = Color.Yellow, fontSize = 10.sp)
                        }
                    } else if (streamUiState.videoFrame != null) {
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
        // SCO Mic Status and Volume - first row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF1E1E1E))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(micStatus, color = if (micStatus == "CONNECTED") Color.Green else Color.Gray, fontSize = 10.sp)
            Text("🔊 $micVolume", color = if (micVolume > 100) Color.Green else Color.Gray, fontSize = 10.sp)
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        // Buttons row - second row
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
                modifier = Modifier.weight(1f),
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
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(6.dp)
            ) {
                Text("OFF", fontSize = 10.sp)
            }
            
            // Mic Source Dropdown
            Box(modifier = Modifier.weight(1f)) {
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
        
        // Translation Section
        Text("🌐 Translation", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        
        // Language Selection Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // My Language
            Column(modifier = Modifier.weight(1f)) {
                Text("My Lang", color = Color.Gray, fontSize = 9.sp)
                Box {
                    Button(
                        onClick = { myLangDropdownExpanded = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(6.dp)
                    ) {
                        Text(
                            when (myLanguage) {
                                TranslationService.LANG_KOREAN -> "🇰🇷 한국어"
                                TranslationService.LANG_ARABIC -> "🇸🇦 العربية"
                                TranslationService.LANG_SPANISH -> "🇪🇸 Español"
                                else -> "🇺🇸 English"
                            },
                            fontSize = 10.sp
                        )
                    }
                    DropdownMenu(
                        expanded = myLangDropdownExpanded,
                        onDismissRequest = { myLangDropdownExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("🇺🇸 English") },
                            onClick = {
                                myLanguage = TranslationService.LANG_ENGLISH
                                translator.setMyLanguage(myLanguage)
                                myLangDropdownExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("🇰🇷 한국어") },
                            onClick = {
                                myLanguage = TranslationService.LANG_KOREAN
                                translator.setMyLanguage(myLanguage)
                                myLangDropdownExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("🇸🇦 العربية") },
                            onClick = {
                                myLanguage = TranslationService.LANG_ARABIC
                                translator.setMyLanguage(myLanguage)
                                myLangDropdownExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("🇪🇸 Español") },
                            onClick = {
                                myLanguage = TranslationService.LANG_SPANISH
                                translator.setMyLanguage(myLanguage)
                                myLangDropdownExpanded = false
                            }
                        )
                    }
                }
            }
            
            Text("↔️", color = Color.White, fontSize = 16.sp)
            
            // Partner Language
            Column(modifier = Modifier.weight(1f)) {
                Text("Partner", color = Color.Gray, fontSize = 9.sp)
                Box {
                    Button(
                        onClick = { partnerLangDropdownExpanded = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(6.dp)
                    ) {
                        Text(
                            when (partnerLanguage) {
                                TranslationService.LANG_KOREAN -> "🇰🇷 한국어"
                                TranslationService.LANG_ARABIC -> "🇸🇦 العربية"
                                TranslationService.LANG_SPANISH -> "🇪🇸 Español"
                                else -> "🇺🇸 English"
                            },
                            fontSize = 10.sp
                        )
                    }
                    DropdownMenu(
                        expanded = partnerLangDropdownExpanded,
                        onDismissRequest = { partnerLangDropdownExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("🇺🇸 English") },
                            onClick = {
                                partnerLanguage = TranslationService.LANG_ENGLISH
                                translator.setPartnerLanguage(partnerLanguage)
                                partnerLangDropdownExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("🇰🇷 한국어") },
                            onClick = {
                                partnerLanguage = TranslationService.LANG_KOREAN
                                translator.setPartnerLanguage(partnerLanguage)
                                partnerLangDropdownExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("🇸🇦 العربية") },
                            onClick = {
                                partnerLanguage = TranslationService.LANG_ARABIC
                                translator.setPartnerLanguage(partnerLanguage)
                                partnerLangDropdownExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("🇪🇸 Español") },
                            onClick = {
                                partnerLanguage = TranslationService.LANG_SPANISH
                                translator.setPartnerLanguage(partnerLanguage)
                                partnerLangDropdownExpanded = false
                            }
                        )
                    }
                }
            }
        }
        
        // Translation Status
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF1E1E1E))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(translatorState.status, color = Color.White, fontSize = 10.sp)
            if (translatorState.isProcessing) {
                Text("⏳", fontSize = 10.sp)
            }
        }
        
        // Translation Result
        if (translatorState.originalText.isNotBlank() || translatorState.translatedText.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF2E2E2E))
                    .padding(8.dp)
            ) {
                if (translatorState.originalText.isNotBlank()) {
                    Text("원문: ${translatorState.originalText}", color = Color.Gray, fontSize = 10.sp)
                }
                if (translatorState.translatedText.isNotBlank()) {
                    Text("번역: ${translatorState.translatedText}", color = Color.Green, fontSize = 11.sp)
                }
            }
        }
        
        // Live Translation Buttons
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Start/Stop Recording for Live Translation
            Button(
                onClick = {
                    if (!isRecordingForTranslation) {
                        // Start recording
                        isRecordingForTranslation = true
                        log("🎤 녹음 시작 (말하세요, 멈추면 자동 종료)...")
                        
                        // Start SCO mic recording for translation with VAD
                        val audioBuffer = mutableListOf<Byte>()
                        var lastSpeechTime = System.currentTimeMillis()
                        var hasSpeechStarted = false
                        val silenceThreshold = 500  // Volume threshold for speech detection
                        val silenceTimeoutMs = 1500L  // 1.5 seconds of silence = stop
                        val maxRecordingMs = 30000L  // Max 30 seconds
                        
                        val capture = BluetoothScoAudioCapture(context)
                        var captureRef: BluetoothScoAudioCapture? = capture
                        
                        capture.setListener(object : BluetoothScoAudioCapture.AudioCaptureListener {
                            override fun onAudioData(data: ByteArray, size: Int) {
                                if (!isRecordingForTranslation) return
                                
                                audioBuffer.addAll(data.take(size))
                                
                                // Calculate volume (RMS)
                                var sum = 0L
                                for (i in 0 until size step 2) {
                                    if (i + 1 < size) {
                                        val sample = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
                                        sum += sample.toLong() * sample
                                    }
                                }
                                val rms = kotlin.math.sqrt(sum.toDouble() / (size / 2)).toInt()
                                
                                // Detect speech
                                if (rms > silenceThreshold) {
                                    lastSpeechTime = System.currentTimeMillis()
                                    if (!hasSpeechStarted) {
                                        hasSpeechStarted = true
                                        log("🎤 음성 감지됨...")
                                    }
                                }
                            }
                            override fun onScoConnected() {
                                log("🎤 SCO 연결됨, 녹음 시작")
                                capture.startRecording()
                            }
                            override fun onScoDisconnected() {}
                            override fun onError(message: String) {
                                log("❌ 녹음 에러: $message")
                            }
                        })
                        capture.startScoConnection()
                        
                        // VAD monitoring: stop when silence detected after speech
                        translationScope.launch {
                            val startTime = System.currentTimeMillis()
                            while (isRecordingForTranslation) {
                                kotlinx.coroutines.delay(100)
                                val now = System.currentTimeMillis()
                                
                                // Stop conditions
                                val timeSinceLastSpeech = now - lastSpeechTime
                                val totalTime = now - startTime
                                
                                // If speech started and silence for 1.5s, or max time reached
                                if ((hasSpeechStarted && timeSinceLastSpeech > silenceTimeoutMs) || 
                                    totalTime > maxRecordingMs) {
                                    break
                                }
                            }
                            
                            isRecordingForTranslation = false
                            captureRef?.stop()
                            captureRef = null
                            
                            val audioData = audioBuffer.toByteArray()
                            log("🎤 녹음 완료: ${audioData.size} bytes (${audioData.size / 32}ms)")
                            
                            if (audioData.size < 3200) {  // Less than 100ms of audio
                                log("⚠️ 오디오가 너무 짧음")
                                return@launch
                            }
                            
                            // Process: STT → Translate → TTS
                            log("🔄 음성 인식 중...")
                            translator.setMyLanguage(myLanguage)
                            translator.setPartnerLanguage(partnerLanguage)
                            translator.processAudio(audioData, 16000, isFromPartner = false)
                        }
                    } else {
                        // Stop recording early
                        isRecordingForTranslation = false
                        log("🎤 녹음 중지")
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecordingForTranslation) Color.Red else Color(0xFF4CAF50)
                ),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(6.dp)
            ) {
                Text(if (isRecordingForTranslation) "🔴 녹음중..." else "🎤 Live번역", fontSize = 10.sp)
            }
            
            // Quick Test: Direct text translation + TTS
            Button(
                onClick = {
                    translationScope.launch {
                        val testText = when (myLanguage) {
                            TranslationService.LANG_KOREAN -> "안녕하세요, 반갑습니다"
                            TranslationService.LANG_ARABIC -> "مرحبا، كيف حالك"
                            else -> "Hello, nice to meet you"
                        }
                        log("📝 원문($myLanguage): $testText")
                        
                        val result = translator.translateText(testText, toMyLanguage = false)
                        if (result != null) {
                            log("🌐 번역($partnerLanguage): ${result.translatedText}")
                            log("🔊 TTS 재생중...")
                            val spoke = translator.speak(result.translatedText, partnerLanguage)
                            log(if (spoke) "✅ TTS 완료" else "❌ TTS 실패")
                        } else {
                            log("❌ 번역 실패")
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(6.dp)
            ) {
                Text("📝 Quick Test", fontSize = 10.sp)
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Video Stream Header with Silent Mode toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Title + No Preview toggle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("📹 Video Stream", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                
                // No Preview toggle (hide video stream display)
                var noVideoPreview by remember { mutableStateOf(streamViewModel.noVideoPreview) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "📵",
                        color = if (noVideoPreview) Color.Yellow else Color.Gray,
                        fontSize = 10.sp
                    )
                    Box(modifier = Modifier.scale(0.6f).height(20.dp)) {
                        Switch(
                            checked = noVideoPreview,
                            onCheckedChange = { 
                                noVideoPreview = it
                                streamViewModel.noVideoPreview = it
                                log(if (it) "📵 Video Preview OFF" else "📹 Video Preview ON")
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Yellow,
                                checkedTrackColor = Color.Yellow.copy(alpha = 0.5f)
                            )
                        )
                    }
                }
            }
            
            // Right: Silent Mode Change toggle
            var silentMode by remember { mutableStateOf(streamViewModel.silentModeChange) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "🔇",
                    color = if (silentMode) Color.Cyan else Color.Gray,
                    fontSize = 10.sp
                )
                Box(modifier = Modifier.scale(0.6f).height(20.dp)) {
                    Switch(
                        checked = silentMode,
                        onCheckedChange = { 
                            silentMode = it
                            streamViewModel.silentModeChange = it
                            log(if (it) "🔇 Silent Mode ON" else "🔊 Silent Mode OFF")
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Cyan,
                            checkedTrackColor = Color.Cyan.copy(alpha = 0.5f)
                        )
                    )
                }
            }
        }
        
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
        
        // Capture Section Header with No Preview toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("📸 Capture", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            
            // No Preview toggle for capture (hide video stream display, same as Video Stream toggle)
            var noVideoPreviewCapture by remember { mutableStateOf(streamViewModel.noVideoPreview) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "📵",
                    color = if (noVideoPreviewCapture) Color.Yellow else Color.Gray,
                    fontSize = 10.sp
                )
                Box(modifier = Modifier.scale(0.6f).height(20.dp)) {
                    Switch(
                        checked = noVideoPreviewCapture,
                        onCheckedChange = { 
                            noVideoPreviewCapture = it
                            streamViewModel.noVideoPreview = it
                            log(if (it) "📵 Stream Preview OFF" else "� Stream Preview ON")
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Yellow,
                            checkedTrackColor = Color.Yellow.copy(alpha = 0.5f)
                        )
                    )
                }
            }
        }
        
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
                .fillMaxWidth()
                .height(200.dp)
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
