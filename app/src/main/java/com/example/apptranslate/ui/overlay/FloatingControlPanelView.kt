package com.example.apptranslate.ui.overlay

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.GridLayoutManager
import com.example.apptranslate.databinding.ViewFloatingControlPanelBinding
import com.example.apptranslate.ui.overlay.adapter.ControlPanelAdapter
import com.example.apptranslate.ui.overlay.model.ControlPanelItem
import kotlinx.coroutines.CoroutineScope
import android.graphics.Rect
import androidx.recyclerview.widget.RecyclerView
import com.example.apptranslate.R
/**
 * Panel điều khiển nổi với các chức năng chính
 */
@SuppressLint("ViewConstructor")
class FloatingControlPanelView(
    context: Context,
    private val coroutineScope: CoroutineScope,
    private val onActionClick: (ControlPanelAction) -> Unit,
    private val onLanguageSelectClick: () -> Unit,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val binding: ViewFloatingControlPanelBinding
    private lateinit var adapter: ControlPanelAdapter

    init {
        val inflater = LayoutInflater.from(context)
        binding = ViewFloatingControlPanelBinding.inflate(inflater, this, true)

        setupControlPanel()
    }

    /**
     * Thiết lập panel điều khiển
     */
    private fun setupControlPanel() {
        // Thiết lập RecyclerView với responsive layout
        val orientation = resources.configuration.orientation
        val spanCount = if (orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) 2 else 3

        binding.recyclerViewFunctions.layoutManager = GridLayoutManager(context, spanCount)

        // SỬA: Lấy khoảng cách từ dimens
        val spacing = resources.getDimensionPixelSize(R.dimen.panel_button_spacing)
        // THÊM: Áp dụng ItemDecoration
        binding.recyclerViewFunctions.addItemDecoration(GridSpacingItemDecoration(spanCount, spacing, true))

        // Tạo danh sách items
        val controlItems = createControlItems()

        // Setup adapter
        adapter = ControlPanelAdapter { item ->
            handleItemClick(item)
        }

        binding.recyclerViewFunctions.adapter = adapter
        adapter.submitList(controlItems)

        // Setup language selection button
        binding.buttonLanguageSelection.setOnClickListener {
            onLanguageSelectClick()
        }

        // Setup close button
        binding.buttonMove.setOnClickListener {
            animateOut()
        }
    }

    /**
     * Tạo danh sách các control items
     */
    private fun createControlItems(): List<ControlPanelItem> {
        return listOf(
            // Hàng điều khiển cơ bản
            ControlPanelItem(
                id = "home",
                iconRes = com.example.apptranslate.R.drawable.ic_home,
                title = "Home",
                action = ControlPanelAction.HOME,
                type = ControlPanelItem.Type.CONTROL
            ),
            ControlPanelItem(
                id = "move",
                iconRes = com.example.apptranslate.R.drawable.ic_drag_indicator,
                title = "Di chuyển",
                action = ControlPanelAction.MOVE,
                type = ControlPanelItem.Type.CONTROL
            ),

            // 6 chức năng chính
            ControlPanelItem(
                id = "global_translate",
                iconRes = com.example.apptranslate.R.drawable.ic_global,
                title = "Dịch toàn cầu",
                action = ControlPanelAction.GLOBAL_TRANSLATE,
                type = ControlPanelItem.Type.FUNCTION
            ),
            ControlPanelItem(
                id = "area_translate",
                iconRes = com.example.apptranslate.R.drawable.ic_crop,
                title = "Dịch khu vực",
                action = ControlPanelAction.AREA_TRANSLATE,
                type = ControlPanelItem.Type.FUNCTION
            ),
            ControlPanelItem(
                id = "image_translate",
                iconRes = com.example.apptranslate.R.drawable.ic_image,
                title = "Dịch ảnh",
                action = ControlPanelAction.IMAGE_TRANSLATE,
                type = ControlPanelItem.Type.FUNCTION
            ),
            ControlPanelItem(
                id = "copy_text",
                iconRes = com.example.apptranslate.R.drawable.ic_copy,
                title = "Sao chép văn bản",
                action = ControlPanelAction.COPY_TEXT,
                type = ControlPanelItem.Type.FUNCTION
            ),
            ControlPanelItem(
                id = "auto_global",
                iconRes = com.example.apptranslate.R.drawable.ic_auto_play,
                title = "Toàn cầu Tự động",
                action = ControlPanelAction.AUTO_GLOBAL,
                type = ControlPanelItem.Type.FUNCTION
            ),
            ControlPanelItem(
                id = "auto_area",
                iconRes = com.example.apptranslate.R.drawable.ic_auto_play,
                title = "Vùng Tự động",
                action = ControlPanelAction.AUTO_AREA,
                type = ControlPanelItem.Type.FUNCTION
            )
        )
    }

    /**
     * Xử lý click item
     */
    private fun handleItemClick(item: ControlPanelItem) {
        onActionClick(item.action)
    }

    /**
     * Animation hiển thị panel
     */
    fun animateIn() {
        // Bắt đầu từ scale nhỏ và alpha 0
        scaleX = 0.5f
        scaleY = 0.5f
        alpha = 0f

        animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(300)
            .start()
    }

    /**
     * Animation ẩn panel
     */
    fun animateOut(onComplete: (() -> Unit)? = null) {
        animate()
            .scaleX(0.5f)
            .scaleY(0.5f)
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                onComplete?.invoke()
            }
            .start()
    }
}

/**
 * Enum cho các hành động của control panel
 */
enum class ControlPanelAction {
    HOME,
    MOVE,
    GLOBAL_TRANSLATE,
    AREA_TRANSLATE,
    IMAGE_TRANSLATE,
    COPY_TEXT,
    AUTO_GLOBAL,
    AUTO_AREA,
    LANGUAGE_SELECTION
}

class GridSpacingItemDecoration(private val spanCount: Int, private val spacing: Int, private val includeEdge: Boolean) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val position = parent.getChildAdapterPosition(view)
        val column = position % spanCount
        if (includeEdge) {
            outRect.left = spacing - column * spacing / spanCount
            outRect.right = (column + 1) * spacing / spanCount
            if (position < spanCount) {
                outRect.top = spacing
            }
            outRect.bottom = spacing
        } else {
            outRect.left = column * spacing / spanCount
            outRect.right = spacing - (column + 1) * spacing / spanCount
            if (position >= spanCount) {
                outRect.top = spacing
            }
        }
    }
}