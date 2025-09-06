package com.example.apptranslate.ui.overlay.adapter

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.apptranslate.R
import com.example.apptranslate.ui.overlay.model.FunctionItem

/**
 * Adapter để hiển thị các nút chức năng trong panel điều khiển
 */
class FunctionAdapter(
    private val onItemClick: (FunctionItem) -> Unit
) : ListAdapter<FunctionItem, FunctionAdapter.FunctionViewHolder>(FunctionDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FunctionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_function_button, parent, false)
        return FunctionViewHolder(view, onItemClick)
    }

    override fun onBindViewHolder(holder: FunctionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /**
     * ViewHolder cho mỗi nút chức năng
     */
    class FunctionViewHolder(
        itemView: View,
        private val onItemClick: (FunctionItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val ivIcon: ImageView = itemView.findViewById(R.id.ivFunctionIcon)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvFunctionTitle)

        fun bind(item: FunctionItem) {
            
            ivIcon.setImageResource(item.iconRes)
            tvTitle.text = item.title

            // Thiết lập trạng thái có thể nhấp
            itemView.isEnabled = item.isClickable
            itemView.alpha = if (item.isClickable) 1.0f else 0.5f

            // Thiết lập sự kiện click
            if (item.isClickable) {
                itemView.setOnClickListener {
                    
                    onItemClick(item)
                }
            } else {
                itemView.setOnClickListener(null)
            }
        }
    }
}

/**
 * DiffUtil callback cho việc so sánh các FunctionItem
 */
class FunctionDiffCallback : DiffUtil.ItemCallback<FunctionItem>() {
    override fun areItemsTheSame(oldItem: FunctionItem, newItem: FunctionItem): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: FunctionItem, newItem: FunctionItem): Boolean {
        return oldItem == newItem
    }
}
