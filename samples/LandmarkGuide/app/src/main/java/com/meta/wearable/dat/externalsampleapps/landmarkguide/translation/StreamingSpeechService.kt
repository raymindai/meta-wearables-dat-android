/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.landmarkguide.translation

import android.util.Log
import com.meta.wearable.dat.externalsampleapps.landmarkguide.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Deepgram Streaming Speech-to-Text Service
 * Provides real-time transcription with ~200ms latency
 */
class StreamingSpeechService {
    
    companion object {
        private const val TAG = "StreamingSTT"
        private const val DEEPGRAM_WS_URL = "wss://api.deepgram.com/v1/listen"
    }
    
    private val apiKey = BuildConfig.DEEPGRAM_API_KEY
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MINUTES)  // No timeout for WebSocket
        .writeTimeout(0, TimeUnit.MINUTES)
        .build()
    
    private var webSocket: WebSocket? = null
    private var isConnected = false
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    
    // State
    private val _transcription = MutableStateFlow("")
    val transcription: StateFlow<String> = _transcription.asStateFlow()
    
    private val _partialTranscription = MutableStateFlow("")
    val partialTranscription: StateFlow<String> = _partialTranscription.asStateFlow()
    
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()
    
    // Callback for real-time results
    var onTranscript: ((String, Boolean) -> Unit)? = null  // text, isFinal
    var onError: ((String) -> Unit)? = null
    
    /**
     * Start streaming connection
     * @param languageCode Language code (e.g., "ko", "en", "ar", "es")
     */
    fun startListening(languageCode: String = "en") {
        if (apiKey.isBlank()) {
            Log.e(TAG, "❌ Deepgram API Key not configured")
            onError?.invoke("Deepgram API Key not configured")
            return
        }
        
        if (isConnected) {
            Log.w(TAG, "⚠️ Already connected")
            return
        }
        
        // Map language codes to Deepgram format
        // Whisper uses simple codes (en, ko, ar, es)
        // Nova-2 uses regional codes (en-US, ko, ar, es)
        val dgLang = when (languageCode) {
            "ko" -> "ko"
            "ar" -> "ar"  // Arabic
            "es" -> "es"  // Spanish
            "en" -> "en"
            "auto" -> "en"  // Default to English for auto
            else -> "en"
        }
        
        // Nova-2 for real-time streaming (fastest)
        // Whisper doesn't support real-time streaming!
        val model = "nova-2"
        
        // Build WebSocket URL with STABLE parameters only
        val url = "$DEEPGRAM_WS_URL?" +
                "language=$dgLang" +
                "&model=$model" +
                "&punctuate=true" +
                "&interim_results=true" +
                "&encoding=linear16" +
                "&sample_rate=16000" +
                "&channels=1"
        
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Token $apiKey")
            .build()
        
        Log.d(TAG, "🎤 Connecting to Deepgram ($dgLang)...")
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "✅ WebSocket connected")
                isConnected = true
                _isListening.value = true
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    
                    if (json.has("channel")) {
                        val channel = json.getJSONObject("channel")
                        val alternatives = channel.getJSONArray("alternatives")
                        
                        if (alternatives.length() > 0) {
                            val alt = alternatives.getJSONObject(0)
                            val transcript = alt.getString("transcript")
                            val isFinal = json.optBoolean("is_final", false)
                            
                            if (transcript.isNotBlank()) {
                                if (isFinal) {
                                    // Final result - append to full transcription
                                    val current = _transcription.value
                                    _transcription.value = if (current.isBlank()) transcript else "$current $transcript"
                                    _partialTranscription.value = ""
                                    Log.d(TAG, "📝 Final: $transcript")
                                } else {
                                    // Interim result
                                    _partialTranscription.value = transcript
                                    Log.d(TAG, "💭 Partial: $transcript")
                                }
                                
                                onTranscript?.invoke(transcript, isFinal)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing response: ${e.message}")
                }
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "❌ WebSocket error: ${t.message}")
                isConnected = false
                _isListening.value = false
                onError?.invoke(t.message ?: "Connection failed")
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason")
                isConnected = false
                _isListening.value = false
            }
        })
    }
    
    /**
     * Send audio data to Deepgram
     * @param audioData PCM 16-bit mono audio data
     */
    fun sendAudio(audioData: ByteArray) {
        if (!isConnected || webSocket == null) {
            return
        }
        
        webSocket?.send(audioData.toByteString())
    }
    
    /**
     * Stop listening and close connection
     */
    fun stopListening() {
        Log.d(TAG, "🛑 Stopping...")
        
        // Send close message
        webSocket?.send("{\"type\": \"CloseStream\"}")
        webSocket?.close(1000, "Done")
        webSocket = null
        isConnected = false
        _isListening.value = false
    }
    
    /**
     * Clear transcription
     */
    fun clearTranscription() {
        _transcription.value = ""
        _partialTranscription.value = ""
    }
    
    /**
     * Check if connected
     */
    fun isConnected(): Boolean = isConnected
}
