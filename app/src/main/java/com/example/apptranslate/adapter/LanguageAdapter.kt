// File: app/src/main/java/com/example/apptranslate/adapter/LanguageAdapter.kt

package com.example.apptranslate.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.apptranslate.R
import com.example.apptranslate.databinding.ItemLanguageBinding
import com.example.apptranslate.databinding.ItemLanguageHeaderBinding
import com.example.apptranslate.model.Language

// ✨ Lớp đại diện cho các item trong RecyclerView, có thể là Ngôn ngữ hoặc Tiêu đề ✨
sealed class LanguageListItem {
    data class LanguageItem(val language: Language) : LanguageListItem()
    data class HeaderItem(val title: String) : LanguageListItem()
}

class LanguageAdapter(
    private val onLanguageClick: (Language) -> Unit
) : ListAdapter<LanguageListItem, RecyclerView.ViewHolder>(LanguageListItemDiffCallback()) {

    // ✨ Định nghĩa các loại View ✨
    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_LANGUAGE = 1

        // ✨ HÀM HELPER: Tạo danh sách hoàn chỉnh để hiển thị ✨
        fun createFullList(
            recent: List<Language>,
            all: List<Language>,
            context: android.content.Context
        ): List<LanguageListItem> {
            val list = mutableListOf<LanguageListItem>()
            if (recent.isNotEmpty()) {
                list.add(LanguageListItem.HeaderItem(context.getString(R.string.recent_languages)))
                list.addAll(recent.map { LanguageListItem.LanguageItem(it) })
            }
            list.add(LanguageListItem.HeaderItem(context.getString(R.string.all_languages)))
            // Loại bỏ các ngôn ngữ đã có trong 'gần đây' khỏi danh sách 'tất cả'
            val allWithoutRecents = all.filterNot { lang -> recent.any { it.code == lang.code } }
            list.addAll(allWithoutRecents.map { LanguageListItem.LanguageItem(it) })
            return list
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is LanguageListItem.HeaderItem -> VIEW_TYPE_HEADER
            is LanguageListItem.LanguageItem -> VIEW_TYPE_LANGUAGE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val binding = ItemLanguageHeaderBinding.inflate(inflater, parent, false)
                HeaderViewHolder(binding)
            }
            else -> {
                val binding = ItemLanguageBinding.inflate(inflater, parent, false)
                LanguageViewHolder(binding, onLanguageClick)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is LanguageListItem.HeaderItem -> (holder as HeaderViewHolder).bind(item)
            is LanguageListItem.LanguageItem -> (holder as LanguageViewHolder).bind(item)
        }
    }

    class LanguageViewHolder(
        private val binding: ItemLanguageBinding,
        private val onLanguageClick: (Language) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: LanguageListItem.LanguageItem) {
            binding.tvLanguageName.text = item.language.name
            binding.tvLanguageNativeName.text = item.language.nativeName
            binding.root.setOnClickListener {
                onLanguageClick(item.language)
            }
        }
    }

    class HeaderViewHolder(private val binding: ItemLanguageHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: LanguageListItem.HeaderItem) {
            binding.tvHeader.text = item.title
        }
    }

    class LanguageListItemDiffCallback : DiffUtil.ItemCallback<LanguageListItem>() {
        override fun areItemsTheSame(oldItem: LanguageListItem, newItem: LanguageListItem): Boolean {
            return if (oldItem is LanguageListItem.LanguageItem && newItem is LanguageListItem.LanguageItem) {
                oldItem.language.code == newItem.language.code
            } else if (oldItem is LanguageListItem.HeaderItem && newItem is LanguageListItem.HeaderItem) {
                oldItem.title == newItem.title
            } else false
        }

        override fun areContentsTheSame(oldItem: LanguageListItem, newItem: LanguageListItem): Boolean {
            return oldItem == newItem
        }
    }
}