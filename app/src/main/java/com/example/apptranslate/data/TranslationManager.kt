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

    // Thay vì gọi SettingsManager, giờ đây chúng ta gọi ApiKeyManager
    private val apiKeyManager = ApiKeyManager.getInstance(context)

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

    /**
     * Dịch văn bản bằng Gemini AI.
     * Hàm này đã được cập nhật để sử dụng ApiKeyManager.
     */
    private suspend fun translateWithGemini(text: String, sourceLang: String, targetLang: String): Result<String> {
        // 1. Yêu cầu một key hợp lệ từ ApiKeyManager
        val apiKey = apiKeyManager.getAvailableKey()
        if (apiKey == null) {
            return Result.failure(Exception("Tất cả API Key đã đạt giới hạn. Vui lòng thử lại sau hoặc thêm key mới."))
        }

        var requestSuccessful = false
        try {
            val generativeModel = GenerativeModel(
                // Sử dụng model mới nhất và nhẹ nhất
                modelName = "gemini-2.5-flash-lite",
                apiKey = apiKey.key // Lấy chuỗi key từ object được trả về
            )
            val prompt = "Translate the following text from the language with ISO 639-1 code '$sourceLang' to the language with ISO 639-1 code '$targetLang'. Provide only the translated text, without any additional explanations or context: $text"
            val response = generativeModel.generateContent(prompt)

            // 2. Nếu API trả về kết quả, đánh dấu request là thành công
            requestSuccessful = response.text != null
            return Result.success(response.text ?: "Không có kết quả từ AI.")
        } catch (e: Exception) {
            // Lỗi có thể xảy ra do key không hợp lệ, hết hạn, hoặc vấn đề mạng
            e.printStackTrace()
            return Result.failure(e)
        } finally {
            // 3. Luôn luôn cập nhật trạng thái sử dụng của key sau mỗi lần gọi API
            // Nếu request không thành công (vd: crash), nó sẽ không được tính vào rate limit.
            apiKeyManager.updateKeyUsage(apiKey.key, requestSuccessful)
        }
    }
}