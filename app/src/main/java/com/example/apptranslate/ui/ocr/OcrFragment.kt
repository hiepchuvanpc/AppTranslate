package com.example.apptranslate.ui.ocr

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.apptranslate.databinding.FragmentOcrBinding
import com.example.apptranslate.ocr.OcrHelper
import com.example.apptranslate.ocr.OcrResult
import com.example.apptranslate.viewmodel.LanguageViewModel

/**
 * Fragment demo cho chức năng OCR
 */
class OcrFragment : Fragment() {
    
    private var _binding: FragmentOcrBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var ocrHelper: OcrHelper
    private lateinit var languageViewModel: LanguageViewModel
    
    // ActivityResultLauncher để chọn ảnh từ gallery
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                processImageUri(uri)
            }
        }
    }
    
    // ActivityResultLauncher để chụp ảnh từ camera
    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val bitmap = result.data?.extras?.get("data") as? Bitmap
            bitmap?.let { processImageBitmap(it) }
        }
    }
    
    // Permission launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            openCamera()
        } else {
            Toast.makeText(context, "Cần quyền camera để chụp ảnh", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOcrBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupOcrHelper()
        setupLanguageViewModel()
        setupClickListeners()
        setupObservers()
    }
    
    private fun setupOcrHelper() {
        ocrHelper = OcrHelper.getInstance(requireContext())
    }
    
    private fun setupLanguageViewModel() {
        languageViewModel = ViewModelProvider(requireActivity())[LanguageViewModel::class.java]
    }
    
    private fun setupClickListeners() {
        binding.apply {
            // Nút chọn ảnh từ gallery
            buttonSelectImage.setOnClickListener {
                openGallery()
            }
            
            // Nút chụp ảnh từ camera
            buttonTakePhoto.setOnClickListener {
                checkCameraPermissionAndOpen()
            }
            
            // Nút xóa kết quả
            buttonClearResult.setOnClickListener {
                clearResults()
            }
            
            // Nút copy kết quả
            buttonCopyResult.setOnClickListener {
                copyResultToClipboard()
            }
        }
    }
    
    private fun setupObservers() {
        // Observer cho OCR result, error, và processing state
        ocrHelper.setupObservers(
            viewLifecycleOwner,
            onResult = { result -> displayOcrResult(result) },
            onError = { error -> showError(error) },
            onProcessingChange = { isProcessing -> updateProcessingState(isProcessing) }
        )
        
        // Observer cho ngôn ngữ hiện tại
        languageViewModel.sourceLanguage.observe(viewLifecycleOwner) { language ->
            binding.textCurrentLanguage.text = "Ngôn ngữ hiện tại: ${language.name}"
        }
    }
    
    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        pickImageLauncher.launch(intent)
    }
    
    private fun checkCameraPermissionAndOpen() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                openCamera()
            }
            ActivityCompat.shouldShowRequestPermissionRationale(
                requireActivity(),
                Manifest.permission.CAMERA
            ) -> {
                Toast.makeText(
                    context,
                    "Ứng dụng cần quyền camera để chụp ảnh",
                    Toast.LENGTH_LONG
                ).show()
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }
    
    private fun openCamera() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        takePictureLauncher.launch(intent)
    }
    
    private fun processImageUri(uri: Uri) {
        val currentLanguage = languageViewModel.sourceLanguage.value
        if (currentLanguage != null) {
            ocrHelper.recognizeTextFromUri(uri, currentLanguage.code)
            binding.imageViewSelected.setImageURI(uri)
        } else {
            showError("Vui lòng chọn ngôn ngữ trước")
        }
    }
    
    private fun processImageBitmap(bitmap: Bitmap) {
        val currentLanguage = languageViewModel.sourceLanguage.value
        if (currentLanguage != null) {
            ocrHelper.recognizeTextFromBitmap(bitmap, currentLanguage.code)
            binding.imageViewSelected.setImageBitmap(bitmap)
        } else {
            showError("Vui lòng chọn ngôn ngữ trước")
        }
    }
    
    private fun displayOcrResult(result: OcrResult) {
        binding.apply {
            // Hiển thị văn bản nhận dạng được
            textOcrResult.text = result.fullText
            
            // Hiển thị thống kê
            val stats = ocrHelper.getOcrStatistics(result)
            val statsText = buildString {
                append("📊 Thống kê OCR:\n")
                append("• Thời gian xử lý: ${stats["processingTimeMs"]}ms\n")
                append("• Số khối văn bản: ${stats["totalBlocks"]}\n")
                append("• Số dòng: ${stats["totalLines"]}\n")
                append("• Số ký tự: ${stats["totalCharacters"]}\n")
                append("• Hệ chữ: ${stats["scriptType"]}\n")
                append("• Độ tin cậy TB: ${String.format("%.2f", stats["averageConfidence"])}")
            }
            textOcrStats.text = statsText
            
            // Hiển thị các nút hành động
            buttonCopyResult.visibility = View.VISIBLE
            buttonClearResult.visibility = View.VISIBLE
            
            // Scroll xuống để xem kết quả
            scrollView.post {
                scrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
    }
    
    private fun showError(error: String) {
        binding.textOcrResult.text = "❌ Lỗi: $error"
        binding.textOcrStats.text = ""
        Toast.makeText(context, error, Toast.LENGTH_LONG).show()
    }
    
    private fun updateProcessingState(isProcessing: Boolean) {
        binding.apply {
            progressBarOcr.visibility = if (isProcessing) View.VISIBLE else View.GONE
            buttonSelectImage.isEnabled = !isProcessing
            buttonTakePhoto.isEnabled = !isProcessing
            
            if (isProcessing) {
                textOcrResult.text = "🔍 Đang nhận dạng văn bản..."
                textOcrStats.text = ""
            }
        }
    }
    
    private fun clearResults() {
        binding.apply {
            textOcrResult.text = ""
            textOcrStats.text = ""
            imageViewSelected.setImageDrawable(null)
            buttonCopyResult.visibility = View.GONE
            buttonClearResult.visibility = View.GONE
        }
    }
    
    private fun copyResultToClipboard() {
        val text = binding.textOcrResult.text.toString()
        if (text.isNotEmpty()) {
            val clipboard = ContextCompat.getSystemService(
                requireContext(),
                android.content.ClipboardManager::class.java
            )
            val clip = android.content.ClipData.newPlainText("OCR Result", text)
            clipboard?.setPrimaryClip(clip)
            Toast.makeText(context, "Đã copy kết quả", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (::ocrHelper.isInitialized) {
            ocrHelper.cleanup()
        }
    }
}
