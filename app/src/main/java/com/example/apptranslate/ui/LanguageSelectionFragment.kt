// File: app/src/main/java/com/example/apptranslate/ui/LanguageSelectionFragment.kt

package com.example.apptranslate.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.apptranslate.R
import com.example.apptranslate.adapter.LanguageAdapter
import com.example.apptranslate.adapter.LanguageListItem
import com.example.apptranslate.databinding.FragmentLanguageSelectionBinding
import com.example.apptranslate.viewmodel.LanguageViewModel
import com.example.apptranslate.viewmodel.LanguageViewModelFactory

class LanguageSelectionFragment : Fragment() {

    private var _binding: FragmentLanguageSelectionBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LanguageViewModel by activityViewModels {
        LanguageViewModelFactory(requireActivity().application)
    }

    private val args: LanguageSelectionFragmentArgs by navArgs()
    private lateinit var adapter: LanguageAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
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
        binding.toolbar.title = if (args.isSourceLanguage) {
            getString(R.string.select_source_language)
        } else {
            getString(R.string.select_target_language)
        }
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupAdapter() {
        adapter = LanguageAdapter { language ->
            if (args.isSourceLanguage) {
                viewModel.setSourceLanguage(language)
            } else {
                viewModel.setTargetLanguage(language)
            }
            findNavController().navigateUp()
        }
        binding.rvLanguages.adapter = adapter
    }

    private fun setupSearchField() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchLanguages(s.toString().trim())
            }
        })
    }

    /**
     * ✨ CẬP NHẬT LOGIC TẢI NGÔN NGỮ ✨
     * Tải và hiển thị cả danh sách ngôn ngữ gần đây và tất cả.
     */
    private fun loadLanguages() {
        val recentLanguages = if (args.isSourceLanguage) {
            viewModel.recentSourceLanguages.value ?: emptyList()
        } else {
            viewModel.recentTargetLanguages.value ?: emptyList()
        }
        val allLanguages = viewModel.getAllLanguages()

        val items = LanguageAdapter.createFullList(recentLanguages, allLanguages, requireContext())
        adapter.submitList(items)

        binding.rvLanguages.visibility = View.VISIBLE
        binding.tvEmptyState.visibility = View.GONE
    }

    /**
     * ✨ CẬP NHẬT LOGIC TÌM KIẾM ✨
     * Chỉ hiển thị kết quả tìm kiếm, không có header.
     */
    private fun searchLanguages(query: String) {
        if (query.isEmpty()) {
            loadLanguages()
            return
        }
        val searchResults = viewModel.searchLanguages(query)
        if (searchResults.isEmpty()) {
            binding.rvLanguages.visibility = View.GONE
            binding.tvEmptyState.visibility = View.VISIBLE
        } else {
            binding.rvLanguages.visibility = View.VISIBLE
            binding.tvEmptyState.visibility = View.GONE
            val items = searchResults.map { LanguageListItem.LanguageItem(it) }
            adapter.submitList(items)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}