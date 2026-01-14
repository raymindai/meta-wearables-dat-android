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
    private val myUserId = UUID.randomUUID().toString().take(8)
    private var currentRoomRef: DatabaseReference? = null
    private var usersListener: ChildEventListener? = null
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
        
        // Listen for users
        listenForUsers()
        
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
        usersListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                if (snapshot.key == myUserId) return // Skip myself
                
                val user = parseUser(snapshot)
                if (user != null) {
                    Log.d(TAG, "👤 User joined: ${user.name}")
                    onUserJoined(user)
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

    private fun listenForMessages() {
        // Only listen for new messages (not historical)
        val messagesRef = currentRoomRef?.child("messages")
        
        messagesListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val message = parseMessage(snapshot)
                if (message != null && message.senderId != myUserId) {
                    Log.d(TAG, "📨 Message from ${message.senderName}: ${message.originalText.take(30)}")
                    onMessageReceived(message)
                }
            }
            
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Messages listener error: ${error.message}")
            }
        }
        
        // Only get messages after joining (limit to last 1 to avoid old messages)
        messagesRef?.orderByChild("timestamp")?.limitToLast(1)?.addChildEventListener(messagesListener!!)
    }

    /**
     * Send a message to the room.
     */
    fun sendMessage(originalText: String, translatedTexts: Map<String, String>) {
        val messageRef = currentRoomRef?.child("messages")?.push()
        messageRef?.setValue(mapOf(
            "senderId" to myUserId,
            "senderName" to myName,
            "senderLanguage" to myLanguage,
            "originalText" to originalText,
            "translatedTexts" to translatedTexts,
            "timestamp" to ServerValue.TIMESTAMP
        ))
        Log.d(TAG, "📤 Sent: ${originalText.take(30)}")
    }

    /**
     * Leave the current room.
     */
    fun leaveRoom() {
        Log.d(TAG, "🚪 Leaving room")
        
        usersListener?.let { currentRoomRef?.child("users")?.removeEventListener(it) }
        messagesListener?.let { currentRoomRef?.child("messages")?.removeEventListener(it) }
        myUserRef?.removeValue()
        
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
            
            RoomMessage(
                messageId = snapshot.key ?: return null,
                senderId = snapshot.child("senderId").getValue(String::class.java) ?: return null,
                senderName = snapshot.child("senderName").getValue(String::class.java) ?: "Unknown",
                senderLanguage = snapshot.child("senderLanguage").getValue(String::class.java) ?: "en",
                originalText = snapshot.child("originalText").getValue(String::class.java) ?: "",
                translatedTexts = translatedTexts,
                timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: 0L
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
    val timestamp: Long
)
