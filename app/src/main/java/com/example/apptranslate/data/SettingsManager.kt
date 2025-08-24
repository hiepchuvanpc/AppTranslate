// File: app/src/main/java/com/example/apptranslate/data/SettingsManager.kt
package com.example.apptranslate.data

import android.content.Context
import android.content.SharedPreferences
import com.example.apptranslate.viewmodel.TranslationMode
import com.example.apptranslate.viewmodel.TranslationSource
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class SettingsManager private constructor(context: Context) {

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson() // Khởi tạo Gson để serialize/deserialize object

    companion object {
        private const val PREFS_NAME = "app_translate_settings"
        private const val KEY_SOURCE_LANG_CODE = "source_language_code"
        private const val KEY_TARGET_LANG_CODE = "target_language_code"
        private const val KEY_RECENT_SOURCE_LANG_CODES = "recent_source_language_codes"
        private const val KEY_RECENT_TARGET_LANG_CODES = "recent_target_language_codes"
        private const val KEY_TRANSLATION_SOURCE = "translation_source"
        private const val KEY_TRANSLATION_MODE = "translation_mode"

        // Key mới để lưu danh sách API keys dưới dạng JSON
        private const val KEY_GEMINI_API_KEYS_JSON = "gemini_api_keys_json"

        @Volatile
        private var INSTANCE: SettingsManager? = null

        fun getInstance(context: Context): SettingsManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // --- Lưu Cài đặt ---

    fun saveSourceLanguageCode(code: String) {
        sharedPreferences.edit().putString(KEY_SOURCE_LANG_CODE, code).apply()
    }

    fun saveTargetLanguageCode(code: String) {
        sharedPreferences.edit().putString(KEY_TARGET_LANG_CODE, code).apply()
    }

    fun saveTranslationSource(source: TranslationSource) {
        sharedPreferences.edit().putString(KEY_TRANSLATION_SOURCE, source.name).apply()
    }

    fun saveTranslationMode(mode: TranslationMode) {
        sharedPreferences.edit().putString(KEY_TRANSLATION_MODE, mode.name).apply()
    }

    fun saveRecentSourceLanguageCodes(codes: List<String>) {
        val codesString = codes.joinToString(separator = ",")
        sharedPreferences.edit().putString(KEY_RECENT_SOURCE_LANG_CODES, codesString).apply()
    }

    fun saveRecentTargetLanguageCodes(codes: List<String>) {
        val codesString = codes.joinToString(separator = ",")
        sharedPreferences.edit().putString(KEY_RECENT_TARGET_LANG_CODES, codesString).apply()
    }

    /**
     * Lưu danh sách các API key của Gemini vào SharedPreferences.
     * Danh sách object sẽ được chuyển thành chuỗi JSON.
     */
    fun saveGeminiApiKeys(apiKeys: List<GeminiApiKey>) {
        val json = gson.toJson(apiKeys)
        sharedPreferences.edit().putString(KEY_GEMINI_API_KEYS_JSON, json).apply()
    }

    // --- Tải Cài đặt ---

    fun getSourceLanguageCode(): String? {
        return sharedPreferences.getString(KEY_SOURCE_LANG_CODE, null)
    }

    fun getTargetLanguageCode(): String? {
        return sharedPreferences.getString(KEY_TARGET_LANG_CODE, null)
    }

    fun getTranslationSource(): TranslationSource {
        val savedName = sharedPreferences.getString(KEY_TRANSLATION_SOURCE, TranslationSource.AI.name)
        return try {
            TranslationSource.valueOf(savedName ?: TranslationSource.AI.name)
        } catch (e: IllegalArgumentException) {
            TranslationSource.AI
        }
    }

    fun getTranslationMode(): TranslationMode {
        val savedName = sharedPreferences.getString(KEY_TRANSLATION_MODE, TranslationMode.ADVANCED.name)
        return try {
            TranslationMode.valueOf(savedName ?: TranslationMode.ADVANCED.name)
        } catch (e: IllegalArgumentException) {
            TranslationMode.ADVANCED
        }
    }

    fun getRecentSourceLanguageCodes(): List<String> {
        val codesString = sharedPreferences.getString(KEY_RECENT_SOURCE_LANG_CODES, "vi,en,ja")
        return codesString?.split(',') ?: listOf("vi", "en", "ja")
    }

    fun getRecentTargetLanguageCodes(): List<String> {
        val codesString = sharedPreferences.getString(KEY_RECENT_TARGET_LANG_CODES, "en,vi,ko")
        return codesString?.split(',') ?: listOf("en", "vi", "ko")
    }

    /**
     * Lấy danh sách các API key của Gemini từ SharedPreferences.
     * Chuỗi JSON sẽ được chuyển ngược lại thành danh sách object.
     * @return Một MutableList chứa các GeminiApiKey, hoặc một list rỗng nếu chưa có gì được lưu.
     */
    fun getGeminiApiKeys(): MutableList<GeminiApiKey> {
        val json = sharedPreferences.getString(KEY_GEMINI_API_KEYS_JSON, null)
        return if (json != null) {
            // Sử dụng TypeToken để Gson biết cách parse danh sách object
            val type = object : TypeToken<MutableList<GeminiApiKey>>() {}.type
            gson.fromJson(json, type)
        } else {
            mutableListOf() // Trả về list rỗng nếu không có dữ liệu
        }
    }
}