package com.example.apptranslate.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.apptranslate.R
import com.example.apptranslate.adapter.LanguageAdapter
import com.example.apptranslate.databinding.ViewLanguageBottomSheetBinding
import com.example.apptranslate.model.Language
import com.example.apptranslate.viewmodel.LanguageViewModel
import com.example.apptranslate.viewmodel.LanguageViewModelFactory
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Bottom Sheet Dialog cho việc chọn ngôn ngữ với khả năng cập nhật tức thời
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

    // Shared ViewModel
    private val viewModel: LanguageViewModel by activityViewModels { LanguageViewModelFactory() }

    // Adapter cho RecyclerView
    private lateinit var adapter: LanguageAdapter

    // Trạng thái chọn nguồn hoặc đích
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

        setupToggleGroup()
        setupAdapter()
        setupSearchField()
        setupConfirmButton()
        observeLanguages()
        loadLanguages()
    }

    /**
     * Thiết lập toggle group để chọn giữa ngôn ngữ nguồn và đích
     */
    private fun setupToggleGroup() {
        // Thiết lập click listener cho các nút chọn ngôn ngữ
        binding.buttonSelectSource.setOnClickListener {
            isSelectingSource = true
            binding.buttonSelectSource.setBackgroundResource(R.drawable.button_selected_background)
            binding.buttonSelectTarget.setBackgroundResource(R.drawable.button_unselected_background)
            binding.editTextSearch.text?.clear() // Xóa trường tìm kiếm khi chuyển tab
            loadLanguages()
        }
        
        binding.buttonSelectTarget.setOnClickListener {
            isSelectingSource = false
            binding.buttonSelectTarget.setBackgroundResource(R.drawable.button_selected_background)
            binding.buttonSelectSource.setBackgroundResource(R.drawable.button_unselected_background)
            binding.editTextSearch.text?.clear() // Xóa trường tìm kiếm khi chuyển tab
            loadLanguages()
        }
        
        // Mặc định chọn nguồn
        binding.buttonSelectSource.performClick()
    }
    
    /**
     * Thiết lập nút xác nhận (FAB)
     */
    private fun setupConfirmButton() {
        binding.fabConfirm.setOnClickListener {
            // Đóng Bottom Sheet khi người dùng nhấn nút xác nhận
            dismiss()
        }
    }

    /**
     * Thiết lập adapter cho RecyclerView
     */
    private fun setupAdapter() {
        adapter = LanguageAdapter { language ->
            // Xử lý khi người dùng chọn một ngôn ngữ
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

    /**
     * Thiết lập search field để tìm kiếm ngôn ngữ
     */
    private fun setupSearchField() {
        binding.editTextSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim()
                if (query.isEmpty()) {
                    // Hiển thị lại danh sách đầy đủ
                    loadLanguages()
                } else {
                    // Hiển thị kết quả tìm kiếm
                    searchLanguages(query)
                }
            }
        })
    }

    /**
     * Quan sát thay đổi từ ViewModel để cập nhật UI
     */
    private fun observeLanguages() {
        // Quan sát ngôn ngữ nguồn
        viewModel.sourceLanguage.observe(viewLifecycleOwner) { language ->
            binding.buttonSelectSource.text = language.nativeName
        }

        // Quan sát ngôn ngữ đích
        viewModel.targetLanguage.observe(viewLifecycleOwner) { language ->
            binding.buttonSelectTarget.text = language.nativeName
        }
    }

    /**
     * Tải danh sách ngôn ngữ phù hợp với trạng thái hiện tại
     */
    private fun loadLanguages() {
        val recentLanguages = if (isSelectingSource) {
            viewModel.recentSourceLanguages.value ?: emptyList()
        } else {
            viewModel.recentTargetLanguages.value ?: emptyList()
        }

        val allLanguages = viewModel.getAllLanguages()
        
        // Sử dụng phân nhóm theo khu vực để hiển thị ngôn ngữ
        val items = LanguageAdapter.createFullList(recentLanguages, allLanguages, groupByRegion = true)
        adapter.submitList(items)
    }

    /**
     * Tìm kiếm ngôn ngữ theo từ khóa
     */
    private fun searchLanguages(query: String) {
        val searchResults = viewModel.searchLanguages(query)

        if (searchResults.isNotEmpty()) {
            val items = LanguageAdapter.createSearchResultsList(searchResults)
            adapter.submitList(items)
            binding.tvNoResults.visibility = View.GONE
        } else {
            adapter.submitList(emptyList())
            binding.tvNoResults.visibility = View.VISIBLE
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
