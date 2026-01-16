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
import androidx.compose.material.icons.filled.Stop
import android.media.AudioManager
import android.media.AudioDeviceInfo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meta.wearable.dat.externalsampleapps.landmarkguide.audio.BluetoothScoAudioCapture
import com.meta.wearable.dat.externalsampleapps.landmarkguide.translation.SonioxStreamingSpeechService
import com.meta.wearable.dat.externalsampleapps.landmarkguide.translation.OpenAITranslationService
import com.meta.wearable.dat.externalsampleapps.landmarkguide.translation.GoogleTranslationService
import com.meta.wearable.dat.externalsampleapps.landmarkguide.translation.GoogleTTSService
import com.meta.wearable.dat.externalsampleapps.landmarkguide.translation.OpenAIRealtimeTTSService
import com.meta.wearable.dat.externalsampleapps.landmarkguide.translation.TranslationService
import com.meta.wearable.dat.externalsampleapps.landmarkguide.utils.LanguagePreferences
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

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
    val languagePrefs = remember { LanguagePreferences(context) }
    
    // Device listening language (persisted setting)
    // Load saved language preference
    var listeningLanguage by remember { 
        mutableStateOf(languagePrefs.getListeningLanguage()) 
    }
    var langDropdownExpanded by remember { mutableStateOf(false) }
    
    // Save language when changed
    LaunchedEffect(listeningLanguage) {
        languagePrefs.saveListeningLanguage(listeningLanguage)
        android.util.Log.d("MajlisScreen", "💾 Saved listening language: $listeningLanguage")
    }
    
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
        "auto" to "🔄 Auto",
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
                        "내가 말하는 언어와 상대방이 나한테 말할 때 번역되는 언어를 설정합니다",
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
    val languagePrefs = remember { LanguagePreferences(context) }
    val translationService = remember { TranslationService() }  // For language detection
    val audioManager = remember { context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager }
    
    // Audio input device tracking
    var currentAudioInput by remember { mutableStateOf<String?>(null) }
    var availableAudioInputs by remember { mutableStateOf<List<String>>(emptyList()) }
    var audioInputExpanded by remember { mutableStateOf(false) }
    var manuallySelectedAudioInput by remember { mutableStateOf<String?>(null) }  // Track manual selection
    
    // Store device info for switching
    var audioDeviceMap by remember { mutableStateOf<Map<String, AudioDeviceInfo>>(emptyMap()) }
    
    // Update audio input devices periodically
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000) // Update every second
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    // Get all devices (both input and output) to see Bluetooth devices
                    val allDevices = audioManager.getDevices(AudioManager.GET_DEVICES_ALL)
                    val inputDevicesList = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    
                    val deviceMap = mutableMapOf<String, AudioDeviceInfo>()
                    val inputDeviceNames = mutableSetOf<String>()
                    
                    // Get device model name to filter out built-in mic that might be reported with model name
                    val deviceModel = android.os.Build.MODEL
                    
                    // Process input devices
                    inputDevicesList.forEach { device ->
                        if (device.isSource) {
                            val productName = device.productName?.toString()?.takeIf { it.isNotEmpty() }
                            
                            val deviceName = when {
                                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                                    // HFP (Hands-Free Profile) - 통화용
                                    if (productName != null) {
                                        "$productName (HFP)"
                                    } else {
                                        "Bluetooth SCO (HFP)"
                                    }
                                }
                                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> {
                                    // A2DP (Advanced Audio Distribution Profile) - 미디어용
                                    if (productName != null) {
                                        "$productName (A2DP)"
                                    } else {
                                        "Bluetooth A2DP"
                                    }
                                }
                                device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
                                device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Mic"
                                device.type == AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
                                else -> {
                                    // If product name matches device model, it's likely the built-in mic
                                    // Also check if it's a built-in device type (not external)
                                    if (productName == deviceModel || 
                                        device.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ||
                                        device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                                        "Built-in Mic"
                                    } else {
                                        productName ?: "Unknown"
                                    }
                                }
                            }
                            
                            // Only add if not already added (avoid duplicates)
                            if (!deviceMap.containsKey(deviceName)) {
                                deviceMap[deviceName] = device
                                inputDeviceNames.add(deviceName)
                            } else if (deviceName == "Built-in Mic") {
                                // If it's built-in mic, prefer the TYPE_BUILTIN_MIC device
                                if (device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                                    deviceMap[deviceName] = device
                                }
                            }
                        }
                    }
                    
                    // Also check all devices for Bluetooth devices that might not be in inputs list
                    allDevices.forEach { device ->
                        if (device.isSource && (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || 
                            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)) {
                            val productName = device.productName?.toString()?.takeIf { it.isNotEmpty() }
                            val deviceName = when {
                                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                                    // HFP (Hands-Free Profile) - 통화용
                                    if (productName != null) {
                                        "$productName (HFP)"
                                    } else {
                                        "Bluetooth SCO (HFP)"
                                    }
                                }
                                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> {
                                    // A2DP (Advanced Audio Distribution Profile) - 미디어용
                                    if (productName != null) {
                                        "$productName (A2DP)"
                                    } else {
                                        "Bluetooth A2DP"
                                    }
                                }
                                else -> "Bluetooth Device"
                            }
                            if (!deviceMap.containsKey(deviceName)) {
                                deviceMap[deviceName] = device
                                inputDeviceNames.add(deviceName)
                            }
                        }
                    }
                    
                    // Always include Built-in Mic
                    inputDeviceNames.add("Built-in Mic")
                    
                    // Check if we have any Bluetooth devices with actual product names
                    val hasBluetoothDeviceWithName = inputDeviceNames.any { name ->
                        // Check if it's a Bluetooth device with actual product name (not the generic names)
                        name.contains("Bluetooth", ignoreCase = true) && 
                        name != "Bluetooth SCO (HFP)" && name != "Bluetooth SCO" && 
                        name != "Bluetooth A2DP" && !name.endsWith("(HFP)") && !name.endsWith("(A2DP)")
                    }
                    
                    // Check if we have any Bluetooth devices in the device list (by type)
                    val hasBluetoothDeviceByType = inputDevicesList.any { device ->
                        device.isSource && (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || 
                            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
                    } || allDevices.any { device ->
                        device.isSource && (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || 
                            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
                    }
                    
                    // Only add generic "Bluetooth SCO" if:
                    // 1. We have Bluetooth devices by type (actually connected)
                    // 2. But we don't have a specific device name (like "Oakley Meta 05FV")
                    // 3. And Bluetooth SCO is available
                    // This prevents duplicate entries when we already have the actual device name
                    if (!hasBluetoothDeviceWithName && hasBluetoothDeviceByType && audioManager.isBluetoothScoAvailableOffCall) {
                        // Check if any Bluetooth device doesn't have a product name
                        val hasBluetoothWithoutName = inputDevicesList.any { device ->
                            device.isSource && (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || 
                                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) &&
                            (device.productName == null || device.productName.toString().isEmpty())
                        } || allDevices.any { device ->
                            device.isSource && (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || 
                                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) &&
                            (device.productName == null || device.productName.toString().isEmpty())
                        }
                        
                        // Only add generic "Bluetooth SCO (HFP)" if there's a Bluetooth device without a name
                        if (hasBluetoothWithoutName) {
                            inputDeviceNames.add("Bluetooth SCO (HFP)")
                        }
                    }
                    
                    // Add "None" option for stopping audio input
                    inputDeviceNames.add("None")
                    
                    availableAudioInputs = inputDeviceNames.toList().sorted()
                    audioDeviceMap = deviceMap
                    
                    // Get current active input (only update if not manually selected)
                    val activeInput = if (manuallySelectedAudioInput != null) {
                        // User manually selected a device - preserve it
                        manuallySelectedAudioInput
                    } else {
                        // Auto-detect current input
                        when {
                            audioManager.isBluetoothScoOn -> {
                                // Check if there's actually a connected Bluetooth device
                                val btDevice = inputDevicesList.find { 
                                    it.isSource && it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO 
                                }
                                // Only return Bluetooth if device is actually found
                                if (btDevice != null) {
                                    val productName = btDevice.productName?.toString()?.takeIf { it.isNotEmpty() }
                                    if (productName != null) {
                                        "$productName (HFP)"
                                    } else {
                                        "Bluetooth SCO (HFP)"
                                    }
                                } else {
                                    // Bluetooth SCO is on but no device found - default to Built-in Mic
                                    "Built-in Mic"
                                }
                            }
                            audioManager.mode == AudioManager.MODE_IN_COMMUNICATION -> {
                                // Check if there's actually a connected Bluetooth device
                                val hasBluetoothConnected = audioManager.isBluetoothScoOn || 
                                    inputDevicesList.any { 
                                        it.isSource && (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || 
                                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
                                    }
                                
                                inputDevicesList.find { it.isSource }?.let {
                                    when (it.type) {
                                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                                            // Only return Bluetooth if it's actually connected
                                            if (hasBluetoothConnected) {
                                                val productName = it.productName?.toString()?.takeIf { it.isNotEmpty() }
                                                if (productName != null) {
                                                    "$productName (HFP)"
                                                } else {
                                                    "Bluetooth SCO (HFP)"
                                                }
                                            } else {
                                                "Built-in Mic"
                                            }
                                        }
                                        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Mic"
                                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> {
                                            // A2DP (Advanced Audio Distribution Profile) - 미디어용
                                            if (hasBluetoothConnected) {
                                                val productName = it.productName?.toString()?.takeIf { it.isNotEmpty() }
                                                if (productName != null) {
                                                    "$productName (A2DP)"
                                                } else {
                                                    "Bluetooth A2DP"
                                                }
                                            } else {
                                                "Built-in Mic"
                                            }
                                        }
                                        else -> {
                                            // For other devices
                                            it.productName?.toString() ?: "Unknown"
                                        }
                                    }
                                } ?: "Built-in Mic"
                            }
                            else -> {
                                // Default to Built-in Mic if nothing detected
                                "Built-in Mic"
                            }
                        }
                    }
                    // Only update if it's different and not manually selected
                    if (activeInput != currentAudioInput && manuallySelectedAudioInput == null) {
                        currentAudioInput = activeInput
                    } else if (manuallySelectedAudioInput != null && currentAudioInput != manuallySelectedAudioInput) {
                        currentAudioInput = manuallySelectedAudioInput
                    }
                } else {
                    // Fallback for older Android versions
                    currentAudioInput = when {
                        audioManager.isBluetoothScoOn -> "Bluetooth SCO (HFP)"
                        else -> "Built-in Mic"
                    }
                    availableAudioInputs = listOf("Built-in Mic", "Bluetooth SCO (HFP)", "None")
                }
            } catch (e: Exception) {
                android.util.Log.e("MajlisRoom", "Error getting audio devices: ${e.message}", e)
            }
        }
    }
    
    // =============================================
    // FIREBASE REALTIME (Multi-user - INSTANT!)
    // =============================================
    var connectedUsers by remember { mutableStateOf(listOf<com.meta.wearable.dat.externalsampleapps.landmarkguide.firebase.RoomUser>()) }
    var firebaseStatus by remember { mutableStateOf("Connecting...") }
    var firebaseMessages by remember { mutableStateOf(listOf<com.meta.wearable.dat.externalsampleapps.landmarkguide.firebase.RoomMessage>()) }
    var isFirebaseConnected by remember { mutableStateOf(false) }
    var actualUserCount by remember { mutableStateOf(1) }  // Start with 1 (myself)
    
    // OpenAI Realtime TTS for receiver-side translation
    val openAIRealtimeTTS = remember { OpenAIRealtimeTTSService(context) }
    
    val firebaseService = remember {
        com.meta.wearable.dat.externalsampleapps.landmarkguide.firebase.FirebaseRoomService(
            onUserJoined = { user ->
                // Always update - sync from Firebase may send duplicates, but we'll deduplicate
                val existingIndex = connectedUsers.indexOfFirst { it.userId == user.userId }
                if (existingIndex >= 0) {
                    // Update existing user (in case name or language changed)
                    connectedUsers = connectedUsers.toMutableList().apply {
                        set(existingIndex, user)
                    }
                    android.util.Log.d("MajlisRoom", "🔄 Updated existing user: ${user.name} (ID: ${user.userId})")
                } else {
                    // Add new user
                    connectedUsers = connectedUsers + user
                    android.util.Log.d("MajlisRoom", "👤 User joined: ${user.name} (ID: ${user.userId})")
                }
                
                // Remove duplicates (LaunchedEffect will recalculate count)
                val uniqueUsers = connectedUsers.distinctBy { it.userId }
                connectedUsers = uniqueUsers
                android.util.Log.d("MajlisRoom", "📋 User list updated: ${uniqueUsers.size} unique users")
            },
            onUserLeft = { userId ->
                val beforeCount = connectedUsers.size
                connectedUsers = connectedUsers.filter { it.userId != userId }
                val afterCount = connectedUsers.size
                android.util.Log.d("MajlisRoom", "👋 User left: $userId (Before: $beforeCount, After: $afterCount)")
                // LaunchedEffect will recalculate count automatically
            },
            onMessageReceived = { message ->
                // Update existing message or add new one
                val existingIndex = firebaseMessages.indexOfFirst { it.messageId == message.messageId }
                firebaseMessages = if (existingIndex >= 0) {
                    // Update existing message (for TTS status updates)
                    firebaseMessages.toMutableList().apply {
                        set(existingIndex, message)
                    }
                } else {
                    // Add new message
                    firebaseMessages + message
                }
                android.util.Log.d("MajlisRoom", "📨 Message ${if (existingIndex >= 0) "updated" else "added"}: ${message.messageId}, playing: ${message.ttsPlayingBy.size}, played: ${message.ttsPlayedBy.size}")
            },
            onConnectionStateChanged = { connected ->
                isFirebaseConnected = connected
                firebaseStatus = if (connected) "✅ Connected" else "⏳ Connecting..."
            }
        )
    }
    
    // UNIFIED LANGUAGE: All languages are now the same (from room selection)
    // This language is used for:
    // 1. What I speak (STT input language)
    // 2. What I want to hear (translation target language)
    // 3. All language settings are unified
    var unifiedLanguage by remember { 
        mutableStateOf(initialListeningLanguage.takeIf { it.isNotBlank() } ?: languagePrefs.getMyLanguage().takeIf { it.isNotBlank() } ?: TranslationService.LANG_KOREAN)
    }
    
    // Update unified language when initialListeningLanguage changes (user changed language in room selection)
    LaunchedEffect(initialListeningLanguage) {
        if (initialListeningLanguage.isNotBlank()) {
            unifiedLanguage = initialListeningLanguage
            android.util.Log.d("MajlisRoom", "🔄 Updated unified language from room selection: $initialListeningLanguage")
        }
    }
    
    // Save unified language when changed
    LaunchedEffect(unifiedLanguage) {
        languagePrefs.saveMyLanguage(unifiedLanguage)
        languagePrefs.saveTranslationLanguage(unifiedLanguage)
        languagePrefs.saveListeningLanguage(unifiedLanguage)
        android.util.Log.d("MajlisRoom", "💾 Saved unified language: $unifiedLanguage")
    }
    
    // For compatibility with existing code - all point to unified language
    val myListeningLanguage = unifiedLanguage
    val mySpeakingLanguage = unifiedLanguage
    val myLanguage = unifiedLanguage
    val translationLanguage = unifiedLanguage
    
    // Auto-join room via Firebase (INSTANT!)
    LaunchedEffect(room.id) {
        android.util.Log.d("MajlisRoom", "🚀 Joining room via Firebase: ${room.name}")
        // Clear connected users when joining a new room to prevent duplicates
        connectedUsers = emptyList()
        actualUserCount = 1  // Reset to 1 (myself)
        
        // Reset audio input to Built-in Mic when joining a new room
        // This prevents Bluetooth SCO from being auto-selected when Bluetooth is off
        manuallySelectedAudioInput = null
        currentAudioInput = "Built-in Mic"
        
        firebaseService.joinRoom(
            roomId = room.id,
            userName = "User",
            language = initialListeningLanguage
        )
    }
    
    // Update user count based on actual unique connected users
    // Use a more reliable calculation: always recalculate from connectedUsers
    LaunchedEffect(connectedUsers) {
        // Get unique user IDs (excluding myself)
        val uniqueUserIds = connectedUsers.distinctBy { it.userId }.map { it.userId }.toSet()
        val uniqueCount = uniqueUserIds.size
        
        // Calculate total: myself (1) + unique connected users
        val newCount = 1 + uniqueCount
        
        // Only update if different to avoid unnecessary recomposition
        if (actualUserCount != newCount) {
            actualUserCount = newCount
            android.util.Log.d("MajlisRoom", "📊 User count recalculated: $actualUserCount (unique users: $uniqueCount, total in list: ${connectedUsers.size}, unique IDs: $uniqueUserIds)")
        }
    }
    
    // Connect OpenAI Realtime TTS for receiver-side translation
    // Pre-connect common language pairs for instant translation (no connection delay)
    var previousListeningLanguage by remember { mutableStateOf(myListeningLanguage) }
    LaunchedEffect(room.id, myListeningLanguage) {
        // Language changed or initial connection
        if (myListeningLanguage != previousListeningLanguage || !openAIRealtimeTTS.isConnected()) {
            android.util.Log.d("MajlisRoom", "🔌 Connecting OpenAI Realtime TTS for translation... (target: $myListeningLanguage)")
            
            // Disconnect existing connection if language changed
            if (previousListeningLanguage != myListeningLanguage && openAIRealtimeTTS.isConnected()) {
                android.util.Log.d("MajlisRoom", "🔄 Language changed from $previousListeningLanguage to $myListeningLanguage, reconnecting...")
                openAIRealtimeTTS.disconnect()
                kotlinx.coroutines.delay(200) // Brief delay before reconnecting
            }
            
            previousListeningLanguage = myListeningLanguage
            
            // Connect with new language (primary connection)
            openAIRealtimeTTS.connect(
                sourceLang = TranslationService.LANG_KOREAN, // Will be updated per message
                targetLang = myListeningLanguage
            )
            
            // Pre-connect common language pairs in background for instant translation
            scope.launch(Dispatchers.IO) {
                val commonSourceLanguages = listOf(
                    TranslationService.LANG_KOREAN,
                    TranslationService.LANG_ENGLISH,
                    TranslationService.LANG_ARABIC,
                    TranslationService.LANG_SPANISH
                )
                
                // Pre-connect other common language pairs (will be used if needed)
                commonSourceLanguages.forEach { sourceLang ->
                    if (sourceLang != TranslationService.LANG_KOREAN && sourceLang != myListeningLanguage) {
                        kotlinx.coroutines.delay(100) // Stagger connections
                        // Note: OpenAI Realtime API supports only one connection at a time
                        // So we'll just ensure the primary connection is ready
                        android.util.Log.d("MajlisRoom", "📋 Pre-connection note: $sourceLang → $myListeningLanguage (will connect on-demand)")
                    }
                }
            }
            
            // Setup callbacks (only if not already set)
            if (openAIRealtimeTTS.onTranslation == null) {
                openAIRealtimeTTS.onTranslation = { translatedText ->
                    android.util.Log.d("MajlisRoom", "✅ Realtime translation received: $translatedText")
                    // Translation will be handled in the message processing logic
                }
            }
        }
    }
    
    // Cleanup when leaving
    DisposableEffect(Unit) {
        onDispose {
            firebaseService.leaveRoom()
            openAIRealtimeTTS.disconnect()
        }
    }
    
    var detectedLanguage by remember { mutableStateOf<String?>(null) }  // For auto-detect display
    var detectedVoice by remember { mutableStateOf<String?>(null) }     // For male/female TTS voice
    val voiceBuffer = remember { mutableListOf<Byte>() }               // Buffer for voice analysis
    var voiceAnalyzed by remember { mutableStateOf(false) }            // Only analyze once per session
    var unifiedLangExpanded by remember { mutableStateOf(false) }
    
    // Speaking languages (includes Auto option)
    val speakingLanguages = listOf(
        "auto" to "🔄 Auto",
        TranslationService.LANG_KOREAN to "🇰🇷 한국어",
        TranslationService.LANG_ENGLISH to "🇺🇸 English",
        TranslationService.LANG_ARABIC to "🇸🇦 العربية",
        TranslationService.LANG_SPANISH to "🇪🇸 Español"
    )
    
    // Listening languages (output - includes Auto for language detection)
    val listeningLanguages = listOf(
        "auto" to "🔄 Auto",
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
    // Soniox Streaming STT (only STT service in use)
    val googleSTT = remember { SonioxStreamingSpeechService() }
    val openAI = remember { OpenAITranslationService(context) }
    
    // Translation and TTS services
    val googleTranslation = remember { GoogleTranslationService() }
    val googleTTS = remember { GoogleTTSService(context) }
    
    // Observe STT StateFlow (only Soniox)
    val googleTranscription by googleSTT.transcription.collectAsStateWithLifecycle()
    
    val sttTranscription = googleTranscription
    
    // Language detected by Google STT (no separate API call needed!)
    var detectedLanguageBySTT by remember { mutableStateOf<String?>(null) }
    
    // Chat history (speaker, original, translation)
    data class ChatMessage(
        val speaker: String,
        val speakerLanguage: String = "",  // Speaker's language code
        val original: String,
        val translated: String,
        val timestamp: Long = System.currentTimeMillis(),
        val messageId: String = "",  // Firebase message ID for proper matching
        val isComplete: Boolean = true,  // Whether the message is complete (not streaming)
        val isSent: Boolean = false,  // Whether message was sent to Firebase (for my messages)
        val isTTSPlayed: Boolean = false,  // Whether TTS playback completed (for peer messages)
        val ttsPlayingByOthers: Set<String> = emptySet(),  // User IDs who are currently playing TTS (for my messages)
        val ttsPlayedByOthers: Set<String> = emptySet()  // User IDs who have completed TTS playback (for my messages)
    )
    
    // State
    var userState by remember { mutableStateOf("LISTENING") }
    // Keep chatHistory sorted by timestamp (use sorted list to avoid re-sorting on every render)
    var chatHistory by remember { mutableStateOf(listOf<ChatMessage>()) }
    var currentOriginal by remember { mutableStateOf("") }
    var currentTranslation by remember { mutableStateOf("") }
    var lastProcessedLength by remember { mutableStateOf(0) }  // Track processed text length
    var volumeLevel by remember { mutableStateOf(0) }
    var isSpeaking by remember { mutableStateOf(false) }
    var audioCapture by remember { mutableStateOf<BluetoothScoAudioCapture?>(null) }
    var status by remember { mutableStateOf("Ready") }
    
    // VAD + Near-field Gate state
    // 8kHz, 16-bit mono: 20ms frame = 160 samples = 320 bytes
    val FRAME_SIZE_20MS = 320  // 20ms at 8kHz, 16-bit mono
    var vadGateOpen by remember { mutableStateOf(false) }
    var vadSpeechFrameCount by remember { mutableStateOf(0) }  // Consecutive speech frames
    var vadNonSpeechFrameCount by remember { mutableStateOf(0) }  // Consecutive non-speech frames
    var vadFrameBuffer by remember { mutableStateOf(ByteArray(0)) }  // Accumulate to 20ms frames
    var vadRmsThreshold by remember { mutableStateOf(25) }  // VAD threshold (tunable, higher for very close voice)
    var nearFieldRmsThreshold by remember { mutableStateOf(35) }  // Near-field threshold (much higher for very close voice only)
    var backgroundNoiseLevel by remember { mutableStateOf(0) }  // For adaptive threshold
    var noiseCalibrationSamples by remember { mutableStateOf(0) }  // Calibration counter
    
    // Function to switch audio input device
    val switchAudioInput: (String) -> Unit = { deviceName ->
        android.util.Log.d("MajlisRoom", "🔄 Switching audio input to: $deviceName")
        try {
            val wasSpeaking = isSpeaking && userState == "LISTENING"
            
            // Stop current audio capture
            audioCapture?.stop()
            audioCapture = null
            
            // Reset VAD state when switching audio input
            vadGateOpen = false
            vadSpeechFrameCount = 0
            vadNonSpeechFrameCount = 0
            vadFrameBuffer = ByteArray(0)
            noiseCalibrationSamples = 0
            backgroundNoiseLevel = 0
            
            // Set manually selected input to prevent auto-detection from overriding
            manuallySelectedAudioInput = deviceName
            
            when (deviceName) {
                "None" -> {
                    // Stop all audio input
                    audioManager.stopBluetoothSco()
                    audioManager.isBluetoothScoOn = false
                    audioManager.mode = AudioManager.MODE_NORMAL
                    android.util.Log.d("MajlisRoom", "✅ Audio input stopped (None)")
                    currentAudioInput = "None"
                }
                "Built-in Mic" -> {
                    // Disable Bluetooth SCO, use built-in mic
                    audioManager.stopBluetoothSco()
                    audioManager.isBluetoothScoOn = false
                    audioManager.mode = AudioManager.MODE_NORMAL
                    android.util.Log.d("MajlisRoom", "✅ Built-in Mic enabled")
                    currentAudioInput = "Built-in Mic"
                }
                else -> {
                    // Check if it's a Bluetooth device
                    val isBluetoothDevice = deviceName.contains("Bluetooth", ignoreCase = true) ||
                        audioDeviceMap[deviceName]?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        audioDeviceMap[deviceName]?.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    
                    if (isBluetoothDevice) {
                        // Enable Bluetooth SCO
                        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                        audioManager.startBluetoothSco()
                        audioManager.isBluetoothScoOn = true
                        android.util.Log.d("MajlisRoom", "✅ Bluetooth device enabled: $deviceName")
                        currentAudioInput = deviceName
                    } else {
                        // For other devices, try to set communication device (API 31+)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            val device = audioDeviceMap[deviceName]
                            if (device != null) {
                                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                                val result = audioManager.setCommunicationDevice(device)
                                android.util.Log.d("MajlisRoom", "✅ Set communication device: $deviceName (result: $result)")
                                currentAudioInput = deviceName
                            } else {
                                android.util.Log.w("MajlisRoom", "⚠️ Device not found: $deviceName")
                            }
                        } else {
                            android.util.Log.w("MajlisRoom", "⚠️ Device switching not supported on this Android version")
                        }
                    }
                }
            }
            
            // Restart audio capture if it was running
            if (wasSpeaking && deviceName != "None") {
                scope.launch {
                    kotlinx.coroutines.delay(1000) // Wait for audio routing to settle
                    android.util.Log.d("MajlisRoom", "🔄 Restarting audio capture with new device: $deviceName")
                    
                    // Actually restart audio capture
                    try {
                        if (isSpeaking && userState == "LISTENING") {
                            val capture = BluetoothScoAudioCapture(context)
                            capture.setListener(object : BluetoothScoAudioCapture.AudioCaptureListener {
                                override fun onAudioData(data: ByteArray, size: Int) {
                                    // CRITICAL: Check isSpeaking state before processing
                                    if (!isSpeaking || userState != "LISTENING") {
                                        // Reset VAD state when disabled
                                        vadGateOpen = false
                                        vadSpeechFrameCount = 0
                                        vadNonSpeechFrameCount = 0
                                        vadFrameBuffer = ByteArray(0)
                                        return  // Mic disabled - ignore audio
                                    }
                                    
                                    // Accumulate audio to form 20ms frames (320 bytes at 8kHz, 16-bit mono)
                                    val newBuffer = ByteArray(vadFrameBuffer.size + size)
                                    System.arraycopy(vadFrameBuffer, 0, newBuffer, 0, vadFrameBuffer.size)
                                    System.arraycopy(data, 0, newBuffer, vadFrameBuffer.size, size)
                                    vadFrameBuffer = newBuffer
                                    
                                    // Process complete 20ms frames
                                    while (vadFrameBuffer.size >= FRAME_SIZE_20MS) {
                                        val frame = ByteArray(FRAME_SIZE_20MS)
                                        System.arraycopy(vadFrameBuffer, 0, frame, 0, FRAME_SIZE_20MS)
                                        
                                        // Calculate RMS for this frame
                                        var sum = 0L
                                        for (i in 0 until FRAME_SIZE_20MS step 2) {
                                            if (i + 1 < FRAME_SIZE_20MS) {
                                                val sample = (frame[i].toInt() and 0xFF) or (frame[i + 1].toInt() shl 8)
                                                val signedSample = if (sample > 32767) sample - 65536 else sample
                                                sum += signedSample.toLong() * signedSample
                                            }
                                        }
                                        val rms = kotlin.math.sqrt(sum.toDouble() / (FRAME_SIZE_20MS / 2)).toInt()
                                        
                                        // Update volume level for UI
                                        volumeLevel = (rms / 100).coerceIn(0, 100)
                                        
                                        // Calibrate background noise level (first 2 seconds = 100 frames)
                                        if (noiseCalibrationSamples < 100) {
                                            backgroundNoiseLevel = (backgroundNoiseLevel * noiseCalibrationSamples + rms) / (noiseCalibrationSamples + 1)
                                            noiseCalibrationSamples++
                                            // Adaptive threshold: Higher multipliers for very close voice only
                                            if (noiseCalibrationSamples >= 50) {
                                                vadRmsThreshold = (backgroundNoiseLevel * 2.5).toInt().coerceIn(20, 50)
                                                nearFieldRmsThreshold = (backgroundNoiseLevel * 3.5).toInt().coerceIn(30, 70)
                                            }
                                        }
                                        
                                        // VAD: Check if frame contains speech
                                        val isSpeech = rms >= vadRmsThreshold
                                        val isNearField = rms >= nearFieldRmsThreshold
                                        
                                        // Update frame counters
                                        if (isSpeech && isNearField) {
                                            vadSpeechFrameCount++
                                            vadNonSpeechFrameCount = 0
                                        } else {
                                            vadNonSpeechFrameCount++
                                            vadSpeechFrameCount = 0
                                        }
                                        
                                        // Gate open condition: 5 consecutive speech frames (100ms) - stricter for very close voice
                                        if (!vadGateOpen && vadSpeechFrameCount >= 5) {
                                            vadGateOpen = true
                                            Log.d("Majlis", "🎤 VAD Gate OPEN (RMS: $rms, threshold: $vadRmsThreshold, nearField: $nearFieldRmsThreshold)")
                                        }
                                        
                                        // Gate close condition: 10 consecutive non-speech frames (200ms)
                                        if (vadGateOpen && vadNonSpeechFrameCount >= 10) {
                                            vadGateOpen = false
                                            vadSpeechFrameCount = 0
                                            Log.d("Majlis", "🔇 VAD Gate CLOSE (RMS: $rms)")
                                        }
                                        
                                        // Only send audio to Soniox when gate is open
                                        if (vadGateOpen && googleSTT.isConnected()) {
                                            googleSTT.sendAudio(frame)
                                        }
                                        
                                        // Remove processed frame from buffer
                                        val remainingSize = vadFrameBuffer.size - FRAME_SIZE_20MS
                                        val remainingBuffer = ByteArray(remainingSize)
                                        System.arraycopy(vadFrameBuffer, FRAME_SIZE_20MS, remainingBuffer, 0, remainingSize)
                                        vadFrameBuffer = remainingBuffer
                                    }
                                }
                                override fun onScoConnected() {
                                    Log.d("Majlis", "🎧 Mic connected")
                                }
                                override fun onScoDisconnected() {
                                    Log.d("Majlis", "🎧 Mic disconnected")
                                }
                                override fun onError(message: String) {
                                    Log.e("Majlis", "❌ Audio: $message")
                                }
                            })
                            
                            // Start SCO connection and recording
                            if (deviceName.contains("Bluetooth", ignoreCase = true)) {
                                capture.startScoConnection()
                            } else {
                                capture.startRecording(useHandsfree = false)
                            }
                            
                            audioCapture = capture
                            
                            // Restart STT service
                            val googleLangCode = when (unifiedLanguage) {
                                TranslationService.LANG_KOREAN -> "ko"
                                TranslationService.LANG_ENGLISH -> "en"
                                TranslationService.LANG_ARABIC -> "ar"
                                TranslationService.LANG_SPANISH -> "es"
                                else -> "auto"
                            }
                            googleSTT.startListening(googleLangCode)
                            
                            android.util.Log.d("MajlisRoom", "✅ Audio capture restarted successfully with $deviceName")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MajlisRoom", "❌ Failed to restart audio capture: ${e.message}", e)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MajlisRoom", "❌ Error switching audio input: ${e.message}", e)
        }
    }
    var lastAudioDebugMs by remember { mutableStateOf(0L) }
    var sonioxStartRequested by remember { mutableStateOf(false) }
    // Track peer speaking status from Firebase
    var peerSpeakingStatus by remember { mutableMapOf<String, Boolean>() }  // userId -> isSpeaking
    
    // Room entry timestamp - ignore messages before this time
    val roomEntryTime = remember { System.currentTimeMillis() }
    
    // Track processed message IDs to avoid duplicates
    val processedMessageIds = remember { mutableSetOf<String>() }
    
    // Track translation callbacks per message ID and original text
    val translationCallbacks = remember { mutableMapOf<String, (String) -> Unit>() }
    val translationDeltaCallbacks = remember { mutableMapOf<String, (String) -> Unit>() }  // For streaming updates
    val messageTextMap = remember { mutableMapOf<String, String>() }  // messageId -> originalText
    val ttsPlayingMessageIds = remember { mutableMapOf<String, String>() }  // messageId -> current playing messageId (for Realtime TTS)
    
    // Setup onSpeechEnd callback for Realtime TTS
    LaunchedEffect(Unit) {
        openAIRealtimeTTS.onSpeechEnd = {
            // Find the message that is currently playing TTS
            val currentPlayingMessageId = ttsPlayingMessageIds["current"]
            if (currentPlayingMessageId != null) {
                android.util.Log.d("MajlisRoom", "🔊✓ Realtime TTS playback completed for message: $currentPlayingMessageId")
                // Notify sender that TTS playback completed
                firebaseService.markTTSPlayed(currentPlayingMessageId)
                // Clear tracking
                ttsPlayingMessageIds.remove("current")
            } else {
                android.util.Log.w("MajlisRoom", "⚠️ onSpeechEnd called but no current playing message ID")
            }
        }
    }
    
    // Track TTS status changes with a derived key
    val ttsStatusKey = remember(firebaseMessages) {
        firebaseMessages
            .filter { it.senderId == firebaseService.myUserId }
            .joinToString("|") { "${it.messageId}:${it.ttsPlayingBy.size}:${it.ttsPlayedBy.size}" }
    }
    
    // Update chatHistory when TTS status changes (for TTS status updates)
    LaunchedEffect(ttsStatusKey) {
        // Process all my messages to catch TTS status updates
        firebaseMessages.forEach { firebaseMsg ->
            // IMPORTANT: Skip messages from before room entry
            if (firebaseMsg.timestamp < roomEntryTime) {
                return@forEach
            }
            
            // Check if this is my own message (for TTS playback status updates)
            if (firebaseMsg.senderId == firebaseService.myUserId) {
                // This is my message - update ttsPlayingByOthers and ttsPlayedByOthers status
                // Filter to only include users that are actually connected
                val connectedUserIds = connectedUsers.map { it.userId }.toSet()
                val ttsPlayingByOthers = firebaseMsg.ttsPlayingBy
                    .filter { it != firebaseService.myUserId && connectedUserIds.contains(it) }
                    .toSet()
                val ttsPlayedByOthers = firebaseMsg.ttsPlayedBy
                    .filter { it != firebaseService.myUserId && connectedUserIds.contains(it) }
                    .toSet()
                
                // Try to find message in chatHistory by messageId first
                var existingMsg = chatHistory.find { it.messageId == firebaseMsg.messageId }
                
                // If not found, try to find by text and timestamp (for messages that were just sent)
                if (existingMsg == null && firebaseMsg.messageId.isNotBlank()) {
                    existingMsg = chatHistory.find { 
                        it.speaker == "나" && 
                        it.original == firebaseMsg.originalText &&
                        kotlin.math.abs(it.timestamp - firebaseMsg.timestamp) < 5000 &&
                        (it.messageId.isEmpty() || it.messageId == firebaseMsg.messageId)
                    }
                    if (existingMsg != null) {
                        android.util.Log.d("MajlisRoom", "🔗 Found message by text, updating messageId: ${firebaseMsg.messageId}")
                        // Update messageId and TTS status
                        chatHistory = chatHistory.map { msg ->
                            if (msg == existingMsg) {
                                msg.copy(
                                    messageId = firebaseMsg.messageId,
                                    ttsPlayingByOthers = ttsPlayingByOthers,
                                    ttsPlayedByOthers = ttsPlayedByOthers
                                )
                            } else {
                                msg
                            }
                        }
                        return@forEach
                    }
                }
                
                // Update TTS status if message found
                if (existingMsg != null) {
                    val oldPlaying = existingMsg.ttsPlayingByOthers.size
                    val oldPlayed = existingMsg.ttsPlayedByOthers.size
                    
                    if (oldPlaying != ttsPlayingByOthers.size || oldPlayed != ttsPlayedByOthers.size) {
                        android.util.Log.d("MajlisRoom", "✅ Updating chatHistory: ${firebaseMsg.messageId} - playing: $oldPlaying→${ttsPlayingByOthers.size}, played: $oldPlayed→${ttsPlayedByOthers.size}")
                        chatHistory = chatHistory.map { msg ->
                            if (msg.messageId == firebaseMsg.messageId) {
                                msg.copy(
                                    ttsPlayingByOthers = ttsPlayingByOthers,
                                    ttsPlayedByOthers = ttsPlayedByOthers
                                )
                            } else {
                                msg
                            }
                        }
                    }
                } else if (firebaseMsg.messageId.isNotBlank()) {
                    android.util.Log.w("MajlisRoom", "⚠️ Message ${firebaseMsg.messageId} not found in chatHistory (text: '${firebaseMsg.originalText.take(20)}...')")
                }
            }
        }
    }
    
    // Add Firebase messages to chat history when received + CLIENT-SIDE TRANSLATION + TTS
    // CLIENT-SIDE TRANSLATION: Each receiver translates to their own language using OpenAI
    LaunchedEffect(firebaseMessages.size) {
        if (firebaseMessages.isNotEmpty()) {
            val latestMessage = firebaseMessages.last()
            
            // IMPORTANT: Skip messages from before room entry
            if (latestMessage.timestamp < roomEntryTime) {
                android.util.Log.d("MajlisRoom", "⏭️ Skipping old message (before room entry): ${latestMessage.originalText}")
                return@LaunchedEffect
            }
            
            // Skip if already processed
            if (processedMessageIds.contains(latestMessage.messageId)) {
                android.util.Log.d("MajlisRoom", "⏭️ Message already processed: ${latestMessage.messageId}")
                return@LaunchedEffect
            }
            
            android.util.Log.d("MajlisRoom", "📨 Firebase message received: ${latestMessage.originalText}")
            android.util.Log.d("MajlisRoom", "   - Message ID: ${latestMessage.messageId}")
            android.util.Log.d("MajlisRoom", "   - Sender ID: ${latestMessage.senderId}")
            android.util.Log.d("MajlisRoom", "   - Sender Name: ${latestMessage.senderName}")
            android.util.Log.d("MajlisRoom", "   - My User ID: ${firebaseService.myUserId}")
            android.util.Log.d("MajlisRoom", "   - Sender Language: ${latestMessage.senderLanguage}")
            android.util.Log.d("MajlisRoom", "   - My Listening Language: $myListeningLanguage")
            
            // Skip my own messages (handled in separate LaunchedEffect above)
            if (latestMessage.senderId == firebaseService.myUserId) {
                return@LaunchedEffect  // Don't process as peer message
            }
            
            // Mark as processed FIRST (before any other checks)
            processedMessageIds.add(latestMessage.messageId)
            
            // STRONG duplicate check for peer messages: Multiple criteria
            val now = System.currentTimeMillis()
            val trimmedText = latestMessage.originalText.trim()
            val isDuplicate = chatHistory.any { msg ->
                // Check 1: Same message ID (most reliable)
                (msg.messageId == latestMessage.messageId && latestMessage.messageId.isNotBlank()) ||
                // Check 2: Same text + same speaker + within 10 seconds (longer window for peer messages)
                (msg.original.trim().equals(trimmedText, ignoreCase = true) &&
                 msg.speaker == latestMessage.senderName &&
                 (now - msg.timestamp) < 10000) ||  // Within 10 seconds
                // Check 3: Similar text (80% match) + same speaker + within 5 seconds (for partial duplicates)
                (msg.original.trim().length > 5 && 
                 trimmedText.length > 5 &&
                 msg.speaker == latestMessage.senderName &&
                 (now - msg.timestamp) < 5000 &&
                 (msg.original.trim().contains(trimmedText, ignoreCase = true) ||
                  trimmedText.contains(msg.original.trim(), ignoreCase = true)))
            }
            
            if (isDuplicate) {
                android.util.Log.d("MajlisRoom", "⏭️ Duplicate peer message detected: '${latestMessage.originalText}' from ${latestMessage.senderName}, skipping")
                return@LaunchedEffect  // Exit LaunchedEffect, not launch block
            }
            
            // Check if translation is needed
            val needsTranslation = latestMessage.senderLanguage != myListeningLanguage
            
            if (!needsTranslation) {
                // Same language - no translation needed
                android.util.Log.d("MajlisRoom", "ℹ️ Same language, no translation needed")
                
                // Final duplicate check before adding (double-check)
                val finalDuplicateCheck = chatHistory.any { msg ->
                    msg.messageId == latestMessage.messageId ||
                    (msg.original.trim().equals(latestMessage.originalText.trim(), ignoreCase = true) &&
                     msg.speaker == latestMessage.senderName &&
                     (System.currentTimeMillis() - msg.timestamp) < 10000)
                }
                
                if (finalDuplicateCheck) {
                    android.util.Log.d("MajlisRoom", "⏭️ Final duplicate check failed, skipping: '${latestMessage.originalText}'")
                    return@LaunchedEffect
                }
                
                // Add to chat history (mark as complete immediately for same language)
                val newMessage = ChatMessage(
                    speaker = latestMessage.senderName,
                    speakerLanguage = latestMessage.senderLanguage,
                    original = latestMessage.originalText,
                    translated = latestMessage.originalText,
                    timestamp = latestMessage.timestamp,
                    messageId = latestMessage.messageId,
                    isComplete = true,
                    isSent = false,  // Not my message
                    isTTSPlayed = false  // Will be updated after TTS completes
                )
                // Insert in sorted position to maintain order
                chatHistory = (chatHistory + newMessage).sortedBy { it.timestamp }
                
                // Play TTS
            scope.launch {
                    try {
                        // TTS for peer messages (always enabled)
                        android.util.Log.d("MajlisRoom", "🔊 Playing TTS (same language): ${latestMessage.originalText}")
                        // Notify sender that TTS playback started
                        firebaseService.markTTSPlaying(latestMessage.messageId)
                        val ttsSuccess = openAI.speak(
                            latestMessage.originalText, 
                            myListeningLanguage, 
                            useBluetooth = true, 
                            voice = detectedVoice,
                            onComplete = {
                                // Update TTS played status after completion
                                chatHistory = chatHistory.map { msg ->
                                    if (msg.messageId == latestMessage.messageId) {
                                        msg.copy(isTTSPlayed = true)
                } else {
                                        msg
                                    }
                                }
                                // Notify sender that TTS playback completed
                                firebaseService.markTTSPlayed(latestMessage.messageId)
                                android.util.Log.d("MajlisRoom", "🔊✓ TTS playback completed for message: ${latestMessage.messageId}")
                            }
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("MajlisRoom", "❌ TTS error: ${e.message}", e)
                    }
                }
            } else {
                // Different language - translate using OpenAI Realtime TTS
                android.util.Log.d("MajlisRoom", "🔄 Translating ${latestMessage.senderLanguage} → $myListeningLanguage (OpenAI Realtime)")
                
                // Final duplicate check before adding (double-check)
                val finalDuplicateCheck = chatHistory.any { msg ->
                    msg.messageId == latestMessage.messageId ||
                    (msg.original.trim().equals(latestMessage.originalText.trim(), ignoreCase = true) &&
                     msg.speaker == latestMessage.senderName &&
                     (System.currentTimeMillis() - msg.timestamp) < 10000)
                }
                
                if (finalDuplicateCheck) {
                    android.util.Log.d("MajlisRoom", "⏭️ Final duplicate check failed, skipping: '${latestMessage.originalText}'")
                    return@LaunchedEffect
                }
                
                // Add to chat history with original text first (will update with translation)
                // Mark as complete since we received the full original text from Firebase
                val newMessage = ChatMessage(
                    speaker = latestMessage.senderName,
                    speakerLanguage = latestMessage.senderLanguage,
                    original = latestMessage.originalText,
                    translated = latestMessage.originalText, // Temporary, will update
                    timestamp = latestMessage.timestamp,
                    messageId = latestMessage.messageId,
                    isComplete = false,  // Will be updated when translation completes
                    isSent = false,  // Not my message
                    isTTSPlayed = false  // Will be updated after TTS completes
                )
                // Insert in sorted position to maintain order
                chatHistory = (chatHistory + newMessage).sortedBy { it.timestamp }
                
                // Use OpenAI Realtime TTS for translation + TTS
                scope.launch {
                    try {
                        // Store original text for this message
                        messageTextMap[latestMessage.messageId] = latestMessage.originalText
                        
                        // Setup translation callback for this specific message ID
                        translationCallbacks[latestMessage.messageId] = { translatedText ->
                            android.util.Log.d("MajlisRoom", "✅ Realtime translation for ${latestMessage.messageId}: $translatedText")
                            
                            // Update chat history with translation using messageId
                            chatHistory = chatHistory.map { msg ->
                                if (msg.messageId == latestMessage.messageId) {
                                    msg.copy(translated = translatedText, isComplete = true)  // Mark as complete
                                } else {
                                    msg
                                }
                            }
                            
                            // Cleanup
                            translationCallbacks.remove(latestMessage.messageId)
                            translationDeltaCallbacks.remove(latestMessage.messageId)
                            messageTextMap.remove(latestMessage.messageId)
                        }
                        
                        // Setup streaming translation callback for this specific message
                        // Capture messageId in closure
                        val messageId = latestMessage.messageId
                        translationDeltaCallbacks[messageId] = { streamingText ->
                            android.util.Log.d("MajlisRoom", "📝 Streaming translation for $messageId (${streamingText.length} chars): ${streamingText.take(50)}")
                            // Update chat history with streaming translation text
                            // Direct update - Compose state can be updated from any thread
                            chatHistory = chatHistory.map { msg ->
                                if (msg.messageId == messageId) {
                                    msg.copy(translated = streamingText, isComplete = false)  // Mark as streaming
                                } else {
                                    msg
                                }
                            }
                        }
                        
                        // Setup global callbacks (only once, shared across all messages)
                        if (openAIRealtimeTTS.onTranslationDelta == null) {
                            // Global delta callback routes to message-specific callbacks
                            openAIRealtimeTTS.onTranslationDelta = { streamingText ->
                                android.util.Log.d("MajlisRoom", "📥 Delta received: ${streamingText.take(30)}")
                                
                                // Find the most recent message that has a delta callback registered
                                // Check by callback existence, not by translated == original (because it changes during streaming)
                                val availableCallbacks = translationDeltaCallbacks.keys.toList()
                                if (availableCallbacks.isEmpty()) {
                                    android.util.Log.w("MajlisRoom", "⚠️ No delta callbacks registered")
                                } else {
                                    // Find the most recent message with a callback
                                    val matchedMessage = chatHistory
                                        .filter { it.messageId in availableCallbacks }
                                        .sortedByDescending { it.timestamp }
                                        .firstOrNull()
                                    
                                    if (matchedMessage != null) {
                                        android.util.Log.d("MajlisRoom", "🔗 Routing delta to message ${matchedMessage.messageId}: ${streamingText.take(30)}")
                                        translationDeltaCallbacks[matchedMessage.messageId]?.invoke(streamingText)
                                    } else {
                                        android.util.Log.w("MajlisRoom", "⚠️ No matching message for delta callback. Available: $availableCallbacks")
                                    }
                                }
                            }
                            
                            // Global final callback routes to message-specific callbacks
                            openAIRealtimeTTS.onTranslation = { translatedText ->
                                android.util.Log.d("MajlisRoom", "✅ Final translation received: ${translatedText.take(30)}")
                                
                                // Find the most recent message that has a translation callback registered
                                val availableCallbacks = translationCallbacks.keys.toList()
                                if (availableCallbacks.isEmpty()) {
                                    android.util.Log.w("MajlisRoom", "⚠️ No translation callbacks registered")
                                } else {
                                    // Find the most recent message with a callback
                                    val matchedMessage = chatHistory
                                        .filter { it.messageId in availableCallbacks }
                                        .sortedByDescending { it.timestamp }
                                        .firstOrNull()
                                    
                                    if (matchedMessage != null) {
                                        android.util.Log.d("MajlisRoom", "🔗 Matching final translation to message ${matchedMessage.messageId}")
                                        translationCallbacks[matchedMessage.messageId]?.invoke(translatedText)
                                        // Cleanup delta callback too
                                        translationDeltaCallbacks.remove(matchedMessage.messageId)
                                    } else {
                                        android.util.Log.w("MajlisRoom", "⚠️ No matching message for translation callback. Available: $availableCallbacks")
                                    }
                                }
                            }
                        }
                        
                        // ALWAYS ensure connection with correct language pair
                        // Check if connection is already established with correct language pair
                        val needsReconnect = !openAIRealtimeTTS.isConnected() || 
                            (openAIRealtimeTTS.currentSourceLangCode != latestMessage.senderLanguage) ||
                            (openAIRealtimeTTS.currentTargetLangCode != myListeningLanguage)
                        
                        if (needsReconnect) {
                            android.util.Log.d("MajlisRoom", "🔌 Connecting OpenAI Realtime TTS: ${latestMessage.senderLanguage} → $myListeningLanguage")
                            openAIRealtimeTTS.connect(
                                sourceLang = latestMessage.senderLanguage,
                                targetLang = myListeningLanguage
                            )
                            // Wait for connection (reduced delay - connection is usually fast)
                            kotlinx.coroutines.delay(300)  // Reduced from 500ms
                        } else {
                            android.util.Log.d("MajlisRoom", "✅ Realtime TTS already connected with correct language pair")
                        }
                        
                        // Send text for translation + TTS (streaming)
                        if (openAIRealtimeTTS.isConnected()) {
                            android.util.Log.d("MajlisRoom", "📤 Sending to Realtime TTS: ${latestMessage.originalText} (${latestMessage.senderLanguage} → $myListeningLanguage)")
                            // Notify sender that TTS playback started
                            firebaseService.markTTSPlaying(latestMessage.messageId)
                            // Track which message is currently playing TTS
                            ttsPlayingMessageIds["current"] = latestMessage.messageId
                            openAIRealtimeTTS.translateAndSpeak(latestMessage.originalText)
                        } else {
                            android.util.Log.e("MajlisRoom", "❌ Realtime TTS not connected, falling back to regular translation")
                            // Fallback to regular translation
                            val translationResult = openAI.translate(
                                text = latestMessage.originalText,
                                targetLanguage = myListeningLanguage,
                                sourceLanguage = latestMessage.senderLanguage
                            )
                            
                            if (translationResult != null) {
                                val translatedText = translationResult.translatedText
                                android.util.Log.d("MajlisRoom", "✅ Fallback translation: $translatedText")
                                
                                chatHistory = chatHistory.map { msg ->
                                    if (msg.messageId == latestMessage.messageId) {
                                        msg.copy(translated = translatedText, isComplete = true)  // Mark as complete
                                    } else {
                                        msg
                                    }
                                }
                                
                                // TTS for peer messages (always enabled)
                                try {
                                    // Notify sender that TTS playback started
                                    firebaseService.markTTSPlaying(latestMessage.messageId)
                                    val ttsSuccess = openAI.speak(
                                        translatedText, 
                                        myListeningLanguage, 
                                        useBluetooth = true, 
                                        voice = detectedVoice,
                                        onComplete = {
                                            // Update TTS played status after completion
                                            chatHistory = chatHistory.map { msg ->
                                                if (msg.messageId == latestMessage.messageId) {
                                                    msg.copy(isTTSPlayed = true)
                                                } else {
                                                    msg
                                                }
                                            }
                                            // Notify sender that TTS playback completed
                                            firebaseService.markTTSPlayed(latestMessage.messageId)
                                            android.util.Log.d("MajlisRoom", "🔊✓ TTS playback completed for message: ${latestMessage.messageId}")
                                        }
                                    )
                                } catch (e: Exception) {
                                    android.util.Log.e("MajlisRoom", "❌ TTS error: ${e.message}", e)
                                }
                            }
                            
                            // Remove callback
                            translationCallbacks.remove(latestMessage.messageId)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MajlisRoom", "❌ Translation error: ${e.message}", e)
                            // Cleanup on error
                            translationCallbacks.remove(latestMessage.messageId)
                            translationDeltaCallbacks.remove(latestMessage.messageId)
                            messageTextMap.remove(latestMessage.messageId)
                    }
                }
            }
        }
    }
    
    // GLOBAL: Pause STT when voice command mode is active
    LaunchedEffect(isVoiceCommandMode) {
        if (isVoiceCommandMode) {
            // Voice command mode started - pause our STT
            Log.d("MajlisRoom", "⏸️ Voice command mode - pausing STT")
            audioCapture?.stop()
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
    
    // RestartSTT when language settings change (but not on initial load)
    var previousUnifiedLanguage by remember { mutableStateOf(unifiedLanguage) }
    
    LaunchedEffect(unifiedLanguage) {
        // Only restart if unified language actually changed (not on initial load)
        if (unifiedLanguage != previousUnifiedLanguage && userState == "LISTENING") {
            Log.d("MajlisRoom", "🔄 Unified language changed: $previousUnifiedLanguage → $unifiedLanguage")
            
            previousUnifiedLanguage = unifiedLanguage
            
            // Update Firebase with new unified language
            firebaseService.updateLanguage(unifiedLanguage)
            
            // Stop current STT service
            googleSTT.stopListening()
            
            // Small delay before restart
            kotlinx.coroutines.delay(500)
            
            // Restart with new language settings
            val googleLangCode = when (unifiedLanguage) {
                TranslationService.LANG_KOREAN -> "ko"
                TranslationService.LANG_ENGLISH -> "en"
                TranslationService.LANG_ARABIC -> "ar"
                TranslationService.LANG_SPANISH -> "es"
                else -> "auto"
            }
            googleSTT.startListening(googleLangCode)
            
            Log.d("MajlisRoom", "✅ Services restarted with new language settings")
        }
    }
    
    // Sync TTS toggle with OpenAI Realtime service
    // TTS for my own speech is always disabled - no need to hear my own voice or translation
    // myTtsEnabled toggle is kept for UI but not used for OpenAI Realtime
    
    // Update current original - show only the current partial transcription (not accumulated)
    // Use partialTranscription from Soniox instead of accumulated transcription
    val partialTranscription by googleSTT.partialTranscription.collectAsStateWithLifecycle()
    
    LaunchedEffect(partialTranscription) {
        // Only show partial transcription if it's not empty and not already in chat history
        val partial = partialTranscription.trim()
        if (partial.isNotBlank()) {
            // Check if this partial text is already in chat history (to avoid showing duplicates)
            val isAlreadyInHistory = chatHistory.any { 
                it.original.contains(partial, ignoreCase = true) && it.isComplete 
            }
            if (!isAlreadyInHistory) {
                currentOriginal = partial
            } else {
                // Already processed, clear it
                currentOriginal = ""
            }
        } else {
            // No partial transcription, clear current original
            currentOriginal = ""
        }
    }
    
    // Process partial transcript for real-time feedback (streaming)
    // This provides immediate feedback before sentence completion
    fun processPartialTranscript(text: String, speaker: String?, detectedLang: String?) {
        scope.launch {
            // Determine if this is my message
            val isMyMessage = speaker == null || speaker == "1"
            
            if (isMyMessage) {
                // My partial transcript - send to Firebase for real-time updates
                val langCode = detectedLang?.split("-")?.first() ?: when (unifiedLanguage) {
                    TranslationService.LANG_KOREAN -> "ko"
                    TranslationService.LANG_ENGLISH -> "en"
                    TranslationService.LANG_ARABIC -> "ar"
                    TranslationService.LANG_SPANISH -> "es"
                    else -> "en"
                }
                
                val fullLanguage = when (langCode) {
                    "ko" -> TranslationService.LANG_KOREAN
                    "en" -> TranslationService.LANG_ENGLISH
                    "ar" -> TranslationService.LANG_ARABIC
                    "es" -> TranslationService.LANG_SPANISH
                    else -> TranslationService.LANG_ENGLISH
                }
                
                // Send partial text to Firebase for real-time updates
                firebaseService.sendPartialText(text, fullLanguage)
                
                // Start partial translation if needed (for preview)
                val currentListeningLang = if (unifiedLanguage == "auto") {
                    fullLanguage
                } else {
                    unifiedLanguage
                }
                
                if (currentListeningLang != fullLanguage && text.length > 5) {
                    // Start streaming translation for preview
                    // Use OpenAI Realtime API for streaming translation
                    if (openAIRealtimeTTS.isConnected()) {
                        // Already connected - can use for streaming
                        // Note: Realtime API expects full sentences, so we'll just update UI
                        currentOriginal = text
                        userState = "TRANSLATING"
                    }
                }
            }
        }
    }
    
    // Common processing function for both Auto and specific language modes
    // speaker: Speaker ID from Soniox diarization (e.g., "1", "2", null if not available)
    fun processTranscript(text: String, languageCode: String, speaker: String? = null) {
        scope.launch {
            // Convert language code to full language identifier
            val fullLanguage = when (languageCode) {
                "ko" -> TranslationService.LANG_KOREAN
                "en" -> TranslationService.LANG_ENGLISH
                "ar" -> TranslationService.LANG_ARABIC
                "es" -> TranslationService.LANG_SPANISH
                else -> TranslationService.LANG_ENGLISH
            }
            
            // Always use "나" (me) - speaker diarization disabled
            val speakerName = "나"
            
            // STRONG duplicate check: Check if same text from same speaker was added recently (within 3 seconds)
            val now = System.currentTimeMillis()
            val recentDuplicate = chatHistory.any { msg ->
                msg.original.trim().equals(text.trim(), ignoreCase = true) &&
                msg.speaker == speakerName &&
                (now - msg.timestamp) < 3000  // Within 3 seconds
            }
            
            if (recentDuplicate) {
                android.util.Log.d("MajlisRoom", "⏭️ Duplicate message detected (recent): '$text' from $speakerName, skipping")
                return@launch
            }
            
            // Process transcript
        currentOriginal = text
        userState = "TRANSLATING"
        
        // Skip translation if listeningLanguage == speakingLanguage
            val currentListeningLang = if (unifiedLanguage == "auto") {
                // In Auto mode, use detected language as listening language
                fullLanguage
            } else {
                unifiedLanguage
            }
            
            if (currentListeningLang == fullLanguage) {
            currentTranslation = text
                // Add to chat history with speaker info
                android.util.Log.d("MajlisRoom", "✅ Adding to chat bubble: '$text' (language: $fullLanguage, speaker: $speakerName)")
                val newMessage = ChatMessage(
                    speaker = speakerName,
                    speakerLanguage = fullLanguage,
                    original = text,
                    translated = text,
                    messageId = "",
                    isComplete = true,
                    isSent = false,  // Will be updated after Firebase send
                    isTTSPlayed = false
                )
                chatHistory = (chatHistory + newMessage).sortedBy { it.timestamp }
                lastProcessedLength = sttTranscription.length
                
                // Broadcast to nearby peers (only if it's my message)
                if (speakerName == "나") {
                    firebaseService.sendMessage(text, fullLanguage)
                    // Update message as sent and set messageId from Firebase
                    // Wait a bit for Firebase to create the message
            scope.launch {
                        kotlinx.coroutines.delay(200) // Wait for Firebase to create message
                        // Find the Firebase message with matching text and timestamp
                        val firebaseMsg = firebaseMessages.find { 
                            it.senderId == firebaseService.myUserId && 
                            it.originalText == text &&
                            kotlin.math.abs(it.timestamp - newMessage.timestamp) < 5000 // Within 5 seconds
                        }
                        if (firebaseMsg != null) {
                            val messageIndex = chatHistory.indexOfFirst { 
                                it.speaker == speakerName && 
                                it.original == text && 
                                it.timestamp == newMessage.timestamp &&
                                it.messageId.isEmpty() // Only update if messageId is empty
                            }
                            if (messageIndex >= 0) {
                                android.util.Log.d("MajlisRoom", "✅ Updating my message with Firebase messageId: ${firebaseMsg.messageId}")
                                chatHistory = chatHistory.toMutableList().apply {
                                    set(messageIndex, chatHistory[messageIndex].copy(
                                        isSent = true,
                                        messageId = firebaseMsg.messageId
                                    ))
                                }.sortedBy { it.timestamp }
                            }
                        }
                    }
                }
                
                // My TTS disabled (always)
                userState = "LISTENING"
                currentOriginal = ""
                currentTranslation = ""
        } else {
                // GOOGLE FULL STACK: Translation + TTS
                userState = "TRANSLATING"
                val result = googleTranslation.translate(
                    text = text,
                    targetLanguage = currentListeningLang,
                    sourceLanguage = fullLanguage
                )
                
                if (result != null) {
                    // Update UI immediately
                    currentTranslation = result.translatedText
                    // Add to chat history with speaker info
                    android.util.Log.d("MajlisRoom", "✅ Adding to chat bubble: '$text' → '$result.translatedText' (language: $fullLanguage, speaker: $speakerName)")
                    chatHistory = (chatHistory + ChatMessage(speakerName, fullLanguage, text, result.translatedText, messageId = "", isComplete = true)).sortedBy { it.timestamp }
                    lastProcessedLength = sttTranscription.length
                    
                    // Broadcast to peers (only if it's my message)
                    if (speakerName == "나") {
                        firebaseService.sendMessage(text, fullLanguage)
                    }
                    
                    // My TTS disabled (always)
                userState = "LISTENING"
                currentOriginal = ""
                currentTranslation = ""
                }
            }
        }
    }
    
    // Helper function to detect language from text content using Unicode ranges
    // This is used as a fallback when Soniox doesn't provide language detection
    fun detectLanguageFromText(text: String): String? {
        if (text.isBlank()) return null
        
        var koreanCount = 0
        var arabicCount = 0
        var latinCount = 0
        var spanishCharCount = 0  // Spanish-specific characters (ñ, á, é, í, ó, ú, ü, etc.)
        
        for (char in text) {
            when {
                // Korean: Hangul syllables (AC00-D7AF), Hangul Jamo (1100-11FF), Hangul Compatibility Jamo (3130-318F)
                char.code in 0xAC00..0xD7AF || char.code in 0x1100..0x11FF || char.code in 0x3130..0x318F -> koreanCount++
                // Arabic: Arabic (0600-06FF), Arabic Supplement (0750-077F), Arabic Extended-A (08A0-08FF)
                char.code in 0x0600..0x06FF || char.code in 0x0750..0x077F || char.code in 0x08A0..0x08FF -> arabicCount++
                // Spanish-specific characters: ñ (00F1), á (00E1), é (00E9), í (00ED), ó (00F3), ú (00FA), ü (00FC)
                // Also uppercase: Ñ (00D1), Á (00C1), É (00C9), Í (00CD), Ó (00D3), Ú (00DA), Ü (00DC)
                char.code in 0x00C1..0x00C2 || char.code in 0x00C9..0x00CA || char.code in 0x00CD..0x00CE ||
                char.code in 0x00D1..0x00D3 || char.code in 0x00DA..0x00DC ||
                char.code in 0x00E1..0x00E2 || char.code in 0x00E9..0x00EA || char.code in 0x00ED..0x00EE ||
                char.code in 0x00F1..0x00F3 || char.code in 0x00FA..0x00FC -> {
                    latinCount++
                    spanishCharCount++  // Spanish-specific
                }
                // English/Spanish: Basic Latin (A-Z, a-z)
                char.code in 0x0041..0x005A || char.code in 0x0061..0x007A -> latinCount++
            }
        }
        
        val totalChars = text.length
        if (totalChars == 0) return null
        
        // Return language if it has significant presence (>30% of characters)
        // Priority: Korean > Arabic > Spanish (if has Spanish chars) > English (Latin)
        return when {
            koreanCount * 100 / totalChars > 30 -> "ko"
            arabicCount * 100 / totalChars > 30 -> "ar"
            spanishCharCount > 0 && latinCount * 100 / totalChars > 30 -> "es"  // Spanish if has Spanish-specific chars
            latinCount * 100 / totalChars > 30 -> "en"  // Default to English for Latin
            else -> null
        }
    }
    
    // Handle transcription result → Translate → TTS
    // For Auto mode: Uses language detected by Google STT
    fun handleTranscriptWithDetectedLanguage(text: String, detectedLang: String?, speaker: String? = null) {
        if (text.isBlank()) return
        
        // Check if this text was already processed by ME (not by others)
        // Only check MY messages to avoid blocking peer messages with same text
        if (chatHistory.any { it.original == text && it.speaker == "나" }) {
            android.util.Log.d("MajlisRoom", "⏭️ Already processed my own message: $text")
            return  // Already processed by me, skip
        }
        
        // CRITICAL: Language filtering - only accept if it matches detected language (Auto mode)
        // This prevents other languages from appearing in my chat bubble
        scope.launch {
            // Skip very short texts (less than 3 characters - likely noise)
            if (text.length < 3) {
                android.util.Log.d("MajlisRoom", "⏭️ Skipping very short text: '$text'")
                return@launch
            }
            
            // Use language detected by Google STT (Auto mode only)
            if (detectedLang == null) {
                android.util.Log.w("MajlisRoom", "🚫 Language detection failed for: '$text'. Blocking to prevent false positives.")
                return@launch  // Block if detection fails
            }
            
            // Convert Google STT language code (e.g., "ko-KR") to simple code (e.g., "ko")
            val detectedLangSimple = detectedLang.split("-").first()
            
            // Update unifiedLanguage with detected language (Auto mode)
            if (unifiedLanguage == "auto") {
                val detectedLanguageCode = when (detectedLangSimple) {
                    "ko" -> TranslationService.LANG_KOREAN
                    "en" -> TranslationService.LANG_ENGLISH
                    "ar" -> TranslationService.LANG_ARABIC
                    "es" -> TranslationService.LANG_SPANISH
                    else -> null
                }
                if (detectedLanguageCode != null) {
                    android.util.Log.d("MajlisRoom", "🌐 Auto-detected language: $detectedLanguageCode")
                    // Note: unifiedLanguage will be updated, but we'll use detectedLangSimple for processing
                }
            }
            
            // Language detected - proceed with processing
            android.util.Log.d("MajlisRoom", "✅ Language detected: $detectedLang (Auto mode)")
            
            // Process with detected language (include speaker info from diarization)
            processTranscript(text, detectedLangSimple, speaker)
        }
    }
    
    // Handle transcription result → Translate → TTS
    // For specific language: Only accept if detected language matches selected language
    fun handleTranscriptWithSpecificLanguage(text: String, selectedLanguage: String, speaker: String? = null, detectedLang: String? = null) {
        if (text.isBlank()) return
        
        // Check if this text was already processed by ME (not by others)
        if (chatHistory.any { it.original == text && it.speaker == "나" }) {
            android.util.Log.d("MajlisRoom", "⏭️ Already processed my own message: $text")
            return
        }
        
        scope.launch {
            // Skip very short texts (less than 3 characters - likely noise)
            if (text.length < 3) {
                android.util.Log.d("MajlisRoom", "⏭️ Skipping very short text: '$text'")
                return@launch
            }
            
            // Convert selected language to simple code
            val selectedLangCode = when (selectedLanguage) {
                TranslationService.LANG_KOREAN -> "ko"
                TranslationService.LANG_ENGLISH -> "en"
                TranslationService.LANG_ARABIC -> "ar"
                TranslationService.LANG_SPANISH -> "es"
                else -> "en"
            }
            
            // With language_hints_strict enabled, Soniox strongly prefers the selected language
            // We can trust Soniox's output more, but still verify as a safety check
            // Note: language_hints_strict is best-effort, not a hard guarantee
            if (detectedLang != null) {
                val detectedLangSimple = detectedLang.split("-").first().lowercase()
                if (detectedLangSimple != selectedLangCode.lowercase()) {
                    // Even with language_hints_strict, rare edge cases may occur
                    android.util.Log.d("MajlisRoom", "🚫 Language mismatch (rare with language_hints_strict): detected=$detectedLangSimple, selected=$selectedLangCode, ignoring: '$text'")
                    return@launch  // Ignore non-matching language
                }
            }
            
            android.util.Log.d("MajlisRoom", "✅ Processing with selected language: $selectedLangCode (language_hints_strict enabled)")
            
            // Process with selected language (language verified)
            processTranscript(text, selectedLangCode, speaker)
        }
    }
    
    // Setup STT callbacks
    // Soniox uses endpoint detection (<end> token) for sentence detection - no regex needed
    // Other STT services may still use regex pattern for sentence detection
    val sentenceEndPattern = remember { Regex("[.?!。？]\\s*$") }  // For non-Soniox STT services
    var lastProcessedSentence by remember { mutableStateOf("") }
    
    // Legacy function for backward compatibility (still uses separate API call for non-Google STT)
    fun handleTranscript(text: String) {
        // For non-Google STT, still use separate language detection (no speaker info)
        scope.launch {
            val detectedLang = translationService.detectLanguage(text)
            handleTranscriptWithDetectedLanguage(text, detectedLang, null)
        }
    }
    
    // Setup Google STT language detection callback (integrated with STT - no separate API call!)
    LaunchedEffect(Unit) {
        googleSTT.onLanguageDetected = { detectedLang ->
            // Google STT already detected language during transcription - use it directly!
            detectedLanguageBySTT = detectedLang
            Log.d("MajlisRoom", "🌐 Google STT detected language: $detectedLang (no separate API call needed!)")
        }
        
        googleSTT.onTranscript = { text, isFinal, speaker, detectedLang ->
            // Soniox STT provides both transcript AND language detection in one call
            // detectedLang is directly from Soniox token (e.g., "ko", "en", "ar", "es")
            // Speaker diarization: speaker ID from Soniox (e.g., "1", "2", etc.)
            // Soniox uses endpoint detection (<end> token) - isFinal is true when endpoint detected
            // No need for regex pattern matching - Soniox's endpoint detection is more accurate
            
            if (isFinal && text != lastProcessedSentence && text.isNotBlank()) {
                // Final transcript - process completely
                lastProcessedSentence = text
                scope.launch { 
                    // Only use language detection if Auto mode is selected
                    if (unifiedLanguage == "auto") {
                        handleTranscriptWithDetectedLanguage(text, detectedLang, speaker)
                    } else {
                        // CRITICAL: If specific language is selected, IGNORE other languages
                        // Check if detected language matches selected language
                        val selectedLangCode = when (unifiedLanguage) {
                            TranslationService.LANG_KOREAN -> "ko"
                            TranslationService.LANG_ENGLISH -> "en"
                            TranslationService.LANG_ARABIC -> "ar"
                            TranslationService.LANG_SPANISH -> "es"
                            else -> null
                        }
                        
                        if (selectedLangCode != null) {
                            // Detect language from text content if Soniox didn't provide it
                            val actualDetectedLang = detectedLang ?: detectLanguageFromText(text)
                            
                            if (actualDetectedLang != null) {
                                val detectedLangSimple = actualDetectedLang.split("-").first().lowercase()
                                
                                if (detectedLangSimple != selectedLangCode.lowercase()) {
                                    android.util.Log.d("MajlisRoom", "🚫 BLOCKING non-selected language: detected=$detectedLangSimple, selected=$selectedLangCode, text='$text'")
                                    return@launch  // BLOCK this transcript completely
                                } else {
                                    android.util.Log.d("MajlisRoom", "✅ Language matches: detected=$detectedLangSimple, selected=$selectedLangCode, text='$text'")
                                }
                            } else {
                                // No language detected from Soniox or text analysis
                                // Since we're using language_hints, assume it's the selected language
                                android.util.Log.d("MajlisRoom", "ℹ️ No language detected, assuming selected language (selected=$selectedLangCode): '$text'")
                            }
                        }
                        
                        // Language matches or assumed to be selected language - proceed
                        handleTranscriptWithSpecificLanguage(text, unifiedLanguage, speaker, detectedLang)
                    }
                }
                Log.d("MajlisRoom", "✅ Final transcript (Soniox endpoint detection) [Speaker: $speaker, Lang: $detectedLang]")
            } else if (!isFinal && text.isNotBlank() && text.length > 3) {
                // Partial transcript - process immediately for real-time feedback
                scope.launch {
                    processPartialTranscript(text, speaker, detectedLang)
                }
            }
        }
        // Google STT callback is now handled in the earlier LaunchedEffect with language detection
        googleSTT.onError = { error ->
            scope.launch { status = "❌ Google: $error" }
        }
        
        // Legacy handleTranscript for non-Google STT (still uses separate API call)
        fun handleTranscript(text: String) {
            // For non-Google STT, still use separate language detection
            scope.launch {
                val detectedLang = translationService.detectLanguage(text)
                handleTranscriptWithDetectedLanguage(text, detectedLang)
            }
        }
        
    }
    
    // Auto-start listening when entering room (for barge-in)
    LaunchedEffect(mySpeakingLanguage) {
        // Check permission first
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) 
            == PackageManager.PERMISSION_GRANTED) {
            
            // Stop previous and CLEAR all state
            googleSTT.stopListening()
            sonioxStartRequested = false
            audioCapture?.stop()
            
            // Clear previous transcription state to avoid showing old messages
            googleSTT.clearTranscription()
            lastProcessedSentence = ""  // Reset last processed sentence
            chatHistory = emptyList()   // Clear chat history
            
            // Start Soniox STT - Use selected language or auto detection
            if (unifiedLanguage == "auto") {
                // Auto mode: Enable language detection
                Log.d("Majlis", "🎤 Starting Soniox STT with AUTO language identification...")
                googleSTT.startListening("auto")  // "auto" enables language detection
            } else {
                // Specific language: Use selected language (no detection)
                val googleLangCode = when (unifiedLanguage) {
                    TranslationService.LANG_KOREAN -> "ko"
                    TranslationService.LANG_ENGLISH -> "en"
                    TranslationService.LANG_ARABIC -> "ar"
                    TranslationService.LANG_SPANISH -> "es"
                    else -> "en"
                }
                Log.d("Majlis", "🎤 Starting Soniox STT with specific language: $googleLangCode")
                googleSTT.startListening(googleLangCode)
            }
                
                // Set up callbacks for UI updates and Firebase
                // Capture current language settings to use in callbacks
                val capturedSpeakingLang = mySpeakingLanguage
                val capturedListeningLang = myListeningLanguage
                
                // Speech detection callbacks for UI indicator AND Firebase notification
            // Note: Soniox handles speech detection internally, so we use transcription callbacks
                    isSpeaking = true
                    // IMMEDIATELY notify others that I'm speaking
                    firebaseService.sendSpeakingStatus(true)
                
            
            isSpeaking = true
            userState = "LISTENING"
            lastProcessedLength = 0  // Reset on language change
            
            // Start audio capture ONLY if speaking is enabled (mic toggle is ON)
            // CRITICAL: Check isSpeaking state to prevent audio capture when mic is off
            if (isSpeaking && userState == "LISTENING") {
            val capture = BluetoothScoAudioCapture(context)
            capture.setListener(object : BluetoothScoAudioCapture.AudioCaptureListener {
                override fun onAudioData(data: ByteArray, size: Int) {
                        // CRITICAL: Double-check speaking state before processing audio
                        if (!isSpeaking || userState != "LISTENING") {
                            Log.d("MajlisRoom", "⏸️ Mic disabled - ignoring audio data")
                            return
                        }
                        
                    val audioData = data.copyOf(size)
                    
                        // Rate-limited debug (1/sec) so we can confirm routing actually happens
                        val now = System.currentTimeMillis()
                        if (now - lastAudioDebugMs > 1000) {
                            lastAudioDebugMs = now
                            Log.d(
                                "MajlisRoom",
                                "🎧 audio(size=$size) route: Soniox connected=${googleSTT.isConnected()}"
                            )
                        }
                    
                    // Route audio to Soniox STT
                        if (!sonioxStartRequested) {
                            sonioxStartRequested = true
                            val langCode = if (unifiedLanguage == "auto") {
                                "auto"
                            } else {
                                when (unifiedLanguage) {
                                    TranslationService.LANG_KOREAN -> "ko"
                                    TranslationService.LANG_ENGLISH -> "en"
                                    TranslationService.LANG_ARABIC -> "ar"
                                    TranslationService.LANG_SPANISH -> "es"
                                    else -> "en"
                                }
                            }
                            Log.d("MajlisRoom", "🚀 Soniox start requested from audio callback (lang=$langCode)")
                            googleSTT.startListening(langCode)
                        }
                        // Always attempt to send; service will drop until WS open.
                        googleSTT.sendAudio(audioData)
                    
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
                        // CRITICAL: Only start recording if speaking is enabled
                        if (isSpeaking && userState == "LISTENING") {
                            Log.d("Majlis", "🎧 Auto-listening started (mic enabled)")
                    capture.startRecording(useHandsfree = true)
                        } else {
                            Log.d("Majlis", "⏸️ Mic disabled - not starting recording")
                        }
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
                Log.d("MajlisRoom", "⏸️ Mic disabled (isSpeaking=$isSpeaking, userState=$userState) - not starting audio capture")
            }
        }
    }
    
    // Cleanup on leave
    DisposableEffect(Unit) {
        onDispose {
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
                        // Use actual Firebase count (more reliable than connectedUsers)
                        append("👥 ${actualUserCount}명 참여")
                        
                        // Collect unique languages from connected users (including myself)
                        val allLanguages = mutableSetOf<String>()
                        allLanguages.add(unifiedLanguage)  // Add my language
                        connectedUsers.forEach { user ->
                            user.language?.let { allLanguages.add(it) }
                        }
                        
                        // Convert language codes to display names
                        val languageDisplayNames = allLanguages.mapNotNull { lang ->
                            when (lang) {
                                TranslationService.LANG_KOREAN, "ko" -> "한국어"
                                TranslationService.LANG_ENGLISH, "en" -> "English"
                                TranslationService.LANG_ARABIC, "ar" -> "العربية"
                                TranslationService.LANG_SPANISH, "es" -> "Español"
                                "auto" -> null  // Skip auto
                                else -> lang
                            }
                        }.sorted()
                        
                        // Show languages if there are any
                        if (languageDisplayNames.isNotEmpty()) {
                            append(" • ")
                            append(languageDisplayNames.joinToString(", "))
                        }
                        
                        append(" • ")
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
            
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Unified Language selector (single language for everything)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🌐 언어", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("(말하기/번역)", color = Color.Gray, fontSize = 9.sp)
                Spacer(modifier = Modifier.height(4.dp))
                ExposedDropdownMenuBox(
                    expanded = unifiedLangExpanded,
                    onExpandedChange = { unifiedLangExpanded = it }
                ) {
                    Surface(
                        modifier = Modifier.menuAnchor(),
                        color = Color(0xFF2196F3),  // Blue
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            listeningLanguages.find { it.first == unifiedLanguage }?.second ?: "",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    ExposedDropdownMenu(
                        expanded = unifiedLangExpanded,
                        onDismissRequest = { unifiedLangExpanded = false }
                    ) {
                        listeningLanguages.forEach { (code, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    android.util.Log.d("MajlisRoom", "🌐 Unified language changed to: $code")
                                    unifiedLanguage = code
                                    unifiedLangExpanded = false
                                    // Firebase and services will be updated in LaunchedEffect
                                }
                            )
                        }
                    }
                }
            }
            
            // Audio input info and stop button
            Column(horizontalAlignment = Alignment.End) {
                Text("🎤 오디오 입력", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                // Current audio input dropdown
                ExposedDropdownMenuBox(
                    expanded = audioInputExpanded,
                    onExpandedChange = { audioInputExpanded = it }
                ) {
                    Surface(
                        modifier = Modifier.menuAnchor(),
                        color = Color(0xFF4CAF50),  // Green
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                                currentAudioInput ?: "Unknown",
                            color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                        )
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = audioInputExpanded)
                        }
                    }
                    ExposedDropdownMenu(
                        expanded = audioInputExpanded,
                        onDismissRequest = { audioInputExpanded = false }
                    ) {
                        // Current audio input display
                        Text(
                            "현재: ${currentAudioInput ?: "Unknown"}",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Gray
                        )
                        HorizontalDivider()
                        
                        // Available audio inputs
                        Text(
                            "사용 가능:",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            fontSize = 10.sp,
                            color = Color.Gray
                        )
                        if (availableAudioInputs.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("없음", fontSize = 11.sp) },
                                onClick = { audioInputExpanded = false }
                            )
                        } else {
                            availableAudioInputs.forEach { input ->
                                DropdownMenuItem(
                                    text = { 
                                        Row(
            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            if (input == currentAudioInput) {
                                                Text("✓", color = Color(0xFF4CAF50), fontSize = 12.sp)
                                            }
                                            Text(
                                                input, 
                                                fontSize = 11.sp,
                                                color = if (input == currentAudioInput) Color(0xFF4CAF50) else Color.Black
                                            )
                                        }
                                    },
                                    onClick = { 
                                        if (input != currentAudioInput) {
                                            switchAudioInput(input)
                                        }
                                        audioInputExpanded = false 
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Volume Level Indicator (always visible when audio capture is active)
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
                        .fillMaxWidth((volumeLevel / 100f).coerceIn(0f, 1f))
                            .background(
                                when {
                                volumeLevel > 70 -> Color(0xFF4CAF50)  // Green - loud
                                volumeLevel > 30 -> Color(0xFFFFEB3B)  // Yellow - medium
                                volumeLevel > 5 -> Color(0xFF2196F3)   // Blue - quiet
                                else -> Color(0xFF666666)               // Gray - no signal
                                }
                            )
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "$volumeLevel%",
                color = if (volumeLevel > 5) Color.White else Color.Gray,
                    fontSize = 12.sp
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        
        // Unified Chat History Header with Clear Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("💬 대화 (Chat)", color = Color.Gray, fontSize = 12.sp)
            Surface(
                modifier = Modifier.clickable { 
                    chatHistory = emptyList()
                    currentOriginal = ""
                    currentTranslation = ""
                },
                color = Color(0xFF333333),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    "🗑️ Clear",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color.Gray,
                    fontSize = 10.sp
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        
        // Auto-scroll state 
        val chatScrollState = rememberScrollState()
        
        // Track last "나" (my) message timestamp for auto-scroll
        val lastMyMessageTimestamp = chatHistory.lastOrNull { it.speaker == "나" }?.timestamp ?: 0L
        
        // Auto-scroll when MY messages are added (not peer messages)
        LaunchedEffect(lastMyMessageTimestamp) {
            if (lastMyMessageTimestamp > 0) {
                // Small delay to ensure layout is complete
                kotlinx.coroutines.delay(50)
                // Scroll to bottom
                chatScrollState.animateScrollTo(chatScrollState.maxValue)
            }
        }
        
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.7f),
            color = Color(0xFF1A1A2E),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(12.dp)
                    .fillMaxSize()
                    .verticalScroll(chatScrollState)
            ) {
                // Chat messages - combined bubble style
                // chatHistory is already sorted, no need to sort again
                chatHistory.forEach { msg ->
                    val isMe = msg.speaker == "나"
                    val langEmoji = languageNames[msg.speakerLanguage] ?: msg.speakerLanguage
                    val isArabic = msg.speakerLanguage == TranslationService.LANG_ARABIC || msg.speakerLanguage == "ar"
                    
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
                    ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
                    ) {
                        Surface(
                            color = if (isMe) Color(0xFF2196F3) else Color(0xFF3A3A4A),
                            shape = RoundedCornerShape(
                                topStart = 12.dp,
                                topEnd = 12.dp,
                                bottomStart = if (isMe) 12.dp else 4.dp,
                                bottomEnd = if (isMe) 4.dp else 12.dp
                            ),
                            modifier = Modifier.widthIn(max = 280.dp)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                // Speaker name with language
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (isMe) "나" else msg.speaker,
                                        color = Color.Yellow,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        " • $langEmoji",
                                        color = Color.Yellow.copy(alpha = 0.7f),
                                        fontSize = 9.sp
                                    )
                                        // Show streaming indicator if message is not complete
                                        if (!msg.isComplete) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                "⏳",
                                                color = Color.Cyan.copy(alpha = 0.7f),
                                            fontSize = 9.sp
                                        )
                                        }
                                        // Show status indicators
                                        Spacer(modifier = Modifier.width(4.dp))
                                        if (isMe) {
                                            // My message: show sent status
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    if (msg.isSent) "✓" else "⋯",
                                                    color = if (msg.isSent) Color.Green else Color.Gray.copy(alpha = 0.5f),
                                                    fontSize = 9.sp
                                                )
                                            }
                                        } else {
                                            // Peer message: show TTS played status
                                            Text(
                                                if (msg.isTTSPlayed) "🔊✓" else "🔊⋯",
                                                color = if (msg.isTTSPlayed) Color.Green else Color.Gray.copy(alpha = 0.5f),
                                                fontSize = 9.sp
                                            )
                                        }
                                    }
                                    
                                Spacer(modifier = Modifier.height(4.dp))
                                    // Original text - right align for Arabic
                                    Text(
                                        msg.original, 
                                        color = Color.White, 
                                        fontSize = 13.sp,
                                        textAlign = if (isArabic) TextAlign.End else TextAlign.Start
                                    )
                                    // Translation (only show for OTHER users' messages, not mine)
                                    if (!isMe && msg.translated.isNotBlank() && msg.original != msg.translated) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            // Check if translation is Arabic (for RTL)
                                            val isTranslationArabic = myListeningLanguage == TranslationService.LANG_ARABIC || myListeningLanguage == "ar"
                                    Text(
                                        "→ ${msg.translated}",
                                        color = Color.Cyan,
                                        fontSize = 12.sp,
                                                fontStyle = FontStyle.Italic,
                                                textAlign = if (isTranslationArabic) TextAlign.End else TextAlign.Start
                                            )
                                            // Show typing indicator if translation is streaming
                                            if (!msg.isComplete) {
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    "⋯",
                                                    color = Color.Cyan.copy(alpha = 0.5f),
                                                    fontSize = 10.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        
                        // Show TTS status per user below the bubble (for my messages only)
                        if (isMe) {
                            // Filter to only show users that are actually connected
                            val connectedUserIds = connectedUsers.map { it.userId }.toSet()
                            val filteredPlayingByOthers = msg.ttsPlayingByOthers.filter { connectedUserIds.contains(it) }.toSet()
                            val filteredPlayedByOthers = msg.ttsPlayedByOthers.filter { connectedUserIds.contains(it) }.toSet()
                            val allUsers = (filteredPlayingByOthers + filteredPlayedByOthers).distinct()
                            val playingCount = filteredPlayingByOthers.size
                            val playedCount = filteredPlayedByOthers.size
                            
                            if (allUsers.isNotEmpty() || connectedUsers.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    if (allUsers.isNotEmpty()) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            allUsers.forEach { userId ->
                                                val userName = connectedUsers.find { it.userId == userId }?.name ?: userId.take(8)
                                                val isPlaying = filteredPlayingByOthers.contains(userId)
                                                val isPlayed = filteredPlayedByOthers.contains(userId)
                                                
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                                ) {
                                                    Text(
                                                        "$userName:",
                                                        color = Color.Gray.copy(alpha = 0.7f),
                                                        fontSize = 8.sp
                                                    )
                                                    Text(
                                                        when {
                                                            isPlaying -> "Playing"
                                                            isPlayed -> "Played"
                                                            else -> "⋯"
                                                        },
                                                        color = when {
                                                            isPlaying -> Color.Cyan
                                                            isPlayed -> Color.Green
                                                            else -> Color.Gray.copy(alpha = 0.5f)
                                                        },
                                                        fontSize = 8.sp
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    
                                    // Always show counts
                                    if (playingCount > 0 || playedCount > 0) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (playingCount > 0) {
                                                Text(
                                                    "Playing($playingCount)",
                                                    color = Color.Cyan,
                                                    fontSize = 8.sp
                                                )
                                            }
                                            if (playedCount > 0) {
                                                Text(
                                                    "Played($playedCount)",
                                                    color = Color.Green,
                                                    fontSize = 8.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                // Current input (in progress)
                if (currentOriginal.isNotBlank() && 
                    (chatHistory.isEmpty() || chatHistory.last().original != currentOriginal)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Surface(
                            color = Color(0xFF2196F3).copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                // Check if current input is Arabic (for RTL)
                                val isCurrentArabic = unifiedLanguage == TranslationService.LANG_ARABIC || unifiedLanguage == "ar"
                                Text(
                                    "💭 $currentOriginal", 
                                    color = Color.White.copy(alpha = 0.7f), 
                                    fontSize = 12.sp,
                                    textAlign = if (isCurrentArabic) TextAlign.End else TextAlign.Start
                                )
                                // Don't show translation for my own messages
                            }
                        }
                    }
                }
                
                if (chatHistory.isEmpty() && currentOriginal.isBlank()) {
                    Text("말을 시작하세요...", color = Color.Gray)
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
                        userState = "LISTENING"  // Changed to LISTENING to match auto-listening mode
                        isSpeaking = true
                        
                        // Start Soniox STT
                            googleSTT.clearTranscription()
                        if (unifiedLanguage == "auto") {
                            // Auto mode: Enable language detection
                            Log.d("MajlisRoom", "🎤 Starting Soniox STT with AUTO language detection...")
                            googleSTT.startListening("auto")
                        } else {
                            // Specific language: Use selected language
                            val googleLangCode = when (unifiedLanguage) {
                                TranslationService.LANG_KOREAN -> "ko"
                                TranslationService.LANG_ENGLISH -> "en"
                                TranslationService.LANG_ARABIC -> "ar"
                                TranslationService.LANG_SPANISH -> "es"
                                else -> "en"
                            }
                            Log.d("MajlisRoom", "🎤 Starting Soniox STT with specific language: $googleLangCode")
                            googleSTT.startListening(googleLangCode)
                        }
                        
                        // Start audio capture (will be controlled by isSpeaking state)
                        val capture = BluetoothScoAudioCapture(context)
                        capture.setListener(object : BluetoothScoAudioCapture.AudioCaptureListener {
                            override fun onAudioData(data: ByteArray, size: Int) {
                                // CRITICAL: Check isSpeaking state before processing
                                if (!isSpeaking || userState != "LISTENING") {
                                    // Reset VAD state when disabled
                                    vadGateOpen = false
                                    vadSpeechFrameCount = 0
                                    vadNonSpeechFrameCount = 0
                                    vadFrameBuffer = ByteArray(0)
                                    return  // Mic disabled - ignore audio
                                }
                                
                                // Accumulate audio to form 20ms frames (320 bytes at 8kHz, 16-bit mono)
                                val newBuffer = ByteArray(vadFrameBuffer.size + size)
                                System.arraycopy(vadFrameBuffer, 0, newBuffer, 0, vadFrameBuffer.size)
                                System.arraycopy(data, 0, newBuffer, vadFrameBuffer.size, size)
                                vadFrameBuffer = newBuffer
                                
                                // Process complete 20ms frames
                                while (vadFrameBuffer.size >= FRAME_SIZE_20MS) {
                                    val frame = ByteArray(FRAME_SIZE_20MS)
                                    System.arraycopy(vadFrameBuffer, 0, frame, 0, FRAME_SIZE_20MS)
                                    
                                    // Calculate RMS for this frame
                                    var sum = 0L
                                    for (i in 0 until FRAME_SIZE_20MS step 2) {
                                        if (i + 1 < FRAME_SIZE_20MS) {
                                            val sample = (frame[i].toInt() and 0xFF) or (frame[i + 1].toInt() shl 8)
                                            val signedSample = if (sample > 32767) sample - 65536 else sample
                                            sum += signedSample.toLong() * signedSample
                                        }
                                    }
                                    val rms = kotlin.math.sqrt(sum.toDouble() / (FRAME_SIZE_20MS / 2)).toInt()
                                    
                                    // Update volume level for UI
                                    volumeLevel = (rms / 100).coerceIn(0, 100)
                                    
                                    // Calibrate background noise level (first 2 seconds = 100 frames)
                                    if (noiseCalibrationSamples < 100) {
                                        backgroundNoiseLevel = (backgroundNoiseLevel * noiseCalibrationSamples + rms) / (noiseCalibrationSamples + 1)
                                        noiseCalibrationSamples++
                                        // Adaptive threshold: 1.5x background noise
                                        if (noiseCalibrationSamples >= 50) {
                                            vadRmsThreshold = (backgroundNoiseLevel * 1.5).toInt().coerceIn(10, 30)
                                            nearFieldRmsThreshold = (backgroundNoiseLevel * 2.0).toInt().coerceIn(15, 40)
                                        }
                                    }
                                    
                                    // VAD: Check if frame contains speech
                                    val isSpeech = rms >= vadRmsThreshold
                                    val isNearField = rms >= nearFieldRmsThreshold
                                    
                                    // Update frame counters
                                    if (isSpeech && isNearField) {
                                        vadSpeechFrameCount++
                                        vadNonSpeechFrameCount = 0
                                    } else {
                                        vadNonSpeechFrameCount++
                                        vadSpeechFrameCount = 0
                                    }
                                    
                                    // Gate open condition: 3 consecutive speech frames (60ms)
                                    if (!vadGateOpen && vadSpeechFrameCount >= 3) {
                                        vadGateOpen = true
                                        Log.d("Majlis", "🎤 VAD Gate OPEN (RMS: $rms, threshold: $vadRmsThreshold)")
                                    }
                                    
                                    // Gate close condition: 10 consecutive non-speech frames (200ms)
                                    if (vadGateOpen && vadNonSpeechFrameCount >= 10) {
                                        vadGateOpen = false
                                        vadSpeechFrameCount = 0
                                        Log.d("Majlis", "🔇 VAD Gate CLOSE (RMS: $rms)")
                                    }
                                    
                                    // Only send audio to Soniox when gate is open
                                    if (vadGateOpen && googleSTT.isConnected()) {
                                        googleSTT.sendAudio(frame)
                                    }
                                    
                                    // Remove processed frame from buffer
                                    val remainingSize = vadFrameBuffer.size - FRAME_SIZE_20MS
                                    val remainingBuffer = ByteArray(remainingSize)
                                    System.arraycopy(vadFrameBuffer, FRAME_SIZE_20MS, remainingBuffer, 0, remainingSize)
                                    vadFrameBuffer = remainingBuffer
                                }
                            }
                            override fun onScoConnected() {
                                // CRITICAL: Only start recording if speaking is enabled
                                if (isSpeaking && userState == "LISTENING") {
                                    Log.d("Majlis", "🎧 Mic connected and enabled")
                                capture.startRecording(useHandsfree = true)
                                } else {
                                    Log.d("Majlis", "⏸️ Mic disabled - not starting recording")
                                }
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
                        // Stop speaking - CRITICAL: Stop audio capture immediately
                        userState = "LISTENING"
                        isSpeaking = false
                        volumeLevel = 0
                        // Reset VAD state
                        vadGateOpen = false
                        vadSpeechFrameCount = 0
                        vadNonSpeechFrameCount = 0
                        vadFrameBuffer = ByteArray(0)
                        noiseCalibrationSamples = 0
                        backgroundNoiseLevel = 0
                        googleSTT.stopListening()
                        audioCapture?.stop()
                        audioCapture = null
                        Log.d("MajlisRoom", "🛑 Mic disabled - stopped all audio capture and STT")
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
