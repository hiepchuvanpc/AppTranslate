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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.apptranslate.R
import com.example.apptranslate.data.SettingsManager
import com.example.apptranslate.data.TranslationManager
import com.example.apptranslate.ocr.OcrManager
import com.example.apptranslate.ui.overlay.*
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCancellableCoroutine

// ✨ Implement BubbleViewListener để xử lý sự kiện từ bubble ✨
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
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())

    // ✨ Quản lý trạng thái tập trung tại Service ✨
    private enum class ServiceState { IDLE, PANEL_OPEN, MAGNIFIER, MOVING_PANEL }
    private var currentState = ServiceState.IDLE
    private var liveTranslationJob: Job? = null
    private var lastOcrText: String? = null

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

    // --- Service Actions Handling ---

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
            floatingBubbleView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
            removeResultView()
            mediaProjection?.stop()
            ocrManager.close() // ✨ Giải phóng tài nguyên OCR ✨
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

    // --- UI Management ---

    private fun showFloatingBubble() {
        val themedContext = ContextThemeWrapper(this, R.style.Theme_AppTranslate_NoActionBar)
        floatingBubbleView = FloatingBubbleView(themedContext, serviceScope).apply {
            listener = this@OverlayService // ✨ Gán listener là Service này ✨
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
        resultViewParams?.apply {
            x = positionRect.left
            y = positionRect.bottom // Hiển thị ngay dưới vùng văn bản
        }
        resultView?.showLoading()
        windowManager.updateViewLayout(resultView, resultViewParams)
    }

    private fun removeResultView() {
        try {
            resultView?.let {
                if (it.isAttachedToWindow) {
                    windowManager.removeView(it)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing result view", e)
        } finally {
            resultView = null
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

    override fun onBubbleLongPressed() {
        setState(ServiceState.MAGNIFIER)
    }

    override fun onDragStarted() {
        if (currentState == ServiceState.IDLE) {
            setState(ServiceState.MAGNIFIER)
        }
    }

    override fun onDragFinished() {
        if (currentState == ServiceState.MAGNIFIER) {
            stopLiveTranslation()
            setState(ServiceState.IDLE)
        }
    }

    override fun onFunctionClicked(functionId: String) {
        Toast.makeText(this, "Chức năng: $functionId", Toast.LENGTH_SHORT).show()
        // TODO: Triển khai logic cho từng chức năng ở đây
        setState(ServiceState.IDLE)
    }

    override fun onLanguageSelectClicked() {
        sendBroadcast(Intent(ACTION_SHOW_LANGUAGE_SHEET).setPackage(packageName))
        setState(ServiceState.IDLE)
    }

    override fun onHomeClicked() {
        // Có thể mở lại MainActivity thay vì đóng service
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        setState(ServiceState.IDLE)
    }

    override fun onMoveClicked() {
        setState(ServiceState.MOVING_PANEL)
    }

    // --- State Machine ---

    private fun setState(newState: ServiceState) {
        if (currentState == newState) return
        Log.d(TAG, "State changing from $currentState to $newState")
        currentState = newState

        when (newState) {
            ServiceState.IDLE -> {
                floatingBubbleView?.closePanel()
                floatingBubbleView?.updateBubbleAppearance(BubbleAppearance.NORMAL)
                stopLiveTranslation()
            }
            ServiceState.PANEL_OPEN -> {
                floatingBubbleView?.openPanel()
            }
            ServiceState.MAGNIFIER -> {
                floatingBubbleView?.closePanel() // Đảm bảo panel đã đóng
                floatingBubbleView?.updateBubbleAppearance(BubbleAppearance.MAGNIFIER)
                startLiveTranslation()
            }
            ServiceState.MOVING_PANEL -> {
                floatingBubbleView?.closePanel()
                floatingBubbleView?.updateBubbleAppearance(BubbleAppearance.MOVING)
            }
        }
    }

    // --- OCR & Translation Logic ---

    private fun startLiveTranslation() {
        if (liveTranslationJob?.isActive == true) return
        lastOcrText = null
        liveTranslationJob = serviceScope.launch {
            while (isActive) {
                val bubbleRect = floatingBubbleView?.let {
                    val location = IntArray(2)
                    it.getLocationOnScreen(location)
                    // Tạo một vùng nhỏ hơn xung quanh bubble để quét
                    val scanSize = 200 // Kích thước vùng quét
                    Rect(location[0] - scanSize/2, location[1] - scanSize/2, location[0] + it.width + scanSize/2, location[1] + it.height + scanSize/2)
                }
                if (bubbleRect != null) {
                    processScreen(bubbleRect)
                }
                delay(500) // Tăng delay để tiết kiệm pin
            }
        }
    }

    private fun stopLiveTranslation() {
        liveTranslationJob?.cancel()
        liveTranslationJob = null
        removeResultView()
    }

    private suspend fun processScreen(scanRect: Rect) {
        val screenBitmap = captureScreen() ?: return
        // Cắt ảnh theo vùng quét để xử lý nhanh hơn
        val croppedBitmap = cropBitmap(screenBitmap, scanRect)
        screenBitmap.recycle()

        if (croppedBitmap.width <= 1 || croppedBitmap.height <= 1) {
            croppedBitmap.recycle()
            return
        }

        val sourceLang = settingsManager.getSourceLanguageCode() ?: "vi"
        try {
            val ocrResult = ocrManager.recognizeTextFromBitmap(croppedBitmap, sourceLang)
            croppedBitmap.recycle()

            val detectedText = ocrResult.fullText.trim()
            if (detectedText.isNotBlank() && detectedText != lastOcrText) {
                lastOcrText = detectedText

                // Lấy bounding box của khối text đầu tiên để định vị
                val firstBlock = ocrResult.textBlocks.firstOrNull() ?: return
                val textBoundingBox = firstBlock.boundingBox ?: return

                // Chuyển tọa độ của text box về tọa độ toàn màn hình
                val globalTextRect = Rect(
                    scanRect.left + textBoundingBox.left,
                    scanRect.top + textBoundingBox.top,
                    scanRect.left + textBoundingBox.right,
                    scanRect.top + textBoundingBox.bottom
                )

                showResultView(globalTextRect)
                val targetLang = settingsManager.getTargetLanguageCode() ?: "en"
                val transSource = settingsManager.getTranslationSource()
                val translationResult = translationManager.translate(detectedText, sourceLang, targetLang, transSource)
                resultView?.updateText(translationResult.getOrDefault("Lỗi dịch"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "OCR or Translation failed", e)
            croppedBitmap.recycle()
        }
    }

    private fun cropBitmap(source: Bitmap, rect: Rect): Bitmap {
        // Đảm bảo vùng cắt không ra ngoài bitmap
        val safeRect = Rect(
            maxOf(0, rect.left),
            maxOf(0, rect.top),
            minOf(source.width, rect.right),
            minOf(source.height, rect.bottom)
        )
        if (safeRect.width() <= 0 || safeRect.height() <= 0) {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
        return Bitmap.createBitmap(source, safeRect.left, safeRect.top, safeRect.width(), safeRect.height())
    }


    // --- Media Projection & Helpers ---

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

    private suspend fun captureScreen(): Bitmap? = suspendCancellableCoroutine { continuation ->
        if (mediaProjection == null || imageReader != null) {
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
            imageReader?.surface, null, null
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width

                val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()

                // Dọn dẹp ngay lập tức
                virtualDisplay?.release()
                reader.close()
                imageReader = null

                if (continuation.isActive) {
                    // Cắt bỏ phần padding nếu có
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