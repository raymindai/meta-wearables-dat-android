/*
 * OpenAI Realtime API Service
 * True real-time speech-to-speech translation using WebSocket
 * Model: gpt-4o-realtime-preview
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
 * OpenAI Realtime API - True speech-to-speech translation
 * Single WebSocket connection handles: Audio → STT → Translation → TTS
 * Latency: ~300ms end-to-end
 */
class OpenAIRealtimeService(private val context: Context) {
    
    companion object {
        private const val TAG = "OpenAIRealtime"
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
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()
    
    private val _transcription = MutableStateFlow("")
    val transcription: StateFlow<String> = _transcription.asStateFlow()
    
    private val _translation = MutableStateFlow("")
    val translation: StateFlow<String> = _translation.asStateFlow()
    
    // Callbacks
    var onTranscript: ((String) -> Unit)? = null
    var onTranslation: ((String) -> Unit)? = null
    var onTranslationWithOriginal: ((String, String) -> Unit)? = null  // (original, translation)
    var onAudioResponse: ((ByteArray) -> Unit)? = null
    var onSpeechStarted: (() -> Unit)? = null
    var onSpeechEnded: (() -> Unit)? = null
    
    // TTS control - can be set externally to mute/unmute audio playback
    var ttsEnabled: Boolean = true
    var onError: ((String) -> Unit)? = null
    
    // State tracking for translation matching
    private var lastTranscriptForTranslation = ""
    private var isSpeaking = false
    
    private var sourceLanguage = "ko"
    private var targetLanguage = "en"
    
    /**
     * Start realtime translation session
     */
    fun start(sourceLang: String, targetLang: String) {
        if (apiKey.isBlank()) {
            Log.e(TAG, "❌ OpenAI API Key not configured")
            onError?.invoke("OpenAI API Key not configured")
            return
        }
        
        if (isConnected) {
            Log.w(TAG, "⚠️ Already connected")
            return
        }
        
        sourceLanguage = sourceLang
        targetLanguage = targetLang
        
        val sourceName = LANGUAGE_NAMES[sourceLang] ?: sourceLang
        val targetName = LANGUAGE_NAMES[targetLang] ?: targetLang
        
        Log.d(TAG, "🚀 Starting OpenAI Realtime: $sourceLang ($sourceName) → $targetLang ($targetName)")
        
        val url = "$REALTIME_URL?model=$MODEL"
        
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("OpenAI-Beta", "realtime=v1")
            .build()
        
        Log.d(TAG, "🚀 Connecting to OpenAI Realtime API ($sourceName → $targetName)...")
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "✅ WebSocket connected")
                isConnected = true
                _isListening.value = true
                
                // Configure session for translation
                configureSession(sourceName, targetName)
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "❌ WebSocket error: ${t.message}")
                isConnected = false
                _isListening.value = false
                onError?.invoke(t.message ?: "Connection failed")
                
                // Auto-reconnect after error
                scope.launch {
                    Log.d(TAG, "🔄 Auto-reconnecting in 2 seconds...")
                    kotlinx.coroutines.delay(2000)
                    if (!isConnected) {
                        start(sourceLanguage, targetLanguage)
                    }
                }
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason (code=$code)")
                isConnected = false
                _isListening.value = false
                
                // Auto-reconnect if closed unexpectedly (not by us)
                if (code != 1000) {
                    scope.launch {
                        Log.d(TAG, "🔄 Auto-reconnecting after unexpected close...")
                        kotlinx.coroutines.delay(1000)
                        if (!isConnected) {
                            start(sourceLanguage, targetLanguage)
                        }
                    }
                }
            }
        })
        
        // Setup audio playback
        setupAudioPlayback()
    }
    
    private fun configureSession(sourceLang: String, targetLang: String) {
        val sourceName = LANGUAGE_NAMES[sourceLang] ?: sourceLang
        val targetName = LANGUAGE_NAMES[targetLang] ?: targetLang
        
        val sessionConfig = JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                put("modalities", JSONArray().apply {
                    put("audio")
                    put("text")  // Required by API - but we won't send text, only audio
                })
                put("tools", JSONArray())  // No tools allowed
                put("tool_choice", "none")  // Explicitly disable tool usage
                put("instructions", """
                    You are a TRANSLATION-ONLY service. Your ONLY job is to translate speech from $sourceName to $targetName.
                    
                    CRITICAL RULES - VIOLATION WILL CAUSE SYSTEM FAILURE:
                    1. NEVER engage in conversation - you are NOT a chatbot
                    2. NEVER answer questions - you are NOT an assistant
                    3. NEVER provide explanations - you are NOT a teacher
                    4. NEVER add commentary - you are NOT a commentator
                    5. NEVER respond to greetings - you are NOT a person
                    6. NEVER ask questions - you are NOT curious
                    7. ONLY output the direct translation of what you hear - word for word translation
                    8. If you hear something that is not in $sourceName, output NOTHING - complete silence
                    9. If the input is unclear or not speech, output NOTHING - complete silence
                    10. If someone asks "how are you", translate it to "$targetName" - do NOT answer
                    11. If someone says "hello", translate it to "$targetName" - do NOT greet back
                    12. If someone asks a question, translate the question - do NOT answer it
                    
                    Input language MUST be $sourceName. If detected language differs, output NOTHING.
                    Output language MUST be $targetName. No exceptions.
                    
                    You are a MACHINE TRANSLATOR. You have NO personality. You have NO opinions. You have NO knowledge beyond translation.
                    You are NOT an assistant. You are NOT a chatbot. You are NOT a person. You are ONLY a translator.
                    
                    REMEMBER: If you engage in conversation, answer questions, or provide explanations, the system will fail.
                """.trimIndent())
                put("voice", "echo")  // Male voice (alloy sounds female)
                put("input_audio_format", "pcm16")
                put("output_audio_format", "pcm16")
                put("input_audio_transcription", JSONObject().apply {
                    put("model", "whisper-1")
                })
                put("turn_detection", JSONObject().apply {
                    put("type", "server_vad")
                    put("threshold", 0.75)  // Higher = less sensitive to noise (increased from 0.7)
                    put("prefix_padding_ms", 300)
                    put("silence_duration_ms", 700)  // Slightly longer to avoid cutting off
                })
            })
        }
        
        webSocket?.send(sessionConfig.toString())
        Log.d(TAG, "📤 Session configured for $sourceName → $targetName (codes: $sourceLang → $targetLang)")
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
                    Log.d(TAG, "✅ Session updated")
                }
                "input_audio_buffer.speech_started" -> {
                    Log.d(TAG, "🎤 Speech detected")
                    isSpeaking = true
                    onSpeechStarted?.invoke()
                }
                "input_audio_buffer.speech_stopped" -> {
                    Log.d(TAG, "🔇 Speech ended")
                    isSpeaking = false
                    onSpeechEnded?.invoke()
                }
                "conversation.item.input_audio_transcription.completed" -> {
                    val transcript = json.optString("transcript", "")
                    if (transcript.isNotBlank()) {
                        Log.d(TAG, "📝 Transcript received: '$transcript' (source: $sourceLanguage)")
                        _transcription.value = transcript
                        lastTranscriptForTranslation = transcript  // Store for translation matching
                        onTranscript?.invoke(transcript)
                    } else {
                        Log.w(TAG, "⚠️ Empty transcript received")
                    }
                }
                "response.audio_transcript.delta" -> {
                    val delta = json.optString("delta", "")
                    // Only accumulate if we have a valid original transcript
                    if (lastTranscriptForTranslation.isNotBlank()) {
                    _translation.value += delta
                    }
                }
                "response.audio_transcript.done" -> {
                    val transcript = json.optString("transcript", "")
                    // Only process if we have a valid original transcript (prevents AI responses to non-speech)
                    if (lastTranscriptForTranslation.isNotBlank()) {
                    Log.d(TAG, "🌐 Translation: $transcript (original: $lastTranscriptForTranslation)")
                    _translation.value = transcript
                    // Pass both original and translation
                    if (transcript.isNotBlank()) {
                        onTranslationWithOriginal?.invoke(lastTranscriptForTranslation, transcript)
                        onTranslation?.invoke(transcript)
                        }
                    } else {
                        Log.w(TAG, "⚠️ Ignoring translation response - no matching original transcript (possible AI conversation attempt)")
                    }
                    lastTranscriptForTranslation = ""  // Reset after use
                }
                "response.text.delta", "response.text.done", "response.item.created" -> {
                    // Block any text responses (AI trying to have a conversation)
                    Log.w(TAG, "🚫 Blocked AI text response attempt: $type")
                }
                "response.audio.delta" -> {
                    // Decode and play audio only if TTS is enabled
                    if (ttsEnabled) {
                    val audioBase64 = json.optString("delta", "")
                    if (audioBase64.isNotBlank()) {
                        val audioBytes = Base64.decode(audioBase64, Base64.DEFAULT)
                        playAudioChunk(audioBytes)
                        }
                    } else {
                        // TTS disabled - ignore audio chunks
                    }
                }
                "response.audio.done" -> {
                    if (ttsEnabled) {
                    Log.d(TAG, "🔊 Audio response complete")
                    } else {
                        Log.d(TAG, "🔇 Audio response received but TTS disabled (ignored)")
                    }
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
     * Send audio data to OpenAI Realtime
     */
    fun sendAudio(audioData: ByteArray) {
        if (!isConnected || webSocket == null) return
        
        val base64Audio = Base64.encodeToString(audioData, Base64.NO_WRAP)
        
        val audioEvent = JSONObject().apply {
            put("type", "input_audio_buffer.append")
            put("audio", base64Audio)
        }
        
        webSocket?.send(audioEvent.toString())
    }
    
    private fun setupAudioPlayback() {
        try {
            val sampleRate = 24000  // OpenAI Realtime uses 24kHz
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            
            synchronized(this) {
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
                    .setBufferSizeInBytes(bufferSize * 8)  // Increased buffer for smooth playback
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                
                // Route to Bluetooth
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
                
                audioTrack?.play()
            }
            Log.d(TAG, "🔊 Audio playback setup complete (buffer: ${bufferSize * 8})")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Audio setup error: ${e.message}", e)
        }
    }
    
    private fun playAudioChunk(audioData: ByteArray) {
        // Skip audio playback if TTS is disabled
        if (!ttsEnabled) return
        
        try {
            synchronized(this) {
                val track = audioTrack
                if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                    // Ensure track is playing
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        track.play()
                    }
                    track.write(audioData, 0, audioData.size)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Audio write error: ${e.message}")
        }
    }
    
    /**
     * Stop the realtime session
     */
    fun stop() {
        Log.d(TAG, "🛑 Stopping...")
        
        webSocket?.close(1000, "Done")
        webSocket = null
        isConnected = false
        _isListening.value = false
        
        synchronized(this) {
            try {
                audioTrack?.stop()
                audioTrack?.release()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Audio stop error: ${e.message}")
            }
            audioTrack = null
        }
        
        try {
            audioManager.stopBluetoothSco()
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            Log.e(TAG, "❌ Bluetooth cleanup error: ${e.message}")
        }
    }
    
    fun isConnected(): Boolean = isConnected
    
    fun clearTranscription() {
        _transcription.value = ""
        _translation.value = ""
    }
}
