package com.example.apptranslate.ui.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.apptranslate.R
import com.example.apptranslate.ui.ImageTranslationResult
import com.google.android.material.floatingactionbutton.FloatingActionButton

class ImageTranslationOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "ImageTranslationOverlay"
    }

    private val backgroundImageView: ImageView
    private val loadingProgressBar: ProgressBar
    private val closeButton: FloatingActionButton
    private val overlayContainer: FrameLayout
    private val usedRects = mutableListOf<Rect>()

    var onCloseListener: (() -> Unit)? = null

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        isFocusable = true

        // Tạo ImageView để hiển thị ảnh nền
        backgroundImageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        addView(backgroundImageView)

        // Container cho các kết quả dịch
        overlayContainer = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        addView(overlayContainer)

        // Loading indicator
        loadingProgressBar = ProgressBar(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            visibility = View.GONE
        }
        addView(loadingProgressBar)

        // Nút đóng giống với chế độ dịch toàn cầu
        closeButton = FloatingActionButton(context).apply {
            setImageResource(R.drawable.ic_close)
            size = FloatingActionButton.SIZE_MINI
            backgroundTintList = ContextCompat.getColorStateList(context, android.R.color.white)
            imageTintList = ContextCompat.getColorStateList(context, R.color.primary_text)
            alpha = 0.8f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                setMargins(16, 16, 16, 16)
            }
            
            setOnClickListener {
                onCloseListener?.invoke()
            }
        }
        addView(closeButton)

        // Instruction text
        val instructionText = TextView(context).apply {
            text = "Nhấn × để đóng"
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(16, 8, 16, 8)
            setBackgroundColor(Color.parseColor("#80000000"))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = 16
            }
            
            postDelayed({
                animate().alpha(0f).setDuration(500).start()
            }, 3000)
        }
        addView(instructionText)
    }

    fun setBackgroundImage(bitmap: Bitmap) {
        Log.d(TAG, "Setting background image: ${bitmap.width}x${bitmap.height}")
        backgroundImageView.setImageBitmap(bitmap)
    }

    fun showLoading() {
        loadingProgressBar.visibility = View.VISIBLE
        overlayContainer.removeAllViews()
    }

    fun hideLoading() {
        loadingProgressBar.visibility = View.GONE
    }

    fun addTranslationResults(results: List<ImageTranslationResult>, originalBitmap: Bitmap) {
        Log.d(TAG, "Adding ${results.size} translation results")
        overlayContainer.removeAllViews()
        usedRects.clear()

        post {
            val imageViewBounds = getImageViewActualBounds()
            val scaleX = imageViewBounds.width().toFloat() / originalBitmap.width
            val scaleY = imageViewBounds.height().toFloat() / originalBitmap.height

            Log.d(TAG, "Scale factors - X: $scaleX, Y: $scaleY")

            val sortedResults = results.sortedWith(compareBy<ImageTranslationResult> { it.boundingBox.top }
                                                      .thenBy { it.boundingBox.left })
            
            sortedResults.forEach { result ->
                addSingleTranslationResult(result, scaleX, scaleY, imageViewBounds)
            }
        }
    }

    private fun getImageViewActualBounds(): Rect {
        val drawable = backgroundImageView.drawable ?: return Rect()
        
        val imageWidth = drawable.intrinsicWidth
        val imageHeight = drawable.intrinsicHeight
        val viewWidth = backgroundImageView.width
        val viewHeight = backgroundImageView.height

        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return Rect()
        }

        val imageRatio = imageWidth.toFloat() / imageHeight
        val viewRatio = viewWidth.toFloat() / viewHeight

        val bounds = if (imageRatio > viewRatio) {
            val scaledHeight = (viewWidth / imageRatio).toInt()
            val top = (viewHeight - scaledHeight) / 2
            Rect(0, top, viewWidth, top + scaledHeight)
        } else {
            val scaledWidth = (viewHeight * imageRatio).toInt()
            val left = (viewWidth - scaledWidth) / 2
            Rect(left, 0, left + scaledWidth, viewHeight)
        }

        val location = IntArray(2)
        backgroundImageView.getLocationInWindow(location)
        val overlayLocation = IntArray(2)
        getLocationInWindow(overlayLocation)
        
        bounds.offset(location[0] - overlayLocation[0], location[1] - overlayLocation[1])
        
        return bounds
    }

    private fun addSingleTranslationResult(
        result: ImageTranslationResult,
        scaleX: Float,
        scaleY: Float,
        imageViewBounds: Rect
    ) {
        // Bước 1: Tính toán bounding box chính xác với text gốc (đè chính xác lên text gốc)
        val exactTextRect = Rect(
            (result.boundingBox.left * scaleX).toInt() + imageViewBounds.left,
            (result.boundingBox.top * scaleY).toInt() + imageViewBounds.top,
            (result.boundingBox.right * scaleX).toInt() + imageViewBounds.left,
            (result.boundingBox.bottom * scaleY).toInt() + imageViewBounds.top
        )

        // Bước 2: Mở rộng ra 4 phía một khoảng nhỏ để box thoáng
        val padding = 6
        val initialBox = Rect(
            exactTextRect.left - padding,
            exactTextRect.top - padding,
            exactTextRect.right + padding,
            exactTextRect.bottom + padding
        )

        Log.d(TAG, "Exact text rect: $exactTextRect, Initial box with padding: $initialBox")

        // Bước 3 & 4: Tạo adaptive text box
        val (textView, finalRect) = createAdaptiveTextBox(result.translatedText, initialBox, imageViewBounds)
        
        // Thêm vào danh sách vùng đã sử dụng
        usedRects.add(finalRect)
        
        val layoutParams = FrameLayout.LayoutParams(
            finalRect.width(),
            finalRect.height()
        ).apply {
            leftMargin = finalRect.left
            topMargin = finalRect.top
        }

        overlayContainer.addView(textView, layoutParams)
    }

    private fun createTranslationTextView(text: String, width: Int, height: Int): View {
        // Tạo FrameLayout giống với overlay_translation_result.xml
        val frameLayout = FrameLayout(context).apply {
            setBackgroundResource(R.drawable.translation_text_background)
        }

        // TextView chính để hiển thị text dịch
        val textView = TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.primary_text))
            setPadding(3, 3, 3, 3)
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            
            // Điều chỉnh text size dựa vào kích thước bounding box
            textSize = when {
                height < 30 -> 10f
                height < 50 -> 12f
                height < 80 -> 14f
                else -> 16f
            }
        }

        frameLayout.addView(textView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        return frameLayout
    }

    private fun createAdaptiveTextBox(text: String, originalRect: Rect, imageBounds: Rect): Pair<View, Rect> {
        var currentRect = Rect(originalRect)
        
        // Bước 3: Thử với cỡ chữ gốc trước (dựa vào chiều cao của bounding box)
        var textSize = when {
            originalRect.height() < 25 -> 9f
            originalRect.height() < 35 -> 11f
            originalRect.height() < 50 -> 13f
            originalRect.height() < 70 -> 15f
            else -> 17f
        }
        
        val minTextSize = 8f
        val originalTextSize = textSize
        
        Log.d(TAG, "Starting with text size: $textSize for box height: ${originalRect.height()}")
        
        // Thử thu nhỏ cỡ chữ trước khi mở rộng box
        while (textSize >= minTextSize) {
            // Kiểm tra text có vừa với kích thước hiện tại không
            if (doesTextFitInBox(text, textSize, currentRect.width(), currentRect.height())) {
                Log.d(TAG, "Text fits with size: $textSize")
                val finalView = createTranslationTextView(text, currentRect.width(), currentRect.height())
                setTextViewSize(finalView, textSize)
                return Pair(finalView, currentRect)
            }
            
            textSize -= 0.5f
        }
        
        // Bước 4: Nếu cỡ chữ đã nhỏ nhất mà vẫn không vừa, mở rộng box theo hướng có chỗ trống
        Log.d(TAG, "Text doesn't fit even with min size, expanding box...")
        currentRect = expandBoxInAvailableDirections(originalRect, imageBounds, text, minTextSize)
        
        val finalView = createTranslationTextView(text, currentRect.width(), currentRect.height())
        setTextViewSize(finalView, minTextSize)
        
        return Pair(finalView, currentRect)
    }
    
    private fun setTextViewSize(frameLayout: View, textSize: Float) {
        (frameLayout as FrameLayout).getChildAt(0).let { textView ->
            (textView as TextView).textSize = textSize
        }
    }
    
    private fun createTestTextView(text: String, textSize: Float, width: Int, height: Int): TextView {
        return TextView(context).apply {
            this.text = text
            this.textSize = textSize
            gravity = Gravity.CENTER
            setPadding(3, 3, 3, 3)
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            maxLines = getMaxLinesForHeight(height)
            
            measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, width, height)
        }
    }
    
    private fun getMaxLinesForHeight(height: Int): Int {
        return when {
            height < 30 -> 1
            height < 60 -> 2
            height < 90 -> 3
            else -> 4
        }
    }
    
    private fun isTextTruncated(textView: TextView): Boolean {
        val layout = textView.layout ?: return false
        val lines = layout.lineCount
        return lines > 0 && layout.getEllipsisCount(lines - 1) > 0
    }
    
    private fun doesTextFitInBox(text: String, textSize: Float, boxWidth: Int, boxHeight: Int): Boolean {
        val testView = TextView(context).apply {
            this.text = text
            this.textSize = textSize
            setPadding(12, 8, 12, 8) // Padding giống với text view thực tế
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
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
        
        Log.d(TAG, "Text fit check: hasEllipsis=$hasEllipsis, textHeight=$textHeight, availableHeight=$availableHeight")
        
        return !hasEllipsis && textHeight <= availableHeight
    }
    
    private fun expandBoxInAvailableDirections(originalRect: Rect, imageBounds: Rect, text: String, textSize: Float): Rect {
        val expandedRect = Rect(originalRect)
        
        // Tính toán kích thước text cần thiết bằng cách tạo TextView không giới hạn
        val tempTextView = TextView(context).apply {
            this.text = text
            this.textSize = textSize
            setPadding(12, 8, 12, 8) // Padding giống với text view thực tế
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            includeFontPadding = false
        }
        
        // Measure với width không giới hạn để biết text cần bao nhiều không gian
        tempTextView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        
        val requiredWidth = tempTextView.measuredWidth + 8 // Thêm margin
        val requiredHeight = tempTextView.measuredHeight + 8 // Thêm margin
        
        Log.d(TAG, "Text '$text' needs width: $requiredWidth, height: $requiredHeight")
        Log.d(TAG, "Current box: ${expandedRect.width()} x ${expandedRect.height()}")
        
        // Tính toán cần mở rộng bao nhiều
        val widthNeeded = maxOf(0, requiredWidth - expandedRect.width())
        val heightNeeded = maxOf(0, requiredHeight - expandedRect.height())
        
        Log.d(TAG, "Need to expand: width=$widthNeeded, height=$heightNeeded")
        
        if (widthNeeded > 0) {
            // Cần mở rộng chiều ngang
            val expandLeft = widthNeeded / 2
            val expandRight = widthNeeded - expandLeft
            
            // Kiểm tra có thể mở rộng trái và phải không
            val canExpandLeft = expandedRect.left - expandLeft >= imageBounds.left && 
                               !hasAdjacentBoxOnLeft(expandedRect, expandLeft)
            val canExpandRight = expandedRect.right + expandRight <= imageBounds.right && 
                                !hasAdjacentBoxOnRight(expandedRect, expandRight)
            
            if (canExpandLeft && canExpandRight) {
                // Mở rộng cả 2 bên cho cân đối
                expandedRect.left -= expandLeft
                expandedRect.right += expandRight
                Log.d(TAG, "Expanded both sides by $expandLeft/$expandRight")
            } else if (canExpandLeft && !canExpandRight) {
                // Chỉ mở rộng trái, nhưng mở rộng toàn bộ width cần thiết
                val maxLeftExpansion = minOf(widthNeeded, expandedRect.left - imageBounds.left)
                expandedRect.left -= maxLeftExpansion
                Log.d(TAG, "Expanded left only by $maxLeftExpansion")
            } else if (!canExpandLeft && canExpandRight) {
                // Chỉ mở rộng phải
                val maxRightExpansion = minOf(widthNeeded, imageBounds.right - expandedRect.right)
                expandedRect.right += maxRightExpansion
                Log.d(TAG, "Expanded right only by $maxRightExpansion")
            } else {
                // Không thể mở rộng ngang, thử mở rộng tối đa có thể
                val availableLeft = expandedRect.left - imageBounds.left
                val availableRight = imageBounds.right - expandedRect.right
                
                if (availableLeft > 0 && !hasAdjacentBoxOnLeft(expandedRect, availableLeft)) {
                    expandedRect.left -= availableLeft
                }
                if (availableRight > 0 && !hasAdjacentBoxOnRight(expandedRect, availableRight)) {
                    expandedRect.right += availableRight
                }
                Log.d(TAG, "Limited expansion: left=$availableLeft, right=$availableRight")
            }
        }
        
        if (heightNeeded > 0) {
            // Cần mở rộng chiều dọc
            val expandTop = heightNeeded / 2
            val expandBottom = heightNeeded - expandTop
            
            val canExpandTop = expandedRect.top - expandTop >= imageBounds.top && 
                              !hasAdjacentBoxOnTop(expandedRect, expandTop)
            val canExpandBottom = expandedRect.bottom + expandBottom <= imageBounds.bottom && 
                                 !hasAdjacentBoxOnBottom(expandedRect, expandBottom)
            
            if (canExpandTop && canExpandBottom) {
                expandedRect.top -= expandTop
                expandedRect.bottom += expandBottom
                Log.d(TAG, "Expanded vertically both sides by $expandTop/$expandBottom")
            } else if (canExpandTop && !canExpandBottom) {
                val maxTopExpansion = minOf(heightNeeded, expandedRect.top - imageBounds.top)
                expandedRect.top -= maxTopExpansion
                Log.d(TAG, "Expanded top only by $maxTopExpansion")
            } else if (!canExpandTop && canExpandBottom) {
                val maxBottomExpansion = minOf(heightNeeded, imageBounds.bottom - expandedRect.bottom)
                expandedRect.bottom += maxBottomExpansion
                Log.d(TAG, "Expanded bottom only by $maxBottomExpansion")
            } else {
                // Mở rộng tối đa có thể
                val availableTop = expandedRect.top - imageBounds.top
                val availableBottom = imageBounds.bottom - expandedRect.bottom
                
                if (availableTop > 0 && !hasAdjacentBoxOnTop(expandedRect, availableTop)) {
                    expandedRect.top -= availableTop
                }
                if (availableBottom > 0 && !hasAdjacentBoxOnBottom(expandedRect, availableBottom)) {
                    expandedRect.bottom += availableBottom
                }
                Log.d(TAG, "Limited vertical expansion: top=$availableTop, bottom=$availableBottom")
            }
        }
        
        Log.d(TAG, "Final expanded rect: $expandedRect (${expandedRect.width()} x ${expandedRect.height()})")
        return expandedRect
    }
    
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

    private fun findNonOverlappingPosition(originalRect: Rect, width: Int, height: Int, imageBounds: Rect): Rect {
        var candidateRect = Rect(originalRect.left, originalRect.top, 
                                 originalRect.left + width, originalRect.top + height)
        
        var attempts = 0
        val maxAttempts = 30
        val offsetStep = 20
        val margin = 8
        
        while (attempts < maxAttempts && isOverlapping(candidateRect, margin)) {
            attempts++
            
            val direction = attempts % 8
            val distance = (attempts / 8 + 1) * offsetStep
            
            when (direction) {
                0 -> candidateRect.offset(0, distance)
                1 -> candidateRect.offset(distance, 0)
                2 -> candidateRect.offset(0, -distance)
                3 -> candidateRect.offset(-distance, 0)
                4 -> candidateRect.offset(distance, distance)
                5 -> candidateRect.offset(-distance, distance)
                6 -> candidateRect.offset(distance, -distance)
                7 -> candidateRect.offset(-distance, -distance)
            }
            
            candidateRect = keepWithinImageBounds(candidateRect, width, height, imageBounds)
        }
        
        return candidateRect
    }
    
    private fun isOverlapping(rect: Rect, margin: Int = 0): Boolean {
        val expandedRect = Rect(rect.left - margin, rect.top - margin, 
                                rect.right + margin, rect.bottom + margin)
        return usedRects.any { usedRect ->
            val expandedUsedRect = Rect(usedRect.left - margin, usedRect.top - margin,
                                        usedRect.right + margin, usedRect.bottom + margin)
            Rect.intersects(expandedRect, expandedUsedRect)
        }
    }

    private fun keepWithinImageBounds(rect: Rect, width: Int, height: Int, imageBounds: Rect): Rect {
        var newLeft = rect.left
        var newTop = rect.top
        
        if (newLeft + width > imageBounds.right) {
            newLeft = imageBounds.right - width
        }
        
        if (newTop + height > imageBounds.bottom) {
            newTop = imageBounds.bottom - height
        }
        
        if (newLeft < imageBounds.left) {
            newLeft = imageBounds.left
        }
        
        if (newTop < imageBounds.top) {
            newTop = imageBounds.top
        }
        
        return Rect(newLeft, newTop, newLeft + width, newTop + height)
    }

    fun clearResults() {
        overlayContainer.removeAllViews()
        usedRects.clear()
    }
}