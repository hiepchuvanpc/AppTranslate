package com.example.apptranslate.ui.overlay.model

/**
 * Model class đại diện cho một nút chức năng trong panel điều khiển
 */
data class FunctionItem(
    val id: String,
    val iconRes: Int,
    val title: String,
    val isClickable: Boolean = true
)
