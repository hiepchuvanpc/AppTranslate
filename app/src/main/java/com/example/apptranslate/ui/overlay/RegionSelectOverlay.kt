// File: app/src/main/java/com/example/apptranslate/ui/overlay/RegionSelectOverlay.kt

package com.example.apptranslate.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.example.apptranslate.R
import com.example.apptranslate.databinding.OverlayRegionSelectionBinding

@SuppressLint("ViewConstructor")
class RegionSelectionOverlay(
    context: Context,
    val onRegionSelected: (Rect) -> Unit,
    val onDismiss: () -> Unit
) : FrameLayout(context) {

    private val binding: OverlayRegionSelectionBinding
    private val selectionPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.primary)
        style = Paint.Style.STROKE
        strokeWidth = 6f
        pathEffect = DashPathEffect(floatArrayOf(20f, 20f), 0f)
    }
    private val backgroundPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.black)
        alpha = 20 // Giảm độ mờ để không ảnh hưởng OCR
    }

    private var startPoint: PointF? = null
    private val selectionRect = Rect()

    init {
        binding = OverlayRegionSelectionBinding.inflate(LayoutInflater.from(context), this)
        setWillNotDraw(false) // Cần thiết để onDraw được gọi
        isFocusableInTouchMode = true
        requestFocus()

        binding.buttonTranslate.setOnClickListener {
            if (!selectionRect.isEmpty) {
                onRegionSelected(selectionRect)
            }
        }
        binding.buttonClose.setOnClickListener { onDismiss() }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startPoint = PointF(event.x, event.y)
                // Reset a rect
                selectionRect.set(0, 0, 0, 0)
                binding.buttonContainer.visibility = View.GONE
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                startPoint?.let { sp ->
                    selectionRect.set(
                        sp.x.coerceAtMost(event.x).toInt(),
                        sp.y.coerceAtMost(event.y).toInt(),
                        sp.x.coerceAtLeast(event.x).toInt(),
                        sp.y.coerceAtLeast(event.y).toInt()
                    )
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (selectionRect.width() > 20 && selectionRect.height() > 20) { // Ngưỡng chọn tối thiểu
                    positionButtonContainer()
                    binding.buttonContainer.visibility = View.VISIBLE
                }
                startPoint = null
                return true
            }
        }
        return false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!selectionRect.isEmpty) {
            // Chỉ vẽ đường viền, không vẽ nền mờ khi đang chọn
            canvas.drawRect(selectionRect, selectionPaint)
        }
    }

    private fun positionButtonContainer() {
        val params = binding.buttonContainer.layoutParams as LayoutParams
        params.gravity = Gravity.NO_GRAVITY
        params.leftMargin = selectionRect.right - binding.buttonContainer.width
        params.topMargin = selectionRect.bottom + 20 // Thêm khoảng cách
        // Giới hạn vị trí để không bị tràn màn hình
        params.leftMargin = params.leftMargin.coerceIn(0, width - binding.buttonContainer.width)
        params.topMargin = params.topMargin.coerceIn(0, height - binding.buttonContainer.height)
        binding.buttonContainer.layoutParams = params
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (binding.buttonContainer.visibility == View.VISIBLE) {
            positionButtonContainer()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event?.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            onDismiss()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}