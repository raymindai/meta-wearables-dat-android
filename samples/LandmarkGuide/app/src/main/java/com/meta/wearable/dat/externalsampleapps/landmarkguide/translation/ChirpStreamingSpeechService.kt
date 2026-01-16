/*
 * Chirp 3 Streaming STT (Cloud Run)
 *
 * Streams raw PCM16 audio to a Cloud Run WebSocket service which bridges to
 * Google Speech-to-Text v2 StreamingRecognize (model=chirp_3).
 */
package com.meta.wearable.dat.externalsampleapps.landmarkguide.translation

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ChirpStreamingSpeechService {
    companion object {
        private const val TAG = "ChirpStreamingSTT"
        // Deployed Cloud Run service URL (public)
        private const val CLOUD_RUN_BASE_URL = "https://stt-streaming-e3dmnp5hdq-an.a.run.app"
        private const val WS_PATH = "/ws"
        private const val DEFAULT_LOCATION = "asia-northeast1"
        private const val DEFAULT_MODEL = "chirp_3"
        private const val DEFAULT_SAMPLE_RATE_HZ = 8000
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // streaming
        .build()

    private var ws: WebSocket? = null
    private var currentLanguage: String = "ko-KR"
    private var isOpen = false
    private var isReady = false

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _transcription = MutableStateFlow("")
    val transcription: StateFlow<String> = _transcription.asStateFlow()

    private val _partialTranscription = MutableStateFlow("")
    val partialTranscription: StateFlow<String> = _partialTranscription.asStateFlow()

    var onTranscript: ((String, Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onLanguageDetected: ((String) -> Unit)? = null

    fun startListening(languageCode: String) {
        // Expect languageCode is either "auto" or simple code -> map to BCP-47
        currentLanguage = when (languageCode) {
            "auto" -> "auto"
            else -> GoogleSpeechService.LANGUAGE_CODES[languageCode] ?: languageCode
        }

        _isListening.value = true
        _transcription.value = ""
        _partialTranscription.value = ""

        connect()
    }

    fun stopListening() {
        _isListening.value = false
        try {
            ws?.close(1000, "stop")
        } catch (_: Exception) {
        }
        ws = null
        isOpen = false
        isReady = false
    }

    fun clearTranscription() {
        _transcription.value = ""
        _partialTranscription.value = ""
    }

    fun sendAudio(data: ByteArray) {
        if (!_isListening.value) return
        if (!isOpen || !isReady) return
        if (data.isEmpty()) return
        ws?.send(ByteString.of(*data))
    }

    fun isConnected(): Boolean = _isListening.value && isOpen

    private fun connect() {
        if (ws != null) return

        val wsUrl = CLOUD_RUN_BASE_URL.replace("https://", "wss://") + WS_PATH
        val request = Request.Builder().url(wsUrl).build()

        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "✅ WS connected")
                isOpen = true
                isReady = false

                val languageCodes = JSONArray().apply {
                    if (currentLanguage == "auto") {
                        put("ko-KR")
                        put("en-US")
                        put("ar-SA")
                        put("es-ES")
                    } else {
                        put(currentLanguage)
                    }
                }

                val init = JSONObject().apply {
                    put("languageCodes", languageCodes)
                    put("location", DEFAULT_LOCATION)
                    put("model", DEFAULT_MODEL)
                    put("sampleRateHertz", DEFAULT_SAMPLE_RATE_HZ)
                }
                webSocket.send(init.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "ready" -> {
                            Log.d(TAG, "🎤 Ready")
                            isReady = true
                        }
                        "result" -> {
                            val t = json.optString("text", "")
                            val isFinal = json.optBoolean("isFinal", false)
                            val lang = json.optString("languageCode", "")
                            if (currentLanguage == "auto" && lang.isNotBlank()) {
                                onLanguageDetected?.invoke(lang)
                            }
                            if (t.isNotBlank()) {
                                if (isFinal) {
                                    _transcription.value = t
                                    _partialTranscription.value = ""
                                } else {
                                    _partialTranscription.value = t
                                }
                                onTranscript?.invoke(t, isFinal)
                            }
                        }
                        "error" -> {
                            val msg = json.optString("message", "Unknown error")
                            Log.e(TAG, "❌ Server error: $msg")
                            onError?.invoke(msg)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse WS message: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "❌ WS failure: ${t.message}")
                isOpen = false
                isReady = false
                _isListening.value = false
                onError?.invoke(t.message ?: "WebSocket failure")
                ws = null
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "🛑 WS closed: $code $reason")
                isOpen = false
                isReady = false
                ws = null
            }
        })
    }
}

