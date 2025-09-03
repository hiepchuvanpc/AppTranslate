package com.example.apptranslate.ui.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.apptranslate.R

class CopyTextOverlay(
    context: Context,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    companion object {
        private const val TAG = "CopyTextOverlay"
    }

    private val backgroundView: View
    private val containerView: FrameLayout
    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val usedRects = mutableListOf<Rect>() // Để tránh đè lên nhau

    init {
        // Background mờ để có thể nhấn ra ngoài để đóng
        backgroundView = View(context).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.overlay_background))
            setOnClickListener { onDismiss() }
        }
        addView(backgroundView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Container để chứa các text view kết quả
        containerView = FrameLayout(context)
        addView(containerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

    // Không còn instruction text
    }

    // Đã xóa instruction text

    fun addCopyTextResult(rect: Rect, text: String) {
        // Padding 3dp mỗi chiều
        val paddingDp = 3f
        val paddingPx = (paddingDp * resources.displayMetrics.density).toInt()
        val viewWidth = rect.width() + (paddingPx * 2)
        val viewHeight = rect.height() + (paddingPx * 2)

        // Tìm vị trí không bị đè lên nhau
        val finalRect = findNonOverlappingPosition(
            Rect(rect.left - paddingPx, rect.top - paddingPx,
                 rect.right + paddingPx, rect.bottom + paddingPx),
            viewWidth, viewHeight
        )

        // Thêm vào danh sách đã sử dụng
        usedRects.add(finalRect)

        // Chọn màu chữ tương phản với nền box
        val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val textColor = if (isNight) {
            ContextCompat.getColor(context, android.R.color.white)
        } else {
            ContextCompat.getColor(context, android.R.color.black)
        }

        val resultView = CopyableTextView(context, text) { copiedText ->
            copyToClipboard(copiedText)
        }
        resultView.setTextColor(textColor)
        resultView.setTextIsSelectable(true)
        resultView.setPadding(0, 0, 0, 0)
        val params = LayoutParams(
            viewWidth,
            viewHeight
        ).apply {
            leftMargin = finalRect.left
            topMargin = finalRect.top
        }
        containerView.addView(resultView, params)
    }

    private fun findNonOverlappingPosition(originalRect: Rect, width: Int, height: Int): Rect {
        var candidateRect = Rect(originalRect.left, originalRect.top,
                                 originalRect.left + width, originalRect.top + height)

        // Kiểm tra xem có đè lên nhau không
        var attempts = 0
        val maxAttempts = 20
        val offsetStep = 20 // pixel để dịch chuyển

        while (attempts < maxAttempts && isOverlapping(candidateRect)) {
            attempts++

            // Thử dịch chuyển theo các hướng khác nhau
            when (attempts % 4) {
                0 -> candidateRect.offset(0, offsetStep) // xuống dưới
                1 -> candidateRect.offset(offsetStep, 0) // sang phải
                2 -> candidateRect.offset(0, -offsetStep) // lên trên
                3 -> candidateRect.offset(-offsetStep, 0) // sang trái
            }

            // Đảm bảo không ra khỏi màn hình
            candidateRect = keepWithinBounds(candidateRect, width, height)
        }

        return candidateRect
    }

    private fun isOverlapping(rect: Rect): Boolean {
        return usedRects.any { usedRect ->
            Rect.intersects(rect, usedRect)
        }
    }

    private fun keepWithinBounds(rect: Rect, width: Int, height: Int): Rect {
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        var newLeft = rect.left
        var newTop = rect.top

        // Đảm bảo không ra khỏi bên phải
        if (newLeft + width > screenWidth) {
            newLeft = screenWidth - width
        }

        // Đảm bảo không ra khỏi bên dưới
        if (newTop + height > screenHeight) {
            newTop = screenHeight - height
        }

        // Đảm bảo không ra khỏi bên trái
        if (newLeft < 0) {
            newLeft = 0
        }

        // Đảm bảo không ra khỏi bên trên
        if (newTop < 0) {
            newTop = 0
        }

        return Rect(newLeft, newTop, newLeft + width, newTop + height)
    }

    private fun copyToClipboard(text: String) {
        try {
            val clip = ClipData.newPlainText("Copied Text", text)
            clipboardManager.setPrimaryClip(clip)
            Toast.makeText(context, "Đã sao chép: \"${text.take(50)}${if (text.length > 50) "..." else ""}\"", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Text copied to clipboard: $text")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy text to clipboard", e)
            Toast.makeText(context, "Lỗi khi sao chép văn bản", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Custom TextView có thể sao chép khi long press
 */
class CopyableTextView(
    context: Context,
    private val text: String,
    private val onCopy: (String) -> Unit
) : TextView(context) {

    init {
        setText(text)
        // Autosize text để luôn vừa với box
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            this, 8, 16, 1, android.util.TypedValue.COMPLEX_UNIT_SP
        )
        setTextColor(resolveAttrColor(context, android.R.attr.textColorPrimary))
        background = ContextCompat.getDrawable(context, R.drawable.translation_text_background)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true

        // Thêm long press listener
        setOnLongClickListener {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onCopy(text)
            animate()
                .scaleX(1.1f)
                .scaleY(1.1f)
                .setDuration(100)
                .withEndAction {
                    animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(100)
                        .start()
                }
                .start()
            true
        }

        // Thêm hover effect
        setOnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    animate().alpha(0.8f).setDuration(100).start()
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    animate().alpha(1.0f).setDuration(100).start()
                }
            }
            false // Để long press vẫn hoạt động
        }
    }

    private fun resolveAttrColor(context: Context, attr: Int): Int {
        val typedArray = context.theme.obtainStyledAttributes(intArrayOf(attr))
        val color = typedArray.getColor(0, ContextCompat.getColor(context, android.R.color.black))
        typedArray.recycle()
        return color
    }
}
