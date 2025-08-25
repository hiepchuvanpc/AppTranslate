// File: app/src/main/java/com/example/apptranslate/ui/HomeFragment.kt

package com.example.apptranslate.ui

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.example.apptranslate.R
import com.example.apptranslate.adapter.FunctionGridAdapter
import com.example.apptranslate.databinding.FragmentHomeBinding
import com.example.apptranslate.model.FunctionItem
import com.example.apptranslate.service.OverlayService
import com.example.apptranslate.viewmodel.LanguageViewModel
import com.example.apptranslate.viewmodel.LanguageViewModelFactory
import com.example.apptranslate.viewmodel.TranslationMode
import com.example.apptranslate.viewmodel.TranslationSource

class HomeFragment : Fragment() {

    companion object {
        private const val TAG = "HomeFragment"
    }

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private var isRunning = false

    private val viewModel: LanguageViewModel by activityViewModels {
        LanguageViewModelFactory(requireActivity().application)
    }

    private lateinit var functionAdapter: FunctionGridAdapter
    private lateinit var mediaProjectionManager: MediaProjectionManager

    // SỬA LỖI: Khai báo BroadcastReceiver ở cấp độ class để có thể truy cập ở cả register và unregister
    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                OverlayService.BROADCAST_SERVICE_STARTED -> {
                    Log.d(TAG, "Service started successfully")
                    isRunning = true
                    updateFabIcon()
                    updateServiceLanguages()
                }
                OverlayService.ACTION_LANGUAGES_UPDATED_FROM_SERVICE -> {
                    Log.d(TAG, "Received language update from service. Reloading settings.")
                    viewModel.loadSettings()
                }
                OverlayService.BROADCAST_SERVICE_STOPPED,
                OverlayService.BROADCAST_SERVICE_ERROR -> {
                    Log.e(TAG, "Service stopped or error occurred")
                    isRunning = false
                    updateFabIcon()
                }
            }
        }
    }

    // (Các ActivityResultLauncher giữ nguyên...)
    private val screenCapturePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { startOverlayService(result.resultCode, it) }
        } else {
            Toast.makeText(requireContext(), getString(R.string.screen_capture_permission_required), Toast.LENGTH_LONG).show()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (canDrawOverlays()) {
            requestScreenCapturePermission()
        } else {
            Toast.makeText(requireContext(), getString(R.string.overlay_permission_required), Toast.LENGTH_LONG).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            if (!canDrawOverlays()) {
                requestOverlayPermission()
            } else {
                requestScreenCapturePermission()
            }
        } else {
            Toast.makeText(requireContext(), "Quyền thông báo là cần thiết cho ứng dụng hoạt động đúng", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mediaProjectionManager = requireContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        registerServiceStateReceiver()

        setupLanguageSelection()
        setupTranslationOptions()
        setupAiSettingsButton()
        setupFunctionGrid()
        setupFab()
        observeLanguageChanges()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkNotificationPermission()
        }
    }

    override fun onDestroyView() {
        unregisterServiceStateReceiver()
        super.onDestroyView()
        binding.rvFunctions.adapter = null
        _binding = null
    }

    // SỬA LỖI: Chỉ có MỘT phiên bản của hàm này
    private fun registerServiceStateReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(OverlayService.BROADCAST_SERVICE_STARTED)
            addAction(OverlayService.BROADCAST_SERVICE_STOPPED)
            addAction(OverlayService.BROADCAST_SERVICE_ERROR)
            addAction(OverlayService.ACTION_LANGUAGES_UPDATED_FROM_SERVICE)
        }
        ContextCompat.registerReceiver(
            requireContext(),
            serviceStateReceiver,
            intentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        Log.d(TAG, "Service state receiver registered")
    }

    // SỬA LỖI: Chỉ có MỘT phiên bản của hàm này
    private fun unregisterServiceStateReceiver() {
        try {
            requireContext().unregisterReceiver(serviceStateReceiver)
            Log.d(TAG, "Service state receiver unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering service state receiver", e)
        }
    }

    // (Toàn bộ các hàm setup, observe và xử lý logic khác giữ nguyên như trong file gốc của bạn)

    private fun setupLanguageSelection() {
        binding.languageCard.btnSourceLanguage.setOnClickListener {
            navigateToLanguageSelection(true)
        }
        binding.languageCard.btnTargetLanguage.setOnClickListener {
            navigateToLanguageSelection(false)
        }
        binding.languageCard.btnSwapLanguages.setOnClickListener {
            viewModel.swapLanguages()
        }
    }

    private fun setupTranslationOptions() {
        val translationSources = listOf(
            TranslationSource.AI,
            TranslationSource.GOOGLE,
            TranslationSource.OFFLINE
        )

        val spinnerAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            translationSources.map {
                when(it) {
                    TranslationSource.AI -> getString(R.string.translation_source_ai)
                    TranslationSource.GOOGLE -> getString(R.string.translation_source_google)
                    TranslationSource.OFFLINE -> getString(R.string.translation_source_offline)
                }
            }
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.languageCard.spinnerTranslationSource.adapter = spinnerAdapter

        binding.languageCard.spinnerTranslationSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedSource = translationSources[position]
                viewModel.setTranslationSource(selectedSource)
                binding.languageCard.btnAiSettings.visibility = if (selectedSource == TranslationSource.AI) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.languageCard.toggleTranslationMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btn_mode_simple -> viewModel.setTranslationMode(TranslationMode.SIMPLE)
                    R.id.btn_mode_advanced -> viewModel.setTranslationMode(TranslationMode.ADVANCED)
                }
            }
        }
    }

    private fun setupAiSettingsButton() {
        binding.languageCard.btnAiSettings.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_aiSettingsFragment)
        }
    }

    private fun observeLanguageChanges() {
        viewModel.sourceLanguage.observe(viewLifecycleOwner) { language ->
            binding.languageCard.btnSourceLanguage.text = language.nativeName
            updateServiceLanguages()
        }
        viewModel.targetLanguage.observe(viewLifecycleOwner) { language ->
            binding.languageCard.btnTargetLanguage.text = language.nativeName
            updateServiceLanguages()
        }
        viewModel.translationSource.observe(viewLifecycleOwner) { source ->
            val position = when (source) {
                TranslationSource.AI -> 0
                TranslationSource.GOOGLE -> 1
                TranslationSource.OFFLINE -> 2
            }
            if (binding.languageCard.spinnerTranslationSource.selectedItemPosition != position) {
                binding.languageCard.spinnerTranslationSource.setSelection(position)
            }
        }
        viewModel.translationMode.observe(viewLifecycleOwner) { mode ->
            val buttonId = when (mode) {
                TranslationMode.SIMPLE -> R.id.btn_mode_simple
                TranslationMode.ADVANCED -> R.id.btn_mode_advanced
            }
            binding.languageCard.toggleTranslationMode.check(buttonId)
        }
    }

    private fun setupFunctionGrid() {
        val functions = listOf(
            FunctionItem("GLOBAL", R.drawable.ic_global, getString(R.string.function_global_translate), getString(R.string.function_global_translate_desc), true),
            FunctionItem("AREA", R.drawable.ic_crop, getString(R.string.function_area_translate), getString(R.string.function_area_translate_desc), false),
            FunctionItem("AUTO_GLOBAL", R.drawable.ic_auto_play, getString(R.string.function_auto_global_translate), getString(R.string.function_auto_global_translate_desc), true),
            FunctionItem("AUTO_AREA", R.drawable.ic_auto_play, getString(R.string.function_auto_area_translate), getString(R.string.function_auto_area_translate_desc), true),
            FunctionItem("IMAGE", R.drawable.ic_image, getString(R.string.function_image_translate), getString(R.string.function_image_translate_desc), true),
            FunctionItem("COPY", R.drawable.ic_copy, getString(R.string.function_copy_text), getString(R.string.function_copy_text_desc), false)
        )
        functionAdapter = FunctionGridAdapter { functionItem -> handleFunctionClick(functionItem) }
        binding.rvFunctions.adapter = functionAdapter
        functionAdapter.submitList(functions)
    }

    private fun handleFunctionClick(item: FunctionItem) {
        if (!item.isClickable) {
            Toast.makeText(requireContext(), "Chức năng '${item.title}' chưa được triển khai", Toast.LENGTH_SHORT).show()
            return
        }
        when (item.id) {
            "GLOBAL", "AUTO_GLOBAL", "AUTO_AREA" -> {
                findNavController().navigate(R.id.functionSettingsFragment, bundleOf("functionType" to item.id))
            }
            "IMAGE" -> {
                Toast.makeText(requireContext(), "Chức năng '${item.title}' sẽ được triển khai sau", Toast.LENGTH_SHORT).show()
            }
            else -> {
                Toast.makeText(requireContext(), "Chức năng '${item.title}' sẽ được triển khai sau", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun navigateToLanguageSelection(isSourceLanguage: Boolean) {
        findNavController().navigate(R.id.languageSelectionFragment, bundleOf("isSourceLanguage" to isSourceLanguage))
    }

    private fun setupFab() {
        binding.fabToggle.setOnClickListener {
            if (!isRunning) startAction() else pauseAction()
        }
        updateFabIcon()
    }

    private fun checkNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return false
            }
        }
        return true
    }

    private fun startAction() {
        if (OverlayService.isRunning) return
        if (!checkNotificationPermission()) return
        if (!canDrawOverlays()) {
            requestOverlayPermission()
            return
        }
        requestScreenCapturePermission()
    }

    private fun pauseAction() {
        if (OverlayService.isRunning) {
            val intent = Intent(requireContext(), OverlayService::class.java).apply {
                action = OverlayService.ACTION_STOP_SERVICE
            }
            requireContext().startService(intent)
            isRunning = false
            updateFabIcon()
            Toast.makeText(requireContext(), "Đã dừng dịch vụ nút nổi", Toast.LENGTH_SHORT).show()
        }
    }

    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(requireContext()) else true
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${requireContext().packageName}"))
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun requestScreenCapturePermission() {
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        screenCapturePermissionLauncher.launch(captureIntent)
    }

    private fun startOverlayService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(requireContext(), OverlayService::class.java).apply {
            action = OverlayService.ACTION_START_SERVICE
            putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
            putExtra(OverlayService.EXTRA_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(serviceIntent)
        } else {
            requireContext().startService(serviceIntent)
        }
        Toast.makeText(requireContext(), "Đang khởi động dịch vụ...", Toast.LENGTH_SHORT).show()
    }

    private fun updateFabIcon() {
        isRunning = OverlayService.isRunning
        if (isRunning) {
            binding.fabToggle.text = "Tạm dừng"
            binding.fabToggle.setIconResource(R.drawable.ic_pause)
        } else {
            binding.fabToggle.text = "Bắt đầu"
            binding.fabToggle.setIconResource(R.drawable.ic_play)
        }
    }

    override fun onResume() {
        super.onResume()
        updateFabIcon()
    }

    private fun updateServiceLanguages() {
        if (!isRunning) return
        val sourceCode = viewModel.sourceLanguage.value?.code ?: "vi"
        val targetCode = viewModel.targetLanguage.value?.code ?: "en"
        val intent = Intent(requireContext(), OverlayService::class.java).apply {
            action = OverlayService.ACTION_UPDATE_LANGUAGES
            putExtra(OverlayService.EXTRA_SOURCE_LANG, sourceCode)
            putExtra(OverlayService.EXTRA_TARGET_LANG, targetCode)
        }
        requireContext().startService(intent)
    }
}