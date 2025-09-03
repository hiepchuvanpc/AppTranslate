package com.example.apptranslate.ocr

import android.graphics.Rect
import com.google.mlkit.vision.text.Text

// Data classes không thay đổi, vẫn rất tốt.
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

                val elements = line.elements.mapNotNull { element ->
                    val elementBox = element.boundingBox ?: return@mapNotNull null
                    val elementText = element.text.trim()
                    if (elementText.isEmpty()) return@mapNotNull null
                    OcrResult.Element(elementText, elementBox)
                }

                if (elements.isEmpty()) return@mapNotNull null

                val mergedText = if (elements.size > 1 && isLikelyMultiWordLabel(line)) {
                    joinElementsWithHeuristicSpacing(elements)
                } else {
                    lineText
                }

                OcrResult.Line(
                    text = mergedText,
                    boundingBox = lineBox,
                    elements = elements
                )
            }

            if (lines.isEmpty()) return@mapNotNull null

            OcrResult.Block(
                // ✨ [SỬA ĐỔI] Dùng "\n" để giữ lại cấu trúc xuống dòng trong một block.
                // Điều này giúp văn bản dễ đọc và dịch chính xác hơn.
                text = lines.joinToString("\n") { it.text },
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
     * Heuristic to determine if a line is likely a short UI label consisting of multiple words
     * that MLKit might have failed to space correctly.
     * @param line The Text.Line object from ML Kit.
     * @return True if the line is likely a candidate for heuristic spacing.
     */
    private fun isLikelyMultiWordLabel(line: Text.Line): Boolean {
        val box = line.boundingBox ?: return false
        // These are heuristic values, tuned for typical mobile UI elements.
        // - Text is relatively short.
        // - Bounding box width is not excessively large.
        val maxLength = 25
        val maxWidthPixels = 600
        return (line.text.length <= maxLength && box.width() < maxWidthPixels)
    }

    /**
     * Joins text elements into a single string, intelligently adding spaces based on
     * the horizontal gap between their bounding boxes.
     * @param elements A list of sorted or unsorted OCR elements from a single line.
     * @return A single string with spaces potentially added.
     */
    private fun joinElementsWithHeuristicSpacing(elements: List<OcrResult.Element>): String {
        if (elements.isEmpty()) return ""

        // Sort elements by their horizontal position to ensure correct order.
        val sorted = elements.sortedBy { it.boundingBox?.left ?: 0 }
        val stringBuilder = StringBuilder()

        for (i in sorted.indices) {
            stringBuilder.append(sorted[i].text)
            // Add a space if it's not the last element
            if (i < sorted.lastIndex) {
                val current = sorted[i].boundingBox
                val next = sorted[i+1].boundingBox

                if (current != null && next != null) {
                    val gap = next.left - current.right
                    
                    // CẢI THIỆN: Giảm threshold để phát hiện khoảng trắng tốt hơn cho tiếng Việt
                    // Heuristic: A "space" is a gap larger than 15% of the current element's width,
                    // or a gap larger than 30% of the element's height (giảm từ 20% và 40%)
                    val spaceThresholdWidth = (current.width() * 0.15f).toInt()
                    val spaceThresholdHeight = (current.height() * 0.3f).toInt()
                    
                    // Thêm điều kiện: nếu gap > 5px thì cũng coi là có space (cho các font nhỏ)
                    val minGapPixels = 5

                    if (gap > spaceThresholdWidth || gap > spaceThresholdHeight || gap > minGapPixels) {
                        stringBuilder.append(" ")
                    }
                }
            }
        }
        return stringBuilder.toString()
    }
}