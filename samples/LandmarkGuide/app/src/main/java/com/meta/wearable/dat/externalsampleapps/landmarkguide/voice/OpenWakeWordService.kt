/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 * 
 * OpenWakeWord Service - Offline Wake Word Detection using ONNX Runtime
 * 
 * Uses pre-trained OpenWakeWord models for on-device wake word detection.
 * Models: melspectrogram.onnx + embedding_model.onnx + hey_mycroft.onnx
 */

package com.meta.wearable.dat.externalsampleapps.landmarkguide.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.meta.wearable.dat.externalsampleapps.landmarkguide.audio.BluetoothScoAudioCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.nio.FloatBuffer
import kotlin.math.ln
import kotlin.math.max

/**
 * OpenWakeWord Service - Offline wake word detection using ONNX Runtime.
 * 
 * Uses 3 models in sequence:
 * 1. melspectrogram.onnx - Converts audio to mel spectrogram
 * 2. embedding_model.onnx - Extracts audio embeddings
 * 3. hey_mycroft.onnx - Classifies wake word (replaceable with custom model)
 */
class OpenWakeWordService(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit,
    private val onStatusChange: ((String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "OpenWakeWord"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE = 1280  // 80ms at 16kHz (OpenWakeWord default)
        private const val DETECTION_THRESHOLD = 0.5f
        private const val DEBOUNCE_MS = 3000L
    }

    private var ortEnv: OrtEnvironment? = null
    private var melSession: OrtSession? = null
    private var embeddingSession: OrtSession? = null
    private var wakeWordSession: OrtSession? = null
    
    private var audioCapture: BluetoothScoAudioCapture? = null
    private var isListening = false
    private var lastDetectionTime = 0L
    private var processingJob: Job? = null
    
    // Audio buffer for accumulating samples
    private val audioBuffer = mutableListOf<Float>()
    private val embeddingBuffer = mutableListOf<FloatArray>()

    /**
     * Initialize ONNX models from assets.
     */
    fun initialize(): Boolean {
        return try {
            Log.d(TAG, "🔄 Loading OpenWakeWord ONNX models...")
            onStatusChange?.invoke("Loading models...")
            
            ortEnv = OrtEnvironment.getEnvironment()
            
            // Load models from assets
            melSession = loadModelFromAssets("openwakeword/melspectrogram.onnx")
            embeddingSession = loadModelFromAssets("openwakeword/embedding_model.onnx")
            wakeWordSession = loadModelFromAssets("openwakeword/hey_mycroft.onnx")
            
            Log.d(TAG, "✅ OpenWakeWord models loaded successfully")
            onStatusChange?.invoke("Models ready")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load models: ${e.message}", e)
            onStatusChange?.invoke("Model load failed")
            false
        }
    }

    private fun loadModelFromAssets(filename: String): OrtSession {
        val modelBytes = context.assets.open(filename).use { it.readBytes() }
        return ortEnv!!.createSession(modelBytes)
    }

    /**
     * Start listening for wake word.
     */
    fun startListening() {
        if (isListening) {
            Log.d(TAG, "Already listening")
            return
        }
        
        if (ortEnv == null) {
            Log.e(TAG, "Models not initialized! Call initialize() first.")
            return
        }

        Log.d(TAG, "🎧 Starting OpenWakeWord listening...")
        isListening = true
        onStatusChange?.invoke("🎧 LISTENING")
        audioBuffer.clear()
        embeddingBuffer.clear()

        // Start audio capture
        audioCapture = BluetoothScoAudioCapture(context)
        audioCapture?.setListener(object : BluetoothScoAudioCapture.AudioCaptureListener {
            override fun onAudioData(data: ByteArray, size: Int) {
                processAudioData(data, size)
            }
            override fun onScoConnected() {
                Log.d(TAG, "✅ SCO connected - using glasses mic")
                onStatusChange?.invoke("🎧 LISTENING (Glasses)")
            }
            override fun onScoDisconnected() {
                Log.d(TAG, "⚠️ SCO disconnected")
            }
            override fun onError(message: String) {
                Log.e(TAG, "Audio error: $message")
            }
        })
        audioCapture?.startRecording()
    }

    /**
     * Process incoming audio data through the OpenWakeWord pipeline.
     */
    private fun processAudioData(data: ByteArray, size: Int) {
        if (!isListening) return
        
        // Convert PCM16 to float samples
        for (i in 0 until size step 2) {
            if (i + 1 < size) {
                val sample = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
                val floatSample = sample.toShort().toFloat() / 32768.0f
                audioBuffer.add(floatSample)
            }
        }

        // Process when we have enough samples (80ms frames)
        while (audioBuffer.size >= FRAME_SIZE) {
            val frame = audioBuffer.take(FRAME_SIZE).toFloatArray()
            audioBuffer.subList(0, FRAME_SIZE).clear()
            
            // Run inference in a separate thread
            CoroutineScope(Dispatchers.Default).launch {
                runInference(frame)
            }
        }
    }

    /**
     * Run wake word detection inference.
     */
    private suspend fun runInference(audioFrame: FloatArray) {
        try {
            // For now, use simplified detection based on audio energy
            // Full OpenWakeWord pipeline requires specific model input formats
            val energy = audioFrame.map { it * it }.average()
            
            // Log periodically for debugging
            if (System.currentTimeMillis() % 5000 < 100) {
                Log.d(TAG, "📊 Audio energy: $energy")
            }
            
            // This is a placeholder - real implementation needs:
            // 1. Run melspectrogram model on audio frame
            // 2. Accumulate mel frames into 76-frame window
            // 3. Run embedding model
            // 4. Accumulate embeddings
            // 5. Run wake word classifier
            
            // For testing, we'll use a simple keyword detection fallback
            // The actual OpenWakeWord inference requires specific tensor shapes
            
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}")
        }
    }

    /**
     * Stop listening for wake word.
     */
    fun stopListening() {
        if (!isListening) return
        isListening = false
        audioCapture?.stop()
        audioCapture = null
        audioBuffer.clear()
        embeddingBuffer.clear()
        processingJob?.cancel()
        Log.d(TAG, "🔇 Stopped listening")
        onStatusChange?.invoke("STOPPED")
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        stopListening()
        melSession?.close()
        embeddingSession?.close()
        wakeWordSession?.close()
        ortEnv?.close()
        melSession = null
        embeddingSession = null
        wakeWordSession = null
        ortEnv = null
    }

    fun isListening(): Boolean = isListening
}
