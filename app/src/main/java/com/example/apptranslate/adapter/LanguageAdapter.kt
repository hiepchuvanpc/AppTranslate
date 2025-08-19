package com.example.apptranslate.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.apptranslate.R
import com.example.apptranslate.model.Language

/**
 * Adapter cho RecyclerView hiển thị danh sách ngôn ngữ
 * Hỗ trợ nhiều loại view (header và item ngôn ngữ)
 */
class LanguageAdapter(
    private val onLanguageClick: (Language) -> Unit
) : ListAdapter<LanguageListItem, RecyclerView.ViewHolder>(LanguageDiffCallback()) {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
        
        /**
         * Tạo danh sách hiển thị đầy đủ với header và items
         * Hỗ trợ nhóm theo khu vực
         */
        fun createFullList(
            recentLanguages: List<Language>,
            allLanguages: List<Language>,
            groupByRegion: Boolean = false
        ): List<LanguageListItem> {
            val items = mutableListOf<LanguageListItem>()
            
            // Chỉ thêm phần "Ngôn ngữ gần đây" nếu có dữ liệu
            if (recentLanguages.isNotEmpty()) {
                items.add(LanguageListItem.Header(R.string.recent_languages))
                recentLanguages.forEach { language ->
                    items.add(LanguageListItem.LanguageItem(language))
                }
            }
            
            if (groupByRegion) {
                // Nhóm ngôn ngữ theo khu vực
                Language.GROUPED_LANGUAGES.forEach { (region, languages) ->
                    items.add(LanguageListItem.Header(0, region))
                    languages.forEach { language ->
                        items.add(LanguageListItem.LanguageItem(language))
                    }
                }
            } else {
                // Thêm phần "Tất cả ngôn ngữ"
                items.add(LanguageListItem.Header(R.string.all_languages))
                allLanguages.forEach { language ->
                    items.add(LanguageListItem.LanguageItem(language))
                }
            }
            
            return items
        }
        
        /**
         * Tạo danh sách kết quả tìm kiếm (chỉ có items, không có header)
         */
        fun createSearchResultsList(
            searchResults: List<Language>
        ): List<LanguageListItem> {
            return searchResults.map { LanguageListItem.LanguageItem(it) }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is LanguageListItem.Header -> TYPE_HEADER
            is LanguageListItem.LanguageItem -> TYPE_ITEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_HEADER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_language_header, parent, false)
                HeaderViewHolder(view)
            }
            TYPE_ITEM -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_language, parent, false)
                LanguageViewHolder(view, onLanguageClick)
            }
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is LanguageListItem.Header -> (holder as HeaderViewHolder).bind(item.titleResId, item.customTitle)
            is LanguageListItem.LanguageItem -> (holder as LanguageViewHolder).bind(item.language)
        }
    }

    /**
     * ViewHolder cho header
     */
    class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val headerText: TextView = itemView.findViewById(R.id.tv_header)

        fun bind(titleResId: Int, customTitle: String? = null) {
            if (customTitle != null) {
                headerText.text = customTitle
            } else {
                headerText.setText(titleResId)
            }
        }
    }

    /**
     * ViewHolder cho item ngôn ngữ
     */
    class LanguageViewHolder(
        itemView: View,
        private val onLanguageClick: (Language) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val languageName: TextView = itemView.findViewById(R.id.tv_language_name)
        private val languageNativeName: TextView = itemView.findViewById(R.id.tv_language_native_name)
        private var currentLanguage: Language? = null

        init {
            itemView.setOnClickListener {
                currentLanguage?.let { onLanguageClick(it) }
            }
        }

        fun bind(language: Language) {
            currentLanguage = language
            
            // Format hiển thị: Tên tiếng Việt (có flag nếu có)
            languageName.text = if (language.flag != "🌐") {
                "${language.flag} ${language.name}"
            } else {
                language.name
            }
            
            // Format hiển thị: Tên gốc (mã)
            languageNativeName.text = language.nativeNameWithCode
        }
    }

    /**
     * DiffCallback để tối ưu cập nhật RecyclerView
     */
    class LanguageDiffCallback : DiffUtil.ItemCallback<LanguageListItem>() {
        override fun areItemsTheSame(oldItem: LanguageListItem, newItem: LanguageListItem): Boolean {
            return when {
                oldItem is LanguageListItem.Header && newItem is LanguageListItem.Header ->
                    oldItem.titleResId == newItem.titleResId
                oldItem is LanguageListItem.LanguageItem && newItem is LanguageListItem.LanguageItem ->
                    oldItem.language.code == newItem.language.code
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: LanguageListItem, newItem: LanguageListItem): Boolean {
            return oldItem == newItem
        }
    }
}

/**
 * Sealed class đại diện cho các loại item trong RecyclerView
 */
sealed class LanguageListItem {
    data class Header(val titleResId: Int = 0, val customTitle: String? = null) : LanguageListItem()
    data class LanguageItem(val language: Language) : LanguageListItem()
}
