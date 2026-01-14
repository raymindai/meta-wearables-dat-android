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
import android.util.Base64
import android.util.Log
import com.meta.wearable.dat.externalsampleapps.landmarkguide.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Google Cloud Text-to-Speech API service
 * Supports Arabic, Korean, and English with natural voices
 */
class TextToSpeechService(private val context: Context) {
    
    companion object {
        private const val TAG = "TextToSpeechService"
        private const val TTS_URL = "https://texttospeech.googleapis.com/v1/text:synthesize"
        
        // Voice configurations per language (Male voices)
        val VOICE_CONFIG = mapOf(
            "ar" to VoiceConfig("ar-XA", "ar-XA-Wavenet-B", "MALE"),
            "ko" to VoiceConfig("ko-KR", "ko-KR-Wavenet-C", "MALE"),
            "en" to VoiceConfig("en-US", "en-US-Wavenet-D", "MALE"),
            "es" to VoiceConfig("es-ES", "es-ES-Wavenet-B", "MALE")
        )
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val apiKey = BuildConfig.GOOGLE_CLOUD_API_KEY
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    
    private var currentAudioTrack: AudioTrack? = null
    
    /**
     * Speak text in the specified language
     * @param text Text to speak
     * @param languageCode Language code (ar, ko, en)
     * @param useBluetooth If true, attempt to route to Bluetooth SCO
     */
    suspend fun speak(
        text: String,
        languageCode: String,
        useBluetooth: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) {
                Log.e(TAG, "❌ Google Cloud API Key not configured")
                return@withContext false
            }
            
            // Get voice config for language
            val voiceConfig = VOICE_CONFIG[languageCode] ?: VOICE_CONFIG["en"]!!
            
            val url = "$TTS_URL?key=$apiKey"
            
            // Build request
            val input = JSONObject().apply {
                put("text", text)
            }
            
            val voice = JSONObject().apply {
                put("languageCode", voiceConfig.languageCode)
                put("name", voiceConfig.voiceName)
                put("ssmlGender", voiceConfig.gender)
            }
            
            val audioConfig = JSONObject().apply {
                put("audioEncoding", "LINEAR16")
                put("sampleRateHertz", 24000)
                put("speakingRate", 1.0)
            }
            
            val jsonBody = JSONObject().apply {
                put("input", input)
                put("voice", voice)
                put("audioConfig", audioConfig)
            }
            
            val requestBody = jsonBody.toString()
                .toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()
            
            Log.d(TAG, "🔊 Synthesizing: '$text' in $languageCode")
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            
            if (!response.isSuccessful) {
                Log.e(TAG, "❌ TTS failed: ${response.code} - $responseBody")
                return@withContext false
            }
            
            val json = JSONObject(responseBody ?: "")
            val audioContent = json.getString("audioContent")
            
            // Decode Base64 audio
            val audioData = Base64.decode(audioContent, Base64.DEFAULT)
            
            // Skip WAV header (44 bytes) if present
            val pcmData = if (audioData.size > 44) {
                audioData.copyOfRange(44, audioData.size)
            } else {
                audioData
            }
            
            // Play audio
            playAudio(pcmData, 24000, useBluetooth)
            
            Log.d(TAG, "✅ Spoke: '$text' in ${VOICE_CONFIG[languageCode]?.languageCode ?: "en-US"}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ TTS error: ${e.message}", e)
            false
        }
    }
    
    private suspend fun playAudio(
        pcmData: ByteArray,
        sampleRate: Int,
        useBluetooth: Boolean
    ) = withContext(Dispatchers.IO) {
        try {
            // Stop any currently playing audio
            stop()
            
            // Configure audio routing
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
            Thread.sleep(durationMs + 200)
            
            audioTrack.stop()
            audioTrack.release()
            currentAudioTrack = null
            
            // NOTE: Don't reset audio routing here to keep microphone active
            // The calling code (LiveTranslationScreen) manages the SCO connection
            // if (useBluetooth) {
            //     audioManager.stopBluetoothSco()
            //     audioManager.mode = AudioManager.MODE_NORMAL
            // }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Audio playback error: ${e.message}", e)
        }
    }
    
    /**
     * Stop any currently playing audio
     */
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

/**
 * Voice configuration for a language
 */
data class VoiceConfig(
    val languageCode: String,
    val voiceName: String,
    val gender: String
)
