package com.example.apptranslate.ocr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import java.io.IOException

/**
 * Helper class để tích hợp OcrManager với UI components
 */
class OcrHelper(private val context: Context) {
    
    private val ocrManager = OcrManager.getInstance() // Sử dụng singleton pattern
    
    // LiveData để theo dõi trạng thái OCR
    private val _isProcessing = MutableLiveData<Boolean>()
    val isProcessing: MutableLiveData<Boolean> = _isProcessing
    
    private val _ocrResult = MutableLiveData<OcrResult>()
    val ocrResult: MutableLiveData<OcrResult> = _ocrResult
    
    private val _error = MutableLiveData<String>()
    val error: MutableLiveData<String> = _error
    
    /**
     * Nhận dạng văn bản từ Uri ảnh
     * @param imageUri Uri của ảnh
     * @param languageCode Mã ngôn ngữ
     */
    fun recognizeTextFromUri(imageUri: Uri, languageCode: String) {
        _isProcessing.value = true
        
        try {
            val bitmap = MediaStore.Images.Media.getBitmap(context.contentResolver, imageUri)
            recognizeTextFromBitmap(bitmap, languageCode)
        } catch (e: IOException) {
            _isProcessing.value = false
            _error.value = "Không thể đọc ảnh: ${e.message}"
        }
    }
    
    /**
     * Nhận dạng văn bản từ Bitmap
     * @param bitmap Ảnh bitmap
     * @param languageCode Mã ngôn ngữ
     */
    fun recognizeTextFromBitmap(bitmap: Bitmap, languageCode: String) {
        _isProcessing.value = true
        
        // Kiểm tra ngôn ngữ có được hỗ trợ không
        if (!OcrManager.isLanguageSupported(languageCode)) {
            _isProcessing.value = false
            _error.value = "Ngôn ngữ '$languageCode' chưa được hỗ trợ"
            return
        }
        
        ocrManager.recognizeText(bitmap, languageCode, object : OcrListener {
            override fun onSuccess(result: OcrResult) {
                _isProcessing.value = false
                _ocrResult.value = result
            }
            
            override fun onFailure(exception: Exception) {
                _isProcessing.value = false
                _error.value = "Lỗi OCR: ${exception.message}"
            }
        })
    }
    
    /**
     * Thiết lập observers cho lifecycle-aware components
     * @param lifecycleOwner LifecycleOwner (Activity/Fragment)
     * @param onResult Callback khi có kết quả
     * @param onError Callback khi có lỗi
     * @param onProcessingChange Callback khi trạng thái xử lý thay đổi
     */
    fun setupObservers(
        lifecycleOwner: LifecycleOwner,
        onResult: (OcrResult) -> Unit,
        onError: (String) -> Unit,
        onProcessingChange: (Boolean) -> Unit
    ) {
        ocrResult.observe(lifecycleOwner, Observer { result ->
            result?.let { onResult(it) }
        })
        
        error.observe(lifecycleOwner, Observer { errorMsg ->
            errorMsg?.let { onError(it) }
        })
        
        isProcessing.observe(lifecycleOwner, Observer { processing ->
            onProcessingChange(processing)
        })
    }
    
    /**
     * Lấy thống kê từ kết quả OCR
     * @param result Kết quả OCR
     * @return Map chứa thống kê
     */
    fun getOcrStatistics(result: OcrResult): Map<String, Any> {
        val stats = mutableMapOf<String, Any>()
        
        stats["totalBlocks"] = result.textBlocks.size
        stats["totalLines"] = result.textBlocks.sumOf { it.lines.size }
        stats["totalElements"] = result.textBlocks.sumOf { block ->
            block.lines.sumOf { it.elements.size }
        }
        stats["totalCharacters"] = result.fullText.length
        stats["processingTimeMs"] = result.processingTimeMs
        stats["scriptType"] = result.scriptType.name
        stats["averageConfidence"] = calculateAverageConfidence(result)
        
        return stats
    }
    
    /**
     * Tính confidence trung bình
     * @param result Kết quả OCR
     * @return Confidence trung bình (0-1)
     */
    private fun calculateAverageConfidence(result: OcrResult): Float {
        val allConfidences = mutableListOf<Float>()
        
        result.textBlocks.forEach { block ->
            block.confidence?.let { allConfidences.add(it) }
            block.lines.forEach { line ->
                line.confidence?.let { allConfidences.add(it) }
                line.elements.forEach { element ->
                    element.confidence?.let { allConfidences.add(it) }
                }
            }
        }
        
        return if (allConfidences.isNotEmpty()) {
            allConfidences.average().toFloat()
        } else {
            0f
        }
    }
    
    /**
     * Xuất kết quả OCR dưới dạng format khác nhau
     * @param result Kết quả OCR
     * @param format Format xuất ("plain", "json", "structured")
     * @return String đã format
     */
    fun exportResult(result: OcrResult, format: String = "plain"): String {
        return when (format.lowercase()) {
            "plain" -> result.fullText
            "json" -> exportAsJson(result)
            "structured" -> exportAsStructured(result)
            else -> result.fullText
        }
    }
    
    private fun exportAsJson(result: OcrResult): String {
        // Simplified JSON export - trong thực tế nên dùng library như Gson
        val jsonBuilder = StringBuilder()
        jsonBuilder.append("{\n")
        jsonBuilder.append("  \"fullText\": \"${result.fullText.replace("\"", "\\\"")}\",\n")
        jsonBuilder.append("  \"processingTimeMs\": ${result.processingTimeMs},\n")
        jsonBuilder.append("  \"scriptType\": \"${result.scriptType.name}\",\n")
        jsonBuilder.append("  \"blocksCount\": ${result.textBlocks.size}\n")
        jsonBuilder.append("}")
        return jsonBuilder.toString()
    }
    
    private fun exportAsStructured(result: OcrResult): String {
        val builder = StringBuilder()
        builder.append("=== OCR RESULT ===\n")
        builder.append("Script Type: ${result.scriptType.name}\n")
        builder.append("Processing Time: ${result.processingTimeMs}ms\n")
        builder.append("Total Blocks: ${result.textBlocks.size}\n\n")
        
        result.textBlocks.forEachIndexed { blockIndex, block ->
            builder.append("Block ${blockIndex + 1}:\n")
            builder.append("Text: ${block.text}\n")
            block.confidence?.let { builder.append("Confidence: $it\n") }
            builder.append("\n")
        }
        
        return builder.toString()
    }
    
    /**
     * Dọn dẹp tài nguyên
     */
    fun cleanup() {
        ocrManager.cleanup()
    }
    
    companion object {
        /**
         * Tạo instance singleton của OcrHelper
         */
        @Volatile
        private var INSTANCE: OcrHelper? = null
        
        fun getInstance(context: Context): OcrHelper {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: OcrHelper(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
