/*
 * OpenAI Whisper STT Service
 * Official Whisper API with high accuracy
 */

package com.meta.wearable.dat.externalsampleapps.landmarkguide.translation

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
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OpenAI Whisper - Official API for STT
 * Uses VAD to detect speech end, then sends to Whisper API
 */
class OpenAIWhisperService {
    
    companion object {
        private const val TAG = "OpenAIWhisper"
        private const val API_URL = "https://api.openai.com/v1/audio/transcriptions"
        
        // VAD settings - STRICTER to avoid hallucination
        private const val SILENCE_THRESHOLD = 600   // RMS threshold (was 400)
        private const val SILENCE_DURATION_MS = 600 // 600ms silence = end of phrase
        private const val MIN_SPEECH_MS = 500       // Min 500ms speech before processing (was 250)
        private const val MIN_AUDIO_BYTES = 16000   // Min ~500ms of audio before sending
        private const val MAX_AUDIO_MS = 10000      // Max 10 seconds
        
        // Whisper hallucination phrases to filter out
        private val HALLUCINATION_PHRASES = listOf(
            "시청해 주셔서 감사합니다",
            "감사합니다",
            "thank you for watching",
            "thanks for watching",
            "subscribe",
            "구독",
            "좋아요",
            "MBC",
            "👍",
            "🎵",
            "♪"
        )
        
        // Language codes
        val LANGUAGE_CODES = mapOf(
            "ko" to "ko",
            "en" to "en",
            "ar" to "ar",
            "es" to "es"
        )
    }
    
    private val apiKey = BuildConfig.OPENAI_API_KEY
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
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
            Log.e(TAG, "❌ OpenAI API Key not configured")
            onError?.invoke("OpenAI API Key not configured")
            return
        }
        
        currentLanguage = LANGUAGE_CODES[languageCode] ?: languageCode
        if (currentLanguage == "auto") currentLanguage = "en"
        
        _isListening.value = true
        _transcription.value = ""
        audioBuffer.clear()
        
        Log.d(TAG, "🎤 Started listening ($currentLanguage) - OpenAI Whisper")
        
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
            
            delay(25)  // Fast polling
        }
        
        // Process remaining
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
    
    /**
     * Convert PCM to WAV for Whisper API
     */
    private fun pcmToWav(pcmData: ByteArray, sampleRate: Int = 16000, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val fileSize = 36 + dataSize
        
        val wav = ByteArrayOutputStream()
        
        // RIFF header
        wav.write("RIFF".toByteArray())
        wav.write(intToBytes(fileSize, 4))
        wav.write("WAVE".toByteArray())
        
        // fmt chunk
        wav.write("fmt ".toByteArray())
        wav.write(intToBytes(16, 4))  // Chunk size
        wav.write(intToBytes(1, 2))   // PCM format
        wav.write(intToBytes(channels, 2))
        wav.write(intToBytes(sampleRate, 4))
        wav.write(intToBytes(byteRate, 4))
        wav.write(intToBytes(blockAlign, 2))
        wav.write(intToBytes(bitsPerSample, 2))
        
        // data chunk
        wav.write("data".toByteArray())
        wav.write(intToBytes(dataSize, 4))
        wav.write(pcmData)
        
        return wav.toByteArray()
    }
    
    private fun intToBytes(value: Int, size: Int): ByteArray {
        val bytes = ByteArray(size)
        for (i in 0 until size) {
            bytes[i] = (value shr (i * 8) and 0xFF).toByte()
        }
        return bytes
    }
    
    private fun transcribeAudio(audioData: ByteArray) {
        // Skip if audio is too short (likely noise)
        if (audioData.size < MIN_AUDIO_BYTES) {
            Log.d(TAG, "⏭️ Skipping short audio: ${audioData.size} bytes")
            return
        }
        
        try {
            val startTime = System.currentTimeMillis()
            
            // Convert PCM to WAV
            val wavData = pcmToWav(audioData)
            
            // Build multipart request
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "audio.wav",
                    wavData.toRequestBody("audio/wav".toMediaType())
                )
                .addFormDataPart("model", "whisper-1")
                .addFormDataPart("language", currentLanguage)
                .addFormDataPart("response_format", "json")
                .build()
            
            val request = Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            val latency = System.currentTimeMillis() - startTime
            
            if (!response.isSuccessful) {
                Log.e(TAG, "❌ API error: ${response.code} - $responseBody")
                onError?.invoke("OpenAI Whisper: ${response.code}")
                return
            }
            
            val json = JSONObject(responseBody ?: "{}")
            val transcript = json.optString("text", "").trim()
            
            // Filter out hallucinations
            val isHallucination = HALLUCINATION_PHRASES.any { 
                transcript.contains(it, ignoreCase = true) 
            }
            
            if (transcript.isNotBlank() && !isHallucination) {
                Log.d(TAG, "📝 Whisper (${latency}ms): $transcript")
                _transcription.value = transcript
                onTranscript?.invoke(transcript, true)
            } else if (isHallucination) {
                Log.w(TAG, "⚠️ Filtered hallucination: $transcript")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Transcription error: ${e.message}", e)
            onError?.invoke("Whisper error: ${e.message}")
        }
    }
    
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
