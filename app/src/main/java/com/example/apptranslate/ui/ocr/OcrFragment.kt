package com.example.apptranslate.ui.ocr

import android.Manifest
import android.app.Activity
import android.content.Context
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
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.example.apptranslate.R
import com.example.apptranslate.databinding.FragmentOcrBinding
import com.example.apptranslate.ocr.OcrHelper
import com.example.apptranslate.ocr.OcrResult
import com.example.apptranslate.viewmodel.LanguageViewModel
import com.example.apptranslate.viewmodel.LanguageViewModelFactory

class OcrFragment : Fragment() {

    private var _binding: FragmentOcrBinding? = null
    private val binding get() = _binding!!

    private lateinit var ocrHelper: OcrHelper
    private val languageViewModel: LanguageViewModel by activityViewModels {
        LanguageViewModelFactory(requireActivity().application)
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> processImageUri(uri) }
        }
    }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val bitmap = result.data?.extras?.get("data") as? Bitmap
            bitmap?.let { processImageBitmap(it) }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) openCamera()
        else Toast.makeText(context, "Cần quyền camera để chụp ảnh", Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOcrBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // SỬA Ở ĐÂY: Khởi tạo OcrHelper với lifecycleScope
        ocrHelper = OcrHelper(requireContext(), viewLifecycleOwner.lifecycleScope)

        setupClickListeners()
        setupObservers()
    }

    private fun setupClickListeners() {
        binding.buttonSelectImage.setOnClickListener { openGallery() }
        binding.buttonTakePhoto.setOnClickListener { checkCameraPermissionAndOpen() }
        binding.buttonClearResult.setOnClickListener { clearResults() }
        binding.buttonCopyResult.setOnClickListener { copyResultToClipboard() }
    }

    private fun setupObservers() {
        // SỬA Ở ĐÂY: Lắng nghe LiveData trực tiếp từ ocrHelper
        ocrHelper.ocrResult.observe(viewLifecycleOwner) { result -> displayOcrResult(result) }
        ocrHelper.error.observe(viewLifecycleOwner) { error -> showError(error) }
        ocrHelper.isProcessing.observe(viewLifecycleOwner) { isProcessing -> updateProcessingState(isProcessing) }

        languageViewModel.sourceLanguage.observe(viewLifecycleOwner) { language ->
            binding.textCurrentLanguage.text = "Ngôn ngữ OCR: ${language.name}"
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        pickImageLauncher.launch(intent)
    }

    private fun checkCameraPermissionAndOpen() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openCamera() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        takePictureLauncher.launch(intent)
    }

    private fun processImageUri(uri: Uri) {
        languageViewModel.sourceLanguage.value?.code?.let {
            ocrHelper.recognizeTextFromUri(uri, it)
            binding.imageViewSelected.setImageURI(uri)
        } ?: showError("Vui lòng chọn ngôn ngữ trước.")
    }

    private fun processImageBitmap(bitmap: Bitmap) {
        languageViewModel.sourceLanguage.value?.code?.let {
            ocrHelper.recognizeTextFromBitmap(bitmap, it)
            binding.imageViewSelected.setImageBitmap(bitmap)
        } ?: showError("Vui lòng chọn ngôn ngữ trước.")
    }

    private fun displayOcrResult(result: OcrResult) {
        // SỬA Ở ĐÂY: Cập nhật cách hiển thị kết quả
        binding.textOcrResult.text = result.fullText
        binding.textOcrStats.text = "Xử lý trong: ${result.processingTimeMs}ms\n" +
                                   "Số khối văn bản: ${result.textBlocks.size}"
        binding.buttonCopyResult.visibility = View.VISIBLE
        binding.buttonClearResult.visibility = View.VISIBLE
        binding.scrollView.post { binding.scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun showError(error: String) {
        binding.textOcrResult.text = "❌ Lỗi: $error"
        binding.textOcrStats.text = ""
        Toast.makeText(context, error, Toast.LENGTH_LONG).show()
    }

    private fun updateProcessingState(isProcessing: Boolean) {
        binding.progressBarOcr.visibility = if (isProcessing) View.VISIBLE else View.GONE
        binding.buttonSelectImage.isEnabled = !isProcessing
        binding.buttonTakePhoto.isEnabled = !isProcessing
        if (isProcessing) {
            binding.textOcrResult.text = "🔍 Đang nhận dạng văn bản..."
            binding.textOcrStats.text = ""
        }
    }

    private fun clearResults() {
        binding.textOcrResult.text = ""
        binding.textOcrStats.text = ""
        binding.imageViewSelected.setImageResource(R.drawable.ic_image_placeholder)
        binding.buttonCopyResult.visibility = View.GONE
        binding.buttonClearResult.visibility = View.GONE
    }

    private fun copyResultToClipboard() {
        val text = binding.textOcrResult.text.toString()
        if (text.isNotBlank()) {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("OCR Result", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Đã sao chép kết quả", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}