// File: app/src/main/java/com/example/apptranslate/service/OverlayService.kt

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

import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.math.roundToInt
import androidx.core.view.doOnLayout
import com.example.apptranslate.viewmodel.LanguageViewModel
import com.example.apptranslate.viewmodel.LanguageViewModelFactory

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
    private var regionSelectOverlay: RegionSelectOverlay? = null

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
    }
    private var currentState: ServiceState = ServiceState.IDLE

    private data class TranslatedBlock(val original: OcrResult.Block, val translated: String)
    //endregion

    //region Vòng đời Service và Listeners
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        settingsManager = SettingsManager.getInstance(this)
        translationManager = TranslationManager(this)
        createNotificationChannel()
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
        stopServiceCleanup()
        super.onDestroy()
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
            "AREA" -> setState(ServiceState.REGION_SELECT_ACTIVE) // <-- Xử lý chức năng mới
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
    //endregion

    //region Quản lý Trạng thái (State Machine)
    private fun setState(newState: ServiceState) {
        if (currentState::class == newState::class) return
        Log.d(TAG, "State changing from ${currentState.javaClass.simpleName} to ${newState.javaClass.simpleName}")

        // --- Bước 1: Dọn dẹp trạng thái HIỆN TẠI ---
        when (currentState) {
            is ServiceState.PANEL_OPEN -> floatingBubbleView?.closePanel()
            is ServiceState.MAGNIFIER_ACTIVE -> stopMagnifierMode()
            is ServiceState.GLOBAL_TRANSLATE_ACTIVE -> removeGlobalOverlay()
            is ServiceState.LANGUAGE_SELECT_OPEN -> removeLanguageSheet()
            is ServiceState.REGION_SELECT_ACTIVE -> removeRegionSelectOverlay()

            else -> {}
        }

        currentState = newState

        // --- Bước 2: Thiết lập cho trạng thái MỚI ---
        // Ẩn/hiện bubble một cách nhất quán
        floatingBubbleView?.visibility = when (newState) {
            // Ẩn bubble khi các giao diện toàn màn hình được hiển thị
            is ServiceState.GLOBAL_TRANSLATE_ACTIVE,
            is ServiceState.LANGUAGE_SELECT_OPEN -> View.GONE,
            is ServiceState.REGION_SELECT_ACTIVE -> View.GONE
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

    private fun showLanguageSheet() {
        if (languageSheetView != null) return

        // DÙNG themedContext ở đây
        languageSheetView = LanguageSheetView(themedContext, languageViewModel) { languageChanged ->
            setState(ServiceState.IDLE)
            // Nếu ngôn ngữ thực sự thay đổi, gửi broadcast
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

    //region Logic chung cho OCR và Dịch thuật
    private suspend fun performOcrAndTranslation(bitmap: Bitmap): Result<List<TranslatedBlock>> = runCatching {
        if (bitmap.isRecycled) return@runCatching emptyList()

        val sourceLang = settingsManager.getSourceLanguageCode() ?: "vi"
        val ocrResult = withContext(Dispatchers.IO) {
            ocrManager.recognizeTextFromBitmap(bitmap, sourceLang)
        }

        val blocksToTranslate = ocrResult.textBlocks.filter { it.text.isNotBlank() && it.boundingBox != null }
        if (blocksToTranslate.isEmpty()) return@runCatching emptyList()

        val combinedText = blocksToTranslate.joinToString(separator = OCR_TRANSLATION_DELIMITER) { it.text }
        val targetLang = settingsManager.getTargetLanguageCode() ?: "en"
        val transSource = settingsManager.getTranslationSource()

        val translationResult = withContext(Dispatchers.IO) {
            translationManager.translate(combinedText, sourceLang, targetLang, transSource)
        }

        val translatedText = translationResult.getOrThrow()
        val translatedSegments = translatedText.split(OCR_TRANSLATION_DELIMITER)

        if (blocksToTranslate.size == translatedSegments.size) {
            blocksToTranslate.zip(translatedSegments).map { (original, translated) ->
                TranslatedBlock(original, translated)
            }
        } else {
            Log.w(TAG, "Mismatch between OCR blocks (${blocksToTranslate.size}) and translated segments (${translatedSegments.size})")
            emptyList()
        }
    }
    //endregion

    private fun startMagnifierMode() {
        magnifierLensView?.visibility = View.VISIBLE
        magnifierJob = serviceScope.launch {
            val bubble = floatingBubbleView ?: return@launch
            withContext(Dispatchers.Main) {
                bubble.alpha = 0.0f  // Thay INVISIBLE bằng alpha=0 để giữ touch events
            }

            val screenBitmap = try {
                captureScreen()
            } catch (e: Exception) {
                Log.e(TAG, "Capture failed: ${e.stackTraceToString()}")
                null
            }
            withContext(Dispatchers.Main) {
                bubble.alpha = 1.0f  // Reset alpha (nhưng trong magnifier, sẽ set 0 ở updateAppearance)
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

    override fun onDrag(x: Int, y: Int) {
        // Chỉ cập nhật kính lúp khi đang ở chế độ Magnifier
        if (currentState is ServiceState.MAGNIFIER_ACTIVE) {
            val lensDetails = calculateLensDetails(x, y)
            updateMagnifierLensPosition(lensDetails)
            findAndShowMagnifierResultAt(lensDetails.scanCenter)
        }
    }
    //endregion

    //region Logic Dịch Toàn cầu (Global Translate)
    private fun performGlobalTranslate() = serviceScope.launch {
        // === BƯỚC 1: CHỤP VÀ CHUẨN BỊ ẢNH TRƯỚC KHI HIỂN THỊ BẤT CỨ GÌ ===

        // Chờ một chút để các animation (như đóng panel) hoàn thành
        delay(100L)

        // Chụp ảnh màn hình (lúc này chưa có nền mờ)
        val fullScreenBitmap = captureScreenWithBubbleHidden()
        if (fullScreenBitmap == null) {
            Toast.makeText(this@OverlayService, "Không thể chụp màn hình", Toast.LENGTH_SHORT).show()
            setState(ServiceState.IDLE)
            return@launch
        }

        // Cắt bỏ thanh trạng thái
        val statusBarHeight = getStatusBarHeight()
        val croppedBitmap = try {
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
        // Giải phóng bộ nhớ của ảnh gốc ngay lập tức
        fullScreenBitmap.recycle()


        // === BƯỚC 2: SAU KHI ĐÃ CÓ ẢNH, MỚI HIỂN THỊ UI ===
        val overlay = showGlobalOverlay() ?: run {
            // Nếu không hiển thị được overlay, phải hủy bitmap đã tạo để tránh rò rỉ bộ nhớ
            croppedBitmap.recycle()
            setState(ServiceState.IDLE)
            return@launch
        }
        overlay.showLoading()


        // === BƯỚC 3: XỬ LÝ ẢNH VÀ HIỂN THỊ KẾT QUẢ ===
        overlay.doOnLayout { view ->
            serviceScope.launch(Dispatchers.IO) {
                // Lấy tọa độ Y của overlay để tính toán vị trí hiển thị chính xác
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                val windowOffsetY = location[1]

                // Thực hiện OCR và dịch trên ảnh đã được chuẩn bị sẵn
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

                // Dọn dẹp bitmap cuối cùng sau khi mọi thứ hoàn tất
                croppedBitmap.recycle()
            }
        }
    }

    //region Quản lý Service & Tương tác Hệ thống
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
            // Refactor: Khởi tạo MediaProjection và các thành phần chụp màn hình
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

            // Refactor: Dọn dẹp các thành phần chụp màn hình
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()

            // Dọn dẹp các view
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

        val realSize = getRealScreenSizePx()
        val screenWidth = realSize.x
        val screenHeight = realSize.y
        val screenDensity = resources.displayMetrics.densityDpi

        if (screenWidth <= 0 || screenHeight <= 0) {
             Log.e(TAG, "setupScreenCaptureComponents failed: Invalid screen dimensions ($screenWidth, $screenHeight).")
            return
        }

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

        // Sau khi kiểm tra null ở trên, trình biên dịch đã biết chắc `image` là kiểu `Image` (không null)
        // và có thể truy cập các thuộc tính như .planes, .width, .close()
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
            image.close() // Quan trọng: Luôn đóng image sau khi dùng xong
        }
    }

    private suspend fun captureScreenWithBubbleHidden(): Bitmap? {
        val bubble = floatingBubbleView ?: return null
        return withContext(Dispatchers.Main) {
            try {
                // 1. Ẩn bong bóng
                bubble.visibility = View.INVISIBLE
                // 2. Chờ 1 frame để UI thread render lại
                delay(16)
                // 3. Chụp màn hình
                captureScreen()
            } finally {
                // 4. Hiện lại bong bóng (luôn luôn thực thi)
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

    //region Quản lý UI & View
    private data class LensDetails(val iconTopLeft: Point, val scanCenter: Point)

    private fun calculateLensDetails(handleX: Int, handleY: Int): LensDetails {
        val handleSize = resources.getDimensionPixelSize(R.dimen.bubble_size)
        val handleCenterX = handleX + handleSize / 2
        val handleCenterY = handleY + handleSize / 2

        // Offset icon kính lúp chéo trên-trái 45°
        val offset = (LENS_SIZE * 0.4f).toInt()
        val diagonal = (offset / Math.sqrt(2.0)).toInt()

        // Tâm icon (cũng là tâm scan)
        val lensCenterX = handleCenterX - diagonal
        val lensCenterY = handleCenterY - diagonal

        val iconTopLeftX = lensCenterX - LENS_SIZE / 2
        val iconTopLeftY = lensCenterY - LENS_SIZE / 2

        return LensDetails(
            iconTopLeft = Point(iconTopLeftX, iconTopLeftY),
            scanCenter = Point(lensCenterX, lensCenterY) // 🟢 lấy tâm icon làm tâm scan
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

        // DÙNG themedContext ở đây
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

        // DÙNG themedContext ở đây
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
        val paddingDp = 2f
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

    // Sửa lại hàm này để nhận thêm yOffset
    private fun displaySingleGlobalResult(
        screenRect: Rect,
        finalTopMargin: Int, // MỚI: Nhận margin đã tính sẵn
        text: String,
        overlay: GlobalTranslationOverlay?
    ) {
        val resultView = TranslationResultView(this).apply { updateText(text) }
        val paddingPx = (2f * resources.displayMetrics.density).toInt()

        val params = FrameLayout.LayoutParams(
            screenRect.width() + (paddingPx * 2),
            screenRect.height() + (paddingPx * 2)
        ).apply {
            leftMargin = screenRect.left - paddingPx
            topMargin = finalTopMargin - paddingPx // Áp dụng margin đã được tính toán
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
            // Ẩn nó đi ngay từ đầu
            visibility = View.GONE
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        // Vị trí ban đầu không quan trọng vì nó sẽ được cập nhật sau
        val params = createOverlayLayoutParams(LENS_SIZE, LENS_SIZE, flags)
        windowManager.addView(magnifierLensView, params)
    }

    private fun updateMagnifierLensPosition(lensDetails: LensDetails) {
        magnifierLensView?.let { lens ->
            lens.visibility = View.VISIBLE // Đảm bảo visible khi drag
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

    //region Tiện ích (Utilities)
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
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            return resources.getDimensionPixelSize(resourceId)
        }
        // Giá trị dự phòng nếu không tìm thấy resource
        return (24 * resources.displayMetrics.density).toInt()
    }

    private fun showRegionSelectOverlay() {
        if (regionSelectOverlay != null) return

        regionSelectOverlay = RegionSelectionOverlay(themedContext,
            onRegionSelected = { rect ->
                // Người dùng đã chọn xong, tiến hành dịch
                performRegionTranslation(rect)
            },
            onDismiss = {
                // Người dùng hủy
                setState(ServiceState.IDLE)
            }
        )

        val params = createOverlayLayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            0 // Không cần flag đặc biệt, cho phép focus và touch
        )
        windowManager.addView(regionSelectOverlay, params)
    }

    private fun removeRegionSelectOverlay() {
        regionSelectOverlay?.let {
            if (it.isAttachedToWindow) {
                windowManager.removeView(it)
            }
        }
        regionSelectOverlay = null
    }

    private fun performRegionTranslation(region: Rect) = serviceScope.launch {
        removeRegionSelectOverlay()

        val resultOverlay = showGlobalOverlay()
        resultOverlay?.showLoading()

        val screenBitmap = captureScreenWithBubbleHidden()
        if (screenBitmap == null) {
            Toast.makeText(this@OverlayService, "Không thể chụp màn hình", Toast.LENGTH_SHORT).show()
            setState(ServiceState.IDLE)
            return@launch
        }

        val croppedBitmap = try {
            // Đảm bảo vùng cắt nằm trong giới hạn của bitmap
            val validRegion = Rect(region)
            if (!validRegion.intersect(0, 0, screenBitmap.width, screenBitmap.height)) {
                throw IllegalArgumentException("Vùng chọn nằm ngoài màn hình.")
            }
            Bitmap.createBitmap(screenBitmap, validRegion.left, validRegion.top, validRegion.width(), validRegion.height())
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi cắt ảnh", e)
            Toast.makeText(this@OverlayService, "Lỗi: Vùng chọn không hợp lệ.", Toast.LENGTH_SHORT).show()
            setState(ServiceState.IDLE)
            screenBitmap.recycle()
            return@launch
        }
        screenBitmap.recycle()

        performOcrAndTranslation(croppedBitmap).onSuccess { results ->
            resultOverlay?.hideLoading()
            if (results.isEmpty()) {
                Toast.makeText(this@OverlayService, "Không tìm thấy văn bản", Toast.LENGTH_SHORT).show()
                delay(1500)
                setState(ServiceState.IDLE)
            } else {
                results.forEach { block ->
                    val originalBox = block.original.boundingBox!!
                    val onScreenRect = Rect(
                        region.left + originalBox.left,
                        region.top + originalBox.top,
                        region.left + originalBox.right,
                        region.top + originalBox.bottom
                    )
                    displaySingleGlobalResult(onScreenRect, onScreenRect.top, block.translated, resultOverlay)
                }
            }
        }.onFailure { e ->
            resultOverlay?.hideLoading()
            Log.e(TAG, "Lỗi khi dịch vùng", e)
            Toast.makeText(this@OverlayService, "Lỗi: ${e.message}", Toast.LENGTH_LONG).show()
            setState(ServiceState.IDLE)
        }
        croppedBitmap.recycle()
    }
}