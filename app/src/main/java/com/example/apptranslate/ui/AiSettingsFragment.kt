package com.example.apptranslate.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.apptranslate.data.SettingsManager
import com.example.apptranslate.databinding.FragmentAiSettingsBinding

class AiSettingsFragment : Fragment() {
    private var _binding: FragmentAiSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAiSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val settingsManager = SettingsManager.getInstance(requireContext())

        binding.etGeminiApiKey.setText(settingsManager.getGeminiApiKey())

        binding.btnSaveApiKey.setOnClickListener {
            val apiKey = binding.etGeminiApiKey.text.toString().trim()
            settingsManager.saveGeminiApiKey(apiKey)
            Toast.makeText(requireContext(), "Đã lưu API Key", Toast.LENGTH_SHORT).show()
            findNavController().navigateUp()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}