/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 */

package com.meta.wearable.dat.externalsampleapps.landmarkguide.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import org.vosk.Model
import org.vosk.Recognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Wake Word Detection Service using Vosk with SCO microphone input.
 * Uses Bluetooth SCO audio (from Ray-Ban glasses) instead of built-in phone mic.
 * 
 * Vosk is free, open source, and has no device limits.
 */
class WakeWordService(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit,
    private val onVolumeLevel: ((Int) -> Unit)? = null,
    private val onStatusChange: ((String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "WakeWordService"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        
        // Wake word phrases to detect (case insensitive)
        // Multiple variations to improve recognition rate with Vosk
        // Note: Vosk small models often mishear phonemes, so we use many variants
        private val WAKE_WORDS = listOf(
            // Primary variants
            "hey human", "hey humain", "hey humane", 
            // Common mishearings
            "a human", "hey you man", "hey you men",
            "human", "humans", "he human", "hey humans",
            // Phonetic variants
            "hey you mine", "hey hubbin", "hey humin",
            "hey humming", "hey hue man", "hey who man",
            // Short variants (trigger on just "human" heard)
            "humain", "humin", "hubbin"
        )
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var audioManager: AudioManager? = null
    private var processingJob: Job? = null
    private var isListening = false
    private var isScoActive = false
    private var isModelLoaded = false
    private var pendingStartListening = false

    /**
     * Initialize Vosk model. This should be called once at startup.
     * Model loading is async and may take a few seconds.
     */
    fun initialize() {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        // Load Vosk model asynchronously by manually unpacking assets
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val modelPath = copyAssetsToCache(context, "model")
                if (modelPath != null) {
                    model = Model(modelPath)
                    isModelLoaded = true
                    Log.d(TAG, "✅ Vosk model loaded successfully from: $modelPath")
                    CoroutineScope(Dispatchers.Main).launch {
                        onStatusChange?.invoke("Model Ready")
                        // Auto-start listening if it was requested before model loaded
                        if (pendingStartListening) {
                            pendingStartListening = false
                            startListening()
                        }
                    }
                } else {
                    Log.e(TAG, "Failed to copy model from assets")
                    CoroutineScope(Dispatchers.Main).launch {
                        onStatusChange?.invoke("Model Load Failed")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load Vosk model: ${e.message}")
                CoroutineScope(Dispatchers.Main).launch {
                    onStatusChange?.invoke("Model Load Failed")
                }
            }
        }
    }
    
    /**
     * Copy assets folder to cache directory for Vosk to load.
     */
    private fun copyAssetsToCache(context: Context, assetPath: String): String? {
        val cacheDir = java.io.File(context.cacheDir, assetPath)
        
        // Check if already extracted
        if (cacheDir.exists() && cacheDir.isDirectory && (cacheDir.list()?.isNotEmpty() == true)) {
            Log.d(TAG, "Model already extracted at: ${cacheDir.absolutePath}")
            return cacheDir.absolutePath
        }
        
        try {
            copyAssetFolder(context.assets, assetPath, cacheDir)
            Log.d(TAG, "Model extracted to: ${cacheDir.absolutePath}")
            return cacheDir.absolutePath
        } catch (e: IOException) {
            Log.e(TAG, "Error copying assets: ${e.message}")
            return null
        }
    }
    
    /**
     * Recursively copy asset folder to destination.
     */
    private fun copyAssetFolder(assets: android.content.res.AssetManager, srcPath: String, destDir: java.io.File) {
        val list = assets.list(srcPath) ?: return
        
        if (list.isEmpty()) {
            // It's a file
            destDir.parentFile?.mkdirs()
            assets.open(srcPath).use { input ->
                java.io.FileOutputStream(destDir).use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            // It's a directory
            destDir.mkdirs()
            for (item in list) {
                copyAssetFolder(assets, "$srcPath/$item", java.io.File(destDir, item))
            }
        }
    }

    /**
     * Start listening for wake word using SCO microphone.
     */
    fun startListening() {
        if (isListening) {
            Log.d(TAG, "Already listening")
            return
        }
        
        if (!isModelLoaded) {
            Log.d(TAG, "Model not loaded yet, will start when ready")
            onStatusChange?.invoke("Model Loading...")
            pendingStartListening = true
            return
        }

        // Check permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            return
        }

        // Start Bluetooth SCO
        startScoConnection()
    }

    /**
     * Start Bluetooth SCO connection for glasses microphone.
     */
    private fun startScoConnection() {
        val am = audioManager ?: return
        
        if (!am.isBluetoothScoAvailableOffCall) {
            Log.e(TAG, "Bluetooth SCO not available - using phone mic")
            startAudioProcessing()
            return
        }

        if (!am.isBluetoothScoOn) {
            Log.d(TAG, "Starting Bluetooth SCO...")
            am.startBluetoothSco()
            
            // Wait for SCO connection
            CoroutineScope(Dispatchers.Main).launch {
                repeat(10) { // Try for 5 seconds
                    delay(500)
                    if (am.isBluetoothScoOn) {
                        Log.d(TAG, "✅ Bluetooth SCO connected - using glasses mic")
                        isScoActive = true
                        am.mode = AudioManager.MODE_IN_COMMUNICATION
                        startAudioProcessing()
                        return@launch
                    }
                }
                Log.w(TAG, "SCO timeout - using phone mic")
                startAudioProcessing()
            }
        } else {
            Log.d(TAG, "Bluetooth SCO already on")
            isScoActive = true
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            startAudioProcessing()
        }
    }

    /**
     * Start audio processing - read from mic and process with Vosk.
     */
    private fun startAudioProcessing() {
        val currentModel = model ?: run {
            Log.e(TAG, "Model not available")
            return
        }
        
        try {
            // Create recognizer with grammar for better keyword detection
            // Using a simple grammar focused on wake words
            recognizer = Recognizer(currentModel, SAMPLE_RATE.toFloat())
            
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, // Use SCO input
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                maxOf(bufferSize, 4096) * 2
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                onStatusChange?.invoke("MIC INIT FAILED")
                return
            }
            
            audioRecord?.startRecording()
            isListening = true
            val status = if (isScoActive) "🎧 LISTENING (SCO)" else "🎧 LISTENING (Phone)"
            Log.d(TAG, "$status for 'Hey Human'")
            onStatusChange?.invoke(status)
            
            // Process audio in background
            processingJob = CoroutineScope(Dispatchers.IO).launch {
                val buffer = ByteArray(4096)
                var volumeCounter = 0
                var lastDetectionTime = 0L
                
                while (isActive && isListening) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    
                    if (bytesRead > 0) {
                        // Calculate volume level every 10 reads
                        volumeCounter++
                        if (volumeCounter % 10 == 0) {
                            var sum = 0.0
                            for (i in 0 until bytesRead step 2) {
                                if (i + 1 < bytesRead) {
                                    val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                                    sum += sample.toDouble() * sample.toDouble()
                                }
                            }
                            val rms = kotlin.math.sqrt(sum / (bytesRead / 2)).toInt()
                            CoroutineScope(Dispatchers.Main).launch {
                                onVolumeLevel?.invoke(rms / 100) // Scale down
                            }
                        }
                        
                        // Feed audio to Vosk recognizer
                        if (recognizer?.acceptWaveForm(buffer, bytesRead) == true) {
                            val result = recognizer?.result ?: ""
                            if (result.isNotEmpty() && result != "{\"text\":\"\"}" && result.contains("text")) {
                                Log.d(TAG, "📝 Vosk result: $result")
                            }
                            checkForWakeWord(result, lastDetectionTime)?.let { 
                                lastDetectionTime = System.currentTimeMillis()
                            }
                        } else {
                            // Check partial result for faster detection
                            val partial = recognizer?.partialResult ?: ""
                            if (partial.isNotEmpty() && partial != "{\"partial\":\"\"}" && partial.contains("partial")) {
                                // Log partial only every 50 reads to avoid spam
                                if (volumeCounter % 50 == 0) {
                                    Log.d(TAG, "📝 Vosk partial: $partial")
                                }
                            }
                            checkForWakeWord(partial, lastDetectionTime)?.let {
                                lastDetectionTime = System.currentTimeMillis()
                                // Reset recognizer after detection
                                recognizer?.reset()
                            }
                        }
                    }
                }
            }
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting audio: ${e.message}")
        }
    }
    
    /**
     * Check if the recognition result contains a wake word.
     * Returns non-null if wake word was detected.
     */
    private fun checkForWakeWord(result: String, lastDetectionTime: Long): Unit? {
        val lowerResult = result.lowercase()
        
        // Debounce: ignore detections within 2 seconds of last one
        if (System.currentTimeMillis() - lastDetectionTime < 2000) {
            return null
        }
        
        for (wakeWord in WAKE_WORDS) {
            if (lowerResult.contains(wakeWord)) {
                Log.d(TAG, "🎤 Wake word detected: '$wakeWord' in '$result' (SCO: $isScoActive)")
                CoroutineScope(Dispatchers.Main).launch {
                    onStatusChange?.invoke("✅ DETECTED!")
                    onWakeWordDetected()
                }
                return Unit
            }
        }
        return null
    }

    /**
     * Stop listening for wake word.
     */
    fun stopListening() {
        if (!isListening) {
            Log.d(TAG, "Not currently listening")
            return
        }
        
        processingJob?.cancel()
        processingJob = null
        
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        
        recognizer?.close()
        recognizer = null
        
        isListening = false
        Log.d(TAG, "🔇 Stopped listening for wake word")
        onStatusChange?.invoke("STOPPED")
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        stopListening()
        
        // Stop SCO
        if (isScoActive) {
            audioManager?.stopBluetoothSco()
            audioManager?.mode = AudioManager.MODE_NORMAL
            isScoActive = false
        }
        
        model?.close()
        model = null
        isModelLoaded = false
        Log.d(TAG, "Vosk resources released")
    }

    fun isListening(): Boolean = isListening
    fun isScoActive(): Boolean = isScoActive
    fun isModelLoaded(): Boolean = isModelLoaded
}
