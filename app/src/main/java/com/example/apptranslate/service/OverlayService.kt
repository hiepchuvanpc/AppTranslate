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
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
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
import android.view.WindowManager
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
    }

    // --- Core Components ---
    private lateinit var windowManager: WindowManager
    private lateinit var settingsManager: SettingsManager
    private val ocrManager by lazy { OcrManager.getInstance() }
    private lateinit var translationManager: TranslationManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // --- UI Components ---
    private var floatingBubbleView: FloatingBubbleView? = null
    private var resultView: TranslationResultView? = null
    private var resultViewParams: WindowManager.LayoutParams? = null

    // --- Media Projection ---
    private var mediaProjection: MediaProjection? = null
    private val handler = Handler(Looper.getMainLooper())

    // --- KIẾN TRÚC QUÉT MỚI ---
    private var magnifierJob: Job? = null
    private var fullScreenOcrJob: Job? = null
    @Volatile
    private var screenTextMap: List<OcrResult.Block> = emptyList()
    private var lastTranslatedBlock: OcrResult.Block? = null
    private var imageReader: ImageReader? = null

    // --- State Management ---
    private var currentState = ServiceState.IDLE

    // --- Lifecycle Methods ---
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        settingsManager = SettingsManager.getInstance(this)
        translationManager = TranslationManager(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createBasicNotification("Đang khởi động dịch vụ..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVICE -> handleStartService(intent)
            ACTION_STOP_SERVICE -> stopService()
            ACTION_UPDATE_LANGUAGES -> handleUpdateLanguages(intent)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopService()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- Service Actions & State ---

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
            startFullScreenOcrLoop() // Bắt đầu quét nền
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
            fullScreenOcrJob?.cancel()
            magnifierJob?.cancel()
            floatingBubbleView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
            removeResultView()
            mediaProjection?.stop()
            ocrManager.close()
            serviceScope.cancel()
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

    private fun setState(newState: ServiceState) {
        if (currentState == newState) return
        Log.d(TAG, "State changing from $currentState to $newState")

        // Dọn dẹp trạng thái cũ
        if (currentState == ServiceState.MAGNIFIER) {
            stopMagnifierTracking()
        }

        currentState = newState

        // Thiết lập trạng thái mới
        when (newState) {
            ServiceState.IDLE -> {
                floatingBubbleView?.closePanel()
                floatingBubbleView?.updateBubbleAppearance(BubbleAppearance.NORMAL)
            }
            ServiceState.PANEL_OPEN -> {
                floatingBubbleView?.openPanel()
            }
            ServiceState.MAGNIFIER -> {
                floatingBubbleView?.closePanel()
                floatingBubbleView?.updateBubbleAppearance(BubbleAppearance.MAGNIFIER)
                startMagnifierTracking()
            }
            ServiceState.MOVING_PANEL -> {
                floatingBubbleView?.closePanel()
                floatingBubbleView?.updateBubbleAppearance(BubbleAppearance.MOVING)
                // Ở trạng thái này, bubble có thể được kéo đi mà không kích hoạt kính lúp
            }
        }
    }

    // --- BubbleViewListener Implementation ---

    override fun onBubbleTapped() {
        if (currentState == ServiceState.PANEL_OPEN) {
            setState(ServiceState.IDLE)
        } else {
            setState(ServiceState.PANEL_OPEN)
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
            setState(ServiceState.IDLE)
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
        // Tạo Intent để mở lại MainActivity
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(intent)
        // Đóng panel lại
        setState(ServiceState.IDLE)
    }

    // SỬA HÀM NÀY
    override fun onMoveClicked() {
        // Chuyển sang trạng thái di chuyển panel
        setState(ServiceState.MOVING_PANEL)
    }

    override fun onFunctionClicked(functionId: String) {
        setState(ServiceState.IDLE)
        Toast.makeText(this, "Chức năng: $functionId", Toast.LENGTH_SHORT).show()
        // TODO: Implement logic for each function here
    }

    // --- New OCR Architecture ---

    private fun startFullScreenOcrLoop() {
        if (fullScreenOcrJob?.isActive == true) return
        fullScreenOcrJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val screenBitmap = captureScreen() ?: continue
                    val processedBitmap = preprocessBitmapForOcr(screenBitmap)
                    screenBitmap.recycle()

                    val sourceLang = settingsManager.getSourceLanguageCode() ?: "vi"
                    val ocrResult = ocrManager.recognizeTextFromBitmap(processedBitmap, sourceLang)
                    processedBitmap.recycle()

                    screenTextMap = ocrResult.textBlocks
                } catch (e: Exception) {
                    Log.e(TAG, "Error in full screen OCR loop", e)
                }
                delay(1500) // Rescan every 1.5 seconds
            }
        }
    }

    private fun startMagnifierTracking() {
        if (magnifierJob?.isActive == true) return
        magnifierJob = serviceScope.launch {
            while (isActive) {
                floatingBubbleView?.let { bubble ->
                    val location = IntArray(2)
                    bubble.getLocationOnScreen(location)
                    val bubbleCenterX = location[0] + bubble.width / 2
                    val bubbleCenterY = location[1] + bubble.height / 2

                    findAndTranslateTextAt(bubbleCenterX, bubbleCenterY)
                }
                delay(100) // Track pointer 10 times per second
            }
        }
    }

    private suspend fun findAndTranslateTextAt(bubbleX: Int, bubbleY: Int) {
        val targetBlock = screenTextMap.find {
            it.boundingBox?.contains(bubbleX, bubbleY) ?: false
        }

        if (targetBlock == null) {
            return // Do nothing if not pointing at text, keeps the old result visible
        }

        if (targetBlock == lastTranslatedBlock) {
            return // Don't re-translate the same block
        }

        lastTranslatedBlock = targetBlock
        val textToTranslate = targetBlock.text.trim()

        if (textToTranslate.isBlank()) return

        showResultView(targetBlock.boundingBox!!)
        val sourceLang = settingsManager.getSourceLanguageCode() ?: "vi"
        val targetLang = settingsManager.getTargetLanguageCode() ?: "en"
        val transSource = settingsManager.getTranslationSource()

        val translationResult = translationManager.translate(textToTranslate, sourceLang, targetLang, transSource)
        resultView?.updateText(translationResult.getOrDefault("Lỗi dịch"))
    }

    private fun stopMagnifierTracking() {
        magnifierJob?.cancel()
        magnifierJob = null
        lastTranslatedBlock = null
        removeResultView()
    }

    // --- UI & Utility Methods ---

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

    @SuppressLint("InflateParams")
    private fun showResultView(positionRect: Rect) {
        if (resultView == null) {
            resultView = TranslationResultView(this)
            resultViewParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            windowManager.addView(resultView, resultViewParams)
        }

        val paddingInPx = (8 * resources.displayMetrics.density).toInt()

        resultViewParams?.apply {
            x = positionRect.left
            y = positionRect.top - paddingInPx
        }

        resultView?.showLoading()
        windowManager.updateViewLayout(resultView, resultViewParams)
    }

    private fun removeResultView() {
        try {
            resultView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing result view", e)
        } finally {
            resultView = null
        }
    }

    private fun preprocessBitmapForOcr(bitmap: Bitmap): Bitmap {
        val grayscaleBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(grayscaleBitmap)
        val paint = Paint()
        val colorMatrix = ColorMatrix().apply { setSaturation(0f) }
        val scale = 2f
        val translate = -(scale - 1f) * 128f
        val contrastMatrix = ColorMatrix(floatArrayOf(
            scale, 0f, 0f, 0f, translate,
            0f, scale, 0f, 0f, translate,
            0f, 0f, scale, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        ))
        colorMatrix.postConcat(contrastMatrix)
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return grayscaleBitmap
    }

    private suspend fun captureScreen(): Bitmap? = suspendCancellableCoroutine { continuation ->
        if (mediaProjection == null || imageReader?.surface != null) {
            if (continuation.isActive) continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

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

                val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()

                virtualDisplay?.release()
                reader.close()
                imageReader = null

                if (continuation.isActive) {
                    val finalBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                    bitmap.recycle()
                    continuation.resume(finalBitmap)
                } else {
                    bitmap.recycle()
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
}