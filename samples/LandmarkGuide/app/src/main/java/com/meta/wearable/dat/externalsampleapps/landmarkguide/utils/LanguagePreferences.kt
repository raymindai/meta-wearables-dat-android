/*
 * Language Preferences - Save and load language settings
 */

package com.meta.wearable.dat.externalsampleapps.landmarkguide.utils

import android.content.Context
import android.content.SharedPreferences
import com.meta.wearable.dat.externalsampleapps.landmarkguide.translation.TranslationService

/**
 * Manages language preferences persistence
 */
class LanguagePreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
    
    companion object {
        private const val PREFS_NAME = "language_preferences"
        private const val KEY_MY_LANGUAGE = "my_language"  // 내가 말하는 언어
        private const val KEY_TRANSLATION_LANGUAGE = "translation_language"  // 상대방이 나한테 말할 때 번역되는 언어
        private const val KEY_LISTENING_LANGUAGE = "listening_language"  // 방 선택 화면에서 선택한 언어 (호환성)
        
        // Default language - Always use a specific language (Korean), not "auto"
        private const val DEFAULT_LANGUAGE = TranslationService.LANG_KOREAN
    }
    
    /**
     * Save my language (내가 말하는 언어)
     */
    fun saveMyLanguage(language: String) {
        prefs.edit().putString(KEY_MY_LANGUAGE, language).apply()
    }
    
    /**
     * Get my language (내가 말하는 언어)
     */
    fun getMyLanguage(): String {
        return prefs.getString(KEY_MY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
    }
    
    /**
     * Save translation language (상대방이 나한테 말할 때 번역되는 언어)
     */
    fun saveTranslationLanguage(language: String) {
        prefs.edit().putString(KEY_TRANSLATION_LANGUAGE, language).apply()
    }
    
    /**
     * Get translation language (상대방이 나한테 말할 때 번역되는 언어)
     */
    fun getTranslationLanguage(): String {
        return prefs.getString(KEY_TRANSLATION_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
    }
    
    /**
     * Save listening language (방 선택 화면에서 선택한 언어)
     * This is used for backward compatibility and initial setup
     */
    fun saveListeningLanguage(language: String) {
        prefs.edit().putString(KEY_LISTENING_LANGUAGE, language).apply()
        // Also save as both my language and translation language if not set
        if (!prefs.contains(KEY_MY_LANGUAGE)) {
            saveMyLanguage(language)
        }
        if (!prefs.contains(KEY_TRANSLATION_LANGUAGE)) {
            saveTranslationLanguage(language)
        }
    }
    
    /**
     * Get listening language (방 선택 화면에서 선택한 언어)
     */
    fun getListeningLanguage(): String {
        return prefs.getString(KEY_LISTENING_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
    }
    
    /**
     * Save both languages at once (for convenience)
     */
    fun saveLanguages(myLanguage: String, translationLanguage: String) {
        prefs.edit()
            .putString(KEY_MY_LANGUAGE, myLanguage)
            .putString(KEY_TRANSLATION_LANGUAGE, translationLanguage)
            .putString(KEY_LISTENING_LANGUAGE, translationLanguage)  // Also save as listening language
            .apply()
    }
    
    /**
     * Clear all language preferences
     */
    fun clear() {
        prefs.edit().clear().apply()
    }
}
