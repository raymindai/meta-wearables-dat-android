/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.landmarkguide.translation

import android.util.Base64
import android.util.Log
import com.meta.wearable.dat.externalsampleapps.landmarkguide.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Google Cloud Speech-to-Text API service
 * Converts audio to text with language detection
 */
class SpeechToTextService {
    
    companion object {
        private const val TAG = "SpeechToTextService"
        private const val STT_URL = "https://speech.googleapis.com/v1/speech:recognize"
        
        // Supported language codes for speech recognition
        val SUPPORTED_LANGUAGES = listOf(
            "ar-SA",  // Arabic (Saudi Arabia)
            "ar-EG",  // Arabic (Egypt)
            "ko-KR",  // Korean
            "en-US",  // English (US)
            "en-GB",  // English (UK)
            "es-ES",  // Spanish (Spain)
            "es-MX"   // Spanish (Mexico)
        )
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)  // Speech recognition can take time
        .build()
    
    private val apiKey = BuildConfig.GOOGLE_CLOUD_API_KEY
    
    /**
     * Recognize speech from audio data
     * @param audioData Raw audio data (PCM 16-bit, mono)
     * @param sampleRate Audio sample rate (e.g., 16000)
     * @param languageCode Primary language code (e.g., "en-US", "ko-KR", "ar-SA")
     * @param alternativeLanguages Additional language codes for multi-language support
     * @return Recognition result or null on error
     */
    suspend fun recognize(
        audioData: ByteArray,
        sampleRate: Int = 16000,
        languageCode: String = "en-US",
        alternativeLanguages: List<String> = emptyList()
    ): SpeechRecognitionResult? = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) {
                Log.e(TAG, "❌ Google Cloud API Key not configured")
                return@withContext null
            }
            
            // Encode audio to Base64
            val audioContent = Base64.encodeToString(audioData, Base64.NO_WRAP)
            
            val url = "$STT_URL?key=$apiKey"
            
            // Build request JSON
            val config = JSONObject().apply {
                put("encoding", "LINEAR16")
                put("sampleRateHertz", sampleRate)
                put("languageCode", languageCode)
                put("enableAutomaticPunctuation", true)
                
                // Add alternative languages for multi-language support
                if (alternativeLanguages.isNotEmpty()) {
                    put("alternativeLanguageCodes", JSONArray(alternativeLanguages))
                }
            }
            
            val audio = JSONObject().apply {
                put("content", audioContent)
            }
            
            val jsonBody = JSONObject().apply {
                put("config", config)
                put("audio", audio)
            }
            
            val requestBody = jsonBody.toString()
                .toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()
            
            Log.d(TAG, "🎤 Sending ${audioData.size} bytes to Speech-to-Text...")
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            
            if (!response.isSuccessful) {
                Log.e(TAG, "❌ Speech recognition failed: ${response.code} - $responseBody")
                return@withContext null
            }
            
            val json = JSONObject(responseBody ?: "")
            
            // Check if we have results
            if (!json.has("results")) {
                Log.d(TAG, "⚠️ No speech detected")
                return@withContext SpeechRecognitionResult(
                    transcript = "",
                    confidence = 0f,
                    languageCode = languageCode
                )
            }
            
            val results = json.getJSONArray("results")
            if (results.length() == 0) {
                return@withContext SpeechRecognitionResult(
                    transcript = "",
                    confidence = 0f,
                    languageCode = languageCode
                )
            }
            
            val result = results.getJSONObject(0)
            val alternatives = result.getJSONArray("alternatives")
            val alternative = alternatives.getJSONObject(0)
            
            val transcript = alternative.getString("transcript")
            val confidence = alternative.optDouble("confidence", 0.0).toFloat()
            val detectedLang = result.optString("languageCode", languageCode)
            
            Log.d(TAG, "✅ Recognized: '$transcript' (confidence: ${(confidence * 100).toInt()}%, lang: $detectedLang)")
            
            SpeechRecognitionResult(
                transcript = transcript,
                confidence = confidence,
                languageCode = detectedLang
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ Speech recognition error: ${e.message}", e)
            null
        }
    }
    
    /**
     * Recognize speech with automatic language detection
     * Tries Arabic, Korean, and English
     */
    suspend fun recognizeMultiLanguage(
        audioData: ByteArray,
        sampleRate: Int = 16000
    ): SpeechRecognitionResult? {
        return recognize(
            audioData = audioData,
            sampleRate = sampleRate,
            languageCode = "en-US",
            alternativeLanguages = listOf("ko-KR", "ar-SA")
        )
    }
}

/**
 * Result of speech recognition
 */
data class SpeechRecognitionResult(
    val transcript: String,
    val confidence: Float,
    val languageCode: String
)
