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

    // Cache riêng cho magnifier để tránh lặp lại
    private var lastMagnifierPosition = Rect()
    private var lastMagnifierText = ""
    private var lastMagnifierTimestamp = 0L

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
            // ẨN TẤT CẢ OVERLAY TRƯỚC KHI CHỤP MÀN HÌNH
            hideAllOverlaysForCapture()

            delay(100) // Đợi overlay ẩn hoàn toàn
            val screenBitmap = captureScreen() ?: return@launch
            val processedBitmap = preprocessBitmapForOcr(screenBitmap)
            screenBitmap.recycle()

            // HIỆN LẠI OVERLAY SAU KHI CHỤP XONG
            showAllOverlaysAfterCapture()

            val sourceLang = settingsManager.getSourceLanguageCode() ?: "vi"
            val ocrResult = ocrManager.recognizeTextFromBitmap(processedBitmap, sourceLang)
            processedBitmap.recycle()

            overlay.hideLoading()

            if (ocrResult.textBlocks.isEmpty()) {
                Toast.makeText(this@OverlayService, "Không tìm thấy văn bản nào.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            // Log tất cả các block text và bounding box
            ocrResult.textBlocks.forEachIndexed { idx, block ->
                val box = block.boundingBox
                val txt = block.text.trim()
                if (box != null && txt.isNotBlank()) {
                    Log.d(TAG, "[GLOBAL] Block#$idx: text='${txt}', box=${box}")
                }
            }

            val targetLang = settingsManager.getTargetLanguageCode() ?: "en"
            val transSource = settingsManager.getTranslationSource()
            ocrResult.textBlocks.forEach { block ->
                launch(Dispatchers.IO) {
                    val text = block.text.trim()
                    if (text.isNotBlank() && block.boundingBox != null) {
                        val translation = translationManager.translate(text, sourceLang, targetLang, transSource)
                        withContext(Dispatchers.Main) {
                            val translatedText = translation.getOrDefault("...")
                            val resultView = TranslationResultView(this@OverlayService).apply {
                                // Sử dụng setBoundingBox để tối ưu kích thước và text size
                                setBoundingBox(block.boundingBox!!, text, translatedText)
                                updateText(translatedText)
                            }
                            val p = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                                leftMargin = block.boundingBox.left
                                topMargin = block.boundingBox.top
                            }
                            Log.d(TAG, "[GLOBAL] Original text: '$text'")
                            Log.d(TAG, "[GLOBAL] ResultView text: $translatedText")
                            Log.d(TAG, "[GLOBAL] ResultView box: Rect(${p.leftMargin}, ${p.topMargin} - ${p.leftMargin + block.boundingBox.width()}, ${p.topMargin + block.boundingBox.height()})")
                            overlay.addResultView(resultView, p)
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

                        // Giới hạn tần suất OCR (tối thiểu 2 giây giữa các lần quét cho magnifier)
                        if (currentTime - lastOcrTimestamp < 2000) {
                            delay(500)
                            continue
                        }

                        // CHỈ ẨN RESULT VIEWS, KHÔNG ẨN BUBBLE để tránh mất touch event
                        withContext(Dispatchers.Main) { hideResultViewsForCapture() }
                        delay(50) // Đợi overlay ẩn

                        val screenBitmap = captureScreen() ?: continue

                        // HIỆN LẠI RESULT VIEWS SAU KHI CHỤP XONG
                        withContext(Dispatchers.Main) { showResultViewsAfterCapture() }

                        // Kiểm tra xem screenshot có thay đổi không
                        val currentHash = screenBitmap.hashCode()
                        if (currentHash == lastScreenshotHash) {
                            screenBitmap.recycle()
                            delay(1500)
                            continue
                        }

                        lastScreenshotHash = currentHash
                        lastOcrTimestamp = currentTime

                        val processedBitmap = preprocessBitmapForOcr(screenBitmap)
                        screenBitmap.recycle()

                        val sourceLang = settingsManager.getSourceLanguageCode() ?: "vi"
                        val ocrResult = ocrManager.recognizeTextFromBitmap(processedBitmap, sourceLang)
                        processedBitmap.recycle()

                        screenTextMap = ocrResult.textBlocks

                        // Gọi GC để dọn dẹp memory
                        System.gc()
                    } else {
                        // Khi không ở chế độ MAGNIFIER, xóa dữ liệu cũ và tạm dừng
                        screenTextMap = emptyList()
                        lastScreenshotHash = 0
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in full screen OCR loop", e)
                }

                // Tăng thời gian delay và chỉ quét khi cần
                delay(if (currentState == ServiceState.MAGNIFIER) 1500 else 5000) // 1.5s khi magnifier, 5s khi không
            }
        }
    }

    private fun startMagnifierTracking() {
        if (magnifierJob?.isActive == true) return
        magnifierJob = serviceScope.launch {
            while (isActive && currentState == ServiceState.MAGNIFIER) {
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
                delay(150) // Track pointer với tần suất hợp lý
            }
            Log.d(TAG, "Magnifier tracking stopped - state: $currentState")
        }
    }

    private suspend fun findAndTranslateTextAt(bubbleX: Int, bubbleY: Int) {
        // Đảm bảo chỉ chạy khi đang ở trạng thái MAGNIFIER
        if (currentState != ServiceState.MAGNIFIER) {
            Log.d(TAG, "[MAGNIFIER] Dừng: không còn ở trạng thái magnifier")
            return
        }

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

        // Nếu cùng vị trí, cùng text và chưa đủ thời gian (1.5 giây), thì skip
        if (currentPosition == lastMagnifierPosition &&
            currentText == lastMagnifierText &&
            currentTime - lastMagnifierTimestamp < 1500) {
            Log.d(TAG, "[MAGNIFIER] Skip: cùng vị trí và text, chưa đủ delay")
            return // Không dịch lại
        }

        // Nếu cùng block như lần trước nhưng chưa đủ thời gian, skip
        if (targetBlock == lastTranslatedBlock &&
            currentTime - lastMagnifierTimestamp < 1000) {
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
        val resultView = showSingleMagnifierResult(targetBlock.boundingBox!!, currentText)

        val sourceLang = settingsManager.getSourceLanguageCode() ?: "vi"
        val targetLang = settingsManager.getTargetLanguageCode() ?: "en"
        val transSource = settingsManager.getTranslationSource()

        Log.d(TAG, "[MAGNIFIER] Đang dịch: '$currentText' ($sourceLang->$targetLang)")
        val translationResult = translationManager.translate(currentText, sourceLang, targetLang, transSource)
        Log.d(TAG, "[MAGNIFIER] Kết quả dịch: '${translationResult.getOrDefault("Lỗi dịch")}'")

        // Kiểm tra lại trạng thái trước khi update UI
        if (currentState == ServiceState.MAGNIFIER) {
            // Cập nhật bounding box với thông tin text gốc và dịch
            val translatedText = translationResult.getOrDefault("Lỗi dịch")
            resultView?.setBoundingBox(targetBlock.boundingBox!!, currentText, translatedText)
            resultView?.updateText(translatedText)
        } else {
            Log.d(TAG, "[MAGNIFIER] Trạng thái đã thay đổi, không update UI")
            // Remove view nếu trạng thái đã thay đổi
            resultView?.let { view ->
                if (view.isAttachedToWindow) {
                    windowManager.removeView(view)
                }
                magnifierResultViews.remove(view)
            }
        }
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
    private fun showSingleMagnifierResult(position: Rect, originalText: String = ""): TranslationResultView {
        val width = position.width()
        val height = position.height()
        val resultView = TranslationResultView(this)

        val resultViewParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Đặt chính xác tại vị trí text gốc, không trừ padding
            x = position.left
            y = position.top
        }

        Log.d(TAG, "[MAGNIFIER] addView: x=${resultViewParams.x}, y=${resultViewParams.y}, width=$width, height=$height")
        Log.d(TAG, "[MAGNIFIER] Original text: '$originalText'")
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



    private fun preprocessBitmapForOcr(bitmap: Bitmap): Bitmap {
        val scaledBitmap = if (bitmap.width > 1920 || bitmap.height > 1920) {
            // Scale down để tăng tốc độ OCR nhưng vẫn giữ quality
            val scale = minOf(1920f / bitmap.width, 1920f / bitmap.height)
            val newWidth = (bitmap.width * scale).toInt()
            val newHeight = (bitmap.height * scale).toInt()
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } else {
            bitmap
        }

        // Tạo bitmap grayscale với contrast cao
        val processedBitmap = Bitmap.createBitmap(scaledBitmap.width, scaledBitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(processedBitmap)
        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }

        // Matrix để tăng contrast và làm sắc nét text
        val colorMatrix = ColorMatrix().apply {
            setSaturation(0f) // Grayscale trước
        }

        // Tăng contrast mạnh hơn để text rõ ràng hơn
        val contrastScale = 2.5f
        val translate = -(contrastScale - 1f) * 128f
        val contrastMatrix = ColorMatrix(floatArrayOf(
            contrastScale, 0f, 0f, 0f, translate,
            0f, contrastScale, 0f, 0f, translate,
            0f, 0f, contrastScale, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        ))
        colorMatrix.postConcat(contrastMatrix)

        // Sharpening filter để làm sắc nét text
        val sharpenMatrix = ColorMatrix(floatArrayOf(
            0f, -1f, 0f, 0f, 0f,
            -1f, 5f, -1f, 0f, 0f,
            0f, -1f, 0f, 0f, 0f,
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

        return processedBitmap
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

    // --- Overlay Management for OCR ---

    // HÀM MỚI: Chỉ ẩn result views, không ẩn bubble để tránh mất touch event
    private fun hideResultViewsForCapture() {
        try {
            // Chỉ ẩn tất cả magnifier result views, KHÔNG ẩn bubble
            magnifierResultViews.forEach { view ->
                view.visibility = View.INVISIBLE
            }

            Log.d(TAG, "Hidden result views for screen capture (bubble still visible)")
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding result views for capture", e)
        }
    }

    private fun showResultViewsAfterCapture() {
        try {
            // Hiện lại tất cả magnifier result views
            magnifierResultViews.forEach { view ->
                view.visibility = View.VISIBLE
            }

            Log.d(TAG, "Restored result views after screen capture")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing result views after capture", e)
        }
    }

    private fun hideAllOverlaysForCapture() {
        try {
            // Ẩn floating bubble
            floatingBubbleView?.visibility = View.INVISIBLE

            // Ẩn tất cả magnifier result views
            magnifierResultViews.forEach { view ->
                view.visibility = View.INVISIBLE
            }

            // Ẩn global overlay (nhưng không remove để không mất state)
            globalOverlay?.visibility = View.INVISIBLE

            Log.d(TAG, "Hidden all overlays for screen capture")
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding overlays for capture", e)
        }
    }

    private fun showAllOverlaysAfterCapture() {
        try {
            // Hiện lại floating bubble (chỉ khi không ở trạng thái GLOBAL)
            if (currentState != ServiceState.GLOBAL_TRANSLATE_ACTIVE) {
                floatingBubbleView?.visibility = View.VISIBLE
            }

            // Hiện lại tất cả magnifier result views
            magnifierResultViews.forEach { view ->
                view.visibility = View.VISIBLE
            }

            // Hiện lại global overlay
            globalOverlay?.visibility = View.VISIBLE

            Log.d(TAG, "Restored all overlays after screen capture")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing overlays after capture", e)
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