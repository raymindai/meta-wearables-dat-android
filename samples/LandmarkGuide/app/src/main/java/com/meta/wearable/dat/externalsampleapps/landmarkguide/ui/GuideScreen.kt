/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// GuideScreen - Landmark Guide Streaming UI
//
// This composable provides the main guide UI with camera stream, AI analysis,
// and voice guidance controls.

package com.meta.wearable.dat.externalsampleapps.landmarkguide.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import android.media.MediaPlayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.externalsampleapps.landmarkguide.R
import com.meta.wearable.dat.externalsampleapps.landmarkguide.guide.GuideMode
import com.meta.wearable.dat.externalsampleapps.landmarkguide.guide.GuideViewModel
import com.meta.wearable.dat.externalsampleapps.landmarkguide.guide.SavedScene
import com.meta.wearable.dat.externalsampleapps.landmarkguide.stream.StreamViewModel
import com.meta.wearable.dat.externalsampleapps.landmarkguide.wearables.WearablesViewModel
import com.meta.wearable.dat.externalsampleapps.landmarkguide.audio.BluetoothScoAudioCapture
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState

@Composable
fun GuideScreen(
    wearablesViewModel: WearablesViewModel,
    modifier: Modifier = Modifier,
    streamViewModel: StreamViewModel =
        viewModel(
            factory =
                StreamViewModel.Factory(
                    application = (LocalActivity.current as ComponentActivity).application,
                    wearablesViewModel = wearablesViewModel,
                ),
        ),
    guideViewModel: GuideViewModel = viewModel(),
) {
    val streamUiState by streamViewModel.uiState.collectAsStateWithLifecycle()
    val guideUiState by guideViewModel.uiState.collectAsStateWithLifecycle()
    val isSpeaking by guideViewModel.isSpeaking.collectAsStateWithLifecycle()
    
    // Background music player
    val context = LocalContext.current
    
    // Haptic feedback for button clicks
    val view = LocalView.current
    val playClickSound = {
        view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
    }
    
    val mediaPlayer = remember {
        MediaPlayer.create(context, R.raw.humaineye_bgm).apply {
            isLooping = true
            setVolume(0.2f, 0.2f) // 20% volume so TTS is clearly audible
        }
    }
    
    // Music playing state
    var isMusicPlaying by remember { mutableStateOf(true) }
    
    val toggleMusic = {
        playClickSound()
        if (isMusicPlaying) {
            mediaPlayer.pause()
        } else {
            mediaPlayer.start()
        }
        isMusicPlaying = !isMusicPlaying
    }
    
    // Helper function to set mode with TTS announcement
    fun setModeWithAnnouncement(mode: GuideMode) {
        guideViewModel.setMode(mode)
        playClickSound()
        val modeName = when(mode) {
            GuideMode.TOUR -> "Tour mode"
            GuideMode.GENERAL -> "General mode"
            GuideMode.TRANSLATE -> "Translate mode"
        }
        guideViewModel.speakText(modeName)
    }

    // Start streaming and music when screen appears, cleanup when leaving
    DisposableEffect(Unit) {
        streamViewModel.startStream()
        mediaPlayer.start()
        onDispose {
            // Cleanup all resources when leaving this screen
            mediaPlayer.stop()
            mediaPlayer.release()
            guideViewModel.cleanup()
            streamViewModel.stopStream()
        }
    }

    // Update guide viewmodel with current frames
    LaunchedEffect(streamUiState.videoFrame) {
        streamUiState.videoFrame?.let { frame ->
            guideViewModel.updateCurrentFrame(frame)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Camera stream view
        streamUiState.videoFrame?.let { videoFrame ->
            Image(
                bitmap = videoFrame.asImageBitmap(),
                contentDescription = stringResource(R.string.live_stream),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }

        // Loading indicator
        if (streamUiState.streamSessionState == StreamSessionState.STARTING) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
        }

        // Guide text overlay (below mode selector) - tap to dismiss
        guideUiState.lastGuideText?.let { guideText ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 56.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .clickable { 
                        playClickSound()
                        guideViewModel.clearLastGuide() 
                    }
                    .padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Show thumbnail if available
                    guideUiState.analyzedThumbnail?.let { thumbnail ->
                        Image(
                            bitmap = thumbnail.asImageBitmap(),
                            contentDescription = "Analyzed frame",
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                    
                    Column(modifier = Modifier.weight(1f)) {
                        if (isSpeaking) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VolumeUp,
                                    contentDescription = "Speaking",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Speaking...",
                                    color = Color.White.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        Text(
                            text = guideText,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Start,
                        )
                    }
                }
            }
        }

        // Analyzing indicator with thumbnail
        if (guideUiState.isAnalyzing) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(
                        color = Color.Black.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(24.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Show thumbnail being analyzed
                    guideUiState.analyzedThumbnail?.let { thumbnail ->
                        Image(
                            bitmap = thumbnail.asImageBitmap(),
                            contentDescription = "Analyzing frame",
                            modifier = Modifier
                                .size(120.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(2.dp, Color.White, RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Analyzing...",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        // Auto-analyze countdown timer (right of mode selector, same height)
        if (guideUiState.isAutoAnalyzeEnabled) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 8.dp, end = 16.dp)
            ) {
                FloatingActionButton(
                    onClick = { guideViewModel.toggleAutoAnalyze() },
                    containerColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                ) {
                    Text(
                        text = "${guideUiState.autoAnalyzeCountdown}",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
            }
        }

        // Mode selector bar (3 buttons) - always at top
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp)
                .background(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(24.dp)
                )
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Tour Mode button
            ModeButton(
                label = "Tour",
                icon = Icons.Default.TravelExplore,
                isSelected = guideUiState.guideMode == GuideMode.TOUR,
                selectedColor = Color(0xFF2E7D32),
                onClick = { setModeWithAnnouncement(GuideMode.TOUR) }
            )
            
            // General Mode button
            ModeButton(
                label = "General",
                icon = Icons.Default.Visibility,
                isSelected = guideUiState.guideMode == GuideMode.GENERAL,
                selectedColor = Color(0xFF1565C0),
                onClick = { setModeWithAnnouncement(GuideMode.GENERAL) }
            )
            
            // Translate Mode button
            ModeButton(
                label = "Translate",
                icon = Icons.Default.Translate,
                isSelected = guideUiState.guideMode == GuideMode.TRANSLATE,
                selectedColor = Color(0xFFE65100),
                onClick = { setModeWithAnnouncement(GuideMode.TRANSLATE) }
            )
        }

        // Saved scenes gallery button (left of mode selector, same height)
        if (guideUiState.savedScenes.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(top = 8.dp, start = 16.dp)
            ) {
                FloatingActionButton(
                    onClick = { guideViewModel.showGallery() },
                    containerColor = Color.Black.copy(alpha = 0.7f),
                    modifier = Modifier.size(48.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Collections,
                            contentDescription = "Gallery",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "${guideUiState.savedScenes.size}",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Minimap (center left)
        val bujairiLocation = LatLng(24.7341, 46.5772) // Bujairi Terrace, Diriyah
        val cameraPositionState = rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom(bujairiLocation, 15f)
        }
        
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 16.dp)
                .width(80.dp)
                .height(140.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(2.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
        ) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                properties = MapProperties(
                    isMyLocationEnabled = false
                ),
                uiSettings = MapUiSettings(
                    zoomControlsEnabled = false,
                    mapToolbarEnabled = false,
                    compassEnabled = false,
                    myLocationButtonEnabled = false,
                    scrollGesturesEnabled = false,
                    zoomGesturesEnabled = false,
                    tiltGesturesEnabled = false,
                    rotationGesturesEnabled = false
                )
            ) {
                Marker(
                    state = MarkerState(position = bujairiLocation),
                    title = "Bujairi Terrace",
                    snippet = "Diriyah, Saudi Arabia"
                )
            }
            
            // Location label overlay
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Column {
                    Text(
                        text = "Bujairi Terrace",
                        color = Color.White,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Diriyah",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 7.sp
                    )
                }
            }
        }

        // Bottom controls
        Box(modifier = Modifier.fillMaxSize().padding(all = 24.dp)) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Guide control buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Auto-analyze toggle
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FloatingActionButton(
                            onClick = { guideViewModel.toggleAutoAnalyze() },
                            containerColor = if (guideUiState.isAutoAnalyzeEnabled) 
                                MaterialTheme.colorScheme.primary 
                            else 
                                Color.Gray,
                            modifier = Modifier.size(56.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "Auto Guide",
                                tint = Color.White
                            )
                        }
                        Text(
                            text = "Auto",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    // Manual analyze button (large) - saves scene
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FloatingActionButton(
                            onClick = { guideViewModel.analyzeAndSave() },
                            containerColor = Color.White,
                            modifier = Modifier.size(72.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Analyze & Save",
                                tint = Color.Black,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Text(
                            text = "Save",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    // Stop speaking button
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FloatingActionButton(
                            onClick = { guideViewModel.stopSpeaking() },
                            containerColor = if (isSpeaking) Color.Red else Color.Gray,
                            modifier = Modifier.size(56.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Stop Speaking",
                                tint = Color.White
                            )
                        }
                        Text(
                            text = "Stop",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Stop stream button with music toggle
                Row(
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SwitchButton(
                        label = stringResource(R.string.stop_stream_button_title),
                        onClick = {
                            playClickSound()
                            guideViewModel.stopAutoAnalyze()
                            streamViewModel.stopStream()
                            wearablesViewModel.navigateToDeviceSelection()
                        },
                        isDestructive = true,
                        modifier = Modifier.weight(1f),
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    // Music toggle button
                    FloatingActionButton(
                        onClick = { toggleMusic() },
                        containerColor = if (isMusicPlaying) Color(0xFF1DB954) else Color.Gray,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = if (isMusicPlaying) Icons.Default.VolumeUp else Icons.Default.Stop,
                            contentDescription = "Toggle Music",
                            tint = Color.White
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    // SCO Mic Test button
                    var micStatus by remember { mutableStateOf("🎤") }
                    FloatingActionButton(
                        onClick = {
                            micStatus = "⏳"
                            val scoCapture = BluetoothScoAudioCapture(context)
                            scoCapture.setListener(object : BluetoothScoAudioCapture.AudioCaptureListener {
                                override fun onAudioData(data: ByteArray, size: Int) {
                                    var sum = 0.0
                                    for (i in 0 until minOf(size, 100) step 2) {
                                        val sample = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
                                        sum += sample * sample
                                    }
                                    val rms = kotlin.math.sqrt(sum / 50).toInt()
                                    micStatus = "$rms"
                                }
                                override fun onScoConnected() {
                                    micStatus = "🔊"
                                    scoCapture.startRecording()
                                }
                                override fun onScoDisconnected() { micStatus = "❌" }
                                override fun onError(message: String) { micStatus = "❌" }
                            })
                            scoCapture.startScoConnection()
                        },
                        containerColor = Color(0xFF2196F3),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Text(
                            text = micStatus,
                            color = Color.White,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        // Saved scenes gallery overlay
        if (guideUiState.isGalleryVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .clickable { guideViewModel.hideGallery() }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(16.dp)
                ) {
                    // Gallery header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Saved Scenes (${guideUiState.savedScenes.size})",
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = { guideViewModel.hideGallery() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color.White
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Saved scenes list
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(guideUiState.savedScenes) { scene ->
                            SavedSceneCard(
                                scene = scene,
                                onSpeak = { guideViewModel.speakScene(scene) },
                                onDelete = { guideViewModel.deleteScene(scene.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedSceneCard(
    scene: SavedScene,
    onSpeak: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(200.dp)
            .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .clickable { onSpeak() }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Thumbnail
        Image(
            bitmap = scene.thumbnail.asImageBitmap(),
            contentDescription = "Saved scene",
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )
        
        // Guide text
        Text(
            text = scene.guideText,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        
        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Speak button
            Row(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                    .clickable { onSpeak() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.VolumeUp,
                    contentDescription = "Speak",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Play",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            
            // Delete button
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = Color.Red.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun ModeButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    selectedColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (isSelected) selectedColor else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isSelected) Color.White else Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}
