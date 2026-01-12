/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.landmarkguide.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * BluetoothScoAudioCapture - Attempts to capture audio from Bluetooth SCO (glasses microphone)
 * 
 * SCO (Synchronous Connection-Oriented) is used for two-way audio like phone calls.
 * This may allow us to access the Meta glasses microphone bypassing DAT SDK limitations.
 */
class BluetoothScoAudioCapture(private val context: Context) {
    
    companion object {
        private const val TAG = "BluetoothScoAudio"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }
    
    private var audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var isScoConnected = false
    
    interface AudioCaptureListener {
        fun onAudioData(data: ByteArray, size: Int)
        fun onScoConnected()
        fun onScoDisconnected()
        fun onError(message: String)
    }
    
    private var listener: AudioCaptureListener? = null
    
    fun setListener(listener: AudioCaptureListener) {
        this.listener = listener
    }
    
    /**
     * Start Bluetooth SCO connection for microphone access
     */
    fun startScoConnection(): Boolean {
        Log.d(TAG, "Starting Bluetooth SCO connection...")
        
        // Check if Bluetooth SCO is available
        if (!audioManager.isBluetoothScoAvailableOffCall) {
            Log.e(TAG, "Bluetooth SCO not available off call")
            listener?.onError("Bluetooth SCO not available")
            return false
        }
        
        // Check if a Bluetooth headset is connected
        if (!audioManager.isBluetoothScoOn) {
            Log.d(TAG, "Starting Bluetooth SCO...")
            audioManager.startBluetoothSco()
            
            // Wait for SCO connection (async)
            CoroutineScope(Dispatchers.Main).launch {
                repeat(10) { // Try for 5 seconds
                    delay(500)
                    if (audioManager.isBluetoothScoOn) {
                        Log.d(TAG, "Bluetooth SCO connected!")
                        isScoConnected = true
                        listener?.onScoConnected()
                        return@launch
                    }
                }
                Log.e(TAG, "Timeout waiting for Bluetooth SCO connection")
                listener?.onError("SCO connection timeout")
            }
        } else {
            Log.d(TAG, "Bluetooth SCO already on")
            isScoConnected = true
            listener?.onScoConnected()
        }
        
        return true
    }
    
    /**
     * Start recording from Bluetooth SCO microphone
     */
    fun startRecording(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            listener?.onError("Microphone permission required")
            return false
        }
        
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid buffer size: $bufferSize")
            listener?.onError("Invalid audio buffer size")
            return false
        }
        
        try {
            // Use VOICE_COMMUNICATION for SCO input
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, // Key for SCO
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                listener?.onError("Failed to initialize audio recorder")
                return false
            }
            
            // Set audio mode for SCO
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            
            audioRecord?.startRecording()
            Log.d(TAG, "Started recording from SCO microphone")
            
            // Start reading audio data
            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                val buffer = ByteArray(bufferSize)
                while (isActive) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (bytesRead > 0) {
                        listener?.onAudioData(buffer.copyOf(bytesRead), bytesRead)
                    }
                }
            }
            
            return true
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception: ${e.message}")
            listener?.onError("Security exception: ${e.message}")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording: ${e.message}")
            listener?.onError("Error: ${e.message}")
            return false
        }
    }
    
    /**
     * Stop recording and SCO connection
     */
    fun stop() {
        Log.d(TAG, "Stopping Bluetooth SCO audio capture...")
        
        recordingJob?.cancel()
        recordingJob = null
        
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        
        if (isScoConnected) {
            audioManager.stopBluetoothSco()
            audioManager.mode = AudioManager.MODE_NORMAL
            isScoConnected = false
            listener?.onScoDisconnected()
        }
        
        Log.d(TAG, "Stopped")
    }
    
    /**
     * Check if Bluetooth SCO is supported and available
     */
    fun isScoAvailable(): Boolean {
        return audioManager.isBluetoothScoAvailableOffCall
    }
    
    /**
     * Check current SCO connection status
     */
    fun isScoConnected(): Boolean {
        return audioManager.isBluetoothScoOn
    }
}
