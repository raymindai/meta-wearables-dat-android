/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
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
 * Google Cloud Translation API service
 * Supports bidirectional translation between Arabic, Korean, and English
 */
class TranslationService {
    
    companion object {
        private const val TAG = "TranslationService"
        private const val TRANSLATE_URL = "https://translation.googleapis.com/language/translate/v2"
        
        // Language codes
        const val LANG_ARABIC = "ar"
        const val LANG_KOREAN = "ko"
        const val LANG_ENGLISH = "en"
        const val LANG_SPANISH = "es"
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val apiKey = BuildConfig.GOOGLE_CLOUD_API_KEY
    
    /**
     * Translate text from source language to target language
     * @param text Text to translate
     * @param targetLang Target language code (ar, ko, en)
     * @param sourceLang Source language code (optional, auto-detect if null)
     * @return Translated text or null on error
     */
    suspend fun translate(
        text: String,
        targetLang: String,
        sourceLang: String? = null
    ): TranslationResult? = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) {
                Log.e(TAG, "❌ Google Cloud API Key not configured")
                return@withContext null
            }
            
            val url = "$TRANSLATE_URL?key=$apiKey"
            
            val jsonBody = JSONObject().apply {
                put("q", text)
                put("target", targetLang)
                if (sourceLang != null) {
                    put("source", sourceLang)
                }
                put("format", "text")
            }
            
            val requestBody = jsonBody.toString()
                .toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            
            if (!response.isSuccessful) {
                Log.e(TAG, "❌ Translation failed: ${response.code} - $responseBody")
                return@withContext null
            }
            
            val json = JSONObject(responseBody ?: "")
            val data = json.getJSONObject("data")
            val translations = data.getJSONArray("translations")
            val translation = translations.getJSONObject(0)
            
            val translatedText = translation.getString("translatedText")
            val detectedSourceLang = translation.optString("detectedSourceLanguage", sourceLang ?: "")
            
            Log.d(TAG, "✅ Translated: '$text' → '$translatedText' ($detectedSourceLang → $targetLang)")
            
            TranslationResult(
                originalText = text,
                translatedText = translatedText,
                sourceLang = detectedSourceLang,
                targetLang = targetLang
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ Translation error: ${e.message}", e)
            null
        }
    }
    
    /**
     * Detect the language of the given text
     */
    suspend fun detectLanguage(text: String): String? = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) return@withContext null
            
            val url = "https://translation.googleapis.com/language/translate/v2/detect?key=$apiKey"
            
            val jsonBody = JSONObject().apply {
                put("q", text)
            }
            
            val requestBody = jsonBody.toString()
                .toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            
            if (!response.isSuccessful) return@withContext null
            
            val json = JSONObject(responseBody ?: "")
            val data = json.getJSONObject("data")
            val detections = data.getJSONArray("detections")
            val detection = detections.getJSONArray(0).getJSONObject(0)
            
            val language = detection.getString("language")
            Log.d(TAG, "🔍 Detected language: $language for '$text'")
            language
        } catch (e: Exception) {
            Log.e(TAG, "❌ Language detection error: ${e.message}")
            null
        }
    }
    
    /**
     * Get display name for language code
     */
    fun getLanguageName(langCode: String): String = when (langCode) {
        LANG_ARABIC -> "العربية"
        LANG_KOREAN -> "한국어"
        LANG_ENGLISH -> "English"
        LANG_SPANISH -> "Español"
        else -> langCode
    }
}

/**
 * Result of a translation operation
 */
data class TranslationResult(
    val originalText: String,
    val translatedText: String,
    val sourceLang: String,
    val targetLang: String
)
