/*
 * Deepgram Whisper Cloud STT Service (Batch Mode)
 * Uses REST API for best accuracy with Whisper model
 */

package com.meta.wearable.dat.externalsampleapps.landmarkguide.translation

import android.util.Base64
import android.util.Log
import com.meta.wearable.dat.externalsampleapps.landmarkguide.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Deepgram Whisper Cloud - Batch Mode STT
 * Uses REST API with VAD for best accuracy
 * 
 * Whisper models: whisper-tiny, whisper-base, whisper-small, whisper-medium, whisper-large
 */
class DeepgramWhisperService {
    
    companion object {
        private const val TAG = "DeepgramWhisper"
        private const val API_URL = "https://api.deepgram.com/v1/listen"
        
        // VAD settings
        private const val SILENCE_THRESHOLD = 500   // RMS threshold for silence
        private const val SILENCE_DURATION_MS = 800 // 800ms of silence = end of phrase
        private const val MIN_SPEECH_MS = 300       // Minimum speech before processing
        private const val MAX_AUDIO_MS = 15000      // Max 15 seconds before force processing
    }
    
    private val apiKey = BuildConfig.DEEPGRAM_API_KEY
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val audioBuffer = ConcurrentLinkedQueue<ByteArray>()
    private val isProcessing = AtomicBoolean(false)
    private var processingJob: Job? = null
    
    // State
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()
    
    private val _transcription = MutableStateFlow("")
    val transcription: StateFlow<String> = _transcription.asStateFlow()
    
    // Callbacks
    var onTranscript: ((String, Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    
    private var currentLanguage = "en"
    
    /**
     * Start listening
     */
    fun startListening(languageCode: String) {
        if (apiKey.isBlank()) {
            Log.e(TAG, "❌ Deepgram API Key not configured")
            onError?.invoke("Deepgram API Key not configured")
            return
        }
        
        // Map language codes (Whisper uses simple codes)
        currentLanguage = when (languageCode) {
            "ko" -> "ko"
            "ar" -> "ar"
            "es" -> "es"
            "en" -> "en"
            "auto" -> "en"  // Whisper will auto-detect if no language specified
            else -> "en"
        }
        
        _isListening.value = true
        _transcription.value = ""
        audioBuffer.clear()
        
        Log.d(TAG, "🎤 Started listening ($currentLanguage) - Whisper Batch Mode")
        
        // Start processing loop with VAD
        isProcessing.set(true)
        processingJob = scope.launch {
            processAudioWithVAD()
        }
    }
    
    /**
     * Send audio data
     */
    fun sendAudio(data: ByteArray) {
        if (_isListening.value) {
            audioBuffer.offer(data.copyOf())
        }
    }
    
    /**
     * Calculate RMS for VAD
     */
    private fun calculateRMS(audio: ByteArray): Int {
        if (audio.size < 2) return 0
        var sum = 0L
        for (i in 0 until audio.size - 1 step 2) {
            val sample = (audio[i].toInt() and 0xFF) or (audio[i + 1].toInt() shl 8)
            sum += sample.toLong() * sample
        }
        return kotlin.math.sqrt(sum.toDouble() / (audio.size / 2)).toInt()
    }
    
    /**
     * Process audio with VAD
     */
    private suspend fun processAudioWithVAD() {
        val accumulatedAudio = mutableListOf<ByteArray>()
        var speechStartTime: Long? = null
        var lastSpeechTime = System.currentTimeMillis()
        var isSpeaking = false
        
        while (isProcessing.get()) {
            // Collect audio from buffer
            while (audioBuffer.isNotEmpty()) {
                val chunk = audioBuffer.poll() ?: continue
                accumulatedAudio.add(chunk)
                
                val rms = calculateRMS(chunk)
                val hasSpeech = rms > SILENCE_THRESHOLD
                
                if (hasSpeech) {
                    if (!isSpeaking) {
                        speechStartTime = System.currentTimeMillis()
                        isSpeaking = true
                        Log.d(TAG, "🗣️ Speech started (RMS: $rms)")
                    }
                    lastSpeechTime = System.currentTimeMillis()
                }
            }
            
            val now = System.currentTimeMillis()
            val silenceDuration = now - lastSpeechTime
            val totalDuration = speechStartTime?.let { now - it } ?: 0
            
            val shouldProcess = isSpeaking && (
                (silenceDuration >= SILENCE_DURATION_MS && totalDuration >= MIN_SPEECH_MS) ||
                (totalDuration >= MAX_AUDIO_MS)
            )
            
            if (shouldProcess && accumulatedAudio.isNotEmpty()) {
                val combinedAudio = combineAudioChunks(accumulatedAudio)
                val audioDurationMs = (combinedAudio.size / 32)
                
                Log.d(TAG, "📤 Processing ${audioDurationMs}ms of audio")
                
                accumulatedAudio.clear()
                isSpeaking = false
                speechStartTime = null
                
                transcribeAudio(combinedAudio)
            }
            
            delay(50)
        }
        
        // Process remaining audio
        if (accumulatedAudio.isNotEmpty()) {
            val combinedAudio = combineAudioChunks(accumulatedAudio)
            if (combinedAudio.size > 3200) {
                transcribeAudio(combinedAudio)
            }
        }
    }
    
    private fun combineAudioChunks(chunks: List<ByteArray>): ByteArray {
        val totalSize = chunks.sumOf { it.size }
        val result = ByteArray(totalSize)
        var offset = 0
        for (chunk in chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.size)
            offset += chunk.size
        }
        return result
    }
    
    private fun transcribeAudio(audioData: ByteArray) {
        try {
            val startTime = System.currentTimeMillis()
            
            // Build URL with Whisper model and audio format
            // Deepgram REST API requires format info in URL params
            val url = "$API_URL?" +
                    "model=whisper-large" +
                    "&language=$currentLanguage" +
                    "&punctuate=true" +
                    "&smart_format=true" +
                    "&encoding=linear16" +
                    "&sample_rate=16000" +
                    "&channels=1"
            
            // Send raw PCM audio with correct content type
            val requestBody = audioData.toRequestBody("audio/l16; rate=16000".toMediaType())
            
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Token $apiKey")
                .post(requestBody)
                .build()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            val latency = System.currentTimeMillis() - startTime
            
            if (!response.isSuccessful) {
                Log.e(TAG, "❌ API error: ${response.code} - $responseBody")
                onError?.invoke("Deepgram API: ${response.code}")
                return
            }
            
            val json = JSONObject(responseBody ?: "{}")
            val results = json.optJSONObject("results")
            val channels = results?.optJSONArray("channels")
            
            if (channels != null && channels.length() > 0) {
                val channel = channels.getJSONObject(0)
                val alternatives = channel.optJSONArray("alternatives")
                
                if (alternatives != null && alternatives.length() > 0) {
                    val transcript = alternatives.getJSONObject(0).optString("transcript", "").trim()
                    
                    if (transcript.isNotBlank()) {
                        Log.d(TAG, "📝 Whisper (${latency}ms): $transcript")
                        _transcription.value = transcript
                        onTranscript?.invoke(transcript, true)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Transcription error: ${e.message}", e)
            onError?.invoke("Whisper error: ${e.message}")
        }
    }
    
    /**
     * Stop listening
     */
    fun stopListening() {
        Log.d(TAG, "🛑 Stopping...")
        isProcessing.set(false)
        processingJob?.cancel()
        processingJob = null
        _isListening.value = false
        audioBuffer.clear()
    }
    
    fun clearTranscription() {
        _transcription.value = ""
    }
    
    fun isConnected(): Boolean = _isListening.value
}
