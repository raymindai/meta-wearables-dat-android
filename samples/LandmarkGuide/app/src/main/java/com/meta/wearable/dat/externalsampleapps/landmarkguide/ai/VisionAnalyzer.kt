/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// VisionAnalyzer - OpenAI GPT-4o Vision API Integration
//
// This class analyzes images using OpenAI's GPT-4o Vision API to identify
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
        private const val OPENAI_API_URL = "https://api.openai.com/v1/chat/completions"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * Analyzes a scene from the given bitmap and returns a Korean description
     */
    suspend fun analyzeScene(bitmap: Bitmap, locationHint: String? = null): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val apiKey = BuildConfig.OPENAI_API_KEY
                if (apiKey.isBlank()) {
                    return@withContext Result.failure(Exception("OpenAI API key not configured"))
                }

                val base64Image = bitmapToBase64(bitmap)
                val prompt = buildPrompt(locationHint)

                val requestBody = buildRequestBody(base64Image, prompt)

                val request = Request.Builder()
                    .url(OPENAI_API_URL)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    Log.e(TAG, "API call failed: ${response.code} - ${response.message}")
                    return@withContext Result.failure(Exception("API call failed: ${response.code}"))
                }

                val responseBody = response.body?.string()
                if (responseBody == null) {
                    return@withContext Result.failure(Exception("Empty response"))
                }

                val chatResponse = gson.fromJson(responseBody, ChatCompletionResponse::class.java)
                val content = chatResponse.choices?.firstOrNull()?.message?.content
                
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

    private fun buildPrompt(locationHint: String?): String {
        val locationInfo = if (locationHint != null) {
            "\n현재 위치: $locationHint"
        } else {
            ""
        }

        return """당신은 전문 여행 가이드입니다.$locationInfo

사용자가 스마트 안경을 통해 보고 있는 장면을 분석하세요.
만약 유적지, 랜드마크, 건물, 또는 흥미로운 장소가 보인다면:
1. 그것이 무엇인지 간단히 설명하세요
2. 역사적 배경이나 흥미로운 사실을 한두 가지 알려주세요

중요한 규칙:
- 한국어로 답변하세요
- 2-3문장으로 간결하게 답변하세요 (TTS로 읽힐 예정)
- 일반적인 거리나 자연 풍경만 보인다면 "특별한 랜드마크가 보이지 않습니다"라고 답변하세요
- 확실하지 않은 정보는 추측이라고 말하세요"""
    }

    private fun buildRequestBody(base64Image: String, prompt: String): String {
        val request = ChatCompletionRequest(
            model = "gpt-4o",
            messages = listOf(
                Message(
                    role = "user",
                    content = listOf(
                        ContentPart(type = "text", text = prompt),
                        ContentPart(
                            type = "image_url",
                            imageUrl = ImageUrl(url = "data:image/jpeg;base64,$base64Image")
                        )
                    )
                )
            ),
            maxTokens = 300
        )
        return gson.toJson(request)
    }
}

// Request/Response data classes
data class ChatCompletionRequest(
    val model: String,
    val messages: List<Message>,
    @SerializedName("max_tokens") val maxTokens: Int
)

data class Message(
    val role: String,
    val content: List<ContentPart>
)

data class ContentPart(
    val type: String,
    val text: String? = null,
    @SerializedName("image_url") val imageUrl: ImageUrl? = null
)

data class ImageUrl(val url: String)

data class ChatCompletionResponse(
    val choices: List<Choice>?
)

data class Choice(
    val message: ResponseMessage?
)

data class ResponseMessage(
    val content: String?
)
