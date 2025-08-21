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

object OcrResultParser {
    fun parse(visionText: Text, processingTimeMs: Long): OcrResult {
        val blocks = visionText.textBlocks.mapNotNull { block ->
            val blockBox = block.boundingBox ?: return@mapNotNull null
            val blockText = block.text.trim()
            if (blockText.isEmpty()) return@mapNotNull null

            val lines = block.lines.mapNotNull { line ->
                val lineBox = line.boundingBox ?: return@mapNotNull null
                val lineText = line.text.trim()
                if (lineText.isEmpty()) return@mapNotNull null

                // Lấy elements & xử lý spacing
                val elements = line.elements.mapNotNull { element ->
                    val elementBox = element.boundingBox ?: return@mapNotNull null
                    val elementText = element.text.trim()
                    if (elementText.isEmpty()) return@mapNotNull null

                    OcrResult.Element(elementText, elementBox)
                }

                // Với line ngắn → thử tách elements theo spacing
                val mergedText = if (elements.size > 1 && isLikelyLabel(line)) {
                    joinElementsWithSpacing(elements)
                } else {
                    lineText
                }

                OcrResult.Line(
                    text = mergedText,
                    boundingBox = lineBox,
                    elements = elements
                )
            }

            OcrResult.Block(
                text = lines.joinToString(" ") { it.text },
                boundingBox = blockBox,
                lines = lines
            )
        }

        return OcrResult(
            fullText = blocks.joinToString("\n") { it.text },
            textBlocks = blocks,
            processingTimeMs = processingTimeMs
        )
    }

    /**
    * Ước lượng xem line này có phải label ngắn không
    */
    private fun isLikelyLabel(line: Text.Line): Boolean {
        val box = line.boundingBox ?: return false
        // Ví dụ: line rộng không quá lớn, ít chữ → có khả năng là app label
        return (line.text.length <= 15 && box.width() < 500)
    }

    /**
    * Ghép element theo khoảng cách boundingBox
    */
    private fun joinElementsWithSpacing(elements: List<OcrResult.Element>): String {
        if (elements.isEmpty()) return ""

        val sorted = elements.sortedBy { it.boundingBox?.left ?: 0 }
        val sb = StringBuilder()
        for (i in sorted.indices) {
            sb.append(sorted[i].text)
            if (i < sorted.lastIndex) {
                val current = sorted[i].boundingBox
                val next = sorted[i + 1].boundingBox
                if (current != null && next != null) {
                    val gap = next.left - current.right
                    if (gap > current.width() * 0.2f) {
                        sb.append(" ") // thêm khoảng trắng nếu gap đủ lớn
                    }
                }
            }
        }
        return sb.toString()
    }
}
