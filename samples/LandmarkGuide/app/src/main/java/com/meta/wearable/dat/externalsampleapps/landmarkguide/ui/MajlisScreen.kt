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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.meta.wearable.dat.externalsampleapps.landmarkguide.ui.components.QrCodeDialog
import kotlinx.coroutines.launch

/**
 * Majlis - Multi-user Real-time Translation Room
 * 
 * Room selection screen with:
 * - Default rooms (Kabsa, Jareesh, Saleeg)
 * - Language selection (Listening Language = device-level)
 * - Join room functionality
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MajlisScreen(
    onBack: () -> Unit,
    isVoiceCommandMode: Boolean = false,
    deepLinkRoomId: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    // Device listening language (persisted setting)
    var listeningLanguage by remember { mutableStateOf(TranslationService.LANG_KOREAN) }
    var langDropdownExpanded by remember { mutableStateOf(false) }
    
    // Room selection
    var selectedRoom by remember { mutableStateOf<MajlisRoom?>(null) }
    var isInRoom by remember { mutableStateOf(false) }
    
    // Auto-join from deep link
    LaunchedEffect(deepLinkRoomId) {
        if (deepLinkRoomId != null) {
            android.util.Log.d("MajlisScreen", "🔗 Auto-joining room from deep link: $deepLinkRoomId")
            // Create or find room with the deep link ID
            val room = MajlisRoom(
                id = deepLinkRoomId,
                name = "🔗 $deepLinkRoomId",
                description = "Joined via QR code"
            )
            selectedRoom = room
            isInRoom = true
        }
    }
    
    // Default rooms
    var rooms by remember { mutableStateOf(
        listOf(
            MajlisRoom("kabsa", "🍛 Kabsa", "Saudi traditional rice dish room"),
            MajlisRoom("jareesh", "🥣 Jareesh", "Wheat-based comfort food room"),
            MajlisRoom("saleeg", "🍚 Saleeg", "Creamy rice dish room")
        )
    ) }
    
    // Dialog for creating new room
    var showCreateDialog by remember { mutableStateOf(false) }
    var newRoomName by remember { mutableStateOf("") }
    
    val languages = listOf(
        TranslationService.LANG_KOREAN to "🇰🇷 한국어",
        TranslationService.LANG_ENGLISH to "🇺🇸 English",
        TranslationService.LANG_ARABIC to "🇸🇦 العربية",
        TranslationService.LANG_SPANISH to "🇪🇸 Español"
    )
    
    if (isInRoom && selectedRoom != null) {
        // Show room screen
        MajlisRoomScreen(
            room = selectedRoom!!,
            initialListeningLanguage = listeningLanguage,
            isVoiceCommandMode = isVoiceCommandMode,
            onLeave = {
                isInRoom = false
                selectedRoom = null
            }
        )
    } else {
        // Room selection screen
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF1A1A2E), Color(0xFF16213E))
                    )
                )
                .statusBarsPadding()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                }
                Text(
                    "🕌 Majlis",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                "사람들이 모여 자연스럽게 대화하는 공간",
                color = Color.Gray,
                fontSize = 14.sp
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Listening Language Selection
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2D3A4F))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "🎧 내가 듣는 언어 (Listening Language)",
                        color = Color.Cyan,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "어떤 Room에서도 이 언어로 번역됩니다",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    ExposedDropdownMenuBox(
                        expanded = langDropdownExpanded,
                        onExpandedChange = { langDropdownExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = languages.find { it.first == listeningLanguage }?.second ?: "",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = langDropdownExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedTextColor = Color.White,
                                focusedTextColor = Color.White,
                                unfocusedBorderColor = Color.Gray,
                                focusedBorderColor = Color.Cyan
                            )
                        )
                        ExposedDropdownMenu(
                            expanded = langDropdownExpanded,
                            onDismissRequest = { langDropdownExpanded = false }
                        ) {
                            languages.forEach { (code, name) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        listeningLanguage = code
                                        langDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
            
            // =============================================
            // ROOMS SECTION
            // =============================================
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "🏠 Rooms",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                
                // Create Room Button
                TextButton(onClick = { showCreateDialog = true }) {
                    Text("+ Create", color = Color.Cyan)
                }
            }
            
            Text(
                "Join a room to start multi-user translation",
                color = Color.Gray,
                fontSize = 12.sp
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(rooms) { room ->
                    RoomCard(
                        room = room,
                        onClick = {
                            selectedRoom = room
                            isInRoom = true
                        }
                    )
                }
            }
        }
        
        // Create Room Dialog
        if (showCreateDialog) {
            AlertDialog(
                onDismissRequest = { showCreateDialog = false },
                title = { Text("Create New Room") },
                text = {
                    OutlinedTextField(
                        value = newRoomName,
                        onValueChange = { newRoomName = it },
                        label = { Text("Room Name") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (newRoomName.isNotBlank()) {
                                val newRoom = MajlisRoom(
                                    id = newRoomName.lowercase().replace(" ", "-"),
                                    name = "🏠 $newRoomName",
                                    description = "Custom room"
                                )
                                rooms = rooms + newRoom
                                newRoomName = ""
                                showCreateDialog = false
                            }
                        }
                    ) {
                        Text("Create")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
private fun RoomCard(
    room: MajlisRoom,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF3D4A5F)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Room icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF4CAF50)),
                contentAlignment = Alignment.Center
            ) {
                Text(room.name.take(2), fontSize = 20.sp)
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    room.name,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    room.description,
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }
            
            Icon(
                Icons.Default.Groups,
                contentDescription = "Join",
                tint = Color.Cyan
            )
        }
    }
}

/**
 * Active Room Screen with Full STT → Translation → TTS Pipeline
 * Auto-listening enabled for barge-in support
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MajlisRoomScreen(
    room: MajlisRoom,
    initialListeningLanguage: String,
    isVoiceCommandMode: Boolean = false,
    onLeave: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // =============================================
    // FIREBASE REALTIME (Multi-user - INSTANT!)
    // =============================================
    var connectedUsers by remember { mutableStateOf(listOf<com.meta.wearable.dat.externalsampleapps.landmarkguide.firebase.RoomUser>()) }
    var firebaseStatus by remember { mutableStateOf("Connecting...") }
    var firebaseMessages by remember { mutableStateOf(listOf<com.meta.wearable.dat.externalsampleapps.landmarkguide.firebase.RoomMessage>()) }
    var isFirebaseConnected by remember { mutableStateOf(false) }
    
    val firebaseService = remember {
        com.meta.wearable.dat.externalsampleapps.landmarkguide.firebase.FirebaseRoomService(
            onUserJoined = { user ->
                connectedUsers = connectedUsers + user
                android.util.Log.d("MajlisRoom", "👤 User joined: ${user.name}")
            },
            onUserLeft = { userId ->
                connectedUsers = connectedUsers.filter { it.userId != userId }
            },
            onMessageReceived = { message ->
                firebaseMessages = firebaseMessages + message
            },
            onConnectionStateChanged = { connected ->
                isFirebaseConnected = connected
                firebaseStatus = if (connected) "✅ Connected" else "⏳ Connecting..."
            }
        )
    }
    
    // Auto-join room via Firebase (INSTANT!)
    LaunchedEffect(room.id) {
        android.util.Log.d("MajlisRoom", "🚀 Joining room via Firebase: ${room.name}")
        firebaseService.joinRoom(
            roomId = room.id,
            userName = "User",
            language = initialListeningLanguage
        )
    }
    
    // Cleanup when leaving
    DisposableEffect(Unit) {
        onDispose {
            firebaseService.leaveRoom()
        }
    }
    
    // Languages (mutable in room)
    var myListeningLanguage by remember { mutableStateOf(initialListeningLanguage) }
    var mySpeakingLanguage by remember { mutableStateOf(room.spokenLanguage) }
    var detectedLanguage by remember { mutableStateOf<String?>(null) }  // For auto-detect display
    var detectedVoice by remember { mutableStateOf<String?>(null) }     // For male/female TTS voice
    val voiceBuffer = remember { mutableListOf<Byte>() }               // Buffer for voice analysis
    var voiceAnalyzed by remember { mutableStateOf(false) }            // Only analyze once per session
    var listenLangExpanded by remember { mutableStateOf(false) }
    var speakLangExpanded by remember { mutableStateOf(false) }
    
    // Speaking languages (includes Auto option)
    val speakingLanguages = listOf(
        "auto" to "🔄 Auto",
        TranslationService.LANG_KOREAN to "🇰🇷 한국어",
        TranslationService.LANG_ENGLISH to "🇺🇸 English",
        TranslationService.LANG_ARABIC to "🇸🇦 العربية",
        TranslationService.LANG_SPANISH to "🇪🇸 Español"
    )
    
    // Listening languages (output - no Auto needed)
    val listeningLanguages = listOf(
        TranslationService.LANG_KOREAN to "🇰🇷 한국어",
        TranslationService.LANG_ENGLISH to "🇺🇸 English",
        TranslationService.LANG_ARABIC to "🇸🇦 العربية",
        TranslationService.LANG_SPANISH to "🇪🇸 Español"
    )
    
    val languageNames = mapOf(
        "auto" to "Auto",
        TranslationService.LANG_KOREAN to "한국어",
        TranslationService.LANG_ENGLISH to "English",
        TranslationService.LANG_ARABIC to "العربية",
        TranslationService.LANG_SPANISH to "Español"
    )
    
    // Services
    val deepgramSTT = remember { StreamingSpeechService() }
    val googleSTT = remember { GoogleSpeechService() }
    val openAI = remember { OpenAITranslationService(context) }
    
    // Helper: use Google for Arabic, Deepgram for others
    fun isArabic(lang: String) = lang == TranslationService.LANG_ARABIC
    
    // Observe STT StateFlow (like LiveTranslationScreen)
    val deepgramTranscription by deepgramSTT.transcription.collectAsStateWithLifecycle()
    val googleTranscription by googleSTT.transcription.collectAsStateWithLifecycle()
    val sttTranscription = if (googleTranscription.isNotBlank()) googleTranscription else deepgramTranscription
    
    // Chat history (speaker, original, translation)
    data class ChatMessage(
        val speaker: String,
        val original: String,
        val translated: String,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    // State
    var userState by remember { mutableStateOf("LISTENING") }
    var chatHistory by remember { mutableStateOf(listOf<ChatMessage>()) }
    var currentOriginal by remember { mutableStateOf("") }
    var currentTranslation by remember { mutableStateOf("") }
    var lastProcessedLength by remember { mutableStateOf(0) }  // Track processed text length
    var volumeLevel by remember { mutableStateOf(0) }
    var isSpeaking by remember { mutableStateOf(false) }
    var audioCapture by remember { mutableStateOf<BluetoothScoAudioCapture?>(null) }
    var status by remember { mutableStateOf("Ready") }
    var myTtsEnabled by remember { mutableStateOf(true) }    // TTS for my own speech
    var peerTtsEnabled by remember { mutableStateOf(true) }  // TTS for peer messages
    var showQrDialog by remember { mutableStateOf(false) }    // QR code dialog
    
    // Add Firebase messages to chat history when received + translate + TTS
    LaunchedEffect(firebaseMessages.size) {
        if (firebaseMessages.isNotEmpty()) {
            val latestMessage = firebaseMessages.last()
            android.util.Log.d("MajlisRoom", "📨 Firebase message received: ${latestMessage.originalText}")
            
            // Check if we already have translation for our language
            var translatedText = latestMessage.translatedTexts[myListeningLanguage]
            
            if (translatedText == null) {
                // Need to translate the original text to my language
                android.util.Log.d("MajlisRoom", "🔄 Translating to $myListeningLanguage...")
                val result = openAI.translate(
                    text = latestMessage.originalText,
                    targetLanguage = myListeningLanguage,
                    sourceLanguage = latestMessage.senderLanguage
                )
                translatedText = result?.translatedText ?: latestMessage.originalText
            }
            
            // Add to chat history
            chatHistory = chatHistory + ChatMessage(
                speaker = latestMessage.senderName,
                original = latestMessage.originalText,
                translated = translatedText, 
                timestamp = latestMessage.timestamp
            )
            
            // Play TTS if enabled
            if (peerTtsEnabled) {
                android.util.Log.d("MajlisRoom", "🔊 Playing TTS: $translatedText")
                openAI.speak(translatedText, myListeningLanguage, useBluetooth = true, voice = detectedVoice)
            }
        }
    }
    
    // GLOBAL: Pause STT when voice command mode is active
    LaunchedEffect(isVoiceCommandMode) {
        if (isVoiceCommandMode) {
            // Voice command mode started - pause our STT
            Log.d("MajlisRoom", "⏸️ Voice command mode - pausing STT")
            audioCapture?.stop()
            deepgramSTT.stopListening()
            googleSTT.stopListening()
            status = "⏸️ Wake Word Active"
        } else {
            // Voice command mode ended - resume STT if listening
            if (userState == "LISTENING") {
                Log.d("MajlisRoom", "▶️ Resuming STT after voice command")
                status = "🎤 Listening..."
                // STT will be restarted by the LaunchedEffect(userState) below
            }
        }
    }
    
    // Update current original - show only new content after lastProcessedLength
    LaunchedEffect(sttTranscription) {
        if (sttTranscription.isNotBlank() && sttTranscription.length > lastProcessedLength) {
            // Only show the new part
            currentOriginal = sttTranscription.substring(lastProcessedLength).trim()
        } else if (sttTranscription.length <= lastProcessedLength) {
            // STT was cleared or reset
            currentOriginal = ""
        }
    }
    
    // Handle transcription result → Translate → TTS
    fun handleTranscript(text: String) {
        if (text.isBlank()) return
        
        // Check if this text was already processed (check by content in history)
        if (chatHistory.any { it.original == text }) {
            return  // Already processed, skip
        }
        
        currentOriginal = text
        userState = "TRANSLATING"
        
        // Skip translation if listeningLanguage == speakingLanguage
        if (myListeningLanguage == mySpeakingLanguage) {
            currentTranslation = text
            // Add to chat history
            chatHistory = chatHistory + ChatMessage("나", text, text)
            lastProcessedLength = sttTranscription.length  // Mark position as processed
            
            // Broadcast to nearby peers
            firebaseService.sendMessage(text, mapOf(myListeningLanguage to text))
            
            scope.launch {
                if (myTtsEnabled) {
                    userState = "SPEAKING_OUT"
                    openAI.speak(text, myListeningLanguage, useBluetooth = true, voice = detectedVoice)
                }
                userState = "LISTENING"
                currentOriginal = ""
                currentTranslation = ""
            }
        } else {
            scope.launch {
                val result = openAI.translate(text, myListeningLanguage, mySpeakingLanguage)
                if (result != null) {
                    currentTranslation = result.translatedText
                    // Add to chat history
                    chatHistory = chatHistory + ChatMessage("나", text, result.translatedText)
                    lastProcessedLength = sttTranscription.length  // Mark position as processed
                    
                    // Broadcast to nearby peers
                    firebaseService.sendMessage(text, mapOf(myListeningLanguage to result.translatedText))
                    
                    if (myTtsEnabled) {
                        userState = "SPEAKING_OUT"
                        openAI.speak(result.translatedText, myListeningLanguage, useBluetooth = true, voice = detectedVoice)
                    }
                }
                userState = "LISTENING"
                currentOriginal = ""
                currentTranslation = ""
            }
        }
    }
    
    // Setup STT callbacks
    LaunchedEffect(Unit) {
        deepgramSTT.onTranscript = { text, isFinal ->
            if (isFinal) {
                scope.launch { handleTranscript(text) }
            }
        }
        deepgramSTT.onError = { error ->
            scope.launch { status = "❌ Deepgram: $error" }
        }
        
        googleSTT.onTranscript = { text, isFinal ->
            if (isFinal) {
                scope.launch { handleTranscript(text) }
            }
        }
        googleSTT.onError = { error ->
            scope.launch { status = "❌ Google: $error" }
        }
    }
    
    // Auto-start listening when entering room (for barge-in)
    LaunchedEffect(mySpeakingLanguage) {
        // Check permission first
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) 
            == PackageManager.PERMISSION_GRANTED) {
            
            // Stop previous
            deepgramSTT.stopListening()
            googleSTT.stopListening()
            audioCapture?.stop()
            
            // Start appropriate STT
            // Auto mode or Arabic → use Google (supports multi-language detection)
            val useGoogle = mySpeakingLanguage == "auto" || isArabic(mySpeakingLanguage)
            if (useGoogle) {
                googleSTT.clearTranscription()
                googleSTT.startListening(mySpeakingLanguage)
                // Set up language detection callback for auto mode
                if (mySpeakingLanguage == "auto") {
                    googleSTT.onLanguageDetected = { lang ->
                        scope.launch { 
                            detectedLanguage = lang
                            Log.d("Majlis", "🌐 Detected: $lang")
                        }
                    }
                }
            } else {
                deepgramSTT.clearTranscription()
                deepgramSTT.startListening(mySpeakingLanguage)
            }
            
            isSpeaking = true
            userState = "LISTENING"
            lastProcessedLength = 0  // Reset on language change
            
            // Start audio capture
            val capture = BluetoothScoAudioCapture(context)
            capture.setListener(object : BluetoothScoAudioCapture.AudioCaptureListener {
                override fun onAudioData(data: ByteArray, size: Int) {
                    val audioData = data.copyOf(size)
                    if (useGoogle && googleSTT.isConnected()) {
                        googleSTT.sendAudio(audioData)
                    } else if (deepgramSTT.isConnected()) {
                        deepgramSTT.sendAudio(audioData)
                    }
                    
                    // Collect audio for voice analysis (first 1 second = 32000 bytes at 16kHz)
                    if (!voiceAnalyzed && voiceBuffer.size < 32000) {
                        for (b in audioData) voiceBuffer.add(b)
                        
                        // Analyze when we have enough data
                        if (voiceBuffer.size >= 32000) {
                            val buffer = voiceBuffer.toByteArray()
                            val voice = com.meta.wearable.dat.externalsampleapps.landmarkguide.voice.VoiceAnalyzer.getTtsVoice(buffer)
                            detectedVoice = voice
                            voiceAnalyzed = true
                            Log.d("Majlis", "🎤 Voice detected: $voice (${if (voice == "nova") "Female" else "Male"})")
                        }
                    }
                    
                    // Volume
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
                    Log.d("Majlis", "🎧 Auto-listening started")
                    capture.startRecording(useHandsfree = true)
                }
                override fun onScoDisconnected() {
                    Log.d("Majlis", "🎧 Mic disconnected")
                }
                override fun onError(message: String) {
                    Log.e("Majlis", "❌ Audio: $message")
                }
            })
            capture.startScoConnection()
            audioCapture = capture
        }
    }
    
    // Cleanup on leave
    DisposableEffect(Unit) {
        onDispose {
            deepgramSTT.stopListening()
            googleSTT.stopListening()
            audioCapture?.stop()
        }
    }
    
    Column(
        modifier = Modifier
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
                audioCapture?.stop()
                deepgramSTT.stopListening()
                googleSTT.stopListening()
                onLeave()
            }) {
                Icon(Icons.Default.ArrowBack, "Leave", tint = Color.White)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    room.name,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    buildString {
                        val peerCount = 1 + connectedUsers.size  // Me + connected users
                        append("👥 ${peerCount}명 참여 • ")
                        if (connectedUsers.isEmpty()) {
                            append("🔍 Searching...")
                        } else if (isSpeaking) {
                            append("🎤 Listening")
                            // Show detected language if in Auto mode
                            if (mySpeakingLanguage == "auto" && detectedLanguage != null) {
                                append(" ($detectedLanguage)")
                            }
                        } else {
                            append("대기 중")
                        }
                    },
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }
            
            // QR Share Button
            TextButton(onClick = { showQrDialog = true }) {
                Text("📱 QR", color = Color.Cyan, fontSize = 14.sp)
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Language selectors row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Speaking language (what I speak)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🗣️ 내가 말하는 언어", color = Color.Gray, fontSize = 10.sp)
                ExposedDropdownMenuBox(
                    expanded = speakLangExpanded,
                    onExpandedChange = { speakLangExpanded = it }
                ) {
                    Surface(
                        modifier = Modifier.menuAnchor(),
                        color = Color(0xFFFF9800),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            speakingLanguages.find { it.first == mySpeakingLanguage }?.second ?: "",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            color = Color.White,
                            fontSize = 12.sp
                        )
                    }
                    ExposedDropdownMenu(
                        expanded = speakLangExpanded,
                        onDismissRequest = { speakLangExpanded = false }
                    ) {
                        speakingLanguages.forEach { (code, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    mySpeakingLanguage = code
                                    speakLangExpanded = false
                                }
                            )
                        }
                    }
                }
            }
            
            // Listening language (output)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🎧 듣는 언어 (출력)", color = Color.Gray, fontSize = 10.sp)
                ExposedDropdownMenuBox(
                    expanded = listenLangExpanded,
                    onExpandedChange = { listenLangExpanded = it }
                ) {
                    Surface(
                        modifier = Modifier.menuAnchor(),
                        color = Color(0xFF4CAF50),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            listeningLanguages.find { it.first == myListeningLanguage }?.second ?: "",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            color = Color.White,
                            fontSize = 12.sp
                        )
                    }
                    ExposedDropdownMenu(
                        expanded = listenLangExpanded,
                        onDismissRequest = { listenLangExpanded = false }
                    ) {
                        listeningLanguages.forEach { (code, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    myListeningLanguage = code
                                    listenLangExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // TTS Toggles Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // My TTS Toggle
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🎤 내 음성", color = Color.Gray, fontSize = 11.sp)
                Switch(
                    checked = myTtsEnabled,
                    onCheckedChange = { myTtsEnabled = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF4CAF50),
                        uncheckedThumbColor = Color.Gray,
                        uncheckedTrackColor = Color.DarkGray
                    )
                )
            }
            
            // Peer TTS Toggle
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("👥 상대 음성", color = Color.Gray, fontSize = 11.sp)
                Switch(
                    checked = peerTtsEnabled,
                    onCheckedChange = { peerTtsEnabled = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF2196F3),
                        uncheckedThumbColor = Color.Gray,
                        uncheckedTrackColor = Color.DarkGray
                    )
                )
            }
        }
        
        
        // Volume Level Indicator (visible when speaking)
        if (isSpeaking) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🎤", fontSize = 16.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF333333))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(volumeLevel / 100f)
                            .background(
                                when {
                                    volumeLevel > 70 -> Color(0xFF4CAF50)
                                    volumeLevel > 30 -> Color(0xFFFFEB3B)
                                    else -> Color(0xFF2196F3)
                                }
                            )
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "$volumeLevel%",
                    color = Color.White,
                    fontSize = 12.sp
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
        
        // State indicator
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (isSpeaking) 100.dp else 120.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    when (userState) {
                        "SPEAKING" -> Color(0xFF4CAF50)
                        "TRANSLATING" -> Color(0xFFFF9800)
                        else -> Color(0xFF2196F3)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    when (userState) {
                        "SPEAKING" -> Icons.Default.Mic
                        else -> Icons.Default.Language
                    },
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    when (userState) {
                        "SPEAKING" -> "🎤 Speaking"
                        "TRANSLATING" -> "🔄 Translating"
                        else -> "👂 Listening"
                    },
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Chat History (Original messages)
        Text("📝 원문 (Original)", color = Color.Gray, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.35f),
            color = Color(0xFF1A1A2E),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(12.dp)
                    .fillMaxSize()
            ) {
                // Previous messages (completed)
                chatHistory.takeLast(5).forEach { msg ->
                    Text(
                        "${msg.speaker}: ${msg.original}",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
                // Current input (in progress) - show only if not empty and different from last
                if (currentOriginal.isNotBlank() && 
                    (chatHistory.isEmpty() || chatHistory.last().original != currentOriginal)) {
                    Text(
                        "💭 $currentOriginal",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 13.sp
                    )
                }
                if (chatHistory.isEmpty() && currentOriginal.isBlank()) {
                    Text("말을 시작하세요...", color = Color.Gray)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Translation area (with history)
        Text("🌐 번역 (${languageNames[myListeningLanguage]})", color = Color.Cyan, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.35f),
            color = Color(0xFF0D2137),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(12.dp)
                    .fillMaxSize()
            ) {
                // Previous translations (completed)
                chatHistory.takeLast(5).forEach { msg ->
                    Text(
                        "${msg.speaker}: ${msg.translated}",
                        color = Color.Cyan,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
                // Current translation (in progress)
                if (currentTranslation.isNotBlank() && 
                    (chatHistory.isEmpty() || chatHistory.last().translated != currentTranslation)) {
                    Text(
                        "💭 $currentTranslation",
                        color = Color.Cyan.copy(alpha = 0.5f),
                        fontSize = 13.sp
                    )
                }
                if (chatHistory.isEmpty() && currentTranslation.isBlank()) {
                    Text("번역이 여기에 표시됩니다...", color = Color.Gray)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Bottom controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Speak button (toggle)
            Button(
                onClick = {
                    if (!isSpeaking) {
                        // Check permission
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) 
                            != PackageManager.PERMISSION_GRANTED) {
                            return@Button
                        }
                        
                        // Start speaking
                        userState = "SPEAKING"
                        isSpeaking = true
                        
                        // Start appropriate STT based on room language
                        val useGoogle = isArabic(room.spokenLanguage)
                        if (useGoogle) {
                            googleSTT.clearTranscription()
                            googleSTT.startListening(room.spokenLanguage)
                        } else {
                            deepgramSTT.clearTranscription()
                            deepgramSTT.startListening(room.spokenLanguage)
                        }
                        
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
                                Log.d("Majlis", "🎧 Mic connected")
                                capture.startRecording(useHandsfree = true)
                            }
                            override fun onScoDisconnected() {
                                Log.d("Majlis", "🎧 Mic disconnected")
                            }
                            override fun onError(message: String) {
                                Log.e("Majlis", "❌ Audio: $message")
                            }
                        })
                        capture.startScoConnection()
                        audioCapture = capture
                    } else {
                        // Stop speaking
                        userState = "LISTENING"
                        isSpeaking = false
                        volumeLevel = 0
                        deepgramSTT.stopListening()
                        googleSTT.stopListening()
                        audioCapture?.stop()
                        audioCapture = null
                    }
                },
                modifier = Modifier.weight(1f).height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSpeaking) Color(0xFFFF5722) else Color(0xFF4CAF50)
                )
            ) {
                Icon(Icons.Default.Mic, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isSpeaking) "🛑 Stop" else "🎤 Speak")
            }
            
            // Leave button
            Button(
                onClick = {
                    audioCapture?.stop()
                    onLeave()
                },
                modifier = Modifier.weight(1f).height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Red
                )
            ) {
                Text("🚪 Leave")
            }
        }
    }
    
    // QR Code Dialog
    if (showQrDialog) {
        QrCodeDialog(
            roomId = room.id,
            roomName = room.name,
            onDismiss = { showQrDialog = false }
        )
    }
}

/**
 * Room data class
 */
data class MajlisRoom(
    val id: String,
    val name: String,
    val description: String,
    val spokenLanguage: String = TranslationService.LANG_ARABIC  // Default room language
)
