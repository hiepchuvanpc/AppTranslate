package com.example.apptranslate.model

/**
 * Data class đại diện cho một ngôn ngữ trong ứng dụng
 * @param code Mã ngôn ngữ theo chuẩn ISO (ví dụ: "en", "vi")
 * @param name Tên Việt hóa của ngôn ngữ (ví dụ: "Tiếng Anh", "Tiếng Việt")
 * @param nativeName Tên gốc của ngôn ngữ (ví dụ: "English", "Tiếng Việt")
 * @param flag Emoji cờ quốc gia (ví dụ: "🇺🇸", "🇻🇳")
 */
data class Language(
    val code: String,
    val name: String,
    val nativeName: String,
    val flag: String = "🌐" // Default flag
) {
    /**
     * Hiển thị tên gốc kèm mã ngôn ngữ
     * Ví dụ: "English (en)"
     */
    val nativeNameWithCode: String
        get() = "$nativeName ($code)"
        
    companion object {
        // Danh sách ngôn ngữ đầy đủ
        val SAMPLE_LANGUAGES = listOf(
            // Châu Á
            Language("vi", "Tiếng Việt", "Tiếng Việt", "🇻🇳"),
            Language("zh", "Tiếng Trung (Giản thể)", "简体中文", "🇨🇳"),
            Language("zh-TW", "Tiếng Trung (Phồn thể)", "繁體中文", "🇹🇼"),
            Language("ja", "Tiếng Nhật", "日本語", "🇯🇵"),
            Language("ko", "Tiếng Hàn", "한국어", "🇰🇷"),
            Language("th", "Tiếng Thái", "ไทย", "🇹🇭"),
            Language("id", "Tiếng Indonesia", "Bahasa Indonesia", "🇮🇩"),
            Language("ms", "Tiếng Malaysia", "Bahasa Melayu", "🇲🇾"),
            Language("hi", "Tiếng Hindi", "हिन्दी", "🇮🇳"),
            Language("ta", "Tiếng Tamil", "தமிழ்", "🇮🇳"),
            Language("bn", "Tiếng Bengali", "বাংলা", "🇧🇩"),
            
            // Châu Âu
            Language("en", "Tiếng Anh", "English", "�🇧"),
            Language("en-US", "Tiếng Anh (Mỹ)", "English (US)", "�🇺🇸"),
            Language("fr", "Tiếng Pháp", "Français", "🇫🇷"),
            Language("de", "Tiếng Đức", "Deutsch", "🇩🇪"),
            Language("es", "Tiếng Tây Ban Nha", "Español", "��"),
            Language("it", "Tiếng Ý", "Italiano", "��"),
            Language("pt", "Tiếng Bồ Đào Nha", "Português", "��"),
            Language("pt-BR", "Tiếng Bồ Đào Nha (Brazil)", "Português do Brasil", "��"),
            Language("ru", "Tiếng Nga", "Русский", "🇷🇺"),
            Language("pl", "Tiếng Ba Lan", "Polski", "🇵🇱"),
            Language("nl", "Tiếng Hà Lan", "Nederlands", "��"),
            Language("sv", "Tiếng Thụy Điển", "Svenska", "🇸🇪"),
            Language("da", "Tiếng Đan Mạch", "Dansk", "🇩🇰"),
            Language("fi", "Tiếng Phần Lan", "Suomi", "🇫🇮"),
            Language("no", "Tiếng Na Uy", "Norsk", "🇳🇴"),
            Language("cs", "Tiếng Séc", "Čeština", "🇨🇿"),
            Language("hu", "Tiếng Hungary", "Magyar", "🇭🇺"),
            Language("ro", "Tiếng Rumani", "Română", "🇷�"),
            Language("tr", "Tiếng Thổ Nhĩ Kỳ", "Türkçe", "🇹🇷"),
            Language("el", "Tiếng Hy Lạp", "Ελληνικά", "🇬🇷"),
            
            // Trung Đông & Châu Phi
            Language("ar", "Tiếng Ả Rập", "العربية", "🇸🇦"),
            Language("he", "Tiếng Do Thái", "עברית", "🇮🇱"),
            Language("fa", "Tiếng Ba Tư", "فارسی", "🇮�"),
            Language("sw", "Tiếng Swahili", "Kiswahili", "🇰🇪"),
            Language("am", "Tiếng Amharic", "አማርኛ", "🇪🇹"),
            
            // Châu Mỹ Latin
            Language("es-MX", "Tiếng Tây Ban Nha (Mexico)", "Español de México", "🇲🇽"),
            
            // Khác
            Language("la", "Tiếng Latin", "Lingua Latina", "🏛️")
        )
        
        /**
         * Nhóm ngôn ngữ theo khu vực
         */
        val GROUPED_LANGUAGES = mapOf(
            "Châu Á" to SAMPLE_LANGUAGES.slice(0..10),
            "Châu Âu" to SAMPLE_LANGUAGES.slice(11..30),
            "Trung Đông & Châu Phi" to SAMPLE_LANGUAGES.slice(31..35),
            "Châu Mỹ Latin" to SAMPLE_LANGUAGES.slice(36..36),
            "Khác" to SAMPLE_LANGUAGES.slice(37..37)
        )
        
        /**
         * Lấy ngôn ngữ từ mã
         */
        fun getLanguageByCode(code: String): Language? {
            return GROUPED_LANGUAGES.values.flatten().find { it.code == code }
        }
    }
}
