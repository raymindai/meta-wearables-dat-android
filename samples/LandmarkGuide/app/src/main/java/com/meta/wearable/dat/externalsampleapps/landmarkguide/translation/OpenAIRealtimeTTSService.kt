/*
 * OpenAI Realtime Translation + TTS Service
 * Takes text input, translates, and outputs speech via WebSocket
 * Uses gpt-4o-realtime-preview for fast translation + natural TTS
 */

package com.meta.wearable.dat.externalsampleapps.landmarkguide.translation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import com.meta.wearable.dat.externalsampleapps.landmarkguide.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OpenAI Realtime for Translation + TTS only
 * Input: Text (from Deepgram STT)
 * Output: Translated speech (via WebSocket streaming)
 */
class OpenAIRealtimeTTSService(private val context: Context) {
    
    companion object {
        private const val TAG = "OpenAIRealtimeTTS"
        private const val REALTIME_URL = "wss://api.openai.com/v1/realtime"
        private const val MODEL = "gpt-4o-realtime-preview-2024-12-17"
        
        val LANGUAGE_NAMES = mapOf(
            "ko" to "Korean",
            "en" to "English",
            "ar" to "Arabic",
            "es" to "Spanish"
        )
    }
    
    private val apiKey = BuildConfig.OPENAI_API_KEY
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MINUTES)
        .writeTimeout(0, TimeUnit.MINUTES)
        .build()
    
    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var audioTrack: AudioTrack? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    
    // State
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()
    
    private val _translatedText = MutableStateFlow("")
    val translatedText: StateFlow<String> = _translatedText.asStateFlow()
    
    // Callbacks
    var onTranslation: ((String) -> Unit)? = null
    var onSpeechStart: (() -> Unit)? = null
    var onSpeechEnd: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    
    private var sourceLanguage = "Korean"
    private var targetLanguage = "English"
    
    /**
     * Connect to OpenAI Realtime API
     */
    fun connect(sourceLang: String, targetLang: String) {
        if (apiKey.isBlank()) {
            Log.e(TAG, "❌ OpenAI API Key not configured")
            onError?.invoke("OpenAI API Key not configured")
            return
        }
        
        if (isConnected) {
            Log.w(TAG, "⚠️ Already connected")
            return
        }
        
        sourceLanguage = LANGUAGE_NAMES[sourceLang] ?: "Korean"
        targetLanguage = LANGUAGE_NAMES[targetLang] ?: "English"
        
        val url = "$REALTIME_URL?model=$MODEL"
        
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("OpenAI-Beta", "realtime=v1")
            .build()
        
        Log.d(TAG, "🚀 Connecting for $sourceLanguage → $targetLanguage...")
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "✅ WebSocket connected")
                isConnected = true
                configureSession()
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "❌ WebSocket error: ${t.message}")
                isConnected = false
                _isReady.value = false
                onError?.invoke(t.message ?: "Connection failed")
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason")
                isConnected = false
                _isReady.value = false
            }
        })
        
        setupAudioPlayback()
    }
    
    private fun configureSession() {
        val sessionConfig = JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                put("modalities", JSONArray().apply {
                    put("text")
                    put("audio")
                })
                put("instructions", """
                    You are a translator. I will send you text in $sourceLanguage.
                    Translate it to $targetLanguage and respond with ONLY the translation.
                    Keep responses short and natural for speech.
                    Do not add any explanations or extra text.
                """.trimIndent())
                put("voice", "alloy")
                put("output_audio_format", "pcm16")
                put("turn_detection", null)  // Manual mode - we send text, not audio
            })
        }
        
        webSocket?.send(sessionConfig.toString())
        Log.d(TAG, "📤 Session configured")
    }
    
    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "")
            
            when (type) {
                "session.created" -> {
                    Log.d(TAG, "✅ Session created")
                }
                "session.updated" -> {
                    Log.d(TAG, "✅ Session ready")
                    _isReady.value = true
                }
                "response.audio_transcript.delta" -> {
                    val delta = json.optString("delta", "")
                    _translatedText.value += delta
                }
                "response.audio_transcript.done" -> {
                    val transcript = json.optString("transcript", "")
                    Log.d(TAG, "🌐 Translation: $transcript")
                    _translatedText.value = transcript
                    onTranslation?.invoke(transcript)
                }
                "response.audio.delta" -> {
                    val audioBase64 = json.optString("delta", "")
                    if (audioBase64.isNotBlank()) {
                        val audioBytes = Base64.decode(audioBase64, Base64.DEFAULT)
                        playAudioChunk(audioBytes)
                    }
                }
                "response.audio.done" -> {
                    Log.d(TAG, "🔊 Audio complete")
                    onSpeechEnd?.invoke()
                }
                "response.created" -> {
                    onSpeechStart?.invoke()
                }
                "error" -> {
                    val error = json.optJSONObject("error")
                    val message = error?.optString("message", "Unknown error") ?: "Unknown error"
                    Log.e(TAG, "❌ Error: $message")
                    onError?.invoke(message)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: ${e.message}")
        }
    }
    
    /**
     * Translate text and speak (main function)
     * Call this with text from Deepgram STT
     */
    fun translateAndSpeak(text: String) {
        if (!isConnected || webSocket == null) {
            Log.e(TAG, "❌ Not connected")
            return
        }
        
        _translatedText.value = ""
        
        // Create conversation item with text
        val conversationItem = JSONObject().apply {
            put("type", "conversation.item.create")
            put("item", JSONObject().apply {
                put("type", "message")
                put("role", "user")
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "input_text")
                        put("text", text)
                    })
                })
            })
        }
        
        webSocket?.send(conversationItem.toString())
        
        // Request response
        val responseRequest = JSONObject().apply {
            put("type", "response.create")
        }
        
        webSocket?.send(responseRequest.toString())
        
        Log.d(TAG, "📤 Sent for translation: $text")
    }
    
    private fun setupAudioPlayback() {
        try {
            val sampleRate = 24000
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            
            if (bufferSize <= 0) {
                Log.e(TAG, "❌ Invalid buffer size: $bufferSize")
                return
            }
            
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            
            audioTrack?.play()
            Log.d(TAG, "✅ AudioTrack setup complete")
        } catch (e: Exception) {
            Log.e(TAG, "❌ AudioTrack setup failed: ${e.message}", e)
        }
    }
    
    private fun playAudioChunk(audioData: ByteArray) {
        try {
            val track = audioTrack
            if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                scope.launch {
                    try {
                        track.write(audioData, 0, audioData.size)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ AudioTrack write failed: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ playAudioChunk error: ${e.message}")
        }
    }
    
    fun disconnect() {
        Log.d(TAG, "🛑 Disconnecting...")
        
        try {
            webSocket?.close(1000, "Done")
            webSocket = null
            isConnected = false
            _isReady.value = false
            
            audioTrack?.let { track ->
                try {
                    if (track.state == AudioTrack.STATE_INITIALIZED) {
                        track.stop()
                    }
                    track.release()
                } catch (e: Exception) {
                    Log.e(TAG, "AudioTrack cleanup error: ${e.message}")
                }
            }
            audioTrack = null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Disconnect error: ${e.message}")
        }
    }
    
    fun isConnected(): Boolean = isConnected
}
