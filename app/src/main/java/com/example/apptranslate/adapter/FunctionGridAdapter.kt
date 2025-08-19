package com.example.apptranslate.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.apptranslate.databinding.ItemFunctionCardBinding
import com.example.apptranslate.model.FunctionItem

/**
 * Adapter cho RecyclerView hiển thị grid các chức năng
 */
class FunctionGridAdapter(
    private val onItemClick: (FunctionItem) -> Unit
) : ListAdapter<FunctionItem, FunctionGridAdapter.FunctionViewHolder>(FunctionDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FunctionViewHolder {
        val binding = ItemFunctionCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return FunctionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FunctionViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, onItemClick)
    }

    class FunctionViewHolder(
        private val binding: ItemFunctionCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FunctionItem, onItemClick: (FunctionItem) -> Unit) {
            binding.ivFunctionIcon.setImageResource(item.iconRes)
            binding.tvFunctionTitle.text = item.title
            binding.tvFunctionDescription.text = item.description

            binding.root.apply {
                alpha = if (item.isClickable) 1.0f else 0.6f
                isClickable = item.isClickable
                setOnClickListener {
                    if (item.isClickable) {
                        onItemClick(item)
                    }
                }
            }
        }
    }

    class FunctionDiffCallback : DiffUtil.ItemCallback<FunctionItem>() {
        override fun areItemsTheSame(oldItem: FunctionItem, newItem: FunctionItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: FunctionItem, newItem: FunctionItem): Boolean {
            return oldItem == newItem
        }
    }
}
