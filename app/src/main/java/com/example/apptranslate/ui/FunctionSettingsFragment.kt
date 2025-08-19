package com.example.apptranslate.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.navArgs
import com.example.apptranslate.databinding.FragmentFunctionSettingsBinding

/**
 * Fragment placeholder cho cài đặt các chức năng dịch
 */
class FunctionSettingsFragment : Fragment() {
    
    private var _binding: FragmentFunctionSettingsBinding? = null
    private val binding get() = _binding!!
    
    // Arguments từ Navigation Component
    private val args: FunctionSettingsFragmentArgs by navArgs()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFunctionSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupToolbar()
        setupContent()
    }
    
    private fun setupToolbar() {
        val functionName = when (args.functionType) {
            "GLOBAL" -> "Dịch toàn cầu"
            "AUTO_GLOBAL" -> "Dịch toàn cầu tự động"
            "AUTO_AREA" -> "Dịch vùng tự động"
            else -> "Cài đặt chức năng"
        }
        
        binding.tvTitle.text = "Cài đặt $functionName"
    }
    
    private fun setupContent() {
        binding.tvDescription.text = "Màn hình cài đặt cho chức năng ${args.functionType} sẽ được triển khai ở đây."
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
