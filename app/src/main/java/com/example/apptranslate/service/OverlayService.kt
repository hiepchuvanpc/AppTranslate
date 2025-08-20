// File: app/src/main/java/com/example/apptranslate/service/OverlayService.kt

package com.example.apptranslate.service

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.*
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
import androidx.core.app.NotificationCompat
import com.example.apptranslate.R
import com.example.apptranslate.data.SettingsManager
import com.example.apptranslate.data.TranslationManager
import com.example.apptranslate.ocr.OcrManager
import com.example.apptranslate.ui.overlay.FloatingBubbleView
import com.example.apptranslate.ui.overlay.TranslationResultView
import kotlinx.coroutines.*
import kotlin.coroutines.cancellable
import kotlin.coroutines.resume

class OverlayService : Service() {

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
    private var mediaProjectionResultCode: Int = 0
    private var mediaProjectionData: Intent? = null

    // --- Live Translation State ---
    private var liveTranslationJob: Job? = null
    private var lastOcrText: String? = null

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
            ACTION_START_SERVICE -> {
                mediaProjectionResultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                mediaProjectionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                if (mediaProjectionResultCode == Activity.RESULT_OK && mediaProjectionData != null) {
                    initializeMediaProjection()
                    showFloatingBubble()
                    isRunning = true
                    sendSafeBroadcast(BROADCAST_SERVICE_STARTED)
                } else {
                    sendSafeBroadcast(BROADCAST_SERVICE_ERROR)
                    stopSelf()
                }
            }
            ACTION_STOP_SERVICE -> stopService()
            ACTION_UPDATE_LANGUAGES -> {
                val sourceCode = intent.getStringExtra(EXTRA_SOURCE_LANG) ?: "vi"
                val targetCode = intent.getStringExtra(EXTRA_TARGET_LANG) ?: "en"
                floatingBubbleView?.updateLanguageDisplay(sourceCode, targetCode)
            }
        }
        return START_STICKY
    }

    private fun showFloatingBubble() {
        try {
            val themedContext = ContextThemeWrapper(this, R.style.Theme_AppTranslate_NoActionBar)
            floatingBubbleView = FloatingBubbleView(themedContext, serviceScope).apply {
                this.mediaProjection = this@OverlayService.mediaProjection
                onLanguageSelectClickListener = { sendSafeBroadcast(ACTION_SHOW_LANGUAGE_SHEET) }
                onMagnifierMoveListener = { rect -> startLiveTranslation(rect) }
                onMagnifierReleaseListener = { stopLiveTranslation() }
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show floating bubble", e)
            sendSafeBroadcast(BROADCAST_SERVICE_ERROR)
            stopSelf()
        }
    }

    private fun startLiveTranslation(initialRect: Rect) {
        if (liveTranslationJob?.isActive == true) return
        lastOcrText = null
        liveTranslationJob = serviceScope.launch {
            var latestRect = initialRect
            floatingBubbleView?.onMagnifierMoveListener = { newRect -> latestRect = newRect }
            while (isActive) {
                processScreen(latestRect)
                delay(400) // Tạm dừng giữa các lần quét để tiết kiệm pin
            }
        }
    }

    private fun stopLiveTranslation() {
        liveTranslationJob?.cancel()
        floatingBubbleView?.onMagnifierMoveListener = { rect -> startLiveTranslation(rect) }
        resultView?.let { setupDismissOnTouchOutside(it) }
    }

    private suspend fun processScreen(magnifierRect: Rect) {
        val screenBitmap = captureScreen() ?: return
        val croppedBitmap = cropBitmap(screenBitmap, magnifierRect)
        screenBitmap.recycle()

        val sourceLang = settingsManager.getSourceLanguageCode() ?: "vi"
        try {
            val ocrResult = ocrManager.recognizeTextFromBitmap(croppedBitmap, sourceLang)
            croppedBitmap.recycle()
            val detectedText = ocrResult.fullText.trim()
            if (detectedText.isNotBlank() && detectedText != lastOcrText) {
                lastOcrText = detectedText
                val textBlock = ocrResult.textBlocks.firstOrNull() ?: return
                val textRect = textBlock.boundingBox ?: return
                val globalTextRect = Rect(
                    magnifierRect.left + textRect.left, magnifierRect.top + textRect.top,
                    magnifierRect.left + textRect.right, magnifierRect.top + textRect.bottom
                )
                showResultView(globalTextRect)
                val targetLang = settingsManager.getTargetLanguageCode() ?: "en"
                val transSource = settingsManager.getTranslationSource()
                val translationResult = translationManager.translate(detectedText, sourceLang, targetLang, transSource)
                resultView?.updateText(translationResult.getOrDefault("..."))
            }
        } catch (e: Exception) {
            Log.e(TAG, "OCR failed", e)
            croppedBitmap.recycle()
        }
    }

    private fun showResultView(positionRect: Rect) {
        removeResultView()
        resultView = TranslationResultView(this)
        resultViewParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = positionRect.left; y = positionRect.top
        }
        resultView?.showLoading()
        windowManager.addView(resultView, resultViewParams)
    }

    private fun removeResultView() {
        try {
            resultView?.let { if(it.isAttachedToWindow) windowManager.removeView(it) }
        } catch (e: Exception) { Log.e(TAG, "Error removing result view", e) }
        resultView = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDismissOnTouchOutside(viewToDismiss: View) {
        val scrim = View(this)
        val scrimParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        scrim.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                removeResultView()
                try { if(scrim.isAttachedToWindow) windowManager.removeView(scrim) } catch (e: Exception){}
                true
            } else false
        }
        windowManager.addView(scrim, scrimParams)
        viewToDismiss.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                try { if (scrim.isAttachedToWindow) windowManager.removeView(scrim) } catch (e: Exception) {}
            }
        })
    }

    private suspend fun captureScreen(): Bitmap? = suspendCancellableCoroutine { continuation ->
        if (mediaProjection == null) { continuation.resume(null); return@suspendCancellableCoroutine }
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val virtualDisplay = mediaProjection!!.createVirtualDisplay("sc", width, height, density, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, null)
        imageReader!!.setOnImageAvailableListener({ reader ->
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
                virtualDisplay?.release()
                imageReader = null
                if (continuation.isActive) continuation.resume(bitmap)
            }
        }, handler)
    }

    private fun cropBitmap(source: Bitmap, rect: Rect): Bitmap {
        val safeRect = Rect(maxOf(0, rect.left), maxOf(0, rect.top), minOf(source.width, rect.right), minOf(source.height, rect.bottom))
        if (safeRect.width() <= 0 || safeRect.height() <= 0) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        return Bitmap.createBitmap(source, safeRect.left, safeRect.top, safeRect.width(), safeRect.height())
    }

    private fun initializeMediaProjection() {
        try {
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(mediaProjectionResultCode, mediaProjectionData!!)
            if (mediaProjection == null) throw IllegalStateException("MediaProjection is null")
        } catch (e: Exception) {
            sendSafeBroadcast(BROADCAST_SERVICE_ERROR); stopSelf()
        }
    }

    private fun stopService() {
        if (!isRunning) return
        isRunning = false
        sendSafeBroadcast(BROADCAST_SERVICE_STOPPED)
        removeResultView()
        floatingBubbleView?.let { view ->
            try { if (view.isAttachedToWindow) windowManager.removeView(view) }
            catch (e: Exception) { Log.e(TAG, "Error removing bubble view", e) }
        }
        floatingBubbleView = null
        mediaProjection?.stop()
        mediaProjection = null
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { stopService(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Overlay Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
    private fun createBasicNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle(getString(R.string.app_name)).setContentText(text).setSmallIcon(R.drawable.ic_translate).setOngoing(true).build()
    }
    private fun sendSafeBroadcast(action: String) { sendBroadcast(Intent(action).apply { `package` = packageName }) }
}