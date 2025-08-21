package com.example.apptranslate.ocr

import android.graphics.Rect
import com.google.mlkit.vision.text.Text

/**
 * Data class để chứa kết quả OCR đã được xử lý.
 * Cấu trúc này làm cho việc sử dụng kết quả ở tầng UI trở nên dễ dàng hơn.
 */
data class OcrResult(
    val fullText: String,
    val textBlocks: List<Block>,
    val processingTimeMs: Long
) {
    data class Block(
        val text: String,
        val boundingBox: Rect?,
        val lines: List<Line>
    )

    data class Line(
        val text: String,
        val boundingBox: Rect?,
        val elements: List<Element>
    )

    data class Element(
        val text: String,
        val boundingBox: Rect?
    )
}

/**
 * Helper object để chuyển đổi từ `Text` của ML Kit sang cấu trúc `OcrResult` của chúng ta.
 * Cải thiện để xử lý chữ nhỏ tốt hơn.
 */
object OcrResultParser {
    fun parse(visionText: Text, processingTimeMs: Long): OcrResult {
        val blocks = visionText.textBlocks.mapNotNull { block ->
            val blockText = block.text.trim()
            val blockBox = block.boundingBox

            // Cải thiện lọc để nhận chữ nhỏ hơn
            if (blockText.isEmpty() || blockBox == null) return@mapNotNull null

            // Giảm ngưỡng kích thước tối thiểu để nhận diện chữ nhỏ
            val minWidth = 6 // Giảm từ 20 xuống 6
            val minHeight = 6 // Giảm từ 15 xuống 6
            val minTextLength = 1 // Cho phép text 1 ký tự (như biểu tượng, số)

            if (blockBox.width() < minWidth ||
                blockBox.height() < minHeight ||
                blockText.length < minTextLength) {
                return@mapNotNull null
            }

            // Lọc text không mong muốn nhưng vẫn giữ chữ nhỏ hợp lệ
            if (isInvalidText(blockText)) return@mapNotNull null

            val lines = block.lines.mapNotNull { line ->
                val lineText = line.text.trim()
                val lineBox = line.boundingBox

                if (lineText.isEmpty() || lineBox == null) return@mapNotNull null

                val elements = line.elements.mapNotNull { element ->
                    val elementText = element.text.trim()
                    val elementBox = element.boundingBox

                    if (elementText.isEmpty() || elementBox == null) return@mapNotNull null

                    OcrResult.Element(
                        text = elementText,
                        boundingBox = elementBox
                    )
                }

                OcrResult.Line(
                    text = lineText,
                    boundingBox = lineBox,
                    elements = elements
                )
            }

            OcrResult.Block(
                text = blockText,
                boundingBox = blockBox,
                lines = lines
            )
        }

        return OcrResult(
            fullText = visionText.text,
            textBlocks = blocks,
            processingTimeMs = processingTimeMs
        )
    }

    /**
     * Kiểm tra text có phải là invalid không
     * Cải thiện để giữ lại chữ nhỏ hợp lệ
     */
    private fun isInvalidText(text: String): Boolean {
        // Chỉ loại bỏ những text rõ ràng không hợp lệ
        return when {
            // Text chỉ toàn ký tự đặc biệt
            text.all { !it.isLetterOrDigit() } -> true
            // Text chỉ toàn khoảng trắng
            text.isBlank() -> true
            // Text có quá nhiều ký tự lặp (như "........")
            text.length > 3 && text.toSet().size == 1 -> true
            // Các trường hợp khác đều giữ lại, kể cả chữ nhỏ
            else -> false
        }
    }
}