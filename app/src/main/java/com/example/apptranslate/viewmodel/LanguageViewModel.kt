    package com.example.apptranslate.viewmodel

    import androidx.lifecycle.LiveData
    import androidx.lifecycle.MutableLiveData
    import androidx.lifecycle.ViewModel
    import androidx.lifecycle.ViewModelProvider
    import com.example.apptranslate.model.Language

    /**
    * Enum định nghĩa các nguồn dịch khả dụng
    */
    enum class TranslationSource {
        AI, GOOGLE, OFFLINE
    }

    /**
    * Enum định nghĩa các chế độ dịch
    */
    enum class TranslationMode {
        SIMPLE, ADVANCED
    }

    /**
    * ViewModel để quản lý trạng thái và dữ liệu cho tính năng chọn ngôn ngữ
    */
    class LanguageViewModel : ViewModel() {

        // Danh sách tất cả ngôn ngữ được hỗ trợ (lấy danh sách phẳng từ GROUPED_LANGUAGES)
        private val allLanguages = Language.GROUPED_LANGUAGES.values.flatten().distinct()

        // Ngôn ngữ nguồn hiện tại
        private val _sourceLanguage = MutableLiveData<Language>()
        val sourceLanguage: LiveData<Language> = _sourceLanguage

        // Ngôn ngữ đích hiện tại
        private val _targetLanguage = MutableLiveData<Language>()
        val targetLanguage: LiveData<Language> = _targetLanguage

        // Danh sách ngôn ngữ nguồn gần đây
        private val _recentSourceLanguages = MutableLiveData<List<Language>>()
        val recentSourceLanguages: LiveData<List<Language>> = _recentSourceLanguages

        // Danh sách ngôn ngữ đích gần đây
        private val _recentTargetLanguages = MutableLiveData<List<Language>>()
        val recentTargetLanguages: LiveData<List<Language>> = _recentTargetLanguages

        // Nguồn dịch hiện tại (AI, Google, Offline)
        private val _translationSource = MutableLiveData<TranslationSource>()
        val translationSource: LiveData<TranslationSource> = _translationSource

        // Chế độ dịch hiện tại (Đơn giản, Nâng cao)
        private val _translationMode = MutableLiveData<TranslationMode>()
        val translationMode: LiveData<TranslationMode> = _translationMode

        init {
            // Khởi tạo giá trị mặc định
            _sourceLanguage.value = allLanguages.find { it.code == "vi" } ?: allLanguages.first()
            _targetLanguage.value = allLanguages.find { it.code == "en" } ?: allLanguages.getOrNull(1) ?: allLanguages.first()

            // Khởi tạo danh sách ngôn ngữ gần đây
            _recentSourceLanguages.value = listOf(
                allLanguages.find { it.code == "vi" }!!,
                allLanguages.find { it.code == "en" }!!,
                allLanguages.find { it.code == "fr" }!!
            )
            _recentTargetLanguages.value = listOf(
                allLanguages.find { it.code == "en" }!!,
                allLanguages.find { it.code == "fr" }!!,
                allLanguages.find { it.code == "ja" }!!
            )

            // Khởi tạo giá trị mặc định cho nguồn dịch và chế độ dịch
            _translationSource.value = TranslationSource.AI
            _translationMode.value = TranslationMode.ADVANCED
        }

        /**
        * Lấy danh sách tất cả ngôn ngữ được hỗ trợ
        */
        fun getAllLanguages(): List<Language> = allLanguages

        /**
        * Cập nhật ngôn ngữ nguồn
        * @param language Ngôn ngữ được chọn làm nguồn
        */
        fun setSourceLanguage(language: Language) {
            // Nếu ngôn ngữ này đang là ngôn ngữ đích, hoán đổi
            if (_targetLanguage.value?.code == language.code) {
                _targetLanguage.value = _sourceLanguage.value
            }

            _sourceLanguage.value = language

            // Cập nhật danh sách ngôn ngữ gần đây
            updateRecentLanguages(language, isSource = true)
        }

        /**
        * Cập nhật ngôn ngữ đích
        * @param language Ngôn ngữ được chọn làm đích
        */
        fun setTargetLanguage(language: Language) {
            // Nếu ngôn ngữ này đang là ngôn ngữ nguồn, hoán đổi
            if (_sourceLanguage.value?.code == language.code) {
                _sourceLanguage.value = _targetLanguage.value
            }

            _targetLanguage.value = language

            // Cập nhật danh sách ngôn ngữ gần đây
            updateRecentLanguages(language, isSource = false)
        }

        /**
        * Hoán đổi ngôn ngữ nguồn và đích
        */
        fun swapLanguages() {
            // Lấy giá trị hiện tại ra các biến tạm
            val currentSource = _sourceLanguage.value
            val currentTarget = _targetLanguage.value

            // Chỉ thực hiện hoán đổi nếu cả hai giá trị đều không phải là null
            if (currentSource != null && currentTarget != null) {
                _sourceLanguage.value = currentTarget
                _targetLanguage.value = currentSource
            }
        }

        /**
        * Cập nhật danh sách ngôn ngữ gần đây
        */
        private fun updateRecentLanguages(language: Language, isSource: Boolean) {
            if (isSource) {
                val currentList = _recentSourceLanguages.value?.toMutableList() ?: mutableListOf()
                // Xóa nếu đã tồn tại trong danh sách
                currentList.removeIf { it.code == language.code }
                // Thêm vào đầu danh sách
                currentList.add(0, language)
                // Giới hạn danh sách
                _recentSourceLanguages.value = currentList.take(3)
            } else {
                val currentList = _recentTargetLanguages.value?.toMutableList() ?: mutableListOf()
                currentList.removeIf { it.code == language.code }
                currentList.add(0, language)
                _recentTargetLanguages.value = currentList.take(3)
            }
        }

        /**
        * Tìm kiếm ngôn ngữ theo từ khóa
        * Hỗ trợ tìm kiếm theo tên, tên bản địa, mã ngôn ngữ và emoji flag
        */
        fun searchLanguages(query: String): List<Language> {
            if (query.isEmpty()) return emptyList()

            val lowercaseQuery = query.lowercase().trim()
            
            // Tìm trong danh sách tất cả ngôn ngữ
            return allLanguages.filter { language ->
                language.name.lowercase().contains(lowercaseQuery) ||
                language.nativeName.lowercase().contains(lowercaseQuery) ||
                language.code.lowercase().contains(lowercaseQuery) ||
                language.flag.contains(lowercaseQuery)
            }.sortedWith(compareBy(
                // Sắp xếp kết quả để ưu tiên trùng khớp chính xác hơn
                { !it.code.equals(lowercaseQuery, ignoreCase = true) }, // Ưu tiên trùng khớp mã trước
                { !it.name.lowercase().startsWith(lowercaseQuery) },    // Sau đó là tên bắt đầu bằng từ khóa
                { !it.nativeName.lowercase().startsWith(lowercaseQuery) }, // Rồi đến tên bản địa
                { it.name }  // Cuối cùng sắp xếp theo tên
            ))
        }

        /**
        * Cập nhật nguồn dịch
        */
        fun setTranslationSource(source: TranslationSource) {
            _translationSource.value = source
        }

        /**
        * Cập nhật chế độ dịch
        */
        fun setTranslationMode(mode: TranslationMode) {
            _translationMode.value = mode
        }
    }

    /**
    * Factory để tạo LanguageViewModel với constructor phù hợp
    */
    class LanguageViewModelFactory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(LanguageViewModel::class.java)) {
                return LanguageViewModel() as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
