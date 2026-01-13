/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 */

package com.meta.wearable.dat.externalsampleapps.landmarkguide.mode

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App Mode Manager - State machine for different app modes.
 * Manages transitions between IDLE, GUIDE_MODE, and TRANSLATION_MODE.
 */
class AppModeManager {
    companion object {
        private const val TAG = "AppModeManager"
    }

    /**
     * Available app modes
     */
    enum class AppMode {
        IDLE,           // No active mode, just listening for wake word
        GUIDE_MODE,     // Landmark guide mode (camera + AI vision)
        TRANSLATION_MODE // Real-time translation mode
    }

    private val _currentMode = MutableStateFlow(AppMode.IDLE)
    val currentMode: StateFlow<AppMode> = _currentMode.asStateFlow()

    private val _targetLanguage = MutableStateFlow("ko") // Default: Korean
    val targetLanguage: StateFlow<String> = _targetLanguage.asStateFlow()

    /**
     * Switch to a new mode
     */
    fun switchMode(newMode: AppMode) {
        val oldMode = _currentMode.value
        if (oldMode == newMode) {
            Log.d(TAG, "Already in $newMode mode")
            return
        }

        Log.d(TAG, "🔄 Mode change: $oldMode → $newMode")
        _currentMode.value = newMode
    }

    /**
     * Set target language for translation
     */
    fun setTargetLanguage(languageCode: String) {
        Log.d(TAG, "🌐 Target language: $languageCode")
        _targetLanguage.value = languageCode
    }

    /**
     * Get language code from language name
     */
    fun getLanguageCode(languageName: String): String {
        return when (languageName.lowercase()) {
            "korean", "한국어" -> "ko"
            "arabic", "아랍어" -> "ar"
            "english", "영어" -> "en"
            "chinese", "중국어" -> "zh"
            "japanese", "일본어" -> "ja"
            "spanish", "스페인어" -> "es"
            "french", "프랑스어" -> "fr"
            "german", "독일어" -> "de"
            else -> "en" // Default to English
        }
    }

    /**
     * Get display name for language code
     */
    fun getLanguageDisplayName(languageCode: String): String {
        return when (languageCode) {
            "ko" -> "한국어"
            "ar" -> "العربية"
            "en" -> "English"
            "zh" -> "中文"
            "ja" -> "日本語"
            "es" -> "Español"
            "fr" -> "Français"
            "de" -> "Deutsch"
            else -> languageCode
        }
    }

    /**
     * Reset to idle mode
     */
    fun reset() {
        _currentMode.value = AppMode.IDLE
        Log.d(TAG, "Reset to IDLE mode")
    }
}
