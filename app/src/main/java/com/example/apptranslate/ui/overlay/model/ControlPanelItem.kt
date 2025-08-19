package com.example.apptranslate.ui.overlay.model

import com.example.apptranslate.ui.overlay.ControlPanelAction

/**
 * Data class cho items trong control panel
 */
data class ControlPanelItem(
    val id: String,
    val iconRes: Int,
    val title: String,
    val action: ControlPanelAction,
    val type: Type = Type.FUNCTION,
    val isEnabled: Boolean = true
) {
    enum class Type {
        CONTROL,    // Điều khiển cơ bản (Home, Move)
        FUNCTION    // Chức năng chính (Translate, OCR, etc.)
    }
}
