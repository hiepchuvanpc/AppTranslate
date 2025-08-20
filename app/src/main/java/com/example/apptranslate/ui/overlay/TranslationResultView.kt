package com.example.apptranslate.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView
import com.example.apptranslate.R
import com.example.apptranslate.databinding.OverlayTranslationResultBinding

@SuppressLint("ViewConstructor")
class TranslationResultView(context: Context) : FrameLayout(context) {

    private val binding: OverlayTranslationResultBinding
    private val textView: TextView

    init {
        // Sử dụng themed context để tránh lỗi theme
        val themedContext = ContextThemeWrapper(context, R.style.Theme_AppTranslate_NoActionBar)
        val inflater = LayoutInflater.from(themedContext)
        binding = OverlayTranslationResultBinding.inflate(inflater, this, true)
        textView = binding.tvTranslatedText
    }

    fun showLoading() {
        textView.text = "..."
    }

    fun updateText(text: String) {
        textView.text = text
    }
}