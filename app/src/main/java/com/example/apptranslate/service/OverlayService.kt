// File: app/src/main/java/com/example/apptranslate/service/OverlayService.kt

package com.example.apptranslate.service

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
import com.example.apptranslate.R
import com.example.apptranslate.data.SettingsManager
import com.example.apptranslate.data.TranslationManager
import com.example.apptranslate.ocr.OcrManager
import com.example.apptranslate.ocr.OcrResult
import com.example.apptranslate.ui.overlay.*
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

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
    private val handler = Handler(Looper.getMainLooper())

    // Views và UI
    private var floatingBubbleView: FloatingBubbleView? = null
    private var globalOverlay: GlobalTranslationOverlay? = null
    private var dismissOverlay: View? = null

    // Chế độ Kính lúp
    private var magnifierJob: Job? = null
    private var lastHoveredBlock: OcrResult.Block? = null
    private var magnifierCache: List<TranslatedBlock> = emptyList()
    private val magnifierResultViews = mutableListOf<TranslationResultView>()
    private var magnifierLensView: ImageView? = null

    private val LENS_SIZE by lazy { resources.getDimensionPixelSize(R.dimen.magnifier_lens_size) }

    // Quản lý trạng thái
    private sealed class ServiceState {
        object IDLE : ServiceState()
        object PANEL_OPEN : ServiceState()
        object MAGNIFIER_ACTIVE : ServiceState()
        object GLOBAL_TRANSLATE_ACTIVE : ServiceState()
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
        /* Không dùng đến, nhưng bắt buộc phải có để implement interface */
    }

    override fun onDragStarted() {
        if (currentState is ServiceState.IDLE) {
            setState(ServiceState.MAGNIFIER_ACTIVE)
        }
    }

    override fun onDragFinished() {
        if (currentState is ServiceState.MAGNIFIER_ACTIVE) {
            setState(ServiceState.IDLE)
        }
    }

    override fun onFunctionClicked(functionId: String) {
        if (functionId == "GLOBAL") {
            setState(ServiceState.GLOBAL_TRANSLATE_ACTIVE)
        } else {
            setState(ServiceState.IDLE)
            Toast.makeText(this, "Chức năng '$functionId' chưa được triển khai", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onLanguageSelectClicked() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            action = ACTION_SHOW_LANGUAGE_SHEET
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
        setState(ServiceState.IDLE)
    }

    override fun onHomeClicked() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(intent)
        setState(ServiceState.IDLE)
    }

    override fun onMoveClicked() {
        setState(ServiceState.IDLE)
    }
    //endregion

    //region Quản lý Trạng thái (State Machine)
    private fun setState(newState: ServiceState) {
        if (currentState::class == newState::class) return
        Log.d(TAG, "State changing from ${currentState::class.simpleName} to ${newState::class.simpleName}")

        // --- Thoát khỏi trạng thái hiện tại ---
        when (currentState) {
            is ServiceState.MAGNIFIER_ACTIVE -> stopMagnifierMode()
            is ServiceState.GLOBAL_TRANSLATE_ACTIVE -> removeGlobalOverlay()
            is ServiceState.PANEL_OPEN -> removeDismissOverlay()
            is ServiceState.IDLE -> {}
        }

        currentState = newState

        // --- Vào trạng thái mới ---
        when (newState) {
            is ServiceState.IDLE -> {
                floatingBubbleView?.apply {
                    closePanel()
                    updateBubbleAppearance(BubbleAppearance.NORMAL)
                    visibility = View.VISIBLE
                    alpha = 1.0f
                }
            }
            is ServiceState.PANEL_OPEN -> {
                floatingBubbleView?.openPanel()
                showDismissOverlay()
            }
            is ServiceState.MAGNIFIER_ACTIVE -> {
                floatingBubbleView?.apply {
                    closePanel()
                    updateBubbleAppearance(BubbleAppearance.MAGNIFIER)
                }
                startMagnifierMode()
            }
            is ServiceState.GLOBAL_TRANSLATE_ACTIVE -> {
                floatingBubbleView?.closePanel()
                performGlobalTranslate()
            }
        }
    }
    //endregion

    //region Logic chung cho OCR và Dịch thuật
    /**
     * Chụp ảnh màn hình, thực hiện OCR và dịch thuật.
     * Đây là hàm cốt lõi được sử dụng bởi cả Magnifier và Global Translate.
     * @return Một `Result` chứa danh sách các `TranslatedBlock` hoặc một Exception.
     */
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

        val translatedText = translationResult.getOrThrow() // Ném lỗi nếu dịch thất bại
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

    //region Logic Chế độ Kính lúp (Magnifier)
    private fun startMagnifierMode() {
        showMagnifierLens()
        magnifierJob = serviceScope.launch {
            // Vòng lặp cập nhật UI chạy ngay lập tức để đảm bảo sự mượt mà
            launch { startMagnifierTrackingLoop() }

            // Tác vụ nặng (chụp màn hình, OCR, dịch) chạy song song
            val bubble = floatingBubbleView ?: return@launch
            withContext(Dispatchers.Main) { bubble.alpha = 0f }
            delay(150) // Chờ bubble ẩn đi trước khi chụp

            val screenBitmap = captureScreen()
            withContext(Dispatchers.Main) { bubble.alpha = 1f }

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
                Log.e(TAG, "Error preparing magnifier data", e)
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

    private suspend fun startMagnifierTrackingLoop() {
        while (currentCoroutineContext().isActive && currentState is ServiceState.MAGNIFIER_ACTIVE) {
            floatingBubbleView?.let { bubble ->
                val bubbleParams = bubble.layoutParams as WindowManager.LayoutParams
                val lensDetails = calculateLensDetails(bubbleParams.x, bubbleParams.y)

                updateMagnifierLensPosition(lensDetails)
                findAndShowMagnifierResultAt(lensDetails.scanCenter)
            }
            delay(16) // ~60 FPS
        }
    }

    private fun findAndShowMagnifierResultAt(scanCenter: Point) {
        val lensRadiusRatio = 6.5f / 24f
        val lensRadius = (LENS_SIZE * lensRadiusRatio).toInt()

        val targetCacheItem = magnifierCache.find {
            checkCircleRectIntersection(scanCenter.x, scanCenter.y, lensRadius, it.original.boundingBox!!)
        }

        if (targetCacheItem?.original == lastHoveredBlock) return

        lastHoveredBlock = targetCacheItem?.original
        removeAllMagnifierResults()

        targetCacheItem?.let {
            val resultView = showSingleMagnifierResult(it.original.boundingBox!!)
            resultView.updateText(it.translated)
            magnifierResultViews.add(resultView)
        }
    }
    //endregion

    //region Logic Dịch Toàn cầu (Global Translate)
    private fun performGlobalTranslate() = serviceScope.launch {
        floatingBubbleView?.alpha = 0.0f
        delay(150)
        val screenBitmap = captureScreen()

        if (screenBitmap == null) {
            Toast.makeText(this@OverlayService, "Không thể chụp màn hình", Toast.LENGTH_SHORT).show()
            setState(ServiceState.IDLE)
            return@launch
        }

        val overlay = showGlobalOverlay()
        overlay?.showLoading()

        performOcrAndTranslation(screenBitmap).onSuccess { results ->
            overlay?.hideLoading()
            if (results.isEmpty()) {
                Toast.makeText(this@OverlayService, "Không tìm thấy văn bản", Toast.LENGTH_SHORT).show()
            } else {
                results.forEach { block ->
                    displaySingleGlobalResult(block.original.boundingBox!!, block.translated, overlay)
                }
            }
        }.onFailure { e ->
            overlay?.hideLoading()
            Log.e(TAG, "Error in global translate", e)
            Toast.makeText(this@OverlayService, "Lỗi: ${e.message}", Toast.LENGTH_LONG).show()
        }

        screenBitmap.recycle()
    }
    //endregion

    //region Quản lý Service & Tương tác Hệ thống
    private fun handleStartService(intent: Intent) {
        if (!hasOverlayPermission()) {
            // TODO: Gửi broadcast hoặc thông báo cho Activity để yêu cầu quyền
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
            mediaProjection?.stop()
            ocrManager.close()
            floatingBubbleView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
            removeGlobalOverlay()
            removeAllMagnifierResults()
            removeDismissOverlay()
            removeMagnifierLens()
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
                    stopServiceCleanup()
                }
            }, handler)
        }
    }
    //endregion

    //region Chụp màn hình (Screen Capture)
    private suspend fun captureScreen(): Bitmap? = suspendCancellableCoroutine { continuation ->
        val currentMediaProjection = mediaProjection
        if (currentMediaProjection == null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val screenDensity = displayMetrics.densityDpi

        if (screenWidth <= 0 || screenHeight <= 0) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        val imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        val virtualDisplay = currentMediaProjection.createVirtualDisplay(
            "ScreenCapture", screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, handler
        )

        var imageAcquired = false
        imageReader.setOnImageAvailableListener({ reader ->
            if (!continuation.isActive || imageAcquired) return@setOnImageAvailableListener
            imageAcquired = true

            reader.acquireLatestImage()?.use { image ->
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * screenWidth

                val bitmap = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)

                // Cắt phần padding nếu có
                val finalBitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                bitmap.recycle()

                if (continuation.isActive) {
                    continuation.resume(finalBitmap)
                } else {
                    finalBitmap.recycle()
                }
            }
            virtualDisplay?.release()
            reader.close()
        }, handler)

        continuation.invokeOnCancellation {
            virtualDisplay?.release()
            imageReader.close()
        }
    }
    //endregion

    //region Quản lý UI & View
    private data class LensDetails(val iconTopLeft: Point, val scanCenter: Point)

    private fun calculateLensDetails(handleX: Int, handleY: Int): LensDetails {
        val handleSize = resources.getDimensionPixelSize(R.dimen.bubble_size)
        val handleCenterX = handleX + handleSize / 2
        val handleCenterY = handleY + handleSize / 2

        val visualCenterRatio = 9.5f / 24f // Tỷ lệ để căn giữa hình ảnh kính lúp
        val iconTopLeftX = handleCenterX - (LENS_SIZE * visualCenterRatio).toInt()
        val iconTopLeftY = handleCenterY - (LENS_SIZE * visualCenterRatio).toInt()

        return LensDetails(
            iconTopLeft = Point(iconTopLeftX, iconTopLeftY),
            scanCenter = Point(handleCenterX, handleCenterY)
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
        val themedContext = ContextThemeWrapper(this, R.style.Theme_AppTranslate_NoActionBar)
        floatingBubbleView = FloatingBubbleView(themedContext, serviceScope).apply {
            listener = this@OverlayService
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val params = createOverlayLayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            flags
        )
        floatingBubbleView?.setViewLayoutParams(params)
        windowManager.addView(floatingBubbleView, params)
    }

    private fun showGlobalOverlay(): GlobalTranslationOverlay? {
        if (globalOverlay != null) return globalOverlay
        globalOverlay = GlobalTranslationOverlay(this, windowManager).apply {
            onDismiss = {
                globalOverlay = null // Quan trọng: nullify trước khi thay đổi trạng thái
                setState(ServiceState.IDLE)
            }
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
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

    private fun displaySingleGlobalResult(boundingBox: Rect, text: String, overlay: GlobalTranslationOverlay?) {
        val resultView = TranslationResultView(this).apply { updateText(text) }
        val paddingDp = 2f
        val paddingPx = (paddingDp * resources.displayMetrics.density).toInt()

        val params = FrameLayout.LayoutParams(
            boundingBox.width() + (paddingPx * 2),
            boundingBox.height() + (paddingPx * 2)
        ).apply {
            leftMargin = boundingBox.left - paddingPx
            topMargin = boundingBox.top - paddingPx
        }
        overlay?.addResultView(resultView, params)
    }

    private fun showDismissOverlay() {
        if (dismissOverlay != null) return
        dismissOverlay = FrameLayout(this).apply {
            setOnClickListener { setState(ServiceState.IDLE) }
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val params = createOverlayLayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            flags
        )
        windowManager.addView(dismissOverlay, params)
    }

    private fun removeDismissOverlay() {
        dismissOverlay?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        dismissOverlay = null
    }

    private fun showMagnifierLens() {
        if (magnifierLensView != null || floatingBubbleView == null) return

        val bubbleParams = (floatingBubbleView!!.layoutParams as WindowManager.LayoutParams)
        val lensDetails = calculateLensDetails(bubbleParams.x, bubbleParams.y)

        magnifierLensView = ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val params = createOverlayLayoutParams(LENS_SIZE, LENS_SIZE, flags).apply {
            x = lensDetails.iconTopLeft.x
            y = lensDetails.iconTopLeft.y
        }
        windowManager.addView(magnifierLensView, params)
    }

    private fun updateMagnifierLensPosition(lensDetails: LensDetails) {
        magnifierLensView?.let { lens ->
            val params = lens.layoutParams as WindowManager.LayoutParams
            if (params.x != lensDetails.iconTopLeft.x || params.y != lensDetails.iconTopLeft.y) {
                params.x = lensDetails.iconTopLeft.x
                params.y = lensDetails.iconTopLeft.y
                windowManager.updateViewLayout(lens, params)
            }
        }
    }

    private fun removeMagnifierLens() {
        magnifierLensView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        magnifierLensView = null
    }
    //endregion

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
    //endregion
}