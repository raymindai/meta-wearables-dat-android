/*
 * Voice Analyzer - Detects speaker gender based on pitch
 * Used for selecting appropriate TTS voice (male/female)
 */

package com.meta.wearable.dat.externalsampleapps.landmarkguide.voice

import android.util.Log
import kotlin.math.sqrt

/**
 * Analyzes audio to detect speaker characteristics.
 * Uses pitch (fundamental frequency) to determine male vs female voice.
 * 
 * Typical pitch ranges:
 * - Male: 85-180 Hz
 * - Female: 165-255 Hz
 * - Threshold: ~180 Hz
 */
object VoiceAnalyzer {
    
    private const val TAG = "VoiceAnalyzer"
    
    enum class Gender { MALE, FEMALE, UNKNOWN }
    
    /**
     * Analyze PCM audio buffer and detect gender.
     * 
     * @param audioBuffer PCM 16-bit audio data
     * @param sampleRate Sample rate (typically 16000 Hz)
     * @return Detected gender
     */
    fun detectGender(audioBuffer: ByteArray, sampleRate: Int = 16000): Gender {
        if (audioBuffer.size < sampleRate) {
            Log.d(TAG, "Buffer too small, need at least 1 second")
            return Gender.UNKNOWN
        }
        
        // Convert bytes to samples
        val samples = ShortArray(audioBuffer.size / 2)
        for (i in samples.indices) {
            val low = audioBuffer[i * 2].toInt() and 0xFF
            val high = audioBuffer[i * 2 + 1].toInt()
            samples[i] = ((high shl 8) or low).toShort()
        }
        
        // Calculate pitch using autocorrelation
        val pitch = calculatePitch(samples, sampleRate)
        
        Log.d(TAG, "📊 Detected pitch: ${pitch.toInt()} Hz")
        
        return when {
            pitch <= 0 -> Gender.UNKNOWN
            pitch <= 180 -> {
                Log.d(TAG, "🧔 Detected: MALE (pitch: ${pitch.toInt()} Hz)")
                Gender.MALE
            }
            else -> {
                Log.d(TAG, "👩 Detected: FEMALE (pitch: ${pitch.toInt()} Hz)")
                Gender.FEMALE
            }
        }
    }
    
    /**
     * Analyze audio and return recommended TTS voice.
     */
    fun getTtsVoice(audioBuffer: ByteArray, sampleRate: Int = 16000): String {
        return when (detectGender(audioBuffer, sampleRate)) {
            Gender.MALE -> "echo"      // Male voice
            Gender.FEMALE -> "nova"    // Female voice
            Gender.UNKNOWN -> "onyx"   // Default deep voice
        }
    }
    
    /**
     * Calculate fundamental frequency (pitch) using autocorrelation.
     * Simple but effective for voice gender detection.
     */
    private fun calculatePitch(samples: ShortArray, sampleRate: Int): Float {
        // Use only first 0.5 seconds for faster processing
        val windowSize = minOf(samples.size, sampleRate / 2)
        if (windowSize < 200) return 0f
        
        // Normalize samples
        val normalized = FloatArray(windowSize)
        var maxVal = 1f
        for (i in 0 until windowSize) {
            val absVal = kotlin.math.abs(samples[i].toFloat())
            if (absVal > maxVal) maxVal = absVal
        }
        for (i in 0 until windowSize) {
            normalized[i] = samples[i].toFloat() / maxVal
        }
        
        // Check if there's enough signal
        val rms = sqrt(normalized.map { it * it }.average().toFloat())
        if (rms < 0.01f) {
            Log.d(TAG, "Signal too weak (RMS: $rms)")
            return 0f
        }
        
        // Autocorrelation to find pitch period
        // Search range: 60-300 Hz (period: ~53-267 samples at 16kHz)
        val minLag = sampleRate / 300  // 300 Hz = high female
        val maxLag = sampleRate / 60   // 60 Hz = very low male
        
        var bestLag = 0
        var bestCorr = 0f
        
        for (lag in minLag..minOf(maxLag, windowSize / 2)) {
            var corr = 0f
            var count = 0
            for (i in 0 until windowSize - lag) {
                corr += normalized[i] * normalized[i + lag]
                count++
            }
            corr /= count
            
            if (corr > bestCorr) {
                bestCorr = corr
                bestLag = lag
            }
        }
        
        // Require minimum correlation for valid pitch
        if (bestCorr < 0.2f || bestLag == 0) {
            Log.d(TAG, "No clear pitch detected (corr: $bestCorr)")
            return 0f
        }
        
        val pitch = sampleRate.toFloat() / bestLag
        return pitch
    }
}
