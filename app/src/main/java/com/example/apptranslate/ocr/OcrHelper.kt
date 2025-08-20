package com.example.apptranslate.ocr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Helper class để tích hợp OcrManager với UI components một cách an toàn với Coroutines.
 */
class OcrHelper(
    private val context: Context,
    private val coroutineScope: CoroutineScope // Nhận scope từ bên ngoài (vd: viewModelScope)
) {

    private val ocrManager = OcrManager.getInstance()

    private val _isProcessing = MutableLiveData<Boolean>()
    val isProcessing: LiveData<Boolean> = _isProcessing

    private val _ocrResult = MutableLiveData<OcrResult>()
    val ocrResult: LiveData<OcrResult> = _ocrResult

    private val _error = MutableLiveData<String>()
    val error: LiveData<String> = _error

    fun recognizeTextFromUri(imageUri: Uri, languageCode: String) {
        _isProcessing.value = true
        coroutineScope.launch {
            try {
                val bitmap = MediaStore.Images.Media.getBitmap(context.contentResolver, imageUri)
                recognizeTextFromBitmap(bitmap, languageCode)
            } catch (e: IOException) {
                _isProcessing.postValue(false)
                _error.postValue("Không thể đọc ảnh: ${e.message}")
            }
        }
    }

    fun recognizeTextFromBitmap(bitmap: Bitmap, languageCode: String) {
        _isProcessing.value = true
        coroutineScope.launch {
            try {
                val result = ocrManager.recognizeTextFromBitmap(bitmap, languageCode)
                _ocrResult.postValue(result)
            } catch (e: Exception) {
                _error.postValue("Lỗi OCR: ${e.message}")
            } finally {
                _isProcessing.postValue(false)
            }
        }
    }
}