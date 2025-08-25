package com.example.apptranslate.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.example.apptranslate.R
import com.example.apptranslate.databinding.OverlayRegionResultBinding

@SuppressLint("ViewConstructor")
class RegionResultOverlay(
    context: Context,
    private val selectedRegion: Rect,
    val onDismiss: () -> Unit
) : FrameLayout(context) {

    private val binding: OverlayRegionResultBinding
    private val backgroundPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.black)
        alpha = 128 // Nền mờ chỉ hiển thị sau khi OCR
    }

    private val resultView: TranslationResultView

    init {
        binding = OverlayRegionResultBinding.inflate(LayoutInflater.from(context), this)
        setWillNotDraw(false)
        isFocusableInTouchMode = true
        requestFocus()

        // Tạo TranslationResultView để hiển thị kết quả
        resultView = TranslationResultView(context)
        
        // Thêm resultView vào vị trí được chọn
        val params = LayoutParams(
            selectedRegion.width(),
            selectedRegion.height()
        ).apply {
            leftMargin = selectedRegion.left
            topMargin = selectedRegion.top
        }
        addView(resultView, params)

        binding.buttonClose.setOnClickListener { onDismiss() }
    }

    fun updateResult(translatedText: String) {
        resultView.updateText(translatedText)
    }

    fun showLoading() {
        resultView.showLoading()
    }

    fun hideLoading() {
        resultView.hideLoading()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Kiểm tra xem có click vào vùng kết quả không
                if (selectedRegion.contains(event.x.toInt(), event.y.toInt())) {
                    return false // Cho phép tương tác với vùng kết quả
                } else {
                    // Click ra ngoài -> đóng overlay
                    onDismiss()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Vẽ nền mờ toàn màn hình
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        
        // Tạo một vùng trong suốt tại vị trí kết quả
        canvas.save()
        canvas.clipRect(selectedRegion, android.graphics.Region.Op.DIFFERENCE)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        canvas.restore()
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event?.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            onDismiss()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
