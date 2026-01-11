/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// VisionAnalyzer - Google Gemini Vision API Integration
//
// This class analyzes images using Google's Gemini API to identify
// landmarks, scenes, and provide descriptive guides in Korean.

package com.meta.wearable.dat.externalsampleapps.landmarkguide.ai

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.meta.wearable.dat.externalsampleapps.landmarkguide.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class VisionAnalyzer {
    companion object {
        private const val TAG = "VisionAnalyzer"
        private const val GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * Analyzes a scene from the given bitmap and returns a description
     */
    suspend fun analyzeScene(
        bitmap: Bitmap, 
        locationHint: String? = null,
        mode: GuideMode = GuideMode.TOUR
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val apiKey = BuildConfig.GEMINI_API_KEY
                if (apiKey.isBlank()) {
                    return@withContext Result.failure(Exception("Gemini API key not configured"))
                }

                val base64Image = bitmapToBase64(bitmap)
                val prompt = buildPrompt(locationHint, mode)

                val requestBody = buildRequestBody(base64Image, prompt)

                val request = Request.Builder()
                    .url("$GEMINI_API_URL?key=$apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    Log.e(TAG, "API call failed: ${response.code} - ${response.message} - $errorBody")
                    return@withContext Result.failure(Exception("API call failed: ${response.code}"))
                }

                val responseBody = response.body?.string()
                if (responseBody == null) {
                    return@withContext Result.failure(Exception("Empty response"))
                }

                Log.d(TAG, "Raw response: $responseBody")

                val geminiResponse = gson.fromJson(responseBody, GeminiResponse::class.java)
                val content = geminiResponse.candidates?.firstOrNull()
                    ?.content?.parts?.firstOrNull()?.text
                
                if (content.isNullOrBlank()) {
                    return@withContext Result.failure(Exception("No content in response"))
                }

                Log.d(TAG, "Analysis result: $content")
                Result.success(content)

            } catch (e: Exception) {
                Log.e(TAG, "Error analyzing scene", e)
                Result.failure(e)
            }
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        // Resize if too large to save bandwidth
        val scaledBitmap = if (bitmap.width > 1024 || bitmap.height > 1024) {
            val scale = 1024.0f / maxOf(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true
            )
        } else {
            bitmap
        }
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * Guide mode enum for different AI behaviors
     */
    enum class GuideMode {
        TOUR,      // Saudi Arabia landmarks and historic sites
        GENERAL,   // Describe everything visible
        TRANSLATE  // Translate Arabic text to English
    }

    private fun buildPrompt(locationHint: String?, mode: GuideMode = GuideMode.TOUR): String {
        val locationInfo = if (locationHint != null) {
            "\nCurrent location: $locationHint"
        } else {
            ""
        }

        return when (mode) {
            GuideMode.TOUR -> """You are an expert tour guide specializing in Saudi Arabia.$locationInfo

Analyze the scene the user is viewing through smart glasses.
Focus on identifying Saudi Arabian landmarks, historic sites, mosques, cultural locations, or interesting places.

If you recognize a landmark:
1. Name it and briefly explain its significance
2. Share an interesting historical or cultural fact

Important rules:
- Respond in English
- Keep response to 2-3 sentences (TTS output)
- If no notable Saudi landmarks visible, say "No notable landmarks visible."
- If uncertain, mention it's your best guess"""

            GuideMode.GENERAL -> """You are a visual assistant helping describe the world.$locationInfo

Describe what you see in the image:
- People, objects, signs, text
- Colors, actions, spatial layout
- Anything notable or interesting

Important rules:
- Respond in English
- Keep response to 2-3 sentences (TTS output)
- Be descriptive but concise
- Describe the most important elements first"""

            GuideMode.TRANSLATE -> """You are a translator specializing in Arabic to English translation.

Look at the image and find any Arabic text (signs, menus, documents, labels, etc.).

Your task:
1. Identify all Arabic text visible in the image
2. Translate each piece of text to English
3. Provide context if helpful (e.g., "The sign says..." or "The menu reads...")

Important rules:
- Respond in English only
- Keep response to 2-3 sentences (TTS output)
- If no Arabic text is visible, say "No Arabic text detected."
- Read translations naturally for TTS
- If text is partially visible or unclear, mention that"""
        }
    }

    private fun buildRequestBody(base64Image: String, prompt: String): String {
        val request = GeminiRequest(
            contents = listOf(
                GeminiContent(
                    parts = listOf(
                        GeminiPart(text = prompt),
                        GeminiPart(
                            inlineData = InlineData(
                                mimeType = "image/jpeg",
                                data = base64Image
                            )
                        )
                    )
                )
            ),
            generationConfig = GenerationConfig(
                maxOutputTokens = 500,
                temperature = 0.4f
            )
        )
        return gson.toJson(request)
    }
}

// Gemini Request data classes
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val generationConfig: GenerationConfig? = null
)

data class GeminiContent(
    val parts: List<GeminiPart>
)

data class GeminiPart(
    val text: String? = null,
    @SerializedName("inline_data") val inlineData: InlineData? = null
)

data class InlineData(
    @SerializedName("mime_type") val mimeType: String,
    val data: String
)

data class GenerationConfig(
    @SerializedName("max_output_tokens") val maxOutputTokens: Int,
    val temperature: Float
)

// Gemini Response data classes
data class GeminiResponse(
    val candidates: List<GeminiCandidate>?
)

data class GeminiCandidate(
    val content: GeminiContentResponse?
)

data class GeminiContentResponse(
    val parts: List<GeminiPartResponse>?
)

data class GeminiPartResponse(
    val text: String?
)
