package com.example.apptranslate.ui.overlay.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.apptranslate.databinding.ItemControlPanelBinding
import com.example.apptranslate.ui.overlay.model.ControlPanelItem

/**
 * Adapter cho RecyclerView trong control panel
 */
class ControlPanelAdapter(
    private val onItemClick: (ControlPanelItem) -> Unit
) : ListAdapter<ControlPanelItem, ControlPanelAdapter.ControlViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ControlViewHolder {
        val binding = ItemControlPanelBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ControlViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ControlViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ControlViewHolder(
        private val binding: ItemControlPanelBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ControlPanelItem) {
            binding.apply {
                // Set icon và title
                imageViewIcon.setImageResource(item.iconRes)
                textViewTitle.text = item.title

                // Thiết lập style theo type
                when (item.type) {
                    ControlPanelItem.Type.CONTROL -> {
                        // Style cho control buttons (Home, Move)
                        cardView.strokeWidth = 2
                        cardView.strokeColor = binding.root.context.getColor(com.example.apptranslate.R.color.primary)
                    }
                    ControlPanelItem.Type.FUNCTION -> {
                        // Style cho function buttons
                        cardView.strokeWidth = 0
                    }
                }

                // Set enabled state
                root.isEnabled = item.isEnabled
                root.alpha = if (item.isEnabled) 1.0f else 0.5f

                // Click listener
                root.setOnClickListener {
                    if (item.isEnabled) {
                        onItemClick(item)
                    }
                }
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<ControlPanelItem>() {
        override fun areItemsTheSame(oldItem: ControlPanelItem, newItem: ControlPanelItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ControlPanelItem, newItem: ControlPanelItem): Boolean {
            return oldItem == newItem
        }
    }
}