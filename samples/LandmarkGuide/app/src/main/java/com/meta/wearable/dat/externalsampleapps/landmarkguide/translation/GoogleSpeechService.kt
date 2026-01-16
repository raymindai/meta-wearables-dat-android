/*
 * Google Cloud Speech-to-Text Service
 * Streaming STT with Arabic support and VAD (Voice Activity Detection)
 */

package com.meta.wearable.dat.externalsampleapps.landmarkguide.translation

import android.util.Base64
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
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
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
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
        /**
         * IMPORTANT:
         * Speech-to-Text v2 (including Chirp 3) requires IAM-authenticated requests.
         * API key calls from Android will fail with `speech.recognizers.recognize` PERMISSION_DENIED.
         *
         * So we call an HTTPS Firebase Function proxy (`sttRecognizeV2`) that runs with a service account.
         */
        // Prefer DB-triggered STT v2 (no public HTTPS invoker needed due to org policy).
        private const val DEFAULT_STT_LOCATION = "asia-northeast1"
        private const val DEFAULT_MODEL = "chirp_3"
        private const val AUDIO_SAMPLE_RATE_HZ = 8000
        
        // Language codes for Google STT
        val LANGUAGE_CODES = mapOf(
            "ko" to "ko-KR",
            "en" to "en-US",
            "ar" to "ar-SA",  // Saudi Arabic
            "es" to "es-ES"
        )
        
        // VAD settings - OPTIMIZED for low latency
        private const val SILENCE_THRESHOLD = 400   // RMS threshold for silence (lowered for sensitivity)
        // Tune for lower perceived latency (send smaller chunks more frequently)
        private const val SILENCE_DURATION_MS = 250 // 250ms of silence = end of phrase
        private const val MIN_SPEECH_MS = 150       // Minimum speech before processing
        private const val MAX_AUDIO_MS = 3500       // Force process after 3.5s max
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)  // Faster connection timeout
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val db = FirebaseDatabase.getInstance()
    private val sttRequestsRef = db.getReference("sttRequests")
    private val sttResponsesRef = db.getReference("sttResponses")
    
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
    var onLanguageDetected: ((String) -> Unit)? = null  // Callback for detected language
    
    private var currentLanguage = "ar-SA"
    
    /**
     * Start listening
     */
    fun startListening(languageCode: String) {
        // Support "auto" for auto-detection
        currentLanguage = when (languageCode) {
            "auto" -> "auto"
            else -> LANGUAGE_CODES[languageCode] ?: "ar-SA"
        }
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
                // bytes/ms = sampleRateHz * 2 bytes / 1000. For 8kHz mono PCM16 => 16 bytes/ms
                val audioDurationMs = (combinedAudio.size / 16)
                
                Log.d(TAG, "📤 Processing ${audioDurationMs}ms of audio (silence: ${silenceDuration}ms)")
                
                // Clear and reset
                accumulatedAudio.clear()
                isSpeaking = false
                speechStartTime = null
                
                // Transcribe
                transcribeAudio(combinedAudio)
            }
            
            delay(30)  // 30ms polling interval (was 50ms)
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
            
            val langs = if (currentLanguage == "auto") {
                org.json.JSONArray().apply {
                    put("ko-KR")
                    put("en-US")
                            put("ar-SA")
                            put("es-ES")
                }
            } else {
                org.json.JSONArray().apply { put(currentLanguage) }
            }

            // DB-triggered STT v2 request (no HTTPS invoker needed)
            val requestId = sttRequestsRef.push().key
            if (requestId.isNullOrBlank()) {
                onError?.invoke("Failed to create STT request id")
                return
            }

            val requestMap = mapOf(
                "audioContentBase64" to audioBase64,
                "languageCodes" to (0 until langs.length()).map { langs.getString(it) },
                "model" to DEFAULT_MODEL,
                "location" to DEFAULT_STT_LOCATION,
                "sampleRateHertz" to AUDIO_SAMPLE_RATE_HZ,
                "createdAt" to ServerValue.TIMESTAMP
            )

            Log.d(TAG, "📤 STT v2 request → RTDB /sttRequests/$requestId (lang=$currentLanguage)")

            // Write request
            val writeLatch = CountDownLatch(1)
            var writeError: String? = null
            sttRequestsRef.child(requestId).setValue(requestMap)
                .addOnSuccessListener { writeLatch.countDown() }
                .addOnFailureListener { e ->
                    writeError = e.message
                    writeLatch.countDown()
                }

            if (!writeLatch.await(5, TimeUnit.SECONDS)) {
                onError?.invoke("STT request write timeout")
                return
            }
            if (writeError != null) {
                onError?.invoke("STT request write failed: $writeError")
                return
            }

            // Wait for response
            val startTime = System.currentTimeMillis()
            val responseLatch = CountDownLatch(1)
            val responseHolder = arrayOfNulls<String>(1)

            // We store a JSON-like map in RTDB; easiest is to read raw snapshot as JSON via toString()
            sttResponsesRef.child(requestId).addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()) return
                    responseHolder[0] = snapshot.value?.let { JSONObject.wrap(it)?.toString() ?: "{}" } ?: "{}"
                    responseLatch.countDown()
                    sttResponsesRef.child(requestId).removeEventListener(this)
                }
                override fun onCancelled(error: DatabaseError) {
                    responseHolder[0] = JSONObject().apply {
                        put("error", "db_cancelled")
                        put("details", error.message)
                    }.toString()
                    responseLatch.countDown()
                    sttResponsesRef.child(requestId).removeEventListener(this)
                }
            })

            if (!responseLatch.await(25, TimeUnit.SECONDS)) {
                onError?.invoke("STT response timeout")
                return
            }

            val latency = System.currentTimeMillis() - startTime
            val responseBody = responseHolder[0] ?: "{}"
            val json = JSONObject(responseBody)

            val err = json.optString("error", "")
            if (err.isNotBlank()) {
                Log.e(TAG, "❌ STT v2 error (DB): $responseBody")
                onError?.invoke("STT v2 error: $err")
                return
            }
            
            val transcript = json.optString("transcript", "").trim()
            val detectedLanguage = json.optString("languageCode", "").takeIf { it.isNotBlank() }

                if (transcript.isNotBlank()) {
                if (currentLanguage == "auto" && !detectedLanguage.isNullOrBlank()) {
                        Log.d(TAG, "🌐 Detected language: $detectedLanguage")
                        onLanguageDetected?.invoke(detectedLanguage)
                    }
                    Log.d(TAG, "📝 Transcript (${latency}ms): $transcript")
                    _transcription.value = transcript
                    onTranscript?.invoke(transcript, true)
            } else {
                Log.d(TAG, "🔇 Empty transcript (DB response): $responseBody")
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
