/*
 * Google Cloud Translation Service
 * Uses REST API for translation
 */

package com.meta.wearable.dat.externalsampleapps.landmarkguide.translation

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
 * Google Cloud Translation API v2
 * Simple REST-based translation service
 */
class GoogleTranslationService {
    
    companion object {
        private const val TAG = "GoogleTranslation"
        private const val API_URL = "https://translation.googleapis.com/language/translate/v2"
        
        // Language codes for Google Translate
        val LANGUAGE_CODES = mapOf(
            "ko" to "ko",
            "en" to "en",
            "ar" to "ar",
            "es" to "es"
        )
        
        // Language names for display
        val LANGUAGE_NAMES = mapOf(
            "ko" to "Korean",
            "en" to "English",
            "ar" to "Arabic",
            "es" to "Spanish"
        )
    }
    
    private val apiKey = BuildConfig.GOOGLE_CLOUD_API_KEY
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    
    data class TranslationResult(
        val originalText: String,
        val translatedText: String,
        val sourceLang: String,
        val targetLang: String
    )
    
    /**
     * Translate text using Google Cloud Translation API
     */
    suspend fun translate(
        text: String,
        targetLanguage: String,
        sourceLanguage: String
    ): TranslationResult? = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) {
                Log.e(TAG, "❌ Google Cloud API Key not configured")
                return@withContext null
            }
            
            val targetLang = LANGUAGE_CODES[targetLanguage] ?: targetLanguage
            val sourceLang = LANGUAGE_CODES[sourceLanguage] ?: sourceLanguage
            
            val jsonBody = JSONObject().apply {
                put("q", text)
                put("target", targetLang)
                put("source", sourceLang)
                put("format", "text")
            }
            
            val requestBody = jsonBody.toString()
                .toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("$API_URL?key=$apiKey")
                .post(requestBody)
                .build()
            
            Log.d(TAG, "🔄 Translating: '$text' → $targetLang")
            val startTime = System.currentTimeMillis()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            val latency = System.currentTimeMillis() - startTime
            
            if (!response.isSuccessful) {
                Log.e(TAG, "❌ API error: ${response.code} - $responseBody")
                return@withContext null
            }
            
            val json = JSONObject(responseBody ?: "{}")
            val data = json.optJSONObject("data")
            val translations = data?.optJSONArray("translations")
            
            if (translations != null && translations.length() > 0) {
                val translatedText = translations.getJSONObject(0)
                    .optString("translatedText", "")
                
                Log.d(TAG, "✅ Translated (${latency}ms): $translatedText")
                
                return@withContext TranslationResult(
                    originalText = text,
                    translatedText = translatedText,
                    sourceLang = sourceLang,
                    targetLang = targetLang
                )
            }
            
            null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Translation error: ${e.message}", e)
            null
        }
    }
}
