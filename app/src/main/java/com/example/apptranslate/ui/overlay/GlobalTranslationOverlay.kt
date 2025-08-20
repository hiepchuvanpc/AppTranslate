package com.example.apptranslate.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.isVisible
import com.example.apptranslate.databinding.OverlayGlobalContainerBinding

@SuppressLint("ViewConstructor")
class GlobalTranslationOverlay(
    context: Context,
    private val windowManager: WindowManager
) : FrameLayout(context) {

    private val binding: OverlayGlobalContainerBinding

    // Callback để thông báo cho Service rằng nó đã bị đóng
    var onDismiss: (() -> Unit)? = null

    init {
        // Lắng nghe sự kiện nút Back
        isFocusableInTouchMode = true

        val inflater = LayoutInflater.from(context)
        binding = OverlayGlobalContainerBinding.inflate(inflater, this, true)
        binding.buttonClose.setOnClickListener {
            dismiss()
        }
    }

    fun addResultView(view: View) {
        binding.container.addView(view)
    }

    fun showLoading() {
        binding.loadingIndicator.isVisible = true
    }

    fun hideLoading() {
        binding.loadingIndicator.isVisible = false
    }

    // Xử lý nút Back của hệ thống
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
            // Ignore
        }
    }
}