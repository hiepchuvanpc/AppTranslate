// File: app/src/main/java/com/example/apptranslate/data/TranslationManager.kt

package com.example.apptranslate.data

import android.content.Context
import com.example.apptranslate.viewmodel.TranslationSource
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URL
import java.net.URLEncoder

class TranslationManager(context: Context) {

    private val settingsManager = SettingsManager.getInstance(context)

    suspend fun translate(
        text: String,
        sourceLangCode: String,
        targetLangCode: String,
        sourceType: TranslationSource
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            when (sourceType) {
                TranslationSource.GOOGLE -> translateWithGoogle(text, sourceLangCode, targetLangCode)
                TranslationSource.AI -> translateWithGemini(text, sourceLangCode, targetLangCode)
                TranslationSource.OFFLINE -> {
                    // TODO: Tích hợp ML Kit Translation (Offline)
                    Result.success("Offline: Dịch '${text}' từ $sourceLangCode sang $targetLangCode")
                }
            }
        }
    }

    private fun translateWithGoogle(text: String, sourceLang: String, targetLang: String): Result<String> {
        return try {
            val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sourceLang&tl=$targetLang&dt=t&q=${URLEncoder.encode(text, "UTF-8")}")
            val urlConnection = url.openConnection()
            urlConnection.setRequestProperty("User-Agent", "Mozilla/5.0")
            val result = urlConnection.getInputStream().bufferedReader().readText()

            val jsonArray = JSONArray(result)
            val translationArray = jsonArray.getJSONArray(0)
            val translatedText = StringBuilder()
            for (i in 0 until translationArray.length()) {
                val lineArray = translationArray.getJSONArray(i)
                translatedText.append(lineArray.getString(0))
            }
            Result.success(translatedText.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    private suspend fun translateWithGemini(text: String, sourceLang: String, targetLang: String): Result<String> {
        val apiKey = settingsManager.getGeminiApiKey()
        if (apiKey.isNullOrBlank()) {
            return Result.failure(Exception("Vui lòng nhập API Key của Gemini trong Cài đặt AI."))
        }

        return try {
            val generativeModel = GenerativeModel(
                modelName = "gemini-1.5-flash-latest",
                apiKey = apiKey
            )
            val prompt = "Translate the following text from the language with ISO 639-1 code '$sourceLang' to the language with ISO 639-1 code '$targetLang'. Provide only the translated text, without any additional explanations or context: $text"
            val response = generativeModel.generateContent(prompt)
            Result.success(response.text ?: "Không có kết quả từ AI.")
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }
}