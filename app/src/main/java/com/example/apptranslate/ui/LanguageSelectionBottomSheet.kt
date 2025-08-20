// File: app/src/main/java/com/example/apptranslate/ui/LanguageSelectionBottomSheet.kt

package com.example.apptranslate.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.apptranslate.R
import com.example.apptranslate.adapter.LanguageAdapter
import com.example.apptranslate.databinding.ViewLanguageBottomSheetBinding
import com.example.apptranslate.model.Language
import com.example.apptranslate.viewmodel.LanguageViewModel
import com.example.apptranslate.viewmodel.LanguageViewModelFactory
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.example.apptranslate.adapter.LanguageListItem
/**
 * Bottom Sheet Dialog cho việc chọn ngôn ngữ, tự động đóng sau khi chọn.
 */
class LanguageSelectionBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "LanguageSelectionBottomSheet"
        fun newInstance(): LanguageSelectionBottomSheet {
            return LanguageSelectionBottomSheet()
        }
    }

    private var _binding: ViewLanguageBottomSheetBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LanguageViewModel by activityViewModels {
        LanguageViewModelFactory(requireActivity().application)
    }
    private lateinit var adapter: LanguageAdapter
    private var isSelectingSource = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ViewLanguageBottomSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupAdapter()
        setupToggleGroup()
        setupSearchField()
        observeLanguages()
    }

    private fun setupToggleGroup() {
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
        updateButtonStyles()
        loadLanguages()
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

        selectedButton.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.primary_container))
        selectedButton.strokeWidth = 0

        unselectedButton.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.transparent))
        unselectedButton.strokeWidth = resources.getDimensionPixelSize(R.dimen.button_stroke_width)
        unselectedButton.setStrokeColorResource(R.color.outline)
    }

    private fun setupAdapter() {
        adapter = LanguageAdapter { language ->
            if (isSelectingSource) {
                viewModel.setSourceLanguage(language)
            } else {
                viewModel.setTargetLanguage(language)
            }
            // Đóng Bottom Sheet ngay sau khi chọn
            dismiss()
        }
        binding.recyclerViewLanguages.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewLanguages.adapter = adapter
    }

    private fun setupSearchField() {
        binding.editTextSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchLanguages(s.toString().trim())
            }
        })
    }

    private fun observeLanguages() {
        viewModel.sourceLanguage.observe(viewLifecycleOwner) { language ->
            binding.buttonSelectSource.text = language.code.uppercase()
        }
        viewModel.targetLanguage.observe(viewLifecycleOwner) { language ->
            binding.buttonSelectTarget.text = language.code.uppercase()
        }
    }

    private fun loadLanguages() {
        val recentLanguages = if (isSelectingSource) {
            viewModel.recentSourceLanguages.value ?: emptyList()
        } else {
            viewModel.recentTargetLanguages.value ?: emptyList()
        }
        val allLanguages = viewModel.getAllLanguages()

        // ✨ SỬ DỤNG HÀM HELPER MỚI CỦA ADAPTER ✨
        val items = LanguageAdapter.createFullList(recentLanguages, allLanguages, requireContext())

        adapter.submitList(items)
        binding.tvNoResults.visibility = View.GONE
        binding.recyclerViewLanguages.visibility = View.VISIBLE
    }

    private fun searchLanguages(query: String) {
        if (query.isEmpty()) {
            loadLanguages()
            return
        }
        val searchResults = viewModel.searchLanguages(query)
        if (searchResults.isNotEmpty()) {
            // ✨ Khi tìm kiếm, chỉ hiển thị kết quả (không có header) ✨
            val items = searchResults.map { LanguageListItem.LanguageItem(it) }
            adapter.submitList(items)
            binding.tvNoResults.visibility = View.GONE
            binding.recyclerViewLanguages.visibility = View.VISIBLE
        } else {
            adapter.submitList(emptyList())
            binding.tvNoResults.visibility = View.VISIBLE
            binding.recyclerViewLanguages.visibility = View.GONE
        }
    }

    override fun getTheme(): Int {
        return R.style.Theme_AppTranslate_BottomSheetDialog
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}