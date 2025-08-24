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
    private var isBubbleOnLeft = true // Mặc định bubble ở bên trái

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

        // Ẩn bubble, hiện panel
        TransitionManager.beginDelayedTransition(binding.root as ViewGroup)
        binding.bubbleView.visibility = View.GONE
        binding.controlPanel.root.visibility = View.VISIBLE

        val currentBubbleIsOnLeft = isBubbleOnLeft

        // Cập nhật layout params để panel có kích thước phù hợp
        layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT
        layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT

        // Giữ lại các cờ cần thiết ban đầu.
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

        updateViewLayout() // Áp dụng WRAP_CONTENT, cho phép panel được đo lường

        TransitionManager.beginDelayedTransition(binding.root as ViewGroup)
        binding.bubbleView.visibility = View.GONE
        binding.controlPanel.root.visibility = View.VISIBLE

        // Post để đảm bảo panel đã được đo lường và có width/height
        post {
            val panelWidth = binding.controlPanel.root.width
            val panelHeight = binding.controlPanel.root.height

            // Tính toán vị trí X mới dựa trên bên của bubble
            layoutParams.x = if (currentBubbleIsOnLeft) {
                0 // Căn chỉnh vào cạnh trái
            } else {
                screenWidth - panelWidth // Căn chỉnh vào cạnh phải
            }

            // Tính toán vị trí Y mới để căn giữa theo chiều dọc
            layoutParams.y = (screenHeight / 2) - (panelHeight / 2)

            // Đảm bảo panel không bị tràn màn hình theo chiều dọc
            layoutParams.y = Math.max(0, Math.min(layoutParams.y, screenHeight - panelHeight))

            updateViewLayout() // Áp dụng vị trí mới

            // ✨ Đoạn code xử lý chạm bên ngoài để đóng panel này rất hay, nên giữ lại ✨
            binding.root.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    val panelRect = Rect()
                    binding.controlPanel.root.getGlobalVisibleRect(panelRect)
                    if (!panelRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                        listener?.onBubbleTapped()
                        return@setOnTouchListener true
                    }
                }
                false
            }
        }
    }

    /**
    * Đóng panel điều khiển với animation.
    */
    fun closePanel() {
        if (!isPanelOpen) return
        isPanelOpen = false

        // Dọn dẹp listener để tránh rò rỉ và xung đột
        binding.root.setOnTouchListener(null)

        TransitionManager.beginDelayedTransition(binding.root as ViewGroup)
        binding.controlPanel.root.visibility = View.GONE
        binding.bubbleView.visibility = View.VISIBLE

        snapToEdge()
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

            override fun onDown(e: MotionEvent): Boolean {
                initialX = layoutParams.x
                initialY = layoutParams.y
                expandBubble()
                cancelCollapseTimer()
                return true // Quan trọng: Phải trả về true để nhận các sự kiện sau
            }

            // Dùng onSingleTapConfirmed để chắc chắn đây là 1 cú chạm, không phải chạm-kéo
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                listener?.onBubbleTapped()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                listener?.onBubbleLongPressed()
            }

            // onScroll sẽ xử lý toàn bộ logic kéo thả
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                // Lần onScroll đầu tiên, báo cho Service biết để vào chế độ kính lúp
                if (e1 != null && e1.historySize == 0) {
                    listener?.onDragStarted()
                }

                val totalDeltaX = e2.rawX - e1!!.rawX
                val totalDeltaY = e2.rawY - e1!!.rawY
                layoutParams.x = initialX + totalDeltaX.toInt()
                layoutParams.y = initialY + totalDeltaY.toInt()
                updateViewLayout()
                return true
            }
        })

        binding.bubbleView.setOnTouchListener { _, event ->
            // Chuyển toàn bộ sự kiện cho GestureDetector
            val handledByDetector = gestureDetector.onTouchEvent(event)

            // Khi người dùng nhấc tay, luôn gọi endDrag để xử lý logic kết thúc kéo
            if (event.action == MotionEvent.ACTION_UP) {
                endDrag()
            }

            handledByDetector
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