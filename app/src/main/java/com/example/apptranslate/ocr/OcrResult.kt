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
 */
object OcrResultParser {
    fun parse(visionText: Text, processingTimeMs: Long): OcrResult {
        val blocks = visionText.textBlocks.map { block ->
            val lines = block.lines.map { line ->
                val elements = line.elements.map { element ->
                    OcrResult.Element(
                        text = element.text,
                        boundingBox = element.boundingBox
                    )
                }
                OcrResult.Line(
                    text = line.text,
                    boundingBox = line.boundingBox,
                    elements = elements
                )
            }
            OcrResult.Block(
                text = block.text,
                boundingBox = block.boundingBox,
                lines = lines
            )
        }
        return OcrResult(
            fullText = visionText.text,
            textBlocks = blocks,
            processingTimeMs = processingTimeMs
        )
    }
}