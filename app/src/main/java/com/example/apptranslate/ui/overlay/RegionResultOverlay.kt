package com.example.apptranslate.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.example.apptranslate.R

@SuppressLint("ViewConstructor")
class RegionResultOverlay(
    context: Context,
    private val selectedRegion: Rect,
    val onDismiss: () -> Unit
) : FrameLayout(context) {

    private val backgroundPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.black)
        alpha = 128 // Nền mờ chỉ hiển thị trong vùng chọn
    }

    private val resultViews = mutableListOf<TranslationResultView>()
    private var loadingView: TranslationResultView? = null

    init {
        setWillNotDraw(false)
        isFocusableInTouchMode = true
        requestFocus()

        // Tạo view loading ban đầu
        loadingView = TranslationResultView(context)
        val loadingParams = LayoutParams(
            selectedRegion.width(),
            selectedRegion.height()
        ).apply {
            leftMargin = selectedRegion.left
            topMargin = selectedRegion.top
        }
        addView(loadingView, loadingParams)
    }

    fun addTranslationResult(position: Rect, translatedText: String) {
        val resultView = TranslationResultView(context).apply {
            updateText(translatedText)
        }
        
        val paddingPx = (2f * context.resources.displayMetrics.density).toInt()
        val params = LayoutParams(
            position.width() + (paddingPx * 2),
            position.height() + (paddingPx * 2)
        ).apply {
            leftMargin = position.left - paddingPx
            topMargin = position.top - paddingPx
        }
        
        addView(resultView, params)
        resultViews.add(resultView)
    }

    fun showLoading() {
        loadingView?.showLoading()
    }

    fun hideLoading() {
        loadingView?.let {
            removeView(it)
            loadingView = null
        }
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
        
        // Chỉ vẽ nền mờ trong vùng được chọn
        canvas.save()
        canvas.clipRect(selectedRegion)
        canvas.drawRect(selectedRegion, backgroundPaint)
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
