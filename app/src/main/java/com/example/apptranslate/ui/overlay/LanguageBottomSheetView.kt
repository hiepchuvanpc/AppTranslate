package com.example.apptranslate.ui.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.apptranslate.databinding.ViewLanguageBottomSheetBinding
import com.example.apptranslate.ui.overlay.adapter.LanguageSelectionAdapter
import com.example.apptranslate.model.Language
import kotlinx.coroutines.CoroutineScope

/**
 * Bottom Sheet để chọn ngôn ngữ
 */
@SuppressLint("ViewConstructor")
class LanguageBottomSheetView(
    context: Context,
    private val coroutineScope: CoroutineScope,
    private val onDismiss: () -> Unit,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val binding: ViewLanguageBottomSheetBinding
    private lateinit var sourceAdapter: LanguageSelectionAdapter
    private lateinit var targetAdapter: LanguageSelectionAdapter

    private var selectedSourceLanguage: Language? = null
    private var selectedTargetLanguage: Language? = null

    private var isSelectingSource = true // true = source, false = target

    init {
        val inflater = LayoutInflater.from(context)
        binding = ViewLanguageBottomSheetBinding.inflate(inflater, this, true)

        setupBottomSheet()
        setupLanguageList()
        setupClickListeners()
    }

    /**
     * Thiết lập bottom sheet
     */
    private fun setupBottomSheet() {
        // Set background click để đóng
        binding.backgroundOverlay.setOnClickListener {
            animateOut { onDismiss() }
        }

        // Ngăn click trên content đóng bottom sheet
        binding.contentContainer.setOnClickListener { /* Do nothing */ }
    }

    /**
     * Thiết lập danh sách ngôn ngữ
     */
    private fun setupLanguageList() {
        // Tạo danh sách ngôn ngữ mẫu
        val languages = createLanguageList()

        // Setup RecyclerView
        binding.recyclerViewLanguages.layoutManager = LinearLayoutManager(context)

        // Adapter cho source language
        sourceAdapter = LanguageSelectionAdapter { language ->
            selectedSourceLanguage = language
            updateLanguageButtons()
        }

        // Adapter cho target language
        targetAdapter = LanguageSelectionAdapter { language ->
            selectedTargetLanguage = language
            updateLanguageButtons()
        }

        // Set adapter ban đầu (source)
        binding.recyclerViewLanguages.adapter = sourceAdapter
        sourceAdapter.submitList(languages)

        // Update language buttons
        updateLanguageButtons()
    }

    /**
     * Thiết lập click listeners
     */
    private fun setupClickListeners() {
        // Nút chọn ngôn ngữ nguồn
        binding.buttonSelectSource.setOnClickListener {
            isSelectingSource = true
            updateSelectionMode()
        }

        // Nút chọn ngôn ngữ đích
        binding.buttonSelectTarget.setOnClickListener {
            isSelectingSource = false
            updateSelectionMode()
        }

        // FAB confirm
        binding.fabConfirm.setOnClickListener {
            saveLanguageChanges()
            animateOut { onDismiss() }
        }

        // Search functionality
        binding.editTextSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterLanguages(s.toString())
            }
        })
    }

    /**
     * Cập nhật chế độ chọn (source/target)
     */
    private fun updateSelectionMode() {
        if (isSelectingSource) {
            // Chọn ngôn ngữ nguồn
            binding.buttonSelectSource.isSelected = true
            binding.buttonSelectTarget.isSelected = false
            binding.recyclerViewLanguages.adapter = sourceAdapter
            binding.editTextSearch.hint = "Tìm ngôn ngữ nguồn..."
        } else {
            // Chọn ngôn ngữ đích
            binding.buttonSelectSource.isSelected = false
            binding.buttonSelectTarget.isSelected = true
            binding.recyclerViewLanguages.adapter = targetAdapter
            binding.editTextSearch.hint = "Tìm ngôn ngữ đích..."
        }

        // Clear search
        binding.editTextSearch.setText("")

        // Refresh adapter
        val languages = createLanguageList()
        if (isSelectingSource) {
            sourceAdapter.submitList(languages)
        } else {
            targetAdapter.submitList(languages)
        }
    }

    /**
     * Cập nhật text trên các nút ngôn ngữ
     */
    private fun updateLanguageButtons() {
        binding.buttonSelectSource.text = selectedSourceLanguage?.name ?: "Chọn ngôn ngữ nguồn"
        binding.buttonSelectTarget.text = selectedTargetLanguage?.name ?: "Chọn ngôn ngữ đích"

        // Enable FAB nếu đã chọn cả hai ngôn ngữ
        val bothSelected = selectedSourceLanguage != null && selectedTargetLanguage != null
        binding.fabConfirm.isEnabled = bothSelected
        binding.fabConfirm.alpha = if (bothSelected) 1.0f else 0.5f
    }

    /**
     * Lọc danh sách ngôn ngữ theo search
     */
    private fun filterLanguages(query: String) {
        val allLanguages = createLanguageList()
        val filteredLanguages = if (query.isBlank()) {
            allLanguages
        } else {
            allLanguages.filter {
                it.name.contains(query, ignoreCase = true) ||
                it.code.contains(query, ignoreCase = true)
            }
        }

        if (isSelectingSource) {
            sourceAdapter.submitList(filteredLanguages)
        } else {
            targetAdapter.submitList(filteredLanguages)
        }
    }

    /**
     * Lưu thay đổi ngôn ngữ
     */
    private fun saveLanguageChanges() {
        // TODO: Integrate with LanguageViewModel
        // Tạm thời log các ngôn ngữ đã chọn
        selectedSourceLanguage?.let { source ->
            println("Selected source language: ${source.name}")
        }

        selectedTargetLanguage?.let { target ->
            println("Selected target language: ${target.name}")
        }
    }

    /**
     * Tạo danh sách ngôn ngữ mẫu
     */
    private fun createLanguageList(): List<Language> {
        return listOf(
            Language("en", "English", "🇺🇸"),
            Language("vi", "Tiếng Việt", "🇻🇳"),
            Language("ja", "日本語", "🇯🇵"),
            Language("ko", "한국어", "🇰🇷"),
            Language("zh", "中文", "🇨🇳"),
            Language("zh-tw", "繁體中文", "🇹🇼"),
            Language("es", "Español", "🇪🇸"),
            Language("fr", "Français", "🇫🇷"),
            Language("de", "Deutsch", "🇩🇪"),
            Language("it", "Italiano", "🇮🇹"),
            Language("pt", "Português", "🇵🇹"),
            Language("ru", "Русский", "🇷🇺"),
            Language("ar", "العربية", "🇸🇦"),
            Language("hi", "हिन्दी", "🇮🇳"),
            Language("th", "ไทย", "🇹🇭"),
            Language("tr", "Türkçe", "🇹🇷")
        )
    }

    /**
     * Animation hiển thị bottom sheet
     */
    fun animateIn() {
        // Start from bottom
        binding.contentContainer.translationY = binding.contentContainer.height.toFloat()
        binding.backgroundOverlay.alpha = 0f

        // Animate background
        binding.backgroundOverlay.animate()
            .alpha(0.5f)
            .setDuration(300)
            .start()

        // Animate content sliding up
        binding.contentContainer.animate()
            .translationY(0f)
            .setDuration(300)
            .start()
    }

    /**
     * Animation ẩn bottom sheet
     */
    fun animateOut(onComplete: (() -> Unit)? = null) {
        // Animate background
        binding.backgroundOverlay.animate()
            .alpha(0f)
            .setDuration(200)
            .start()

        // Animate content sliding down
        binding.contentContainer.animate()
            .translationY(binding.contentContainer.height.toFloat())
            .setDuration(200)
            .withEndAction {
                onComplete?.invoke()
            }
            .start()
    }
}
