package com.example.apptranslate.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import com.example.apptranslate.databinding.OverlayTranslationResultBinding
import kotlin.math.min

@SuppressLint("ViewConstructor")
class TranslationResultView(context: Context) : FrameLayout(context) {

    private val binding: OverlayTranslationResultBinding
    private var targetWidth = 0
    private var targetHeight = 0

    init {
        val inflater = LayoutInflater.from(context)
        binding = OverlayTranslationResultBinding.inflate(inflater, this, true)
        
        // Thiết lập auto-resize text thông minh hơn
        setupAutoResizeText()
    }

    private fun setupAutoResizeText() {
        // Thiết lập auto-size với khoảng min-max nhỏ hơn để tránh chồng chéo
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            binding.tvTranslatedText,
            6, // minTextSize - giảm từ 8 xuống 6
            12, // maxTextSize - giảm từ 16 xuống 12
            1, // granularity
            TypedValue.COMPLEX_UNIT_SP
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
        
        // Điều chỉnh layout sau khi set text
        adjustLayoutForText(text)
    }

    // HÀM CẢI TIẾN: Thiết lập kích thước thông minh
    fun setSize(width: Int, height: Int) {
        targetWidth = width
        targetHeight = height
        
        // Thiết lập max width/height dựa trên target size
        val maxWidth = if (width > 0) width else 300
        val maxHeight = if (height > 0) height else 100
        
        binding.tvTranslatedText.maxWidth = maxWidth
        binding.tvTranslatedText.maxHeight = maxHeight
        
        // Điều chỉnh layout parameters của container
        val layoutParams = this.layoutParams ?: ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        
        // Giới hạn kích thước container
        if (layoutParams is ViewGroup.MarginLayoutParams) {
            layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
            layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        
        this.layoutParams = layoutParams
    }
    
    private fun adjustLayoutForText(text: String) {
        // Điều chỉnh số dòng tối đa dựa trên độ dài text và kích thước target
        val textLength = text.length
        
        // Ước tính tỷ lệ text dài so với kích thước box
        val estimatedTextWidth = textLength * 7 // Ước tính mỗi ký tự ~7px với font nhỏ
        val expansionRatio = if (targetWidth > 0) estimatedTextWidth.toFloat() / targetWidth else 1f
        
        val maxLines = when {
            targetHeight <= 40 -> 1                           // Box rất nhỏ chỉ 1 dòng
            expansionRatio > 3.0 -> 4                         // Text dài gấp 3 lần box -> 4 dòng
            expansionRatio > 2.0 -> 3                         // Text dài gấp 2 lần box -> 3 dòng
            expansionRatio > 1.5 || textLength > 40 -> 2      // Text dài 1.5 lần hoặc >40 ký tự -> 2 dòng
            else -> 1                                          // Text ngắn -> 1 dòng
        }
        
        binding.tvTranslatedText.maxLines = maxLines
        
        // Điều chỉnh font size dựa trên target size - làm nhỏ hơn để tránh chồng chéo
        if (targetWidth > 0 && targetHeight > 0) {
            // Font size nhỏ hơn khi text dài để tránh chồng chéo
            val fontReduction = when {
                expansionRatio > 2.5 -> 2  // Giảm 2sp khi text rất dài
                expansionRatio > 1.8 -> 1  // Giảm 1sp khi text hơi dài
                else -> 0                  // Không giảm khi text vừa phải
            }
            
            val minTextSize = when {
                targetWidth < 100 || targetHeight < 30 -> maxOf(5, 6 - fontReduction)
                targetWidth < 200 || targetHeight < 50 -> maxOf(5, 7 - fontReduction)
                else -> maxOf(6, 8 - fontReduction)
            }
            
            val maxTextSize = when {
                targetWidth > 300 || targetHeight > 80 -> maxOf(8, 12 - fontReduction)
                targetWidth > 200 || targetHeight > 60 -> maxOf(7, 10 - fontReduction)
                else -> maxOf(6, 9 - fontReduction)
            }
            
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                binding.tvTranslatedText,
                minTextSize,
                maxTextSize,
                1,
                TypedValue.COMPLEX_UNIT_SP
            )
        }
        
        // Force measure và layout
        requestLayout()
    }
}