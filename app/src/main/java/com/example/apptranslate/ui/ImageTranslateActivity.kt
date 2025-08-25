package com.example.apptranslate.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.apptranslate.R
import com.example.apptranslate.data.SettingsManager
import com.example.apptranslate.data.TranslationManager
import com.example.apptranslate.ocr.OcrManager
import com.example.apptranslate.service.OverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

class ImageTranslateActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ImageTranslateActivity"
        const val ACTION_TRANSLATE_IMAGE = "TRANSLATE_IMAGE"
        const val RESULT_IMAGE_TRANSLATED = "IMAGE_TRANSLATED"
        const val EXTRA_TRANSLATED_RESULTS = "TRANSLATED_RESULTS"

        private const val PERMISSION_REQUEST_CODE = 1001

        // Quyền cần thiết dựa trên phiên bản Android
        private fun getRequiredPermissions(): Array<String> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.READ_MEDIA_IMAGES
                )
            } else {
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                )
            }
        }
    }

    private lateinit var settingsManager: SettingsManager
    private lateinit var translationManager: TranslationManager
    private val ocrManager by lazy { OcrManager.getInstance() }

    private var photoFile: File? = null

    // Launcher cho chụp ảnh
    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            photoFile?.let { file ->
                processImageFile(file)
            }
        } else {
            finish()
        }
    }

    // Launcher cho chọn ảnh từ thư viện
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            processImageUri(uri)
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settingsManager = SettingsManager.getInstance(this)
        translationManager = TranslationManager(this)

        Log.d(TAG, "Android SDK: ${Build.VERSION.SDK_INT}")
        val requiredPermissions = getRequiredPermissions()
        Log.d(TAG, "Required permissions: ${requiredPermissions.joinToString(", ")}")

        requiredPermissions.forEach { permission ->
            val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Permission $permission: ${if (granted) "GRANTED" else "DENIED"}")
        }

        if (checkPermissions()) {
            showImageSourceDialog()
        } else {
            requestPermissions()
        }
    }

    private fun checkPermissions(): Boolean {
        return getRequiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val permissions = getRequiredPermissions()
        ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                showImageSourceDialog()
            } else {
                // Kiểm tra quyền nào bị từ chối
                val deniedPermissions = permissions.filterIndexed { index, _ ->
                    grantResults[index] != PackageManager.PERMISSION_GRANTED
                }

                val message = when {
                    deniedPermissions.contains(Manifest.permission.CAMERA) &&
                    (deniedPermissions.contains(Manifest.permission.READ_EXTERNAL_STORAGE) ||
                     deniedPermissions.contains(Manifest.permission.READ_MEDIA_IMAGES)) -> {
                        "Cần cấp quyền Camera và Thư viện ảnh để sử dụng chức năng này"
                    }
                    deniedPermissions.contains(Manifest.permission.CAMERA) -> {
                        "Cần cấp quyền Camera để chụp ảnh"
                    }
                    deniedPermissions.contains(Manifest.permission.READ_EXTERNAL_STORAGE) ||
                    deniedPermissions.contains(Manifest.permission.READ_MEDIA_IMAGES) -> {
                        "Cần cấp quyền Thư viện ảnh để chọn ảnh"
                    }
                    else -> "Cần cấp quyền để sử dụng chức năng này"
                }

                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun showImageSourceDialog() {
        val cameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val storagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (cameraPermission) {
            options.add("Chụp ảnh từ camera")
            actions.add { openCamera() }
        }

        if (storagePermission) {
            options.add("Chọn ảnh từ thư viện")
            actions.add { openGallery() }
        }

        if (options.isEmpty()) {
            Toast.makeText(this, "Không có quyền sử dụng camera hoặc thư viện ảnh", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (options.size == 1) {
            // Nếu chỉ có 1 lựa chọn, thực hiện luôn
            actions[0]()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Chọn nguồn ảnh")
            .setItems(options.toTypedArray()) { _, which ->
                actions[which]()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun openCamera() {
        try {
            photoFile = createImageFile()
            photoFile?.let { file ->
                val photoUri = FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    file
                )
                takePictureLauncher.launch(photoUri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera", e)
            Toast.makeText(this, "Không thể mở camera", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun openGallery() {
        pickImageLauncher.launch("image/*")
    }

    private fun createImageFile(): File {
        val timestamp = System.currentTimeMillis()
        val fileName = "IMG_${timestamp}.jpg"
        return File(getExternalFilesDir(null), fileName)
    }

    private fun processImageFile(file: File) {
        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    BitmapFactory.decodeFile(file.absolutePath)
                }

                if (bitmap != null) {
                    processImageBitmap(bitmap)
                } else {
                    Toast.makeText(this@ImageTranslateActivity, "Không thể đọc ảnh", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image file", e)
                Toast.makeText(this@ImageTranslateActivity, "Lỗi xử lý ảnh: ${e.message}", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun processImageUri(uri: Uri) {
        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val inputStream: InputStream? = contentResolver.openInputStream(uri)
                    BitmapFactory.decodeStream(inputStream)
                }

                if (bitmap != null) {
                    processImageBitmap(bitmap)
                } else {
                    Toast.makeText(this@ImageTranslateActivity, "Không thể đọc ảnh", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image URI", e)
                Toast.makeText(this@ImageTranslateActivity, "Lỗi xử lý ảnh: ${e.message}", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private suspend fun processImageBitmap(bitmap: Bitmap) {
        try {
            // Hiển thị loading
            Toast.makeText(this, "Đang xử lý ảnh...", Toast.LENGTH_SHORT).show()

            val sourceLang = settingsManager.getSourceLanguageCode() ?: "vi"
            val targetLang = settingsManager.getTargetLanguageCode() ?: "en"
            val transSource = settingsManager.getTranslationSource()

            // Thực hiện OCR
            val ocrResult = withContext(Dispatchers.IO) {
                ocrManager.recognizeTextFromBitmap(bitmap, sourceLang)
            }

            val blocksToTranslate = ocrResult.textBlocks.filter {
                it.text.isNotBlank() && it.boundingBox != null
            }

            if (blocksToTranslate.isEmpty()) {
                Toast.makeText(this, "Không tìm thấy văn bản trong ảnh", Toast.LENGTH_LONG).show()
                finish()
                return
            }

            // Thực hiện dịch
            val combinedText = blocksToTranslate.joinToString(separator = "\n\n") { it.text }
            val translationResult = withContext(Dispatchers.IO) {
                translationManager.translate(combinedText, sourceLang, targetLang, transSource)
            }

            translationResult.onSuccess { translatedText ->
                val translatedSegments = translatedText.split("\n\n")

                if (blocksToTranslate.size == translatedSegments.size) {
                    val results = blocksToTranslate.zip(translatedSegments).map { (original, translated) ->
                        ImageTranslationResult(
                            originalText = original.text,
                            translatedText = translated,
                            boundingBox = original.boundingBox!!
                        )
                    }

                    // Gửi kết quả về OverlayService để hiển thị global overlay
                    sendResultsToOverlayService(results)

                } else {
                    Log.w(TAG, "Mismatch between OCR blocks (${blocksToTranslate.size}) and translated segments (${translatedSegments.size})")
                    Toast.makeText(this, "Lỗi trong quá trình dịch", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }.onFailure { e ->
                Log.e(TAG, "Translation failed", e)
                Toast.makeText(this, "Lỗi dịch: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing bitmap", e)
            Toast.makeText(this, "Lỗi xử lý: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        } finally {
            bitmap.recycle()
        }
    }

    private fun sendResultsToOverlayService(results: List<ImageTranslationResult>) {
        val intent = Intent(OverlayService.ACTION_SHOW_IMAGE_TRANSLATION_RESULTS).apply {
            setPackage(packageName)
            putParcelableArrayListExtra(EXTRA_TRANSLATED_RESULTS, ArrayList(results))
        }
        sendBroadcast(intent)

        // Thông báo thành công và đóng activity
        Toast.makeText(this, "Dịch ảnh thành công! Kết quả sẽ hiển thị trên màn hình.", Toast.LENGTH_LONG).show()

        setResult(Activity.RESULT_OK)
        finish()
    }
}

// Data class để lưu kết quả dịch ảnh
data class ImageTranslationResult(
    val originalText: String,
    val translatedText: String,
    val boundingBox: android.graphics.Rect
) : android.os.Parcelable {
    constructor(parcel: android.os.Parcel) : this(
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readParcelable(android.graphics.Rect::class.java.classLoader) ?: android.graphics.Rect()
    )

    override fun writeToParcel(parcel: android.os.Parcel, flags: Int) {
        parcel.writeString(originalText)
        parcel.writeString(translatedText)
        parcel.writeParcelable(boundingBox, flags)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : android.os.Parcelable.Creator<ImageTranslationResult> {
        override fun createFromParcel(parcel: android.os.Parcel): ImageTranslationResult {
            return ImageTranslationResult(parcel)
        }

        override fun newArray(size: Int): Array<ImageTranslationResult?> {
            return arrayOfNulls(size)
        }
    }
}
