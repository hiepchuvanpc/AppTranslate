package com.example.apptranslate.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.apptranslate.adapter.ApiKeyAdapter
import com.example.apptranslate.data.ApiKeyManager
import com.example.apptranslate.databinding.FragmentAiSettingsBinding

class AiSettingsFragment : Fragment() {
    private var _binding: FragmentAiSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var apiKeyManager: ApiKeyManager
    private lateinit var adapter: ApiKeyAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAiSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        apiKeyManager = ApiKeyManager.getInstance(requireContext())

        setupRecyclerView()
        setupListeners()
        loadKeys()
    }

    private fun setupRecyclerView() {
        adapter = ApiKeyAdapter(emptyList()) { keyString ->
            apiKeyManager.removeApiKey(keyString)
            loadKeys() // Tải lại danh sách để cập nhật UI
            Toast.makeText(requireContext(), "Đã xóa key", Toast.LENGTH_SHORT).show()
        }
        binding.rvApiKeys.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnAddApiKey.setOnClickListener {
            val newKey = binding.etGeminiApiKey.text.toString().trim()
            if (apiKeyManager.addApiKey(newKey)) {
                binding.etGeminiApiKey.text?.clear()
                loadKeys()
                Toast.makeText(requireContext(), "Đã thêm key mới", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Key không hợp lệ hoặc đã tồn tại", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadKeys() {
        val keys = apiKeyManager.getAllKeys()
        adapter.updateKeys(keys)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}