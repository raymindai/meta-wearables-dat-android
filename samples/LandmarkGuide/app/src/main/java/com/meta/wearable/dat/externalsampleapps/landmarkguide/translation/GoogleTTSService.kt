/*
 * Google Cloud Text-to-Speech Service
 * Uses REST API for TTS
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Google Cloud Text-to-Speech API
 * Supports multiple languages with high quality voices
 */
class GoogleTTSService(private val context: Context) {
    
    companion object {
        private const val TAG = "GoogleTTS"
        private const val API_URL = "https://texttospeech.googleapis.com/v1/text:synthesize"
        
        // Voice mappings by language (Neural2 voices for best quality)
        val VOICE_MAPPING = mapOf(
            "ko" to Pair("ko-KR-Neural2-A", "ko-KR"),      // Korean female
            "en" to Pair("en-US-Neural2-D", "en-US"),      // English male
            "ar" to Pair("ar-XA-Standard-B", "ar-XA"),     // Arabic male
            "es" to Pair("es-ES-Neural2-B", "es-ES")       // Spanish male
        )
        
        // Male voice alternatives
        val MALE_VOICES = mapOf(
            "ko" to "ko-KR-Neural2-C",
            "en" to "en-US-Neural2-D",
            "ar" to "ar-XA-Standard-B",
            "es" to "es-ES-Neural2-B"
        )
        
        // Female voice alternatives
        val FEMALE_VOICES = mapOf(
            "ko" to "ko-KR-Neural2-A",
            "en" to "en-US-Neural2-F",
            "ar" to "ar-XA-Standard-A",
            "es" to "es-ES-Neural2-A"
        )
    }
    
    private val apiKey = BuildConfig.GOOGLE_CLOUD_API_KEY
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var currentAudioTrack: AudioTrack? = null
    
    // Audio queue for seamless playback (no interruption)
    private data class AudioQueueItem(
        val audioData: ByteArray,
        val useBluetooth: Boolean
    )
    
    private val audioQueue = mutableListOf<AudioQueueItem>()
    private val queueMutex = Mutex()
    private var isPlaying = false
    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var playbackJob: Job? = null
    
    /**
     * Speak text using Google Cloud TTS
     */
    suspend fun speak(
        text: String,
        languageCode: String,
        useBluetooth: Boolean = true,
        voiceGender: String = "male"  // "male" or "female"
    ): Boolean = withContext(Dispatchers.IO) {
            try {
                if (apiKey.isBlank()) {
                    Log.e(TAG, "❌ Google Cloud API Key not configured")
                return@withContext false
                }
                
                // Get voice for language
                val voiceInfo = VOICE_MAPPING[languageCode] ?: VOICE_MAPPING["en"]!!
                val voiceName = if (voiceGender == "female") {
                    FEMALE_VOICES[languageCode] ?: voiceInfo.first
                } else {
                    MALE_VOICES[languageCode] ?: voiceInfo.first
                }
                val langCode = voiceInfo.second
                
                val jsonBody = JSONObject().apply {
                    put("input", JSONObject().apply {
                        put("text", text)
                    })
                    put("voice", JSONObject().apply {
                        put("languageCode", langCode)
                        put("name", voiceName)
                    })
                    put("audioConfig", JSONObject().apply {
                        put("audioEncoding", "LINEAR16")
                        put("sampleRateHertz", 24000)
                        put("speakingRate", 1.2)  // 20% faster
                        put("pitch", 0.0)
                    })
                }
                
                val requestBody = jsonBody.toString()
                    .toRequestBody("application/json".toMediaType())
                
                val request = Request.Builder()
                    .url("$API_URL?key=$apiKey")
                    .post(requestBody)
                    .build()
                
                Log.d(TAG, "🔊 TTS: '$text' (voice: $voiceName)")
                val startTime = System.currentTimeMillis()
                
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()
                val latency = System.currentTimeMillis() - startTime
                
                Log.d(TAG, "⏱️ TTS response: ${latency}ms")
                
                if (!response.isSuccessful) {
                    Log.e(TAG, "❌ TTS failed: ${response.code} - $responseBody")
                    return@withContext false
                }
                
                val json = JSONObject(responseBody ?: "{}")
                val audioContent = json.optString("audioContent", "")
                
                if (audioContent.isBlank()) {
                    Log.e(TAG, "❌ No audio content in response")
                    return@withContext false
                }
                
                // Decode base64 audio
                val audioBytes = Base64.decode(audioContent, Base64.DEFAULT)
                
                // Add to queue instead of playing directly
                queueAudio(audioBytes, useBluetooth)
                
                true
            } catch (e: Exception) {
                Log.e(TAG, "❌ TTS error: ${e.message}", e)
                false
            }
        }
    
    /**
     * Queue audio for seamless playback (no interruption)
     */
    private suspend fun queueAudio(
        audioBytes: ByteArray,
        useBluetooth: Boolean
    ) = withContext(Dispatchers.IO) {
        queueMutex.withLock {
            audioQueue.add(AudioQueueItem(audioBytes, useBluetooth))
            Log.d(TAG, "📥 Audio queued (queue size: ${audioQueue.size})")
        }
        
        // Start playback loop if not already running
        if (!isPlaying) {
            startPlaybackLoop()
        }
    }
    
    /**
     * Start the playback loop that processes the queue sequentially
     */
    private fun startPlaybackLoop() {
        if (isPlaying) return
        
        playbackJob = playbackScope.launch {
            isPlaying = true
            Log.d(TAG, "▶️ Playback loop started")
            
            val sampleRate = 24000
            var bluetoothInitialized = false
            
            while (true) {
                val item = queueMutex.withLock {
                    if (audioQueue.isEmpty()) {
                        null
                    } else {
                        audioQueue.removeAt(0)
                    }
                }
                
                if (item == null) {
                    // Queue is empty, wait a bit and check again
                    kotlinx.coroutines.delay(100)
                    val shouldStop = queueMutex.withLock {
                        if (audioQueue.isEmpty()) {
                            // Queue is still empty, stop the loop
                            isPlaying = false
                            Log.d(TAG, "⏹️ Playback loop stopped (queue empty)")
                            true
                        } else {
                            false
                        }
                    }
                    if (shouldStop) break
                    continue
                }
                
                // Play the audio item
                try {
                    // Initialize Bluetooth SCO on first item if needed
                    if (item.useBluetooth && !bluetoothInitialized) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isBluetoothScoOn = true
            audioManager.startBluetoothSco()
                        bluetoothInitialized = true
                        Log.d(TAG, "🔵 Bluetooth SCO initialized")
        }
        
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        
                    val audioTrack = AudioTrack.Builder()
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
                        .setBufferSizeInBytes(bufferSize.coerceAtLeast(item.audioData.size))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        
                    currentAudioTrack = audioTrack
                    
                    audioTrack.write(item.audioData, 0, item.audioData.size)
                    audioTrack.play()
        
        // Wait for playback to complete
                    val durationMs = (item.audioData.size / (sampleRate * 2)) * 1000
                    Thread.sleep(durationMs.toLong() + 50) // Small buffer for seamless transition
        
        // Cleanup
                    audioTrack.stop()
                    audioTrack.release()
        currentAudioTrack = null
        
                    Log.d(TAG, "✅ Playback done (${durationMs}ms, queue remaining: ${queueMutex.withLock { audioQueue.size }})")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Audio playback error: ${e.message}", e)
                }
            }
            
            // Don't stop SCO to keep mic active
        }
    }
    
    private fun playAudio(audioBytes: ByteArray, useBluetooth: Boolean) {
        // Legacy function - now uses queue
        kotlinx.coroutines.runBlocking {
            queueAudio(audioBytes, useBluetooth)
        }
    }
    
    fun stop() {
        try {
            // Stop current playback
        currentAudioTrack?.stop()
        currentAudioTrack?.release()
        currentAudioTrack = null
            
            // Clear queue
            kotlinx.coroutines.runBlocking {
                queueMutex.withLock {
                    audioQueue.clear()
                    isPlaying = false
                }
            }
            
            // Cancel playback job
            playbackJob?.cancel()
            playbackJob = null
            
            Log.d(TAG, "⏹️ TTS stopped and queue cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio: ${e.message}")
        }
    }
}
