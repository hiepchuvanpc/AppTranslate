package com.example.apptranslate.model

import androidx.annotation.DrawableRes

/**
 * Data class đại diện cho một chức năng trong grid
 * @param id ID duy nhất của chức năng
 * @param iconRes Resource ID của icon
 * @param title Tiêu đề của chức năng
 * @param description Mô tả của chức năng
 * @param isClickable Có thể nhấn vào được không
 */
data class FunctionItem(
    val id: String,
    @DrawableRes val iconRes: Int,
    val title: String,
    val description: String,
    val isClickable: Boolean
)
