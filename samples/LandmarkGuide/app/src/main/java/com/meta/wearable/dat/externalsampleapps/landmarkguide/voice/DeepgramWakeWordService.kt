/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 */

package com.meta.wearable.dat.externalsampleapps.landmarkguide.voice

import android.content.Context
import android.util.Log
import com.meta.wearable.dat.externalsampleapps.landmarkguide.audio.BluetoothScoAudioCapture
import com.meta.wearable.dat.externalsampleapps.landmarkguide.translation.StreamingSpeechService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Wake Word Detection Service using Deepgram for always-on listening.
 * 
 * Uses Deepgram's streaming STT for high accuracy wake word detection.
 * More accurate than Vosk, but requires internet connection.
 */
class DeepgramWakeWordService(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit,
    private val onStatusChange: ((String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "DeepgramWakeWord"
        
        // Wake word variants to detect (expanded for better recognition)
        private val WAKE_WORDS = listOf(
            // Primary
            "hey human", "hey humain", "hey humane",
            // Common transcriptions
            "a human", "hey you man", "hey you men",
            "human", "humans", "hey humans", "he human",
            // Phonetic variants
            "hey you mine", "hey humin", "hey humming",
            "hey hue man", "hey who man", "hey uman",
            // Short forms
            "humain", "humin", "uman",
            // With fillers
            "hey there human", "okay human", "yo human"
        )
    }

    private var deepgramSTT: StreamingSpeechService? = null
    private var audioCapture: BluetoothScoAudioCapture? = null
    private var isListening = false
    private var lastDetectionTime = 0L
    private var reconnectJob: Job? = null
    private var logCounter = 0

    /**
     * Start listening for wake word using Deepgram.
     */
    fun startListening() {
        if (isListening) {
            Log.d(TAG, "Already listening")
            return
        }

        Log.d(TAG, "🎧 Starting Deepgram wake word listening...")
        isListening = true
        onStatusChange?.invoke("🎧 LISTENING")

        // Initialize Deepgram STT
        deepgramSTT = StreamingSpeechService()
        
        deepgramSTT?.onTranscript = { text, isFinal ->
            if (text.isNotBlank()) {
                // Log every transcript for debugging
                logCounter++
                if (logCounter % 5 == 0 || isFinal) {
                    Log.d(TAG, "📝 Heard: '$text' (final: $isFinal)")
                }
                checkForWakeWord(text)
            }
        }
        
        deepgramSTT?.onError = { error ->
            Log.e(TAG, "Deepgram error: $error")
            // Auto-reconnect on error
            if (isListening) {
                scheduleReconnect()
            }
        }

        // Start STT
        deepgramSTT?.startListening("en")

        // Start audio capture
        audioCapture = BluetoothScoAudioCapture(context)
        audioCapture?.setListener(object : BluetoothScoAudioCapture.AudioCaptureListener {
            override fun onAudioData(data: ByteArray, size: Int) {
                deepgramSTT?.sendAudio(data.copyOf(size))
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
     * Check if the transcription contains a wake word.
     */
    private fun checkForWakeWord(text: String) {
        val lowerText = text.lowercase()
        
        // Debounce: ignore detections within 3 seconds of last one
        val now = System.currentTimeMillis()
        if (now - lastDetectionTime < 3000) {
            return
        }

        for (wakeWord in WAKE_WORDS) {
            if (lowerText.contains(wakeWord)) {
                Log.d(TAG, "🎤 Wake word detected: '$wakeWord' in '$text'")
                lastDetectionTime = now
                
                // Stop listening temporarily
                stopListening()
                
                // Notify callback
                CoroutineScope(Dispatchers.Main).launch {
                    onStatusChange?.invoke("✅ DETECTED!")
                    onWakeWordDetected()
                }
                return
            }
        }
    }

    /**
     * Schedule reconnection after error.
     */
    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = CoroutineScope(Dispatchers.Main).launch {
            kotlinx.coroutines.delay(2000)
            if (isListening) {
                Log.d(TAG, "🔄 Reconnecting...")
                stopListeningInternal()
                startListening()
            }
        }
    }

    /**
     * Stop listening for wake word.
     */
    fun stopListening() {
        if (!isListening) return
        isListening = false
        stopListeningInternal()
    }

    private fun stopListeningInternal() {
        deepgramSTT?.stopListening()
        deepgramSTT = null
        audioCapture?.stop()
        audioCapture = null
        reconnectJob?.cancel()
        reconnectJob = null
        Log.d(TAG, "🔇 Stopped listening")
        onStatusChange?.invoke("STOPPED")
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        stopListening()
    }

    fun isListening(): Boolean = isListening
}
