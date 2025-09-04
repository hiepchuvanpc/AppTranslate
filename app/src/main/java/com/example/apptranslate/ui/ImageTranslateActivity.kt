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
import com.example.apptranslate.ocr.OcrResult
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

            // Thực hiện dịch với logic thông minh từ OverlayService
            val intelligentCombinedText = combineTextIntelligently(blocksToTranslate)
            val translationResult = withContext(Dispatchers.IO) {
                translationManager.translate(intelligentCombinedText, sourceLang, targetLang, transSource)
            }

            translationResult.onSuccess { translatedText ->
                val translatedSegments = splitTranslatedTextIntelligently(translatedText, blocksToTranslate.size)

                if (blocksToTranslate.size == translatedSegments.size) {
                    // Lưu bitmap gốc vào file tạm thời
                    val bitmapPath = saveImageToTemp(bitmap)

                    val results = blocksToTranslate.zip(translatedSegments).map { (original, translated) ->
                        ImageTranslationResult(
                            originalText = original.text,
                            translatedText = translated,
                            boundingBox = original.boundingBox!!
                        )
                    }

                    // Gửi kết quả về OverlayService để hiển thị với ảnh nền
                    sendResultsToOverlayService(results, bitmapPath)

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

    private fun sendResultsToOverlayService(results: List<ImageTranslationResult>, bitmapPath: String?) {
        Log.d(TAG, "Sending ${results.size} translation results to OverlayService with image path: $bitmapPath")
        val intent = Intent(OverlayService.ACTION_SHOW_IMAGE_TRANSLATION_RESULTS).apply {
            setPackage(packageName)
            putParcelableArrayListExtra(EXTRA_TRANSLATED_RESULTS, ArrayList(results))
            if (bitmapPath != null) {
                putExtra("BACKGROUND_IMAGE_PATH", bitmapPath)
            }
        }
        sendBroadcast(intent)
        Log.d(TAG, "Broadcast sent successfully")

        // Thông báo thành công và đóng activity
        Toast.makeText(this, "Dịch ảnh thành công! Kết quả sẽ hiển thị trên màn hình.", Toast.LENGTH_LONG).show()

        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun saveImageToTemp(bitmap: Bitmap): String? {
        return try {
            val tempDir = File(cacheDir, "image_translation")
            if (!tempDir.exists()) {
                tempDir.mkdirs()
            }

            val tempFile = File(tempDir, "temp_image_${System.currentTimeMillis()}.jpg")
            tempFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }

            Log.d(TAG, "Saved temporary image to: ${tempFile.absolutePath}")
            tempFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save temporary image", e)
            null
        }
    }

    /**
     * Gộp text thông minh để giữ ngữ cảnh tốt hơn (đơn giản hóa cho dịch ảnh)
     */
    private fun combineTextIntelligently(blocks: List<OcrResult.Block>): String {
        val delimiter = " ◆◇◆ " // Delimiter đặc biệt, ít khả năng xuất hiện trong text thường
        val texts = blocks.map { it.text.trim() }

        // Phân tích vị trí các blocks để quyết định cách gộp
        val combinedParts = mutableListOf<String>()

        texts.forEachIndexed { index, text ->
            if (text.isNotBlank()) {
                // Kiểm tra xem text có cần nối liền với text trước không
                val currentBlock = blocks[index]
                val shouldConnect = if (index > 0) {
                    val prevBlock = blocks[index - 1]
                    // Nối liền nếu hai blocks ở gần nhau (cùng dòng hoặc dòng liền kề)
                    val verticalDistance = kotlin.math.abs(currentBlock.boundingBox?.top ?: 0 - (prevBlock.boundingBox?.bottom ?: 0))
                    val isCloseVertically = verticalDistance < 20 // 20px threshold

                    // Và text trước không kết thúc bằng dấu câu
                    val prevText = texts[index - 1].trim()
                    val prevEndsWithPunctuation = prevText.endsWith(".") || prevText.endsWith("!") ||
                                                   prevText.endsWith("?") || prevText.endsWith(":") ||
                                                   prevText.endsWith(";") || prevText.endsWith(",")

                    isCloseVertically && !prevEndsWithPunctuation
                } else false

                if (shouldConnect && combinedParts.isNotEmpty()) {
                    // Nối với part trước đó bằng dấu space thay vì delimiter
                    val lastIndex = combinedParts.size - 1
                    combinedParts[lastIndex] = combinedParts[lastIndex] + " " + text
                } else {
                    // Thêm như part riêng biệt
                    combinedParts.add(text)
                }
            }
        }

        // Gộp các parts bằng delimiter đặc biệt
        val result = combinedParts.joinToString(delimiter)

        Log.d(TAG, "Combined ${texts.size} texts into ${combinedParts.size} intelligent parts")
        Log.d(TAG, "Intelligent combined result: '$result'")

        return result
    }

    /**
     * Tách kết quả dịch thông minh theo số lượng blocks gốc (đơn giản hóa cho dịch ảnh)
     */
    private fun splitTranslatedTextIntelligently(translatedText: String, expectedParts: Int): List<String> {
        val delimiter = " ◆◇◆ "

        // Thử tách theo delimiter đặc biệt trước
        val parts = translatedText.split(delimiter).filter { it.isNotBlank() }

        if (parts.size == expectedParts) {
            Log.d(TAG, "Perfect split: got ${parts.size} parts as expected")
            return parts.map { postprocessTranslatedText(it) }
        }

        // Nếu không khớp, thử các cách tách khác
        Log.d(TAG, "Delimiter split gave ${parts.size} parts, expected $expectedParts")

        if (parts.size < expectedParts) {
            // Ít parts hơn mong đợi - thử tách thêm bằng dấu câu
            val expandedParts = mutableListOf<String>()
            parts.forEach { part ->
                // Tách thêm theo dấu câu nếu cần
                val subParts = part.split(Regex("(?<=[.!?:;])\\s+")).filter { it.isNotBlank() }
                expandedParts.addAll(subParts)
            }

            if (expandedParts.size >= expectedParts) {
                Log.d(TAG, "Extended split to ${expandedParts.size} parts")
                return expandedParts.take(expectedParts).map { postprocessTranslatedText(it) }
            }
        }

        if (parts.size > expectedParts) {
            // Nhiều parts hơn - gộp lại
            val consolidatedParts = mutableListOf<String>()
            val partsPerGroup = parts.size / expectedParts
            val remainder = parts.size % expectedParts

            var index = 0
            repeat(expectedParts) { groupIndex ->
                val groupSize = partsPerGroup + if (groupIndex < remainder) 1 else 0
                val groupParts = parts.subList(index, index + groupSize)
                consolidatedParts.add(groupParts.joinToString(" "))
                index += groupSize
            }

            Log.d(TAG, "Consolidated ${parts.size} parts into $expectedParts groups")
            return consolidatedParts.map { postprocessTranslatedText(it) }
        }

        // Fallback: split equal chunks
        val fallbackParts = mutableListOf<String>()
        val chunkSize = translatedText.length / expectedParts

        for (i in 0 until expectedParts) {
            val start = i * chunkSize
            val end = if (i == expectedParts - 1) translatedText.length else (i + 1) * chunkSize
            fallbackParts.add(translatedText.substring(start, end).trim())
        }

        Log.d(TAG, "Using fallback equal chunk split")
        return fallbackParts.map { postprocessTranslatedText(it) }
    }

    /**
     * Xử lý hậu kỳ text đã dịch: làm sạch và sửa lỗi viết hoa không cần thiết
     */
    private fun postprocessTranslatedText(translatedText: String): String {
        var result = translatedText.trim()

        // Làm sạch khoảng trắng thừa
        result = result.replace(Regex("\\s+"), " ").trim()

        // Sửa lỗi viết hoa không cần thiết ở đầu câu khi xuống dòng
        // Chỉ giữ viết hoa nếu là đầu câu thật sự (sau dấu câu)
        result = fixUnnecessaryCapitalization(result)

        return result
    }

    /**
     * Sửa lỗi viết hoa không cần thiết khi xuống dòng
     */
    private fun fixUnnecessaryCapitalization(text: String): String {
        var result = text

        // Pattern để tìm chữ viết hoa không cần thiết ở giữa câu
        // Chỉ sửa nếu từ viết hoa không đứng sau dấu câu
        val words = result.split(" ")
        val fixedWords = mutableListOf<String>()

        words.forEachIndexed { index, word ->
            if (index == 0) {
                // Từ đầu tiên - giữ nguyên
                fixedWords.add(word)
            } else {
                val prevWord = words[index - 1]
                // Kiểm tra xem từ trước có kết thúc bằng dấu câu không
                val prevEndsWithPunctuation = prevWord.endsWith(".") || prevWord.endsWith("!") ||
                                               prevWord.endsWith("?") || prevWord.endsWith(":") ||
                                               prevWord.endsWith(";")

                if (!prevEndsWithPunctuation && word.isNotEmpty() && word[0].isUpperCase()) {
                    // Từ viết hoa nhưng không phải đầu câu mới -> chuyển thành chữ thường
                    val fixedWord = word[0].lowercaseChar() + word.substring(1)
                    Log.d(TAG, "Fixed capitalization: '$word' -> '$fixedWord'")
                    fixedWords.add(fixedWord)
                } else {
                    // Giữ nguyên
                    fixedWords.add(word)
                }
            }
        }

        result = fixedWords.joinToString(" ")
        return result
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
