package com.example.apptranslate.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.example.apptranslate.R
import com.example.apptranslate.databinding.OverlayGlobalContainerBinding
import com.example.apptranslate.ocr.OcrResult

@SuppressLint("ViewConstructor")
class GlobalTranslationOverlay(
    context: Context,
    private val windowManager: WindowManager
) : FrameLayout(context) {

    private val binding: OverlayGlobalContainerBinding
    var onDismiss: (() -> Unit)? = null
    private val usedRects = mutableListOf<Rect>() // Để tránh đè lên nhau

    init {
        isFocusableInTouchMode = true
        val themedContext = ContextThemeWrapper(context, R.style.Theme_AppTranslate_NoActionBar)
        val inflater = LayoutInflater.from(themedContext)
        binding = OverlayGlobalContainerBinding.inflate(inflater, this, true)
        binding.buttonClose.setOnClickListener {
            dismiss()
        }
    }

    fun addResultView(view: View, params: LayoutParams) {
        // Cải thiện vị trí để tránh đè lên nhau
        val adjustedParams = adjustPositionToAvoidOverlap(params)
        binding.container.addView(view, adjustedParams)

        // Thêm rect vào danh sách đã sử dụng
        val rect = Rect(adjustedParams.leftMargin, adjustedParams.topMargin,
                       adjustedParams.leftMargin + adjustedParams.width,
                       adjustedParams.topMargin + adjustedParams.height)
        usedRects.add(rect)
    }

    private fun adjustPositionToAvoidOverlap(originalParams: LayoutParams): LayoutParams {
        val newParams = LayoutParams(originalParams)
        val originalRect = Rect(originalParams.leftMargin, originalParams.topMargin,
                               originalParams.leftMargin + originalParams.width,
                               originalParams.topMargin + originalParams.height)

        val adjustedRect = findNonOverlappingPosition(originalRect, originalParams.width, originalParams.height)

        newParams.leftMargin = adjustedRect.left
        newParams.topMargin = adjustedRect.top

        return newParams
    }

    private fun findNonOverlappingPosition(originalRect: Rect, width: Int, height: Int): Rect {
        var candidateRect = Rect(originalRect)

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

    fun showLoading() {
        binding.loadingIndicator.isVisible = true
    }

    fun hideLoading() {
        binding.loadingIndicator.isVisible = false
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event?.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                dismiss()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun dismiss() {
        try {
            if (isAttachedToWindow) {
                windowManager.removeView(this)
                onDismiss?.invoke()
            }
        } catch (e: Exception) {
            // Bỏ qua
        }
    }
}