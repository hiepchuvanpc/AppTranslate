package com.example.apptranslate.ui.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.transition.TransitionManager
import android.util.Log
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.example.apptranslate.R
import com.example.apptranslate.databinding.ViewFloatingBubbleBinding
import com.example.apptranslate.databinding.ViewFloatingControlPanelBinding
import com.example.apptranslate.ui.overlay.adapter.FunctionAdapter
import com.example.apptranslate.ui.overlay.model.FunctionItem
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
/**
 * Interface định nghĩa các sự kiện tương tác mà FloatingBubbleView sẽ gửi ra ngoài.
 * Lớp Service sẽ implement interface này để xử lý logic.
 */
interface BubbleViewListener {
    fun onDrag(x: Int, y: Int)
    fun onBubbleTapped()
    fun onBubbleLongPressed()
    fun onDragStarted()
    fun onDragFinished()
    fun onFunctionClicked(functionId: String)
    fun onLanguageSelectClicked()
    fun onHomeClicked()
    fun onMoveClicked()
    fun onRegionTranslateClicked()
}

/**
* Enum định nghĩa các kiểu hiển thị của bubble.
*/
enum class BubbleAppearance {
    NORMAL,
    MAGNIFIER,
    MOVING
}

enum class ServiceState {
    IDLE,
    PANEL_OPEN,
    MAGNIFIER,
    MOVING_PANEL
}

@SuppressLint("ViewConstructor")
class FloatingBubbleView(
    context: Context,
    private val coroutineScope: CoroutineScope
) : FrameLayout(context) {

    companion object {
        private const val TAG = "FloatingBubbleView"
        private const val SNAP_ANIMATION_DURATION = 300L
        private const val COLLAPSE_DELAY_MS = 3000L
    }

    // --- Listener để giao tiếp với Service ---
    var listener: BubbleViewListener? = null

    // --- Views & Managers ---
    private val binding: ViewFloatingBubbleBinding
    private val controlPanelBinding: ViewFloatingControlPanelBinding
    private val windowManager: WindowManager
    private lateinit var layoutParams: WindowManager.LayoutParams
    private val defaultBubbleBackground: Drawable?

    // --- State ---
    private var isPanelOpen = false
    private var isCollapsed = false
    private var isDragging = false
    private var collapseJob: Job? = null
    private var isBubbleOnLeft = true // Mặc định bubble ở bên trái
    private var lastBubbleY = 0

    // --- Touch Handling ---
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var screenWidth = 0
    private var screenHeight = 0
    // Biến để lưu offset khi kéo
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private val gestureDetector: GestureDetector

    init {
        val themedContext = ContextThemeWrapper(context, R.style.Theme_AppTranslate_NoActionBar)
        binding = ViewFloatingBubbleBinding.inflate(LayoutInflater.from(themedContext), this, true)
        controlPanelBinding = ViewFloatingControlPanelBinding.bind(binding.controlPanel.root)
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        defaultBubbleBackground = ContextCompat.getDrawable(context, R.drawable.bubble_background)

        gestureDetector = GestureDetector(context, GestureListener())

        updateScreenDimensions()
        setupBubbleView()
        setupTouchListener()
        setupControlPanel()
    }

    fun setViewLayoutParams(params: WindowManager.LayoutParams) {
        this.layoutParams = params
    }

    // --- Public Methods (API for Service to call) ---

    /**
     * Mở panel điều khiển với animation.
     */
    fun openPanel() {
        if (isPanelOpen) return
        isPanelOpen = true
        cancelCollapseTimer()

        // Lưu lại vị trí Y của bubble
        lastBubbleY = layoutParams.y

        // Step 1: Ẩn bubble đi một cách mượt mà
        binding.bubbleView.animate().alpha(0f).setDuration(150).withEndAction {
            binding.bubbleView.visibility = View.GONE

            // Step 2: Cấu hình lại LayoutParams cho panel
            layoutParams.apply {
                // Thay đổi trọng lực để căn giữa theo chiều dọc và bám vào cạnh
                gravity = Gravity.CENTER_VERTICAL or if (isBubbleOnLeft) Gravity.LEFT else Gravity.RIGHT
                // Reset x, y vì gravity sẽ lo việc định vị
                x = 0
                y = 0
                width = WindowManager.LayoutParams.WRAP_CONTENT
                height = WindowManager.LayoutParams.WRAP_CONTENT
            }
            // Bỏ cờ NOT_TOUCH_MODAL để bắt sự kiện chạm bên ngoài
            layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            updateViewLayout()

            // Step 3: Hiển thị panel một cách mượt mà
            binding.controlPanel.root.alpha = 0f
            binding.controlPanel.root.visibility = View.VISIBLE
            binding.controlPanel.root.animate().alpha(1f).setDuration(150).start()

            // Gán listener để đóng panel khi chạm ra ngoài
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    // ✨ FIX: Đóng panel và về trạng thái idle thay vì chuyển sang move
                    closePanel()
                    listener?.onHomeClicked() // Về trạng thái home/idle
                    true
                } else {
                    false
                }
            }
        }.start()
    }

    /**
    * Đóng panel điều khiển với animation.
    */
    fun closePanel(onComplete: (() -> Unit)? = null) {
        if (!isPanelOpen) {
            onComplete?.invoke()
            return
        }
        isPanelOpen = false

        // Gỡ bỏ listener chạm bên ngoài
        setOnTouchListener(null)

        // Step 1: Ẩn panel đi một cách mượt mà
        binding.controlPanel.root.animate().alpha(0f).setDuration(150).withEndAction {
            binding.controlPanel.root.visibility = View.GONE

            // Step 2: Cấu hình lại LayoutParams cho bubble
            layoutParams.apply {
                // Trả lại trọng lực mặc định
                gravity = Gravity.TOP or Gravity.START
                // Phục hồi lại vị trí x, y của bubble
                x = if (isBubbleOnLeft) 0 else screenWidth - binding.bubbleView.width
                y = lastBubbleY
                width = resources.getDimensionPixelSize(R.dimen.bubble_size)
                height = resources.getDimensionPixelSize(R.dimen.bubble_size)
            }
            // Trả lại cờ để cho phép chạm xuyên qua
            layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            updateViewLayout()

            // Step 3: Hiển thị bubble một cách mượt mà
            binding.bubbleView.alpha = 0f
            binding.bubbleView.visibility = View.VISIBLE
            binding.bubbleView.animate().alpha(1f).setDuration(150).withEndAction {
                // Bắt đầu hẹn giờ tự thu gọn sau khi bubble hiện lại
                startCollapseTimer()
                // 🔧 CẢI TIẾN: Gọi callback khi animation hoàn thành
                onComplete?.invoke()
            }.start()

        }.start()
    }

    /**
     * Kiểm tra xem panel có đang mở không
     */
    fun isPanelOpen(): Boolean {
        return isPanelOpen
    }

    fun updateBubbleAppearance(appearance: BubbleAppearance) {
        fun resetIconScaleAndAlpha() {
            binding.ivBubbleIcon.scaleX = 1.0f
            binding.ivBubbleIcon.scaleY = 1.0f
            binding.bubbleView.alpha = 1.0f // Đảm bảo alpha được reset
        }

        when (appearance) {
            BubbleAppearance.NORMAL -> {
                binding.bubbleView.background = defaultBubbleBackground
                binding.ivBubbleIcon.setImageResource(R.drawable.ic_translate)
                resetIconScaleAndAlpha()
            }
            BubbleAppearance.MAGNIFIER -> {
                // <<< SỬA LỖI: Chỉ cần làm bubble trong suốt, không cần thay icon
                binding.bubbleView.alpha = 0.0f
            }
            BubbleAppearance.MOVING -> {
                binding.bubbleView.background = defaultBubbleBackground
                binding.ivBubbleIcon.setImageResource(R.drawable.ic_drag_indicator)
                resetIconScaleAndAlpha()
            }
        }
    }

    /**
     * Cập nhật text hiển thị ngôn ngữ trên panel.
     */
    fun updateLanguageDisplay(text: String) {
        controlPanelBinding.buttonLanguageSelection.text = text
    }

    // --- Setup Methods ---

    private fun setupBubbleView() {
        binding.ivBubbleIcon.setImageResource(R.drawable.ic_translate)
    }


    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                Log.d(TAG, "Single tap confirmed")
                listener?.onBubbleTapped()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                Log.d(TAG, "Long press detected")
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                listener?.onBubbleLongPressed() // Nếu muốn trigger magnifier bằng long press, implement ở service
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                Log.d(TAG, "onScroll: dx=$distanceX, dy=$distanceY, isDragging=$isDragging")
                if (!isDragging) {
                    isDragging = true
                    listener?.onDragStarted()
                }

                // Cập nhật vị trí và gọi onDrag ngay
                layoutParams.x = (initialX + (e2.rawX - initialTouchX)).toInt()
                layoutParams.y = (initialY + (e2.rawY - initialTouchY)).toInt().coerceAtLeast(0)
                listener?.onDrag(layoutParams.x, layoutParams.y)
                updateViewLayout()
                return true
            }
        })

        binding.bubbleView.setOnTouchListener { _, event ->
            Log.d(TAG, "Touch event: action=${event.action}")
            val consumed = gestureDetector.onTouchEvent(event)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    expandBubble()
                    cancelCollapseTimer()
                    return@setOnTouchListener true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        endDrag()
                    }
                    isDragging = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    Log.w(TAG, "Touch cancelled")
                    isDragging = false
                }
            }
            consumed
        }
    }

    private fun setupControlPanel() {
        val recyclerView = controlPanelBinding.recyclerViewFunctions
        val spanCount = if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) 2 else 3
        recyclerView.layoutManager = GridLayoutManager(context, spanCount)

        val functionAdapter = FunctionAdapter { item ->
            // Gửi sự kiện click chức năng ra cho Service xử lý
            Log.d(TAG, "Function clicked: ${item.id}")
            listener?.onFunctionClicked(item.id)
        }
        recyclerView.adapter = functionAdapter
        functionAdapter.submitList(createFunctionItems())

        // Kết nối các nút bấm với listener để Service có thể nhận được sự kiện
        controlPanelBinding.buttonHome.setOnClickListener {
            Log.d(TAG, "Home button clicked")
            listener?.onHomeClicked()
        }
        controlPanelBinding.buttonMove.setOnClickListener {
            Log.d(TAG, "Move button clicked")
            listener?.onMoveClicked()
        }
        controlPanelBinding.buttonLanguageSelection.setOnClickListener {
            Log.d(TAG, "Language selection button clicked")
            listener?.onLanguageSelectClicked()
        }
    }
    // --- Internal Logic & Animations ---

    private fun endDrag() {
        snapToEdge()
        listener?.onDragFinished()
    }

    private fun snapToEdge() {
        val endX = if (layoutParams.x + width / 2 < screenWidth / 2) {
            isBubbleOnLeft = true
            0
        } else {
            isBubbleOnLeft = false
            screenWidth - width
        }
        ValueAnimator.ofInt(layoutParams.x, endX).apply {
            duration = SNAP_ANIMATION_DURATION
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                layoutParams.x = it.animatedValue as Int
                updateViewLayout()
            }
            doOnEnd { startCollapseTimer() }
        }.start()
    }

    private fun startCollapseTimer() {
        if (isPanelOpen || isDragging) return
        cancelCollapseTimer()
        collapseJob = coroutineScope.launch {
            delay(COLLAPSE_DELAY_MS)
            if (!isPanelOpen) { // Đảm bảo panel không mở trong lúc delay
                collapseBubble()
            }
        }
    }

    private fun cancelCollapseTimer() = collapseJob?.cancel()

    private fun collapseBubble() {
        if (isCollapsed) return
        isCollapsed = true
        val translationX = if (layoutParams.x == 0) -width / 2f else width / 2f
        animate().translationX(translationX).alpha(0.6f).setDuration(SNAP_ANIMATION_DURATION).start()
    }

    private fun expandBubble() {
        if (!isCollapsed) return
        isCollapsed = false
        animate().translationX(0f).alpha(1.0f).setDuration(SNAP_ANIMATION_DURATION).start()
    }

    private fun updateViewLayout() {
        // Chỉ cập nhật nếu view còn được gắn vào window.
        // Điều này sẽ ngăn chặn hầu hết các crash.
        if (!isAttachedToWindow) {
            Log.w(TAG, "updateViewLayout called when view is not attached.")
            return
        }

        if (!isPanelOpen) {
            layoutParams.width = resources.getDimensionPixelSize(R.dimen.bubble_size)
            layoutParams.height = resources.getDimensionPixelSize(R.dimen.bubble_size)
        }

        // Không cần try-catch ở đây nữa
        windowManager.updateViewLayout(this, layoutParams)
    }

    private fun createFunctionItems(): List<FunctionItem> {
        return listOf(
            FunctionItem("GLOBAL", R.drawable.ic_global, context.getString(R.string.function_global_translate)),
            FunctionItem("AREA", R.drawable.ic_crop, context.getString(R.string.function_area_translate), isClickable = true),
            FunctionItem("IMAGE", R.drawable.ic_image, context.getString(R.string.function_image_translate), isClickable = true),
            FunctionItem("COPY", R.drawable.ic_copy, context.getString(R.string.function_copy_text), isClickable = true),
            FunctionItem("AUTO_GLOBAL", R.drawable.ic_auto_play, context.getString(R.string.function_auto_global_translate)),
            FunctionItem("AUTO_AREA", R.drawable.ic_auto_play, context.getString(R.string.function_auto_area_translate))
        )
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            listener?.onBubbleTapped()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            // Thực hiện haptic feedback để báo hiệu cho người dùng
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            listener?.onBubbleLongPressed()
        }
    }

    // --- Overridden Methods & Helper methods ---

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post {
            binding.controlPanel.root.visibility = View.GONE
            binding.bubbleView.visibility = View.VISIBLE
            isPanelOpen = false
            snapToEdge() // Đảm bảo bubble được snap và isBubbleOnLeft được thiết lập
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelCollapseTimer()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenDimensions()
        (controlPanelBinding.recyclerViewFunctions.layoutManager as? GridLayoutManager)?.spanCount =
            if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) 2 else 3

        if (isPanelOpen) {
            // Tính toán lại vị trí panel dựa trên kích thước màn hình mới
            post {
                val panelWidth = binding.controlPanel.root.width
                val panelHeight = binding.controlPanel.root.height

                // Định vị panel dựa trên bên mà bubble đã nằm
                layoutParams.x = if (isBubbleOnLeft) {
                    0 // Căn chỉnh vào cạnh trái
                } else {
                    screenWidth - panelWidth // Căn chỉnh vào cạnh phải
                }

                layoutParams.y = (screenHeight / 2) - (panelHeight / 2)
                // Đảm bảo panel không bị tràn màn hình theo chiều dọc
                layoutParams.y = Math.max(0, Math.min(layoutParams.y, screenHeight - panelHeight))
                updateViewLayout()
            }
        } else { // Nếu panel không mở, đảm bảo bubble được snap vào cạnh đúng
            // Nếu bubble ở bên phải, di chuyển nó đến cạnh phải mới
            if (!isBubbleOnLeft) {
                layoutParams.x = screenWidth - resources.getDimensionPixelSize(R.dimen.bubble_size)
                updateViewLayout()
            }
            // Nếu nó ở bên trái (isBubbleOnLeft là true), layoutParams.x đã là 0, không cần thay đổi.
        }
    }

    private fun updateScreenDimensions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Dành cho Android 11 (API 30) trở lên
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            // Dành cho Android 10 (API 29) và cũ hơn
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            @Suppress("DEPRECATION")
            val size = Point()
            @Suppress("DEPRECATION")
            display.getSize(size)
            screenWidth = size.x
            screenHeight = size.y
        }
    }
}