package com.example.apptranslate.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Enum định nghĩa các loại hệ chữ được hỗ trợ
 */
enum class ScriptType {
    LATIN,      // Chữ Latin (Tiếng Anh, Pháp, Đức, v.v.)
    CHINESE,    // Chữ Hán (Tiếng Trung)
    JAPANESE,   // Chữ Nhật (Hiragana, Katakana, Kanji)
    KOREAN,     // Chữ Hàn (Hangul)
    DEVANAGARI  // Chữ Devanagari (Hindi, Sanskrit)
}

/**
 * Data class chứa thông tin chi tiết về một khối văn bản được nhận dạng
 */
data class TextBlock(
    val text: String,
    val boundingBox: android.graphics.Rect?,
    val confidence: Float?,
    val lines: List<TextLine>
)

/**
 * Data class chứa thông tin về một dòng văn bản
 */
data class TextLine(
    val text: String,
    val boundingBox: android.graphics.Rect?,
    val confidence: Float?,
    val elements: List<TextElement>
)

/**
 * Data class chứa thông tin về một phần tử văn bản (từ hoặc ký tự)
 */
data class TextElement(
    val text: String,
    val boundingBox: android.graphics.Rect?,
    val confidence: Float?
)

/**
 * Data class chứa kết quả OCR hoàn chỉnh
 */
data class OcrResult(
    val textBlocks: List<TextBlock>,
    val fullText: String,
    val processingTimeMs: Long,
    val scriptType: ScriptType
)

/**
 * Interface listener để xử lý kết quả OCR
 */
interface OcrListener {
    fun onSuccess(result: OcrResult)
    fun onFailure(exception: Exception)
}

/**
 * Manager chính để xử lý OCR đa ngôn ngữ thông minh
 */
class OcrManager private constructor() { // 1. Làm constructor private
    
    // Cache các recognizer để tái sử dụng
    private val recognizers = mutableMapOf<ScriptType, TextRecognizer>()
    
    // Kích thước tối đa cho ảnh đầu vào
    private val maxImageDimension = 1280
    
    /**
     * Hàm chính để nhận dạng văn bản từ ảnh
     * @param bitmap Ảnh cần nhận dạng
     * @param sourceLanguageCode Mã ngôn ngữ (ví dụ: "ja", "zh", "en")
     * @param listener Listener để nhận kết quả
     */
    fun recognizeText(bitmap: Bitmap, sourceLanguageCode: String, listener: OcrListener) {
        val startTime = System.currentTimeMillis()
        
        try {
            // 1. Tiền xử lý ảnh
            val preprocessedBitmap = preprocessImage(bitmap)
            
            // 2. Xác định hệ chữ từ mã ngôn ngữ
            val scriptType = getScriptTypeFromLanguageCode(sourceLanguageCode)
            
            // 3. Lấy recognizer phù hợp
            val recognizer = getRecognizerForScript(scriptType)
            
            // 4. Tạo InputImage từ bitmap đã xử lý
            val inputImage = InputImage.fromBitmap(preprocessedBitmap, 0)
            
            // 5. Thực hiện nhận dạng
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val processingTime = System.currentTimeMillis() - startTime
                    val result = parseTextResult(visionText, scriptType, processingTime)
                    listener.onSuccess(result)
                }
                .addOnFailureListener { exception ->
                    listener.onFailure(exception)
                }
                
        } catch (e: Exception) {
            listener.onFailure(e)
        }
    }
    
    /**
     * Chuyển đổi mã ngôn ngữ thành ScriptType
     * @param langCode Mã ngôn ngữ ISO (ví dụ: "ja", "zh-cn", "ko")
     * @return ScriptType tương ứng
     */
    private fun getScriptTypeFromLanguageCode(langCode: String): ScriptType {
        return when (langCode.lowercase().take(2)) {
            "zh" -> ScriptType.CHINESE     // Tiếng Trung (包括简体和繁体)
            "ja" -> ScriptType.JAPANESE    // Tiếng Nhật
            "ko" -> ScriptType.KOREAN      // Tiếng Hàn
            "hi", "sa", "ne", "mr" -> ScriptType.DEVANAGARI  // Hindi, Sanskrit, Nepal, Marathi
            else -> ScriptType.LATIN       // Mặc định cho các ngôn ngữ khác
        }
    }
    
    /**
     * Lấy hoặc tạo recognizer cho hệ chữ cụ thể
     * @param script Loại hệ chữ
     * @return TextRecognizer tương ứng
     */
    private fun getRecognizerForScript(script: ScriptType): TextRecognizer {
        // Kiểm tra cache trước
        recognizers[script]?.let { return it }
        
        // Tạo recognizer mới nếu chưa có
        val recognizer = when (script) {
            ScriptType.CHINESE -> {
                val options = ChineseTextRecognizerOptions.Builder().build()
                TextRecognition.getClient(options)
            }
            ScriptType.JAPANESE -> {
                val options = JapaneseTextRecognizerOptions.Builder().build()
                TextRecognition.getClient(options)
            }
            ScriptType.KOREAN -> {
                val options = KoreanTextRecognizerOptions.Builder().build()
                TextRecognition.getClient(options)
            }
            ScriptType.DEVANAGARI -> {
                val options = DevanagariTextRecognizerOptions.Builder().build()
                TextRecognition.getClient(options)
            }
            ScriptType.LATIN -> {
                val options = TextRecognizerOptions.Builder().build()
                TextRecognition.getClient(options)
            }
        }
        
        // Lưu vào cache
        recognizers[script] = recognizer
        return recognizer
    }
    
    /**
     * Tiền xử lý ảnh để tối ưu cho OCR
     * @param bitmap Ảnh gốc
     * @return Ảnh đã được xử lý
     */
    private fun preprocessImage(bitmap: Bitmap): Bitmap {
        var processedBitmap = bitmap
        
        // 1. Giảm kích thước ảnh nếu quá lớn
        processedBitmap = resizeImageIfNeeded(processedBitmap)
        
        // 2. Tăng độ tương phản và chuyển sang grayscale
        processedBitmap = enhanceContrast(processedBitmap)
        
        return processedBitmap
    }
    
    /**
     * Giảm kích thước ảnh nếu vượt quá ngưỡng cho phép
     * @param bitmap Ảnh gốc
     * @return Ảnh đã được resize
     */
    private fun resizeImageIfNeeded(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        if (width <= maxImageDimension && height <= maxImageDimension) {
            return bitmap
        }
        
        val scaleFactor = if (width > height) {
            maxImageDimension.toFloat() / width
        } else {
            maxImageDimension.toFloat() / height
        }
        
        val newWidth = (width * scaleFactor).toInt()
        val newHeight = (height * scaleFactor).toInt()
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
    
    /**
     * Tăng độ tương phản và chuyển sang grayscale
     * @param bitmap Ảnh gốc
     * @return Ảnh đã được xử lý
     */
    private fun enhanceContrast(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        val processedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(processedBitmap)
        
        // Tạo matrix để chuyển sang grayscale và tăng contrast
        val colorMatrix = ColorMatrix().apply {
            // Chuyển sang grayscale
            setSaturation(0f)
            
            // Tăng contrast (điều chỉnh các giá trị này để tối ưu)
            val contrast = 1.5f
            val brightness = 10f
            
            val contrastMatrix = ColorMatrix(floatArrayOf(
                contrast, 0f, 0f, 0f, brightness,
                0f, contrast, 0f, 0f, brightness,
                0f, 0f, contrast, 0f, brightness,
                0f, 0f, 0f, 1f, 0f
            ))
            
            postConcat(contrastMatrix)
        }
        
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }
        
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return processedBitmap
    }
    
    /**
     * Chuyển đổi kết quả từ ML Kit thành format của ứng dụng
     * @param visionText Kết quả từ ML Kit
     * @param scriptType Loại hệ chữ đã sử dụng
     * @param processingTimeMs Thời gian xử lý
     * @return OcrResult đã được format
     */
    private fun parseTextResult(
        visionText: Text,
        scriptType: ScriptType,
        processingTimeMs: Long
    ): OcrResult {
        val textBlocks = visionText.textBlocks.map { block -> // `block` ở đây là Text.TextBlock
            val lines = block.lines.map { line ->
                val elements = line.elements.map { element ->
                    TextElement(
                        text = element.text,
                        boundingBox = element.boundingBox,
                        confidence = element.confidence // ĐÚNG: 'element' có thuộc tính confidence
                    )
                }
                TextLine(
                    text = line.text,
                    boundingBox = line.boundingBox,
                    confidence = line.confidence, // ĐÚNG: 'line' có thuộc tính confidence
                    elements = elements
                )
            }
            TextBlock(
                text = block.text,
                boundingBox = block.boundingBox,
                // SỬA LẠI: 'block' không có confidence, tính trung bình từ các dòng con
                confidence = lines.mapNotNull { it.confidence }.average().toFloat().takeIf { !it.isNaN() },
                lines = lines
            )
        }
        
        return OcrResult(
            textBlocks = textBlocks,
            fullText = visionText.text,
            processingTimeMs = processingTimeMs,
            scriptType = scriptType
        )
    }
    
    /**
     * Lọc kết quả OCR theo ngưỡng confidence
     * @param result Kết quả OCR gốc
     * @param minConfidence Ngưỡng confidence tối thiểu (0.0 - 1.0)
     * @return Kết quả OCR đã được lọc
     */
    fun filterByConfidence(result: OcrResult, minConfidence: Float = 0.5f): OcrResult {
        val filteredBlocks = result.textBlocks.mapNotNull { block ->
            val blockConfidence = block.confidence
            if (blockConfidence != null && blockConfidence < minConfidence) {
                return@mapNotNull null
            }
            
            val filteredLines = block.lines.mapNotNull { line ->
                val lineConfidence = line.confidence
                if (lineConfidence != null && lineConfidence < minConfidence) {
                    return@mapNotNull null
                }
                
                val filteredElements = line.elements.filter { element ->
                    val elementConfidence = element.confidence
                    elementConfidence == null || elementConfidence >= minConfidence
                }
                
                if (filteredElements.isEmpty()) null
                else line.copy(elements = filteredElements)
            }
            
            if (filteredLines.isEmpty()) null
            else block.copy(lines = filteredLines)
        }
        
        val filteredFullText = filteredBlocks.joinToString("\n") { it.text }
        
        return result.copy(
            textBlocks = filteredBlocks,
            fullText = filteredFullText
        )
    }
    
    /**
     * Giải phóng tài nguyên
     */
    fun cleanup() {
        recognizers.values.forEach { recognizer ->
            try {
                recognizer.close()
            } catch (e: Exception) {
                // Log error nếu cần
            }
        }
        recognizers.clear()
    }
    
    /**
     * Companion object chứa các utility functions và singleton pattern
     */
    companion object {
        @Volatile private var instance: OcrManager? = null

        // 2. Cung cấp hàm getInstance để truy cập
        fun getInstance(): OcrManager {
            return instance ?: synchronized(this) {
                instance ?: OcrManager().also { instance = it }
            }
        }
        
        /**
         * Kiểm tra xem ngôn ngữ có được hỗ trợ hay không
         * @param languageCode Mã ngôn ngữ
         * @return true nếu được hỗ trợ
         */
        fun isLanguageSupported(languageCode: String): Boolean {
            val supportedLanguages = setOf(
                "en", "es", "fr", "de", "it", "pt", "ru", "ar",  // Latin
                "zh", "zh-cn", "zh-tw",                          // Chinese
                "ja",                                            // Japanese
                "ko",                                            // Korean
                "hi", "sa", "ne", "mr"                          // Devanagari
            )
            return supportedLanguages.contains(languageCode.lowercase())
        }
        
        /**
         * Lấy danh sách tất cả ngôn ngữ được hỗ trợ
         * @return Set mã ngôn ngữ được hỗ trợ
         */
        fun getSupportedLanguages(): Set<String> {
            return setOf(
                "en", "es", "fr", "de", "it", "pt", "ru", "ar",
                "zh", "zh-cn", "zh-tw",
                "ja", "ko",
                "hi", "sa", "ne", "mr"
            )
        }
    }
}
