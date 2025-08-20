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
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.core.os.bundleOf
import com.example.apptranslate.R
import com.example.apptranslate.adapter.FunctionGridAdapter
import com.example.apptranslate.adapter.TranslationSourceAdapter
import com.example.apptranslate.databinding.FragmentHomeBinding
import com.example.apptranslate.model.FunctionItem
import com.example.apptranslate.service.OverlayService
import com.example.apptranslate.viewmodel.LanguageViewModel
import com.example.apptranslate.viewmodel.LanguageViewModelFactory
import com.example.apptranslate.viewmodel.TranslationMode
import com.example.apptranslate.viewmodel.TranslationSource
/**
 * Home Fragment - Main landing screen of the app
 */
class HomeFragment : Fragment() {

    companion object {
        private const val TAG = "HomeFragment"
    }

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private var isRunning = false

    // Shared ViewModel với các Fragment khác
    private val viewModel: LanguageViewModel by activityViewModels {
        LanguageViewModelFactory(requireActivity().application)
    }

    // Adapter cho function grid
    private lateinit var functionAdapter: FunctionGridAdapter

    // MediaProjection manager
    private lateinit var mediaProjectionManager: MediaProjectionManager

    // BroadcastReceiver để lắng nghe trạng thái service
    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                OverlayService.BROADCAST_SERVICE_STARTED -> {
                    Log.d(TAG, "Service started successfully")
                    isRunning = true
                    updateFabIcon()
                    updateServiceLanguages()
                    Toast.makeText(requireContext(), "Nút nổi đã sẵn sàng!", Toast.LENGTH_SHORT).show()
                }
                OverlayService.BROADCAST_SERVICE_STOPPED -> {
                    Log.d(TAG, "Service stopped")
                    isRunning = false
                    updateFabIcon()
                    Toast.makeText(requireContext(), "Đã dừng nút nổi", Toast.LENGTH_SHORT).show()
                }
                OverlayService.BROADCAST_SERVICE_ERROR -> {
                    Log.e(TAG, "Service error occurred")
                    isRunning = false
                    updateFabIcon()
                    Toast.makeText(requireContext(), "Lỗi khi khởi động nút nổi", Toast.LENGTH_SHORT).show()
                }
                OverlayService.ACTION_SHOW_LANGUAGE_SHEET -> {
                    Log.d(TAG, "Showing Language Selection Bottom Sheet")
                    showLanguageBottomSheet()
                }
            }
        }
    }

    // ActivityResultLauncher cho Screen Capture permission
    private val screenCapturePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Người dùng đồng ý cấp quyền
            val data = result.data
            if (data != null) {
                startOverlayService(result.resultCode, data)
            }
        } else {
            // Người dùng từ chối
            Toast.makeText(
                requireContext(),
                getString(R.string.screen_capture_permission_required),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ActivityResultLauncher cho Overlay permission
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (canDrawOverlays()) {
            // Đã có quyền overlay, tiếp tục yêu cầu screen capture
            requestScreenCapturePermission()
        } else {
            Toast.makeText(
                requireContext(),
                getString(R.string.overlay_permission_required),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ActivityResultLauncher cho Notification permission (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d(TAG, "Notification permission granted")
            // Đã có quyền thông báo, tiếp tục kiểm tra quyền overlay
            if (!canDrawOverlays()) {
                requestOverlayPermission()
            } else {
                // Đã có quyền overlay, tiếp tục yêu cầu screen capture
                requestScreenCapturePermission()
            }
        } else {
            Log.d(TAG, "Notification permission denied")
            Toast.makeText(
                requireContext(),
                "Quyền thông báo là cần thiết cho ứng dụng hoạt động đúng",
                Toast.LENGTH_LONG
            ).show()
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
        setupAiSettingsButton() // ✨ THÊM HÀM NÀY
        // Initialize MediaProjectionManager
        mediaProjectionManager = requireContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Đăng ký BroadcastReceiver
        registerServiceStateReceiver()

        setupLanguageSelection()
        setupTranslationOptions()
        setupFunctionGrid()
        setupFab()
        observeLanguageChanges()

        // Kiểm tra quyền thông báo khi fragment được tạo, để cho người dùng biết sớm nếu cần cấp quyền
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkNotificationPermission()
        }
    }

    /**
     * Thiết lập các thành phần trên card chọn ngôn ngữ
     */
    private fun setupLanguageSelection() {
        // Thiết lập nút chọn ngôn ngữ nguồn
        binding.languageCard.btnSourceLanguage.setOnClickListener {
            navigateToLanguageSelection(true)
        }

        // Thiết lập nút chọn ngôn ngữ đích
        binding.languageCard.btnTargetLanguage.setOnClickListener {
            navigateToLanguageSelection(false)
        }

        // Thiết lập nút hoán đổi ngôn ngữ
        binding.languageCard.btnSwapLanguages.setOnClickListener {
            viewModel.swapLanguages()
        }
    }

    /**
     * Thiết lập các tùy chọn dịch thuật
     */
    private fun setupTranslationOptions() {
        // Thiết lập custom spinner adapter cho nguồn dịch
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

                // ✨ CẬP NHẬT LOGIC HIỂN THỊ NÚT CÀI ĐẶT ✨
                binding.languageCard.btnAiSettings.visibility = if (selectedSource == TranslationSource.AI) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Thiết lập toggle chọn chế độ dịch
        binding.languageCard.toggleTranslationMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btn_mode_simple -> viewModel.setTranslationMode(TranslationMode.SIMPLE)
                    R.id.btn_mode_advanced -> viewModel.setTranslationMode(TranslationMode.ADVANCED)
                }
            }
        }
    }

    // ✨ THÊM HÀM MỚI NÀY ✨
    private fun setupAiSettingsButton() {
        binding.languageCard.btnAiSettings.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_aiSettingsFragment)
        }
    }

    /**
     * Quan sát thay đổi trạng thái từ ViewModel
     */
    private fun observeLanguageChanges() {
        // Quan sát ngôn ngữ nguồn
        viewModel.sourceLanguage.observe(viewLifecycleOwner) { language ->
            binding.languageCard.btnSourceLanguage.text = language.nativeName

            // Gửi thông tin ngôn ngữ đến OverlayService
            updateServiceLanguages()
        }

        // Quan sát ngôn ngữ đích
        viewModel.targetLanguage.observe(viewLifecycleOwner) { language ->
            binding.languageCard.btnTargetLanguage.text = language.nativeName

            // Gửi thông tin ngôn ngữ đến OverlayService
            updateServiceLanguages()
        }

        // Quan sát nguồn dịch
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

        // Quan sát chế độ dịch
        viewModel.translationMode.observe(viewLifecycleOwner) { mode ->
            val buttonId = when (mode) {
                TranslationMode.SIMPLE -> R.id.btn_mode_simple
                TranslationMode.ADVANCED -> R.id.btn_mode_advanced
            }
            binding.languageCard.toggleTranslationMode.check(buttonId)
        }
    }

    /**
     * Tạo danh sách dữ liệu và thiết lập RecyclerView cho lưới chức năng
     */
    private fun setupFunctionGrid() {
        // 1. Tạo danh sách dữ liệu cho các chức năng
        val functions = listOf(
            FunctionItem(
                id = "GLOBAL",
                iconRes = R.drawable.ic_global,
                title = getString(R.string.function_global_translate),
                description = getString(R.string.function_global_translate_desc),
                isClickable = true
            ),
            FunctionItem(
                id = "AREA",
                iconRes = R.drawable.ic_crop,
                title = getString(R.string.function_area_translate),
                description = getString(R.string.function_area_translate_desc),
                isClickable = false
            ),
            FunctionItem(
                id = "AUTO_GLOBAL",
                iconRes = R.drawable.ic_auto_play,
                title = getString(R.string.function_auto_global_translate),
                description = getString(R.string.function_auto_global_translate_desc),
                isClickable = true
            ),
            FunctionItem(
                id = "AUTO_AREA",
                iconRes = R.drawable.ic_auto_play,
                title = getString(R.string.function_auto_area_translate),
                description = getString(R.string.function_auto_area_translate_desc),
                isClickable = true
            ),
            FunctionItem(
                id = "IMAGE",
                iconRes = R.drawable.ic_image,
                title = getString(R.string.function_image_translate),
                description = getString(R.string.function_image_translate_desc),
                isClickable = true  // Đã thay đổi từ false thành true
            ),
            FunctionItem(
                id = "COPY",
                iconRes = R.drawable.ic_copy,
                title = getString(R.string.function_copy_text),
                description = getString(R.string.function_copy_text_desc),
                isClickable = false
            )
        )

        // 2. Khởi tạo Adapter
        functionAdapter = FunctionGridAdapter { functionItem ->
            handleFunctionClick(functionItem)
        }

        // 3. Gán Adapter và dữ liệu cho RecyclerView
        binding.rvFunctions.adapter = functionAdapter
        functionAdapter.submitList(functions)
    }

    /**
     * Xử lý sự kiện click cho một chức năng
     */
    private fun handleFunctionClick(item: FunctionItem) {
        if (!item.isClickable) {
            Toast.makeText(
                requireContext(),
                "Chức năng '${item.title}' chưa được triển khai",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        when (item.id) {
            "GLOBAL", "AUTO_GLOBAL", "AUTO_AREA" -> {
                // Điều hướng đến FunctionSettingsFragment bằng cách sử dụng destination ID trực tiếp
                findNavController().navigate(
                    R.id.functionSettingsFragment,
                    bundleOf("functionType" to item.id)
                )
            }
            "IMAGE" -> {
                // Chức năng nhận dạng văn bản từ ảnh sẽ được triển khai sau
                Toast.makeText(
                    requireContext(),
                    "Chức năng '${item.title}' sẽ được triển khai sau",
                    Toast.LENGTH_SHORT
                ).show()
            }
            else -> {
                // Xử lý các chức năng có thể click khác nếu có
                Toast.makeText(
                    requireContext(),
                    "Chức năng '${item.title}' sẽ được triển khai sau",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Điều hướng đến màn hình chọn ngôn ngữ
     * @param isSourceLanguage true nếu đang chọn ngôn ngữ nguồn, false nếu chọn ngôn ngữ đích
     */
    private fun navigateToLanguageSelection(isSourceLanguage: Boolean) {
        findNavController().navigate(
            R.id.languageSelectionFragment,
            bundleOf("isSourceLanguage" to isSourceLanguage)
        )
    }

    private fun setupFab() {
        binding.fabToggle.setOnClickListener {
            if (!isRunning) {
                startAction()
            } else {
                pauseAction()
            }
        }

        // Update FAB icon based on service state
        updateFabIcon()
    }

    /**
     * Kiểm tra và yêu cầu quyền thông báo (cho Android 13+)
     */
    private fun checkNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return false
            }
        }
        return true
    }

    /**
     * Bắt đầu dịch vụ nút nổi
     */
    private fun startAction() {
        if (OverlayService.isRunning) {
            // Service đã chạy rồi
            return
        }

        // Kiểm tra quyền thông báo trước (chỉ áp dụng từ Android 13 trở lên)
        if (!checkNotificationPermission()) {
            return
        }

        // Kiểm tra quyền overlay
        if (!canDrawOverlays()) {
            requestOverlayPermission()
            return
        }

        // Yêu cầu quyền MediaProjection
        requestScreenCapturePermission()
    }

    /**
     * Dừng dịch vụ nút nổi
     */
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

    /**
     * Kiểm tra quyền hiển thị trên các ứng dụng khác
     */
    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(requireContext())
        } else {
            true
        }
    }

    /**
     * Yêu cầu quyền overlay
     */
    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${requireContext().packageName}")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    /**
     * Yêu cầu quyền MediaProjection (Screen Capture)
     */
    private fun requestScreenCapturePermission() {
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        screenCapturePermissionLauncher.launch(captureIntent)
    }

    /**
     * Khởi động OverlayService với MediaProjection data
     */
    private fun startOverlayService(resultCode: Int, data: Intent) {
        Log.d(TAG, "Starting OverlayService with resultCode: $resultCode")

        val serviceIntent = Intent(requireContext(), OverlayService::class.java).apply {
            action = OverlayService.ACTION_START_SERVICE
            putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
            // Truyền data Intent như một Parcelable
            putExtra(OverlayService.EXTRA_DATA, data)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(serviceIntent)
        } else {
            requireContext().startService(serviceIntent)
        }

        // Không cập nhật UI ngay lập tức, đợi broadcast từ service
        Toast.makeText(requireContext(), "Đang khởi động dịch vụ...", Toast.LENGTH_SHORT).show()
    }

    /**
     * Cập nhật icon và text của FAB
     */
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
        // Cập nhật trạng thái FAB khi quay lại fragment
        updateFabIcon()
    }

    /**
     * Hiển thị Bottom Sheet chọn ngôn ngữ
     */
    private fun showLanguageBottomSheet() {
        val bottomSheet = LanguageSelectionBottomSheet.newInstance()
        bottomSheet.show(childFragmentManager, LanguageSelectionBottomSheet.TAG)
    }

    /**
     * Gửi thông tin ngôn ngữ đến OverlayService
     */
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
        Log.d(TAG, "Sent language update to service: $sourceCode → $targetCode")
    }

    override fun onDestroyView() {
        // Hủy đăng ký BroadcastReceiver
        unregisterServiceStateReceiver()

        super.onDestroyView()
        binding.rvFunctions.adapter = null // Giải phóng adapter
        _binding = null
    }

    /**
     * Đăng ký BroadcastReceiver để lắng nghe trạng thái service
     */
    private fun registerServiceStateReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(OverlayService.BROADCAST_SERVICE_STARTED)
            addAction(OverlayService.BROADCAST_SERVICE_STOPPED)
            addAction(OverlayService.BROADCAST_SERVICE_ERROR)
            addAction(OverlayService.ACTION_SHOW_LANGUAGE_SHEET)
        }
        ContextCompat.registerReceiver(
            requireContext(),
            serviceStateReceiver,
            intentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED // Chỉ định rằng receiver này không nhận broadcast từ bên ngoài
        )
        Log.d(TAG, "Service state receiver registered")
    }

    /**
     * Hủy đăng ký BroadcastReceiver
     */
    private fun unregisterServiceStateReceiver() {
        try {
            requireContext().unregisterReceiver(serviceStateReceiver)
            Log.d(TAG, "Service state receiver unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering service state receiver", e)
        }
    }
}
