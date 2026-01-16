/*
 * Soniox Streaming STT (WebSocket)
 *
 * Streams raw PCM16 audio to Soniox real-time STT WebSocket API.
 * Designed to be a drop-in replacement for ChirpStreamingSpeechService in MajlisScreen.
 */
package com.meta.wearable.dat.externalsampleapps.landmarkguide.translation

import android.util.Log
import com.meta.wearable.dat.externalsampleapps.landmarkguide.BuildConfig
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

class SonioxStreamingSpeechService {
    companion object {
        private const val TAG = "SonioxStreamingSTT"
        private const val SONIOX_WS_URL = "wss://stt-rt.soniox.com/transcribe-websocket"
        private const val DEFAULT_MODEL = "stt-rt-v3"  // Updated to v3 for language_hints_strict support
        private const val DEFAULT_SAMPLE_RATE_HZ = 8000 // matches BluetoothScoAudioCapture
        private const val DEFAULT_NUM_CHANNELS = 1
    }

    private val apiKey = BuildConfig.SONIOX_API_KEY

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // streaming
        .build()

    private var ws: WebSocket? = null
    private var isOpen = false

    private var currentLanguage: String = "en"
    
    // Track processed final text to avoid duplicates (Soniox re-sends final tokens)
    private var lastFinalText = ""

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _transcription = MutableStateFlow("")
    val transcription: StateFlow<String> = _transcription.asStateFlow()

    private val _partialTranscription = MutableStateFlow("")
    val partialTranscription: StateFlow<String> = _partialTranscription.asStateFlow()

    var onTranscript: ((String, Boolean, String?, String?) -> Unit)? = null  // (text, isFinal, speaker, detectedLanguage)
    var onError: ((String) -> Unit)? = null
    var onLanguageDetected: ((String) -> Unit)? = null

    fun startListening(languageCode: String) {
        if (apiKey.isBlank()) {
            Log.e(TAG, "❌ SONIOX_API_KEY not configured in local.properties")
            onError?.invoke("Soniox API key not configured (SONIOX_API_KEY)")
            return
        }
        currentLanguage = languageCode
        Log.d(TAG, "🎤 startListening(languageCode=$languageCode)")

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
    }

    fun clearTranscription() {
        _transcription.value = ""
        _partialTranscription.value = ""
        lastFinalText = ""  // Reset duplicate tracking
    }

    fun sendAudio(data: ByteArray) {
        if (!_isListening.value) return
        if (!isOpen) return
        if (data.isEmpty()) return
        ws?.send(ByteString.of(*data))
    }

    fun isConnected(): Boolean = _isListening.value && isOpen

    private fun connect() {
        if (ws != null) {
            Log.w(TAG, "⚠️ Already connecting/connected, skipping")
            return
        }

        val request = Request.Builder().url(SONIOX_WS_URL).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "✅ WS connected")
                isOpen = true

                // Build start/config message.
                // Soniox expects snake_case fields directly in the root JSON (no nested "config").
                val start = JSONObject().apply {
                    put("api_key", apiKey)
                    put("model", DEFAULT_MODEL)
                    put("audio_format", "s16le")
                    put("sample_rate", DEFAULT_SAMPLE_RATE_HZ)
                    put("num_channels", DEFAULT_NUM_CHANNELS)
                    put("enable_endpoint_detection", true)
                    put("enable_speaker_diarization", false)  // Disable speaker diarization

                    // Language behavior (per Soniox docs: https://soniox.com/docs/stt/concepts/language-restrictions):
                    // - If "auto": enable language identification (no hints, no restriction)
                    // - Else: use language_hints_strict to restrict recognition to selected language only
                    if (currentLanguage == "auto") {
                        put("enable_language_identification", true)
                        // No language_hints for auto mode - let model detect automatically
                    } else {
                        put("enable_language_identification", false)
                        // Convert to ISO language codes for language_hints
                        val isoCode = when (currentLanguage.lowercase()) {
                            "ko", "korean" -> "ko"
                            "en", "english" -> "en"
                            "ar", "arabic" -> "ar"
                            "es", "spanish" -> "es"
                            else -> currentLanguage.lowercase()
                        }
                        // Use language_hints_strict to restrict recognition to selected language only
                        // This strongly prefers output only in the specified language
                        // Best results with single language (per Soniox docs)
                        put("language_hints", JSONArray().apply { put(isoCode) })
                        put("language_hints_strict", true)  // Enable language restriction
                        Log.d(TAG, "🔒 Using language_hints_strict: [$isoCode] (restricted to this language only)")
                    }
                }

                Log.d(TAG, "➡️ sending start (model=$DEFAULT_MODEL, sampleRate=$DEFAULT_SAMPLE_RATE_HZ, lang=$currentLanguage)")
                Log.d(TAG, "📤 Start message: ${start.toString()}")
                // Ensure we send as TEXT message (not binary)
                webSocket.send(start.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    
                    // Check for error response (per Soniox docs)
                    if (json.has("error_code")) {
                        val errorCode = json.optInt("error_code", 0)
                        val errorMsg = json.optString("error_message", "Unknown error")
                        Log.e(TAG, "❌ Soniox error ($errorCode): $errorMsg")
                        onError?.invoke("Soniox error $errorCode: $errorMsg")
                        return
                    }
                    
                    // Check for finished response (per Soniox docs)
                    if (json.optBoolean("finished", false)) {
                        Log.d(TAG, "✅ Stream finished")
                        // Process any remaining tokens before closing
                        processTokens(json)
                        return
                    }
                    
                    // Process tokens array (standard Soniox response format)
                    processTokens(json)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse WS message: ${e.message}", e)
                }
            }
            
            private fun processTokens(json: JSONObject) {
                val tokens = json.optJSONArray("tokens") ?: return
                
                if (tokens.length() == 0) return
                
                val sb = StringBuilder()
                var hasFinal = false
                var hasEndToken = false
                var detectedLang: String? = null
                var currentSpeaker: String? = null  // Track speaker for this batch of tokens
                
                // Process all tokens in this response
                for (i in 0 until tokens.length()) {
                    val token = tokens.optJSONObject(i) ?: continue
                    val originalText = token.optString("text", "")
                    val text = originalText.trim()
                    val isFinal = token.optBoolean("is_final", false)
                    val lang = token.optString("language", "")
                    val speaker = token.optString("speaker", "")  // Extract speaker ID from token
                    
                    // Use the first non-empty speaker ID we encounter
                    if (speaker.isNotBlank() && currentSpeaker == null) {
                        currentSpeaker = speaker
                        Log.d(TAG, "👤 Speaker detected: $speaker")
                    }
                    
                    // Check for <end> token (per Soniox endpoint detection docs)
                    if (text == "<end>") {
                        hasEndToken = true
                        hasFinal = true  // <end> is always final
                        Log.d(TAG, "🔚 Endpoint detected: <end> token received")
                        continue  // Don't include <end> in text, but use it as signal
                    }
                    
                    if (text.isNotEmpty()) {
                        // Preserve spaces that Soniox already included in the token
                        // If token starts/ends with space, it's intentional (e.g., " word " or "word ")
                        val hasLeadingSpace = originalText.startsWith(" ")
                        val hasTrailingSpace = originalText.endsWith(" ")
                        
                        // Determine if we need spaces between tokens based on language
                        // Korean, Chinese, Japanese: no spaces between words (characters are tokens)
                        // Arabic, English, Spanish: spaces between words
                        val needsSpaces = when {
                            detectedLang != null -> {
                                val lang = detectedLang.lowercase()
                                !lang.startsWith("ko") && 
                                !lang.startsWith("zh") && 
                                !lang.startsWith("ja")
                            }
                            currentLanguage != "auto" -> {
                                val lang = currentLanguage.lowercase()
                                !lang.startsWith("ko") && 
                                !lang.startsWith("zh") && 
                                !lang.startsWith("ja")
                            }
                            else -> true  // Default to spaces for unknown languages
                        }
                        
                        // Add leading space if token has it OR if language needs spaces and we're not at start
                        if (hasLeadingSpace || (needsSpaces && sb.isNotEmpty() && !sb.endsWith(" ") && 
                            !sb.endsWith(".") && !sb.endsWith("?") && !sb.endsWith("!") && !sb.endsWith(","))) {
                            sb.append(" ")
                        }
                        
                        // Append the trimmed text
                        sb.append(text)
                        
                        // Preserve trailing space if token had it
                        if (hasTrailingSpace) {
                            sb.append(" ")
                        }
                    }
                    
                    if (isFinal) hasFinal = true
                    if (lang.isNotBlank() && detectedLang == null) {
                        detectedLang = lang
                    }
                }
                
                val fullText = sb.toString().trim()
                if (fullText.isBlank() && !hasEndToken) return
                
                // Language detection callback - ALWAYS call if language is detected (not just for auto mode)
                // This allows the UI to filter out non-selected languages
                if (detectedLang != null) {
                    onLanguageDetected?.invoke(detectedLang)
                    Log.d(TAG, "🌐 Language detected: $detectedLang")
                }
                
                // Update transcription state
                if (hasFinal || hasEndToken) {
                    // Final tokens: Soniox re-sends final tokens, so check for duplicates
                    // Only process if this is new final text or <end> token signals completion
                    if (hasEndToken || fullText != lastFinalText) {
                        if (hasEndToken && fullText.isNotBlank()) {
                            // <end> token received: finalize current text (per Soniox endpoint detection docs)
                            val current = _transcription.value
                            _transcription.value = if (current.isBlank()) fullText else "$current $fullText"
                            _partialTranscription.value = ""
                            lastFinalText = fullText
                            Log.d(TAG, "📝 Final (endpoint): $fullText [Speaker: $currentSpeaker, Lang: $detectedLang]")
                            onTranscript?.invoke(fullText, true, currentSpeaker, detectedLang)
                        } else if (fullText.isNotBlank() && fullText != lastFinalText) {
                            // New final tokens (not duplicate - Soniox re-sends final tokens)
                            val current = _transcription.value
                            _transcription.value = if (current.isBlank()) fullText else "$current $fullText"
                            _partialTranscription.value = ""
                            lastFinalText = fullText
                            Log.d(TAG, "📝 Final: $fullText [Speaker: $currentSpeaker, Lang: $detectedLang]")
                            onTranscript?.invoke(fullText, true, currentSpeaker, detectedLang)
                        }
                    }
                } else {
                    // Partial tokens: update partial transcription
                    _partialTranscription.value = fullText
                    Log.d(TAG, "💭 Partial: $fullText [Speaker: $currentSpeaker, Lang: $detectedLang]")
                    onTranscript?.invoke(fullText, false, currentSpeaker, detectedLang)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val respInfo = response?.let { "http=${it.code} ${it.message}" } ?: "http=<none>"
                Log.e(TAG, "❌ WS failure: ${t.message} ($respInfo)")
                isOpen = false
                _isListening.value = false
                onError?.invoke(t.message ?: "WebSocket failure")
                ws = null
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "🛑 WS closed: $code $reason")
                isOpen = false
                ws = null
            }
        })
    }

}

