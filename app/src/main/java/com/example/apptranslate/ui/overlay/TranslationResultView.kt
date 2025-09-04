package com.example.apptranslate.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
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

    // Smart expansion properties
    private var screenBounds: Rect = Rect()
    private var usedRects: List<Rect> = emptyList()
    private var currentPosition: Rect = Rect()

    init {
        val inflater = LayoutInflater.from(context)
        binding = OverlayTranslationResultBinding.inflate(inflater, this, true)

        // Đặt màu chữ tương phản với nền box
        val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val textColor = if (isNight) {
            context.getColor(android.R.color.white)
        } else {
            context.getColor(android.R.color.black)
        }
        binding.tvTranslatedText.setTextColor(textColor)

        // Set text style giống ImageTranslationOverlay - KHÔNG in đậm
        binding.tvTranslatedText.apply {
            setTypeface(android.graphics.Typeface.DEFAULT) // Bỏ in đậm
            includeFontPadding = false
            gravity = Gravity.CENTER
            setPadding(3, 3, 3, 3) // Padding giống ImageTranslationOverlay
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
    }

    fun setOnSizeChangeListener(listener: (newWidth: Int, newHeight: Int) -> Unit) {
        onSizeChangeListener = listener
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

        // Cập nhật layout params ngay lập tức
        val params = layoutParams ?: LayoutParams(width, height)
        params.width = width
        params.height = height
        layoutParams = params

        Log.d(TAG, "Initialized size: ${width}x${height}")
    }

    /**
     * Cập nhật kích thước sau khi smart expansion
     */
    fun updateSize(newWidth: Int, newHeight: Int) {
        originalWidth = newWidth
        originalHeight = newHeight

        val params = layoutParams ?: LayoutParams(newWidth, newHeight)
        params.width = newWidth
        params.height = newHeight
        layoutParams = params

        requestLayout()
        Log.d(TAG, "Updated size to: ${newWidth}x${newHeight}")
    }

    /**
     * Thiết lập thông tin môi trường để hỗ trợ smart expansion
     */
    fun setEnvironmentInfo(screenBounds: Rect, usedRects: List<Rect>, currentPosition: Rect) {
        this.screenBounds = screenBounds
        this.usedRects = usedRects
        this.currentPosition = currentPosition
    }

    fun updateText(text: String) {
        Log.d(TAG, "Updating text with ImageTranslation-style logic: '$text'")
        Log.d(TAG, "Original size: ${originalWidth}x${originalHeight}")

        // Ẩn loading, hiện text
        binding.loadingIndicator.isVisible = false
        binding.tvTranslatedText.isVisible = true

        if (originalWidth > 0 && originalHeight > 0) {
            // Sử dụng logic giống ImageTranslationOverlay
            val originalRect = android.graphics.Rect(0, 0, originalWidth, originalHeight)
            val (finalWidth, finalHeight, finalTextSize) = calculateOptimalSizeAndText(text, originalRect)

            Log.d(TAG, "Calculated optimal: ${finalWidth}x${finalHeight}, textSize: $finalTextSize")

            // Set text và text size
            binding.tvTranslatedText.text = text
            binding.tvTranslatedText.textSize = finalTextSize

            // Cập nhật kích thước nếu cần
            if (finalWidth != originalWidth || finalHeight != originalHeight) {
                Log.d(TAG, "Size changed, triggering callback")
                originalWidth = finalWidth
                originalHeight = finalHeight
                onSizeChangeListener?.invoke(finalWidth, finalHeight)
            }
        } else {
            // Fallback đơn giản
            binding.tvTranslatedText.text = text
            val defaultTextSize = calculateDefaultTextSize(text)
            binding.tvTranslatedText.textSize = defaultTextSize
            Log.d(TAG, "No original size, using default textSize: $defaultTextSize")
        }
    }

    /**
     * Tính toán kích thước và text size tối ưu giống ImageTranslationOverlay
     */
    private fun calculateOptimalSizeAndText(text: String, originalRect: android.graphics.Rect): Triple<Int, Int, Float> {
        var currentRect = android.graphics.Rect(originalRect)

        // Bước 1: Tính text size dựa vào chiều cao của bounding box (giống ImageTranslationOverlay)
        var textSize = when {
            originalRect.height() < 25 -> 9f
            originalRect.height() < 35 -> 11f
            originalRect.height() < 50 -> 13f
            originalRect.height() < 70 -> 15f
            else -> 17f
        }

        val minTextSize = 8f
        Log.d(TAG, "Starting with text size: $textSize for box height: ${originalRect.height()}")

        // Bước 2: Thử thu nhỏ cỡ chữ trước khi mở rộng box
        while (textSize >= minTextSize) {
            if (doesTextFitInBox(text, textSize, currentRect.width(), currentRect.height())) {
                Log.d(TAG, "Text fits with size: $textSize")
                return Triple(currentRect.width(), currentRect.height(), textSize)
            }
            textSize -= 0.5f
        }

        // Bước 3: Nếu cỡ chữ đã nhỏ nhất mà vẫn không vừa, mở rộng box
        Log.d(TAG, "Text doesn't fit even with min size, expanding box...")
        currentRect = expandBoxInAvailableDirections(originalRect, text, minTextSize)

        return Triple(currentRect.width(), currentRect.height(), minTextSize)
    }

    /**
     * Mở rộng box theo hướng có chỗ trống (đơn giản hóa từ ImageTranslationOverlay)
     */
    private fun expandBoxInAvailableDirections(originalRect: android.graphics.Rect, text: String, textSize: Float): android.graphics.Rect {
        val expandedRect = android.graphics.Rect(originalRect)

        // Tính kích thước cần thiết cho text
        val requiredSize = calculateRequiredBoxSize(text, textSize)
        val widthNeeded = maxOf(0, requiredSize.first - originalRect.width())
        val heightNeeded = maxOf(0, requiredSize.second - originalRect.height())

        Log.d(TAG, "Need to expand: width+$widthNeeded, height+$heightNeeded")

        // Mở rộng width
        if (widthNeeded > 0) {
            val expandLeft = widthNeeded / 2
            val expandRight = widthNeeded - expandLeft
            expandedRect.left -= expandLeft
            expandedRect.right += expandRight
        }

        // Mở rộng height
        if (heightNeeded > 0) {
            val expandTop = heightNeeded / 2
            val expandBottom = heightNeeded - expandTop
            expandedRect.top -= expandTop
            expandedRect.bottom += expandBottom
        }

        Log.d(TAG, "Expanded from ${originalRect.width()}x${originalRect.height()} to ${expandedRect.width()}x${expandedRect.height()}")
        return expandedRect
    }

    /**
     * Kiểm tra text có vừa trong box không (giống ImageTranslationOverlay)
     */
    private fun doesTextFitInBox(text: String, textSize: Float, boxWidth: Int, boxHeight: Int): Boolean {
        if (boxWidth <= 0 || boxHeight <= 0) return false

        val testView = TextView(context).apply {
            this.text = text
            this.textSize = textSize
            setPadding(3, 3, 3, 3) // Padding giống ImageTranslationOverlay
            setTypeface(android.graphics.Typeface.DEFAULT)
            includeFontPadding = false
            gravity = Gravity.CENTER
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        testView.measure(
            View.MeasureSpec.makeMeasureSpec(boxWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(boxHeight, View.MeasureSpec.EXACTLY)
        )

        testView.layout(0, 0, boxWidth, boxHeight)
        val layout = testView.layout ?: return false

        // Kiểm tra xem có bị ellipsize không
        val lastLine = layout.lineCount - 1
        val hasEllipsis = layout.getEllipsisCount(lastLine) > 0

        // Kiểm tra xem text có vượt quá chiều cao không
        val textHeight = layout.height
        val availableHeight = boxHeight - testView.paddingTop - testView.paddingBottom

        return !hasEllipsis && textHeight <= availableHeight
    }

    /**
     * Tính kích thước box cần thiết cho text
     */
    private fun calculateRequiredBoxSize(text: String, textSize: Float): Pair<Int, Int> {
        val tempTextView = TextView(context).apply {
            this.text = text
            this.textSize = textSize
            setPadding(3, 3, 3, 3) // Padding giống ImageTranslationOverlay
            typeface = Typeface.DEFAULT
            includeFontPadding = false
            gravity = Gravity.CENTER
        }

        // Measure với width hợp lý
        val screenWidth = context.resources.displayMetrics.widthPixels
        val maxReasonableWidth = (screenWidth * 0.8f).toInt()

        tempTextView.measure(
            View.MeasureSpec.makeMeasureSpec(maxReasonableWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        return Pair(tempTextView.measuredWidth, tempTextView.measuredHeight)
    }

    /**
     * Tính text size mặc định cho fallback case
     */
    private fun calculateDefaultTextSize(text: String): Float {
        return when {
            text.length < 10 -> 16f
            text.length < 30 -> 14f
            text.length < 60 -> 12f
            else -> 10f
        }
    }
}