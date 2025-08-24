package com.example.apptranslate.data

import com.google.gson.annotations.SerializedName

/**
 * Data class đại diện cho một API key của Gemini và trạng thái sử dụng của nó.
 *
 * @property key Chuỗi API key.
 * @property lastUsedTimestamp Thời điểm (ms) key được sử dụng lần cuối.
 * @property requestsToday Số lượng request đã thực hiện trong ngày hôm nay.
 * @property requestsThisMinute Số lượng request đã thực hiện trong phút hiện tại.
 * @property firstRequestTimestampOfMinute Thời điểm bắt đầu của phút đang đếm.
 */
data class GeminiApiKey(
    @SerializedName("key") val key: String,
    @SerializedName("last_used_timestamp") var lastUsedTimestamp: Long = 0L,
    @SerializedName("requests_today") var requestsToday: Int = 0,
    @SerializedName("requests_this_minute") var requestsThisMinute: Int = 0,
    @SerializedName("first_request_timestamp_of_minute") var firstRequestTimestampOfMinute: Long = 0L
) {
    fun isRateLimited(): Boolean {
        // Kiểm tra giới hạn request mỗi ngày (RPD)
        if (requestsToday >= MAX_REQUESTS_PER_DAY) {
            // Cần kiểm tra xem ngày đã reset chưa, nhưng để đơn giản, ta giả định logic này được xử lý bên ngoài
            return true
        }

        // Kiểm tra giới hạn request mỗi phút (RPM)
        val currentTime = System.currentTimeMillis()
        if (currentTime - firstRequestTimestampOfMinute < 60_000) {
            if (requestsThisMinute >= MAX_REQUESTS_PER_MINUTE) {
                return true
            }
        }
        return false
    }

    companion object {
        const val MAX_REQUESTS_PER_MINUTE = 15
        const val MAX_REQUESTS_PER_DAY = 1000
    }
}