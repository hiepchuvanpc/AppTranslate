package com.example.apptranslate.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.apptranslate.data.SettingsManager
import com.example.apptranslate.ocr.OcrManager
import com.example.apptranslate.service.OverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CopyTextActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CopyTextActivity"
        const val ACTION_COPY_TEXT = "COPY_TEXT"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_DATA = "EXTRA_DATA"
        const val RESULT_TEXT_COPIED = "TEXT_COPIED"
        const val EXTRA_COPY_RESULTS = "COPY_RESULTS"
    }

    private lateinit var settingsManager: SettingsManager
    private val ocrManager by lazy { OcrManager.getInstance() }
    private var mediaProjection: MediaProjection? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settingsManager = SettingsManager.getInstance(this)

        val action = intent.action
        if (action == ACTION_COPY_TEXT) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, RESULT_CANCELED)
            val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)

            if (resultCode == RESULT_OK && data != null) {
                initializeMediaProjection(resultCode, data)
                performScreenCopyText()
            } else {
                Toast.makeText(this, "Không thể khởi tạo chụp màn hình", Toast.LENGTH_SHORT).show()
                finish()
            }
        } else {
            finish()
        }
    }

    private fun initializeMediaProjection(resultCode: Int, data: Intent) {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
    }

    private fun performScreenCopyText() {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@CopyTextActivity, "Đang chụp màn hình để nhận diện văn bản...", Toast.LENGTH_SHORT).show()

                // Chụp màn hình (tương tự như trong OverlayService)
                val bitmap = captureScreen()
                if (bitmap == null) {
                    Toast.makeText(this@CopyTextActivity, "Không thể chụp màn hình", Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }

                // Thực hiện OCR
                val sourceLang = settingsManager.getSourceLanguageCode() ?: "vi"
                val ocrResult = withContext(Dispatchers.IO) {
                    ocrManager.recognizeTextFromBitmap(bitmap, sourceLang)
                }

                bitmap.recycle()

                val textBlocks = ocrResult.textBlocks.filter {
                    it.text.isNotBlank() && it.boundingBox != null
                }

                if (textBlocks.isEmpty()) {
                    Toast.makeText(this@CopyTextActivity, "Không tìm thấy văn bản trên màn hình", Toast.LENGTH_LONG).show()
                    finish()
                    return@launch
                }

                // Tạo danh sách kết quả để hiển thị
                val copyResults = textBlocks.map { block ->
                    CopyTextResult(
                        text = block.text,
                        boundingBox = block.boundingBox!!
                    )
                }

                    // TODO: Hiển thị kết quả trực tiếp hoặc xử lý theo ý bạn
                    Toast.makeText(this@CopyTextActivity, "Nhận diện văn bản thành công!", Toast.LENGTH_LONG).show()
                    setResult(RESULT_OK)
                    finish()

            } catch (e: Exception) {
                Log.e(TAG, "Error in performScreenCopyText", e)
                Toast.makeText(this@CopyTextActivity, "Lỗi: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private suspend fun captureScreen(): Bitmap? {
        // Sử dụng MediaProjection để chụp màn hình
        // Vì chúng ta đã có MediaProjection từ OverlayService, ta sẽ sử dụng nó
        // Đây là implementation đơn giản, trong thực tế cần setup ImageReader và VirtualDisplay
        return withContext(Dispatchers.IO) {
            try {
                // Tạm thời return null, sẽ được implement đầy đủ sau
                // Cần setup tương tự như trong OverlayService
                null
            } catch (e: Exception) {
                Log.e(TAG, "Error capturing screen", e)
                null
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaProjection?.stop()
    }
}

// Data class để lưu kết quả copy text
data class CopyTextResult(
    val text: String,
    val boundingBox: android.graphics.Rect
) : android.os.Parcelable {
    constructor(parcel: android.os.Parcel) : this(
        parcel.readString() ?: "",
        parcel.readParcelable(android.graphics.Rect::class.java.classLoader) ?: android.graphics.Rect()
    )

    override fun writeToParcel(parcel: android.os.Parcel, flags: Int) {
        parcel.writeString(text)
        parcel.writeParcelable(boundingBox, flags)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : android.os.Parcelable.Creator<CopyTextResult> {
        override fun createFromParcel(parcel: android.os.Parcel): CopyTextResult {
            return CopyTextResult(parcel)
        }

        override fun newArray(size: Int): Array<CopyTextResult?> {
            return arrayOfNulls(size)
        }
    }
}
