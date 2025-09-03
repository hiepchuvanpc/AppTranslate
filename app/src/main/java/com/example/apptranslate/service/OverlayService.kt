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
import android.graphics.BitmapFactory

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
    private var imageTranslationOverlay: ImageTranslationOverlay? = null
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
            Log.d(TAG, "Received broadcast with action: ${intent?.action}")
            if (intent?.action == ACTION_SHOW_IMAGE_TRANSLATION_RESULTS) {
                val results = intent.getParcelableArrayListExtra<ImageTranslationResult>("TRANSLATED_RESULTS")
                val imagePath = intent.getStringExtra("BACKGROUND_IMAGE_PATH")
                Log.d(TAG, "Image translation results received: ${results?.size} items, image path: $imagePath")
                results?.let { showImageTranslationResults(it, imagePath) }
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
    private val magnifierUsedRects = mutableListOf<Rect>() // Để tránh đè lên nhau trong magnifier mode
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
        val imageTranslationFilter = IntentFilter(ACTION_SHOW_IMAGE_TRANSLATION_RESULTS)
        registerReceiver(imageTranslationReceiver, imageTranslationFilter)

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
            unregisterReceiver(imageTranslationReceiver)
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

    //region Logic chung cho OCR và Dịch thuật
    private suspend fun performOcrAndTranslation(bitmap: Bitmap): Result<List<TranslatedBlock>> = runCatching {
        if (bitmap.isRecycled) return@runCatching emptyList()

        val sourceLang = settingsManager.getSourceLanguageCode() ?: "vi"
        val ocrResult = withContext(Dispatchers.IO) {
            ocrManager.recognizeTextFromBitmap(bitmap, sourceLang)
        }

        // Lọc bỏ các text blocks nằm trong vùng overlay translation để tránh dịch lại
        val filteredBlocks = ocrResult.textBlocks
            .filter { it.text.isNotBlank() && it.boundingBox != null }
            .filter { !isBlockInTranslationOverlayArea(it.boundingBox!!) }

        Log.d(TAG, "Found ${ocrResult.textBlocks.size} total blocks, ${filteredBlocks.size} blocks after filtering overlays")

        if (filteredBlocks.isEmpty()) return@runCatching emptyList()

        // Áp dụng logic phân tích thông minh để nhóm và xử lý văn bản
        val processedBlocks = preprocessOcrBlocks(filteredBlocks)

        Log.d(TAG, "After intelligent processing: ${processedBlocks.size} blocks")

        // LOGIC MỚI: Gộp TẤT CẢ text blocks thành 1 request duy nhất
        // Xử lý mixed content (text + link/code/key trong cùng câu)

        val processedTexts = mutableListOf<String>()
        val allBlocks = mutableListOf<OcrResult.Block>()
        val specialContentMaps = mutableListOf<Map<String, String>>() // Placeholder -> Original content

        Log.d(TAG, "=== SINGLE REQUEST TRANSLATION DEBUG ===")

        // Bước 1: Xử lý từng block, tách special content ra
        processedBlocks.forEach { block ->
            val blockText = block.text.trim()

            if (isSpecialTextThatShouldNotBeTranslated(blockText)) {
                Log.d(TAG, "Full special text block (skip translation): '$blockText'")
                // Toàn bộ block là special text
                processedTexts.add(blockText)
                allBlocks.add(block)
                specialContentMaps.add(emptyMap()) // Không có special content để thay thế
            } else {
                // Kiểm tra mixed content trong block
                val (processedText, specialMap) = extractAndReplaceSpecialContent(blockText)

                Log.d(TAG, "Block text: '$blockText'")
                Log.d(TAG, "  Processed for translation: '$processedText'")
                Log.d(TAG, "  Special content map: $specialMap")

                processedTexts.add(processedText)
                allBlocks.add(block)
                specialContentMaps.add(specialMap)
            }
        }

        // Bước 2: Dịch TẤT CẢ text trong 1 request duy nhất (kể cả mixed content đã được placeholder)
        val results = mutableListOf<TranslatedBlock>()

        if (allBlocks.isNotEmpty()) {
            // CÁCH MỚI: Gộp text thông minh để giữ ngữ cảnh tốt hơn
            // Thay vì ngăn cách bằng \n, dùng cách thông minh hơn
            val intelligentCombinedText = combineTextIntelligently(processedTexts, allBlocks)

            Log.d(TAG, "Intelligent combined text for single translation request:")
            Log.d(TAG, "'$intelligentCombinedText'")

            val targetLang = settingsManager.getTargetLanguageCode() ?: "en"
            val transSource = settingsManager.getTranslationSource()

            val translationResult = withContext(Dispatchers.IO) {
                translationManager.translate(intelligentCombinedText, sourceLang, targetLang, transSource)
            }

            val translatedText = translationResult.getOrThrow()
            
            // Phân tách kết quả dịch thông minh theo delimiter đặc biệt
            val translatedParts = splitTranslatedTextIntelligently(translatedText, allBlocks.size)

            Log.d(TAG, "Translation result: '$translatedText'")
            Log.d(TAG, "Split into ${translatedParts.size} parts for ${allBlocks.size} blocks")

            // Bước 3: Ghép kết quả dịch với từng block và phục hồi special content
            if (translatedParts.size == allBlocks.size) {
                allBlocks.forEachIndexed { index, block ->
                    var translatedPart = postprocessTranslatedText(translatedParts[index])

                    // Phục hồi special content từ placeholder
                    val specialMap = specialContentMaps[index]
                    specialMap.forEach { (placeholder, originalContent) ->
                        translatedPart = translatedPart.replace(placeholder, originalContent)
                    }

                    results.add(TranslatedBlock(block, translatedPart))
                    Log.d(TAG, "Block $index: '${block.text}' -> '$translatedPart'")
                }
            } else {
                Log.w(TAG, "Mismatch: ${translatedParts.size} translated parts vs ${allBlocks.size} blocks")
                // Fallback: ghép theo tỷ lệ và phục hồi special content
                allBlocks.forEachIndexed { index, block ->
                    var translatedPart = if (index < translatedParts.size) {
                        postprocessTranslatedText(translatedParts[index])
                    } else {
                        postprocessTranslatedText(translatedText) // Dùng toàn bộ nếu không khớp
                    }

                    // Phục hồi special content
                    val specialMap = specialContentMaps[index]
                    specialMap.forEach { (placeholder, originalContent) ->
                        translatedPart = translatedPart.replace(placeholder, originalContent)
                    }

                    results.add(TranslatedBlock(block, translatedPart))
                }
            }
        }

        Log.d(TAG, "Completed single request translation with ${results.size} total blocks")
        results
    }

    /**
     * Tách và thay thế special content (URL, email, code, key) bằng placeholder
     * @return Pair of (processed text for translation, placeholder map)
     */
    private fun extractAndReplaceSpecialContent(text: String): Pair<String, Map<String, String>> {
        var processedText = text
        val specialMap = mutableMapOf<String, String>()
        var placeholderCounter = 0

        // Các pattern cho special content
        val specialPatterns = listOf(
            // URLs
            Regex("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=]+"),
            Regex("www\\.[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=]+"),
            Regex("ftp://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=]+"),

            // Email
            Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"),

            // Phone numbers
            Regex("\\+?[0-9]{1,4}[\\s\\-]?\\(?[0-9]{1,3}\\)?[\\s\\-]?[0-9]{3,4}[\\s\\-]?[0-9]{3,4}"),

            // API Keys / Tokens (long alphanumeric strings)
            Regex("[A-Za-z0-9]{20,}"),

            // File paths
            Regex("[A-Za-z]:\\\\[\\w\\\\.-]+"),
            Regex("/[\\w/.-]+"),

            // Code snippets (words with underscores/camelCase)
            Regex("\\b[a-z]+[A-Z][a-zA-Z]*\\b"), // camelCase
            Regex("\\b[a-zA-Z]+_[a-zA-Z_]+\\b"), // snake_case

            // Version numbers
            Regex("v?[0-9]+\\.[0-9]+(?:\\.[0-9]+)?"),

            // IDs and codes
            Regex("\\b[A-Z]{2,4}[0-9]{4,}\\b"),
            Regex("\\b[0-9]{8,}\\b")
        )

        // Tìm và thay thế special content
        specialPatterns.forEach { pattern ->
            val matches = pattern.findAll(processedText).toList()
            matches.forEach { match ->
                val originalContent = match.value
                val placeholder = "SPECIAL_PLACEHOLDER_${placeholderCounter++}"

                // Chỉ thay thế nếu đây thực sự là special content (không phải từ thường)
                if (isSpecialTextThatShouldNotBeTranslated(originalContent)) {
                    processedText = processedText.replace(originalContent, placeholder)
                    specialMap[placeholder] = originalContent
                }
            }
        }

        return Pair(processedText, specialMap)
    }
    
    /**
     * Gộp text thông minh để giữ ngữ cảnh tốt hơn
     * Sử dụng delimiter đặc biệt thay vì \n để không làm mất ngữ cảnh
     */
    private fun combineTextIntelligently(texts: List<String>, blocks: List<OcrResult.Block>): String {
        val delimiter = " ◆◇◆ " // Delimiter đặc biệt, ít khả năng xuất hiện trong text thường
        
        // Phân tích vị trí các blocks để quyết định cách gộp
        val combinedParts = mutableListOf<String>()
        
        texts.forEachIndexed { index, text ->
            if (text.isNotBlank()) {
                // Kiểm tra xem text có cần nối liền với text trước không
                val currentBlock = blocks[index]
                val shouldConnect = if (index > 0) {
                    val prevBlock = blocks[index - 1]
                    // Nối liền nếu hai blocks ở gần nhau (cùng dòng hoặc dòng liền kề)
                    val verticalDistance = kotlin.math.abs(currentBlock.boundingBox?.top ?: 0 - (prevBlock.boundingBox?.bottom ?: 0))
                    val isCloseVertically = verticalDistance < 20 // 20px threshold
                    
                    // Và text trước không kết thúc bằng dấu câu
                    val prevText = texts[index - 1].trim()
                    val prevEndsWithPunctuation = prevText.endsWith(".") || prevText.endsWith("!") || 
                                                   prevText.endsWith("?") || prevText.endsWith(":") ||
                                                   prevText.endsWith(";") || prevText.endsWith(",")
                    
                    isCloseVertically && !prevEndsWithPunctuation
                } else false
                
                if (shouldConnect && combinedParts.isNotEmpty()) {
                    // Nối với part trước đó bằng dấu space thay vì delimiter
                    val lastIndex = combinedParts.size - 1
                    combinedParts[lastIndex] = combinedParts[lastIndex] + " " + text
                } else {
                    // Thêm như part riêng biệt
                    combinedParts.add(text)
                }
            }
        }
        
        // Gộp các parts bằng delimiter đặc biệt
        val result = combinedParts.joinToString(delimiter)
        
        Log.d(TAG, "Combined ${texts.size} texts into ${combinedParts.size} intelligent parts")
        Log.d(TAG, "Intelligent combined result: '$result'")
        
        return result
    }
    
    /**
     * Tách kết quả dịch thông minh theo số lượng blocks gốc
     */
    private fun splitTranslatedTextIntelligently(translatedText: String, expectedParts: Int): List<String> {
        val delimiter = " ◆◇◆ "
        
        // Thử tách theo delimiter đặc biệt trước
        val parts = translatedText.split(delimiter).filter { it.isNotBlank() }
        
        if (parts.size == expectedParts) {
            Log.d(TAG, "Perfect split: got ${parts.size} parts as expected")
            return parts
        }
        
        // Nếu không khớp, thử các cách tách khác
        Log.d(TAG, "Delimiter split gave ${parts.size} parts, expected $expectedParts")
        
        if (parts.size < expectedParts) {
            // Ít parts hơn mong đợi - thử tách thêm bằng dấu câu
            val expandedParts = mutableListOf<String>()
            parts.forEach { part ->
                // Tách thêm theo dấu câu nếu cần
                val subParts = part.split(Regex("(?<=[.!?:;])\\s+")).filter { it.isNotBlank() }
                expandedParts.addAll(subParts)
            }
            
            if (expandedParts.size >= expectedParts) {
                Log.d(TAG, "Extended split to ${expandedParts.size} parts")
                return expandedParts.take(expectedParts)
            }
        }
        
        if (parts.size > expectedParts) {
            // Nhiều parts hơn - gộp lại
            val consolidatedParts = mutableListOf<String>()
            val partsPerGroup = parts.size / expectedParts
            val remainder = parts.size % expectedParts
            
            var index = 0
            repeat(expectedParts) { groupIndex ->
                val groupSize = partsPerGroup + if (groupIndex < remainder) 1 else 0
                val groupParts = parts.subList(index, index + groupSize)
                consolidatedParts.add(groupParts.joinToString(" "))
                index += groupSize
            }
            
            Log.d(TAG, "Consolidated ${parts.size} parts into ${consolidatedParts.size} parts")
            return consolidatedParts
        }
        
        // Fallback: trả về parts hiện tại
        Log.d(TAG, "Using fallback: returning ${parts.size} parts")
        return parts
    }

    /**
     * Kiểm tra xem block có nằm trong vùng overlay translation hay không
     */
    private fun isBlockInTranslationOverlayArea(blockRect: Rect): Boolean {
        // Lấy danh sách tất cả các vùng overlay translation hiện tại
        val overlayAreas = mutableListOf<Rect>()
        val margin = 10 // Margin để tránh OCR text quá gần overlay

        // Thêm vùng của magnifier result views (Global Translation mode)
        magnifierResultViews.forEach { resultView ->
            if (resultView.isAttachedToWindow) {
                val location = IntArray(2)
                resultView.getLocationOnScreen(location)
                val overlayRect = Rect(
                    location[0] - margin,
                    location[1] - margin,
                    location[0] + resultView.width + margin,
                    location[1] + resultView.height + margin
                )
                overlayAreas.add(overlayRect)
            }
        }

        // Thêm vùng của copy text overlay
        copyTextOverlay?.let { overlay ->
            if (overlay.isAttachedToWindow) {
                val location = IntArray(2)
                overlay.getLocationOnScreen(location)
                val overlayRect = Rect(
                    location[0] - margin,
                    location[1] - margin,
                    location[0] + overlay.width + margin,
                    location[1] + overlay.height + margin
                )
                overlayAreas.add(overlayRect)
            }
        }

        // Thêm vùng của image translation overlay
        imageTranslationOverlay?.let { overlay ->
            if (overlay.isAttachedToWindow) {
                val location = IntArray(2)
                overlay.getLocationOnScreen(location)
                val overlayRect = Rect(
                    location[0] - margin,
                    location[1] - margin,
                    location[0] + overlay.width + margin,
                    location[1] + overlay.height + margin
                )
                overlayAreas.add(overlayRect)
            }
        }

        // Thêm vùng của floating bubble với margin lớn hơn
        floatingBubbleView?.let { bubble ->
            if (bubble.isAttachedToWindow) {
                val location = IntArray(2)
                bubble.getLocationOnScreen(location)
                val bubbleMargin = 30 // Margin lớn hơn cho bubble
                val bubbleRect = Rect(
                    location[0] - bubbleMargin,
                    location[1] - bubbleMargin,
                    location[0] + bubble.width + bubbleMargin,
                    location[1] + bubble.height + bubbleMargin
                )
                overlayAreas.add(bubbleRect)
            }
        }

        // Kiểm tra xem blockRect có overlap với bất kỳ overlay nào không
        return overlayAreas.any { overlayRect ->
            val hasOverlap = Rect.intersects(blockRect, overlayRect)
            if (hasOverlap) {
                Log.d(TAG, "Text block $blockRect overlaps with overlay $overlayRect - excluding from OCR")
            }
            hasOverlap
        }
    }

    /**
     * Xử lý text OCR để tránh dính chữ và cải thiện chất lượng
     */
    private fun preprocessOcrText(text: String): String {
        var processedText = text

        // Kiểm tra xem có phải text đặc biệt không (URL, email, code, key)
        if (isSpecialTextThatShouldNotBeTranslated(text.trim())) {
            Log.d(TAG, "Special text detected, minimal processing: '$text'")
            // Chỉ làm sạch cơ bản, không áp dụng các rule format
            processedText = processedText.replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "") // Zero-width characters
            processedText = processedText.trim()
            return processedText
        }

        // 1. Loại bỏ các ký tự đặc biệt không mong muốn
        processedText = processedText.replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "") // Zero-width characters

        // 2. Chuẩn hóa khoảng trắng và xuống dòng
        processedText = processedText.replace(Regex("\\s+"), " ") // Multiple spaces -> single space
        processedText = processedText.trim()

        // 3. Xử lý dính chữ cơ bản
        // Thêm khoảng trắng giữa chữ cái và số
        processedText = processedText.replace(Regex("([a-zA-Z])([0-9])"), "$1 $2")
        processedText = processedText.replace(Regex("([0-9])([a-zA-Z])"), "$1 $2")

        // 4. Tách các từ dính nhau với pattern phổ biến
        // Ví dụ: "HelloWorld" -> "Hello World" (chữ thường + chữ hoa)
        processedText = processedText.replace(Regex("([a-z])([A-Z])"), "$1 $2")

        // 5. Xử lý dính chữ phức tạp - phân tách từ dựa trên pattern
        processedText = separateStuckWords(processedText)

        // 6. Sửa lỗi dấu câu bị dính (chỉ với text thường) - CHÚ Ý: không làm ảnh hưởng xuống dòng
        val beforePunctuation = processedText
        processedText = processedText.replace(Regex("([a-zA-Z])([.!?,:;])"), "$1$2") // Không thêm space trước dấu câu
        processedText = processedText.replace(Regex("([.!?])([a-zA-Z])"), "$1 $2") // Thêm space sau dấu câu
        if (beforePunctuation != processedText) {
            Log.d(TAG, "  Punctuation fix: '$beforePunctuation' -> '$processedText'")
        }

        // 7. Sửa lỗi từ tiếng Việt bị dính
        processedText = fixVietnameseStuckWords(processedText)

        // 8. Xử lý các từ viết tắt phổ biến
        processedText = processedText.replace(Regex("\\b(vs)\\b"), "$1 ")
        processedText = processedText.replace(Regex("\\b(etc)\\b"), "$1 ")

        // 9. Phát hiện và xử lý text dính chữ nghiêm trọng
        processedText = handleSevereTextMerging(processedText)

        // 10. Trim và loại bỏ khoảng trắng thừa cuối cùng
        processedText = processedText.replace(Regex("\\s+"), " ").trim()

        Log.d(TAG, "OCR text preprocessed: '$text' -> '$processedText'")
        return processedText
    }

    /**
     * Phân tách các từ bị dính dựa trên pattern và ngữ cảnh
     */
    private fun separateStuckWords(text: String): String {
        var processedText = text

        // 1. Tách từ dựa trên pattern dấu câu bị dính
        processedText = processedText.replace(Regex("([a-zA-Z])([.!?])([A-Z])"), "$1$2 $3")

        // 2. Tách từ có dấu gạch ngang hoặc dấu gạch dưới không đúng chỗ
        processedText = processedText.replace(Regex("([a-zA-Z])-([A-Z])"), "$1 $2")
        processedText = processedText.replace(Regex("([a-zA-Z])_([A-Z])"), "$1 $2")

        // 3. Tách từ có số và chữ dính nhau với pattern phức tạp
        processedText = processedText.replace(Regex("([0-9])([A-Z][a-z])"), "$1 $2")
        processedText = processedText.replace(Regex("([a-z])([0-9][A-Z])"), "$1 $2")

        // 4. XỬ LÝ ĐẶC BIỆT CHO TIẾNG VIỆT: Tách các từ phổ biến bị dính
        processedText = separateVietnameseCommonWords(processedText)

        // 5. Tách các từ có độ dài bất thường (>20 ký tự không có khoảng trắng)
        val words = processedText.split(" ")
        processedText = words.joinToString(" ") { word ->
            if (word.length > 20 && !word.contains(Regex("[0-9]")) && !isSpecialTextThatShouldNotBeTranslated(word)) {
                // Cố gắng tách từ dài thành các phần ngắn hơn
                separateLongWord(word)
            } else {
                word
            }
        }

        return processedText
    }

    /**
     * Tách các từ tiếng Việt phổ biến bị dính
     */
    private fun separateVietnameseCommonWords(text: String): String {
        var result = text

        // Danh sách các từ tiếng Việt phổ biến thường bị dính
        val commonWords = listOf(
            "google", "dịch", "trực", "tuyến", "văn", "bản", "ngôn", "ngữ",
            "chọn", "lựa", "chọn", "tìm", "kiếm", "tải", "xuống", "cài", "đặt",
            "thiết", "lập", "cấu", "hình", "quản", "lý", "người", "dùng",
            "tài", "khoản", "đăng", "nhập", "đăng", "ký", "mật", "khẩu"
        )

        // Tạo các pattern thay thế cho từng từ
        commonWords.forEach { word ->
            // Pattern: word dính với từ khác
            result = result.replace(Regex("($word)([a-zA-Zà-ỹ]{2,})"), "$1 $2")
            result = result.replace(Regex("([a-zA-Zà-ỹ]{2,})($word)"), "$1 $2")
        }

        // Một số pattern đặc biệt thường gặp
        val specificPatterns = mapOf(
            Regex("(google)(dịch)") to "$1 $2",
            Regex("(dịch)(trực)") to "$1 $2",
            Regex("(trực)(tuyến)") to "$1 $2",
            Regex("(văn)(bản)") to "$1 $2",
            Regex("(ngôn)(ngữ)") to "$1 $2",
            Regex("(thiết)(lập)") to "$1 $2",
            Regex("(cài)(đặt)") to "$1 $2",
            Regex("(tài)(khoản)") to "$1 $2",
            Regex("(đăng)(nhập)") to "$1 $2",
            Regex("(đăng)(ký)") to "$1 $2",
            Regex("(mật)(khẩu)") to "$1 $2"
        )

        specificPatterns.forEach { (pattern, replacement) ->
            result = result.replace(pattern, replacement)
        }

        return result
    }

    /**
     * Tách từ dài thành các phần nhỏ hơn dựa trên pattern
     */
    private fun separateLongWord(word: String): String {
        var result = word

        // Tách dựa trên chuyển đổi từ thường sang hoa
        result = result.replace(Regex("([a-z])([A-Z])"), "$1 $2")

        // Tách dựa trên pattern lặp lại
        result = result.replace(Regex("(\\w{3,})\\1"), "$1 $1")

        // Nếu vẫn quá dài, tách mỗi 10-15 ký tự
        if (result.length > 15 && !result.contains(" ")) {
            val chunks = mutableListOf<String>()
            var i = 0
            while (i < result.length) {
                val end = minOf(i + 12, result.length)
                chunks.add(result.substring(i, end))
                i = end
            }
            result = chunks.joinToString(" ")
        }

        return result
    }

    /**
     * Sửa lỗi các từ tiếng Việt bị dính
     */
    private fun fixVietnameseStuckWords(text: String): String {
        var processedText = text

        // 1. Các pattern tiếng Việt thường gặp
        val vietnamesePatterns = mapOf(
            Regex("(của)([A-Z])") to "$1 $2",
            Regex("(với)([A-Z])") to "$1 $2",
            Regex("(trong)([A-Z])") to "$1 $2",
            Regex("(cho)([A-Z])") to "$1 $2",
            Regex("(đến)([A-Z])") to "$1 $2",
            Regex("(từ)([A-Z])") to "$1 $2",
            Regex("(và)([A-Z])") to "$1 $2",
            Regex("(để)([A-Z])") to "$1 $2"
        )

        // 2. Áp dụng các pattern
        vietnamesePatterns.forEach { (pattern, replacement) ->
            processedText = processedText.replace(pattern, replacement)
        }

        // 3. Sửa lỗi dấu thanh tiếng Việt bị OCR sai
        val vietnameseFixMap = mapOf(
            "â" to "ã", "ã" to "ã", // Đôi khi OCR nhầm
            "đ" to "đ", "Đ" to "Đ"  // Đảm bảo đúng ký tự
        )

        return processedText
    }

    /**
     * Xử lý trường hợp text bị dính chữ nghiêm trọng
     */
    private fun handleSevereTextMerging(text: String): String {
        var processedText = text

        // 1. Phát hiện các từ dài bất thường (>15 ký tự không có khoảng trắng)
        val words = processedText.split(" ")
        processedText = words.joinToString(" ") { word ->
            if (word.length > 15 && !word.contains(Regex("[0-9]"))) {
                // Cố gắng tách dựa trên pattern
                separateMergedText(word)
            } else {
                word
            }
        }

        // 2. Tìm và tách các pattern câu bị dính (dấu câu + chữ hoa)
        processedText = processedText.replace(Regex("([.!?])([A-ZÀ-Ý][a-zà-ý])"), "$1 $2")

        // 3. Tách từ có pattern number+text hoặc text+number không hợp lý
        processedText = processedText.replace(Regex("([0-9]{2,})([A-Za-zÀ-ỹ]{3,})"), "$1 $2")
        processedText = processedText.replace(Regex("([A-Za-zÀ-ỹ]{3,})([0-9]{2,})"), "$1 $2")

        // 4. Xử lý case đặc biệt: từ tiếng Việt dính với từ tiếng Anh
        processedText = processedText.replace(Regex("([à-ỹ]+)([A-Z][a-z]+)"), "$1 $2")
        processedText = processedText.replace(Regex("([a-z]+)([À-Ỹ][à-ỹ]+)"), "$1 $2")

        // 5. Sửa lỗi OCR phổ biến: các ký tự bị nhầm
        val ocrFixMap = mapOf(
            "rn" to "m",    // r+n thường bị nhầm thành m
            "cl" to "d",    // c+l thường bị nhầm thành d
            "ii" to "li",   // i+i thường bị nhầm thành li
            "vv" to "w"     // v+v thường bị nhầm thành w
        )

        ocrFixMap.forEach { (wrong, correct) ->
            processedText = processedText.replace(wrong, correct)
        }

        return processedText
    }

    /**
     * Tách text bị merge dựa trên các pattern heuristic
     */
    private fun separateMergedText(text: String): String {
        var result = text

        // Pattern 1: Tách dựa trên chuyển đổi case (camelCase)
        result = result.replace(Regex("([a-z])([A-Z])"), "$1 $2")

        // Pattern 2: Tách dựa trên vowel + consonant clusters bất thường
        result = result.replace(Regex("([aeiouàáảãạêếềểễệôốồổỗộưứừửữựyýỳỷỹỵ]{2,})([bcdfghjklmnpqrstvwxz]{3,})"), "$1 $2")

        // Pattern 3: Tách dựa trên consonant clusters quá dài (>3 phụ âm liên tiếp)
        result = result.replace(Regex("([bcdfghjklmnpqrstvwxz]{3})([bcdfghjklmnpqrstvwxz]+)"), "$1 $2")

        // Pattern 4: Tách từ tiếng Việt dính nhau dựa trên âm tiết
        result = separateVietnameseSyllables(result)

        // Pattern 5: Phát hiện pattern lặp lại và tách
        if (result.length > 8) {
            for (len in 3..6) {
                if (result.length >= len * 2) {
                    val chunk = result.substring(0, len)
                    if (result.substring(len, minOf(len * 2, result.length)) == chunk) {
                        result = result.replace("$chunk$chunk", "$chunk $chunk")
                        break
                    }
                }
            }
        }

        // Pattern 6: Tách từ dựa trên common English/Vietnamese prefixes và suffixes
        result = separateByCommonAffixes(result)

        // Pattern 7: Nếu vẫn quá dài, tách theo độ dài cố định nhưng thông minh hơn
        if (result.length > 20 && !result.contains(" ")) {
            result = intelligentLengthSplit(result)
        }

        return result
    }

    /**
     * Tách từ tiếng Việt dựa trên âm tiết
     */
    private fun separateVietnameseSyllables(text: String): String {
        var result = text

        // Các pattern âm tiết tiếng Việt phổ biến
        val vietnameseSyllablePatterns = listOf(
            // Tách các âm tiết có dấu thanh
            Regex("([àáảãạăắằẳẵặâấầẩẫậ])([bcdfghjklmnpqrstvwxyz])") to "$1 $2",
            Regex("([èéẻẽẹêếềểễệ])([bcdfghjklmnpqrstvwxyz])") to "$1 $2",
            Regex("([ìíỉĩị])([bcdfghjklmnpqrstvwxyz])") to "$1 $2",
            Regex("([òóỏõọôốồổỗộơớờởỡợ])([bcdfghjklmnpqrstvwxyz])") to "$1 $2",
            Regex("([ùúủũụưứừửữự])([bcdfghjklmnpqrstvwxyz])") to "$1 $2",
            Regex("([ỳýỷỹỵ])([bcdfghjklmnpqrstvwxyz])") to "$1 $2",

            // Tách âm tiết kết thúc bằng âm cuối
            Regex("([aeiou])([ng])([A-ZÀÁẢÃẠĂẮẰẲẴẶÂẤẦẨẪẬ])") to "$1$2 $3",
            Regex("([aeiou])([ch])([A-ZÀÁẢÃẠĂẮẰẲẴẶÂẤẦẨẪẬ])") to "$1$2 $3",
            Regex("([aeiou])([nh])([A-ZÀÁẢÃẠĂẮẰẲẴẶÂẤẦẨẪẬ])") to "$1$2 $3"
        )

        vietnameseSyllablePatterns.forEach { (pattern, replacement) ->
            result = result.replace(pattern, replacement)
        }

        return result
    }

    /**
     * Tách từ dựa trên prefix và suffix phổ biến
     */
    private fun separateByCommonAffixes(text: String): String {
        var result = text

        // Common English prefixes
        val englishPrefixes = listOf("un", "re", "pre", "dis", "mis", "over", "under", "out", "up", "sub", "inter", "super")
        // Common English suffixes
        val englishSuffixes = listOf("ing", "ed", "er", "est", "ly", "tion", "sion", "ness", "ment", "able", "ible", "ful", "less")

        // Vietnamese common words
        val vietnameseWords = listOf("không", "được", "nhiều", "người", "thời", "nước", "việc", "năm", "ngày", "tháng")

        // Tách prefix
        englishPrefixes.forEach { prefix ->
            result = result.replace(Regex("($prefix)([A-Z][a-z])"), "$1 $2")
        }

        // Tách suffix
        englishSuffixes.forEach { suffix ->
            result = result.replace(Regex("([a-z])($suffix)([A-Z])"), "$1$2 $3")
        }

        // Tách từ tiếng Việt phổ biến
        vietnameseWords.forEach { word ->
            result = result.replace(Regex("($word)([A-ZÀ-Ý])"), "$1 $2")
            result = result.replace(Regex("([a-zà-ỹ])($word)"), "$1 $2")
        }

        return result
    }

    /**
     * Tách thông minh theo độ dài, ưu tiên tại vowel hoặc consonant boundary
     */
    private fun intelligentLengthSplit(text: String): String {
        val chunks = mutableListOf<String>()
        var currentChunk = ""
        val maxChunkLength = 12

        for (i in text.indices) {
            currentChunk += text[i]

            // Nếu đủ dài và gặp boundary tốt để split
            if (currentChunk.length >= maxChunkLength) {
                val nextChar = text.getOrNull(i + 1)
                val currentChar = text[i]

                // Ưu tiên split tại:
                // 1. Vowel -> Consonant
                // 2. Consonant -> Vowel
                // 3. Lowercase -> Uppercase
                val isGoodSplitPoint = when {
                    nextChar == null -> true
                    isVowel(currentChar) && !isVowel(nextChar) -> true
                    !isVowel(currentChar) && isVowel(nextChar) -> true
                    currentChar.isLowerCase() && nextChar.isUpperCase() -> true
                    else -> false
                }

                if (isGoodSplitPoint) {
                    chunks.add(currentChunk)
                    currentChunk = ""
                }
            }
        }

        // Thêm phần còn lại
        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk)
        }

        return chunks.joinToString(" ")
    }

    /**
     * Kiểm tra ký tự có phải vowel không (bao gồm tiếng Việt)
     */
    private fun isVowel(char: Char): Boolean {
        return char.lowercase() in "aeiouàáảãạăắằẳẵặâấầẩẫậèéẻẽẹêếềểễệìíỉĩịòóỏõọôốồổỗộơớờởỡợùúủũụưứừửữựyýỳỷỹỵ"
    }

    /**
     * Xử lý text đã dịch để cải thiện hiển thị
     */
    private fun postprocessTranslatedText(text: String): String {
        var processedText = text

        // 1. Chuẩn hóa khoảng trắng
        processedText = processedText.replace(Regex("\\s+"), " ")

        // 2. Loại bỏ khoảng trắng trước dấu câu
        processedText = processedText.replace(Regex("\\s+([,.!?;:])"), "$1")

        // 3. Thêm khoảng trắng sau dấu câu nếu thiếu
        processedText = processedText.replace(Regex("([,.!?;:])([a-zA-Z])"), "$1 $2")

        // 4. [BỎ] Không tự động viết hoa sau dấu câu vì có thể là xuống dòng trong đoạn
        // Chỉ viết hoa đầu câu đầu tiên nếu text chưa có chữ hoa
        if (processedText.isNotEmpty() && processedText[0].isLowerCase()) {
            processedText = processedText[0].uppercase() + processedText.substring(1)
        }

        // 5. Trim
        processedText = processedText.trim()

        Log.d(TAG, "Translation postprocessed: '$text' -> '$processedText'")
        return processedText
    }

    /**
     * Kiểm tra xem text có phải là loại đặc biệt cần COPY thay vì DỊCH không
     */
    private fun isSpecialTextThatShouldNotBeTranslated(text: String): Boolean {
        val cleanText = text.trim()

        if (cleanText.length < 3) return false // Quá ngắn thì có thể là text bình thường

        // 1. URLs và Links
        val urlPatterns = listOf(
            Regex("^https?://.*", RegexOption.IGNORE_CASE),
            Regex("^www\\..*\\.[a-z]{2,}", RegexOption.IGNORE_CASE),
            Regex(".*\\.(com|net|org|edu|gov|io|co|ly|me|app)(/.*)?$", RegexOption.IGNORE_CASE),
            Regex("[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}(/\\S*)?") // Generic domain pattern
        )

        if (urlPatterns.any { it.matches(cleanText) }) {
            Log.d(TAG, "Detected URL/Link: '$cleanText'")
            return true
        }

        // 2. API Keys, Tokens, Hash codes (nhiều ký tự alphanumeric liên tiếp)
        val keyPatterns = listOf(
            Regex("^[A-Za-z0-9]{16,}$"), // Key dài >= 16 ký tự
            Regex("^[A-Fa-f0-9]{32}$"), // MD5 hash
            Regex("^[A-Fa-f0-9]{40}$"), // SHA1 hash
            Regex("^[A-Za-z0-9+/]{20,}={0,2}$"), // Base64-like
            Regex("^[A-Za-z0-9_-]{20,}$"), // JWT-like token
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE) // UUID
        )

        if (keyPatterns.any { it.matches(cleanText) }) {
            Log.d(TAG, "Detected API Key/Token: '$cleanText'")
            return true
        }

        // 3. ID codes (có pattern đặc biệt)
        val idPatterns = listOf(
            Regex("^[A-Z]{2,4}[0-9]{4,}$"), // VD: ABC1234
            Regex("^[0-9]{8,}$"), // Dãy số dài
            Regex("^[A-Za-z]{1,3}[0-9]{5,}$"), // VD: A12345
            Regex("^[A-Z0-9]{6,}-[A-Z0-9]{4,}$") // VD: ABC123-DEF456
        )

        if (idPatterns.any { it.matches(cleanText) }) {
            Log.d(TAG, "Detected ID Code: '$cleanText'")
            return true
        }

        // 4. Email addresses
        val emailPattern = Regex("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$")
        if (emailPattern.matches(cleanText)) {
            Log.d(TAG, "Detected Email: '$cleanText'")
            return true
        }

        // 5. File paths
        val pathPatterns = listOf(
            Regex("^[a-zA-Z]:\\\\.*"), // Windows path
            Regex("^/[a-zA-Z0-9_/.-]+"), // Unix path
            Regex(".*\\.[a-zA-Z0-9]{1,5}$") // File with extension
        )

        if (pathPatterns.any { it.matches(cleanText) }) {
            Log.d(TAG, "Detected File Path: '$cleanText'")
            return true
        }

        // 6. Kiểm tra tỷ lệ ký tự đặc biệt - nếu quá nhiều thì có thể là code
        val specialCharCount = cleanText.count { !it.isLetterOrDigit() && it != ' ' }
        val specialCharRatio = specialCharCount.toFloat() / cleanText.length

        if (specialCharRatio > 0.3 && cleanText.length > 10) {
            Log.d(TAG, "Detected code-like text (high special char ratio): '$cleanText'")
            return true
        }

        // 7. Kiểm tra pattern random - nhiều ký tự hoa, số xen kẽ không có nghĩa
        val upperCaseCount = cleanText.count { it.isUpperCase() }
        val digitCount = cleanText.count { it.isDigit() }
        val totalAlphaNum = cleanText.count { it.isLetterOrDigit() }

        if (totalAlphaNum > 8 && (upperCaseCount + digitCount).toFloat() / totalAlphaNum > 0.6) {
            // Nếu >60% là chữ hoa + số thì có thể là random key
            val hasConsecutiveLetters = cleanText.windowed(3).any { window ->
                window.all { it.isLetter() && it.isLowerCase() }
            }

            if (!hasConsecutiveLetters) { // Không có từ bình thường nào
                Log.d(TAG, "Detected random key-like text: '$cleanText'")
                return true
            }
        }

        Log.d(TAG, "Normal text (will be translated): '$cleanText'")
        return false
    }

    /**
     * Khôi phục special content từ placeholder map
     */
    private fun restoreSpecialContent(translatedText: String, specialMap: Map<String, String>): String {
        var restoredText = translatedText
        specialMap.forEach { (placeholder, originalContent) ->
            restoredText = restoredText.replace(placeholder, originalContent)
        }
        return restoredText
    }

    /**
     * Phân tích và xử lý thông minh các OCR blocks
     */
    private fun preprocessOcrBlocks(blocks: List<OcrResult.Block>): List<OcrResult.Block> {
        if (blocks.isEmpty()) return blocks

        Log.d(TAG, "Preprocessing ${blocks.size} OCR blocks")

        // Bước 1: Lọc bỏ các block có thể là logo/biểu tượng
        val filteredBlocks = blocks.filter { block ->
            isValidTextBlock(block)
        }

        Log.d(TAG, "After filtering: ${filteredBlocks.size} blocks remain")

        // Bước 2: Nhóm các block thành đoạn văn
        val groupedBlocks = groupBlocksIntoParagraphs(filteredBlocks)

        Log.d(TAG, "Grouped into ${groupedBlocks.size} paragraphs")

        return groupedBlocks
    }

    private fun isValidTextBlock(block: OcrResult.Block): Boolean {
        val text = block.text.trim()

        // Lọc bỏ text quá ngắn (có thể là noise)
        if (text.length < 2) {
            Log.d(TAG, "Filtered short text: '$text'")
            return false
        }

        // Lọc bỏ text chỉ có ký tự đặc biệt (có thể là logo/icon)
        val specialCharPattern = Regex("^[^a-zA-ZÀ-ỹ0-9\\s]+$")
        if (specialCharPattern.matches(text)) {
            Log.d(TAG, "Filtered special chars only: '$text'")
            return false
        }

        // Lọc bỏ text có quá nhiều ký tự lặp (có thể là pattern/decoration)
        if (hasExcessiveRepeatingChars(text)) {
            Log.d(TAG, "Filtered excessive repeating: '$text'")
            return false
        }

        // Lọc bỏ text có tỷ lệ số/ký tự đặc biệt quá cao (có thể là ID, code)
        val alphaCount = text.count { it.isLetter() }
        val totalCount = text.length
        if (totalCount > 3 && alphaCount.toFloat() / totalCount < 0.3) {
            Log.d(TAG, "Filtered low alpha ratio: '$text'")
            return false
        }

        return true
    }

    private fun hasExcessiveRepeatingChars(text: String): Boolean {
        if (text.length < 3) return false

        var maxRepeat = 1
        var currentRepeat = 1

        for (i in 1 until text.length) {
            if (text[i] == text[i-1]) {
                currentRepeat++
                maxRepeat = maxOf(maxRepeat, currentRepeat)
            } else {
                currentRepeat = 1
            }
        }

        // Nếu có ký tự lặp quá 3 lần liên tiếp
        return maxRepeat > 3
    }

    private fun groupBlocksIntoParagraphs(blocks: List<OcrResult.Block>): List<OcrResult.Block> {
        if (blocks.size <= 1) return blocks

        val grouped = mutableListOf<OcrResult.Block>()
        val sortedBlocks = blocks.sortedWith(compareBy<OcrResult.Block> { it.boundingBox?.top ?: 0 }
                                                .thenBy { it.boundingBox?.left ?: 0 })

        var currentGroup = mutableListOf<OcrResult.Block>()

        for (i in sortedBlocks.indices) {
            val currentBlock = sortedBlocks[i]

            if (currentGroup.isEmpty()) {
                currentGroup.add(currentBlock)
                continue
            }

            val lastBlock = currentGroup.last()

            // Kiểm tra xem có nên ghép với đoạn hiện tại không
            if (shouldMergeWithParagraph(lastBlock, currentBlock)) {
                currentGroup.add(currentBlock)
                Log.d(TAG, "Merged '${lastBlock.text.take(20)}...' with '${currentBlock.text.take(20)}...'")
            } else {
                // Kết thúc đoạn hiện tại và bắt đầu đoạn mới
                if (currentGroup.isNotEmpty()) {
                    grouped.add(mergeBlocksIntoOne(currentGroup))
                }
                currentGroup = mutableListOf(currentBlock)
            }
        }

        // Thêm nhóm cuối cùng
        if (currentGroup.isNotEmpty()) {
            grouped.add(mergeBlocksIntoOne(currentGroup))
        }

        return grouped
    }

    private fun shouldMergeWithParagraph(block1: OcrResult.Block, block2: OcrResult.Block): Boolean {
        val text1 = block1.text.trim()
        val text2 = block2.text.trim()
        val bbox1 = block1.boundingBox
        val bbox2 = block2.boundingBox

        if (bbox1 == null || bbox2 == null) return false

        // Tính khoảng cách dọc giữa 2 block
        val verticalDistance = (bbox2.top - bbox1.bottom).toFloat()
        val block1Height = bbox1.height()
        val block2Height = bbox2.height()
        val avgHeight = (block1Height + block2Height) / 2f

        // Tính khoảng cách ngang
        val horizontalOverlap = minOf(bbox1.right, bbox2.right) - maxOf(bbox1.left, bbox2.left)
        val minWidth = minOf(bbox1.width(), bbox2.width())

        Log.d(TAG, "Merge check: '$text1' -> '$text2'")
        Log.d(TAG, "  Vertical distance: $verticalDistance, avg height: $avgHeight")
        Log.d(TAG, "  Horizontal overlap: $horizontalOverlap, min width: $minWidth")
        Log.d(TAG, "  Heights: ${block1Height} vs ${block2Height}")

        // Kiểm tra 0: Phát hiện style khác nhau (title vs description)
        if (hasSignificantStyleDifference(text1, text2, block1Height, block2Height)) {
            Log.d(TAG, "  -> Significant style difference detected")
            return false
        }

        // Kiểm tra 1: Khoảng cách dọc hợp lý (không quá xa nhau)
        if (verticalDistance > avgHeight * 1.2) { // Giảm từ 1.5 xuống 1.2 để nghiêm ngặt hơn
            Log.d(TAG, "  -> Too far vertically")
            return false
        }

        // Kiểm tra 2: Có overlap ngang đủ lớn (cùng cột/đoạn)
        if (horizontalOverlap < minWidth * 0.4) { // Tăng từ 0.3 lên 0.4 để nghiêm ngặt hơn
            Log.d(TAG, "  -> Insufficient horizontal overlap")
            return false
        }

        // Kiểm tra 3: Phân tích ngữ pháp để quyết định ghép
        val shouldMergeByGrammar = shouldMergeByGrammarRules(text1, text2, verticalDistance, avgHeight)

        Log.d(TAG, "  -> Grammar decision: $shouldMergeByGrammar")
        return shouldMergeByGrammar
    }

    private fun shouldMergeByGrammarRules(text1: String, text2: String, verticalDistance: Float, avgHeight: Float): Boolean {
        // Kiểm tra dấu câu kết thúc ở text1
        val hasEndPunctuation = text1.matches(Regex(".*[.!?;:]\\s*$"))

        // Kiểm tra viết hoa đầu câu ở text2
        val startsWithCapital = text2.matches(Regex("^[A-ZÀ-Ý].*"))

        // Kiểm tra khoảng cách - nếu quá gần thì có thể cùng đoạn
        val isCloseVertically = verticalDistance < avgHeight * 0.8

        // Kiểm tra xuống dòng trong cùng đoạn văn
        val isLineBreakInSameParagraph = analyzeLineBreakContext(text1, text2, verticalDistance, avgHeight)

        Log.d(TAG, "    Grammar analysis:")
        Log.d(TAG, "      Text1 ends with punctuation: $hasEndPunctuation")
        Log.d(TAG, "      Text2 starts with capital: $startsWithCapital")
        Log.d(TAG, "      Close vertically: $isCloseVertically")
        Log.d(TAG, "      Line break in same paragraph: $isLineBreakInSameParagraph")

        // LOGIC MỚI: Ưu tiên phân tích xuống dòng trong đoạn văn
        if (isLineBreakInSameParagraph) {
            Log.d(TAG, "      -> Merged: Same paragraph line break detected")
            return true
        }

        // Quy tắc 1: Nếu text1 kết thúc bằng dấu câu VÀ text2 bắt đầu bằng chữ hoa
        // -> Kiểm tra kỹ xem có phải đoạn mới thật sự không
        if (hasEndPunctuation && startsWithCapital) {
            // Nếu khoảng cách quá gần và không có dấu hiệu đoạn mới -> vẫn cùng đoạn
            val isParagraphBreak = isProbablyNewParagraph(text1, text2, verticalDistance, avgHeight)
            Log.d(TAG, "      -> Punctuation + Capital: isParagraphBreak = $isParagraphBreak")
            return !isParagraphBreak
        }

        // Quy tắc 2: Nếu text1 KHÔNG kết thúc bằng dấu câu VÀ text2 KHÔNG bắt đầu bằng chữ hoa
        // -> Rất có thể cùng câu/đoạn
        if (!hasEndPunctuation && !startsWithCapital) {
            return true
        }

        // Quy tắc 3: Nếu text1 KHÔNG kết thúc bằng dấu câu NHƯNG text2 bắt đầu bằng chữ hoa
        // -> Có thể text1 bị cắt, nhưng nếu khoảng cách gần thì ghép
        if (!hasEndPunctuation && startsWithCapital) {
            return isCloseVertically
        }

        // Quy tắc 4: Nếu text1 kết thúc bằng dấu câu NHƯNG text2 KHÔNG bắt đầu bằng chữ hoa
        // -> Có thể cùng đoạn nếu khoảng cách gần
        if (hasEndPunctuation && !startsWithCapital) {
            return isCloseVertically
        }

        // Mặc định: không ghép nếu không chắc chắn
        return false
    }

    /**
     * Phân tích xem có phải xuống dòng trong cùng đoạn văn không
     */
    private fun analyzeLineBreakContext(text1: String, text2: String, verticalDistance: Float, avgHeight: Float): Boolean {
        // 1. Kiểm tra khoảng cách: xuống dòng trong đoạn thường có khoảng cách nhỏ
        val isNormalLineSpacing = verticalDistance < avgHeight * 1.0 // Bình thường là 1 line height

        // 2. Kiểm tra text1 không kết thúc như kết thúc đoạn
        val text1EndsLikeParagraph = text1.matches(Regex(".*[.!?]\\s*$")) && text1.length > 30

        // 3. Kiểm tra text2 không bắt đầu như đầu đoạn mới
        val text2StartsLikeNewParagraph = text2.matches(Regex("^[A-ZÀ-Ý][a-zà-ỹ]+\\s+[A-ZÀ-Ý].*")) || // "The Title..."
                                         text2.matches(Regex("^[0-9]+\\..*")) || // "1. Something"
                                         text2.matches(Regex("^[•\\-\\*]\\s+.*")) // "• Bullet point"

        // 4. Kiểm tra độ dài: dòng xuống dòng trong đoạn thường không quá ngắn
        val reasonableLength = text1.length > 10 && text2.length > 5

        // 5. Kiểm tra pattern thường gặp của xuống dòng trong đoạn
        val commonLineBreakPatterns = listOf(
            // Text1 kết thúc bằng từ thường, text2 bắt đầu bằng từ thường
            text1.matches(Regex(".*[a-zà-ỹ]\\s*$")) && text2.matches(Regex("^[a-zà-ỹ].*")),
            // Text1 kết thúc bằng dấu phẩy, text2 tiếp tục
            text1.matches(Regex(".*,\\s*$")),
            // Text1 kết thúc bằng "và", "hoặc", "nhưng", text2 tiếp tục
            text1.matches(Regex(".*(và|hoặc|nhưng|mà|nên|để|khi|nếu)\\s*$")),
            // Text2 bắt đầu bằng từ nối
            text2.matches(Regex("^(và|hoặc|nhưng|mà|nên|để|khi|nếu|có thể|sẽ|đã|đang)\\s+.*"))
        )

        val hasLineBreakPattern = commonLineBreakPatterns.any { it }

        Log.d(TAG, "        Line break analysis:")
        Log.d(TAG, "          Normal line spacing: $isNormalLineSpacing")
        Log.d(TAG, "          Text1 ends like paragraph: $text1EndsLikeParagraph")
        Log.d(TAG, "          Text2 starts like new paragraph: $text2StartsLikeNewParagraph")
        Log.d(TAG, "          Reasonable length: $reasonableLength")
        Log.d(TAG, "          Has line break pattern: $hasLineBreakPattern")

        // Kết luận: Là xuống dòng trong đoạn nếu:
        // - Khoảng cách bình thường VÀ
        // - Không có dấu hiệu kết thúc/bắt đầu đoạn VÀ
        // - Có độ dài hợp lý VÀ
        // - Có pattern xuống dòng trong đoạn
        return isNormalLineSpacing &&
               !text1EndsLikeParagraph &&
               !text2StartsLikeNewParagraph &&
               reasonableLength &&
               hasLineBreakPattern
    }

    /**
     * Kiểm tra xem có phải thực sự là đoạn văn mới không
     */
    private fun isProbablyNewParagraph(text1: String, text2: String, verticalDistance: Float, avgHeight: Float): Boolean {
        // 1. Khoảng cách lớn hơn bình thường (>1.5 line height)
        val hasLargeGap = verticalDistance > avgHeight * 1.5

        // 2. Text1 kết thúc với câu hoàn chỉnh dài
        val text1IsCompleteSentence = text1.matches(Regex(".*[.!?]\\s*$")) && text1.length > 20

        // 3. Text2 bắt đầu như một đoạn mới
        val text2StartsLikeNewParagraph = text2.matches(Regex("^[A-ZÀ-Ý][a-zà-ỹ]+\\s+.*")) && text2.length > 15

        // 4. Có pattern đặc biệt của đoạn mới
        val hasNewParagraphPattern = text2.matches(Regex("^(Tuy nhiên|Ngoài ra|Bên cạnh đó|Mặt khác|Cuối cùng|Đầu tiên|Thứ hai|Thứ ba).*")) ||
                                    text2.matches(Regex("^[0-9]+\\..*")) ||
                                    text2.matches(Regex("^[A-ZÀ-Ý]{2,}.*")) // Tiêu đề viết hoa

        Log.d(TAG, "        New paragraph analysis:")
        Log.d(TAG, "          Large gap: $hasLargeGap")
        Log.d(TAG, "          Text1 complete sentence: $text1IsCompleteSentence")
        Log.d(TAG, "          Text2 starts like new paragraph: $text2StartsLikeNewParagraph")
        Log.d(TAG, "          Has new paragraph pattern: $hasNewParagraphPattern")

        return (hasLargeGap && text1IsCompleteSentence && text2StartsLikeNewParagraph) ||
               hasNewParagraphPattern
    }

    private fun hasSignificantStyleDifference(text1: String, text2: String, height1: Int, height2: Int): Boolean {
        // Kiểm tra 1: Sự khác biệt về kích thước (title vs description)
        val heightRatio = maxOf(height1, height2).toFloat() / minOf(height1, height2).toFloat()
        if (heightRatio > 1.4) { // Chênh lệch chiều cao > 40%
            Log.d(TAG, "    Style check: Height ratio = $heightRatio (significant)")
            return true
        }

        // Kiểm tra 2: Phát hiện pattern title/header (ngắn, chữ hoa, ít dấu câu)
        val isTitlePattern = text1.length <= 50 && // Ngắn
                           (text1.count { it.isUpperCase() }.toFloat() / text1.length > 0.3 || // Nhiều chữ hoa
                            text1.all { it.isLetterOrDigit() || it.isWhitespace() || it in ".-_" }) // Ít ký tự đặc biệt

        val isDescriptionPattern = text2.length > 20 && // Dài hơn
                                 text2.contains(' ') && // Có khoảng trắng
                                 text2.count { it in ".,!?;:" } > 0 // Có dấu câu

        if (isTitlePattern && isDescriptionPattern) {
            Log.d(TAG, "    Style check: Title-Description pattern detected")
            return true
        }

        // Kiểm tra 3: Text quá khác biệt về độ dài (title ngắn vs paragraph dài)
        val lengthRatio = maxOf(text1.length, text2.length).toFloat() / minOf(text1.length, text2.length).toFloat()
        if (lengthRatio > 3.0 && minOf(text1.length, text2.length) < 30) {
            Log.d(TAG, "    Style check: Length ratio = $lengthRatio (very different)")
            return true
        }

        // Kiểm tra 4: Phát hiện text dính chữ (không có khoảng trắng trong text dài)
        val hasSpaceIssue = (text1.length > 10 && !text1.contains(' ')) ||
                           (text2.length > 10 && !text2.contains(' '))
        if (hasSpaceIssue) {
            Log.d(TAG, "    Style check: Potential text merging issue detected")
            // Không return true ở đây vì đây là vấn đề OCR, không phải style
        }

        return false
    }

    private fun mergeBlocksIntoOne(blocks: List<OcrResult.Block>): OcrResult.Block {
        if (blocks.size == 1) {
            // Nếu chỉ có 1 block, vẫn cần preprocess
            val processedText = preprocessOcrText(blocks[0].text.trim())
            return blocks[0].copy(text = processedText)
        }

        // Tính bounding box tổng hợp
        var minLeft = Int.MAX_VALUE
        var minTop = Int.MAX_VALUE
        var maxRight = Int.MIN_VALUE
        var maxBottom = Int.MIN_VALUE

        blocks.forEach { block ->
            block.boundingBox?.let { bbox ->
                minLeft = minOf(minLeft, bbox.left)
                minTop = minOf(minTop, bbox.top)
                maxRight = maxOf(maxRight, bbox.right)
                maxBottom = maxOf(maxBottom, bbox.bottom)
            }
        }

        // QUAN TRỌNG: Ghép text RAW trước, sau đó preprocess một lần duy nhất
        // Điều này ngăn việc preprocess từng dòng riêng biệt gây ra viết hoa sai
        val rawMergedText = blocks.joinToString(" ") { it.text.trim() }
        val mergedText = preprocessOcrText(rawMergedText)

        Log.d(TAG, "Merged ${blocks.size} blocks:")
        blocks.forEachIndexed { index, block ->
            Log.d(TAG, "  Block $index: '${block.text.trim()}'")
        }
        Log.d(TAG, "  Raw merged: '$rawMergedText'")
        Log.d(TAG, "  Final processed: '$mergedText'")

        // Tạo block mới
        val mergedBoundingBox = if (minLeft != Int.MAX_VALUE) {
            android.graphics.Rect(minLeft, minTop, maxRight, maxBottom)
        } else {
            blocks[0].boundingBox
        }

        // Tạo combined lines từ tất cả blocks
        val mergedLines = blocks.flatMap { it.lines }

        return OcrResult.Block(mergedText, mergedBoundingBox, mergedLines)
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
                captureScreenWithAllUIHidden()
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
        return captureScreenWithAllUIHidden()
    }

    private suspend fun captureScreenWithAllUIHidden(): Bitmap? {
        return withContext(Dispatchers.Main) {
            // Lưu trạng thái hiện tại của panel
            val wasPanelOpen = currentState is ServiceState.PANEL_OPEN

            // Lưu trạng thái hiện tại của các UI elements
            val bubbleVisibility = floatingBubbleView?.visibility
            val globalOverlayVisibility = globalOverlay?.visibility
            val languageSheetVisibility = languageSheetView?.visibility
            val regionSelectVisibility = regionSelectOverlay?.visibility
            val regionResultVisibility = regionResultOverlay?.visibility
            val copyTextVisibility = copyTextOverlay?.visibility
            val imageTranslationVisibility = imageTranslationOverlay?.visibility
            val magnifierLensVisibility = magnifierLensView?.visibility

            // Lưu trạng thái magnifier result views
            val magnifierResultVisibilities = magnifierResultViews.map { it.visibility }

            try {
                Log.d(TAG, "Hiding all UI elements for screen capture...")

                // Đóng panel trước nếu đang mở
                if (wasPanelOpen) {
                    Log.d(TAG, "Closing panel before capture...")
                    floatingBubbleView?.closePanel()
                    delay(100) // Chờ panel đóng hoàn toàn
                }

                // Ẩn tất cả UI elements
                floatingBubbleView?.visibility = View.INVISIBLE
                globalOverlay?.visibility = View.INVISIBLE
                languageSheetView?.visibility = View.INVISIBLE
                regionSelectOverlay?.visibility = View.INVISIBLE
                regionResultOverlay?.visibility = View.INVISIBLE
                copyTextOverlay?.visibility = View.INVISIBLE
                imageTranslationOverlay?.visibility = View.INVISIBLE
                magnifierLensView?.visibility = View.INVISIBLE

                // Ẩn tất cả magnifier result views
                magnifierResultViews.forEach { it.visibility = View.INVISIBLE }

                // Chờ một chút để UI update
                delay(100) // Tăng delay để đảm bảo UI được ẩn hoàn toàn

                // Chụp màn hình
                val bitmap = captureScreen()

                Log.d(TAG, "Screen captured, restoring UI elements...")

                return@withContext bitmap

            } finally {
                // Khôi phục trạng thái ban đầu của các UI elements
                floatingBubbleView?.visibility = bubbleVisibility ?: View.VISIBLE
                globalOverlay?.visibility = globalOverlayVisibility ?: View.VISIBLE
                languageSheetView?.visibility = languageSheetVisibility ?: View.VISIBLE
                regionSelectOverlay?.visibility = regionSelectVisibility ?: View.VISIBLE
                regionResultOverlay?.visibility = regionResultVisibility ?: View.VISIBLE
                copyTextOverlay?.visibility = copyTextVisibility ?: View.VISIBLE
                imageTranslationOverlay?.visibility = imageTranslationVisibility ?: View.VISIBLE
                magnifierLensView?.visibility = magnifierLensVisibility ?: View.VISIBLE

                // Khôi phục magnifier result views
                magnifierResultViews.forEachIndexed { index, view ->
                    view.visibility = magnifierResultVisibilities.getOrNull(index) ?: View.VISIBLE
                }

                // Khôi phục panel nếu trước đó đang mở
                if (wasPanelOpen) {
                    Log.d(TAG, "Reopening panel after capture...")
                    delay(50) // Chờ UI elements khôi phục trước
                    floatingBubbleView?.openPanel()
                    setState(ServiceState.PANEL_OPEN) // Đảm bảo state đúng
                }

                Log.d(TAG, "All UI elements restored")
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
        val paddingDp = 8f  // Tăng padding để text có không gian thoải mái hơn
        val paddingPx = (paddingDp * resources.displayMetrics.density).toInt()

        // Sử dụng kích thước base nhỏ hơn, để TranslationResultView tự expand
        val baseWidth = maxOf(120, position.width()) // Kích thước tối thiểu 120px
        val baseHeight = maxOf(40, position.height()) // Kích thước tối thiểu 40px
        val viewWidth = baseWidth + (paddingPx * 2)
        val viewHeight = baseHeight + (paddingPx * 2)

        // Initialize với kích thước base, cho phép auto-resize
        resultView.initializeSize(viewWidth, viewHeight)

        // Set listener để handle thay đổi kích thước (giống ImageTranslationOverlay logic)
        resultView.setOnSizeChangeListener { newWidth, newHeight ->
            Log.d(TAG, "Magnifier result view size changed: ${newWidth}x${newHeight}")

            // Cập nhật layout params khi kích thước thay đổi
            val currentParams = resultView.layoutParams as? WindowManager.LayoutParams
            if (currentParams != null) {
                // Tìm vị trí mới không bị overlap với kích thước mới
                val currentRect = Rect(currentParams.x, currentParams.y,
                                     currentParams.x + currentParams.width,
                                     currentParams.y + currentParams.height)
                val newRect = findNonOverlappingMagnifierPositionForResize(currentRect, newWidth, newHeight)

                // Cập nhật layout params
                currentParams.width = newWidth
                currentParams.height = newHeight
                currentParams.x = newRect.left
                currentParams.y = newRect.top

                try {
                    windowManager.updateViewLayout(resultView, currentParams)
                    Log.d(TAG, "Updated magnifier view layout to ${newWidth}x${newHeight} at (${newRect.left}, ${newRect.top})")
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating magnifier view layout: ${e.message}")
                }
            }
        }

        // Tìm vị trí không bị đè lên nhau
        val originalRect = Rect(position.left - paddingPx, position.top - paddingPx,
                               position.right + paddingPx, position.bottom + paddingPx)
        val finalRect = findNonOverlappingMagnifierPosition(originalRect, viewWidth, viewHeight)
        magnifierUsedRects.add(finalRect)

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val params = createOverlayLayoutParams(viewWidth, viewHeight, flags).apply {
            x = finalRect.left
            y = finalRect.top
        }
        windowManager.addView(resultView, params)
        return resultView
    }

    private fun findNonOverlappingMagnifierPosition(originalRect: Rect, width: Int, height: Int): Rect {
        var candidateRect = Rect(originalRect.left, originalRect.top,
                                 originalRect.left + width, originalRect.top + height)

        // Kiểm tra xem có đè lên nhau không
        var attempts = 0
        val maxAttempts = 20
        val offsetStep = 20 // pixel để dịch chuyển

        while (attempts < maxAttempts && isMagnifierOverlapping(candidateRect)) {
            attempts++

            // Thử dịch chuyển theo các hướng khác nhau
            when (attempts % 4) {
                0 -> candidateRect.offset(0, offsetStep) // xuống dưới
                1 -> candidateRect.offset(offsetStep, 0) // sang phải
                2 -> candidateRect.offset(0, -offsetStep) // lên trên
                3 -> candidateRect.offset(-offsetStep, 0) // sang trái
            }

            // Đảm bảo không ra khỏi màn hình
            candidateRect = keepMagnifierWithinBounds(candidateRect, width, height)
        }

        return candidateRect
    }

    private fun findNonOverlappingMagnifierPositionForResize(currentRect: Rect, newWidth: Int, newHeight: Int): Rect {
        // Thử giữ nguyên vị trí hiện tại trước
        var candidateRect = Rect(currentRect.left, currentRect.top,
                                 currentRect.left + newWidth, currentRect.top + newHeight)

        // Nếu không overlap, giữ nguyên vị trí
        if (!isMagnifierOverlapping(candidateRect)) {
            candidateRect = keepMagnifierWithinBounds(candidateRect, newWidth, newHeight)
            return candidateRect
        }

        // Nếu overlap, tìm vị trí mới
        var attempts = 0
        val maxAttempts = 20
        val offsetStep = 15

        while (attempts < maxAttempts && isMagnifierOverlapping(candidateRect)) {
            attempts++

            // Thử các hướng mở rộng thông minh
            when (attempts % 8) {
                0 -> candidateRect.offset(0, offsetStep) // xuống dưới
                1 -> candidateRect.offset(offsetStep, 0) // sang phải
                2 -> candidateRect.offset(0, -offsetStep) // lên trên
                3 -> candidateRect.offset(-offsetStep, 0) // sang trái
                4 -> candidateRect.offset(offsetStep, offsetStep) // chéo phải dưới
                5 -> candidateRect.offset(-offsetStep, offsetStep) // chéo trái dưới
                6 -> candidateRect.offset(offsetStep, -offsetStep) // chéo phải trên
                7 -> candidateRect.offset(-offsetStep, -offsetStep) // chéo trái trên
            }

            candidateRect = keepMagnifierWithinBounds(candidateRect, newWidth, newHeight)
        }

        return candidateRect
    }

    private fun isMagnifierOverlapping(rect: Rect): Boolean {
        return magnifierUsedRects.any { usedRect ->
            Rect.intersects(rect, usedRect)
        }
    }

    private fun keepMagnifierWithinBounds(rect: Rect, width: Int, height: Int): Rect {
        val screenSize = getRealScreenSizePx()

        var newLeft = rect.left
        var newTop = rect.top

        // Đảm bảo không ra khỏi bên phải
        if (newLeft + width > screenSize.x) {
            newLeft = screenSize.x - width
        }

        // Đảm bảo không ra khỏi bên dưới
        if (newTop + height > screenSize.y) {
            newTop = screenSize.y - height
        }

        // Đảm bảo không ra khỏi bên trái
        if (newLeft < 0) {
            newLeft = 0
        }

        // Đảm bảo không ra khỏi bên trên
        if (newTop < 0) {
            newTop = 0
        }

        return Rect(newLeft, newTop, newLeft + width, newTop + height)
    }

    private fun removeAllMagnifierResults() {
        magnifierResultViews.forEach { if (it.isAttachedToWindow) windowManager.removeView(it) }
        magnifierResultViews.clear()
        magnifierUsedRects.clear() // Reset danh sách vị trí đã sử dụng
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

                    // XỬ LÝ TEXT để đảm bảo có khoảng trắng đúng cho Copy mode
                    val processedText = fixCopyTextSpacing(block.text)

                    Log.d(TAG, "Copy text block:")
                    Log.d(TAG, "  Original: '${block.text}'")
                    Log.d(TAG, "  Processed: '$processedText'")

                    // Không cần điều chỉnh tọa độ vì mapRectFromBitmapToScreen đã xử lý đúng
                    CopyTextResult(processedText, screenRect)
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

    /**
     * Sửa lỗi dính chữ đặc biệt cho Copy Text mode
     * Không làm thay đổi ý nghĩa, chỉ thêm khoảng trắng bị thiếu
     */
    private fun fixCopyTextSpacing(text: String): String {
        var result = text.trim()

        // 1. Chuẩn hóa khoảng trắng cơ bản
        result = result.replace(Regex("\\s+"), " ")

        // 2. Thêm khoảng trắng giữa từ tiếng Việt dính nhau
        result = result.replace(Regex("([a-zà-ỹ])([A-ZÀ-Ý])"), "$1 $2")

        // 3. Thêm khoảng trắng giữa chữ cái và số
        result = result.replace(Regex("([a-zA-Zà-ỹ])([0-9])"), "$1 $2")
        result = result.replace(Regex("([0-9])([a-zA-Zà-ỹ])"), "$1 $2")

        // 4. Xử lý các từ tiếng Việt thường dính nhau
        val vietnameseWords = listOf("google", "dịch", "trực", "tuyến", "văn", "bản", "chọn", "ngôn", "ngữ")
        vietnameseWords.forEach { word ->
            // Tìm pattern: từ này dính với từ khác
            val patterns = listOf(
                Regex("($word)([a-zA-Zà-ỹ]{2,})") to "$1 $2",
                Regex("([a-zA-Zà-ỹ]{2,})($word)") to "$1 $2"
            )
            patterns.forEach { (pattern, replacement) ->
                result = result.replace(pattern, replacement)
            }
        }

        // 5. Sử lý pattern đặc biệt "googledịch" -> "google dịch"
        result = result.replace(Regex("(google)(dịch)"), "$1 $2")
        result = result.replace(Regex("(dịch)(trực)"), "$1 $2")
        result = result.replace(Regex("(trực)(tuyến)"), "$1 $2")

        // 6. Thêm khoảng trắng sau dấu câu nếu thiếu
        result = result.replace(Regex("([.!?,;:])([a-zA-Zà-ỹ])"), "$1 $2")

        // 7. Loại bỏ khoảng trắng thừa
        result = result.replace(Regex("\\s+"), " ").trim()

        return result
    }

    private fun showImageTranslationResults(results: List<ImageTranslationResult>, imagePath: String?) {
        Log.d(TAG, "showImageTranslationResults called with ${results.size} results and image path: $imagePath")

        // Đóng tất cả overlay khác
        setState(ServiceState.IDLE)

        if (imagePath == null) {
            Toast.makeText(this, "Không thể hiển thị ảnh nền", Toast.LENGTH_SHORT).show()
            return
        }

        // Tải bitmap từ file tạm thời
        val bitmap = try {
            BitmapFactory.decodeFile(imagePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load image from path: $imagePath", e)
            Toast.makeText(this, "Không thể tải ảnh", Toast.LENGTH_SHORT).show()
            return
        }

        if (bitmap == null) {
            Toast.makeText(this, "Không thể tải ảnh", Toast.LENGTH_SHORT).show()
            return
        }

        // Tạo và hiển thị ImageTranslationOverlay
        imageTranslationOverlay = ImageTranslationOverlay(themedContext).apply {
            onCloseListener = {
                hideImageTranslationOverlay()
            }
        }

        val params = createOverlayLayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            0
        )

        windowManager.addView(imageTranslationOverlay, params)
        setState(ServiceState.IMAGE_TRANSLATION_ACTIVE)

        // Thiết lập ảnh nền và hiển thị kết quả
        imageTranslationOverlay?.setBackgroundImage(bitmap)
        imageTranslationOverlay?.showLoading()

        // Đợi một frame để overlay được layout
        imageTranslationOverlay?.post {
            imageTranslationOverlay?.hideLoading()
            if (results.isEmpty()) {
                Toast.makeText(this@OverlayService, "Không có kết quả dịch", Toast.LENGTH_SHORT).show()
            } else {
                imageTranslationOverlay?.addTranslationResults(results, bitmap)
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

    private fun removeImageTranslationOverlay() {
        imageTranslationOverlay?.let {
            if (it.isAttachedToWindow) windowManager.removeView(it)
        }
        imageTranslationOverlay = null
    }

    private fun hideImageTranslationOverlay() {
        removeImageTranslationOverlay()
        setState(ServiceState.IDLE)
    }

    private fun removeCopyTextOverlay() {
        copyTextOverlay?.let {
            if (it.isAttachedToWindow) windowManager.removeView(it)
        }
        copyTextOverlay = null
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