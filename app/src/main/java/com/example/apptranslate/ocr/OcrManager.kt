// File: app/src/main/java/com/example/apptranslate/ocr/OcrManager.kt

package com.example.apptranslate.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.LatinTextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Quản lý việc nhận dạng văn bản (OCR) bằng Google ML Kit.
 * Đây là công cụ chỉ để lấy text từ ảnh.
 */
class OcrManager private constructor() {

    // Tạo sẵn các bộ nhận dạng cho từng bộ chữ viết
    private val latinRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(LatinTextRecognizerOptions.Builder().build())
    }
    private val japaneseRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }
    private val chineseRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val koreanRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }
    private val devanagariRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }

    /**
     * Chọn bộ nhận dạng (recognizer) phù hợp dựa trên mã ngôn ngữ (ISO 639-1 code).
     */
    private fun getRecognizerForLanguage(languageCode: String): TextRecognizer {
        return when (languageCode) {
            "ja" -> japaneseRecognizer
            "zh", "zh-CN", "zh-TW" -> chineseRecognizer
            "ko" -> koreanRecognizer
            "hi", "mr", "ne", "sa" -> devanagariRecognizer // Tiếng Hindi, Marathi, Nepali, Sanskrit
            else -> latinRecognizer // Mặc định cho Tiếng Việt, Anh và các ngôn ngữ Latin khác
        }
    }

    /**
     * Nhận dạng văn bản từ một Bitmap.
     * @param bitmap ảnh đầu vào.
     * @param languageCode mã ngôn ngữ của văn bản trong ảnh để chọn bộ nhận dạng phù hợp.
     * @return OcrResult chứa kết quả nhận dạng.
     */
    suspend fun recognizeTextFromBitmap(bitmap: Bitmap, languageCode: String): OcrResult {
        val startTime = System.currentTimeMillis()
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        val recognizer = getRecognizerForLanguage(languageCode)

        return suspendCoroutine { continuation ->
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val processingTimeMs = System.currentTimeMillis() - startTime
                    val result = OcrHelper.parseTextResult(visionText, processingTimeMs)
                    continuation.resume(result)
                }
                .addOnFailureListener { e ->
                    continuation.resumeWithException(e)
                }
        }
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