/*
 * Vosk Local STT Service
 * Offline speech recognition with ultra-low latency (~50-100ms)
 */

package com.meta.wearable.dat.externalsampleapps.landmarkguide.translation

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import org.json.JSONObject
import java.io.IOException

/**
 * Vosk Local STT - Ultra-low latency offline speech recognition
 * 
 * Models required in assets:
 * - vosk-model-small-ko (Korean) ~50MB
 * - vosk-model-small-en-us (English) ~40MB
 * - vosk-model-ar (Arabic) ~50MB
 * - vosk-model-small-es (Spanish) ~40MB
 */
class VoskSpeechService(private val context: Context) {
    
    companion object {
        private const val TAG = "VoskSTT"
        private const val SAMPLE_RATE = 16000f
        
        // Model names in assets
        // Currently using the existing English model, can add more later
        val MODEL_PATHS = mapOf(
            "ko" to "model",   // Will use English model for now (needs Korean model)
            "en" to "model",   // English model in assets/model
            "ar" to "model",   // Will use English model for now (needs Arabic model)
            "es" to "model"    // Will use English model for now (needs Spanish model)
        )
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var currentLanguage: String = "en"
    
    // State
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()
    
    private val _transcription = MutableStateFlow("")
    val transcription: StateFlow<String> = _transcription.asStateFlow()
    
    private val _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded: StateFlow<Boolean> = _isModelLoaded.asStateFlow()
    
    // Callbacks
    var onTranscript: ((String, Boolean) -> Unit)? = null
    var onPartialResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    
    /**
     * Initialize Vosk model for a language
     * Must be called before startListening
     */
    suspend fun initModel(languageCode: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val lang = if (languageCode == "auto") "en" else languageCode
            
            if (currentLanguage == lang && model != null) {
                Log.d(TAG, "✅ Model already loaded for $lang")
                return@withContext true
            }
            
            // Close previous model
            recognizer?.close()
            model?.close()
            
            val modelPath = MODEL_PATHS[lang]
            if (modelPath == null) {
                Log.e(TAG, "❌ No model for language: $lang")
                onError?.invoke("No Vosk model for $lang")
                return@withContext false
            }
            
            Log.d(TAG, "📦 Loading Vosk model: $modelPath")
            val startTime = System.currentTimeMillis()
            
            // Try to load from assets using StorageService
            try {
                StorageService.unpack(context, modelPath, "model",
                    { loadedModel ->
                        model = loadedModel
                        recognizer = Recognizer(loadedModel, SAMPLE_RATE)
                        currentLanguage = lang
                        _isModelLoaded.value = true
                        
                        val loadTime = System.currentTimeMillis() - startTime
                        Log.d(TAG, "✅ Vosk model loaded in ${loadTime}ms")
                    },
                    { exception ->
                        Log.e(TAG, "❌ Failed to load model: ${exception.message}")
                        onError?.invoke("Failed to load Vosk model: ${exception.message}")
                        _isModelLoaded.value = false
                    }
                )
                
                // Wait for model to load (with timeout)
                var waitCount = 0
                while (!_isModelLoaded.value && waitCount < 100) {
                    kotlinx.coroutines.delay(100)
                    waitCount++
                }
                
                return@withContext _isModelLoaded.value
                
            } catch (e: IOException) {
                Log.e(TAG, "❌ Model not found in assets: $modelPath")
                onError?.invoke("Vosk model not found: $modelPath")
                return@withContext false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Model init error: ${e.message}", e)
            onError?.invoke("Vosk init error: ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Start listening (model must be loaded first)
     */
    fun startListening(languageCode: String) {
        if (!_isModelLoaded.value) {
            Log.e(TAG, "❌ Model not loaded, call initModel first")
            onError?.invoke("Vosk model not loaded")
            return
        }
        
        _isListening.value = true
        _transcription.value = ""
        Log.d(TAG, "🎤 Vosk listening started ($currentLanguage)")
    }
    
    /**
     * Process audio data - call this from audio capture callback
     * Returns partial/final results via callbacks
     */
    fun processAudio(audioData: ByteArray): String? {
        if (!_isListening.value || recognizer == null) return null
        
        try {
            // Convert ByteArray to short array for Vosk
            val shorts = ShortArray(audioData.size / 2)
            for (i in shorts.indices) {
                shorts[i] = ((audioData[i * 2].toInt() and 0xFF) or 
                           (audioData[i * 2 + 1].toInt() shl 8)).toShort()
            }
            
            // Feed to recognizer
            val isFinal = recognizer!!.acceptWaveForm(shorts, shorts.size)
            
            if (isFinal) {
                // Final result
                val result = recognizer!!.result
                val json = JSONObject(result)
                val text = json.optString("text", "").trim()
                
                if (text.isNotBlank()) {
                    Log.d(TAG, "📝 Final: $text")
                    _transcription.value = text
                    onTranscript?.invoke(text, true)
                    return text
                }
            } else {
                // Partial result (for real-time display)
                val partial = recognizer!!.partialResult
                val json = JSONObject(partial)
                val text = json.optString("partial", "").trim()
                
                if (text.isNotBlank()) {
                    onPartialResult?.invoke(text)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Process error: ${e.message}")
        }
        
        return null
    }
    
    /**
     * Stop listening
     */
    fun stopListening() {
        _isListening.value = false
        
        // Get final result
        recognizer?.let {
            val result = it.finalResult
            val json = JSONObject(result)
            val text = json.optString("text", "").trim()
            if (text.isNotBlank()) {
                Log.d(TAG, "📝 Final on stop: $text")
                _transcription.value = text
                onTranscript?.invoke(text, true)
            }
        }
        
        Log.d(TAG, "🛑 Vosk stopped")
    }
    
    fun clearTranscription() {
        _transcription.value = ""
        recognizer?.reset()
    }
    
    fun isConnected(): Boolean = _isListening.value && _isModelLoaded.value
    
    fun release() {
        stopListening()
        recognizer?.close()
        model?.close()
        recognizer = null
        model = null
        _isModelLoaded.value = false
    }
}
