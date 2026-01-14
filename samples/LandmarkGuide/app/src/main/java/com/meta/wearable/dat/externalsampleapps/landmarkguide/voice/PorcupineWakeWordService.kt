/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 * 
 * Porcupine Wake Word Service - Picovoice's accurate wake word detection
 * 
 * Uses Picovoice Porcupine for offline, low-latency wake word detection.
 * Requires Access Key from https://console.picovoice.ai/
 */

package com.meta.wearable.dat.externalsampleapps.landmarkguide.voice

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import ai.picovoice.porcupine.PorcupineException
import android.content.Context
import android.util.Log
import com.meta.wearable.dat.externalsampleapps.landmarkguide.BuildConfig

/**
 * Porcupine Wake Word Service - Uses Picovoice's accurate wake word detection.
 * 
 * Built-in keywords: "Alexa", "Blueberry", "Bumblebee", "Computer", "Grapefruit",
 * "Grasshopper", "Hey Barista", "Hey Google", "Hey Siri", "Jarvis", "ok Google", "Picovoice", "Porcupine", "Terminator"
 * 
 * Custom keywords require training at console.picovoice.ai
 */
class PorcupineWakeWordService(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit,
    private val onStatusChange: ((String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "PorcupineWakeWord"
        private const val DEBOUNCE_MS = 3000L
    }

    private var porcupineManager: PorcupineManager? = null
    private var isListening = false
    private var lastDetectionTime = 0L

    /**
     * Initialize Porcupine with custom "Hey Humain" keyword model
     */
    fun initialize(): Boolean {
        return try {
            val accessKey = BuildConfig.PICOVOICE_API_KEY
            
            if (accessKey.isBlank()) {
                Log.e(TAG, "❌ Picovoice Access Key not set in local.properties")
                onStatusChange?.invoke("❌ No API Key")
                return false
            }

            Log.d(TAG, "🔄 Initializing Porcupine with custom 'Hey Humain' keyword...")
            onStatusChange?.invoke("Loading...")

            // Use custom "Hey Humain" keyword from res/raw/hey_humain.ppn
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeywordPath("hey_humain.ppn")  // Loads from res/raw
                .setSensitivity(0.7f)  // Higher = more sensitive (0.0 to 1.0)
                .build(context, object : PorcupineManagerCallback {
                    override fun invoke(keywordIndex: Int) {
                        handleWakeWordDetected(keywordIndex)
                    }
                })

            Log.d(TAG, "✅ Porcupine initialized with 'Hey Humain' keyword")
            onStatusChange?.invoke("Ready (Hey Humain)")
            true
        } catch (e: PorcupineException) {
            Log.e(TAG, "❌ Porcupine init failed: ${e.message}", e)
            onStatusChange?.invoke("Init failed: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "❌ Unexpected error: ${e.message}", e)
            onStatusChange?.invoke("Error: ${e.message}")
            false
        }
    }

    /**
     * Handle wake word detection from Porcupine.
     */
    private fun handleWakeWordDetected(keywordIndex: Int) {
        val now = System.currentTimeMillis()
        
        // Debounce: ignore detections within 3 seconds
        if (now - lastDetectionTime < DEBOUNCE_MS) {
            Log.d(TAG, "⏳ Debounced detection (too soon)")
            return
        }
        
        lastDetectionTime = now
        Log.d(TAG, "🎤 Wake word detected! (keyword index: $keywordIndex)")
        onStatusChange?.invoke("✅ DETECTED!")
        
        // Stop listening before callback to prevent re-detection
        stopListening()
        
        // Notify callback on main thread
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            onWakeWordDetected()
        }
    }

    /**
     * Start listening for wake word.
     */
    fun startListening() {
        if (isListening) {
            Log.d(TAG, "Already listening")
            return
        }
        
        if (porcupineManager == null) {
            Log.e(TAG, "Porcupine not initialized! Call initialize() first.")
            return
        }

        try {
            Log.d(TAG, "🎧 Starting Porcupine wake word listening...")
            porcupineManager?.start()
            isListening = true
            onStatusChange?.invoke("🎧 LISTENING (Hey Jarvis)")
        } catch (e: PorcupineException) {
            Log.e(TAG, "Failed to start: ${e.message}", e)
            onStatusChange?.invoke("Start failed")
        }
    }

    /**
     * Stop listening for wake word.
     */
    fun stopListening() {
        if (!isListening) return
        
        try {
            porcupineManager?.stop()
            isListening = false
            Log.d(TAG, "🔇 Stopped listening")
            onStatusChange?.invoke("STOPPED")
        } catch (e: PorcupineException) {
            Log.e(TAG, "Failed to stop: ${e.message}", e)
        }
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        stopListening()
        try {
            porcupineManager?.delete()
            porcupineManager = null
        } catch (e: PorcupineException) {
            Log.e(TAG, "Failed to delete: ${e.message}", e)
        }
    }

    fun isListening(): Boolean = isListening
}
