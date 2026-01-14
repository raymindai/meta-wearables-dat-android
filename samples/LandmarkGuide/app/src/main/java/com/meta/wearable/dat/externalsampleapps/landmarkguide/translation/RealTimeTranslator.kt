/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
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

/**
 * Real-time translator that integrates Speech-to-Text, Translation, and Text-to-Speech
 * Supports bidirectional translation between Arabic, Korean, and English
 */
class RealTimeTranslator(private val context: Context) {
    
    companion object {
        private const val TAG = "RealTimeTranslator"
    }
    
    // Services
    private val speechToText = SpeechToTextService()
    private val translator = TranslationService()
    private val textToSpeech = TextToSpeechService(context)
    
    // State
    private val _state = MutableStateFlow(TranslatorState())
    val state: StateFlow<TranslatorState> = _state.asStateFlow()
    
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    
    // Language settings
    private var myLanguage = TranslationService.LANG_KOREAN  // User's language
    private var partnerLanguage = TranslationService.LANG_ENGLISH  // Partner's language
    
    /**
     * Set user's language
     */
    fun setMyLanguage(langCode: String) {
        myLanguage = langCode
        Log.d(TAG, "🔊 My language set to: ${translator.getLanguageName(langCode)}")
    }
    
    /**
     * Set partner's language
     */
    fun setPartnerLanguage(langCode: String) {
        partnerLanguage = langCode
        Log.d(TAG, "🔊 Partner language set to: ${translator.getLanguageName(langCode)}")
    }
    
    /**
     * Process audio from microphone
     * Recognizes speech, translates, and speaks the translation
     * 
     * @param audioData Raw PCM audio data
     * @param sampleRate Audio sample rate
     * @param isFromPartner If true, translate to my language; if false, translate to partner language
     */
    fun processAudio(
        audioData: ByteArray,
        sampleRate: Int = 16000,
        isFromPartner: Boolean = false
    ) {
        scope.launch {
            try {
                _state.value = _state.value.copy(isProcessing = true, status = "🎤 Recognizing...")
                
                // Determine source and target languages
                val sourceLang = if (isFromPartner) partnerLanguage else myLanguage
                val targetLang = if (isFromPartner) myLanguage else partnerLanguage
                
                // Speech to Text
                val sttLangCode = when (sourceLang) {
                    TranslationService.LANG_ARABIC -> "ar-SA"
                    TranslationService.LANG_KOREAN -> "ko-KR"
                    TranslationService.LANG_SPANISH -> "es-ES"
                    else -> "en-US"
                }
                
                val recognition = speechToText.recognize(
                    audioData = audioData,
                    sampleRate = sampleRate,
                    languageCode = sttLangCode
                )
                
                if (recognition == null || recognition.transcript.isBlank()) {
                    _state.value = _state.value.copy(
                        isProcessing = false,
                        status = "⚠️ No speech detected"
                    )
                    return@launch
                }
                
                _state.value = _state.value.copy(
                    originalText = recognition.transcript,
                    status = "🔄 Translating..."
                )
                
                // Translate
                val translation = translator.translate(
                    text = recognition.transcript,
                    targetLang = targetLang,
                    sourceLang = sourceLang
                )
                
                if (translation == null) {
                    _state.value = _state.value.copy(
                        isProcessing = false,
                        status = "❌ Translation failed"
                    )
                    return@launch
                }
                
                _state.value = _state.value.copy(
                    translatedText = translation.translatedText,
                    status = "🔊 Speaking..."
                )
                
                // Text to Speech
                val spoke = textToSpeech.speak(
                    text = translation.translatedText,
                    languageCode = targetLang,
                    useBluetooth = true
                )
                
                _state.value = _state.value.copy(
                    isProcessing = false,
                    status = if (spoke) "✅ Complete" else "⚠️ TTS failed",
                    lastConversation = ConversationEntry(
                        originalText = recognition.transcript,
                        translatedText = translation.translatedText,
                        sourceLang = sourceLang,
                        targetLang = targetLang,
                        isFromPartner = isFromPartner
                    )
                )
                
                Log.d(TAG, "✅ Translation complete: ${recognition.transcript} → ${translation.translatedText}")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Processing error: ${e.message}", e)
                _state.value = _state.value.copy(
                    isProcessing = false,
                    status = "❌ Error: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Translate text directly (without speech recognition)
     */
    suspend fun translateText(
        text: String,
        toMyLanguage: Boolean = true
    ): TranslationResult? {
        val targetLang = if (toMyLanguage) myLanguage else partnerLanguage
        val sourceLang = if (toMyLanguage) partnerLanguage else myLanguage
        
        return translator.translate(
            text = text,
            targetLang = targetLang,
            sourceLang = sourceLang
        )
    }
    
    /**
     * Speak text in specified language
     */
    suspend fun speak(text: String, languageCode: String): Boolean {
        return textToSpeech.speak(text, languageCode, useBluetooth = true)
    }
    
    /**
     * Stop any ongoing audio playback
     */
    fun stop() {
        textToSpeech.stop()
    }
    
    /**
     * Get available languages
     */
    fun getAvailableLanguages(): List<LanguageOption> = listOf(
        LanguageOption(TranslationService.LANG_ENGLISH, "English", "🇺🇸"),
        LanguageOption(TranslationService.LANG_KOREAN, "한국어", "🇰🇷"),
        LanguageOption(TranslationService.LANG_ARABIC, "العربية", "🇸🇦"),
        LanguageOption(TranslationService.LANG_SPANISH, "Español", "🇪🇸")
    )
}

/**
 * Current state of the translator
 */
data class TranslatorState(
    val isProcessing: Boolean = false,
    val status: String = "Ready",
    val originalText: String = "",
    val translatedText: String = "",
    val lastConversation: ConversationEntry? = null
)

/**
 * Single conversation entry
 */
data class ConversationEntry(
    val originalText: String,
    val translatedText: String,
    val sourceLang: String,
    val targetLang: String,
    val isFromPartner: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Language option for UI
 */
data class LanguageOption(
    val code: String,
    val name: String,
    val flag: String
)
