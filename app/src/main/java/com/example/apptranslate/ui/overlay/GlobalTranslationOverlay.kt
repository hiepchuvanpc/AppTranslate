package com.example.apptranslate.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
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
        binding.container.addView(view, params)
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