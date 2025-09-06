// File: app/src/main/java/com/example/apptranslate/ui/overlay/TranslationResultView.kt

package com.example.apptranslate.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.example.apptranslate.R

// ✨ EXPERIMENT: Interface để thay thế databinding
interface TranslationResultBinding {
    val tvTranslatedText: TextView
    val loadingIndicator: android.widget.ProgressBar
    val root: FrameLayout
}

@SuppressLint("ViewConstructor")
class TranslationResultView(context: Context) : FrameLayout(context) {

    companion object {
        private const val TAG = "TranslationResultView"
    }

    private val binding: TranslationResultBinding
    private var originalWidth: Int = 0
    private var originalHeight: Int = 0
    private var onSizeChangeListener: ((newWidth: Int, newHeight: Int) -> Unit)? = null

    // NEW: Biến để lưu ranh giới và các box khác
    private var screenBounds: Rect = Rect()
    private var usedRects: List<Rect> = emptyList()

    init {
        // ✨ EXPERIMENT: Tạo TextView programmatically như ImageTranslationOverlay
        setBackgroundResource(R.drawable.translation_text_background)

        val textView = TextView(context).apply {
            id = View.generateViewId()
            setTypeface(Typeface.DEFAULT)
            includeFontPadding = false
            gravity = Gravity.CENTER
            setPadding(3, 3, 3, 3) // ✨ COPY EXACTLY từ ImageTranslationOverlay
            setTextColor(resources.getColor(R.color.primary_text, null))
            ellipsize = android.text.TextUtils.TruncateAt.END // ✨ COPY từ ImageTranslationOverlay

            // 🔧 Multi-line setup
            maxLines = 5
            setSingleLine(false)
            setLineSpacing(2f, 1.1f)
        }

        addView(textView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Tạo ProgressBar
        val progressBar = android.widget.ProgressBar(context).apply {
            id = View.generateViewId()
            visibility = View.GONE
        }

        addView(progressBar, FrameLayout.LayoutParams(
            40.dpToPx(), 40.dpToPx(), Gravity.CENTER
        ))

        // Tạo binding object để tương thích với code hiện tại
        binding = object : TranslationResultBinding {
            override val tvTranslatedText: TextView = textView
            override val loadingIndicator: android.widget.ProgressBar = progressBar
            override val root: FrameLayout = this@TranslationResultView
        }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    fun setOnSizeChangeListener(listener: (newWidth: Int, newHeight: Int) -> Unit) {
        onSizeChangeListener = listener
    }

    // ✨ NEW: Thiết lập kích thước ban đầu
    fun setOriginalSize(width: Int, height: Int) {
        originalWidth = width
        originalHeight = height
        Log.d("TranslationView", "🎯 setOriginalSize: ${width}×${height}")
    }

    fun showLoading() {
        binding.tvTranslatedText.isVisible = false
        binding.loadingIndicator.isVisible = true
    }

    // MODIFIED: Hàm này giờ sẽ nhận cả danh sách các box khác
    fun setEnvironment(bounds: Rect, otherUsedRects: List<Rect>) {
        this.screenBounds = bounds
        this.usedRects = otherUsedRects
    }

    fun initializeSize(width: Int, height: Int) {
        originalWidth = width
        originalHeight = height
    }

    fun updateText(text: String, forcedTextSize: Float? = null) {
        binding.loadingIndicator.isVisible = false
        binding.tvTranslatedText.isVisible = true

        // ✨ CRITICAL FIX: Đảm bảo luôn có kích thước hợp lý
        val workingWidth = if (originalWidth > 0) originalWidth else layoutParams?.width ?: 200
        val workingHeight = if (originalHeight > 0) originalHeight else layoutParams?.height ?: 100
        
        Log.d("TranslationView", "🔧 updateText: originalSize=${originalWidth}×${originalHeight}, workingSize=${workingWidth}×${workingHeight}")

        if (workingWidth > 0 && workingHeight > 0) {
            val (finalWidth, finalHeight, finalTextSize) = calculateOptimalSizeAndText(text, Rect(0, 0, workingWidth, workingHeight))

            binding.tvTranslatedText.text = text
            binding.tvTranslatedText.setTextSize(TypedValue.COMPLEX_UNIT_SP, forcedTextSize ?: finalTextSize)

            // 🔧 CRITICAL FIX: Force update layout params để TextView sử dụng đúng kích thước
            binding.tvTranslatedText.layoutParams = FrameLayout.LayoutParams(finalWidth, finalHeight).apply {
                gravity = Gravity.CENTER
            }

            // ✨ DEBUG: Log TextView settings để kiểm tra
            Log.d("TranslationView", """
                📺 TEXTVIEW SETUP:
                🔤 Text: ${text.take(30)}${if (text.length > 30) "..." else ""}
                📏 Text size: ${forcedTextSize ?: finalTextSize}sp
                📦 Box size: ${finalWidth}×${finalHeight}px
                ⚙️ MaxLines: ${binding.tvTranslatedText.maxLines}
                ⚙️ SingleLine: ${binding.tvTranslatedText.isSingleLine}
                ⚙️ Gravity: ${binding.tvTranslatedText.gravity}
                📐 Layout size: ${binding.tvTranslatedText.layoutParams.width}×${binding.tvTranslatedText.layoutParams.height}
            """.trimIndent())

            if (finalWidth != originalWidth || finalHeight != originalHeight) {
                onSizeChangeListener?.invoke(finalWidth, finalHeight)
            }
        } else {
            // Fallback cho trường hợp không có kích thước
            Log.w("TranslationView", "⚠️ No valid size available, using fallback")
            binding.tvTranslatedText.text = text
            binding.tvTranslatedText.setTextSize(TypedValue.COMPLEX_UNIT_SP, forcedTextSize ?: 12f)
        }
    }

    // ✨ COPIED FROM ImageTranslationOverlay: Kiểm tra text có vừa với box không  
    private fun doesTextFitInBox(text: String, textSize: Float, boxWidth: Int, boxHeight: Int): Boolean {
        val testView = TextView(context).apply {
            this.text = text
            this.textSize = textSize
            setPadding(12, 8, 12, 8) // Padding giống với ImageTranslationOverlay
            setTypeface(android.graphics.Typeface.DEFAULT)
            includeFontPadding = false
            maxLines = 5 // Giới hạn tối đa 5 dòng
        }

        testView.measure(
            View.MeasureSpec.makeMeasureSpec(boxWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(boxHeight, View.MeasureSpec.EXACTLY)
        )

        // Kiểm tra xem text có bị cắt không
        testView.layout(0, 0, boxWidth, boxHeight)
        val layout = testView.layout

        if (layout == null) return false

        // Kiểm tra xem có bị ellipsize không
        val lastLine = layout.lineCount - 1
        val hasEllipsis = layout.getEllipsisCount(lastLine) > 0

        // Kiểm tra xem text có vượt quá chiều cao không
        val textHeight = layout.height
        val availableHeight = boxHeight - testView.paddingTop - testView.paddingBottom

        Log.d("TranslationView", "Text fit check: hasEllipsis=$hasEllipsis, textHeight=$textHeight, availableHeight=$availableHeight")

        return !hasEllipsis && textHeight <= availableHeight
    }

    private fun calculateOptimalSizeAndText(text: String, originalRect: Rect): Triple<Int, Int, Float> {
        // ✨ COPIED FROM ImageTranslationOverlay: Logic tính textSize giống hệt
        var textSize = when {
            originalRect.height() < 25 -> 9f
            originalRect.height() < 35 -> 11f
            originalRect.height() < 50 -> 13f
            originalRect.height() < 70 -> 15f
            else -> 17f
        }
        val minTextSize = 8f
        
        Log.d("TranslationView", "🎯 calculateOptimalSizeAndText: box=${originalRect.width()}×${originalRect.height()}, startTextSize=${textSize}f")
        
        // ✨ BƯỚC 1: Thử thu nhỏ text để vừa với box ban đầu (giống ImageTranslationOverlay)
        while (textSize >= minTextSize) {
            if (doesTextFitInBox(text, textSize, originalRect.width(), originalRect.height())) {
                Log.d("TranslationView", "✅ Text fits with size ${textSize}f in original box")
                return Triple(originalRect.width(), originalRect.height(), textSize)
            }
            textSize -= 0.5f
        }

        // ✨ BƯỚC 2: Nếu text quá dài, mở rộng box và dùng textSize tối thiểu
        Log.d("TranslationView", "📏 Text too long, expanding box with minTextSize=${minTextSize}f")
        val expandedRect = expandBoxToFitTextIntelligent(originalRect, text, minTextSize)
        return Triple(expandedRect.width(), expandedRect.height(), minTextSize)
    }

    // ✨ NEW: Intelligent expansion như ImageTranslationOverlay
    private fun expandBoxToFitTextIntelligent(originalRect: Rect, text: String, textSize: Float): Rect {
        val expandedRect = Rect(originalRect)
        
        // Bước 1: Thêm padding nhỏ ban đầu
        val padding = 6
        expandedRect.inset(-padding, -padding)
        
        // Bước 2: Tính toán kích thước text cần thiết
        val tempTextView = TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
            setPadding(8, 8, 8, 8)
            includeFontPadding = false
            maxLines = 5
            ellipsize = null
            setSingleLine(false)
            setLineSpacing(2f, 1.1f)
        }
        
        // Đo với width hợp lý để có multi-line
        val preferredWidth = minOf(expandedRect.width(), (screenBounds.width() * 0.6f).toInt())
        tempTextView.measure(
            View.MeasureSpec.makeMeasureSpec(preferredWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        
        val requiredWidth = tempTextView.measuredWidth + 16 // padding
        val requiredHeight = tempTextView.measuredHeight + 16 // padding
        
        // Bước 3: Expansion logic như ImageTranslationOverlay
        val widthNeeded = maxOf(0, requiredWidth - expandedRect.width())
        val heightNeeded = maxOf(0, requiredHeight - expandedRect.height())
        
        Log.d("TranslationView", "🔧 Intelligent expansion: text='${text.take(20)}...' widthNeeded=$widthNeeded heightNeeded=$heightNeeded")
        
        // Mở rộng width trước
        if (widthNeeded > 0) {
            val expandLeft = widthNeeded / 2
            val expandRight = widthNeeded - expandLeft
            
            val canExpandLeft = expandedRect.left - expandLeft >= screenBounds.left &&
                               !hasAdjacentBoxOnLeft(expandedRect, expandLeft)
            val canExpandRight = expandedRect.right + expandRight <= screenBounds.right &&
                                !hasAdjacentBoxOnRight(expandedRect, expandRight)
            
            when {
                canExpandLeft && canExpandRight -> {
                    expandedRect.left -= expandLeft
                    expandedRect.right += expandRight
                }
                canExpandLeft && !canExpandRight -> {
                    val maxLeftExpansion = minOf(widthNeeded, expandedRect.left - screenBounds.left)
                    expandedRect.left -= maxLeftExpansion
                }
                !canExpandLeft && canExpandRight -> {
                    val maxRightExpansion = minOf(widthNeeded, screenBounds.right - expandedRect.right)
                    expandedRect.right += maxRightExpansion
                }
            }
        }
        
        // Sau đó mở rộng height
        if (heightNeeded > 0) {
            val expandTop = heightNeeded / 2
            val expandBottom = heightNeeded - expandTop
            
            val canExpandTop = expandedRect.top - expandTop >= screenBounds.top &&
                              !hasAdjacentBoxOnTop(expandedRect, expandTop)
            val canExpandBottom = expandedRect.bottom + expandBottom <= screenBounds.bottom &&
                                 !hasAdjacentBoxOnBottom(expandedRect, expandBottom)
            
            when {
                canExpandTop && canExpandBottom -> {
                    expandedRect.top -= expandTop
                    expandedRect.bottom += expandBottom
                }
                canExpandTop && !canExpandBottom -> {
                    val maxTopExpansion = minOf(heightNeeded, expandedRect.top - screenBounds.top)
                    expandedRect.top -= maxTopExpansion
                }
                !canExpandTop && canExpandBottom -> {
                    val maxBottomExpansion = minOf(heightNeeded, screenBounds.bottom - expandedRect.bottom)
                    expandedRect.bottom += maxBottomExpansion
                }
            }
        }
        
        Log.d("TranslationView", "✅ Expansion result: ${originalRect.width()}×${originalRect.height()} → ${expandedRect.width()}×${expandedRect.height()}")
        return expandedRect
    }

    // NEW: Hàm kiểm tra va chạm với các box khác
    private fun isOverlappingWithOthers(rect: Rect): Boolean {
        val margin = 8 // 🔧 CẢI TIẾN: Tăng margin buffer để tránh đè lên nhau
        val checkRect = Rect(rect.left - margin, rect.top - margin, rect.right + margin, rect.bottom + margin)

        return usedRects.any { usedRect ->
            val result = Rect.intersects(usedRect, checkRect)
            if (result) {
                Log.d("TranslationView", "💥 OVERLAP DETECTED: New=$checkRect vs Used=$usedRect")
            }
            result
        }
    }

    // ✨ NEW: Direction-specific collision detection from ImageTranslationOverlay
    private fun hasAdjacentBoxOnLeft(rect: Rect, margin: Int): Boolean {
        val checkRect = Rect(rect.left - margin, rect.top, rect.left, rect.bottom)
        return usedRects.any { Rect.intersects(it, checkRect) }
    }

    private fun hasAdjacentBoxOnRight(rect: Rect, margin: Int): Boolean {
        val checkRect = Rect(rect.right, rect.top, rect.right + margin, rect.bottom)
        return usedRects.any { Rect.intersects(it, checkRect) }
    }

    private fun hasAdjacentBoxOnTop(rect: Rect, margin: Int): Boolean {
        val checkRect = Rect(rect.left, rect.top - margin, rect.right, rect.top)
        return usedRects.any { Rect.intersects(it, checkRect) }
    }

    private fun hasAdjacentBoxOnBottom(rect: Rect, margin: Int): Boolean {
        val checkRect = Rect(rect.left, rect.bottom, rect.right, rect.bottom + margin)
        return usedRects.any { Rect.intersects(it, checkRect) }
    }

    // HEAVILY MODIFIED: Logic mở rộng box thông minh và cân đối
    // ✨ ENHANCED: Direction-specific expansion như ImageTranslationOverlay
    private fun expandBoxToFitText(originalRect: Rect, text: String, textSize: Float): Rect {
        Log.d("TranslationView", "🚀 STARTING EXPANSION for text: ${text.take(30)}...")

        val requiredSize = calculateRequiredBoxSize(text, textSize)
        val extraPadding = 6

        val targetWidth = requiredSize.first + extraPadding
        val targetHeight = requiredSize.second + extraPadding

        val widthNeeded = maxOf(0, targetWidth - originalRect.width())
        val heightNeeded = maxOf(0, targetHeight - originalRect.height())

        var expandedRect = Rect(originalRect)

        Log.d("TranslationView", """
            🔧 EXPANSION DEBUG:
            📏 Required size: ${requiredSize.first}×${requiredSize.second}px
            🎯 Target size: ${targetWidth}×${targetHeight}px
            📦 Original rect: ${originalRect.width()}×${originalRect.height()}px
            📈 Need to expand: width=$widthNeeded, height=$heightNeeded
            🌍 Screen bounds: ${screenBounds}
        """.trimIndent())

        // ✨ ENHANCED: Direction-specific expansion như ImageTranslationOverlay
        if (widthNeeded > 0) {
            // Cần mở rộng chiều ngang
            val expandLeft = widthNeeded / 2
            val expandRight = widthNeeded - expandLeft

            // Kiểm tra có thể mở rộng trái và phải không
            val canExpandLeft = expandedRect.left - expandLeft >= screenBounds.left &&
                               !hasAdjacentBoxOnLeft(expandedRect, expandLeft)
            val canExpandRight = expandedRect.right + expandRight <= screenBounds.right &&
                                !hasAdjacentBoxOnRight(expandedRect, expandRight)

            if (canExpandLeft && canExpandRight) {
                // Mở rộng cả 2 bên cho cân đối
                expandedRect.left -= expandLeft
                expandedRect.right += expandRight
                Log.d("TranslationView", "✅ Expanded both sides by $expandLeft/$expandRight")
            } else if (canExpandLeft && !canExpandRight) {
                // Chỉ mở rộng trái, nhưng mở rộng toàn bộ width cần thiết
                val maxLeftExpansion = minOf(widthNeeded, expandedRect.left - screenBounds.left)
                expandedRect.left -= maxLeftExpansion
                Log.d("TranslationView", "⬅️ Expanded left only by $maxLeftExpansion")
            } else if (!canExpandLeft && canExpandRight) {
                // Chỉ mở rộng phải
                val maxRightExpansion = minOf(widthNeeded, screenBounds.right - expandedRect.right)
                expandedRect.right += maxRightExpansion
                Log.d("TranslationView", "➡️ Expanded right only by $maxRightExpansion")
            } else {
                // Không thể mở rộng ngang, thử mở rộng tối đa có thể
                val availableLeft = expandedRect.left - screenBounds.left
                val availableRight = screenBounds.right - expandedRect.right

                if (availableLeft > 0 && !hasAdjacentBoxOnLeft(expandedRect, availableLeft)) {
                    expandedRect.left -= availableLeft
                }
                if (availableRight > 0 && !hasAdjacentBoxOnRight(expandedRect, availableRight)) {
                    expandedRect.right += availableRight
                }
                Log.d("TranslationView", "🔄 Limited expansion: left=$availableLeft, right=$availableRight")
            }
        }

        if (heightNeeded > 0) {
            Log.d("TranslationView", "🔧 HEIGHT EXPANSION NEEDED: $heightNeeded px")
            // Cần mở rộng chiều dọc
            val expandTop = heightNeeded / 2
            val expandBottom = heightNeeded - expandTop

            val canExpandTop = expandedRect.top - expandTop >= screenBounds.top &&
                              !hasAdjacentBoxOnTop(expandedRect, expandTop)
            val canExpandBottom = expandedRect.bottom + expandBottom <= screenBounds.bottom &&
                                 !hasAdjacentBoxOnBottom(expandedRect, expandBottom)

            Log.d("TranslationView", """
                📏 Height expansion analysis:
                ⬆️ Can expand top: $canExpandTop (need $expandTop px)
                ⬇️ Can expand bottom: $canExpandBottom (need $expandBottom px)
                📍 Current rect: top=${expandedRect.top}, bottom=${expandedRect.bottom}
                📦 Screen bounds: top=${screenBounds.top}, bottom=${screenBounds.bottom}
            """.trimIndent())

            if (canExpandTop && canExpandBottom) {
                expandedRect.top -= expandTop
                expandedRect.bottom += expandBottom
                Log.d("TranslationView", "↕️ Expanded vertically both sides by $expandTop/$expandBottom")
            } else if (canExpandTop && !canExpandBottom) {
                val maxTopExpansion = minOf(heightNeeded, expandedRect.top - screenBounds.top)
                expandedRect.top -= maxTopExpansion
                Log.d("TranslationView", "⬆️ Expanded top only by $maxTopExpansion")
            } else if (!canExpandTop && canExpandBottom) {
                val maxBottomExpansion = minOf(heightNeeded, screenBounds.bottom - expandedRect.bottom)
                expandedRect.bottom += maxBottomExpansion
                Log.d("TranslationView", "⬇️ Expanded bottom only by $maxBottomExpansion")
            } else {
                // Mở rộng tối đa có thể
                val availableTop = expandedRect.top - screenBounds.top
                val availableBottom = screenBounds.bottom - expandedRect.bottom

                if (availableTop > 0 && !hasAdjacentBoxOnTop(expandedRect, availableTop)) {
                    expandedRect.top -= availableTop
                }
                if (availableBottom > 0 && !hasAdjacentBoxOnBottom(expandedRect, availableBottom)) {
                    expandedRect.bottom += availableBottom
                }
                Log.d("TranslationView", "🔄 Limited vertical expansion: top=$availableTop, bottom=$availableBottom")
            }
        }

        // 🔧 CẢI TIẾN 3: Đảm bảo box cuối cùng hợp lệ
        expandedRect.left = maxOf(screenBounds.left, expandedRect.left)
        expandedRect.top = maxOf(screenBounds.top, expandedRect.top)
        expandedRect.right = minOf(screenBounds.right, expandedRect.right)
        expandedRect.bottom = minOf(screenBounds.bottom, expandedRect.bottom)

        // Đảm bảo kích thước tối thiểu
        if (expandedRect.width() < 50) {
            val center = expandedRect.centerX()
            expandedRect.left = maxOf(screenBounds.left, center - 25)
            expandedRect.right = minOf(screenBounds.right, expandedRect.left + 50)
        }

        if (expandedRect.height() < 30) {
            val center = expandedRect.centerY()
            expandedRect.top = maxOf(screenBounds.top, center - 15)
            expandedRect.bottom = minOf(screenBounds.bottom, expandedRect.top + 30)
        }

        Log.d("TranslationView", """
            ✅ EXPANSION COMPLETE:
            📦 Original: ${originalRect.width()}×${originalRect.height()}px
            📈 Final: ${expandedRect.width()}×${expandedRect.height()}px
            📊 Change: ${expandedRect.width() - originalRect.width()}×${expandedRect.height() - originalRect.height()}px
        """.trimIndent())

        return expandedRect
    }

    // HEAVILY MODIFIED: Tính toán kích thước box với multi-line layout thông minh
    private fun calculateRequiredBoxSize(text: String, textSize: Float): Pair<Int, Int> {
        // ✨ ENHANCED: Thay vì dùng maxWidth như trước, hãy force multi-line bằng cách đo với width giới hạn hợp lý
        val preferredWidth = minOf(400, (screenBounds.width() * 0.6f).toInt()) // Giới hạn để force wrap

        val tempTextView = TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
            setPadding(8, 8, 8, 8) // ✨ SYNC: Khớp với TextView chính
            includeFontPadding = false

            // 🔧 CẢI TIẾN CHÍNH: Cho phép multi-line layout
            maxLines = 5 // Tối đa 5 dòng
            ellipsize = null // Không cắt bớt
            setSingleLine(false) // Quan trọng: KHÔNG single line
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP // ✨ SYNC

            // Cải thiện line spacing cho dễ đọc
            setLineSpacing(2f, 1.1f) // 2px extra + 1.1x multiplier
        }

        // ✨ ENHANCED: Đo với width EXACTLY để force wrap, height UNSPECIFIED để tự do mở rộng
        tempTextView.measure(
            View.MeasureSpec.makeMeasureSpec(preferredWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val measuredWidth = tempTextView.measuredWidth
        val measuredHeight = tempTextView.measuredHeight

        // ✨ LOG: Ghi lại quá trình đo
        Log.d("TranslationView", """
            📐 BOX SIZE CALCULATION:
            🔤 Text: ${text.take(30)}${if (text.length > 30) "..." else ""}
            📏 Text size: ${textSize}sp
            📊 Preferred width: ${preferredWidth}px (forced)
            📦 Measured: ${measuredWidth}×${measuredHeight}px
            📝 Line count: ${tempTextView.lineCount}
        """.trimIndent())

        return Pair(measuredWidth, measuredHeight)
    }
}