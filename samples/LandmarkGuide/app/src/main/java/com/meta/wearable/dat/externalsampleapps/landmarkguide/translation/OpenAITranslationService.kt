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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
        
        // Language display names for prompts
        val LANGUAGE_NAMES = mapOf(
            "ko" to "Korean",
            "en" to "English", 
            "ar" to "Arabic",
            "es" to "Spanish"
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
    
    // Mutex for sequential TTS playback (no interruption)
    private val playbackMutex = Mutex()
    
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
                put("model", "gpt-4o-mini")
                put("messages", messages)
                put("max_tokens", 150)   // Reduced for faster response
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
     * Speak text using OpenAI TTS with STREAMING for low latency
     * Plays audio chunks as they arrive instead of waiting for full response
     */
    suspend fun speak(
        text: String,
        languageCode: String,
        useBluetooth: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) {
                Log.e(TAG, "❌ OpenAI API Key not configured")
                return@withContext false
            }
            
            val voice = TTS_VOICES[languageCode] ?: "onyx"
            
            val jsonBody = JSONObject().apply {
                put("model", "tts-1")  // Use tts-1 for low latency
                put("input", text)
                put("voice", voice)
                put("response_format", "pcm")  // Raw PCM for direct streaming playback
                put("speed", 1.0)
            }
            
            val requestBody = jsonBody.toString()
                .toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url(TTS_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()
            
            Log.d(TAG, "🔊 TTS: '$text' (voice: $voice)")
            val startTime = System.currentTimeMillis()
            
            val response = client.newCall(request).execute()
            
            val latency = System.currentTimeMillis() - startTime
            Log.d(TAG, "⏱️ TTS response: ${latency}ms")
            
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                Log.e(TAG, "❌ TTS failed: ${response.code} - $errorBody")
                return@withContext false
            }
            
            // Get full PCM audio data (non-streaming, but reliable)
            val audioData = response.body?.bytes() ?: return@withContext false
            
            // Play audio
            playAudio(audioData, 24000, useBluetooth)
            
            Log.d(TAG, "✅ TTS played: ${audioData.size} bytes")
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
        
        // Use mutex to queue playback
        playbackMutex.withLock {
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
    }
    
    private suspend fun playAudio(
        pcmData: ByteArray,
        sampleRate: Int,
        useBluetooth: Boolean
    ) = withContext(Dispatchers.IO) {
        // Use mutex to queue playback (wait for previous to finish)
        playbackMutex.withLock {
            try {
                if (useBluetooth) {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
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
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(pcmData.size)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                
                currentAudioTrack = audioTrack
                
                audioTrack.write(pcmData, 0, pcmData.size)
                audioTrack.play()
                
                // Wait for playback to complete
                val durationMs = (pcmData.size / 2) * 1000L / sampleRate
                Thread.sleep(durationMs + 100)
                
                audioTrack.stop()
                audioTrack.release()
                currentAudioTrack = null
                
                Log.d(TAG, "✅ Playback done (${durationMs}ms)")
                
                // Don't stop SCO to keep mic active
            } catch (e: Exception) {
                Log.e(TAG, "❌ Audio playback error: ${e.message}", e)
            }
        }
    }
    
    fun stop() {
        try {
            currentAudioTrack?.stop()
            currentAudioTrack?.release()
            currentAudioTrack = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio: ${e.message}")
        }
    }
}
