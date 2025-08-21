// File: app/src/main/java/com/example/apptranslate/ocr/OcrManager.kt

package com.example.apptranslate.ocr

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine


// ✨ Implement Closeable để dễ dàng quản lý tài nguyên ✨
class OcrManager private constructor() : Closeable {

    // ✨ Sử dụng Map để cache recognizer, thay vì tạo tất cả cùng lúc ✨
    private val recognizerCache = mutableMapOf<String, TextRecognizer>()

    /**
     * Lấy hoặc tạo một recognizer cho mã ngôn ngữ cụ thể.
     */
    private fun getRecognizerForLanguage(languageCode: String): TextRecognizer {
        val script = mapLanguageToScript(languageCode)
        // Chỉ tạo mới nếu chưa có trong cache
        return recognizerCache.getOrPut(script) {
            Log.d("OcrManager", "Creating new TextRecognizer for script: $script")
            when (script) {
                "Japanese" -> TextRecognition.getClient(
                    JapaneseTextRecognizerOptions.Builder()
                        .build()
                )
                "Chinese" -> TextRecognition.getClient(
                    ChineseTextRecognizerOptions.Builder()
                        .build()
                )
                "Korean" -> TextRecognition.getClient(
                    KoreanTextRecognizerOptions.Builder()
                        .build()
                )
                "Devanagari" -> TextRecognition.getClient(
                    DevanagariTextRecognizerOptions.Builder()
                        .build()
                )
                else -> TextRecognition.getClient(
                    TextRecognizerOptions.Builder()
                        .build()
                ) // Latin và mặc định với cài đặt tối ưu
            }
        }
    }

    // ✨ Tách logic map ngôn ngữ ra một hàm riêng để dễ quản lý ✨
    private fun mapLanguageToScript(languageCode: String): String {
        return when (languageCode) {
            "ja" -> "Japanese"
            "zh", "zh-CN", "zh-TW" -> "Chinese"
            "ko" -> "Korean"
            "hi", "mr", "ne", "sa" -> "Devanagari"
            else -> "Latin"
        }
    }

    // BÊN TRONG CLASS OcrManager

    suspend fun recognizeTextFromBitmap(bitmap: Bitmap, languageCode: String): OcrResult {
        val startTime = System.currentTimeMillis()
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        val recognizer = getRecognizerForLanguage(languageCode)

        return suspendCoroutine { continuation ->
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val processingTimeMs = System.currentTimeMillis() - startTime
                    // SỬA Ở ĐÂY: Dùng OcrResultParser.parse thay vì OcrHelper
                    val result = OcrResultParser.parse(visionText, processingTimeMs)
                    continuation.resume(result)
                }
                .addOnFailureListener { e ->
                    continuation.resumeWithException(e)
                }
        }
    }

    /**
     * ✨ Giải phóng tất cả recognizer đã được cache.
     * Nên gọi khi service bị hủy hoặc ứng dụng thoát.
     */
    override fun close() {
        Log.d("OcrManager", "Closing all cached TextRecognizers.")
        recognizerCache.values.forEach { it.close() }
        recognizerCache.clear()
    }

    companion object {
        @Volatile
        private var INSTANCE: OcrManager? = null

        fun getInstance(): OcrManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: OcrManager().also { INSTANCE = it }
            }
        }
    }
}