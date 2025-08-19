package com.example.apptranslate.ui.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.media.projection.MediaProjection
import android.os.Build
import android.transition.TransitionManager
import android.util.AttributeSet
import android.util.Log
import android.view.*
import android.view.ContextThemeWrapper
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.apptranslate.R
import com.example.apptranslate.databinding.ViewFloatingBubbleBinding
import com.example.apptranslate.databinding.ViewFloatingControlPanelBinding
import com.example.apptranslate.ui.overlay.adapter.FunctionAdapter
import com.example.apptranslate.ui.overlay.model.FunctionItem
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*
import kotlin.math.abs

/**
 * Nút nổi đa chức năng với các hành vi nâng cao:
 * - Di chuyển và hít vào cạnh (snap-to-edge)
 * - Tự động thu nhỏ sau 3 giây không hoạt động
 * - Nhấn giữ (long press) để kích hoạt chế độ kính lúp
 * - Chạm (single tap) để mở panel điều khiển
 */
@SuppressLint("ViewConstructor")
class FloatingBubbleView(
    context: Context,
    private val coroutineScope: CoroutineScope,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    // Callback khi người dùng nhấn nút chọn ngôn ngữ
    private var onLanguageSelectClickListener: (() -> Unit)? = null

    companion object {
        private const val TAG = "FloatingBubbleView"
        private const val SNAP_ANIMATION_DURATION = 300L
        private const val COLLAPSE_DELAY_MS = 3000L
        private const val PANEL_MEASURE_DELAY = 100L
    }

    private val binding: ViewFloatingBubbleBinding
    private lateinit var windowManager: WindowManager
    private lateinit var layoutParams: WindowManager.LayoutParams
    private var mediaProjection: MediaProjection? = null
    
    // Control panel binding
    private lateinit var controlPanelBinding: ViewFloatingControlPanelBinding
    
    // Function adapter
    private lateinit var functionAdapter: FunctionAdapter
    
    // State flags
    private var isPanelVisible = false

    // Touch handling
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var longPressOccurred = false

    // Screen dimensions
    private var screenWidth = 0
    private var screenHeight = 0

    // Coroutine job cho việc tự động thu nhỏ
    private var collapseJob: Job? = null
    private var isCollapsed = false

    // GestureDetector để phân biệt chạm, nhấn giữ và kéo
    private val gestureDetector: GestureDetector

    fun setViewLayoutParams(params: WindowManager.LayoutParams) {
        this.layoutParams = params
    }

    init {
        // Sử dụng ContextThemeWrapper để đảm bảo có theme Material Components khi inflate layout
        val themedContext = android.view.ContextThemeWrapper(
            context, 
            com.google.android.material.R.style.Theme_MaterialComponents_Light
        )
        val inflater = LayoutInflater.from(themedContext)
        binding = ViewFloatingBubbleBinding.inflate(inflater, this, true)
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Lấy kích thước màn hình
        updateScreenDimensions()

        // Khởi tạo GestureDetector với GestureListener tùy chỉnh
        gestureDetector = GestureDetector(context, GestureListener())
        
        // Khởi tạo Control Panel binding - đảm bảo áp dụng theme cho view
        val themedPanel = binding.controlPanel.root
        controlPanelBinding = ViewFloatingControlPanelBinding.bind(themedPanel)

        setupBubbleView()
        setupTouchListener()
        setupControlPanel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post {
            // Đảm bảo panel bị ẩn khi mới khởi tạo
            binding.controlPanel.root.visibility = View.GONE
            binding.bubbleView.visibility = View.VISIBLE
            isPanelVisible = false
            
            snapToEdge()
            startCollapseTimer()
        }
    }
    
    /**
     * Xử lý khi thay đổi cấu hình (xoay màn hình)
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        
        // Cập nhật kích thước màn hình mới
        updateScreenDimensions()
        
        // Cập nhật số cột trong grid layout dựa trên hướng màn hình
        val recyclerView = controlPanelBinding.recyclerViewFunctions
        val spanCount = if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) 2 else 3
        
        if (recyclerView.layoutManager is GridLayoutManager) {
            (recyclerView.layoutManager as GridLayoutManager).spanCount = spanCount
        } else {
            recyclerView.layoutManager = GridLayoutManager(context, spanCount)
        }
        
        // Nếu panel đang mở, cần điều chỉnh lại vị trí
        if (isPanelVisible) {
            // Cho thời gian để layout được vẽ lại với kích thước mới
            postDelayed({
                adjustPanelPosition()
            }, PANEL_MEASURE_DELAY)
        } else {
            // Đảm bảo nút nổi không bị tràn ra khỏi màn hình mới
            if (layoutParams.x > screenWidth - resources.getDimensionPixelSize(R.dimen.bubble_size)) {
                layoutParams.x = screenWidth - resources.getDimensionPixelSize(R.dimen.bubble_size)
                updateViewLayout()
            }
        }
        
        Log.d(TAG, "Configuration changed: orientation=${newConfig.orientation}, spanCount=$spanCount")
    }

    private fun setupBubbleView() {
        binding.ivBubbleIcon.setImageResource(R.drawable.ic_translate)
    }
    
    /**
     * Cập nhật kích thước màn hình
     */
    private fun updateScreenDimensions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            screenWidth = windowMetrics.bounds.width()
            screenHeight = windowMetrics.bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            @Suppress("DEPRECATION")
            val size = Point()
            @Suppress("DEPRECATION")
            display.getSize(size)
            screenWidth = size.x
            screenHeight = size.y
        }
        Log.d(TAG, "Screen dimensions: $screenWidth x $screenHeight")
    }

    /**
     * Thiết lập control panel với các thành phần và dữ liệu
     */
    private fun setupControlPanel() {
        try {
            // Thiết lập RecyclerView cho các nút chức năng
            val recyclerView = controlPanelBinding.recyclerViewFunctions
            val orientation = resources.configuration.orientation
            val spanCount = if (orientation == Configuration.ORIENTATION_PORTRAIT) 2 else 3
            recyclerView.layoutManager = GridLayoutManager(context, spanCount)
            
            // Thiết lập adapter và dữ liệu
            functionAdapter = FunctionAdapter { item ->
                handleFunctionClick(item)
            }
            recyclerView.adapter = functionAdapter
            
            // Tạo danh sách chức năng
            val functionItems = createFunctionItems()
            functionAdapter.submitList(functionItems)
            
            // Thiết lập các nút điều khiển
            controlPanelBinding.buttonHome.setOnClickListener {
                // TODO: Xử lý sự kiện về trang chủ
                closePanel()
            }
            
            controlPanelBinding.buttonMove.setOnClickListener {
                // Đóng panel khi nhấp vào nút di chuyển
                closePanel()
            }
            
            // Thiết lập nút chọn ngôn ngữ
            controlPanelBinding.buttonLanguageSelection.setOnClickListener {
                // TODO: Thêm logic chọn ngôn ngữ
                closePanel()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up control panel", e)
        }
    }
    
    /**
     * Tạo danh sách các nút chức năng cho panel
     */
    private fun createFunctionItems(): List<FunctionItem> {
        return listOf(
            FunctionItem(
                id = "GLOBAL",
                iconRes = R.drawable.ic_global,
                title = context.getString(R.string.function_global_translate),
                isClickable = true
            ),
            FunctionItem(
                id = "AREA",
                iconRes = R.drawable.ic_crop,
                title = context.getString(R.string.function_area_translate),
                isClickable = true
            ),
            FunctionItem(
                id = "IMAGE",
                iconRes = R.drawable.ic_image,
                title = context.getString(R.string.function_image_translate),
                isClickable = true
            ),
            FunctionItem(
                id = "COPY",
                iconRes = R.drawable.ic_copy,
                title = context.getString(R.string.function_copy_text),
                isClickable = true
            ),
            FunctionItem(
                id = "AUTO_GLOBAL",
                iconRes = R.drawable.ic_auto_play,
                title = context.getString(R.string.function_auto_global_translate),
                isClickable = true
            ),
            FunctionItem(
                id = "AUTO_AREA",
                iconRes = R.drawable.ic_auto_play,
                title = context.getString(R.string.function_auto_area_translate),
                isClickable = true
            )
        )
    }
    
    /**
     * Xử lý sự kiện khi người dùng nhấp vào một nút chức năng
     */
    private fun handleFunctionClick(item: FunctionItem) {
        Log.d(TAG, "Function clicked: ${item.id}")
        // TODO: Thêm logic xử lý từng chức năng
        closePanel()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        binding.bubbleView.setOnTouchListener { _, event ->
            // Nếu panel đang mở và người dùng chạm vào bên ngoài thì bỏ qua
            if (isPanelVisible && event.action == MotionEvent.ACTION_DOWN) {
                // Chỉ cho phép kéo khi panel không hiển thị
                return@setOnTouchListener true
            }

            // Reset flags khi bắt đầu chạm mới
            if (event.action == MotionEvent.ACTION_DOWN) {
                longPressOccurred = false
            }

            // Xử lý các cử chỉ qua GestureDetector trước
            val handled = gestureDetector.onTouchEvent(event)

            // Logic kéo và thả
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    cancelCollapseTimer()
                    expandBubble()
                }
                MotionEvent.ACTION_MOVE -> {
                    // Chỉ kéo khi không phải nhấn giữ và panel không hiển thị
                    if (!longPressOccurred && !isPanelVisible) {
                        val deltaX = event.rawX - initialTouchX
                        val deltaY = event.rawY - initialTouchY

                        // Xác định khi nào bắt đầu kéo
                        if (!isDragging && (abs(deltaX) > 10 || abs(deltaY) > 10)) {
                            isDragging = true
                            // Đóng panel nếu đang mở
                            if (isPanelVisible) {
                                closePanel()
                            }
                        }

                        if (isDragging) {
                            layoutParams.x = initialX + deltaX.toInt()
                            layoutParams.y = initialY + deltaY.toInt()
                            updateViewLayout()
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        snapToEdge()
                    }

                    // Khôi phục trạng thái mặc định
                    restoreDefaultState()
                    
                    startCollapseTimer()
                    isDragging = false
                }
            }
            true
        }
    }
    
    /**
     * Khôi phục trạng thái mặc định của nút nổi
     */
    private fun restoreDefaultState() {
        // Nếu không trong trạng thái panel mở, thì đặt lại icon
        if (!isPanelVisible) {
            binding.bubbleView.background = resources.getDrawable(
                R.drawable.bubble_background, 
                context.theme
            )
            binding.ivBubbleIcon.setImageResource(R.drawable.ic_translate)
        }
        longPressOccurred = false
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            // Phải trả về true để đảm bảo GestureDetector nhận diện các cử chỉ tiếp theo
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            android.util.Log.d(TAG, "Bubble Tapped!")
            // Nếu panel đang hiển thị, đóng nó; ngược lại, mở panel
            if (isPanelVisible) {
                closePanel()
            } else {
                openPanel()
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            android.util.Log.d(TAG, "Bubble Long Pressed - Activating magnifier mode!")
            
            // Đóng panel nếu đang mở
            if (isPanelVisible) {
                closePanel()
            }
            
            // Thay đổi thành chế độ kính lúp
            activateMagnifierMode()
            
            // Đặt cờ để thông báo rằng nhấn giữ đã xảy ra
            longPressOccurred = true
            isDragging = false
        }
        
        override fun onScroll(
            e1: MotionEvent?, 
            e2: MotionEvent, 
            distanceX: Float, 
            distanceY: Float
        ): Boolean {
            // Kiểm tra nếu panel đang mở thì không cho phép kéo
            if (isPanelVisible) return false
            
            // Nếu đã di chuyển đủ xa, đánh dấu là đang kéo
            if (!isDragging && e1 != null) {
                val deltaX = e2.rawX - e1.rawX
                val deltaY = e2.rawY - e1.rawY
                
                if (abs(deltaX) > 10 || abs(deltaY) > 10) {
                    isDragging = true
                }
            }
            return isDragging
        }
    }
    
    /**
     * Kích hoạt chế độ kính lúp (thay đổi giao diện nút nổi)
     */
    private fun activateMagnifierMode() {
        // Thay đổi icon thành kính lúp
        binding.ivBubbleIcon.setImageResource(R.drawable.ic_search)
        
        // Thay đổi nền thành trong suốt
        binding.bubbleView.background = null
        
        // Thêm các hiệu ứng đặc biệt cho chế độ kính lúp nếu cần
    }
    
    /**
     * Mở panel điều khiển
     */
    private fun openPanel() {
        Log.d(TAG, "Opening control panel")
        
        // Đánh dấu trạng thái đang mở panel
        isPanelVisible = true
        
        // Thay đổi kích thước của WindowManager LayoutParams để chứa panel
        layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT
        layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
        
        // Tạo hiệu ứng chuyển tiếp mượt mà
        TransitionManager.beginDelayedTransition(binding.bubbleContainer)
        
        // Ẩn nút nổi và hiện panel
        binding.bubbleView.visibility = View.GONE
        binding.controlPanel.root.visibility = View.VISIBLE
        
        // Cập nhật layout (đo lường lần đầu)
        updateViewLayout()
        
        // Chờ một chút để panel được đo lường, sau đó mới điều chỉnh vị trí
        postDelayed({
            adjustPanelPosition()
        }, PANEL_MEASURE_DELAY)
        
        // Hủy timer thu gọn
        cancelCollapseTimer()
    }
    
    /**
     * Điều chỉnh vị trí của panel để không bị khuất
     */
    private fun adjustPanelPosition() {
        val panelWidth = binding.controlPanel.root.width
        val panelHeight = binding.controlPanel.root.height
        
        Log.d(TAG, "Adjusting panel position. Current: x=${layoutParams.x}, y=${layoutParams.y}, Panel size: $panelWidth x $panelHeight")
        
        // Tính toán vị trí mới
        var newX = layoutParams.x
        var newY = layoutParams.y
        
        // Điều chỉnh X để panel không bị tràn ra khỏi màn hình
        if (layoutParams.x < screenWidth / 2) {
            // Nút nổi ở bên trái, không cần điều chỉnh X
        } else {
            // Nút nổi ở bên phải, cần điều chỉnh để panel không bị tràn qua phải
            if (layoutParams.x + panelWidth > screenWidth) {
                newX = screenWidth - panelWidth
            }
        }
        
        // Điều chỉnh Y để panel không bị tràn ra khỏi màn hình
        if (layoutParams.y + panelHeight > screenHeight) {
            newY = screenHeight - panelHeight
        }
        
        // Đảm bảo X, Y không âm
        if (newX < 0) newX = 0
        if (newY < 0) newY = 0
        
        // Nếu cần điều chỉnh vị trí
        if (newX != layoutParams.x || newY != layoutParams.y) {
            Log.d(TAG, "Panel position adjusted to: x=$newX, y=$newY")
            layoutParams.x = newX
            layoutParams.y = newY
            updateViewLayout()
        }
    }
    
    /**
     * Đóng panel điều khiển
     */
    private fun closePanel() {
        Log.d(TAG, "Closing control panel")
        
        // Đánh dấu đã đóng panel
        isPanelVisible = false
        
        // Tạo hiệu ứng chuyển tiếp mượt mà
        TransitionManager.beginDelayedTransition(binding.bubbleContainer)
        
        // Ẩn panel và hiện nút nổi
        binding.controlPanel.root.visibility = View.GONE
        binding.bubbleView.visibility = View.VISIBLE
        
        // Đặt lại kích thước layout params về kích thước nút nổi
        layoutParams.width = resources.getDimensionPixelSize(R.dimen.bubble_size)
        layoutParams.height = resources.getDimensionPixelSize(R.dimen.bubble_size)
        
        // Cập nhật layout
        updateViewLayout()
        
        // Khởi động lại timer thu gọn
        startCollapseTimer()
    }

    private fun snapToEdge() {
        // Kích thước view hiện tại (nút nổi hoặc panel)
        val currentWidth = resources.getDimensionPixelSize(R.dimen.bubble_size)
        
        // Tính toán vị trí đích
        val middle = screenWidth / 2
        val endX = if (layoutParams.x < middle - currentWidth / 2) 0 else screenWidth - currentWidth
        
        Log.d(TAG, "Snapping to edge. Current x=${layoutParams.x}, Target x=$endX")
        
        val animator = ValueAnimator.ofInt(layoutParams.x, endX).apply {
            duration = SNAP_ANIMATION_DURATION
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                layoutParams.x = animation.animatedValue as Int
                updateViewLayout()
            }
        }
        animator.start()
    }

    private fun startCollapseTimer() {
        // Không bắt đầu timer nếu panel đang mở
        if (isPanelVisible) return
        
        collapseJob?.cancel()
        collapseJob = coroutineScope.launch {
            delay(COLLAPSE_DELAY_MS)
            // Chỉ thu gọn nếu đang ở cạnh màn hình và không hiển thị panel
            if (!isPanelVisible && (layoutParams.x == 0 || layoutParams.x == screenWidth - resources.getDimensionPixelSize(R.dimen.bubble_size))) {
                collapseBubble()
            }
        }
    }

    private fun cancelCollapseTimer() {
        collapseJob?.cancel()
    }

    private fun collapseBubble() {
        // Không thu gọn nếu panel đang mở hoặc đã thu gọn
        if (isCollapsed || isPanelVisible) return
        
        isCollapsed = true
        val translationX = if (layoutParams.x == 0) -width / 2f else width / 2f
        this.animate()
            .translationX(translationX)
            .alpha(0.6f)
            .setDuration(SNAP_ANIMATION_DURATION)
            .start()
    }

    private fun expandBubble() {
        if (!isCollapsed) return
        isCollapsed = false
        
        // Khôi phục trạng thái mặc định
        restoreDefaultState()
        
        // Hoạt ảnh mở rộng
        this.animate()
            .translationX(0f)
            .alpha(1.0f)
            .setDuration(SNAP_ANIMATION_DURATION)
            .start()
    }

    private fun updateViewLayout() {
        try {
            if (isAttachedToWindow) {
                // Đảm bảo kích thước layout params phù hợp với trạng thái hiện tại
                if (isPanelVisible) {
                    layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT
                    layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
                } else {
                    // Khi nút nổi ở trạng thái mặc định
                    layoutParams.width = resources.getDimensionPixelSize(R.dimen.bubble_size)
                    layoutParams.height = resources.getDimensionPixelSize(R.dimen.bubble_size)
                }
                windowManager.updateViewLayout(this, layoutParams)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error updating view layout", e)
        }
    }

    fun setMediaProjection(projection: MediaProjection?) {
        this.mediaProjection = projection
    }
    
    /**
     * Được gọi từ bên ngoài để cập nhật ngôn ngữ hiển thị trên nút chọn ngôn ngữ
     */
    fun updateLanguageDisplay(sourceCode: String, targetCode: String) {
        try {
            controlPanelBinding.buttonLanguageSelection.text = "$sourceCode → $targetCode"
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error updating language pair", e)
        }
    }
    
    /**
     * Thiết lập callback cho nút chọn ngôn ngữ
     */
    fun setOnLanguageSelectClick(callback: () -> Unit) {
        onLanguageSelectClickListener = callback
        try {
            controlPanelBinding.buttonLanguageSelection.setOnClickListener {
                callback.invoke()
                // Đóng panel sau khi chọn
                closePanel()
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error setting language selection callback", e)
        }
    }
    
    /**
     * Trả về trạng thái của panel
     */
    fun isPanelVisible(): Boolean {
        return isPanelVisible
    }
}
