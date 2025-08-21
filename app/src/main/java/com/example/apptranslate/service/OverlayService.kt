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
import android.graphics.Rect
// ✨ [SỬA LỖI] Thêm import còn thiếu ✨
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.apptranslate.R
import com.example.apptranslate.data.SettingsManager
import com.example.apptranslate.data.TranslationManager
import com.example.apptranslate.ocr.OcrManager
import com.example.apptranslate.ocr.OcrResult
import com.example.apptranslate.ui.overlay.*
import kotlinx.coroutines.*
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import android.graphics.Point
import android.view.Display
import android.view.WindowInsets

@SuppressLint("ViewConstructor")
class OverlayService : Service(), BubbleViewListener {

    companion object {
        private const val TAG = "OverlayService"
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
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "overlay_service_channel"
        var isRunning = false
            private set
        private const val MAGNIFIER_ICON_SCALE = 2.0f
        private const val ICON_VIEWPORT_SIZE = 24f
        private const val HANDLE_PIVOT_X_RATIO = 17f / 24f
        private const val HANDLE_PIVOT_Y_RATIO = 24f / 24f // Dùng tọa độ Y đã sửa
        private const val LENS_OFFSET_X_UNITS = 9.5f - 17f
        private const val LENS_OFFSET_Y_UNITS = 9.5f - 24f
    }

    private lateinit var windowManager: WindowManager
    private lateinit var settingsManager: SettingsManager
    private val ocrManager by lazy { OcrManager.getInstance() }
    private lateinit var translationManager: TranslationManager
    private var dismissOverlay: View? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var floatingBubbleView: FloatingBubbleView? = null
    private var globalOverlay: GlobalTranslationOverlay? = null
    private val magnifierResultViews = mutableListOf<View>()

    private var mediaProjection: MediaProjection? = null
    private val handler = Handler(Looper.getMainLooper())

    private var magnifierJob: Job? = null
    private var ocrJob: Job? = null
    @Volatile
    private var screenTextMap: List<OcrResult.Block> = emptyList()
    private var lastTranslatedBlock: OcrResult.Block? = null
    private var imageReader: ImageReader? = null
    private var lastScreenshotHash = 0
    private val translationCache = mutableMapOf<String, String>()

    private val translationSemaphore = kotlinx.coroutines.sync.Semaphore(3)

    private enum class ServiceState {
        IDLE,
        PANEL_OPEN,
        MAGNIFIER,
        MOVING_PANEL,
        GLOBAL_TRANSLATE_ACTIVE,
        MAGNIFIER_RESULT_VISIBLE // <-- Thêm trạng thái này
    }
    private var currentState = ServiceState.IDLE

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
            ACTION_STOP_SERVICE -> stopService()
            ACTION_UPDATE_LANGUAGES -> handleUpdateLanguages(intent)
        }
        return START_NOT_STICKY // Sử dụng START_NOT_STICKY để service không tự khởi động lại
    }

    override fun onDestroy() {
        stopService()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun setState(newState: ServiceState) {
        if (currentState == newState) return
        Log.d(TAG, "State changing from $currentState to $newState")

        // --- Xử lý logic KHI RỜI KHỎI trạng thái cũ ---
        when (currentState) {
            ServiceState.MAGNIFIER -> stopMagnifierLoop() // Khi rời chế độ lúp, chỉ dừng quét
            ServiceState.GLOBAL_TRANSLATE_ACTIVE -> removeGlobalOverlay()
            ServiceState.PANEL_OPEN -> removeDismissOverlay()
            ServiceState.MAGNIFIER_RESULT_VISIBLE -> removeDismissOverlay()
            else -> {}
        }

        currentState = newState

        // --- Xử lý logic KHI BƯỚC VÀO trạng thái mới ---
        when (newState) {
            ServiceState.IDLE -> {
                floatingBubbleView?.closePanel()
                floatingBubbleView?.updateBubbleAppearance(BubbleAppearance.NORMAL)
                floatingBubbleView?.visibility = View.VISIBLE
                ocrJob?.cancel()
                screenTextMap = emptyList()
                removeAllMagnifierResults() // Khi về IDLE, xóa mọi kết quả còn sót lại
            }
            ServiceState.PANEL_OPEN -> {
                floatingBubbleView?.openPanel()
                showDismissOverlay()
            }
            ServiceState.MAGNIFIER -> {
                removeAllMagnifierResults() // Xóa kết quả cũ trước khi bắt đầu lúp mới
                floatingBubbleView?.closePanel()
                floatingBubbleView?.updateBubbleAppearance(BubbleAppearance.MAGNIFIER)
                startMagnifierTracking()
                triggerFullScreenOcr()
            }
            ServiceState.MOVING_PANEL -> {
                floatingBubbleView?.closePanel()
                floatingBubbleView?.updateBubbleAppearance(BubbleAppearance.MOVING)
            }
            ServiceState.GLOBAL_TRANSLATE_ACTIVE -> {
                floatingBubbleView?.closePanel()
                performGlobalTranslate()
            }
            ServiceState.MAGNIFIER_RESULT_VISIBLE -> {
                // Kết quả đã có trên màn hình, chỉ cần trả bubble về trạng thái bình thường
                floatingBubbleView?.updateBubbleAppearance(BubbleAppearance.NORMAL)
                showDismissOverlay() // Hiển thị lớp phủ đóng khi có kết quả
            }
        }
    }

    private fun performGlobalTranslate() = serviceScope.launch {
        // 1. Ẩn panel nổi
        floatingBubbleView?.visibility = View.GONE

        // ✨ GIẢI PHÁP: Thêm một khoảng trễ nhỏ để đảm bảo UI đã được cập nhật ✨
        delay(100) // Đợi 100ms

        // 2. Chụp ảnh màn hình sau khi panel đã thực sự biến mất
        val screenBitmap = captureScreen() ?: run {
            Toast.makeText(this@OverlayService, "Không thể chụp màn hình", Toast.LENGTH_SHORT).show()
            // Quan trọng: Hiển thị lại bubble nếu quá trình chụp ảnh thất bại
            setState(ServiceState.IDLE)
            return@launch
        }

        val overlay = showGlobalOverlay()
        overlay?.showLoading()

        val ocrResult = withContext(Dispatchers.IO) {
            ocrManager.recognizeTextFromBitmap(screenBitmap, settingsManager.getSourceLanguageCode() ?: "vi")
        }
        screenBitmap.recycle()

        overlay?.hideLoading()

        if (ocrResult.textBlocks.isEmpty()) {
            Toast.makeText(this@OverlayService, "Không tìm thấy văn bản nào.", Toast.LENGTH_SHORT).show()
            // Thêm dòng này để đóng overlay và hiển thị lại bubble nếu không có text
            removeGlobalOverlay()
            setState(ServiceState.IDLE)
            return@launch
        }

        val sourceLang = settingsManager.getSourceLanguageCode() ?: "vi"
        val targetLang = settingsManager.getTargetLanguageCode() ?: "en"
        val transSource = settingsManager.getTranslationSource()

        ocrResult.textBlocks.forEach { block ->
            val text = block.text.trim()
            val boundingBox = block.boundingBox

            if (text.isNotBlank() && boundingBox != null) {
                launch(Dispatchers.IO) {
                    translationSemaphore.acquire()
                    try {
                        val translation = translationManager.translate(text, sourceLang, targetLang, transSource)
                        withContext(Dispatchers.Main) {
                            val resultView = TranslationResultView(this@OverlayService).apply {
                                updateText(translation.getOrDefault("..."))
                            }

                            // ✨ THÊM LỀ ẢO 2DP ĐỂ BOX TO HƠN ✨
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
                    } finally {
                        translationSemaphore.release()
                    }
                }
            }
        }
    }

    private fun triggerFullScreenOcr() {
        ocrJob?.cancel()
        ocrJob = serviceScope.launch(Dispatchers.IO) {
            val screenBitmap = captureScreen() ?: return@launch
            val currentHash = calculateBitmapContentHash(screenBitmap)
            if (currentHash != lastScreenshotHash) {
                lastScreenshotHash = currentHash

                val (processedBitmap, scaleFactor) = preprocessBitmapForOcr(screenBitmap)
                screenBitmap.recycle()

                val sourceLang = settingsManager.getSourceLanguageCode() ?: "vi"
                val ocrResult = ocrManager.recognizeTextFromBitmap(processedBitmap, sourceLang)
                processedBitmap.recycle()

                screenTextMap = ocrResult.textBlocks.map { block ->
                    block.boundingBox?.let { box ->
                        val adjustedBox = Rect(
                            (box.left / scaleFactor).toInt(), (box.top / scaleFactor).toInt(),
                            (box.right / scaleFactor).toInt(), (box.bottom / scaleFactor).toInt()
                        )
                        block.copy(boundingBox = adjustedBox)
                    } ?: block
                }
                Log.d(TAG, "OCR Completed. Found ${screenTextMap.size} text blocks.")
            } else {
                 screenBitmap.recycle()
                 Log.d(TAG, "Screen content unchanged, skipping OCR.")
            }
        }
    }

    private fun startMagnifierTracking() {
        if (magnifierJob?.isActive == true) return

        magnifierJob = serviceScope.launch {
            // Đảm bảo lần quét OCR đầu tiên phải hoàn thành
            ocrJob?.join()
            Log.d(TAG, "Initial OCR scan finished. Starting magnifier tracking.")

            while (isActive && currentState == ServiceState.MAGNIFIER) {
                floatingBubbleView?.let { bubble ->
                    // Lấy vị trí hiện tại của bubble trên màn hình
                    val bubbleLocation = IntArray(2)
                    bubble.getLocationOnScreen(bubbleLocation)

                    // ✨ GIẢI PHÁP: Điểm quét chính là TÂM của bubble ✨
                    // Điều này khớp với vị trí ngón tay của người dùng.
                    val scanTargetX = bubbleLocation[0] + bubble.width / 2
                    val scanTargetY = bubbleLocation[1] + bubble.height / 2

                    // Tìm và dịch văn bản tại điểm quét
                    findAndTranslateTextAt(scanTargetX, scanTargetY)
                }
                delay(100) // Tần suất quét
            }
        }
    }

    private fun stopMagnifierLoop() {
        magnifierJob?.cancel()
        magnifierJob = null
        lastTranslatedBlock = null
    }

    private suspend fun findAndTranslateTextAt(targetX: Int, targetY: Int) {
        val targetBlock = screenTextMap.find { it.boundingBox?.contains(targetX, targetY) ?: false }

        if (targetBlock == null) {
            if (lastTranslatedBlock != null) {
                lastTranslatedBlock = null
                removeAllMagnifierResults()
            }
            return
        }

        if (targetBlock == lastTranslatedBlock) return

        lastTranslatedBlock = targetBlock
        val currentText = targetBlock.text.trim()

        if (currentText.isBlank() || targetBlock.boundingBox == null) {
            removeAllMagnifierResults()
            return
        }

        removeAllMagnifierResults()
        val resultView = showSingleMagnifierResult(targetBlock.boundingBox)
        resultView.showLoading()

        serviceScope.launch(Dispatchers.IO) {
            delay(100)
            val cachedTranslation = translationCache[currentText]
            if (cachedTranslation != null) {
                withContext(Dispatchers.Main) { resultView.updateText(cachedTranslation) }
                return@launch
            }

            val sourceLang = settingsManager.getSourceLanguageCode() ?: "vi"
            val targetLang = settingsManager.getTargetLanguageCode() ?: "en"
            val transSource = settingsManager.getTranslationSource()

            val translationResult = translationManager.translate(currentText, sourceLang, targetLang, transSource)
            val translatedText = translationResult.getOrDefault("Lỗi dịch")
            translationCache[currentText] = translatedText

            withContext(Dispatchers.Main) {
                resultView.updateText(translatedText)
            }
        }
    }

    private fun handleStartService(intent: Intent) {
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
            Log.e(TAG, "Failed to get MediaProjection permission.")
            sendBroadcast(Intent(BROADCAST_SERVICE_ERROR).setPackage(packageName))
            stopSelf()
        }
    }

    private fun handleUpdateLanguages(intent: Intent) {
        val sourceCode = intent.getStringExtra(EXTRA_SOURCE_LANG) ?: "vi"
        val targetCode = intent.getStringExtra(EXTRA_TARGET_LANG) ?: "en"
        val displayText = "${sourceCode.uppercase()} → ${targetCode.uppercase()}"
        floatingBubbleView?.updateLanguageDisplay(displayText)
    }

    private fun stopService() {
        if (!isRunning) return
        isRunning = false
        try {
            ocrJob?.cancel()
            magnifierJob?.cancel()
            removeGlobalOverlay()
            floatingBubbleView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
            removeAllMagnifierResults()
            mediaProjection?.stop()
            ocrManager.close()
            serviceScope.cancel()
            System.gc()
        } catch (e: Exception) {
            Log.e(TAG, "Error during service stop", e)
        } finally {
            floatingBubbleView = null
            mediaProjection = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            sendBroadcast(Intent(BROADCAST_SERVICE_STOPPED).setPackage(packageName))
        }
    }

    // File: app/src/main/java/com/example/apptranslate/service/OverlayService.kt

    private fun showGlobalOverlay(): GlobalTranslationOverlay? {
        val overlay = GlobalTranslationOverlay(this, windowManager).apply {
            onDismiss = {
                globalOverlay = null
                setState(ServiceState.IDLE)
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            // ✨ ĐÃ XÓA FLAG GÂY XUNG ĐỘT ✨
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(overlay, params)
        globalOverlay = overlay
        return overlay
    }

    private fun removeGlobalOverlay() {
        try {
            globalOverlay?.let { overlay ->
                if (overlay.isAttachedToWindow) {
                    windowManager.removeView(overlay)
                }
            }
            globalOverlay = null
        } catch (e: Exception) {
            Log.e(TAG, "Error removing global overlay", e)
        }
    }

    override fun onBubbleTapped() {
        when (currentState) {
            ServiceState.PANEL_OPEN, ServiceState.MAGNIFIER_RESULT_VISIBLE -> {
                // Nếu panel đang mở, hoặc đang xem kết quả lúp, chạm vào bubble sẽ về IDLE
                setState(ServiceState.IDLE)
            }
            else -> {
                // Mặc định, chạm vào bubble sẽ mở panel
                setState(ServiceState.PANEL_OPEN)
            }
        }
    }

    override fun onBubbleLongPressed() = setState(ServiceState.MAGNIFIER)

    override fun onDragStarted() {
        if (currentState == ServiceState.IDLE) {
            setState(ServiceState.MAGNIFIER)
        }
    }

    override fun onDragFinished() {
        if (currentState == ServiceState.MAGNIFIER) {
            // Khi thả tay ở chế độ lúp, chuyển sang trạng thái hiển thị kết quả
            setState(ServiceState.MAGNIFIER_RESULT_VISIBLE)
            triggerFullScreenOcr() // Vẫn quét nền để chuẩn bị cho lần tương tác sau
        } else if (currentState == ServiceState.MOVING_PANEL) {
            setState(ServiceState.IDLE)
            triggerFullScreenOcr()
        }
    }

    override fun onLanguageSelectClicked() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            action = "ACTION_SHOW_LANGUAGE_SHEET"
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
        setState(ServiceState.MOVING_PANEL)
    }

    override fun onFunctionClicked(functionId: String) {
        if (functionId == "GLOBAL") {
            setState(ServiceState.GLOBAL_TRANSLATE_ACTIVE)
        } else {
            setState(ServiceState.IDLE)
            Toast.makeText(this, "Chức năng '$functionId' chưa được triển khai", Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeAllMagnifierResults() {
        magnifierResultViews.forEach { view ->
            try {
                if (view.isAttachedToWindow) windowManager.removeView(view)
            } catch (e: Exception) { /* Ignore */ }
        }
        magnifierResultViews.clear()
    }

    @SuppressLint("InflateParams")
    private fun showSingleMagnifierResult(position: Rect): TranslationResultView {
        val resultView = TranslationResultView(this)

        // ✨ THÊM LỀ ẢO 2DP ĐỂ BOX TO HƠN ✨
        val paddingDp = 2f
        val paddingPx = (paddingDp * resources.displayMetrics.density).toInt()

        val viewWidth = position.width() + (paddingPx * 2)
        val viewHeight = position.height() + (paddingPx * 2)

        val viewLeft = position.centerX() - (viewWidth / 2)
        val viewTop = position.centerY() - (viewHeight / 2)

        val resultViewParams = WindowManager.LayoutParams(
            viewWidth,
            viewHeight,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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
            x = 100; y = 200
        }

        floatingBubbleView?.setViewLayoutParams(params)
        windowManager.addView(floatingBubbleView, params)
    }

    private fun preprocessBitmapForOcr(bitmap: Bitmap): Pair<Bitmap, Float> {
        val maxDimension = 2560
        val minDimension = 1080

        val scaleUpFactor = if (bitmap.width < minDimension || bitmap.height < minDimension) 2.0f else 1.0f
        val baseBitmap = if (scaleUpFactor > 1.0f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scaleUpFactor).toInt(), (bitmap.height * scaleUpFactor).toInt(), true)
        } else {
            bitmap
        }

        val scaleFactor = if (baseBitmap.width > maxDimension || baseBitmap.height > maxDimension) {
            minOf(maxDimension.toFloat() / baseBitmap.width, maxDimension.toFloat() / baseBitmap.height)
        } else {
            1.0f
        }
        val scaledBitmap = if (scaleFactor < 1.0f) {
            Bitmap.createScaledBitmap(baseBitmap, (baseBitmap.width * scaleFactor).toInt(), (baseBitmap.height * scaleFactor).toInt(), true)
        } else {
            baseBitmap
        }

        val width = scaledBitmap.width
        val height = scaledBitmap.height
        val processedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        scaledBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val contrast = 1.5f
        val brightness = -30
        for (i in pixels.indices) {
            val c = pixels[i]
            val gray = (Color.red(c) * 0.299 + Color.green(c) * 0.587 + Color.blue(c) * 0.114).toInt()

            // ✨ [SỬA LỖI] Thêm .toFloat() để tránh lỗi nhập nhằng kiểu dữ liệu ✨
            val v = ((gray.toFloat() * contrast) + brightness).coerceIn(0f, 255f).toInt()
            pixels[i] = Color.rgb(v, v, v)
        }

        processedBitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        if (scaledBitmap != bitmap) scaledBitmap.recycle()
        if (baseBitmap != bitmap && baseBitmap != scaledBitmap) baseBitmap.recycle()

        return Pair(processedBitmap, scaleFactor * scaleUpFactor)
    }

    private fun calculateBitmapContentHash(bitmap: Bitmap): Int {
        val width = bitmap.width
        val height = bitmap.height
        val sampleSize = 8

        val digest = MessageDigest.getInstance("MD5")

        for (y in 0 until height step sampleSize) {
            for (x in 0 until width step sampleSize) {
                val pixel = bitmap.getPixel(x, y)
                digest.update(pixel.toByte())
                digest.update((pixel shr 8).toByte())
                digest.update((pixel shr 16).toByte())
                digest.update((pixel shr 24).toByte())
            }
        }

        return digest.digest().contentHashCode()
    }

    private suspend fun captureScreen(): Bitmap? = suspendCancellableCoroutine { continuation ->
        if (mediaProjection == null) {
            if (continuation.isActive) continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        // ✨ GIẢI PHÁP: Lấy kích thước màn hình thực tế ✨
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val width: Int
        val height: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            width = bounds.width()
            height = bounds.height()
        } else {
            val display = windowManager.defaultDisplay
            val size = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            width = size.x
            height = size.y
        }

        val density = resources.displayMetrics.densityDpi

        // Đảm bảo imageReader cũ đã được đóng
        imageReader?.close()
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        val virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width, height, density,
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

                // Cắt lại bitmap về đúng kích thước màn hình nếu có padding
                val finalBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                if (bitmap != finalBitmap) {
                    bitmap.recycle()
                }

                // Dọn dẹp ngay lập tức
                virtualDisplay?.release()
                reader.close()
                imageReader = null

                if (continuation.isActive) {
                    continuation.resume(finalBitmap)
                } else {
                    finalBitmap.recycle()
                }
            } else {
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
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.e(TAG, "MediaProjection was stopped externally. Stopping service.")
                stopService()
            }
        }, handler)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Dịch vụ Nút nổi", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Thông báo cho dịch vụ nút nổi đang chạy"
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

    // Thêm hai hàm mới này vào trong class OverlayService

    private fun showDismissOverlay() {
        if (dismissOverlay != null) return
        // Tạo một FrameLayout trong suốt, toàn màn hình
        val overlay = FrameLayout(this).apply {
            // Lắng nghe sự kiện chạm
            setOnClickListener {
                // Khi chạm vào, đưa app về trạng thái IDLE (sẽ tự động xóa kết quả)
                setState(ServiceState.IDLE)
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            // Flag này cho phép nhận touch nhưng không nhận focus (không cản trở bàn phím, v.v.)
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(overlay, params)
        dismissOverlay = overlay
    }

    private fun removeDismissOverlay() {
        dismissOverlay?.let {
            try {
                if (it.isAttachedToWindow) {
                    windowManager.removeView(it)
                }
            } catch (e: Exception) { /* Bỏ qua lỗi */ }
        }
        dismissOverlay = null
    }
}