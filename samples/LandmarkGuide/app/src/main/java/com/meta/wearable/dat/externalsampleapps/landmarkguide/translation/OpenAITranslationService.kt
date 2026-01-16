/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.landmarkguide.translation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
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
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OpenAI Translation + TTS Service
 * Uses GPT-4o-mini for fast translation and OpenAI TTS for natural speech
 * Optimized for minimal latency
 */
class OpenAITranslationService(private val context: Context) {
    
    companion object {
        private const val TAG = "OpenAITranslation"
        private const val CHAT_URL = "https://api.openai.com/v1/chat/completions"
        private const val TTS_URL = "https://api.openai.com/v1/audio/speech"
        
        // Language display names for prompts (with regional specifics)
        val LANGUAGE_NAMES = mapOf(
            "ko" to "Korean",
            "en" to "English", 
            "ar" to "Saudi Arabic",      // Saudi dialect
            "es" to "Castilian Spanish"  // Spain Spanish, not Latin American
        )
        
        // TTS voice mapping (all natural sounding)
        val TTS_VOICES = mapOf(
            "ko" to "onyx",    // Male, deep
            "en" to "onyx",    // Male, deep
            "ar" to "onyx",    // Male, deep
            "es" to "onyx"     // Male, deep
        )
    }
    
    private val apiKey = BuildConfig.OPENAI_API_KEY
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var currentAudioTrack: AudioTrack? = null
    
    // Audio queue for seamless playback (no interruption)
    private data class AudioQueueItem(
        val audioData: ByteArray,
        val sampleRate: Int,
        val useBluetooth: Boolean,
        val onComplete: (() -> Unit)? = null  // Callback when playback completes
    )
    
    private val audioQueue = mutableListOf<AudioQueueItem>()
    private val queueMutex = Mutex()
    private var isPlaying = false
    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var playbackJob: Job? = null
    
    /**
     * Translate text using GPT-4o-mini (fastest model)
     */
    suspend fun translate(
        text: String,
        targetLanguage: String,
        sourceLanguage: String
    ): TranslationResult? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔄 translate() called: '$text'")
            Log.d(TAG, "📌 API Key length: ${apiKey.length}, starts with: ${apiKey.take(10)}...")
            
            if (apiKey.isBlank()) {
                Log.e(TAG, "❌ OpenAI API Key not configured (BLANK)")
                return@withContext null
            }
            
            if (!apiKey.startsWith("sk-")) {
                Log.e(TAG, "❌ Invalid API Key format (should start with sk-)")
                return@withContext null
            }
            
            val targetLangName = LANGUAGE_NAMES[targetLanguage] ?: "English"
            val sourceLangName = LANGUAGE_NAMES[sourceLanguage] ?: "Korean"
            
            val systemPrompt = """You are a professional translator. Translate the user's message from $sourceLangName to $targetLangName. 
                |Return ONLY the translation, no explanations or additional text.""".trimMargin()
            
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", text)
                })
            }
            
            val jsonBody = JSONObject().apply {
                put("model", "gpt-3.5-turbo")  // Faster than gpt-4o-mini!
                put("messages", messages)
                put("max_tokens", 100)   // Reduced for faster response
                put("temperature", 0.1)  // Lower = faster, more deterministic
            }
            
            val requestBody = jsonBody.toString()
                .toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url(CHAT_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()
            
            Log.d(TAG, "🔄 Translating: '$text' → $targetLangName")
            val startTime = System.currentTimeMillis()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            
            val latency = System.currentTimeMillis() - startTime
            Log.d(TAG, "⏱️ Translation latency: ${latency}ms")
            
            if (!response.isSuccessful) {
                Log.e(TAG, "❌ Translation failed: ${response.code} - $responseBody")
                return@withContext null
            }
            
            val json = JSONObject(responseBody ?: "")
            val choices = json.getJSONArray("choices")
            val translatedText = choices.getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
            
            Log.d(TAG, "✅ Translated: '$translatedText'")
            
            TranslationResult(
                originalText = text,
                translatedText = translatedText,
                sourceLang = sourceLanguage,
                targetLang = targetLanguage
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ Translation error: ${e.message}", e)
            null
        }
    }
    
    /**
     * Translate AND Speak in optimized sequence
     * Translation result returned immediately, TTS starts concurrently
     */
    suspend fun translateAndSpeak(
        text: String,
        targetLanguage: String,
        sourceLanguage: String,
        useBluetooth: Boolean = true,
        voice: String? = null,
        onTranslated: ((TranslationResult) -> Unit)? = null
    ): TranslationResult? = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        
        // Translate first
        val result = translate(text, targetLanguage, sourceLanguage)
        
        if (result != null) {
            val translateTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "⚡ Translation done in ${translateTime}ms, starting TTS...")
            
            // Notify caller immediately
            onTranslated?.invoke(result)
            
            // Start TTS concurrently
            speak(result.translatedText, targetLanguage, useBluetooth, voice)
            
            val totalTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "✅ Total pipeline: ${totalTime}ms")
        }
        
        result
    }
    
    /**
     * Speak text using OpenAI TTS
     * Uses full buffer download for stable playback (no crackling)
     */
    suspend fun speak(
        text: String,
        languageCode: String,
        useBluetooth: Boolean = true,
        voice: String? = null,  // Optional: override voice (e.g., from VoiceAnalyzer)
        onComplete: (() -> Unit)? = null  // Callback when playback completes
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) {
                Log.e(TAG, "❌ OpenAI API Key not configured")
                return@withContext false
            }
            
            val selectedVoice = voice ?: TTS_VOICES[languageCode] ?: "onyx"
            
            val jsonBody = JSONObject().apply {
                put("model", "tts-1")  // tts-1 for low latency
                put("input", text)
                put("voice", selectedVoice)
                put("response_format", "pcm")  // Raw PCM
                put("speed", 1.25)  // 25% faster for snappy response
            }
            
            val requestBody = jsonBody.toString()
                .toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url(TTS_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()
            
            Log.d(TAG, "🔊 TTS: '$text' (voice: $selectedVoice)")
            val startTime = System.currentTimeMillis()
            
            val response = client.newCall(request).execute()
            
            val latency = System.currentTimeMillis() - startTime
            Log.d(TAG, "⏱️ TTS response: ${latency}ms")
            
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                Log.e(TAG, "❌ TTS failed: ${response.code} - $errorBody")
                return@withContext false
            }
            
            // Full buffer download for stable playback
            val audioData = response.body?.bytes() ?: return@withContext false
            
            // Add to queue instead of playing directly
            queueAudio(audioData, 24000, useBluetooth, onComplete)
            
            Log.d(TAG, "✅ TTS queued: ${audioData.size} bytes")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ TTS error: ${e.message}", e)
            false
        }
    }
    
    /**
     * Translate and speak in one call (optimized pipeline)
     */
    suspend fun translateAndSpeak(
        text: String,
        targetLanguage: String,
        sourceLanguage: String,
        useBluetooth: Boolean = true
    ): TranslationResult? {
        val result = translate(text, targetLanguage, sourceLanguage)
        if (result != null) {
            speak(result.translatedText, targetLanguage, useBluetooth)
        }
        return result
    }
    
    /**
     * Stream audio playback - start playing as soon as first chunk arrives
     * This reduces perceived latency significantly
     */
    private suspend fun playAudioStream(
        inputStream: java.io.InputStream?,
        sampleRate: Int,
        useBluetooth: Boolean
    ) = withContext(Dispatchers.IO) {
        if (inputStream == null) return@withContext
        
        // Legacy streaming function - not used with queue system
            try {
                if (useBluetooth) {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                }
                
                // Calculate buffer size for streaming (100ms chunks)
                val bufferSize = maxOf(
                    AudioTrack.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    ),
                    sampleRate * 2 / 10  // ~100ms of audio
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
                    .setBufferSizeInBytes(bufferSize * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)  // STREAMING mode!
                    .build()
                
                currentAudioTrack = audioTrack
                audioTrack.play()  // Start playing immediately
                
                // Read and write chunks as they arrive
                val buffer = ByteArray(bufferSize)
                var bytesRead: Int
                var totalBytes = 0
                var firstChunk = true
                
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (firstChunk) {
                        Log.d(TAG, "▶️ First chunk playing!")
                        firstChunk = false
                    }
                    audioTrack.write(buffer, 0, bytesRead)
                    totalBytes += bytesRead
                }
                
                // Wait for remaining buffer to play out
                Thread.sleep(200)
                
                audioTrack.stop()
                audioTrack.release()
                currentAudioTrack = null
                
                Log.d(TAG, "✅ Streaming playback done ($totalBytes bytes)")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Audio streaming error: ${e.message}", e)
            } finally {
                inputStream.close()
        }
    }
    
    /**
     * Queue audio for seamless playback (no interruption)
     */
    private suspend fun queueAudio(
        pcmData: ByteArray,
        sampleRate: Int,
        useBluetooth: Boolean,
        onComplete: (() -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        queueMutex.withLock {
            audioQueue.add(AudioQueueItem(pcmData, sampleRate, useBluetooth, onComplete))
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
            
            // Initialize Bluetooth SCO once at the start
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
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                        bluetoothInitialized = true
                        Log.d(TAG, "🔵 Bluetooth SCO initialized")
                }
                
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
                                .setSampleRate(item.sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                        .setBufferSizeInBytes(item.audioData.size)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                
                currentAudioTrack = audioTrack
                
                    audioTrack.write(item.audioData, 0, item.audioData.size)
                audioTrack.play()
                
                // Wait for playback to complete
                    val durationMs = (item.audioData.size / 2) * 1000L / item.sampleRate
                    Thread.sleep(durationMs + 50) // Small buffer for seamless transition
                
                audioTrack.stop()
                audioTrack.release()
                currentAudioTrack = null
                
                    Log.d(TAG, "✅ Playback done (${durationMs}ms, queue remaining: ${queueMutex.withLock { audioQueue.size }})")
                    
                    // Call completion callback if provided
                    item.onComplete?.invoke()
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Audio playback error: ${e.message}", e)
                    // Call callback even on error
                    item.onComplete?.invoke()
                }
            }
            
            // Clean up Bluetooth SCO when queue is empty (but keep mic active)
            // Note: We don't stop SCO here to keep microphone active
        }
    }
    
    private suspend fun playAudio(
        pcmData: ByteArray,
        sampleRate: Int,
        useBluetooth: Boolean
    ) = withContext(Dispatchers.IO) {
        // Legacy function - now uses queue
        queueAudio(pcmData, sampleRate, useBluetooth)
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
