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
    var onAudioResponse: ((ByteArray) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    
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
        
        val sourceName = LANGUAGE_NAMES[sourceLang] ?: "Korean"
        val targetName = LANGUAGE_NAMES[targetLang] ?: "English"
        
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
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason")
                isConnected = false
                _isListening.value = false
            }
        })
        
        // Setup audio playback
        setupAudioPlayback()
    }
    
    private fun configureSession(sourceLang: String, targetLang: String) {
        val sessionConfig = JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                put("modalities", JSONArray().apply {
                    put("text")
                    put("audio")
                })
                put("instructions", """
                    You are a real-time translator. 
                    Listen to audio in $sourceLang and translate it to $targetLang.
                    Respond ONLY with the translation in $targetLang.
                    Keep responses short and natural for speech.
                    Do not add any explanations.
                """.trimIndent())
                put("voice", "alloy")  // Natural voice
                put("input_audio_format", "pcm16")
                put("output_audio_format", "pcm16")
                put("input_audio_transcription", JSONObject().apply {
                    put("model", "whisper-1")
                })
                put("turn_detection", JSONObject().apply {
                    put("type", "server_vad")
                    put("threshold", 0.5)
                    put("prefix_padding_ms", 300)
                    put("silence_duration_ms", 500)  // Fast detection
                })
            })
        }
        
        webSocket?.send(sessionConfig.toString())
        Log.d(TAG, "📤 Session configured for $sourceLang → $targetLang")
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
                }
                "input_audio_buffer.speech_stopped" -> {
                    Log.d(TAG, "🔇 Speech ended")
                }
                "conversation.item.input_audio_transcription.completed" -> {
                    val transcript = json.optString("transcript", "")
                    if (transcript.isNotBlank()) {
                        Log.d(TAG, "📝 Transcript: $transcript")
                        _transcription.value = transcript
                        onTranscript?.invoke(transcript)
                    }
                }
                "response.audio_transcript.delta" -> {
                    val delta = json.optString("delta", "")
                    _translation.value += delta
                }
                "response.audio_transcript.done" -> {
                    val transcript = json.optString("transcript", "")
                    Log.d(TAG, "🌐 Translation: $transcript")
                    _translation.value = transcript
                    onTranslation?.invoke(transcript)
                }
                "response.audio.delta" -> {
                    // Decode and play audio immediately
                    val audioBase64 = json.optString("delta", "")
                    if (audioBase64.isNotBlank()) {
                        val audioBytes = Base64.decode(audioBase64, Base64.DEFAULT)
                        playAudioChunk(audioBytes)
                    }
                }
                "response.audio.done" -> {
                    Log.d(TAG, "🔊 Audio response complete")
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
        val sampleRate = 24000  // OpenAI Realtime uses 24kHz
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        
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
        
        // Route to Bluetooth
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true
        
        audioTrack?.play()
    }
    
    private fun playAudioChunk(audioData: ByteArray) {
        scope.launch {
            audioTrack?.write(audioData, 0, audioData.size)
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
        
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        
        audioManager.stopBluetoothSco()
        audioManager.mode = AudioManager.MODE_NORMAL
    }
    
    fun isConnected(): Boolean = isConnected
    
    fun clearTranscription() {
        _transcription.value = ""
        _translation.value = ""
    }
}
