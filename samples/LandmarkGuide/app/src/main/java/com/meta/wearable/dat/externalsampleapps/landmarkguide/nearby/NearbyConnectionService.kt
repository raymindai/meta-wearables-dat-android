/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * NearbyConnectionService - P2P communication for Multi-user Majlis
 * Uses Google Nearby Connections API for real-time translation sharing.
 */

package com.meta.wearable.dat.externalsampleapps.landmarkguide.nearby

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import org.json.JSONObject
import java.util.UUID

/**
 * Manages Nearby Connections for multi-user Majlis.
 * 
 * Two modes:
 * - HOST: Advertises a room and accepts connections
 * - JOIN: Discovers rooms and connects to a host
 */
class NearbyConnectionService(
    private val context: Context,
    private val onPeerConnected: (PeerInfo) -> Unit,
    private val onPeerDisconnected: (String) -> Unit,
    private val onMessageReceived: (TranslationMessage) -> Unit,
    private val onStatusChange: (String) -> Unit
) {
    companion object {
        private const val TAG = "NearbyConnection"
        private const val SERVICE_ID = "com.meta.wearable.majlis"
        private val STRATEGY = Strategy.P2P_STAR  // 1 host, many clients
    }

    private val connectionsClient: ConnectionsClient by lazy {
        Nearby.getConnectionsClient(context)
    }

    private val localEndpointId = UUID.randomUUID().toString().take(8)
    private var localUserName = "User"
    private var localLanguage = "en"
    private var isHost = false
    
    private val connectedPeers = mutableMapOf<String, PeerInfo>()

    // =============================================
    // HOST MODE
    // =============================================
    
    /**
     * Start hosting a Majlis room. Other devices can discover and join.
     */
    fun startHosting(userName: String, languageCode: String) {
        localUserName = userName
        localLanguage = languageCode
        isHost = true
        
        Log.d(TAG, "🏠 Starting to host room: $userName ($languageCode)")
        onStatusChange("Starting room...")

        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(STRATEGY)
            .build()

        connectionsClient.startAdvertising(
            userName,  // Visible name
            SERVICE_ID,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener {
            Log.d(TAG, "✅ Advertising started successfully")
            onStatusChange("🏠 Hosting: $userName")
        }.addOnFailureListener { e ->
            Log.e(TAG, "❌ Failed to start advertising: ${e.message}", e)
            onStatusChange("Failed to start room")
        }
    }

    /**
     * Stop hosting the room.
     */
    fun stopHosting() {
        connectionsClient.stopAdvertising()
        disconnectAll()
        isHost = false
        Log.d(TAG, "🛑 Stopped hosting")
        onStatusChange("Room closed")
    }

    // =============================================
    // JOIN MODE
    // =============================================

    /**
     * Start discovering nearby Majlis rooms.
     */
    fun startDiscovering(userName: String, languageCode: String, onRoomFound: (String, String) -> Unit) {
        localUserName = userName
        localLanguage = languageCode
        isHost = false
        
        Log.d(TAG, "🔍 Starting to discover rooms...")
        onStatusChange("Searching for rooms...")

        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(STRATEGY)
            .build()

        connectionsClient.startDiscovery(
            SERVICE_ID,
            object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                    Log.d(TAG, "📍 Found room: ${info.endpointName} ($endpointId)")
                    onRoomFound(endpointId, info.endpointName)
                }

                override fun onEndpointLost(endpointId: String) {
                    Log.d(TAG, "📍 Room lost: $endpointId")
                }
            },
            discoveryOptions
        ).addOnSuccessListener {
            Log.d(TAG, "✅ Discovery started")
            onStatusChange("🔍 Searching...")
        }.addOnFailureListener { e ->
            Log.e(TAG, "❌ Discovery failed: ${e.message}", e)
            onStatusChange("Search failed")
        }
    }

    /**
     * Stop discovering rooms.
     */
    fun stopDiscovering() {
        connectionsClient.stopDiscovery()
        Log.d(TAG, "🛑 Stopped discovery")
    }

    /**
     * Request to join a discovered room.
     */
    fun joinRoom(endpointId: String) {
        Log.d(TAG, "🚪 Requesting to join room: $endpointId")
        onStatusChange("Connecting...")

        connectionsClient.requestConnection(
            localUserName,
            endpointId,
            connectionLifecycleCallback
        ).addOnSuccessListener {
            Log.d(TAG, "✅ Connection requested")
        }.addOnFailureListener { e ->
            Log.e(TAG, "❌ Connection request failed: ${e.message}", e)
            onStatusChange("Connection failed")
        }
    }

    // =============================================
    // MESSAGING
    // =============================================

    /**
     * Send a translation message to all connected peers.
     */
    fun broadcastMessage(originalText: String, translatedTexts: Map<String, String>) {
        if (connectedPeers.isEmpty()) {
            Log.d(TAG, "No peers connected, skipping broadcast")
            return
        }

        val message = TranslationMessage(
            senderId = localEndpointId,
            senderName = localUserName,
            senderLanguage = localLanguage,
            originalText = originalText,
            translatedTexts = translatedTexts,
            timestamp = System.currentTimeMillis()
        )

        val json = message.toJson()
        val payload = Payload.fromBytes(json.toByteArray())

        connectedPeers.keys.forEach { endpointId ->
            connectionsClient.sendPayload(endpointId, payload)
                .addOnSuccessListener {
                    Log.d(TAG, "📤 Sent to $endpointId: ${originalText.take(30)}...")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "❌ Failed to send to $endpointId: ${e.message}")
                }
        }
    }

    // =============================================
    // CONNECTION LIFECYCLE
    // =============================================

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.d(TAG, "🤝 Connection initiated with ${info.endpointName}")
            
            // Auto-accept connections (in production, show UI confirmation)
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnSuccessListener {
                    Log.d(TAG, "✅ Connection accepted")
                }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Log.d(TAG, "✅ Connected to $endpointId")
                    stopDiscovering()  // Stop searching once connected
                    
                    val peerInfo = PeerInfo(
                        endpointId = endpointId,
                        name = "Peer",  // Will be updated when they send first message
                        language = "unknown"
                    )
                    connectedPeers[endpointId] = peerInfo
                    onPeerConnected(peerInfo)
                    onStatusChange("✅ Connected (${connectedPeers.size} peers)")
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    Log.d(TAG, "❌ Connection rejected")
                    onStatusChange("Connection rejected")
                }
                else -> {
                    Log.e(TAG, "❌ Connection failed: ${result.status}")
                    onStatusChange("Connection failed")
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d(TAG, "👋 Disconnected from $endpointId")
            connectedPeers.remove(endpointId)
            onPeerDisconnected(endpointId)
            onStatusChange("Peer disconnected (${connectedPeers.size} remaining)")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let { bytes ->
                try {
                    val json = String(bytes)
                    val message = TranslationMessage.fromJson(json)
                    
                    // Update peer info
                    connectedPeers[endpointId]?.let {
                        connectedPeers[endpointId] = it.copy(
                            name = message.senderName,
                            language = message.senderLanguage
                        )
                    }
                    
                    Log.d(TAG, "📥 Received from ${message.senderName}: ${message.originalText.take(30)}...")
                    onMessageReceived(message)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse message: ${e.message}")
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Not needed for small byte payloads
        }
    }

    // =============================================
    // CLEANUP
    // =============================================

    /**
     * Disconnect from all peers and stop all activities.
     */
    fun disconnectAll() {
        connectedPeers.keys.forEach { endpointId ->
            connectionsClient.disconnectFromEndpoint(endpointId)
        }
        connectedPeers.clear()
        connectionsClient.stopAllEndpoints()
        Log.d(TAG, "🔌 Disconnected from all peers")
    }

    fun getConnectedPeers(): List<PeerInfo> = connectedPeers.values.toList()
    fun isConnected(): Boolean = connectedPeers.isNotEmpty()
}

/**
 * Information about a connected peer.
 */
data class PeerInfo(
    val endpointId: String,
    val name: String,
    val language: String
)

/**
 * Translation message sent between peers.
 */
data class TranslationMessage(
    val senderId: String,
    val senderName: String,
    val senderLanguage: String,
    val originalText: String,
    val translatedTexts: Map<String, String>,
    val timestamp: Long
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("senderId", senderId)
        json.put("senderName", senderName)
        json.put("senderLanguage", senderLanguage)
        json.put("originalText", originalText)
        json.put("timestamp", timestamp)
        
        val translations = JSONObject()
        translatedTexts.forEach { (lang, text) ->
            translations.put(lang, text)
        }
        json.put("translatedTexts", translations)
        
        return json.toString()
    }

    companion object {
        fun fromJson(jsonString: String): TranslationMessage {
            val json = JSONObject(jsonString)
            val translationsJson = json.getJSONObject("translatedTexts")
            val translations = mutableMapOf<String, String>()
            translationsJson.keys().forEach { key ->
                translations[key] = translationsJson.getString(key)
            }
            
            return TranslationMessage(
                senderId = json.getString("senderId"),
                senderName = json.getString("senderName"),
                senderLanguage = json.getString("senderLanguage"),
                originalText = json.getString("originalText"),
                translatedTexts = translations,
                timestamp = json.getLong("timestamp")
            )
        }
    }
}
