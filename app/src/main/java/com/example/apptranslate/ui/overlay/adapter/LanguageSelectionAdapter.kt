package com.example.apptranslate.ui.overlay.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.apptranslate.databinding.ItemLanguageSelectionBinding
import com.example.apptranslate.model.Language

/**
 * Adapter cho danh sách chọn ngôn ngữ
 */
class LanguageSelectionAdapter(
    private val onLanguageClick: (Language) -> Unit
) : ListAdapter<Language, LanguageSelectionAdapter.LanguageViewHolder>(DiffCallback()) {
    
    private var selectedLanguage: Language? = null
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LanguageViewHolder {
        val binding = ItemLanguageSelectionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return LanguageViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: LanguageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    inner class LanguageViewHolder(
        private val binding: ItemLanguageSelectionBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(language: Language) {
            binding.apply {
                // Set language info
                textViewLanguageName.text = language.name
                textViewLanguageCode.text = language.code.uppercase()
                textViewFlag.text = language.flag
                
                // Set selection state
                val isSelected = language == selectedLanguage
                root.isSelected = isSelected
                
                // Update UI based on selection
                if (isSelected) {
                    cardView.strokeColor = root.context.getColor(com.example.apptranslate.R.color.primary)
                    cardView.strokeWidth = 2
                } else {
                    cardView.strokeColor = root.context.getColor(com.example.apptranslate.R.color.outline_variant)
                    cardView.strokeWidth = 1
                }
                
                // Click listener
                root.setOnClickListener {
                    val previousSelected = selectedLanguage
                    selectedLanguage = language
                    
                    // Notify changes for previous and current selection
                    previousSelected?.let { prev ->
                        val prevIndex = currentList.indexOf(prev)
                        if (prevIndex != -1) {
                            notifyItemChanged(prevIndex)
                        }
                    }
                    
                    val currentIndex = currentList.indexOf(language)
                    if (currentIndex != -1) {
                        notifyItemChanged(currentIndex)
                    }
                    
                    onLanguageClick(language)
                }
            }
        }
    }
    
    private class DiffCallback : DiffUtil.ItemCallback<Language>() {
        override fun areItemsTheSame(oldItem: Language, newItem: Language): Boolean {
            return oldItem.code == newItem.code
        }
        
        override fun areContentsTheSame(oldItem: Language, newItem: Language): Boolean {
            return oldItem == newItem
        }
    }
}
