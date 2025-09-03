package com.example.apptranslate.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import com.example.apptranslate.databinding.OverlayTranslationResultBinding

@SuppressLint("ViewConstructor")
class TranslationResultView(context: Context) : FrameLayout(context) {

    companion object {
        private const val TAG = "TranslationResultView"
    }

    private val binding: OverlayTranslationResultBinding
    private var originalWidth: Int = 0
    private var originalHeight: Int = 0
    private var onSizeChangeListener: ((newWidth: Int, newHeight: Int) -> Unit)? = null

    init {
        val inflater = LayoutInflater.from(context)
        binding = OverlayTranslationResultBinding.inflate(inflater, this, true)

        // Không dùng auto-resize mặc định nữa, sẽ tự implement
        // setupAutoResizeText()
        
        // Đặt màu chữ tương phản với nền box
        val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val textColor = if (isNight) {
            context.getColor(android.R.color.white)
        } else {
            context.getColor(android.R.color.black)
        }
        binding.tvTranslatedText.setTextColor(textColor)
        
        // Set text style như ImageTranslationOverlay
        binding.tvTranslatedText.apply {
            textSize = 14f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            includeFontPadding = false
            setShadowLayer(1f, 0f, 1f, android.graphics.Color.parseColor("#40000000"))
        }
    }

    fun setOnSizeChangeListener(listener: (newWidth: Int, newHeight: Int) -> Unit) {
        onSizeChangeListener = listener
    }

    private fun setupAutoResizeText() {
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            binding.tvTranslatedText,
            8, // minTextSize (sp)
            16, // maxTextSize (sp)
            1, // granularity (sp)
            TypedValue.COMPLEX_UNIT_SP
        )
    }

    fun showLoading() {
        binding.tvTranslatedText.isVisible = false
        binding.loadingIndicator.isVisible = true
    }

    fun hideLoading() {
        binding.loadingIndicator.isVisible = false
        binding.tvTranslatedText.isVisible = true
    }

    fun initializeSize(width: Int, height: Int) {
        originalWidth = width
        originalHeight = height
    }

    fun updateText(text: String) {
        binding.loadingIndicator.isVisible = false
        binding.tvTranslatedText.isVisible = true
        
        Log.d(TAG, "Updating text: '$text', original size: ${originalWidth}x${originalHeight}")
        
        if (originalWidth > 0 && originalHeight > 0) {
            // Đầu tiên set text trước
            binding.tvTranslatedText.text = text
            
            val (finalWidth, finalHeight, finalTextSize) = calculateOptimalSizeAndText(text, originalWidth, originalHeight)
            
            Log.d(TAG, "Calculated optimal: ${finalWidth}x${finalHeight}, textSize: $finalTextSize")
            
            // Cập nhật text size
            binding.tvTranslatedText.textSize = finalTextSize
            
            // Nếu size thay đổi, thông báo cho OverlayService
            if (finalWidth != originalWidth || finalHeight != originalHeight) {
                Log.d(TAG, "Size changed from ${originalWidth}x${originalHeight} to ${finalWidth}x${finalHeight}")
                onSizeChangeListener?.invoke(finalWidth, finalHeight)
            }
        } else {
            // Fallback nếu chưa initialize
            Log.d(TAG, "Using fallback - no original size set")
            binding.tvTranslatedText.text = text
            setupAutoResizeText() // Dùng auto-resize mặc định
        }
    }
    
    private fun calculateOptimalSizeAndText(text: String, originalWidth: Int, originalHeight: Int): Triple<Int, Int, Float> {
        val maxTextSize = 16f // Cỡ chữ gốc/lớn nhất
        val minTextSize = 10f // Cỡ chữ nhỏ nhất
        
        Log.d(TAG, "Starting calculation for text: '$text'")
        Log.d(TAG, "Original box size: ${originalWidth}x${originalHeight}")
        
        // BƯỚC 1: Thử cỡ chữ gốc (lớn nhất) với box gốc
        Log.d(TAG, "Step 1: Trying original text size ($maxTextSize) with original box")
        if (doesTextFitInBox(text, maxTextSize, originalWidth, originalHeight)) {
            Log.d(TAG, "✓ Perfect! Text fits with original size: $maxTextSize")
            return Triple(originalWidth, originalHeight, maxTextSize)
        }
        
        // BƯỚC 2: Thu nhỏ cỡ chữ cho vừa box gốc
        Log.d(TAG, "Step 2: Trying smaller text sizes with original box")
        var textSize = maxTextSize - 1f
        while (textSize >= minTextSize) {
            if (doesTextFitInBox(text, textSize, originalWidth, originalHeight)) {
                Log.d(TAG, "✓ Text fits with smaller size: $textSize in original box")
                return Triple(originalWidth, originalHeight, textSize)
            }
            Log.d(TAG, "  Size $textSize still doesn't fit, trying smaller...")
            textSize -= 1f
        }
        
        // BƯỚC 3: Cỡ chữ nhỏ nhất vẫn không vừa -> giãn box cho vừa với cỡ chữ nhỏ nhất
        Log.d(TAG, "Step 3: Even minimum size ($minTextSize) doesn't fit, expanding box...")
        val (requiredWidth, requiredHeight) = calculateRequiredBoxSize(text, minTextSize)
        
        // Tính toán kích thước box cần thiết
        val newWidth = maxOf(originalWidth, requiredWidth)
        val newHeight = maxOf(originalHeight, requiredHeight)
        
        Log.d(TAG, "✓ Final solution: size=$minTextSize, expanded box: ${newWidth}x${newHeight}")
        Log.d(TAG, "  Width expansion: ${newWidth - originalWidth}px")
        Log.d(TAG, "  Height expansion: ${newHeight - originalHeight}px")
        
        return Triple(newWidth, newHeight, minTextSize)
    }
    
    /**
     * Tính toán kích thước box cần thiết cho text với size nhất định
     */
    private fun calculateRequiredBoxSize(text: String, textSize: Float): Pair<Int, Int> {
        val tempTextView = TextView(context).apply {
            this.text = text
            this.textSize = textSize
            setPadding(3, 3, 3, 3)
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            includeFontPadding = false
            gravity = Gravity.CENTER
        }
        
        // Measure với width không giới hạn
        tempTextView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        
        val requiredWidth = tempTextView.measuredWidth + 10 // Thêm margin an toàn
        val requiredHeight = tempTextView.measuredHeight + 10
        
        return Pair(requiredWidth, requiredHeight)
    }
    
    private fun doesTextFitInBox(text: String, textSize: Float, boxWidth: Int, boxHeight: Int): Boolean {
        if (boxWidth <= 0 || boxHeight <= 0) return false
        
        // Tạo TextView giống hệt với TextView thực tế
        val testView = TextView(context).apply {
            this.text = text
            this.textSize = textSize
            setPadding(3, 3, 3, 3) // Padding giống với layout
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            includeFontPadding = false
            gravity = Gravity.CENTER
            
            // Không set ellipsize để kiểm tra text có bị cắt không
            ellipsize = null
            maxLines = 3 // Cho phép tối đa 3 dòng như trong layout
        }
        
        val availableWidth = boxWidth - 6 // Trừ padding (3*2)
        val availableHeight = boxHeight - 6 // Trừ padding (3*2)
        
        // Measure với width cố định để cho phép text xuống dòng
        testView.measure(
            View.MeasureSpec.makeMeasureSpec(availableWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        
        val textActualHeight = testView.measuredHeight
        
        val fits = textActualHeight <= availableHeight
        
        Log.d(TAG, "Text fit check: '$text' size=$textSize")
        Log.d(TAG, "  Text needs height: $textActualHeight (with multiline)")
        Log.d(TAG, "  Box has: ${availableWidth}x${availableHeight}")
        Log.d(TAG, "  Fits: $fits")
        
        return fits
    }
}