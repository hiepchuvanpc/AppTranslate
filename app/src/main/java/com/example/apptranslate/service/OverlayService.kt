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
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import java.security.MessageDigest

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
    private var globalOverlay: GlobalTranslationOverlay? = null
    private val magnifierResultViews = mutableListOf<View>()

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

    // Cache để tránh OCR lặp lại
    private var lastOcrTimestamp = 0L
    private var lastScreenshotHash = 0
    private var currentScaleFactor = 1.0f

    // Cache riêng cho magnifier để tránh lặp lại
    private var lastMagnifierPosition = Rect()
    private var lastMagnifierText = ""
    private var lastMagnifierTimestamp = 0L

    // Thread pool cho xử lý ảnh nặng
    private val imageProcessingHandler = Handler(Looper.getMainLooper())

    // Semaphore để kiểm soát số lượng translation đồng thời
    private val translationSemaphore = kotlinx.coroutines.sync.Semaphore(3)

    // --- State Management ---
    private enum class ServiceState { IDLE, PANEL_OPEN, MAGNIFIER, MOVING_PANEL, GLOBAL_TRANSLATE_ACTIVE }
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

            // Dọn dẹp tất cả overlay views
            removeGlobalOverlay()

            floatingBubbleView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
            removeAllMagnifierResults()
            mediaProjection?.stop()
            ocrManager.close()
            serviceScope.cancel()

            // Dọn dẹp memory cache
            screenTextMap = emptyList()
            lastTranslatedBlock = null
            lastScreenshotHash = 0
            lastOcrTimestamp = 0L
            lastMagnifierPosition = Rect()
            lastMagnifierText = ""
            lastMagnifierTimestamp = 0L

            // Force garbage collection
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

    private fun setState(newState: ServiceState) {
        if (currentState == newState) return
        Log.d(TAG, "State changing from $currentState to $newState")

        // Dọn dẹp trạng thái cũ một cách kỹ lưỡng
        if (currentState == ServiceState.MAGNIFIER) stopMagnifierTracking()
        if (currentState == ServiceState.GLOBAL_TRANSLATE_ACTIVE) {
            removeGlobalOverlay()
            // Đảm bảo bubble hiện lại
            floatingBubbleView?.visibility = View.VISIBLE
        }

        currentState = newState

        // Thiết lập trạng thái mới
        when (newState) {
            ServiceState.IDLE -> {
                floatingBubbleView?.closePanel()
                floatingBubbleView?.updateBubbleAppearance(BubbleAppearance.NORMAL)
                floatingBubbleView?.visibility = View.VISIBLE
                // Tạm dừng OCR để tiết kiệm RAM
                screenTextMap = emptyList()
            }
            ServiceState.PANEL_OPEN -> floatingBubbleView?.openPanel()
            ServiceState.MAGNIFIER -> {
                floatingBubbleView?.closePanel()
                floatingBubbleView?.updateBubbleAppearance(BubbleAppearance.MAGNIFIER)
                startMagnifierTracking()
            }
            ServiceState.MOVING_PANEL -> {
                floatingBubbleView?.closePanel()
                floatingBubbleView?.updateBubbleAppearance(BubbleAppearance.MOVING)
            }
            ServiceState.GLOBAL_TRANSLATE_ACTIVE -> {
                floatingBubbleView?.closePanel()
                floatingBubbleView?.visibility = View.GONE // Ẩn bubble đi
                performGlobalTranslate()
            }
        }
    }

    private fun performGlobalTranslate() = serviceScope.launch {
        showGlobalOverlay()?.let { overlay ->
            val screenBitmap = captureScreen() ?: return@launch
            val (processedBitmap, scaleFactor) = withContext(Dispatchers.Default) {
                preprocessBitmapForOcr(screenBitmap)
            }
            screenBitmap.recycle()

            val sourceLang = settingsManager.getSourceLanguageCode() ?: "vi"
            val ocrResult = ocrManager.recognizeTextFromBitmap(processedBitmap, sourceLang)
            processedBitmap.recycle()

            overlay.hideLoading()

            if (ocrResult.textBlocks.isEmpty()) {
                Toast.makeText(this@OverlayService, "Không tìm thấy văn bản nào.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            // Điều chỉnh bounding box theo scale factor
            val adjustedBlocks = ocrResult.textBlocks.map { block ->
                block.boundingBox?.let { box ->
                    val adjustedBox = Rect(
                        (box.left / scaleFactor).toInt(),
                        (box.top / scaleFactor).toInt(),
                        (box.right / scaleFactor).toInt(),
                        (box.bottom / scaleFactor).toInt()
                    )
                    block.copy(boundingBox = adjustedBox)
                } ?: block
            }

            // Log tất cả các block text và bounding box đã điều chỉnh
            adjustedBlocks.forEachIndexed { idx, block ->
                val box = block.boundingBox
                val txt = block.text.trim()
                if (box != null && txt.isNotBlank()) {
                    Log.d(TAG, "[GLOBAL] Block#$idx: text='${txt}', box=${box}")
                }
            }

            val targetLang = settingsManager.getTargetLanguageCode() ?: "en"
            val transSource = settingsManager.getTranslationSource()
            adjustedBlocks.forEach { block ->
                launch(Dispatchers.IO) {
                    val text = block.text.trim()
                    if (text.isNotBlank() && block.boundingBox != null) {
                        // Sử dụng semaphore để kiểm soát concurrency
                        translationSemaphore.acquire()
                        try {
                            val translation = translationManager.translate(text, sourceLang, targetLang, transSource)
                            withContext(Dispatchers.Main) {
                                val resultView = TranslationResultView(this@OverlayService).apply {
                                    updateText(translation.getOrDefault("..."))
                                }
                                val p = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                                    leftMargin = block.boundingBox.left
                                    topMargin = block.boundingBox.top
                                }
                                Log.d(TAG, "[GLOBAL] ResultView text: ${resultView.findViewById<TextView>(R.id.tv_translated_text)?.text}")
                                Log.d(TAG, "[GLOBAL] ResultView box: Rect(${p.leftMargin}, ${p.topMargin} - ${p.leftMargin + block.boundingBox.width()}, ${p.topMargin + block.boundingBox.height()})")
                                overlay.addResultView(resultView, p)
                            }
                        } finally {
                            translationSemaphore.release()
                        }
                    }
                }
            }
        }
    }


    private fun showGlobalOverlay(): GlobalTranslationOverlay? {
        val overlay = GlobalTranslationOverlay(this, windowManager).apply {
            onDismiss = {
                globalOverlay = null
                setState(ServiceState.IDLE)
            }
            showLoading()
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            // Loại bỏ FLAG_NOT_TOUCH_MODAL để tránh background xám
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )

        // Không set background = null nữa, để layout tự xử lý background

        windowManager.addView(overlay, params)
        globalOverlay = overlay
        return overlay
    }


    // THÊM HÀM MỚI
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
        // Chỉ kích hoạt kính lúp nếu đang ở trạng thái rảnh rỗi (IDLE).
        // Nếu đang ở trạng thái MOVING_PANEL, sẽ không làm gì cả, chỉ để cho việc kéo thả diễn ra.
        if (currentState == ServiceState.IDLE) {
            setState(ServiceState.MAGNIFIER)
        }
    }

    override fun onDragFinished() {
        // Bất kể trạng thái trước đó là gì (kính lúp hay di chuyển),
        // sau khi kéo thả xong, chúng ta đều quay về trạng thái rảnh rỗi.
        if (currentState == ServiceState.MAGNIFIER || currentState == ServiceState.MOVING_PANEL) {
            setState(ServiceState.IDLE)
        }
    }

    override fun onLanguageSelectClicked() {
        Log.d(TAG, "onLanguageSelectClicked called")

        // Đảm bảo tắt tất cả overlay trước khi mở bottom sheet
        if (currentState == ServiceState.GLOBAL_TRANSLATE_ACTIVE) {
            removeGlobalOverlay()
        }

        // Ẩn bubble tạm thời để tránh conflict
        floatingBubbleView?.visibility = View.GONE

        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            action = "ACTION_SHOW_LANGUAGE_SHEET"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)

        // Delay một chút rồi hiện lại bubble và set state
        handler.postDelayed({
            floatingBubbleView?.visibility = View.VISIBLE
            setState(ServiceState.IDLE)
        }, 500)
    }

    override fun onHomeClicked() {
        Log.d(TAG, "onHomeClicked called")
        // Tạo Intent để mở lại MainActivity
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(intent)
        // Đóng panel lại
        setState(ServiceState.IDLE)
    }

    override fun onMoveClicked() {
        Log.d(TAG, "onMoveClicked called")
        // Chuyển sang trạng thái di chuyển panel
        setState(ServiceState.MOVING_PANEL)
    }

    override fun onFunctionClicked(functionId: String) {
        Log.d(TAG, "onFunctionClicked called with functionId: $functionId")
        if (functionId == "GLOBAL") {
            setState(ServiceState.GLOBAL_TRANSLATE_ACTIVE)
        } else {
            setState(ServiceState.IDLE)
            Toast.makeText(this, "Chức năng '$functionId' chưa được triển khai", Toast.LENGTH_SHORT).show()
        }
    }

    // --- New OCR Architecture ---

    private fun startFullScreenOcrLoop() {
        if (fullScreenOcrJob?.isActive == true) return
        fullScreenOcrJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    // Chỉ quét khi cần thiết - khi ở trạng thái MAGNIFIER
                    if (currentState == ServiceState.MAGNIFIER) {
                        val currentTime = System.currentTimeMillis()

                        // Giới hạn tần suất OCR (tối thiểu 3 giây giữa các lần quét)
                        if (currentTime - lastOcrTimestamp < 3000) {
                            delay(1000)
                            continue
                        }

                        val screenBitmap = captureScreen() ?: continue

                        // Tính content-based hash thay vì bitmap.hashCode()
                        val currentHash = calculateBitmapContentHash(screenBitmap)
                        if (currentHash == lastScreenshotHash) {
                            screenBitmap.recycle()
                            delay(2000)
                            continue
                        }

                        lastScreenshotHash = currentHash
                        lastOcrTimestamp = currentTime

                        // Xử lý bitmap trong background và lưu scale factor
                        val (processedBitmap, scaleFactor) = withContext(Dispatchers.Default) {
                            preprocessBitmapForOcr(screenBitmap)
                        }
                        screenBitmap.recycle()
                        currentScaleFactor = scaleFactor

                        val sourceLang = settingsManager.getSourceLanguageCode() ?: "vi"
                        val ocrResult = ocrManager.recognizeTextFromBitmap(processedBitmap, sourceLang)
                        processedBitmap.recycle()

                        // Điều chỉnh bounding box theo scale factor
                        val adjustedBlocks = ocrResult.textBlocks.map { block ->
                            block.boundingBox?.let { box ->
                                val adjustedBox = Rect(
                                    (box.left / scaleFactor).toInt(),
                                    (box.top / scaleFactor).toInt(),
                                    (box.right / scaleFactor).toInt(),
                                    (box.bottom / scaleFactor).toInt()
                                )
                                block.copy(boundingBox = adjustedBox)
                            } ?: block
                        }
                        screenTextMap = adjustedBlocks
                    } else {
                        // Khi không ở chế độ MAGNIFIER, xóa dữ liệu cũ và tạm dừng
                        screenTextMap = emptyList()
                        lastScreenshotHash = 0
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in full screen OCR loop", e)
                }

                // Tăng thời gian delay và chỉ quét khi cần
                delay(if (currentState == ServiceState.MAGNIFIER) 2000 else 5000) // 2s khi magnifier, 5s khi không
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

                    // Sử dụng chính center của bubble để test
                    val bubbleCenterX = location[0] + bubble.width / 2
                    val bubbleCenterY = location[1] + bubble.height / 2

                    Log.d(TAG, "Bubble location: (${location[0]}, ${location[1]}), size: ${bubble.width}x${bubble.height}")
                    Log.d(TAG, "Magnifier cursor at: ($bubbleCenterX, $bubbleCenterY)")

                    findAndTranslateTextAt(bubbleCenterX, bubbleCenterY)
                }
                delay(100) // Track pointer 10 times per second
            }
        }
    }

    private suspend fun findAndTranslateTextAt(bubbleX: Int, bubbleY: Int) {
        Log.d(TAG, "[MAGNIFIER] screenTextMap size: ${screenTextMap.size}")
        // Log tất cả các block text và bounding box
        screenTextMap.forEachIndexed { idx, block ->
            val box = block.boundingBox
            val txt = block.text.trim()
            if (box != null && txt.isNotBlank()) {
                Log.d(TAG, "[MAGNIFIER] Block#$idx: text='${txt}', box=${box}")
            }
        }
        val targetBlock = screenTextMap.find {
            it.boundingBox?.contains(bubbleX, bubbleY) ?: false
        }

        if (targetBlock == null) {
            Log.d(TAG, "[MAGNIFIER] Không tìm thấy block nào tại vị trí ($bubbleX, $bubbleY)")
            if (lastTranslatedBlock != null) {
                lastTranslatedBlock = null
                removeAllMagnifierResults()
            }
            return
        }

        // Kiểm tra xem có phải cùng vị trí và cùng text không
        val currentPosition = targetBlock.boundingBox!!
        val currentText = targetBlock.text.trim()
        val currentTime = System.currentTimeMillis()

        Log.d(TAG, "[MAGNIFIER] Found block: text='${currentText}', box=$currentPosition")

        // Nếu cùng vị trí, cùng text và chưa đủ thời gian (2 giây), thì skip
        if (currentPosition == lastMagnifierPosition &&
            currentText == lastMagnifierText &&
            currentTime - lastMagnifierTimestamp < 2000) {
            Log.d(TAG, "[MAGNIFIER] Skip: cùng vị trí và text, chưa đủ delay")
            return // Không dịch lại
        }

        // Nếu cùng block như lần trước nhưng chưa đủ thời gian, skip
        if (targetBlock == lastTranslatedBlock &&
            currentTime - lastMagnifierTimestamp < 1500) {
            Log.d(TAG, "[MAGNIFIER] Skip: cùng block như lần trước, chưa đủ delay")
            return
        }

        // Cập nhật cache
        lastMagnifierPosition = currentPosition
        lastMagnifierText = currentText
        lastMagnifierTimestamp = currentTime
        lastTranslatedBlock = targetBlock

        if (currentText.isBlank()) {
            Log.d(TAG, "[MAGNIFIER] Block text rỗng, không hiển thị kết quả")
            return
        }

        // Xóa kết quả kính lúp cũ trước khi hiển thị cái mới
        removeAllMagnifierResults()
        val resultView = showSingleMagnifierResult(targetBlock.boundingBox!!)

        val sourceLang = settingsManager.getSourceLanguageCode() ?: "vi"
        val targetLang = settingsManager.getTargetLanguageCode() ?: "en"
        val transSource = settingsManager.getTranslationSource()

        Log.d(TAG, "[MAGNIFIER] Đang dịch: '$currentText' ($sourceLang->$targetLang)")

        // Sử dụng semaphore để kiểm soát concurrency
        translationSemaphore.acquire()
        val translationResult = try {
            translationManager.translate(currentText, sourceLang, targetLang, transSource)
        } finally {
            translationSemaphore.release()
        }

        Log.d(TAG, "[MAGNIFIER] Kết quả dịch: '${translationResult.getOrDefault("Lỗi dịch")}'")
        resultView?.updateText(translationResult.getOrDefault("Lỗi dịch"))
    }

    private fun removeAllMagnifierResults() {
        magnifierResultViews.forEach { view -> // Sửa ở đây
            try {
                if (view.isAttachedToWindow) windowManager.removeView(view)
            } catch (e: Exception) { /* Ignore */ }
        }
        magnifierResultViews.clear() // Sửa ở đây
    }


    // BÊN TRONG CLASS OverlayService

    @SuppressLint("InflateParams")
    private fun showSingleMagnifierResult(position: Rect): TranslationResultView {
        val width = position.width()
        val height = position.height()
        val resultView = TranslationResultView(this).apply {
            if (width > 0 && height > 0) {
                setSize(width, height)
            } else {
                Log.d(TAG, "[MAGNIFIER] setSize skipped: width=$width, height=$height")
            }
        }

        // TÍNH TOÁN PADDING: Chuyển 4dp (giảm từ 8dp) sang pixel
        val paddingInPx = (4 * resources.displayMetrics.density).toInt()

        val resultViewParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = position.left
            y = position.top - paddingInPx
        }

        Log.d(TAG, "[MAGNIFIER] addView: x=${resultViewParams.x}, y=${resultViewParams.y}, width=$width, height=$height")
        Log.d(TAG, "[MAGNIFIER] ResultView text: ${resultView.findViewById<TextView>(R.id.tv_translated_text)?.text}")
        Log.d(TAG, "[MAGNIFIER] ResultView box: Rect(${resultViewParams.x}, ${resultViewParams.y} - ${resultViewParams.x + width}, ${resultViewParams.y + height})")
        windowManager.addView(resultView, resultViewParams)
        magnifierResultViews.add(resultView)
        return resultView
    }

    private fun stopMagnifierTracking() {
        magnifierJob?.cancel()
        magnifierJob = null
        lastTranslatedBlock = null
        removeAllMagnifierResults() // Sửa ở đây
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



    private fun preprocessBitmapForOcr(bitmap: Bitmap): Pair<Bitmap, Float> {
        // Tăng độ phân giải tối đa để bảo toàn chữ nhỏ
        val maxDimension = 2560 // Tăng từ 1920 lên 2560 để giữ chi tiết chữ nhỏ
        val scaleFactor = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
            minOf(maxDimension.toFloat() / bitmap.width, maxDimension.toFloat() / bitmap.height)
        } else {
            1.0f
        }

        val scaledBitmap = if (scaleFactor < 1.0f) {
            val newWidth = (bitmap.width * scaleFactor).toInt()
            val newHeight = (bitmap.height * scaleFactor).toInt()
            // Sử dụng FILTER_BITMAP=true để giữ chi tiết khi scale
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } else {
            bitmap
        }

        // Tạo bitmap grayscale với contrast cao - tối ưu cho chữ nhỏ
        val processedBitmap = Bitmap.createBitmap(scaledBitmap.width, scaledBitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(processedBitmap)
        val paint = Paint().apply {
            isAntiAlias = false // Tắt anti-alias để chữ nhỏ sắc nét hơn
            isFilterBitmap = true
            isDither = false
        }

        // Matrix để tăng contrast mạnh hơn cho chữ nhỏ
        val colorMatrix = ColorMatrix().apply {
            setSaturation(0f) // Grayscale trước
        }

        // Tăng contrast rất mạnh để chữ nhỏ nổi bật
        val contrastScale = 3.5f // Tăng từ 2.5f lên 3.5f
        val translate = -(contrastScale - 1f) * 128f
        val contrastMatrix = ColorMatrix(floatArrayOf(
            contrastScale, 0f, 0f, 0f, translate,
            0f, contrastScale, 0f, 0f, translate,
            0f, 0f, contrastScale, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        ))
        colorMatrix.postConcat(contrastMatrix)

        // Sharpening filter mạnh hơn để làm sắc nét chữ nhỏ
        val sharpenMatrix = ColorMatrix(floatArrayOf(
            0f, -1.5f, 0f, 0f, 0f,
            -1.5f, 7f, -1.5f, 0f, 0f, // Tăng độ sắc nét
            0f, -1.5f, 0f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
            0f, 0f, 0f, 0f, 1f
        ))
        colorMatrix.postConcat(sharpenMatrix)

        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(scaledBitmap, 0f, 0f, paint)

        // Cleanup
        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }

        return Pair(processedBitmap, scaleFactor)
    }    // Tính content-based hash thay vì bitmap.hashCode()
    private fun calculateBitmapContentHash(bitmap: Bitmap): Int {
        val width = bitmap.width
        val height = bitmap.height
        val sampleSize = 8 // Sample mỗi 8 pixel để tăng tốc

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
        if (mediaProjection == null || imageReader?.surface != null) {
            if (continuation.isActive) continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        // Tăng density để capture với độ phân giải cao hơn - tốt cho chữ nhỏ
        val density = (metrics.densityDpi * 1.2f).toInt() // Tăng 20% để bảo toàn chi tiết

        // Sử dụng RGBA_8888 để bảo toàn chất lượng màu tối đa
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3) // Tăng buffer từ 2 lên 3
        val virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
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