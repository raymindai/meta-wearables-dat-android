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
    var onTranslationDelta: ((String) -> Unit)? = null  // For streaming translation text
    var onSpeechStart: (() -> Unit)? = null
    var onSpeechEnd: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    
    // Track current translation text for streaming
    private var currentTranslationText = ""
    
    private var sourceLanguage = "Korean"
    private var targetLanguage = "English"
    var currentSourceLangCode = ""  // Track current source language code (public for connection check)
    var currentTargetLangCode = ""  // Track current target language code (public for connection check)
    var ttsEnabled = true  // TTS enabled by default for peer messages
    
    /**
     * Connect to OpenAI Realtime API
     * Reconnects if language pair changed
     */
    fun connect(sourceLang: String, targetLang: String) {
        if (apiKey.isBlank()) {
            Log.e(TAG, "❌ OpenAI API Key not configured")
            onError?.invoke("OpenAI API Key not configured")
            return
        }
        
        // Check if language changed - if so, reconnect
        val sourceLangCode = sourceLang.lowercase()
        val targetLangCode = targetLang.lowercase()
        
        if (isConnected && currentSourceLangCode == sourceLangCode && currentTargetLangCode == targetLangCode) {
            Log.d(TAG, "✅ Already connected with same language pair ($sourceLangCode → $targetLangCode)")
            return
        }
        
        // Language changed or not connected - reconnect
        if (isConnected) {
            Log.d(TAG, "🔄 Language changed from ($currentSourceLangCode → $currentTargetLangCode) to ($sourceLangCode → $targetLangCode), reconnecting...")
            disconnect()
        }
        
        currentSourceLangCode = sourceLangCode
        currentTargetLangCode = targetLangCode
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
                put("tools", JSONArray())  // No tools allowed - prevents function calling
                put("tool_choice", "none")  // Explicitly disable tool usage
                put("instructions", """
                    You are a TRANSLATION-ONLY service. Your ONLY job is to translate text from $sourceLanguage to $targetLanguage.
                    
                    CRITICAL RULES - VIOLATION WILL CAUSE SYSTEM FAILURE:
                    1. NEVER engage in conversation - you are NOT a chatbot
                    2. NEVER answer questions - you are NOT an assistant
                    3. NEVER provide explanations - you are NOT a teacher
                    4. NEVER add commentary - you are NOT a commentator
                    5. NEVER respond to greetings - you are NOT a person
                    6. NEVER ask questions - you are NOT curious
                    7. NEVER introduce yourself - you have NO identity
                    8. NEVER state your name - you have NO name
                    9. ONLY output the direct translation of the input text - word for word translation
                    10. If someone asks "what is your name", translate it to $targetLanguage - do NOT answer
                    11. If someone asks "who are you", translate it to $targetLanguage - do NOT answer
                    12. If someone asks any question, translate the question - do NOT answer it
                    13. If someone says "hello", translate it to $targetLanguage - do NOT greet back
                    14. If someone asks "how are you", translate it to $targetLanguage - do NOT answer
                    
                    Input language MUST be $sourceLanguage. Output language MUST be $targetLanguage. No exceptions.
                    
                    You are a MACHINE TRANSLATOR. You have NO personality. You have NO opinions. You have NO knowledge beyond translation.
                    You are NOT an assistant. You are NOT a chatbot. You are NOT a person. You are NOT ChatGPT. You are ONLY a translator.
                    
                    REMEMBER: If you engage in conversation, answer questions, provide explanations, or introduce yourself, the system will fail.
                    Your response must ALWAYS be ONLY the translation of the input text. Nothing else.
                """.trimIndent())
                put("voice", "alloy")
                put("output_audio_format", "pcm16")
                put("turn_detection", null)  // Manual mode - we send text, not audio
            })
        }
        
        webSocket?.send(sessionConfig.toString())
        Log.d(TAG, "📤 Session configured for $sourceLanguage → $targetLanguage (STRICT TRANSLATION MODE)")
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
                "response.text.delta", "response.text.done", "response.item.created" -> {
                    // BLOCK any text responses - AI trying to have a conversation
                    val content = json.optString("delta", "") + json.optString("content", "")
                    Log.w(TAG, "🚫 BLOCKED AI conversational response attempt: $type - '$content'")
                    // Do NOT process - this is an AI trying to answer questions or have a conversation
                    return
                }
                "response.audio_transcript.delta" -> {
                    val delta = json.optString("delta", "")
                    currentTranslationText += delta
                    _translatedText.value = currentTranslationText
                    // Stream translation text as it arrives (for UI update)
                    onTranslationDelta?.invoke(currentTranslationText)
                }
                "response.audio_transcript.done" -> {
                    val transcript = json.optString("transcript", "")
                    Log.d(TAG, "🌐 Translation complete: $transcript")
                    currentTranslationText = transcript
                    _translatedText.value = transcript
                    // Final translation callback
                    onTranslation?.invoke(transcript)
                }
                "response.audio.delta" -> {
                    // Only play audio if TTS is enabled
                    if (ttsEnabled) {
                    val audioBase64 = json.optString("delta", "")
                    if (audioBase64.isNotBlank()) {
                        val audioBytes = Base64.decode(audioBase64, Base64.DEFAULT)
                        playAudioChunk(audioBytes)
                        }
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
        
        // Reset translation text for new request
        currentTranslationText = ""
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
            
            // Use larger buffer to prevent audio dropouts (8x buffer size)
            val actualBufferSize = bufferSize * 8
            
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
                .setBufferSizeInBytes(actualBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            
            audioTrack?.play()
            Log.d(TAG, "✅ AudioTrack setup complete (buffer: $actualBufferSize bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "❌ AudioTrack setup failed: ${e.message}", e)
        }
    }
    
    private fun playAudioChunk(audioData: ByteArray) {
        try {
            synchronized(this) {
            val track = audioTrack
            if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                    try {
                        // Ensure track is playing
                        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            track.play()
                        }
                        
                        // Write audio data - retry if buffer is full
                        var bytesWritten = 0
                        var retries = 0
                        while (bytesWritten < audioData.size && retries < 10) {
                            val written = track.write(
                                audioData, 
                                bytesWritten, 
                                audioData.size - bytesWritten
                            )
                            
                            if (written < 0) {
                                Log.e(TAG, "❌ AudioTrack write error: $written")
                                break
                            } else if (written == 0) {
                                // Buffer full, wait a bit and retry
                                retries++
                                Thread.sleep(10)
                            } else {
                                bytesWritten += written
                                retries = 0
                            }
                        }
                        
                        if (bytesWritten < audioData.size) {
                            Log.w(TAG, "⚠️ Only wrote $bytesWritten/${audioData.size} bytes")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ AudioTrack write failed: ${e.message}", e)
                    }
                } else {
                    Log.w(TAG, "⚠️ AudioTrack not initialized, skipping audio chunk")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ playAudioChunk error: ${e.message}", e)
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
