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
    
    // Device info state
    var deviceName by remember { mutableStateOf<String?>(null) }
    
    // Collect device info from DAT SDK
    LaunchedEffect(Unit) {
        wearablesViewModel.deviceSelector.activeDevice(com.meta.wearable.dat.core.Wearables.devices).collect { device ->
            device?.let { deviceId ->
                com.meta.wearable.dat.core.Wearables.devicesMetadata[deviceId]?.collect { metadata ->
                    deviceName = metadata.name.ifEmpty { deviceId.toString() }
                }
            } ?: run {
                deviceName = null
            }
        }
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
                Text("Status", color = Color.Gray, fontSize = 9.sp)
                Text(
                    text = streamUiState.streamSessionState.name.take(8),
                    color = if (streamUiState.streamSessionState == StreamSessionState.STREAMING) 
                        Color.Green else Color.Yellow,
                    fontSize = 11.sp
                )
            }
            
            // Capture Time
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
                Text("📸 Capture", color = Color.Gray, fontSize = 9.sp)
                Text(
                    text = when {
                        streamUiState.isCapturing -> "..."
                        streamUiState.photoCaptureTime > 0 -> "${streamUiState.photoCaptureTime}ms"
                        streamUiState.photoCaptureTime < 0 -> "FAIL"
                        else -> "-"
                    },
                    color = when {
                        streamUiState.isCapturing -> Color.Yellow
                        streamUiState.photoCaptureTime < 0 -> Color.Red
                        else -> Color.Magenta
                    },
                    fontSize = 11.sp
                )
            }
            
            // Mic Volume
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
                Text("🎤 Mic", color = Color.Gray, fontSize = 9.sp)
                Text(
                    text = "$micVolume",
                    color = when {
                        micVolume > 300 -> Color.Red
                        micVolume > 100 -> Color.Yellow
                        else -> Color.Green
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text("[SCO]", color = Color.Cyan, fontSize = 8.sp)
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
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Separate Mic Controls (independent of video streaming)
        Text("🎤 Microphone", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Button(
                onClick = {
                    log("🎤 SCO Mic START")
                    startScoMic()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(8.dp)
            ) {
                Text("🎤 ON", fontSize = 11.sp)
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
                contentPadding = PaddingValues(8.dp)
            ) {
                Text("🎤 OFF", fontSize = 11.sp)
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
        
        // Capture Buttons
        Text("Capture", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Capture - use current stream or start LOW if none, stop after capture
            Button(
                onClick = {
                    val currentState = streamUiState.currentStreamState
                    val isStreaming = streamUiState.streamSessionState == StreamSessionState.STREAMING
                    
                    if (isStreaming && currentState?.videoEnabled == true) {
                        // Already streaming - just capture
                        log("📸 Capture (current: ${currentState.displayName})")
                        streamViewModel.capturePhoto()
                    } else {
                        // Not streaming - use captureWithReturn which starts LOW, captures, then stops
                        log("📸 Capture (starting LOW stream...)")
                        streamViewModel.captureWithReturn(StreamState.STANDBY)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BCD4)),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(12.dp)
            ) {
                Text("📸 Capture (1080x1440)", fontSize = 14.sp)
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
