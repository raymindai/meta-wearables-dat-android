/*
 * Firebase Realtime Database service for instant multi-user Majlis connection.
 * Replaces slow Nearby Connections discovery with instant server-based sync.
 */

package com.meta.wearable.dat.externalsampleapps.landmarkguide.firebase

import android.util.Log
import com.google.firebase.database.*
import org.json.JSONObject
import java.util.UUID

/**
 * FirebaseRoomService - Instant real-time multi-user connection.
 * 
 * Structure in Firebase:
 * /rooms/{roomId}/
 *   /users/{oderId}/
 *     - name: String
 *     - language: String
 *     - lastSeen: Long
 *   /messages/{messageId}/
 *     - senderId: String
 *     - senderName: String
 *     - originalText: String
 *     - translatedTexts: { "en": "...", "ko": "..." }
 *     - timestamp: Long
 */
class FirebaseRoomService(
    private val onUserJoined: (RoomUser) -> Unit,
    private val onUserLeft: (String) -> Unit,
    private val onMessageReceived: (RoomMessage) -> Unit,
    private val onConnectionStateChanged: (Boolean) -> Unit
) {
    companion object {
        private const val TAG = "FirebaseRoom"
    }

    private val database = FirebaseDatabase.getInstance()
    val myUserId = UUID.randomUUID().toString().take(8)  // Made public to allow senderId comparison
    private var currentRoomRef: DatabaseReference? = null
    private var usersListener: ChildEventListener? = null
    private var usersValueListener: ValueEventListener? = null  // For periodic sync
    private var messagesListener: ChildEventListener? = null
    private var myUserRef: DatabaseReference? = null
    
    var myName = "User"
    var myLanguage = "ko"

    /**
     * Join a room - instant!
     */
    fun joinRoom(roomId: String, userName: String, language: String) {
        myName = userName
        myLanguage = language
        
        Log.d(TAG, "🚀 Joining room: $roomId as $userName ($language)")
        
        // Clean up previous room if exists
        if (currentRoomRef != null) {
            Log.d(TAG, "🧹 Cleaning up previous room connection")
            usersListener?.let { currentRoomRef?.child("users")?.removeEventListener(it) }
            usersValueListener?.let { currentRoomRef?.child("users")?.removeEventListener(it) }
            messagesListener?.let { currentRoomRef?.child("messages")?.removeEventListener(it) }
            usersListener = null
            usersValueListener = null
            messagesListener = null
        }
        
        currentRoomRef = database.getReference("rooms/$roomId")
        
        // Add myself to users
        myUserRef = currentRoomRef!!.child("users").child(myUserId)
        myUserRef!!.setValue(mapOf(
            "name" to userName,
            "language" to language,
            "lastSeen" to ServerValue.TIMESTAMP
        ))
        
        // Remove myself when disconnected
        myUserRef!!.onDisconnect().removeValue()
        
        // Listen for users (both child events and value events for sync)
        listenForUsers()
        syncUsersFromFirebase()  // Initial sync
        
        // Listen for messages
        listenForMessages()
        
        // Connection state
        database.getReference(".info/connected").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                Log.d(TAG, if (connected) "✅ Connected to Firebase" else "❌ Disconnected")
                onConnectionStateChanged(connected)
            }
            override fun onCancelled(error: DatabaseError) {}
        })
        
        Log.d(TAG, "✅ Joined room instantly!")
    }

    private fun listenForUsers() {
        // Clear any existing listener first
        usersListener?.let { currentRoomRef?.child("users")?.removeEventListener(it) }
        
        usersListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val userId = snapshot.key ?: return
                if (userId == myUserId) {
                    Log.d(TAG, "⏭️ Skipping myself: $userId")
                    return // Skip myself
                }
                
                // Only process if it has valid user data (name and language)
                // This filters out any non-user nodes that might exist under /users/
                if (!snapshot.hasChild("name") || !snapshot.hasChild("language")) {
                    Log.d(TAG, "⚠️ Skipping invalid user node (missing name/language): $userId")
                    return
                }
                
                val user = parseUser(snapshot)
                if (user != null) {
                    Log.d(TAG, "👤 User joined: ${user.name} (ID: $userId)")
                    onUserJoined(user)
                } else {
                    Log.w(TAG, "⚠️ Failed to parse user: $userId")
                }
            }
            
            override fun onChildRemoved(snapshot: DataSnapshot) {
                val userId = snapshot.key ?: return
                if (userId == myUserId) return
                Log.d(TAG, "👋 User left: $userId")
                onUserLeft(userId)
            }
            
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Users listener error: ${error.message}")
            }
        }
        currentRoomRef?.child("users")?.addChildEventListener(usersListener!!)
    }
    
    /**
     * Sync users from Firebase - reads the entire users list and syncs with local state
     * This ensures accuracy even if child events are missed
     */
    private fun syncUsersFromFirebase() {
        currentRoomRef?.child("users")?.get()?.addOnSuccessListener { snapshot ->
            val actualUsers = mutableSetOf<String>()
            val actualUserList = mutableListOf<RoomUser>()
            
            snapshot.children.forEach { child ->
                val userId = child.key ?: return@forEach
                
                // Skip myself
                if (userId == myUserId) {
                    Log.d(TAG, "⏭️ Skipping myself in sync: $userId")
                    return@forEach
                }
                
                // Only process if it has valid user data (name and language)
                // This filters out any non-user nodes that might exist under /users/
                if (!child.hasChild("name") || !child.hasChild("language")) {
                    Log.d(TAG, "⚠️ Skipping invalid user node (missing name/language): $userId")
                    return@forEach
                }
                
                val user = parseUser(child)
                if (user != null) {
                    actualUsers.add(userId)
                    actualUserList.add(user)
                    Log.d(TAG, "🔄 Synced user: ${user.name} (ID: $userId)")
                } else {
                    Log.w(TAG, "⚠️ Failed to parse user: $userId")
                }
            }
            
            Log.d(TAG, "📊 Firebase sync complete: ${actualUsers.size} valid users found (total children: ${snapshot.childrenCount})")
            // Notify about all users (will be deduplicated in UI)
            actualUserList.forEach { user ->
                onUserJoined(user)
            }
        }?.addOnFailureListener { error ->
            Log.e(TAG, "❌ Failed to sync users: ${error.message}")
        }
        
        // Also set up a ValueEventListener for periodic sync
        usersValueListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val actualUsers = mutableSetOf<String>()
                val actualUserList = mutableListOf<RoomUser>()
                
                snapshot.children.forEach { child ->
                    val userId = child.key ?: return@forEach
                    
                    // Skip myself
                    if (userId == myUserId) {
                        return@forEach
                    }
                    
                    // Only process if it has valid user data (name and language)
                    // This filters out any non-user nodes that might exist under /users/
                    if (!child.hasChild("name") || !child.hasChild("language")) {
                        return@forEach
                    }
                    
                    val user = parseUser(child)
                    if (user != null) {
                        actualUsers.add(userId)
                        actualUserList.add(user)
                    }
                }
                
                Log.d(TAG, "🔄 Periodic sync: ${actualUsers.size} valid users found (total children: ${snapshot.childrenCount})")
                // Notify about all users (will be deduplicated in UI)
                actualUserList.forEach { user ->
                    onUserJoined(user)
                }
            }
            
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "❌ Users value listener cancelled: ${error.message}")
            }
        }
        
        // Listen for changes to users node (triggers on any user add/remove)
        currentRoomRef?.child("users")?.addValueEventListener(usersValueListener!!)
    }

    private fun listenForMessages() {
        // Listen for new messages AND updates (for server-side translations)
        val messagesRef = currentRoomRef?.child("messages")
        
        messagesListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val message = parseMessage(snapshot)
                if (message != null && message.senderId != myUserId) {
                    Log.d(TAG, "📨 Message from ${message.senderName}: ${message.originalText.take(30)}")
                    onMessageReceived(message)
                }
            }
            
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                // Listen for TTS playback updates (ttsPlayingBy, ttsPlayedBy fields)
                Log.d(TAG, "🔄 onChildChanged triggered for message: ${snapshot.key}, changed field: $previousChildName")
                val message = parseMessage(snapshot)
                if (message != null) {
                    Log.d(TAG, "📨 Parsed message update: ${message.messageId}, playing: ${message.ttsPlayingBy.size}, played: ${message.ttsPlayedBy.size}")
                    // Notify for both my messages (to update ttsPlayedByOthers) and peer messages
                    onMessageReceived(message)
                } else {
                    Log.w(TAG, "⚠️ Failed to parse message from onChildChanged")
                }
            }
            
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Messages listener error: ${error.message}")
            }
        }
        
        // Listen to all messages (not just last 1) to catch updates
        messagesRef?.orderByChild("timestamp")?.addChildEventListener(messagesListener!!)
    }

    /**
     * Update user language in Firebase
     */
    fun updateLanguage(language: String) {
        myLanguage = language
        myUserRef?.child("language")?.setValue(language)
        Log.d(TAG, "🌐 Language updated to: $language")
    }

    /**
     * Send a message to the room.
     * Only sends original text + speaker language.
     * Each receiver translates to their own preferred language.
     */
    fun sendMessage(originalText: String, speakerLanguage: String = myLanguage) {
        val messageRef = currentRoomRef?.child("messages")?.push()
        messageRef?.setValue(mapOf(
            "senderId" to myUserId,
            "senderName" to myName,
            "senderLanguage" to speakerLanguage,
            "originalText" to originalText,
            "timestamp" to ServerValue.TIMESTAMP
        ))
        Log.d(TAG, "📤 Sent ($speakerLanguage): ${originalText.take(30)}")
    }
    
    /**
     * Mark TTS playback as started for a message.
     * This allows the sender to know that their message is being played.
     */
    fun markTTSPlaying(messageId: String) {
        if (messageId.isBlank()) return
        currentRoomRef?.child("messages")?.child(messageId)?.child("ttsPlayingBy")?.child(myUserId)?.setValue(true)
        Log.d(TAG, "🔊▶️ Marked TTS playing for message: $messageId")
    }
    
    /**
     * Mark TTS playback as completed for a message.
     * This allows the sender to know that their message was heard.
     */
    fun markTTSPlayed(messageId: String) {
        if (messageId.isBlank()) return
        // Remove from playing list and add to played list
        currentRoomRef?.child("messages")?.child(messageId)?.child("ttsPlayingBy")?.child(myUserId)?.removeValue()
        currentRoomRef?.child("messages")?.child(messageId)?.child("ttsPlayedBy")?.child(myUserId)?.setValue(true)
        Log.d(TAG, "🔊✓ Marked TTS played for message: $messageId")
    }
    
    /**
     * Send speaking status - lets others know you're about to speak
     */
    fun sendSpeakingStatus(isSpeaking: Boolean) {
        myUserRef?.child("isSpeaking")?.setValue(isSpeaking)
        if (isSpeaking) {
            Log.d(TAG, "🎤 Broadcasting: speaking started")
        }
    }
    
    /**
     * Send streaming partial text for real-time updates
     */
    fun sendPartialText(partialText: String, speakerLanguage: String = myLanguage) {
        if (partialText.isBlank()) return
        myUserRef?.child("partialText")?.setValue(mapOf(
            "text" to partialText,
            "language" to speakerLanguage,
            "timestamp" to ServerValue.TIMESTAMP
        ))
    }

    /**
     * Leave the current room.
     */
    fun leaveRoom() {
        Log.d(TAG, "🚪 Leaving room")
        
        usersListener?.let { currentRoomRef?.child("users")?.removeEventListener(it) }
        usersValueListener?.let { currentRoomRef?.child("users")?.removeEventListener(it) }
        messagesListener?.let { currentRoomRef?.child("messages")?.removeEventListener(it) }
        myUserRef?.removeValue()
        
        usersListener = null
        usersValueListener = null
        messagesListener = null
        currentRoomRef = null
        myUserRef = null
    }

    /**
     * Get current users count.
     */
    fun getUsersCount(callback: (Int) -> Unit) {
        currentRoomRef?.child("users")?.get()?.addOnSuccessListener { snapshot ->
            callback(snapshot.childrenCount.toInt())
        }
    }

    private fun parseUser(snapshot: DataSnapshot): RoomUser? {
        return try {
            RoomUser(
                userId = snapshot.key ?: return null,
                name = snapshot.child("name").getValue(String::class.java) ?: "Unknown",
                language = snapshot.child("language").getValue(String::class.java) ?: "en"
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseMessage(snapshot: DataSnapshot): RoomMessage? {
        return try {
            val translatedTexts = mutableMapOf<String, String>()
            snapshot.child("translatedTexts").children.forEach { child ->
                translatedTexts[child.key ?: ""] = child.getValue(String::class.java) ?: ""
            }
            
            val ttsPlayingBy = mutableSetOf<String>()
            snapshot.child("ttsPlayingBy").children.forEach { child ->
                if (child.getValue(Boolean::class.java) == true) {
                    ttsPlayingBy.add(child.key ?: "")
                }
            }
            
            val ttsPlayedBy = mutableSetOf<String>()
            snapshot.child("ttsPlayedBy").children.forEach { child ->
                if (child.getValue(Boolean::class.java) == true) {
                    ttsPlayedBy.add(child.key ?: "")
                }
            }
            
            RoomMessage(
                messageId = snapshot.key ?: return null,
                senderId = snapshot.child("senderId").getValue(String::class.java) ?: return null,
                senderName = snapshot.child("senderName").getValue(String::class.java) ?: "Unknown",
                senderLanguage = snapshot.child("senderLanguage").getValue(String::class.java) ?: "en",
                originalText = snapshot.child("originalText").getValue(String::class.java) ?: "",
                translatedTexts = translatedTexts,
                timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: 0L,
                ttsPlayingBy = ttsPlayingBy,
                ttsPlayedBy = ttsPlayedBy
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message: ${e.message}")
            null
        }
    }
}

data class RoomUser(
    val userId: String,
    val name: String,
    val language: String
)

data class RoomMessage(
    val messageId: String,
    val senderId: String,
    val senderName: String,
    val senderLanguage: String,
    val originalText: String,
    val translatedTexts: Map<String, String>,
    val timestamp: Long,
    val ttsPlayingBy: Set<String> = emptySet(),  // User IDs who are currently playing TTS
    val ttsPlayedBy: Set<String> = emptySet()  // User IDs who have completed TTS playback
)
