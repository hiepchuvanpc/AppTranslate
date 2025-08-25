// File: app/src/main/java/com/example/apptranslate/ui/overlay/LanguageSheetView.kt
package com.example.apptranslate.ui.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.apptranslate.R
import com.example.apptranslate.adapter.LanguageAdapter
import com.example.apptranslate.adapter.LanguageListItem
import com.example.apptranslate.databinding.ViewLanguageBottomSheetBinding
import com.example.apptranslate.viewmodel.LanguageViewModel
import com.google.android.material.button.MaterialButton

@SuppressLint("ViewConstructor")
class LanguageSheetView(
    context: Context,
    private val viewModel: LanguageViewModel,
    private val onDismiss: (languageChanged: Boolean) -> Unit
) : FrameLayout(context) {

    private val binding: ViewLanguageBottomSheetBinding
    private var languageWasSelected = false
    private lateinit var adapter: LanguageAdapter
    private var isSelectingSource = true

    init {
        val inflater = LayoutInflater.from(context)
        binding = ViewLanguageBottomSheetBinding.inflate(inflater, this, true)

        isFocusableInTouchMode = true
        requestFocus()

        setupUI()
        animateIn()
    }

    private fun setupUI() {
        adapter = LanguageAdapter { language ->
            languageWasSelected = true
            if (isSelectingSource) {
                viewModel.setSourceLanguage(language)
            } else {
                viewModel.setTargetLanguage(language)
            }
            dismiss()
        }
        binding.recyclerViewLanguages.layoutManager = LinearLayoutManager(context)
        binding.recyclerViewLanguages.adapter = adapter

        binding.buttonSelectSource.setOnClickListener {
            if (!isSelectingSource) {
                isSelectingSource = true
                updateButtonStyles()
                binding.editTextSearch.text?.clear()
                loadLanguages()
            }
        }

        binding.buttonSelectTarget.setOnClickListener {
            if (isSelectingSource) {
                isSelectingSource = false
                updateButtonStyles()
                binding.editTextSearch.text?.clear()
                loadLanguages()
            }
        }

        binding.editTextSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchLanguages(s.toString().trim())
            }
        })

        binding.backgroundOverlay.setOnClickListener { dismiss() }

        observeViewModel()
        updateButtonStyles()
        loadLanguages()
    }

    private fun observeViewModel() {
        // ViewModel được truyền từ Service nên không cần observe
        // chúng ta sẽ lấy giá trị trực tiếp
        binding.buttonSelectSource.text = viewModel.sourceLanguage.value?.code?.uppercase() ?: "..."
        binding.buttonSelectTarget.text = viewModel.targetLanguage.value?.code?.uppercase() ?: "..."
    }

    private fun loadLanguages() {
        val recentLanguages = if (isSelectingSource) {
            viewModel.recentSourceLanguages.value ?: emptyList()
        } else {
            viewModel.recentTargetLanguages.value ?: emptyList()
        }
        val allLanguages = viewModel.getAllLanguages()
        val items = LanguageAdapter.createFullList(recentLanguages, allLanguages, context)
        adapter.submitList(items)
    }

     private fun searchLanguages(query: String) {
        if (query.isEmpty()) {
            loadLanguages()
            return
        }
        val searchResults = viewModel.searchLanguages(query)
        val items = searchResults.map { LanguageListItem.LanguageItem(it) }
        adapter.submitList(items)
        binding.tvNoResults.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateButtonStyles() {
        val selectedButton: MaterialButton
        val unselectedButton: MaterialButton

        if (isSelectingSource) {
            selectedButton = binding.buttonSelectSource
            unselectedButton = binding.buttonSelectTarget
        } else {
            selectedButton = binding.buttonSelectTarget
            unselectedButton = binding.buttonSelectSource
        }

        selectedButton.setBackgroundColor(ContextCompat.getColor(context, R.color.primary_container))
        selectedButton.strokeWidth = 0

        unselectedButton.setBackgroundColor(ContextCompat.getColor(context, android.R.color.transparent))
        unselectedButton.strokeWidth = resources.getDimensionPixelSize(R.dimen.button_stroke_width)
        unselectedButton.setStrokeColorResource(R.color.outline)
    }

    private fun animateIn() {
        binding.backgroundOverlay.alpha = 0f
        ObjectAnimator.ofFloat(binding.backgroundOverlay, "alpha", 0f, 1f).setDuration(300).start()

        binding.contentContainer.translationY = 500f
        binding.contentContainer.animate().translationY(0f).setDuration(300).start()
    }

    fun dismiss() {
        binding.backgroundOverlay.alpha = 1f
        ObjectAnimator.ofFloat(binding.backgroundOverlay, "alpha", 1f, 0f).setDuration(250).start()

        binding.contentContainer.translationY = 0f
        binding.contentContainer.animate().translationY(binding.contentContainer.height.toFloat()).setDuration(250)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onDismiss(languageWasSelected)
                }
            }).start()
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event?.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            dismiss()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}