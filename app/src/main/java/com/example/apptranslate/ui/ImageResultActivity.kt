package com.example.apptranslate.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.apptranslate.R
import com.example.apptranslate.databinding.ActivityImageResultBinding
import com.example.apptranslate.ui.overlay.TranslationResultView
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream

class ImageResultActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ImageResultActivity"
        const val EXTRA_IMAGE_URI = "EXTRA_IMAGE_URI"
        const val EXTRA_IMAGE_FILE_PATH = "EXTRA_IMAGE_FILE_PATH"
        const val EXTRA_TRANSLATION_RESULTS = "EXTRA_TRANSLATION_RESULTS"
    }

    private lateinit var binding: ActivityImageResultBinding
    private var currentImageUri: Uri? = null
    private var currentImageFilePath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        handleIntent()
    }

    private fun setupUI() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        binding.btnSelectAnotherImage.setOnClickListener {
            selectAnotherImage()
        }

        binding.btnRetranslate.setOnClickListener {
            retranslateCurrentImage()
        }
    }

    private fun handleIntent() {
        val imageUri = intent.getParcelableExtra<Uri>(EXTRA_IMAGE_URI)
        val imageFilePath = intent.getStringExtra(EXTRA_IMAGE_FILE_PATH)
        val results = intent.getParcelableArrayListExtra<ImageTranslationResult>(EXTRA_TRANSLATION_RESULTS)

        currentImageUri = imageUri
        currentImageFilePath = imageFilePath

        lifecycleScope.launch {
            try {
                val bitmap = loadImageBitmap(imageUri, imageFilePath)
                if (bitmap != null) {
                    displayImageWithResults(bitmap, results ?: emptyList())
                } else {
                    Toast.makeText(this@ImageResultActivity, "Không thể tải ảnh", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading image", e)
                Toast.makeText(this@ImageResultActivity, "Lỗi tải ảnh: ${e.message}", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private suspend fun loadImageBitmap(uri: Uri?, filePath: String?): Bitmap? {
        return try {
            when {
                uri != null -> {
                    val inputStream: InputStream? = contentResolver.openInputStream(uri)
                    BitmapFactory.decodeStream(inputStream)
                }
                filePath != null -> {
                    BitmapFactory.decodeFile(filePath)
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding bitmap", e)
            null
        }
    }

    private fun displayImageWithResults(bitmap: Bitmap, results: List<ImageTranslationResult>) {
        // Hiển thị ảnh gốc
        binding.imageView.setImageBitmap(bitmap)

        // Đợi imageView layout xong rồi mới add translation results
        binding.imageView.post {
            addTranslationOverlays(results, bitmap)
        }

        // Hiển thị thông tin
        binding.tvResultCount.text = "Tìm thấy ${results.size} đoạn văn bản"

        if (results.isEmpty()) {
            binding.tvNoResults.visibility = View.VISIBLE
        } else {
            binding.tvNoResults.visibility = View.GONE
        }
    }

    private fun addTranslationOverlays(results: List<ImageTranslationResult>, originalBitmap: Bitmap) {
        // Xóa các overlay cũ nếu có
        binding.overlayContainer.removeAllViews()

        val imageView = binding.imageView
        val imageViewWidth = imageView.width
        val imageViewHeight = imageView.height

        val bitmapWidth = originalBitmap.width
        val bitmapHeight = originalBitmap.height

        // Tính toán scale factor
        val scaleX = imageViewWidth.toFloat() / bitmapWidth.toFloat()
        val scaleY = imageViewHeight.toFloat() / bitmapHeight.toFloat()
        val scale = minOf(scaleX, scaleY)

        // Tính toán offset để center ảnh
        val scaledWidth = (bitmapWidth * scale).toInt()
        val scaledHeight = (bitmapHeight * scale).toInt()
        val offsetX = (imageViewWidth - scaledWidth) / 2
        val offsetY = (imageViewHeight - scaledHeight) / 2

        results.forEach { result ->
            val originalRect = result.boundingBox

            // Scale và offset vị trí
            val scaledRect = Rect(
                offsetX + (originalRect.left * scale).toInt(),
                offsetY + (originalRect.top * scale).toInt(),
                offsetX + (originalRect.right * scale).toInt(),
                offsetY + (originalRect.bottom * scale).toInt()
            )

            addTranslationResultView(scaledRect, result.originalText, result.translatedText)
        }
    }

    private fun addTranslationResultView(rect: Rect, originalText: String, translatedText: String) {
        val resultView = TranslationResultView(this).apply {
            updateText(translatedText)

            // Thêm long press để hiển thị text gốc
            setOnLongClickListener {
                Toast.makeText(context, "Văn bản gốc: $originalText", Toast.LENGTH_LONG).show()
                true
            }
        }

        val params = FrameLayout.LayoutParams(
            rect.width(),
            rect.height()
        ).apply {
            leftMargin = rect.left
            topMargin = rect.top
        }

        binding.overlayContainer.addView(resultView, params)
    }

    private fun selectAnotherImage() {
        val intent = Intent(this, ImageTranslateActivity::class.java).apply {
            action = ImageTranslateActivity.ACTION_TRANSLATE_IMAGE
        }
        startActivity(intent)
        finish()
    }

    private fun retranslateCurrentImage() {
        // Chuyển lại về ImageTranslateActivity với ảnh hiện tại
        val intent = Intent(this, ImageTranslateActivity::class.java).apply {
            action = ImageTranslateActivity.ACTION_TRANSLATE_IMAGE
            currentImageUri?.let { putExtra(EXTRA_IMAGE_URI, it) }
            currentImageFilePath?.let { putExtra(EXTRA_IMAGE_FILE_PATH, it) }
        }
        startActivity(intent)
        finish()
    }
}
