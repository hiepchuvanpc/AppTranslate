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
        val resultView = CopyableTextView(context, text) { copiedText ->
            copyToClipboard(copiedText)
        }
        // Đặt minWidth, maxWidth, wrap_content để vừa nội dung
        resultView.minWidth = (60 * resources.displayMetrics.density).toInt()
        resultView.maxWidth = (180 * resources.displayMetrics.density).toInt()
        resultView.setPadding(16, 8, 16, 8)
        resultView.setTextIsSelectable(true)
        val params = LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = rect.left
            topMargin = rect.top
        }
        containerView.addView(resultView, params)
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
        textSize = 15f
        // Sử dụng màu theo theme hệ thống
        setTextColor(resolveAttrColor(context, android.R.attr.textColorPrimary))
        background = ContextCompat.getDrawable(context, R.drawable.copyable_text_background)
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
