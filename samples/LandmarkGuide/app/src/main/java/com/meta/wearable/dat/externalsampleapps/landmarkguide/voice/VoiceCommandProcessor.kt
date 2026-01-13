/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 */

package com.meta.wearable.dat.externalsampleapps.landmarkguide.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.content.Intent
import android.os.Bundle
import android.util.Log
import java.util.Locale

/**
 * Voice Command Processor that listens for commands after wake word detection.
 * Uses Android's built-in SpeechRecognizer for command recognition.
 */
class VoiceCommandProcessor(
    private val context: Context,
    private val onCommandRecognized: (VoiceCommand) -> Unit
) {
    companion object {
        private const val TAG = "VoiceCommandProcessor"
        private const val LISTENING_TIMEOUT_MS = 5000L
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    /**
     * Supported voice commands
     */
    enum class VoiceCommand {
        TRANSLATION_MODE,
        GUIDE_MODE,
        SET_LANGUAGE_KOREAN,
        SET_LANGUAGE_ARABIC,
        SET_LANGUAGE_ENGLISH,
        SET_LANGUAGE_CHINESE,
        STOP,
        UNKNOWN
    }

    fun initialize() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available on this device")
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(createRecognitionListener())
        Log.d(TAG, "VoiceCommandProcessor initialized")
    }

    /**
     * Start listening for voice command (called after wake word detected).
     * Automatically stops after timeout.
     */
    fun startListening() {
        if (isListening) {
            Log.d(TAG, "Already listening for command")
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.ENGLISH.toString())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        try {
            speechRecognizer?.startListening(intent)
            isListening = true
            Log.d(TAG, "🎤 Listening for command...")

            // Set timeout
            timeoutRunnable = Runnable {
                if (isListening) {
                    Log.d(TAG, "Command listening timeout")
                    stopListening()
                    onCommandRecognized(VoiceCommand.UNKNOWN)
                }
            }
            handler.postDelayed(timeoutRunnable!!, LISTENING_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening: ${e.message}")
        }
    }

    fun stopListening() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        speechRecognizer?.stopListening()
        isListening = false
        Log.d(TAG, "Stopped listening for command")
    }

    fun destroy() {
        stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun createRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Speech started")
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "Speech ended")
            isListening = false
        }

        override fun onError(error: Int) {
            val errorMsg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "No match"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                else -> "Error code: $error"
            }
            Log.e(TAG, "Recognition error: $errorMsg")
            isListening = false
            onCommandRecognized(VoiceCommand.UNKNOWN)
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            timeoutRunnable?.let { handler.removeCallbacks(it) }
            
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (matches.isNullOrEmpty()) {
                onCommandRecognized(VoiceCommand.UNKNOWN)
                return
            }

            val spokenText = matches[0].lowercase()
            Log.d(TAG, "Recognized: \"$spokenText\"")
            
            val command = parseCommand(spokenText)
            Log.d(TAG, "Parsed command: $command")
            onCommandRecognized(command)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                Log.d(TAG, "Partial: ${matches[0]}")
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /**
     * Parse spoken text into a VoiceCommand.
     */
    private fun parseCommand(text: String): VoiceCommand {
        return when {
            text.contains("translation") || text.contains("translate") -> VoiceCommand.TRANSLATION_MODE
            text.contains("guide") -> VoiceCommand.GUIDE_MODE
            text.contains("korean") -> VoiceCommand.SET_LANGUAGE_KOREAN
            text.contains("arabic") -> VoiceCommand.SET_LANGUAGE_ARABIC
            text.contains("english") -> VoiceCommand.SET_LANGUAGE_ENGLISH
            text.contains("chinese") -> VoiceCommand.SET_LANGUAGE_CHINESE
            text.contains("stop") || text.contains("cancel") -> VoiceCommand.STOP
            else -> VoiceCommand.UNKNOWN
        }
    }

    fun isListening(): Boolean = isListening
}
