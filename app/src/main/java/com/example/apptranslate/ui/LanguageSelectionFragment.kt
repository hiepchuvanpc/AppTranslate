package com.example.apptranslate.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.apptranslate.R
import com.example.apptranslate.adapter.LanguageAdapter
import com.example.apptranslate.databinding.FragmentLanguageSelectionBinding
import com.example.apptranslate.model.Language
import com.example.apptranslate.viewmodel.LanguageViewModel
import com.example.apptranslate.viewmodel.LanguageViewModelFactory

/**
 * Fragment hiển thị màn hình chọn ngôn ngữ
 */
class LanguageSelectionFragment : Fragment() {

    private var _binding: FragmentLanguageSelectionBinding? = null
    private val binding get() = _binding!!
    
    // Shared ViewModel
    private val viewModel: LanguageViewModel by activityViewModels { LanguageViewModelFactory() }
    
    // Arguments từ Navigation Component
    private val args: LanguageSelectionFragmentArgs by navArgs()
    
    // Adapter cho RecyclerView
    private lateinit var adapter: LanguageAdapter
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLanguageSelectionBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupToolbar()
        setupAdapter()
        setupSearchField()
        loadLanguages()
    }
    
    private fun setupToolbar() {
        // Cập nhật tiêu đề dựa vào loại selection (source/target)
        binding.toolbar.title = if (args.isSourceLanguage) {
            getString(R.string.select_source_language)
        } else {
            getString(R.string.select_target_language)
        }
        
        // Thiết lập nút quay lại
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }
    
    private fun setupAdapter() {
        adapter = LanguageAdapter { language ->
            // Xử lý khi người dùng chọn một ngôn ngữ
            if (args.isSourceLanguage) {
                viewModel.setSourceLanguage(language)
            } else {
                viewModel.setTargetLanguage(language)
            }
            
            // Quay lại màn hình trước
            findNavController().navigateUp()
        }
        
        binding.rvLanguages.adapter = adapter
    }
    
    private fun setupSearchField() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
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
    
    private fun loadLanguages() {
        val recentLanguages = if (args.isSourceLanguage) {
            viewModel.recentSourceLanguages.value ?: emptyList()
        } else {
            viewModel.recentTargetLanguages.value ?: emptyList()
        }
        
        val allLanguages = viewModel.getAllLanguages()
        
        val items = LanguageAdapter.createFullList(recentLanguages, allLanguages)
        adapter.submitList(items)
        
        // Hiển thị RecyclerView, ẩn empty state
        binding.rvLanguages.visibility = View.VISIBLE
        binding.tvEmptyState.visibility = View.GONE
    }
    
    private fun searchLanguages(query: String) {
        val searchResults = viewModel.searchLanguages(query)
        
        if (searchResults.isEmpty()) {
            // Hiển thị empty state
            binding.rvLanguages.visibility = View.GONE
            binding.tvEmptyState.visibility = View.VISIBLE
        } else {
            // Hiển thị kết quả tìm kiếm
            binding.rvLanguages.visibility = View.VISIBLE
            binding.tvEmptyState.visibility = View.GONE
            
            val items = LanguageAdapter.createSearchResultsList(searchResults)
            adapter.submitList(items)
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
    
    companion object {
        // Tạo Bundle arguments cho Fragment
        fun createBundle(isSourceLanguage: Boolean): Bundle {
            return bundleOf("isSourceLanguage" to isSourceLanguage)
        }
    }
}
