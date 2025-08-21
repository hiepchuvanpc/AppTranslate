package com.example.apptranslate.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import com.example.apptranslate.databinding.OverlayTranslationResultBinding

@SuppressLint("ViewConstructor")
class TranslationResultView(context: Context) : FrameLayout(context) {

    private val binding: OverlayTranslationResultBinding

    init {
        val inflater = LayoutInflater.from(context)
        binding = OverlayTranslationResultBinding.inflate(inflater, this, true)

        setupAutoResizeText()
    }

    private fun setupAutoResizeText() {
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            binding.tvTranslatedText,
            8, // minTextSize (sp)
            16, // maxTextSize (sp)
            1, // granularity (sp)
            TypedValue.COMPLEX_UNIT_SP
        )
    }

    fun showLoading() {
        binding.tvTranslatedText.isVisible = false
        binding.loadingIndicator.isVisible = true
    }

    fun updateText(text: String) {
        binding.loadingIndicator.isVisible = false
        binding.tvTranslatedText.isVisible = true
        binding.tvTranslatedText.text = text
    }
}