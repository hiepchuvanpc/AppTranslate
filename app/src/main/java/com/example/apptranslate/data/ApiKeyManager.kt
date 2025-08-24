package com.example.apptranslate.data

import android.content.Context
import java.util.Calendar

/**
 * Quản lý vòng đời và việc sử dụng các API key của Gemini.
 * Class này là Singleton.
 */
class ApiKeyManager private constructor(context: Context) {

    private val settingsManager = SettingsManager.getInstance(context)

    // Lấy danh sách key từ SharedPreferences
    private var apiKeys: MutableList<GeminiApiKey> = settingsManager.getGeminiApiKeys()

    /**
     * Lấy ra API key tốt nhất hiện có (còn nhiều request nhất và không bị giới hạn).
     * @return GeminiApiKey hoặc null nếu tất cả đều bị giới hạn.
     */
    @Synchronized
    fun getAvailableKey(): GeminiApiKey? {
        val currentTime = System.currentTimeMillis()
        resetLimitsIfNeeded(currentTime)

        // Sắp xếp các key: ưu tiên key còn nhiều request trong ngày nhất
        val sortedKeys = apiKeys.sortedByDescending { GeminiApiKey.MAX_REQUESTS_PER_DAY - it.requestsToday }

        return sortedKeys.firstOrNull { !it.isRateLimited() }
    }

    /**
     * Cập nhật trạng thái của một key sau khi sử dụng.
     * @param keyString Key vừa được sử dụng.
     * @param wasSuccessful Request có thành công không.
     */
    @Synchronized
    fun updateKeyUsage(keyString: String, wasSuccessful: Boolean) {
        if (!wasSuccessful) return // Không tính các request thất bại

        val key = apiKeys.find { it.key == keyString } ?: return
        val currentTime = System.currentTimeMillis()

        key.lastUsedTimestamp = currentTime
        key.requestsToday++

        // Cập nhật bộ đếm request mỗi phút
        if (currentTime - key.firstRequestTimestampOfMinute < 60_000) {
            key.requestsThisMinute++
        } else {
            // Bắt đầu một phút mới
            key.firstRequestTimestampOfMinute = currentTime
            key.requestsThisMinute = 1
        }

        saveKeys()
    }

    /**
     * Thêm một key mới vào danh sách.
     * @return true nếu thêm thành công, false nếu key đã tồn tại.
     */
    @Synchronized
    fun addApiKey(keyString: String): Boolean {
        if (keyString.isBlank() || apiKeys.any { it.key == keyString }) {
            return false
        }
        apiKeys.add(GeminiApiKey(key = keyString))
        saveKeys()
        return true
    }

    /**
     * Xóa một key khỏi danh sách.
     */
    @Synchronized
    fun removeApiKey(keyString: String) {
        apiKeys.removeAll { it.key == keyString }
        saveKeys()
    }

    /**
     * Lấy danh sách các key hiện tại.
     */
    @Synchronized
    fun getAllKeys(): List<GeminiApiKey> = apiKeys.toList()


    /**
     * Lưu trạng thái hiện tại của danh sách key vào SharedPreferences.
     */
    private fun saveKeys() {
        settingsManager.saveGeminiApiKeys(apiKeys)
    }

    /**
     * Kiểm tra và reset các bộ đếm giới hạn (ngày, phút) nếu cần.
     */
    private fun resetLimitsIfNeeded(currentTime: Long) {
        val calendar = Calendar.getInstance()
        val currentDayOfYear = calendar.get(Calendar.DAY_OF_YEAR)

        apiKeys.forEach { key ->
            // Reset bộ đếm ngày
            calendar.timeInMillis = key.lastUsedTimestamp
            val lastUsedDayOfYear = calendar.get(Calendar.DAY_OF_YEAR)
            if (currentDayOfYear != lastUsedDayOfYear) {
                key.requestsToday = 0
            }

            // Reset bộ đếm phút
            if (currentTime - key.firstRequestTimestampOfMinute >= 60_000) {
                key.requestsThisMinute = 0
            }
        }
    }


    companion object {
        @Volatile
        private var INSTANCE: ApiKeyManager? = null

        fun getInstance(context: Context): ApiKeyManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ApiKeyManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}