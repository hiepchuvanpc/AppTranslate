package com.example.apptranslate.service

import com.example.apptranslate.MainActivity
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.apptranslate.R
import com.example.apptranslate.data.SettingsManager
import com.example.apptranslate.data.TranslationManager
import com.example.apptranslate.ocr.OcrManager
import com.example.apptranslate.ocr.OcrResult
import com.example.apptranslate.ui.overlay.*
import com.example.apptranslate.ui.ImageTranslateActivity
import com.example.apptranslate.ui.ImageTranslationResult
import android.Manifest
import android.content.pm.PackageManager


import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.math.roundToInt
import androidx.core.view.doOnLayout
import com.example.apptranslate.viewmodel.LanguageViewModel
import com.example.apptranslate.viewmodel.LanguageViewModelFactory
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.res.Configuration

@SuppressLint("ViewConstructor")
class OverlayService : Service(), BubbleViewListener {

    //region Companion Object và Constants
    companion object {
        private const val TAG = "OverlayService"
        var isRunning = false
            private set

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "overlay_service_channel"

        const val ACTION_START_SERVICE = "START_SERVICE"
        const val ACTION_STOP_SERVICE = "STOP_SERVICE"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_DATA = "EXTRA_DATA"
        const val BROADCAST_SERVICE_STARTED = "com.example.apptranslate.SERVICE_STARTED"
        const val BROADCAST_SERVICE_STOPPED = "com.example.apptranslate.SERVICE_STOPPED"
        const val BROADCAST_SERVICE_ERROR = "com.example.apptranslate.SERVICE_ERROR"
        const val ACTION_SHOW_LANGUAGE_SHEET = "com.example.apptranslate.SHOW_LANGUAGE_SHEET"
        const val ACTION_UPDATE_LANGUAGES = "com.example.apptranslate.UPDATE_LANGUAGES"
        const val ACTION_LANGUAGES_UPDATED_FROM_SERVICE = "com.example.apptranslate.LANGUAGES_UPDATED"

        // New actions for image translate
        const val ACTION_SHOW_IMAGE_TRANSLATION_RESULTS = "SHOW_IMAGE_TRANSLATION_RESULTS"

        const val EXTRA_SOURCE_LANG = "SOURCE_LANG"
        const val EXTRA_TARGET_LANG = "TARGET_LANG"
        private const val OCR_TRANSLATION_DELIMITER = "\n\n"
    }
    //endregion

    //region Khai báo biến
    private lateinit var windowManager: WindowManager
    private lateinit var settingsManager: SettingsManager
    private val ocrManager by lazy { OcrManager.getInstance() }
    private lateinit var translationManager: TranslationManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var languageViewModel: LanguageViewModel
    private var languageSheetView: LanguageSheetView? = null
    private var regionSelectOverlay: RegionSelectionOverlay? = null
    private var regionResultOverlay: RegionResultOverlay? = null
    private var copyTextOverlay: CopyTextOverlay? = null
    private var imageTranslationOverlay: GlobalTranslationOverlay? = null
    private var currentOrientation = Configuration.ORIENTATION_UNDEFINED
    private var currentStatusBarHeight = 0
    private val orientationChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_CONFIGURATION_CHANGED) {
                handleOrientationChange()
            }
        }
    }

    private val imageTranslationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SHOW_IMAGE_TRANSLATION_RESULTS) {
                val results = intent.getParcelableArrayListExtra<ImageTranslationResult>("TRANSLATED_RESULTS")
                results?.let { showImageTranslationResults(it) }
            }
        }
    }
    private val cameraPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Received camera permission broadcast: ${intent?.action}")
            if (intent?.action == "com.example.apptranslate.CAMERA_PERMISSION_GRANTED") {
                val granted = intent.getBooleanExtra("PERMISSION_GRANTED", false)
                Log.d(TAG, "Camera permission granted: $granted")
                if (granted) {
                    startImageTranslateDirectly()
                } else {
                    Toast.makeText(this@OverlayService, "Cần cấp quyền camera để sử dụng chức năng dịch ảnh", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Views và UI
    private var floatingBubbleView: FloatingBubbleView? = null
    private var globalOverlay: GlobalTranslationOverlay? = null

    // Chế độ Kính lúp
    private var magnifierJob: Job? = null
    private var lastHoveredBlock: OcrResult.Block? = null
    private var magnifierCache: List<TranslatedBlock> = emptyList()
    private val magnifierResultViews = mutableListOf<TranslationResultView>()
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var magnifierLensView: ImageView? = null
    private var lastCapturedBitmapWidth: Int = 0
    private var lastCapturedBitmapHeight: Int = 0

    private val LENS_SIZE by lazy { resources.getDimensionPixelSize(R.dimen.magnifier_lens_size) }
    private val themedContext: Context by lazy {
        ContextThemeWrapper(this, R.style.Theme_AppTranslate)
    }


    // Quản lý trạng thái
    private sealed class ServiceState {
        object IDLE : ServiceState()
        object MOVING_BUBBLE : ServiceState()
        object PANEL_OPEN : ServiceState()
        object MAGNIFIER_ACTIVE : ServiceState()
        object GLOBAL_TRANSLATE_ACTIVE : ServiceState()
        object LANGUAGE_SELECT_OPEN : ServiceState()
        object REGION_SELECT_ACTIVE : ServiceState()
        object REGION_RESULT_ACTIVE : ServiceState()
        object COPY_TEXT_ACTIVE : ServiceState()
        object IMAGE_TRANSLATION_ACTIVE : ServiceState()
    }
    private var currentState: ServiceState = ServiceState.IDLE

    private data class TranslatedBlock(val original: OcrResult.Block, val translated: String)

    // Data class for copy text results
    private data class CopyTextResult(val text: String, val boundingBox: Rect)
    //endregion

    //region Vòng đời Service và Listeners
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        settingsManager = SettingsManager.getInstance(this)
        translationManager = TranslationManager(this)
        createNotificationChannel()

        // Đăng ký receiver cho việc xoay màn hình
        registerReceiver(orientationChangeReceiver, IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED))

        // Đăng ký receiver cho image translation results
        val cameraPermissionFilter = IntentFilter("com.example.apptranslate.CAMERA_PERMISSION_GRANTED")
        registerReceiver(cameraPermissionReceiver, cameraPermissionFilter)
        currentOrientation = resources.configuration.orientation
        updateStatusBarHeight()

        startForeground(NOTIFICATION_ID, createBasicNotification("Dịch vụ đang chạy"))
        languageViewModel = LanguageViewModelFactory(application).create(LanguageViewModel::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVICE -> handleStartService(intent)
            ACTION_STOP_SERVICE -> stopServiceCleanup()
            ACTION_UPDATE_LANGUAGES -> handleUpdateLanguages(intent)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(orientationChangeReceiver)
            unregisterReceiver(cameraPermissionReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister receivers", e)
        }
        stopServiceCleanup()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onBubbleTapped() {
        when (currentState) {
            is ServiceState.PANEL_OPEN -> setState(ServiceState.IDLE)
            is ServiceState.IDLE -> setState(ServiceState.PANEL_OPEN)
            else -> {}
        }
    }

    override fun onBubbleLongPressed() {
        if (currentState is ServiceState.IDLE) {
            setState(ServiceState.MAGNIFIER_ACTIVE)
        }
    }

    override fun onDragStarted() {
        if (currentState is ServiceState.IDLE) {
            setState(ServiceState.MAGNIFIER_ACTIVE)
        }
    }

    override fun onDragFinished() {
        if (currentState is ServiceState.MAGNIFIER_ACTIVE || currentState is ServiceState.MOVING_BUBBLE) {
            setState(ServiceState.IDLE)
        }
    }

    override fun onRegionTranslateClicked() {
        setState(ServiceState.REGION_SELECT_ACTIVE)
    }

    override fun onFunctionClicked(functionId: String) {
        when (functionId) {
            "GLOBAL" -> setState(ServiceState.GLOBAL_TRANSLATE_ACTIVE)
            "AREA" -> setState(ServiceState.REGION_SELECT_ACTIVE)
            "IMAGE" -> startImageTranslate()
            "COPY" -> startCopyText()
            else -> {
                setState(ServiceState.IDLE)
                Toast.makeText(this, "Chức năng '$functionId' chưa được triển khai", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onLanguageSelectClicked() {
        setState(ServiceState.LANGUAGE_SELECT_OPEN)
    }

    override fun onHomeClicked() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(intent)
        setState(ServiceState.IDLE)
    }

    override fun onMoveClicked() {
        setState(ServiceState.MOVING_BUBBLE)
    }

    override fun onDrag(x: Int, y: Int) {
        // Chỉ cập nhật kính lúp khi đang ở chế độ Magnifier
        if (currentState is ServiceState.MAGNIFIER_ACTIVE) {
            val lensDetails = calculateLensDetails(x, y)
            updateMagnifierLensPosition(lensDetails)
            findAndShowMagnifierResultAt(lensDetails.scanCenter)
        }
    }
    //endregion

    //region Quản lý Trạng thái (State Machine)
    private fun setState(newState: ServiceState) {
        if (currentState::class == newState::class) return
        Log.d(TAG, "State changing from ${currentState.javaClass.simpleName} to ${newState.javaClass.simpleName}")

        // Bước 1: Dọn dẹp trạng thái hiện tại
        when (currentState) {
            is ServiceState.PANEL_OPEN -> floatingBubbleView?.closePanel()
            is ServiceState.MAGNIFIER_ACTIVE -> stopMagnifierMode()
            is ServiceState.GLOBAL_TRANSLATE_ACTIVE -> removeGlobalOverlay()
            is ServiceState.LANGUAGE_SELECT_OPEN -> removeLanguageSheet()
            is ServiceState.REGION_SELECT_ACTIVE -> removeRegionSelectOverlay()
            is ServiceState.REGION_RESULT_ACTIVE -> removeRegionResultOverlay()
            is ServiceState.COPY_TEXT_ACTIVE -> removeCopyTextOverlay()
            is ServiceState.IMAGE_TRANSLATION_ACTIVE -> removeImageTranslationOverlay()
            else -> {}
        }

        currentState = newState

        // Bước 2: Thiết lập cho trạng thái mới
        // Ẩn/hiện bubble một cách nhất quán
        floatingBubbleView?.visibility = when (newState) {
            is ServiceState.GLOBAL_TRANSLATE_ACTIVE,
            is ServiceState.LANGUAGE_SELECT_OPEN,
            is ServiceState.REGION_SELECT_ACTIVE,
            is ServiceState.REGION_RESULT_ACTIVE,
            is ServiceState.COPY_TEXT_ACTIVE,
            is ServiceState.IMAGE_TRANSLATION_ACTIVE -> View.GONE
            else -> View.VISIBLE
        }

        // Kích hoạt logic cho trạng thái mới
        when (newState) {
            is ServiceState.IDLE -> {
                floatingBubbleView?.updateBubbleAppearance(BubbleAppearance.NORMAL)
            }
            is ServiceState.PANEL_OPEN -> {
                floatingBubbleView?.openPanel()
            }
            is ServiceState.MAGNIFIER_ACTIVE -> {
                floatingBubbleView?.updateBubbleAppearance(BubbleAppearance.MAGNIFIER)
                startMagnifierMode()
            }
            is ServiceState.GLOBAL_TRANSLATE_ACTIVE -> {
                performGlobalTranslate()
            }
            is ServiceState.REGION_SELECT_ACTIVE -> {
                showRegionSelectOverlay()
            }
            is ServiceState.REGION_RESULT_ACTIVE -> {
                // Trạng thái hiển thị kết quả vùng đã được xử lý
            }
            is ServiceState.COPY_TEXT_ACTIVE -> {
                // Trạng thái hiển thị kết quả copy text đã được xử lý
            }
            is ServiceState.IMAGE_TRANSLATION_ACTIVE -> {
                // Trạng thái hiển thị kết quả dịch ảnh đã được xử lý
            }
            is ServiceState.LANGUAGE_SELECT_OPEN -> {
                showLanguageSheet()
            }
            is ServiceState.MOVING_BUBBLE -> {
                floatingBubbleView?.updateBubbleAppearance(BubbleAppearance.MOVING)
                Toast.makeText(this, "Kéo thả để di chuyển", Toast.LENGTH_SHORT).show()
            }
        }
    }
    //endregion

    //region Language Sheet Management
    private fun showLanguageSheet() {
        if (languageSheetView != null) return

        languageSheetView = LanguageSheetView(themedContext, languageViewModel) { languageChanged ->
            setState(ServiceState.IDLE)
            if (languageChanged) {
                sendBroadcast(Intent(ACTION_LANGUAGES_UPDATED_FROM_SERVICE))
                Log.d(TAG, "Sent language update broadcast to the app.")
            }
        }

        val params = createOverlayLayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        ).apply {
            dimAmount = 0.5f
            flags = flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
            flags = flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        }

        windowManager.addView(languageSheetView, params)
    }

    private fun removeLanguageSheet() {
        languageSheetView?.let {
            if (it.isAttachedToWindow) {
                windowManager.removeView(it)
            }
        }
        languageSheetView = null
    }
    //endregion

    // Tiền xử lý text trước khi dịch
    private fun preprocessText(text: String): String {
        var result = text.replace("([.,!?])([\\p{L}\\d])".toRegex(), "$1 $2")
        result = result.replace("([a-z])([A-Z])".toRegex(), "$1 $2")
        result = result.replace("[^\\p{L}\\p{N}\\s.,!?]".toRegex(), "")
        result = result.replace("\\s+".toRegex(), " ").trim()
        return result
    }

    //region Logic chung cho OCR và Dịch thuật
    private suspend fun performOcrAndTranslation(bitmap: Bitmap): Result<List<TranslatedBlock>> = runCatching {
        if (bitmap.isRecycled) return@runCatching emptyList()

        val sourceLang = settingsManager.getSourceLanguageCode() ?: "vi"
        val ocrResult = withContext(Dispatchers.IO) {
            ocrManager.recognizeTextFromBitmap(bitmap, sourceLang)
        }

        val blocksToTranslate = ocrResult.textBlocks.filter { it.text.isNotBlank() && it.boundingBox != null }
        if (blocksToTranslate.isEmpty()) return@runCatching emptyList()

        // Tiền xử lý từng block trước khi dịch
        val preprocessedBlocks = blocksToTranslate.map { it.copy(text = preprocessText(it.text)) }
        val combinedText = preprocessedBlocks.joinToString(separator = OCR_TRANSLATION_DELIMITER) { it.text }
        val targetLang = settingsManager.getTargetLanguageCode() ?: "en"
        val transSource = settingsManager.getTranslationSource()

        val translationResult = withContext(Dispatchers.IO) {
            translationManager.translate(combinedText, sourceLang, targetLang, transSource)
        }

        val translatedText = translationResult.getOrThrow()
        val translatedSegments = translatedText.split(OCR_TRANSLATION_DELIMITER)

        if (preprocessedBlocks.size == translatedSegments.size) {
            preprocessedBlocks.zip(translatedSegments).map { (original, translated) ->
                TranslatedBlock(original, translated)
            }
        } else {
            Log.w(TAG, "Mismatch between OCR blocks (${preprocessedBlocks.size}) and translated segments (${translatedSegments.size})")
            emptyList()
        }
    }
    //endregion

    //region Magnifier Mode
    private fun startMagnifierMode() {
        magnifierLensView?.visibility = View.VISIBLE
        magnifierJob = serviceScope.launch {
            val bubble = floatingBubbleView ?: return@launch
            withContext(Dispatchers.Main) {
                bubble.alpha = 0.0f
            }

            val screenBitmap = try {
                captureScreen()
            } catch (e: Exception) {
                Log.e(TAG, "Capture failed: ${e.stackTraceToString()}")
                null
            }

            withContext(Dispatchers.Main) {
                bubble.alpha = 1.0f
            }

            if (screenBitmap == null) {
                Toast.makeText(this@OverlayService, "Không thể chụp màn hình", Toast.LENGTH_SHORT).show()
                setState(ServiceState.IDLE)
                return@launch
            }

            performOcrAndTranslation(screenBitmap).onSuccess { results ->
                magnifierCache = results
                if (results.isEmpty()) {
                    Toast.makeText(this@OverlayService, "Không tìm thấy văn bản", Toast.LENGTH_SHORT).show()
                    setState(ServiceState.IDLE)
                }
            }.onFailure { e ->
                Log.e(TAG, "Error preparing magnifier data: ${e.stackTraceToString()}")
                Toast.makeText(this@OverlayService, "Lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
                setState(ServiceState.IDLE)
            }
            screenBitmap.recycle()
        }
    }

    private fun stopMagnifierMode() {
        magnifierJob?.cancel()
        magnifierJob = null
        magnifierCache = emptyList()
        lastHoveredBlock = null
        removeAllMagnifierResults()
        removeMagnifierLens()
    }

    private fun findAndShowMagnifierResultAt(scanCenter: Point) {
        val lensRadiusRatio = 6.5f / 24f
        val lensRadius = (LENS_SIZE * lensRadiusRatio).toInt()

        val targetCacheItem = magnifierCache.find {
            val screenRect = mapRectFromBitmapToScreen(it.original.boundingBox!!)
            checkCircleRectIntersection(scanCenter.x, scanCenter.y, lensRadius, screenRect)
        }

        if (targetCacheItem?.original == lastHoveredBlock) return

        lastHoveredBlock = targetCacheItem?.original
        removeAllMagnifierResults()

        targetCacheItem?.let {
            val screenRect = mapRectFromBitmapToScreen(it.original.boundingBox!!)
            val resultView = showSingleMagnifierResult(screenRect)
            resultView.updateText(it.translated)
            magnifierResultViews.add(resultView)
        }
    }
    //endregion

    //region Global Translation
    private fun performGlobalTranslate() = serviceScope.launch {
        // Chờ một chút để các animation hoàn thành
        delay(100L)

        // Chụp ảnh màn hình
        val fullScreenBitmap = captureScreenWithBubbleHidden()
        if (fullScreenBitmap == null) {
            Toast.makeText(this@OverlayService, "Không thể chụp màn hình", Toast.LENGTH_SHORT).show()
            setState(ServiceState.IDLE)
            return@launch
        }

        // Cắt bỏ thanh trạng thái
        val statusBarHeight = getStatusBarHeight()
        val croppedBitmap = if (statusBarHeight > 0) {
            try {
                Bitmap.createBitmap(
                    fullScreenBitmap, 0, statusBarHeight,
                    fullScreenBitmap.width, fullScreenBitmap.height - statusBarHeight
                )
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khi cắt ảnh chụp màn hình", e)
                fullScreenBitmap.recycle()
                setState(ServiceState.IDLE)
                return@launch
            }
        } else {
            // Status bar bị ẩn, sử dụng ảnh gốc
            fullScreenBitmap
        }

        if (statusBarHeight > 0) {
            fullScreenBitmap.recycle()
        }

        // Hiển thị overlay
        val overlay = showGlobalOverlay() ?: run {
            croppedBitmap.recycle()
            setState(ServiceState.IDLE)
            return@launch
        }
        overlay.showLoading()

        // Xử lý ảnh và hiển thị kết quả
        overlay.doOnLayout { view ->
            serviceScope.launch(Dispatchers.IO) {
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                val windowOffsetY = location[1]

                performOcrAndTranslation(croppedBitmap).onSuccess { results ->
                    withContext(Dispatchers.Main) {
                        overlay.hideLoading()
                        if (results.isEmpty()) {
                            Toast.makeText(this@OverlayService, "Không tìm thấy văn bản", Toast.LENGTH_SHORT).show()
                        } else {
                            results.forEach { block ->
                                val screenRect = mapRectFromBitmapToScreen(block.original.boundingBox!!)
                                val absoluteTargetY = screenRect.top + statusBarHeight
                                val finalTopMargin = absoluteTargetY - windowOffsetY

                                displaySingleGlobalResult(screenRect, finalTopMargin, block.translated, overlay)
                            }
                        }
                    }
                }.onFailure { e ->
                    withContext(Dispatchers.Main) {
                        overlay.hideLoading()
                        Log.e(TAG, "Lỗi trong quá trình dịch toàn cầu", e)
                        Toast.makeText(this@OverlayService, "Lỗi: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }

                croppedBitmap.recycle()
            }
        }
    }
    //endregion

    //region Service Management & System Interactions
    private fun handleStartService(intent: Intent) {
        if (!hasOverlayPermission()) {
            stopSelf()
            return
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }

        if (resultCode == Activity.RESULT_OK && data != null) {
            initializeMediaProjection(resultCode, data)
            showFloatingBubble()
            isRunning = true
            sendBroadcast(Intent(BROADCAST_SERVICE_STARTED).setPackage(packageName))
        } else {
            sendBroadcast(Intent(BROADCAST_SERVICE_ERROR).setPackage(packageName))
            stopSelf()
        }
    }

    private fun stopServiceCleanup() {
        if (!isRunning) return
        isRunning = false
        try {
            serviceScope.cancel()

            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()

            floatingBubbleView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
            globalOverlay?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
            magnifierLensView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
            magnifierLensView = null
            removeAllMagnifierResults()

        } catch (e: Exception) {
            Log.e(TAG, "Error during service stop", e)
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            sendBroadcast(Intent(BROADCAST_SERVICE_STOPPED).setPackage(packageName))
        }
    }

    private fun handleUpdateLanguages(intent: Intent) {
        val sourceCode = intent.getStringExtra(EXTRA_SOURCE_LANG) ?: "vi"
        val targetCode = intent.getStringExtra(EXTRA_TARGET_LANG) ?: "en"
        val displayText = "${sourceCode.uppercase()} → ${targetCode.uppercase()}"
        floatingBubbleView?.updateLanguageDisplay(displayText)
    }

    private fun initializeMediaProjection(resultCode: Int, data: Intent) {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)?.apply {
            registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection stopped by system.")
                    stopServiceCleanup()
                }
            }, handler)
        }

        setupScreenCaptureComponents()
    }

    private fun setupScreenCaptureComponents() {
        if (mediaProjection == null) {
            Log.e(TAG, "Cannot setup screen capture components, MediaProjection is null.")
            return
        }

        // Đóng components cũ nếu có
        virtualDisplay?.release()
        imageReader?.close()

        val realSize = getRealScreenSizePx()
        val screenWidth = realSize.x
        val screenHeight = realSize.y
        val screenDensity = resources.displayMetrics.densityDpi

        if (screenWidth <= 0 || screenHeight <= 0) {
            Log.e(TAG, "setupScreenCaptureComponents failed: Invalid screen dimensions ($screenWidth, $screenHeight).")
            return
        }

        Log.d(TAG, "Setting up screen capture: ${screenWidth}x${screenHeight}, density: $screenDensity")

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture", screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, handler
        )
    }

    private suspend fun captureScreen(): Bitmap? = suspendCancellableCoroutine { continuation ->
        val reader = imageReader
        if (mediaProjection == null || reader == null || !isRunning) {
            Log.w(TAG, "captureScreen failed: components not ready or service not running.")
            if (continuation.isActive) continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        val image: android.media.Image? = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire image", e)
            null
        }

        if (image == null) {
            Log.w(TAG, "acquireLatestImage returned null.")
            if (continuation.isActive) continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val tempBitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            tempBitmap.copyPixelsFromBuffer(buffer)

            val finalBitmap = Bitmap.createBitmap(tempBitmap, 0, 0, image.width, image.height)
            tempBitmap.recycle()

            lastCapturedBitmapWidth = finalBitmap.width
            lastCapturedBitmapHeight = finalBitmap.height

            if (continuation.isActive) {
                continuation.resume(finalBitmap)
            } else {
                finalBitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing screen capture image", e)
            if (continuation.isActive) continuation.resume(null)
        } finally {
            image.close()
        }
    }

    private suspend fun captureScreenWithBubbleHidden(): Bitmap? {
        val bubble = floatingBubbleView ?: return null
        return withContext(Dispatchers.Main) {
            try {
                bubble.visibility = View.INVISIBLE
                delay(16)
                captureScreen()
            } finally {
                bubble.visibility = View.VISIBLE
            }
        }
    }

    private fun mapRectFromBitmapToScreen(src: Rect): Rect {
        val real = getRealScreenSizePx()
        val screenW = real.x.toFloat()
        val screenH = real.y.toFloat()

        val bmpW = if (lastCapturedBitmapWidth > 0) lastCapturedBitmapWidth.toFloat() else screenW
        val bmpH = if (lastCapturedBitmapHeight > 0) lastCapturedBitmapHeight.toFloat() else screenH

        val scaleX = screenW / bmpW
        val scaleY = screenH / bmpH

        return Rect(
            (src.left   * scaleX).roundToInt(),
            (src.top    * scaleY).roundToInt(),
            (src.right  * scaleX).roundToInt(),
            (src.bottom * scaleY).roundToInt()
        )
    }
    //endregion

    //region UI & View Management
    private data class LensDetails(val iconTopLeft: Point, val scanCenter: Point)

    private fun calculateLensDetails(handleX: Int, handleY: Int): LensDetails {
        val handleSize = resources.getDimensionPixelSize(R.dimen.bubble_size)
        val handleCenterX = handleX + handleSize / 2
        val handleCenterY = handleY + handleSize / 2

        val offset = (LENS_SIZE * 0.4f).toInt()
        val diagonal = (offset / Math.sqrt(2.0)).toInt()

        val lensCenterX = handleCenterX - diagonal
        val lensCenterY = handleCenterY - diagonal

        val iconTopLeftX = lensCenterX - LENS_SIZE / 2
        val iconTopLeftY = lensCenterY - LENS_SIZE / 2

        return LensDetails(
            iconTopLeft = Point(iconTopLeftX, iconTopLeftY),
            scanCenter = Point(lensCenterX, lensCenterY)
        )
    }

    private fun createOverlayLayoutParams(
        width: Int,
        height: Int,
        flags: Int,
        gravity: Int = Gravity.TOP or Gravity.START
    ): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        return WindowManager.LayoutParams(width, height, type, flags, PixelFormat.TRANSLUCENT).apply {
            this.gravity = gravity
        }
    }

    private fun showFloatingBubble() {
        if (floatingBubbleView != null) return

        floatingBubbleView = FloatingBubbleView(themedContext, serviceScope).apply {
            listener = this@OverlayService
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        val params = createOverlayLayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            flags
        ).apply {
            y = 200
        }
        floatingBubbleView?.setViewLayoutParams(params)
        try {
            windowManager.addView(floatingBubbleView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add bubble view", e)
        }

        setupMagnifierLens()
    }

    private fun showGlobalOverlay(): GlobalTranslationOverlay? {
        if (globalOverlay != null) return globalOverlay

        globalOverlay = GlobalTranslationOverlay(themedContext, windowManager).apply {
            onDismiss = {
                globalOverlay = null
                setState(ServiceState.IDLE)
            }
        }
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val params = createOverlayLayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            flags
        )
        windowManager.addView(globalOverlay, params)
        return globalOverlay
    }

    private fun removeGlobalOverlay() {
        globalOverlay?.let {
            if (it.isAttachedToWindow) windowManager.removeView(it)
        }
        globalOverlay = null
    }

    private fun showSingleMagnifierResult(position: Rect): TranslationResultView {
        val resultView = TranslationResultView(this)
        val paddingDp = 3f
        val paddingPx = (paddingDp * resources.displayMetrics.density).toInt()
        val viewWidth = position.width() + (paddingPx * 2)
        val viewHeight = position.height() + (paddingPx * 2)

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val params = createOverlayLayoutParams(viewWidth, viewHeight, flags).apply {
            x = position.left - paddingPx
            y = position.top - paddingPx
        }
        windowManager.addView(resultView, params)
        return resultView
    }

    private fun removeAllMagnifierResults() {
        magnifierResultViews.forEach { if (it.isAttachedToWindow) windowManager.removeView(it) }
        magnifierResultViews.clear()
    }

    private fun displaySingleGlobalResult(
        screenRect: Rect,
        finalTopMargin: Int,
        text: String,
        overlay: GlobalTranslationOverlay?
    ) {
        val resultView = TranslationResultView(this).apply { updateText(text) }
        val paddingPx = (3f * resources.displayMetrics.density).toInt()

        val params = FrameLayout.LayoutParams(
            screenRect.width() + (paddingPx * 2),
            screenRect.height() + (paddingPx * 2)
        ).apply {
            leftMargin = screenRect.left - paddingPx
            topMargin = finalTopMargin - paddingPx
        }
        overlay?.addResultView(resultView, params)
    }

    private fun getRealScreenSizePx(): Point {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.currentWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            @Suppress("DEPRECATION")
            Point().also { windowManager.defaultDisplay.getRealSize(it) }
        }
    }

    private fun setupMagnifierLens() {
        if (magnifierLensView != null) return

        magnifierLensView = ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            visibility = View.GONE
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val params = createOverlayLayoutParams(LENS_SIZE, LENS_SIZE, flags)
        windowManager.addView(magnifierLensView, params)
    }

    private fun updateMagnifierLensPosition(lensDetails: LensDetails) {
        magnifierLensView?.let { lens ->
            lens.visibility = View.VISIBLE
            val params = lens.layoutParams as? WindowManager.LayoutParams ?: return
            if (params.x != lensDetails.iconTopLeft.x || params.y != lensDetails.iconTopLeft.y) {
                params.x = lensDetails.iconTopLeft.x
                params.y = lensDetails.iconTopLeft.y
                try {
                    windowManager.updateViewLayout(lens, params)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update lens position", e)
                }
            }
        }
    }

    private fun removeMagnifierLens() {
        magnifierLensView?.visibility = View.GONE
    }
    //endregion

    //region Region Selection and Translation
    private fun showRegionSelectOverlay() {
        if (regionSelectOverlay != null) return

        regionSelectOverlay = RegionSelectionOverlay(themedContext,
            onRegionSelected = { rect ->
                performRegionTranslation(rect)
            },
            onDismiss = {
                setState(ServiceState.IDLE)
            }
        )

        val params = createOverlayLayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            0
        )
        windowManager.addView(regionSelectOverlay, params)
    }

    private fun removeRegionSelectOverlay() {
        regionSelectOverlay?.let { overlay ->
            if (overlay.isAttachedToWindow) {
                windowManager.removeView(overlay)
            }
        }
        regionSelectOverlay = null
    }

    private fun showRegionResultOverlay(region: Rect): RegionResultOverlay? {
        if (regionResultOverlay != null) return regionResultOverlay

        regionResultOverlay = RegionResultOverlay(themedContext, region) {
            setState(ServiceState.IDLE)
        }

        val params = createOverlayLayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            0
        )
        windowManager.addView(regionResultOverlay, params)
        return regionResultOverlay
    }

    private fun removeRegionResultOverlay() {
        regionResultOverlay?.let { overlay ->
            if (overlay.isAttachedToWindow) {
                windowManager.removeView(overlay)
            }
        }
        regionResultOverlay = null
    }

    private fun performRegionTranslation(region: Rect) = serviceScope.launch {
        removeRegionSelectOverlay()
        delay(100L)

        val startTime = System.currentTimeMillis()

        // Chụp toàn màn hình và cắt bỏ status bar (giống như chế độ toàn cầu)
        val fullScreenBitmap = captureScreenWithBubbleHidden()
        if (fullScreenBitmap == null) {
            Toast.makeText(this@OverlayService, "Không thể chụp màn hình", Toast.LENGTH_SHORT).show()
            setState(ServiceState.IDLE)
            return@launch
        }

        // Cắt bỏ status bar từ ảnh toàn màn hình (giống performGlobalTranslate)
        val statusBarHeight = getStatusBarHeight()
        val croppedFullScreenBitmap = if (statusBarHeight > 0) {
            try {
                Bitmap.createBitmap(
                    fullScreenBitmap, 0, statusBarHeight,
                    fullScreenBitmap.width, fullScreenBitmap.height - statusBarHeight
                )
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khi cắt status bar", e)
                fullScreenBitmap.recycle()
                setState(ServiceState.IDLE)
                return@launch
            }
        } else {
            // Status bar bị ẩn, sử dụng ảnh gốc
            fullScreenBitmap
        }

        if (statusBarHeight > 0) {
            fullScreenBitmap.recycle()
        }

        // Crop vùng được chọn từ ảnh đã bỏ status bar
        val regionBitmap = try {
            cropRegionFromCroppedFullScreen(croppedFullScreenBitmap, region, statusBarHeight)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to crop region", e)
            croppedFullScreenBitmap.recycle()
            Toast.makeText(this@OverlayService, "Không thể cắt vùng được chọn", Toast.LENGTH_SHORT).show()
            setState(ServiceState.IDLE)
            return@launch
        }

        croppedFullScreenBitmap.recycle()
        val captureTime = System.currentTimeMillis() - startTime

        if (regionBitmap == null) {
            Toast.makeText(this@OverlayService, "Không thể tạo ảnh vùng", Toast.LENGTH_SHORT).show()
            setState(ServiceState.IDLE)
            return@launch
        }

        Log.d(TAG, "Region crop completed in ${captureTime}ms, size: ${regionBitmap.width}x${regionBitmap.height}")

        val resultOverlay = showRegionResultOverlay(region) ?: run {
            regionBitmap.recycle()
            setState(ServiceState.IDLE)
            return@launch
        }
        resultOverlay.showLoading()
        setState(ServiceState.REGION_RESULT_ACTIVE)

        resultOverlay.doOnLayout { view ->
            serviceScope.launch(Dispatchers.IO) {
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                val windowOffsetY = location[1]

                val ocrStartTime = System.currentTimeMillis()
                performOcrAndTranslation(regionBitmap).onSuccess { results ->
                    val totalTime = System.currentTimeMillis() - startTime

                    withContext(Dispatchers.Main) {
                        resultOverlay.hideLoading()
                        if (results.isEmpty()) {
                            Toast.makeText(this@OverlayService, "Không tìm thấy văn bản", Toast.LENGTH_SHORT).show()
                            delay(1500)
                            setState(ServiceState.IDLE)
                        } else {
                            results.forEach { block ->
                                // Chuyển tọa độ từ bitmap vùng về màn hình (như chế độ toàn cầu)
                                val screenRect = mapRegionBitmapRectToScreen(block.original.boundingBox!!, region, statusBarHeight)
                                val absoluteTargetY = screenRect.top + statusBarHeight
                                val finalTopMargin = absoluteTargetY - windowOffsetY

                                resultOverlay.addTranslationResult(
                                    Rect(screenRect.left, finalTopMargin, screenRect.right, finalTopMargin + screenRect.height()),
                                    block.translated
                                )
                            }

                            Log.d(TAG, "Region translation completed: total_time=${totalTime}ms, " +
                                "ocr_time=${System.currentTimeMillis() - ocrStartTime}ms, " +
                                "results_count=${results.size}")
                        }
                    }
                }.onFailure { e ->
                    withContext(Dispatchers.Main) {
                        resultOverlay.hideLoading()
                        Log.e(TAG, "Lỗi khi dịch vùng", e)
                        Toast.makeText(this@OverlayService, "Lỗi: ${e.message}", Toast.LENGTH_LONG).show()
                        setState(ServiceState.IDLE)
                    }
                }

                regionBitmap.recycle()
            }
        }
    }

    private fun cropRegionFromFullScreen(fullScreenBitmap: Bitmap, region: Rect): Bitmap? {
        val statusBarHeight = getStatusBarHeight()

        // Điều chỉnh tọa độ vùng cho ảnh toàn màn hình (bao gồm status bar)
        val adjustedRegion = Rect(
            region.left,
            region.top - statusBarHeight,
            region.right,
            region.bottom - statusBarHeight
        )

        // Kiểm tra bounds
        val maxWidth = fullScreenBitmap.width
        val maxHeight = fullScreenBitmap.height

        val clampedRegion = Rect(
            Math.max(0, adjustedRegion.left),
            Math.max(0, adjustedRegion.top),
            Math.min(maxWidth, adjustedRegion.right),
            Math.min(maxHeight, adjustedRegion.bottom)
        )

        if (clampedRegion.width() <= 0 || clampedRegion.height() <= 0) {
            Log.e(TAG, "Invalid region after clamping: $clampedRegion")
            return null
        }

        return try {
            Bitmap.createBitmap(
                fullScreenBitmap,
                clampedRegion.left,
                clampedRegion.top,
                clampedRegion.width(),
                clampedRegion.height()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create cropped bitmap", e)
            null
        }
    }
    private fun cropRegionFromCroppedFullScreen(croppedFullScreenBitmap: Bitmap, region: Rect, statusBarHeight: Int): Bitmap? {
        // Điều chỉnh tọa độ vùng cho ảnh đã bỏ status bar (nếu có)
        val adjustedRegion = if (statusBarHeight > 0) {
            Rect(
                region.left,
                region.top - statusBarHeight,  // Trừ status bar vì ảnh đã bỏ status bar
                region.right,
                region.bottom - statusBarHeight
            )
        } else {
            // Status bar bị ẩn, sử dụng tọa độ gốc
            Rect(region)
        }

        // Kiểm tra bounds
        val maxWidth = croppedFullScreenBitmap.width
        val maxHeight = croppedFullScreenBitmap.height

        val clampedRegion = Rect(
            Math.max(0, adjustedRegion.left),
            Math.max(0, adjustedRegion.top),
            Math.min(maxWidth, adjustedRegion.right),
            Math.min(maxHeight, adjustedRegion.bottom)
        )

        if (clampedRegion.width() <= 0 || clampedRegion.height() <= 0) {
            Log.e(TAG, "Invalid region after clamping: $clampedRegion")
            return null
        }

        return try {
            Bitmap.createBitmap(
                croppedFullScreenBitmap,
                clampedRegion.left,
                clampedRegion.top,
                clampedRegion.width(),
                clampedRegion.height()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create cropped bitmap", e)
            null
        }
    }

    private fun mapRegionBitmapRectToScreen(ocrRect: Rect, originalRegion: Rect, statusBarHeight: Int): Rect {
        // Tọa độ trong bitmap vùng + offset của vùng trên màn hình
        return if (statusBarHeight > 0) {
            Rect(
                originalRegion.left + ocrRect.left,
                (originalRegion.top - statusBarHeight) + ocrRect.top,  // Trừ status bar
                originalRegion.left + ocrRect.right,
                (originalRegion.top - statusBarHeight) + ocrRect.bottom
            )
        } else {
            Rect(
                originalRegion.left + ocrRect.left,
                originalRegion.top + ocrRect.top,  // Không trừ status bar
                originalRegion.left + ocrRect.right,
                originalRegion.top + ocrRect.bottom
            )
        }
    }
    //endregion

    //region Image Translation and Copy Text Functions

    private fun startCopyText() {
        setState(ServiceState.COPY_TEXT_ACTIVE)
        performCopyTextCapture()
    }

    private fun performCopyTextCapture() = serviceScope.launch {
        // Chờ một chút để animation hoàn thành
        delay(100L)

        // Chụp ảnh màn hình
        val fullScreenBitmap = captureScreenWithBubbleHidden()
        if (fullScreenBitmap == null) {
            Toast.makeText(this@OverlayService, "Không thể chụp màn hình", Toast.LENGTH_SHORT).show()
            setState(ServiceState.IDLE)
            return@launch
        }

        // Cắt bỏ thanh trạng thái
        val statusBarHeight = getStatusBarHeight()
        val croppedBitmap = if (statusBarHeight > 0) {
            try {
                Bitmap.createBitmap(
                    fullScreenBitmap, 0, statusBarHeight,
                    fullScreenBitmap.width, fullScreenBitmap.height - statusBarHeight
                )
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khi cắt ảnh chụp màn hình", e)
                fullScreenBitmap.recycle()
                setState(ServiceState.IDLE)
                return@launch
            }
        } else {
            fullScreenBitmap
        }

        if (statusBarHeight > 0) {
            fullScreenBitmap.recycle()
        }

        // Thực hiện OCR
        try {
            val sourceLang = settingsManager.getSourceLanguageCode() ?: "vi"
            val ocrResult = withContext(Dispatchers.IO) {
                ocrManager.recognizeTextFromBitmap(croppedBitmap, sourceLang)
            }

            val textBlocks = ocrResult.textBlocks.filter {
                it.text.isNotBlank() && it.boundingBox != null
            }

            if (textBlocks.isEmpty()) {
                Toast.makeText(this@OverlayService, "Không tìm thấy văn bản", Toast.LENGTH_SHORT).show()
                setState(ServiceState.IDLE)
            } else {
                val copyResults = textBlocks.map { block ->
                    val screenRect = mapRectFromBitmapToScreen(block.boundingBox!!)
                    // Không cần điều chỉnh tọa độ vì mapRectFromBitmapToScreen đã xử lý đúng
                    CopyTextResult(block.text, screenRect)
                }
                showCopyTextResults(copyResults)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi thực hiện OCR cho copy text", e)
            Toast.makeText(this@OverlayService, "Lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
            setState(ServiceState.IDLE)
        } finally {
            croppedBitmap.recycle()
        }
    }

    private fun showImageTranslationResults(results: List<ImageTranslationResult>) {
        // Sử dụng global overlay để hiển thị kết quả dịch ảnh
        val overlay = showImageTranslationOverlay() ?: return
        overlay.showLoading()
        setState(ServiceState.IMAGE_TRANSLATION_ACTIVE)

        overlay.doOnLayout { view ->
            serviceScope.launch(Dispatchers.Main) {
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                val windowOffsetY = location[1]

                overlay.hideLoading()
                if (results.isEmpty()) {
                    Toast.makeText(this@OverlayService, "Không có kết quả dịch", Toast.LENGTH_SHORT).show()
                } else {
                    results.forEach { result ->
                        val finalTopMargin = result.boundingBox.top + getStatusBarHeight() - windowOffsetY
                        displaySingleImageTranslationResult(
                            result.boundingBox,
                            finalTopMargin,
                            result.translatedText,
                            overlay
                        )
                    }
                }
            }
        }
    }

    private fun showCopyTextResults(results: List<CopyTextResult>) {
        copyTextOverlay = CopyTextOverlay(themedContext) {
            setState(ServiceState.IDLE)
        }

        val params = createOverlayLayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            0
        )
        windowManager.addView(copyTextOverlay, params)
        setState(ServiceState.COPY_TEXT_ACTIVE)

        copyTextOverlay?.doOnLayout { view ->
            serviceScope.launch(Dispatchers.Main) {
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                val windowOffsetY = location[1]
                val statusBarHeight = getStatusBarHeight()

                // Thêm các kết quả với offset đúng
                results.forEach { result ->
                    val finalTopMargin = result.boundingBox.top + statusBarHeight - windowOffsetY
                    copyTextOverlay?.addCopyTextResult(
                        Rect(result.boundingBox.left, finalTopMargin,
                            result.boundingBox.right, finalTopMargin + result.boundingBox.height()),
                        result.text
                    )
                }
            }
        }
    }

    private fun showImageTranslationOverlay(): GlobalTranslationOverlay? {
        if (imageTranslationOverlay != null) return imageTranslationOverlay

        imageTranslationOverlay = GlobalTranslationOverlay(themedContext, windowManager).apply {
            onDismiss = {
                imageTranslationOverlay = null
                setState(ServiceState.IDLE)
            }
        }
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val params = createOverlayLayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            flags
        )
        windowManager.addView(imageTranslationOverlay, params)
        return imageTranslationOverlay
    }

    private fun removeImageTranslationOverlay() {
        imageTranslationOverlay?.let {
            if (it.isAttachedToWindow) windowManager.removeView(it)
        }
        imageTranslationOverlay = null
    }

    private fun removeCopyTextOverlay() {
        copyTextOverlay?.let {
            if (it.isAttachedToWindow) windowManager.removeView(it)
        }
        copyTextOverlay = null
    }

    private fun displaySingleImageTranslationResult(
        rect: Rect,
        finalTopMargin: Int,
        text: String,
        overlay: GlobalTranslationOverlay?
    ) {
        val resultView = TranslationResultView(this).apply { updateText(text) }
        val paddingPx = (3f * resources.displayMetrics.density).toInt()

        val params = FrameLayout.LayoutParams(
            rect.width() + (paddingPx * 2),
            rect.height() + (paddingPx * 2)
        ).apply {
            leftMargin = rect.left - paddingPx
            topMargin = finalTopMargin - paddingPx
        }
        overlay?.addResultView(resultView, params)
    }
    //endregion

    //region Utilities

    private fun handleOrientationChange() {
        val newOrientation = resources.configuration.orientation
        if (newOrientation != currentOrientation) {
            Log.d(TAG, "Orientation changed from $currentOrientation to $newOrientation")
            currentOrientation = newOrientation

            // Cập nhật status bar height
            updateStatusBarHeight()

            // Tái thiết lập screen capture components với kích thước mới
            serviceScope.launch {
                delay(200) // Đợi animation xoay màn hình hoàn thành
                setupScreenCaptureComponents()

                // Nếu đang ở chế độ magnifier, cập nhật cache
                if (currentState is ServiceState.MAGNIFIER_ACTIVE) {
                    refreshMagnifierCache()
                }
            }

            // Cập nhật vị trí bubble nếu cần
            adjustBubblePositionForOrientation()
        }
    }

    private fun updateStatusBarHeight() {
        currentStatusBarHeight = getActualStatusBarHeight()
        Log.d(TAG, "Status bar height updated: $currentStatusBarHeight")
    }

    private fun refreshMagnifierCache() {
        magnifierJob?.cancel()
        magnifierCache = emptyList()
        removeAllMagnifierResults()

        // Khởi động lại magnifier mode
        startMagnifierMode()
    }

    private fun adjustBubblePositionForOrientation() {
        floatingBubbleView?.let { bubble ->
            val params = bubble.layoutParams as? WindowManager.LayoutParams ?: return
            val screenSize = getRealScreenSizePx()

            // Đảm bảo bubble không bị ra khỏi màn hình
            params.x = Math.min(params.x, screenSize.x - bubble.width)
            params.y = Math.min(params.y, screenSize.y - bubble.height)
            params.x = Math.max(0, params.x)
            params.y = Math.max(0, params.y)

            try {
                windowManager.updateViewLayout(bubble, params)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update bubble position after orientation change", e)
            }
        }
    }

    private fun checkCircleRectIntersection(
        circleCenterX: Int,
        circleCenterY: Int,
        radius: Int,
        rect: Rect
    ): Boolean {
        val closestX = clamp(circleCenterX, rect.left, rect.right)
        val closestY = clamp(circleCenterY, rect.top, rect.bottom)
        val distanceX = circleCenterX - closestX
        val distanceY = circleCenterY - closestY
        return (distanceX * distanceX + distanceY * distanceY) < (radius * radius)
    }

    private fun clamp(value: Int, min: Int, max: Int): Int {
        return Math.max(min, Math.min(value, max))
    }

    private fun hasOverlayPermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(this)
    } else {
        true
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Dịch vụ Nút nổi", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createBasicNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_translate)
            .setOngoing(true)
            .build()
    }

    private fun getStatusBarHeight(): Int {
        return currentStatusBarHeight
    }
    private fun getActualStatusBarHeight(): Int {
        // Kiểm tra xem status bar có đang hiển thị không
        if (isStatusBarHidden()) {
            return 0
        }

        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            return resources.getDimensionPixelSize(resourceId)
        }
        return (24 * resources.displayMetrics.density).toInt()
    }

    private fun isStatusBarHidden(): Boolean {
        return try {
            // Kiểm tra system UI visibility flags
            val decorView = (this as? Activity)?.window?.decorView
            decorView?.let {
                val flags = it.systemUiVisibility
                (flags and View.SYSTEM_UI_FLAG_FULLSCREEN) != 0
            } ?: false
        } catch (e: Exception) {
            // Fallback: kiểm tra bằng cách so sánh kích thước màn hình
            val displayMetrics = resources.displayMetrics
            val realSize = getRealScreenSizePx()
            Math.abs(displayMetrics.heightPixels - realSize.y) < 10
        }
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermissionForImageTranslate() {
        Log.d(TAG, "Requesting camera permission for image translate")

        // Thử gọi trực tiếp ImageTranslateActivity trước
        try {
            val directIntent = Intent(this, ImageTranslateActivity::class.java).apply {
                action = ImageTranslateActivity.ACTION_TRANSLATE_IMAGE
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(directIntent)
            Log.d(TAG, "Started ImageTranslateActivity directly")
            return
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ImageTranslateActivity directly: ${e.message}")
        }

        // Nếu không thành công, thử qua MainActivity
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                action = "REQUEST_CAMERA_PERMISSION_FOR_IMAGE_TRANSLATE"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            Log.d(TAG, "Started MainActivity for camera permission")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MainActivity for permission: ${e.message}")
            Toast.makeText(this, "Không thể yêu cầu quyền camera", Toast.LENGTH_SHORT).show()
        }
    }
    private fun startImageTranslateDirectly() {
        Log.d(TAG, "Starting ImageTranslateActivity directly")
        try {
            val intent = Intent(this, ImageTranslateActivity::class.java).apply {
                action = ImageTranslateActivity.ACTION_TRANSLATE_IMAGE
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Log.d(TAG, "ImageTranslateActivity started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ImageTranslateActivity: ${e.message}")
            Toast.makeText(this, "Không thể mở camera: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startImageTranslate() {
        setState(ServiceState.IDLE) // Đóng panel trước
        Log.d(TAG, "Starting image translate, checking camera permission")

        if (!checkCameraPermission()) {
            Log.d(TAG, "Camera permission not granted, requesting...")
            requestCameraPermissionForImageTranslate()
            return
        }

        Log.d(TAG, "Camera permission granted, starting image translate directly")
        startImageTranslateDirectly()
    }
    //endregion
}