/*
 * Google Cloud Speech-to-Text Service
 * Streaming STT with Arabic support and VAD (Voice Activity Detection)
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
 * Google Cloud STT with Arabic support
 * Uses VAD (Voice Activity Detection) to send complete phrases
 */
class GoogleSpeechService {
    
    companion object {
        private const val TAG = "GoogleSTT"
        private const val API_URL = "https://speech.googleapis.com/v1/speech:recognize"
        
        // Language codes for Google STT
        val LANGUAGE_CODES = mapOf(
            "ko" to "ko-KR",
            "en" to "en-US",
            "ar" to "ar-SA",  // Saudi Arabic
            "es" to "es-ES"
        )
        
        // VAD settings
        private const val SILENCE_THRESHOLD = 500   // RMS threshold for silence
        private const val SILENCE_DURATION_MS = 800 // 800ms of silence = end of phrase
        private const val MIN_SPEECH_MS = 500       // Minimum speech before processing
        private const val MAX_AUDIO_MS = 15000      // Max 15 seconds before force processing
    }
    
    private val apiKey = BuildConfig.GOOGLE_CLOUD_API_KEY
    
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
    
    private val _partialTranscription = MutableStateFlow("")
    val partialTranscription: StateFlow<String> = _partialTranscription.asStateFlow()
    
    // Callbacks
    var onTranscript: ((String, Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    
    private var currentLanguage = "ar-SA"
    
    /**
     * Start listening
     */
    fun startListening(languageCode: String) {
        if (apiKey.isBlank()) {
            Log.e(TAG, "❌ Google Cloud API Key not configured")
            onError?.invoke("Google Cloud API Key not configured")
            return
        }
        
        currentLanguage = LANGUAGE_CODES[languageCode] ?: "ar-SA"
        _isListening.value = true
        _transcription.value = ""
        _partialTranscription.value = ""
        audioBuffer.clear()
        
        Log.d(TAG, "🎤 Started listening ($currentLanguage)")
        
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
     * Calculate RMS (Root Mean Square) of audio for VAD
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
     * Process audio with Voice Activity Detection
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
                
                // Check if this chunk has speech
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
            
            // Check if we should process:
            // 1. Speech was detected and now there's enough silence (end of phrase)
            // 2. Or we've accumulated too much audio (force process)
            val shouldProcess = isSpeaking && (
                (silenceDuration >= SILENCE_DURATION_MS && totalDuration >= MIN_SPEECH_MS) ||
                (totalDuration >= MAX_AUDIO_MS)
            )
            
            if (shouldProcess && accumulatedAudio.isNotEmpty()) {
                val combinedAudio = combineAudioChunks(accumulatedAudio)
                val audioDurationMs = (combinedAudio.size / 32)  // 16kHz * 2 bytes = 32 bytes/ms
                
                Log.d(TAG, "📤 Processing ${audioDurationMs}ms of audio (silence: ${silenceDuration}ms)")
                
                // Clear and reset
                accumulatedAudio.clear()
                isSpeaking = false
                speechStartTime = null
                
                // Transcribe
                transcribeAudio(combinedAudio)
            }
            
            delay(50)  // 50ms polling interval
        }
        
        // Process remaining audio on stop
        if (accumulatedAudio.isNotEmpty()) {
            val combinedAudio = combineAudioChunks(accumulatedAudio)
            if (combinedAudio.size > 3200) {  // At least 100ms
                Log.d(TAG, "📤 Processing remaining audio")
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
            val audioBase64 = Base64.encodeToString(audioData, Base64.NO_WRAP)
            
            val requestJson = JSONObject().apply {
                put("config", JSONObject().apply {
                    put("encoding", "LINEAR16")
                    put("sampleRateHertz", 16000)
                    put("languageCode", currentLanguage)
                    put("enableAutomaticPunctuation", true)
                    put("model", "latest_long")
                })
                put("audio", JSONObject().apply {
                    put("content", audioBase64)
                })
            }
            
            val requestBody = requestJson.toString()
                .toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("$API_URL?key=$apiKey")
                .post(requestBody)
                .build()
            
            val startTime = System.currentTimeMillis()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            val latency = System.currentTimeMillis() - startTime
            
            if (!response.isSuccessful) {
                Log.e(TAG, "❌ API error: ${response.code} - $responseBody")
                onError?.invoke("Google API: ${response.code}")
                return
            }
            
            val json = JSONObject(responseBody ?: "{}")
            val results = json.optJSONArray("results")
            
            if (results != null && results.length() > 0) {
                // Combine all results for the full transcription
                val fullTranscript = StringBuilder()
                for (i in 0 until results.length()) {
                    val result = results.getJSONObject(i)
                    val alternatives = result.optJSONArray("alternatives")
                    if (alternatives != null && alternatives.length() > 0) {
                        val transcript = alternatives.getJSONObject(0).optString("transcript", "")
                        fullTranscript.append(transcript)
                    }
                }
                
                val transcript = fullTranscript.toString().trim()
                if (transcript.isNotBlank()) {
                    Log.d(TAG, "📝 Transcript (${latency}ms): $transcript")
                    _transcription.value = transcript
                    onTranscript?.invoke(transcript, true)
                }
            } else {
                Log.d(TAG, "🔇 No speech detected")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Transcription error: ${e.message}", e)
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
        _partialTranscription.value = ""
    }
    
    fun isConnected(): Boolean = _isListening.value
}
