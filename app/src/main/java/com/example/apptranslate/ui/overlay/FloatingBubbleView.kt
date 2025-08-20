// File: app/src/main/java/com/example/apptranslate/ui/overlay/FloatingBubbleView.kt

package com.example.apptranslate.ui.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.drawable.Drawable
import android.media.projection.MediaProjection
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

@SuppressLint("ViewConstructor")
class FloatingBubbleView(
    context: Context,
    private val coroutineScope: CoroutineScope
) : FrameLayout(context) {

    private var onLanguageSelectClickListener: (() -> Unit)? = null

    companion object {
        private const val TAG = "FloatingBubbleView"
        private const val SNAP_ANIMATION_DURATION = 300L
        private const val COLLAPSE_DELAY_MS = 3000L
        private const val CLICK_THRESHOLD = 10f
    }

    // ✨ Định nghĩa các callback để giao tiếp với Service ✨
    var onMagnifierMoveListener: ((Rect) -> Unit)? = null
    var onMagnifierReleaseListener: (() -> Unit)? = null
    var onLanguageSelectClickListener: (() -> Unit)? = null

    // --- Views & Managers ---
    private val binding: ViewFloatingBubbleBinding
    private lateinit var windowManager: WindowManager
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var controlPanelBinding: ViewFloatingControlPanelBinding
    private lateinit var functionAdapter: FunctionAdapter
    private val defaultBubbleBackground: Drawable?
    private var scrimView: View? = null // ✨ View nền mờ để bắt sự kiện chạm bên ngoài

    // --- State ---
    private enum class State { NORMAL, PANEL_OPEN, DRAGGING, MAGNIFIER, MOVE_MODE }
    private var currentState = State.NORMAL


    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var screenWidth = 0
    private var screenHeight = 0
    private var collapseJob: Job? = null
    private var isCollapsed = false
    private var touchDownTime = 0L // Để xác định chạm đơn (single tap)
    var mediaProjection: MediaProjection? = null


    init {
        val themedContext = ContextThemeWrapper(context, R.style.Theme_AppTranslate_NoActionBar)
        binding = ViewFloatingBubbleBinding.inflate(LayoutInflater.from(themedContext), this, true)
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        defaultBubbleBackground = ContextCompat.getDrawable(context, R.drawable.bubble_background)
        updateScreenDimensions()
        controlPanelBinding = ViewFloatingControlPanelBinding.bind(binding.controlPanel.root)
        setupBubbleView()
        setupTouchListener()
        setupControlPanel()
    }

    fun setViewLayoutParams(params: WindowManager.LayoutParams) {
        this.layoutParams = params
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        binding.bubbleView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    cancelCollapseTimer()
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchDownTime = System.currentTimeMillis()
                    expandBubble()
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY

                    if (currentState != State.PANEL_OPEN && currentState != State.MOVE_MODE) {
                        if (abs(deltaX) > CLICK_THRESHOLD || abs(deltaY) > CLICK_THRESHOLD) {
                            setState(State.MAGNIFIER)
                        }
                    }

                    if (currentState == State.MAGNIFIER || currentState == State.MOVE_MODE) {
                        layoutParams.x = initialX + deltaX.toInt()
                        layoutParams.y = initialY + deltaY.toInt()
                        updateViewLayout()
                        // ✨ Gửi tọa độ kính lúp liên tục ✨
                        onMagnifierMoveListener?.invoke(getViewRect())
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val timeElapsed = System.currentTimeMillis() - touchDownTime
                    val distanceMoved = abs(event.rawX - initialTouchX) + abs(event.rawY - initialTouchY)

                    if (currentState != State.MAGNIFIER && timeElapsed < 200 && distanceMoved < CLICK_THRESHOLD) {
                         setState(if (currentState == State.PANEL_OPEN) State.NORMAL else State.PANEL_OPEN)
                    } else {
                        // ✨ Báo hiệu đã nhả tay ở chế độ kính lúp ✨
                        if(currentState == State.MAGNIFIER) onMagnifierReleaseListener?.invoke()

                        if (currentState == State.MOVE_MODE || currentState == State.MAGNIFIER) {
                           snapToEdge()
                        }
                        if (currentState != State.PANEL_OPEN) {
                           setState(State.NORMAL)
                        }
                    }
                }
            }
            true
        }
    }

    /**
     * Hàm trung tâm để quản lý tất cả các thay đổi về trạng thái
     */
    private fun setState(newState: State) {
        if (currentState == newState) return

        when (currentState) {
            State.PANEL_OPEN -> closePanelVisuals()
            State.MAGNIFIER, State.MOVE_MODE -> restoreBubbleVisuals()
            else -> {}
        }

        currentState = newState

        when (newState) {
            State.NORMAL -> { restoreBubbleVisuals(); startCollapseTimer() }
            State.PANEL_OPEN -> { cancelCollapseTimer(); openPanelVisuals() }
            State.MAGNIFIER -> { cancelCollapseTimer(); activateMagnifierVisuals() }
            State.MOVE_MODE -> { cancelCollapseTimer(); activateMoveModeVisuals() }
        }
    }

    // --- Các hàm thay đổi giao diện (Visuals) ---

    private fun openPanelVisuals() {
        showScrim()
        layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT
        layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
        TransitionManager.beginDelayedTransition(binding.root as ViewGroup)
        binding.bubbleView.visibility = View.GONE
        binding.controlPanel.root.visibility = View.VISIBLE
        updateViewLayout()
        post { adjustPanelPosition() }
    }

    private fun closePanelVisuals() {
        hideScrim()
        TransitionManager.beginDelayedTransition(binding.root as ViewGroup)
        binding.controlPanel.root.visibility = View.GONE
        binding.bubbleView.visibility = View.VISIBLE
        snapToEdge() // Hít vào cạnh sau khi đóng panel
    }

    // ✨ CÁC HÀM MỚI ĐỂ QUẢN LÝ LỚP NỀN MỜ ✨
    @SuppressLint("ClickableViewAccessibility")
    private fun showScrim() {
        if (scrimView == null) {
            scrimView = View(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(0x01000000) // Gần như trong suốt
                setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        setState(State.NORMAL) // Đóng panel khi chạm
                        true
                    } else false
                }
            }
        }
        val scrimParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        try {
            if (scrimView?.isAttachedToWindow == false) {
                windowManager.addView(scrimView, scrimParams)
            }
        } catch (e: Exception) { Log.e(TAG, "Error adding scrim view", e) }
    }

    private fun hideScrim() {
        try {
            if (scrimView?.isAttachedToWindow == true) {
                windowManager.removeView(scrimView)
            }
        } catch (e: Exception) { Log.e(TAG, "Error removing scrim view", e) }
    }

    private fun getViewRect() = Rect(layoutParams.x, layoutParams.y, layoutParams.x + width, layoutParams.y + height)


    private fun activateMagnifierVisuals() {
        binding.ivBubbleIcon.setImageResource(R.drawable.ic_search)
        binding.bubbleView.background = null
    }

    private fun activateMoveModeVisuals() {
        binding.ivBubbleIcon.setImageResource(R.drawable.ic_drag_indicator)
    }

    private fun restoreBubbleVisuals() {
        binding.bubbleView.background = defaultBubbleBackground
        binding.ivBubbleIcon.setImageResource(R.drawable.ic_translate)
    }

    private fun setupControlPanel() {
        val recyclerView = controlPanelBinding.recyclerViewFunctions
        val spanCount = if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) 2 else 3
        recyclerView.layoutManager = GridLayoutManager(context, spanCount)
        functionAdapter = FunctionAdapter { handleFunctionClick(it) }
        recyclerView.adapter = functionAdapter
        functionAdapter.submitList(createFunctionItems())

        controlPanelBinding.buttonHome.setOnClickListener { setState(State.NORMAL) }
        controlPanelBinding.buttonMove.setOnClickListener { setState(State.MOVE_MODE) }
        controlPanelBinding.buttonLanguageSelection.setOnClickListener {
            onLanguageSelectClickListener?.invoke()
            setState(State.NORMAL)
        }
    }

    private fun snapToEdge() {
        val endX = if (layoutParams.x + width / 2 < screenWidth / 2) 0 else screenWidth - width
        val animator = ValueAnimator.ofInt(layoutParams.x, endX).apply {
            duration = SNAP_ANIMATION_DURATION
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                layoutParams.x = it.animatedValue as Int
                updateViewLayout()
            }
            // ✨ Bắt đầu timer chỉ khi animation kết thúc ✨
            doOnEnd { startCollapseTimer() }
        }
        animator.start()
    }

    private fun startCollapseTimer() {
        if (currentState != State.NORMAL) return
        cancelCollapseTimer()
        collapseJob = coroutineScope.launch {
            delay(COLLAPSE_DELAY_MS)
            if (currentState == State.NORMAL) { // Chỉ thu gọn khi ở trạng thái bình thường
                collapseBubble()
            }
        }
    }

    // --- Các hàm còn lại (không thay đổi nhiều) ---

    private fun cancelCollapseTimer() = collapseJob?.cancel()

    private fun collapseBubble() {
        if (isCollapsed || currentState != State.NORMAL) return
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
                if (currentState != State.PANEL_OPEN) {
                    layoutParams.width = resources.getDimensionPixelSize(R.dimen.bubble_size)
                    layoutParams.height = resources.getDimensionPixelSize(R.dimen.bubble_size)
                } else {
                    layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT
                    layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
                }
                windowManager.updateViewLayout(this, layoutParams)
            }
        } catch (e: Exception) { Log.e(TAG, "Error updating view layout", e) }
    }

    fun updateLanguageDisplay(sourceCode: String, targetCode: String) {
        try {
            controlPanelBinding.buttonLanguageSelection.text = "${sourceCode.uppercase()} → ${targetCode.uppercase()}"
        } catch (e: Exception) { Log.e(TAG, "Error updating language pair", e) }
    }

    fun setOnLanguageSelectClick(callback: () -> Unit) {
        onLanguageSelectClickListener = callback
    }

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
    }

    private fun createFunctionItems(): List<FunctionItem> {
        return listOf(
            FunctionItem("GLOBAL", R.drawable.ic_global, context.getString(R.string.function_global_translate), isClickable = true),
            FunctionItem("AREA", R.drawable.ic_crop, context.getString(R.string.function_area_translate), isClickable = true),
            FunctionItem("IMAGE", R.drawable.ic_image, context.getString(R.string.function_image_translate), isClickable = true),
            FunctionItem("COPY", R.drawable.ic_copy, context.getString(R.string.function_copy_text), isClickable = true),
            FunctionItem("AUTO_GLOBAL", R.drawable.ic_auto_play, context.getString(R.string.function_auto_global_translate), isClickable = true),
            FunctionItem("AUTO_AREA", R.drawable.ic_auto_play, context.getString(R.string.function_auto_area_translate), isClickable = true)
        )
    }

    private fun handleFunctionClick(item: FunctionItem) {
        Log.d(TAG, "Function clicked: ${item.id}")
        setState(State.NORMAL)
    }

    private fun setupBubbleView() {
        binding.ivBubbleIcon.setImageResource(R.drawable.ic_translate)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post {
            binding.controlPanel.root.visibility = View.GONE
            binding.bubbleView.visibility = View.VISIBLE
            currentState = State.NORMAL
            snapToEdge()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenDimensions()
        val recyclerView = controlPanelBinding.recyclerViewFunctions
        val spanCount = if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) 2 else 3
        (recyclerView.layoutManager as? GridLayoutManager)?.spanCount = spanCount
        if (currentState == State.PANEL_OPEN) {
            post { adjustPanelPosition() }
        } else {
            if (layoutParams.x > screenWidth - width) {
                layoutParams.x = screenWidth - width
                updateViewLayout()
            }
        }
    }

    private fun adjustPanelPosition() {
        var newX = layoutParams.x
        var newY = layoutParams.y
        if (layoutParams.x + binding.controlPanel.root.width > screenWidth) newX = screenWidth - binding.controlPanel.root.width
        if (layoutParams.y + binding.controlPanel.root.height > screenHeight) newY = screenHeight - binding.controlPanel.root.height
        if (newX < 0) newX = 0
        if (newY < 0) newY = 0
        if (newX != layoutParams.x || newY != layoutParams.y) {
            layoutParams.x = newX
            layoutParams.y = newY
            updateViewLayout()
        }
    }
}