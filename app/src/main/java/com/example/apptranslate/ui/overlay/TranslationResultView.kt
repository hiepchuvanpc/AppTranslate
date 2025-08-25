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
        // Đặt màu chữ tương phản với nền box
        val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val textColor = if (isNight) {
            context.getColor(android.R.color.white)
        } else {
            context.getColor(android.R.color.black)
        }
        binding.tvTranslatedText.setTextColor(textColor)
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

    fun hideLoading() {
        binding.loadingIndicator.isVisible = false
        binding.tvTranslatedText.isVisible = true
    }

    fun updateText(text: String) {
        binding.loadingIndicator.isVisible = false
        binding.tvTranslatedText.isVisible = true
        binding.tvTranslatedText.text = text
    }
}