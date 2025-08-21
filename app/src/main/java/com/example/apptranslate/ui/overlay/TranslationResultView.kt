package com.example.apptranslate.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import com.example.apptranslate.databinding.OverlayTranslationResultBinding
import kotlin.math.max
import kotlin.math.min

@SuppressLint("ViewConstructor")
class TranslationResultView(context: Context) : FrameLayout(context) {

    private val binding: OverlayTranslationResultBinding

    init {
        val inflater = LayoutInflater.from(context)
        binding = OverlayTranslationResultBinding.inflate(inflater, this, true)
        // Kích hoạt tính năng tự động điều chỉnh cỡ chữ
        TextViewCompat.setAutoSizeTextTypeWithDefaults(
            binding.tvTranslatedText,
            TextViewCompat.AUTO_SIZE_TEXT_TYPE_UNIFORM
        )
    }

    fun showLoading() {
        binding.tvTranslatedText.isVisible = false
        binding.loadingIndicator.isVisible = true
    }

    fun updateText(text: String) {
        binding.loadingIndicator.isVisible = false
        binding.tvTranslatedText.isVisible = true
        binding.tvTranslatedText.text = text
    }

    // HÀM MỚI: Thiết lập bounding box dựa trên text gốc
    fun setBoundingBox(originalBounds: Rect, originalText: String, translatedText: String) {
        val originalWidth = originalBounds.width()
        val originalHeight = originalBounds.height()
        
        // Tính toán tỷ lệ text để ước tính kích thước cần thiết
        val lengthRatio = if (originalText.isNotEmpty()) {
            translatedText.length.toFloat() / originalText.length.toFloat()
        } else {
            1.0f
        }
        
        // Điều chỉnh width dựa trên tỷ lệ độ dài text, nhưng có giới hạn
        val estimatedWidth = (originalWidth * lengthRatio).toInt()
        val finalWidth = max(originalWidth, min(estimatedWidth, originalWidth * 2))
        
        // Height giữ tương tự hoặc tăng ít nếu text dài hơn nhiều
        val finalHeight = if (lengthRatio > 1.5f) {
            max(originalHeight, (originalHeight * 1.2f).toInt())
        } else {
            originalHeight
        }
        
        // Set kích thước cho container
        layoutParams = layoutParams?.apply {
            width = finalWidth
            height = finalHeight
        } ?: ViewGroup.LayoutParams(finalWidth, finalHeight)
        
        // Set kích thước tối đa cho TextView
        binding.tvTranslatedText.maxWidth = finalWidth
        binding.tvTranslatedText.maxHeight = finalHeight
        
        // Điều chỉnh text size dựa trên kích thước bounding box
        adjustTextSize(finalWidth, finalHeight, translatedText)
    }
    
    private fun adjustTextSize(width: Int, height: Int, text: String) {
        // Tính toán text size phù hợp dựa trên kích thước bounding box
        val density = resources.displayMetrics.density
        
        // Base text size dựa trên height của bounding box
        val baseTextSize = when {
            height <= 30 * density -> 12f  // Text nhỏ
            height <= 50 * density -> 14f  // Text trung bình
            height <= 80 * density -> 16f  // Text lớn
            else -> 18f                     // Text rất lớn
        }
        
        // Điều chỉnh thêm dựa trên width và độ dài text
        val adjustedTextSize = if (text.length > 20 && width < 200 * density) {
            baseTextSize * 0.9f  // Giảm size nếu text dài nhưng width hẹp
        } else {
            baseTextSize
        }
        
        // Thiết lập auto-size với range phù hợp
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            binding.tvTranslatedText,
            (adjustedTextSize * 0.7f).toInt(),  // Min size
            adjustedTextSize.toInt(),            // Max size
            1,                                   // Step size
            TypedValue.COMPLEX_UNIT_SP
        )
    }

    // HÀM CŨ: Nhận kích thước của vùng text gốc (để backward compatibility)
    fun setSize(width: Int, height: Int) {
        layoutParams = layoutParams?.apply {
            this.width = width
            this.height = height
        } ?: ViewGroup.LayoutParams(width, height)
        
        binding.tvTranslatedText.maxWidth = width
        binding.tvTranslatedText.maxHeight = height
    }
}