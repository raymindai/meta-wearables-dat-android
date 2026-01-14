/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.landmarkguide.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meta.wearable.dat.externalsampleapps.landmarkguide.audio.BluetoothScoAudioCapture
import com.meta.wearable.dat.externalsampleapps.landmarkguide.translation.StreamingSpeechService
import com.meta.wearable.dat.externalsampleapps.landmarkguide.translation.GoogleSpeechService
import com.meta.wearable.dat.externalsampleapps.landmarkguide.translation.OpenAITranslationService
import com.meta.wearable.dat.externalsampleapps.landmarkguide.translation.TranslationService
import kotlinx.coroutines.launch

/**
 * Live Translation Screen - Hybrid STT (Deepgram + Google for Arabic) + OpenAI TTS
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTranslationScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Services - Hybrid: Deepgram (fast) + Google (Arabic) + OpenAI TTS
    val deepgramSTT = remember { StreamingSpeechService() }  // For ko, en, es
    val googleSTT = remember { GoogleSpeechService() }       // For Arabic
    val openAI = remember { OpenAITranslationService(context) }
    
    // Determine which STT to use based on language
    fun isArabic(lang: String) = lang == TranslationService.LANG_ARABIC
    
    // State - observe from the active STT
    val isDeepgramListening by deepgramSTT.isListening.collectAsStateWithLifecycle()
    val isGoogleListening by googleSTT.isListening.collectAsStateWithLifecycle()
    val isListening = isDeepgramListening || isGoogleListening
    
    val deepgramTranscription by deepgramSTT.transcription.collectAsStateWithLifecycle()
    val googleTranscription by googleSTT.transcription.collectAsStateWithLifecycle()
    val transcription = if (googleTranscription.isNotBlank()) googleTranscription else deepgramTranscription
    val partialTranscription by deepgramSTT.partialTranscription.collectAsStateWithLifecycle()
    
    var myLanguage by remember { mutableStateOf(TranslationService.LANG_KOREAN) }
    var partnerLanguage by remember { mutableStateOf(TranslationService.LANG_ENGLISH) }
    var myLangExpanded by remember { mutableStateOf(false) }
    var partnerLangExpanded by remember { mutableStateOf(false) }
    
    var translatedText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Ready") }
    var logs by remember { mutableStateOf(listOf<String>()) }
    var volumeLevel by remember { mutableStateOf(0) }
    var useHandsfreeMode by remember { mutableStateOf(true) }
    var micModeExpanded by remember { mutableStateOf(false) }
    
    // Audio capture
    var audioCapture by remember { mutableStateOf<BluetoothScoAudioCapture?>(null) }
    
    fun log(message: String) {
        Log.d("LiveTranslation", message)
        logs = (listOf(message) + logs).take(30)
    }
    
    // Common handler for transcription from any STT
    fun handleTranscription(text: String) {
        if (text.isNotBlank()) {
            log("📝 인식: $text")
            
            scope.launch {
                status = "🔄 번역중..."
                val result = openAI.translate(text, partnerLanguage, myLanguage)
                if (result != null) {
                    translatedText = result.translatedText
                    log("🌐 번역: ${result.translatedText}")
                    status = "🎤 듣는 중... (계속 말하세요)"
                    
                    scope.launch {
                        val spoke = openAI.speak(result.translatedText, partnerLanguage, useBluetooth = true)
                        log(if (spoke) "🔊 재생완료" else "⚠️ TTS실패")
                    }
                } else {
                    log("❌ 번역 실패")
                    status = "🎤 듣는 중..."
                }
            }
        }
    }
    
    // Setup callbacks for both STT services
    LaunchedEffect(Unit) {
        // Deepgram STT callback (for ko, en, es)
        deepgramSTT.onTranscript = { text, isFinal ->
            if (isFinal) handleTranscription(text)
        }
        deepgramSTT.onError = { error ->
            log("❌ Deepgram: $error")
            status = "❌ $error"
        }
        
        // Google STT callback (for Arabic)
        googleSTT.onTranscript = { text, isFinal ->
            if (isFinal) handleTranscription(text)
        }
        googleSTT.onError = { error ->
            log("❌ Google: $error")
            status = "❌ $error"
        }
    }
    
    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            deepgramSTT.stopListening()
            googleSTT.stopListening()
            audioCapture?.stop()
        }
    }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { 
                deepgramSTT.stopListening()
                googleSTT.stopListening()
                audioCapture?.stop()
                onBack() 
            }) {
                Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
            }
            Text(
                "🌐 Live Translation",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Language Selection
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // My Language
            Column(modifier = Modifier.weight(1f)) {
                Text("내 언어", color = Color.Gray, fontSize = 11.sp)
                Box {
                    Button(
                        onClick = { myLangExpanded = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(getLanguageDisplay(myLanguage), fontSize = 12.sp)
                    }
                    DropdownMenu(expanded = myLangExpanded, onDismissRequest = { myLangExpanded = false }) {
                        languageOptions.forEach { (code, display) ->
                            DropdownMenuItem(
                                text = { Text(display) },
                                onClick = { myLanguage = code; myLangExpanded = false }
                            )
                        }
                    }
                }
            }
            
            Text("→", color = Color.White, fontSize = 20.sp)
            
            // Partner Language
            Column(modifier = Modifier.weight(1f)) {
                Text("상대 언어", color = Color.Gray, fontSize = 11.sp)
                Box {
                    Button(
                        onClick = { partnerLangExpanded = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(getLanguageDisplay(partnerLanguage), fontSize = 12.sp)
                    }
                    DropdownMenu(expanded = partnerLangExpanded, onDismissRequest = { partnerLangExpanded = false }) {
                        languageOptions.forEach { (code, display) ->
                            DropdownMenuItem(
                                text = { Text(display) },
                                onClick = { partnerLanguage = code; partnerLangExpanded = false }
                            )
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Mic Mode Selection
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🎤 마이크:", color = Color.Gray, fontSize = 11.sp)
            Box {
                Button(
                    onClick = { micModeExpanded = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF607D8B)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        if (useHandsfreeMode) "👂 Handsfree" else "📞 SCO",
                        fontSize = 11.sp
                    )
                }
                DropdownMenu(expanded = micModeExpanded, onDismissRequest = { micModeExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("👂 Handsfree (권장)") },
                        onClick = { useHandsfreeMode = true; micModeExpanded = false }
                    )
                    DropdownMenuItem(
                        text = { Text("📞 SCO") },
                        onClick = { useHandsfreeMode = false; micModeExpanded = false }
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Status with Volume
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1E1E1E))
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(status, color = Color.White, fontSize = 14.sp)
            
            // Volume indicator
            if (isListening) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("🔊", fontSize = 14.sp)
                    Box(
                        modifier = Modifier
                            .width(60.dp)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.DarkGray)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction = (volumeLevel.coerceIn(0, 100) / 100f))
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    when {
                                        volumeLevel > 70 -> Color.Green
                                        volumeLevel > 30 -> Color.Yellow
                                        else -> Color.Gray
                                    }
                                )
                        )
                    }
                    Text("$volumeLevel", color = Color.Gray, fontSize = 10.sp)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Live Transcription Display
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.4f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1A1A2E))
                .padding(16.dp)
        ) {
            Text("🎤 실시간 인식", color = Color.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            
            // Final transcription
            if (transcription.isNotBlank()) {
                Text(transcription, color = Color.White, fontSize = 16.sp)
            }
            // Partial (interim) transcription
            if (partialTranscription.isNotBlank()) {
                Text(partialTranscription, color = Color.Gray, fontSize = 14.sp)
            }
            if (transcription.isBlank() && partialTranscription.isBlank()) {
                Text("말을 시작하면 여기에 표시됩니다...", color = Color.Gray, fontSize = 14.sp)
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Translation Display
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.3f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF2E1A2E))
                .padding(16.dp)
        ) {
            Text("🌐 번역", color = Color.Magenta, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            
            if (translatedText.isNotBlank()) {
                Text(translatedText, color = Color.White, fontSize = 18.sp)
            } else {
                Text("번역 결과가 여기에 표시됩니다...", color = Color.Gray, fontSize = 14.sp)
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Control Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    if (!isListening) {
                        // Check permission
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) 
                            != PackageManager.PERMISSION_GRANTED) {
                            log("❌ 마이크 권한 필요")
                            status = "❌ 마이크 권한 필요"
                            return@Button
                        }
                        
                        val useGoogle = isArabic(myLanguage)
                        log("🚀 ${if (useGoogle) "Google (아랍어)" else "Deepgram"} STT 시작")
                        status = "🔄 연결 중..."
                        translatedText = ""
                        
                        // Start appropriate STT based on language
                        if (useGoogle) {
                            googleSTT.clearTranscription()
                            googleSTT.startListening(myLanguage)
                        } else {
                            deepgramSTT.clearTranscription()
                            deepgramSTT.startListening(myLanguage)
                        }
                        
                        // Start audio capture and send to active STT
                        val capture = BluetoothScoAudioCapture(context)
                        capture.setListener(object : BluetoothScoAudioCapture.AudioCaptureListener {
                            override fun onAudioData(data: ByteArray, size: Int) {
                                val audioData = data.copyOf(size)
                                
                                // Send to appropriate STT
                                if (useGoogle && googleSTT.isConnected()) {
                                    googleSTT.sendAudio(audioData)
                                } else if (deepgramSTT.isConnected()) {
                                    deepgramSTT.sendAudio(audioData)
                                }
                                
                                // Calculate volume (RMS)
                                var sum = 0L
                                for (i in 0 until size step 2) {
                                    if (i + 1 < size) {
                                        val sample = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
                                        sum += sample.toLong() * sample
                                    }
                                }
                                val rms = kotlin.math.sqrt(sum.toDouble() / (size / 2)).toInt()
                                volumeLevel = (rms / 100).coerceIn(0, 100)
                            }
                            override fun onScoConnected() {
                                log("🎧 마이크 연결됨")
                                status = "🎤 듣는 중..."
                                capture.startRecording(useHandsfree = useHandsfreeMode)
                            }
                            override fun onScoDisconnected() {
                                log("🎧 마이크 해제")
                            }
                            override fun onError(message: String) {
                                log("❌ 오디오: $message")
                            }
                        })
                        capture.startScoConnection()
                        audioCapture = capture
                        
                    } else {
                        // Stop
                        log("🛑 중지")
                        status = "⏹️ 중지됨"
                        deepgramSTT.stopListening()
                        googleSTT.stopListening()
                        audioCapture?.stop()
                        audioCapture = null
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isListening) Color.Red else Color(0xFF4CAF50)
                ),
                modifier = Modifier.weight(1f).height(56.dp)
            ) {
                Text(
                    if (isListening) "🛑 중지" else "🎤 시작",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Button(
                onClick = {
                    deepgramSTT.clearTranscription()
                    googleSTT.clearTranscription()
                    translatedText = ""
                    logs = emptyList()
                    status = "Ready"
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                modifier = Modifier.weight(0.5f).height(56.dp)
            ) {
                Text("🗑️", fontSize = 20.sp)
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Log
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.3f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0D0D0D))
                .padding(8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            logs.forEach { logMsg ->
                Text(logMsg, color = Color.Green, fontSize = 10.sp)
            }
            if (logs.isEmpty()) {
                Text("로그가 여기에 표시됩니다...", color = Color.Gray, fontSize = 10.sp)
            }
        }
    }
}

private val languageOptions = listOf(
    TranslationService.LANG_KOREAN to "🇰🇷 한국어",
    TranslationService.LANG_ENGLISH to "🇺🇸 English",
    TranslationService.LANG_ARABIC to "🇸🇦 العربية",
    TranslationService.LANG_SPANISH to "🇪🇸 Español"
)

private fun getLanguageDisplay(code: String): String {
    return languageOptions.find { it.first == code }?.second ?: code
}
