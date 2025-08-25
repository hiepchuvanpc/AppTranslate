// File: app/src/main/java/com/example/apptranslate/viewmodel/LanguageViewModel.kt

package com.example.apptranslate.viewmodel

import android.app.Application
import androidx.lifecycle.*
import com.example.apptranslate.data.SettingsManager
import com.example.apptranslate.model.Language
import java.util.Locale

enum class TranslationSource { AI, GOOGLE, OFFLINE }
enum class TranslationMode { SIMPLE, ADVANCED }

class LanguageViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsManager = SettingsManager.getInstance(application)
    private val allLanguages = Language.ALL_LANGUAGES

    private val _sourceLanguage = MutableLiveData<Language>()
    val sourceLanguage: LiveData<Language> = _sourceLanguage

    private val _targetLanguage = MutableLiveData<Language>()
    val targetLanguage: LiveData<Language> = _targetLanguage

    private val _recentSourceLanguages = MutableLiveData<List<Language>>()
    val recentSourceLanguages: LiveData<List<Language>> = _recentSourceLanguages

    private val _recentTargetLanguages = MutableLiveData<List<Language>>()
    val recentTargetLanguages: LiveData<List<Language>> = _recentTargetLanguages

    private val _translationSource = MutableLiveData<TranslationSource>()
    val translationSource: LiveData<TranslationSource> = _translationSource

    private val _translationMode = MutableLiveData<TranslationMode>()
    val translationMode: LiveData<TranslationMode> = _translationMode

    init {
        loadSettings()
    }

    /**
     * SỬA LỖI: Chỉ có MỘT hàm loadSettings, và nó là public.
     * Tải tất cả cài đặt từ SharedPreferences và cập nhật LiveData.
     */
    fun loadSettings() {
        val savedSourceCode = settingsManager.getSourceLanguageCode()
        val savedTargetCode = settingsManager.getTargetLanguageCode()
        _sourceLanguage.value = allLanguages.find { it.code == savedSourceCode } ?: allLanguages.find { it.code == "vi" }!!
        _targetLanguage.value = allLanguages.find { it.code == savedTargetCode } ?: allLanguages.find { it.code == "en" }!!

        _translationSource.value = settingsManager.getTranslationSource()
        _translationMode.value = settingsManager.getTranslationMode()

        _recentSourceLanguages.value = settingsManager.getRecentSourceLanguageCodes()
            .mapNotNull { code -> allLanguages.find { it.code == code } }
        _recentTargetLanguages.value = settingsManager.getRecentTargetLanguageCodes()
            .mapNotNull { code -> allLanguages.find { it.code == code } }
    }

    fun setSourceLanguage(language: Language) {
        val currentSource = _sourceLanguage.value
        val currentTarget = _targetLanguage.value

        if (currentTarget?.code == language.code && currentSource != null) {
            // Logic hoán đổi
            _sourceLanguage.value = currentTarget
            _targetLanguage.value = currentSource
            settingsManager.saveSourceLanguageCode(currentTarget.code)
            settingsManager.saveTargetLanguageCode(currentSource.code)
        } else {
            // Logic đặt bình thường
            _sourceLanguage.value = language
            settingsManager.saveSourceLanguageCode(language.code)
        }
        updateRecentLanguages(language, isSource = true)
    }

    fun setTargetLanguage(language: Language) {
        val currentSource = _sourceLanguage.value
        val currentTarget = _targetLanguage.value

        if (currentSource?.code == language.code && currentTarget != null) {
            // Logic hoán đổi
            _sourceLanguage.value = currentTarget
            _targetLanguage.value = currentSource
            settingsManager.saveSourceLanguageCode(currentTarget.code)
            settingsManager.saveTargetLanguageCode(currentSource.code)
        } else {
            // Logic đặt bình thường
            _targetLanguage.value = language
            settingsManager.saveTargetLanguageCode(language.code)
        }
        updateRecentLanguages(language, isSource = false)
    }

    private fun updateRecentLanguages(newLanguage: Language, isSource: Boolean) {
        if (isSource) {
            val currentList = _recentSourceLanguages.value?.toMutableList() ?: mutableListOf()
            currentList.remove(newLanguage)
            currentList.add(0, newLanguage)
            val updatedList = currentList.take(3)
            _recentSourceLanguages.value = updatedList
            settingsManager.saveRecentSourceLanguageCodes(updatedList.map { it.code })
        } else {
            val currentList = _recentTargetLanguages.value?.toMutableList() ?: mutableListOf()
            currentList.remove(newLanguage)
            currentList.add(0, newLanguage)
            val updatedList = currentList.take(3)
            _recentTargetLanguages.value = updatedList
            settingsManager.saveRecentTargetLanguageCodes(updatedList.map { it.code })
        }
    }

    fun swapLanguages() {
        val currentSource = _sourceLanguage.value
        val currentTarget = _targetLanguage.value
        if (currentSource != null && currentTarget != null) {
            _sourceLanguage.value = currentTarget
            _targetLanguage.value = currentSource
            settingsManager.saveSourceLanguageCode(currentTarget.code)
            settingsManager.saveTargetLanguageCode(currentSource.code)
        }
    }

    fun setTranslationSource(source: TranslationSource) {
        _translationSource.value = source
        settingsManager.saveTranslationSource(source)
    }

    fun setTranslationMode(mode: TranslationMode) {
        _translationMode.value = mode
        settingsManager.saveTranslationMode(mode)
    }

    fun getAllLanguages(): List<Language> = allLanguages

    fun searchLanguages(query: String): List<Language> {
        if (query.isBlank()) return emptyList()
        val lowerCaseQuery = query.lowercase(Locale.ROOT)
        return allLanguages.filter {
            it.name.lowercase(Locale.ROOT).contains(lowerCaseQuery) ||
            it.nativeName.lowercase(Locale.ROOT).contains(lowerCaseQuery) ||
            it.code.lowercase(Locale.ROOT).startsWith(lowerCaseQuery)
        }
    }
}

class LanguageViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LanguageViewModel::class.java)) {
            return LanguageViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}