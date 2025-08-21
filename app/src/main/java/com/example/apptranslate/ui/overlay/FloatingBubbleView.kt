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
    fun onBubbleTapped()
    fun onBubbleLongPressed()
    fun onDragStarted()
    fun onDragFinished()
    fun onFunctionClicked(functionId: String)
    fun onLanguageSelectClicked()
    fun onHomeClicked()
    fun onMoveClicked()
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
        var magnifierCenterX: Int = 0
        var magnifierCenterY: Int = 0
        private const val HANDLE_LENGTH = 120f
        private const val HANDLE_ANGLE = PI / 4   // 45 độ
        private const val HANDLE_PIVOT_X_RATIO = 17f / 24f
        private const val HANDLE_PIVOT_Y_RATIO = 25f / 24f
        private const val MAGNIFIER_ICON_SCALE = 2.0f
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
        // Không cần showScrim() nữa - sẽ xử lý touch bằng cách khác

        // Cập nhật layout params để panel có thể nhận touch events
        layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT
        layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        updateViewLayout()

        // Đảm bảo panel nằm gọn trong màn hình
        post { adjustPanelPosition() }

        TransitionManager.beginDelayedTransition(binding.root as ViewGroup)
        binding.bubbleView.visibility = View.GONE
        binding.controlPanel.root.visibility = View.VISIBLE

        // Thêm touch listener trực tiếp vào root để xử lý tap outside
        binding.root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                // Kiểm tra xem touch có nằm trong vùng panel không
                val panelView = binding.controlPanel.root
                val location = IntArray(2)
                panelView.getLocationOnScreen(location)
                val panelLeft = location[0]
                val panelTop = location[1]
                val panelRight = panelLeft + panelView.width
                val panelBottom = panelTop + panelView.height

                val touchX = event.rawX.toInt()
                val touchY = event.rawY.toInt()

                // Nếu touch nằm ngoài vùng panel, thì đóng panel
                if (touchX < panelLeft || touchX > panelRight ||
                    touchY < panelTop || touchY > panelBottom) {
                    listener?.onBubbleTapped() // Đóng panel
                    return@setOnTouchListener true
                }
            }
            false // Cho phép các view con xử lý touch events
        }
    }

    /**
     * Đóng panel điều khiển với animation.
     */
    fun closePanel() {
        if (!isPanelOpen) return
        isPanelOpen = false
        // Không cần hideScrim() nữa

        // Khôi phục layout params ban đầu cho bubble
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        // Xóa touch listener khỏi root
        binding.root.setOnTouchListener(null)

        TransitionManager.beginDelayedTransition(binding.root as ViewGroup)
        binding.controlPanel.root.visibility = View.GONE
        binding.bubbleView.visibility = View.VISIBLE

        snapToEdge()
    }

    // ✨ THAY THẾ HÀM updateBubbleAppearance ✨
    fun updateBubbleAppearance(appearance: BubbleAppearance) {
        // Hàm reset scale về mặc định
        fun resetIconScale() {
            binding.ivBubbleIcon.scaleX = 1.0f
            binding.ivBubbleIcon.scaleY = 1.0f
        }

        when (appearance) {
            BubbleAppearance.NORMAL -> {
                binding.bubbleView.background = defaultBubbleBackground
                binding.ivBubbleIcon.setImageResource(R.drawable.ic_translate)
                resetIconScale()
            }
            BubbleAppearance.MAGNIFIER -> {
                binding.bubbleView.background = null // Trong suốt
                binding.ivBubbleIcon.setImageResource(R.drawable.ic_search)
                binding.ivBubbleIcon.scaleX = MAGNIFIER_ICON_SCALE
                binding.ivBubbleIcon.scaleY = MAGNIFIER_ICON_SCALE
            }
            BubbleAppearance.MOVING -> {
                binding.bubbleView.background = defaultBubbleBackground
                binding.ivBubbleIcon.setImageResource(R.drawable.ic_drag_indicator)
                resetIconScale()
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
        binding.bubbleView.setOnTouchListener { _, event ->
            // Ưu tiên xử lý cử chỉ (tap, long press)
            val consumedByGesture = gestureDetector.onTouchEvent(event)
            if (consumedByGesture) {
                if (event.action == MotionEvent.ACTION_UP && isDragging) {
                    endDrag()
                }
                return@setOnTouchListener true
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y

                    // ✨ LOGIC MỚI: TÍNH TOÁN OFFSET ĐỂ "CÁN" ĐI THEO NGÓN TAY ✨
                    // Lấy kích thước hiện tại của bubble view
                    val bubbleWidth = binding.bubbleView.width
                    val bubbleHeight = binding.bubbleView.height

                    // Tính toán điểm pivot của "cán" trong tọa độ của View
                    val handlePivotX = bubbleWidth * HANDLE_PIVOT_X_RATIO
                    val handlePivotY = bubbleHeight * HANDLE_PIVOT_Y_RATIO

                    // Offset là khoảng cách từ góc trên-trái của View đến điểm pivot của cán.
                    // Khi kéo, chúng ta sẽ đặt góc trên-trái của View bằng (vị trí ngón tay - offset)
                    // để điểm pivot của cán luôn nằm dưới ngón tay.
                    dragOffsetX = handlePivotX
                    dragOffsetY = handlePivotY

                    expandBubble()
                    cancelCollapseTimer()
                }
                MotionEvent.ACTION_MOVE -> {
                    // Tọa độ ngón tay hiện tại
                    val newRawX = event.rawX
                    val newRawY = event.rawY

                    // Bắt đầu kéo nếu di chuyển đủ xa
                    if (!isDragging) {
                        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
                        // So sánh với vị trí chạm ban đầu để quyết định bắt đầu kéo
                        if (abs(newRawX - (initialX + dragOffsetX)) > touchSlop ||
                            abs(newRawY - (initialY + dragOffsetY)) > touchSlop) {
                            isDragging = true
                            listener?.onDragStarted()
                        }
                    }

                    if (isDragging) {
                        // ✨ CẬP NHẬT VỊ TRÍ VIEW DỰA TRÊN OFFSET CỦA CÁN ✨
                        layoutParams.x = (newRawX - dragOffsetX).toInt()
                        layoutParams.y = (newRawY - dragOffsetY).toInt()
                        updateViewLayout()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        endDrag()
                    }
                }
            }
            true
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
        isDragging = false
        snapToEdge()
        listener?.onDragFinished()
    }

    private fun snapToEdge() {
        val endX = if (layoutParams.x + width / 2 < screenWidth / 2) 0 else screenWidth - width
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
        try {
            if (isAttachedToWindow) {
                if (!isPanelOpen) {
                    layoutParams.width = resources.getDimensionPixelSize(R.dimen.bubble_size)
                    layoutParams.height = resources.getDimensionPixelSize(R.dimen.bubble_size)
                }
                windowManager.updateViewLayout(this, layoutParams)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating view layout", e)
        }
    }

    private fun createFunctionItems(): List<FunctionItem> {
        return listOf(
            FunctionItem("GLOBAL", R.drawable.ic_global, context.getString(R.string.function_global_translate)),
            FunctionItem("AREA", R.drawable.ic_crop, context.getString(R.string.function_area_translate)),
            FunctionItem("IMAGE", R.drawable.ic_image, context.getString(R.string.function_image_translate)),
            FunctionItem("COPY", R.drawable.ic_copy, context.getString(R.string.function_copy_text)),
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
            snapToEdge()
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
            post { adjustPanelPosition() }
        } else if (layoutParams.x > 0) { // Nếu không ở cạnh trái
            layoutParams.x = screenWidth - width // Dịch chuyển về cạnh phải mới
            updateViewLayout()
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

    private fun adjustPanelPosition() {
        var needsUpdate = false
        if (layoutParams.x + binding.controlPanel.root.width > screenWidth) {
            layoutParams.x = screenWidth - binding.controlPanel.root.width
            needsUpdate = true
        }
        if (layoutParams.y + binding.controlPanel.root.height > screenHeight) {
            layoutParams.y = screenHeight - binding.controlPanel.root.height
            needsUpdate = true
        }
        if (layoutParams.x < 0) {
            layoutParams.x = 0
            needsUpdate = true
        }
        if (layoutParams.y < 0) {
            layoutParams.y = 0
            needsUpdate = true
        }
        if (needsUpdate) updateViewLayout()
    }
}