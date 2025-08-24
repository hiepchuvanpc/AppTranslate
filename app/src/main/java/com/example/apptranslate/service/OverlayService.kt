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
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.math.cos
import kotlin.math.sin

@SuppressLint("ViewConstructor")
class OverlayService : Service(), BubbleViewListener {

    companion object {
        private const val TAG = "OverlayService"
        var isRunning = false
            private set

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "overlay_service_channel"
        private const val LENS_OFFSET_DISTANCE = 280

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
    }

    //<editor-fold desc="Khai báo biến">
    private lateinit var windowManager: WindowManager
    private lateinit var settingsManager: SettingsManager
    private val ocrManager by lazy { OcrManager.getInstance() }
    private lateinit var translationManager: TranslationManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mediaProjection: MediaProjection? = null
    private val handler = Handler(Looper.getMainLooper())

    private var floatingBubbleView: FloatingBubbleView? = null
    private var globalOverlay: GlobalTranslationOverlay? = null
    private var magnifierLensView: ImageView? = null
    private var scanAreaDebugView: View? = null
    private val magnifierResultViews = mutableListOf<View>()
    private var imageReader: ImageReader? = null
    private var dismissOverlay: View? = null
    private val ocrDebugViews = mutableListOf<View>()

    private val LENS_SIZE by lazy { resources.getDimensionPixelSize(R.dimen.magnifier_lens_size) }

    private enum class ServiceState { IDLE, PANEL_OPEN, MAGNIFIER_ACTIVE, GLOBAL_TRANSLATE_ACTIVE }
    private var currentState = ServiceState.IDLE
    private var statusBarHeight = 0

    private data class MagnifierCache(val originalBlock: OcrResult.Block, val translatedText: String)
    private var magnifierCache: List<MagnifierCache> = emptyList()

    private var magnifierJob: Job? = null
    private var lastHoveredBlock: OcrResult.Block? = null
    //</editor-fold>

    //<editor-fold desc="Vòng đời Service & Listeners">
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // <<< THÊM ĐOẠN CODE NÀY
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            statusBarHeight = resources.getDimensionPixelSize(resourceId)
        }
        // >>> KẾT THÚC ĐOẠN CODE THÊM

        settingsManager = SettingsManager.getInstance(this)
        translationManager = TranslationManager(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createBasicNotification("Dịch vụ đang chạy"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVICE -> handleStartService(intent)
            ACTION_STOP_SERVICE -> stopService()
            ACTION_UPDATE_LANGUAGES -> handleUpdateLanguages(intent)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopService()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onBubbleTapped() {
        if (currentState == ServiceState.PANEL_OPEN) {
            setState(ServiceState.IDLE)
        } else if (currentState == ServiceState.IDLE) {
            setState(ServiceState.PANEL_OPEN)
        }
    }

    override fun onBubbleLongPressed() { /* Không dùng */ }

    override fun onDragStarted() {
        if (currentState == ServiceState.IDLE) {
            setState(ServiceState.MAGNIFIER_ACTIVE)
        }
    }

    override fun onDragFinished() {
        if (currentState == ServiceState.MAGNIFIER_ACTIVE) {
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
    //</editor-fold>

    //<editor-fold desc="Quản lý Trạng thái">
    private fun setState(newState: ServiceState) {
        if (currentState == newState) return
        Log.d(TAG, "State changing from $currentState to $newState")

        when (currentState) {
            ServiceState.MAGNIFIER_ACTIVE -> {
                stopMagnifierMode()
                removeMagnifierViews()
            }
            ServiceState.GLOBAL_TRANSLATE_ACTIVE -> removeGlobalOverlay()
            ServiceState.PANEL_OPEN -> removeDismissOverlay()
            else -> {}
        }
        currentState = newState

        when (newState) {
            ServiceState.IDLE -> {
                floatingBubbleView?.closePanel()
                floatingBubbleView?.updateBubbleAppearance(BubbleAppearance.NORMAL)
                floatingBubbleView?.visibility = View.VISIBLE
                floatingBubbleView?.alpha = 1.0f
            }
            ServiceState.PANEL_OPEN -> {
                floatingBubbleView?.openPanel()
                showDismissOverlay()
            }
            ServiceState.MAGNIFIER_ACTIVE -> {
                floatingBubbleView?.closePanel()
                floatingBubbleView?.updateBubbleAppearance(BubbleAppearance.MAGNIFIER)
                showMagnifierViews()
                startMagnifierMode()
            }
            ServiceState.GLOBAL_TRANSLATE_ACTIVE -> {
                floatingBubbleView?.closePanel()
                performGlobalTranslate()
            }
        }
    }
    //</editor-fold>

    //<editor-fold desc="Logic Kính lúp (Magnifier)">
    private fun startMagnifierMode() {
        magnifierJob = serviceScope.launch {
            prepareMagnifierData()
            if (magnifierCache.isNotEmpty()) {
                startMagnifierTrackingLoop()
            } else {
                setState(ServiceState.IDLE)
            }
        }
    }

    private suspend fun prepareMagnifierData() {
        val bubble = floatingBubbleView ?: return
        var screenBitmap: Bitmap? = null
        try {
            withContext(Dispatchers.Main) {
                magnifierLensView?.alpha = 0f
                scanAreaDebugView?.alpha = 0f
                bubble.alpha = 0.0f
            }
            delay(150)
            screenBitmap = captureScreen()
            withContext(Dispatchers.Main) {
                magnifierLensView?.alpha = 1f
                scanAreaDebugView?.alpha = 1f
                bubble.alpha = 1.0f
            }

            if (screenBitmap == null) return

            val sourceLang = settingsManager.getSourceLanguageCode() ?: "vi"
            val ocrResult = withContext(Dispatchers.IO) { ocrManager.recognizeTextFromBitmap(screenBitmap, sourceLang) }

            withContext(Dispatchers.Main) {
                removeAllOcrDebugViews()
                ocrResult.textBlocks.forEach { block ->
                    if (block.boundingBox != null) {
                        showOcrDebugBox(block.boundingBox)
                    }
                }
            }

            val blocksToTranslate = ocrResult.textBlocks.filter { it.text.isNotBlank() && it.boundingBox != null }
            if (blocksToTranslate.isEmpty()) return

            val delimiter = "\n\n"
            val combinedText = blocksToTranslate.joinToString(separator = delimiter) { it.text }
            val targetLang = settingsManager.getTargetLanguageCode() ?: "en"
            val transSource = settingsManager.getTranslationSource()
            val translationResult = withContext(Dispatchers.IO) { translationManager.translate(combinedText, sourceLang, targetLang, transSource) }

            translationResult.onSuccess { translatedText ->
                val translatedBlocks = translatedText.split(delimiter)
                if (blocksToTranslate.size == translatedBlocks.size) {
                    magnifierCache = blocksToTranslate.zip(translatedBlocks).map { (original, translated) ->
                        MagnifierCache(original, translated)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing magnifier data", e)
        } finally {
            screenBitmap?.recycle()
        }
    }

    private suspend fun startMagnifierTrackingLoop() {
        while (coroutineContext.isActive && currentState == ServiceState.MAGNIFIER_ACTIVE) {
            floatingBubbleView?.let { bubble ->
                val bubbleParams = bubble.layoutParams as WindowManager.LayoutParams
                val lensDetails = calculateLensDetails(bubbleParams.x, bubbleParams.y)
                updateMagnifierViewsPosition(lensDetails)
                findAndShowMagnifierResultAt(lensDetails.scanCenter.x, lensDetails.scanCenter.y)
            }
            delay(16)
        }
    }

    private fun findAndShowMagnifierResultAt(lensCenterX: Int, lensCenterY: Int) {
        // <<< SỬA LỖI TẠI ĐÂY
        // Sử dụng đúng tỷ lệ bán kính để quét
        val lensRadiusRatio = 6.5f / 24f
        val lensRadius = (LENS_SIZE * lensRadiusRatio).toInt()

        val targetCacheItem = magnifierCache.find {
            checkCircleRectIntersection(
                lensCenterX,
                lensCenterY,
                lensRadius,
                it.originalBlock.boundingBox!!
            )
        }

        if (targetCacheItem == null) {
            if (lastHoveredBlock != null) {
                lastHoveredBlock = null
                removeAllMagnifierResults()
            }
            return
        }

        if (targetCacheItem.originalBlock == lastHoveredBlock) return

        lastHoveredBlock = targetCacheItem.originalBlock
        removeAllMagnifierResults()
        val resultView = showSingleMagnifierResult(targetCacheItem.originalBlock.boundingBox!!)
        resultView.updateText(targetCacheItem.translatedText)
    }

    private fun stopMagnifierMode() {
        magnifierJob?.cancel()
        magnifierJob = null
        magnifierCache = emptyList()
        lastHoveredBlock = null
        removeAllMagnifierResults()
        removeAllOcrDebugViews()
    }
    //</editor-fold>

    //<editor-fold desc="Logic Dịch Toàn cầu">
    private fun performGlobalTranslate() = serviceScope.launch {
        val bubble = floatingBubbleView ?: return@launch
        var screenBitmap: Bitmap? = null
        try {
            bubble.alpha = 0.0f
            delay(150)
            screenBitmap = captureScreen()

            if (screenBitmap == null) {
                Toast.makeText(this@OverlayService, "Không thể chụp màn hình", Toast.LENGTH_SHORT).show()
                setState(ServiceState.IDLE)
                return@launch
            }

            val overlay = showGlobalOverlay()
            overlay?.showLoading()

            overlay?.post {
                val overlayLocation = IntArray(2)
                overlay.getLocationOnScreen(overlayLocation)
                val offsetY = overlayLocation[1]
                serviceScope.launch { processAndDisplayTranslations(screenBitmap!!, overlay) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in performGlobalTranslate", e)
            screenBitmap?.recycle()
            setState(ServiceState.IDLE)
        }
    }

    private suspend fun processAndDisplayTranslations(bitmap: Bitmap, overlay: GlobalTranslationOverlay?) {
        try {
            if (bitmap.isRecycled) return
            val sourceLang = settingsManager.getSourceLanguageCode() ?: "vi"
            val ocrResult = withContext(Dispatchers.IO) { ocrManager.recognizeTextFromBitmap(bitmap, sourceLang) }
            overlay?.hideLoading()

            val blocksToTranslate = ocrResult.textBlocks.filter { it.text.isNotBlank() && it.boundingBox != null }
            if (blocksToTranslate.isEmpty()) {
                Toast.makeText(this, "Không tìm thấy văn bản nào.", Toast.LENGTH_SHORT).show()
                return
            }

            val delimiter = "\n\n"
            val combinedText = blocksToTranslate.joinToString(separator = delimiter) { it.text }
            val targetLang = settingsManager.getTargetLanguageCode() ?: "en"
            val transSource = settingsManager.getTranslationSource()
            val translationResult = withContext(Dispatchers.IO) { translationManager.translate(combinedText, sourceLang, targetLang, transSource) }

            translationResult.onSuccess { translatedText ->
                val translatedBlocks = translatedText.split(delimiter)
                if (blocksToTranslate.size == translatedBlocks.size) {
                    blocksToTranslate.zip(translatedBlocks).forEach { (originalBlock, translatedString) ->
                        displaySingleGlobalResult(originalBlock.boundingBox!!, translatedString, overlay)
                    }
                } else {
                    Toast.makeText(this, "Lỗi đồng bộ kết quả dịch.", Toast.LENGTH_LONG).show()
                }
            }.onFailure { error ->
                Toast.makeText(this, "Lỗi: ${error.message}", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing translations", e)
        } finally {
            bitmap.recycle()
        }
    }

    private fun displaySingleGlobalResult(boundingBox: Rect, text: String, overlay: GlobalTranslationOverlay?) {
        val resultView = TranslationResultView(this@OverlayService).apply { updateText(text) }
        val paddingDp = 2f
        val paddingPx = (paddingDp * resources.displayMetrics.density).toInt()
        val viewWidth = boundingBox.width() + (paddingPx * 2)
        val viewHeight = boundingBox.height() + (paddingPx * 2)
        val viewLeft = boundingBox.centerX() - (viewWidth / 2)
        val viewTop = boundingBox.centerY() - (viewHeight / 2)
        val params = FrameLayout.LayoutParams(viewWidth, viewHeight).apply {
            leftMargin = viewLeft
            topMargin = viewTop
        }
        overlay?.addResultView(resultView, params)
    }
    //</editor-fold>

    //<editor-fold desc="Service & System Interaction">
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

    private fun stopService() {
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
            removeMagnifierViews()
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

    private suspend fun captureScreen(): Bitmap? = suspendCancellableCoroutine { continuation ->
        if (mediaProjection == null) {
            if (continuation.isActive) continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val width: Int
        val height: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            width = bounds.width()
            height = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            @Suppress("DEPRECATION")
            val size = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            width = size.x
            height = size.y
        }
        if (width <= 0 || height <= 0) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        val density = resources.displayMetrics.densityDpi
        imageReader?.close()
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        val virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, handler
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = try { reader.acquireLatestImage() } catch (e: Exception) { null }
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width

                var bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()

                val finalBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                if (bitmap != finalBitmap) bitmap.recycle()

                virtualDisplay?.release()
                reader.close()
                imageReader = null

                if (continuation.isActive) {
                    continuation.resume(finalBitmap)
                } else {
                    finalBitmap.recycle()
                }
            } else {
                virtualDisplay?.release()
                reader.close()
                imageReader = null
                if (continuation.isActive) continuation.resume(null)
            }
        }, handler)

        continuation.invokeOnCancellation {
            virtualDisplay?.release()
            imageReader?.close()
            imageReader = null
        }
    }

    private fun initializeMediaProjection(resultCode: Int, data: Intent) {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopService()
                }
            }, handler)
        } catch (e: Exception) {
            stopService()
        }
    }
    //</editor-fold>

    //<editor-fold desc="Quản lý UI & View">
    private data class LensDetails(val iconTopLeft: Point, val scanCenter: Point, val debugTopLeft: Point, val debugSize: Int)

    private fun calculateLensDetails(handleX: Int, handleY: Int): LensDetails {
        val handleSize = resources.getDimensionPixelSize(R.dimen.bubble_size)
        val handleCenterX = handleX + handleSize / 2
        val handleCenterY = handleY + handleSize / 2

        // Vì LENS_OFFSET_DISTANCE = 0, tâm quét chính là tâm của tay cầm
        val scanCenterX = handleCenterX
        val scanCenterY = handleCenterY - statusBarHeight

        // Tính vị trí cho icon ic_search
        val visualCenterRatio = 9.5f / 24f
        val iconTopLeftX = scanCenterX - (LENS_SIZE * visualCenterRatio).toInt()
        val iconTopLeftY = scanCenterY - (LENS_SIZE * visualCenterRatio).toInt()

        // <<< SỬA LỖI TẠI ĐÂY
        // Tính bán kính và kích thước của vòng tròn debug dựa trên tỷ lệ thật của icon
        val scanRadiusRatio = 6.5f / 24f
        val scanRadius = (LENS_SIZE * scanRadiusRatio).toInt()
        val debugTopLeftX = scanCenterX - scanRadius
        val debugTopLeftY = scanCenterY - scanRadius
        val debugSize = scanRadius * 2

        return LensDetails(Point(iconTopLeftX, iconTopLeftY), Point(scanCenterX, scanCenterY), Point(debugTopLeftX, debugTopLeftY), debugSize)
    }

    private fun showFloatingBubble() {
        val themedContext = ContextThemeWrapper(this, R.style.Theme_AppTranslate_NoActionBar)
        floatingBubbleView = FloatingBubbleView(themedContext, serviceScope).apply {
            listener = this@OverlayService
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 100
        }

        floatingBubbleView?.setViewLayoutParams(params)
        windowManager.addView(floatingBubbleView, params)
    }

    private fun showGlobalOverlay(): GlobalTranslationOverlay? {
        if (globalOverlay != null) return globalOverlay
        val overlay = GlobalTranslationOverlay(this, windowManager).apply {
            onDismiss = {
                globalOverlay = null
                setState(ServiceState.IDLE)
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(overlay, params)
        globalOverlay = overlay
        return overlay
    }

    private fun removeGlobalOverlay() {
        globalOverlay?.let {
            if (it.isAttachedToWindow) windowManager.removeView(it)
        }
        globalOverlay = null
    }

    private fun removeAllMagnifierResults() {
        magnifierResultViews.forEach { if (it.isAttachedToWindow) windowManager.removeView(it) }
        magnifierResultViews.clear()
    }

    private fun showSingleMagnifierResult(position: Rect): TranslationResultView {
        val resultView = TranslationResultView(this)
        val paddingDp = 2f
        val paddingPx = (paddingDp * resources.displayMetrics.density).toInt()
        val viewWidth = position.width() + (paddingPx * 2)
        val viewHeight = position.height() + (paddingPx * 2)
        val viewLeft = position.centerX() - (viewWidth / 2)
        val viewTop = position.centerY() - (viewHeight / 2)
        val resultViewParams = WindowManager.LayoutParams(
            viewWidth, viewHeight,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = viewLeft
            y = viewTop
        }
        windowManager.addView(resultView, resultViewParams)
        magnifierResultViews.add(resultView)
        return resultView
    }

    private fun showDismissOverlay() {
        if (dismissOverlay != null) return
        val overlay = FrameLayout(this).apply { setOnClickListener { setState(ServiceState.IDLE) } }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(overlay, params)
        dismissOverlay = overlay
    }

    private fun removeDismissOverlay() {
        dismissOverlay?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        dismissOverlay = null
    }

    private fun showMagnifierViews() {
        if (magnifierLensView != null || floatingBubbleView == null) return

        val bubbleParams = (floatingBubbleView!!.layoutParams as WindowManager.LayoutParams)
        val lensDetails = calculateLensDetails(bubbleParams.x, bubbleParams.y)

        magnifierLensView = ImageView(ContextThemeWrapper(this, R.style.Theme_AppTranslate_NoActionBar)).apply {
            setImageResource(R.drawable.ic_search)
        }
        val lensParams = WindowManager.LayoutParams(
            LENS_SIZE, LENS_SIZE,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = lensDetails.iconTopLeft.x
            y = lensDetails.iconTopLeft.y
        }
        windowManager.addView(magnifierLensView, lensParams)

        scanAreaDebugView = View(ContextThemeWrapper(this, R.style.Theme_AppTranslate_NoActionBar)).apply {
            setBackgroundResource(R.drawable.scan_area_background)
        }
        val debugParams = WindowManager.LayoutParams(
            lensDetails.debugSize, lensDetails.debugSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = lensDetails.debugTopLeft.x
            y = lensDetails.debugTopLeft.y
        }
        windowManager.addView(scanAreaDebugView, debugParams)
    }

    private fun updateMagnifierViewsPosition(lensDetails: LensDetails) {
        magnifierLensView?.let { lens ->
            val params = lens.layoutParams as WindowManager.LayoutParams
            params.x = lensDetails.iconTopLeft.x
            params.y = lensDetails.iconTopLeft.y
            windowManager.updateViewLayout(lens, params)
        }
        scanAreaDebugView?.let { debugView ->
            val params = debugView.layoutParams as WindowManager.LayoutParams
            params.x = lensDetails.debugTopLeft.x
            params.y = lensDetails.debugTopLeft.y
            windowManager.updateViewLayout(debugView, params)
        }
    }

    private fun removeMagnifierViews() {
        magnifierLensView?.let {
            if (it.isAttachedToWindow) windowManager.removeView(it)
        }
        magnifierLensView = null
        scanAreaDebugView?.let {
            if (it.isAttachedToWindow) windowManager.removeView(it)
        }
        scanAreaDebugView = null
    }

    private fun checkCircleRectIntersection(circleCenterX: Int, circleCenterY: Int, radius: Int, rect: Rect): Boolean {
        val closestX = Math.max(rect.left, Math.min(circleCenterX, rect.right))
        val closestY = Math.max(rect.top, Math.min(circleCenterY, rect.bottom))

        val distanceX = circleCenterX - closestX
        val distanceY = circleCenterY - closestY
        val distanceSquared = (distanceX * distanceX) + (distanceY * distanceY)

        return distanceSquared < (radius * radius)
    }

    private fun showOcrDebugBox(position: Rect) {
        val debugView = View(this).apply {
            setBackgroundResource(R.drawable.debug_bounding_box)
        }
        val params = WindowManager.LayoutParams(
            position.width(),
            position.height(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = position.left
            y = position.top - statusBarHeight
        }
        windowManager.addView(debugView, params)
        ocrDebugViews.add(debugView)
    }

    private fun removeAllOcrDebugViews() {
        ocrDebugViews.forEach { if (it.isAttachedToWindow) windowManager.removeView(it) }
        ocrDebugViews.clear()
    }
    //</editor-fold>

    //<editor-fold desc="Quản lý Notification">
    private fun hasOverlayPermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

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
    //</editor-fold>
}