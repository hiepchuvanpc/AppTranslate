// File: app/src/main/java/com/example/apptranslate/ui/overlay/TranslationResultView.kt

package com.example.apptranslate.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatTextView
import com.example.apptranslate.R
import com.example.apptranslate.databinding.OverlayTranslationResultBinding

@SuppressLint("ViewConstructor")
class TranslationResultView(context: Context) : FrameLayout(context) {

    private val binding: OverlayTranslationResultBinding
    private val textView: AppCompatTextView

    init {
        val inflater = LayoutInflater.from(context)
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