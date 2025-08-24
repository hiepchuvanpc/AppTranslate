package com.example.apptranslate.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.apptranslate.data.GeminiApiKey
import com.example.apptranslate.databinding.ItemApiKeyBinding

class ApiKeyAdapter(
    private var keys: List<GeminiApiKey>,
    private val onDeleteClick: (String) -> Unit
) : RecyclerView.Adapter<ApiKeyAdapter.ApiKeyViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ApiKeyViewHolder {
        val binding = ItemApiKeyBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ApiKeyViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ApiKeyViewHolder, position: Int) {
        holder.bind(keys[position])
    }

    override fun getItemCount(): Int = keys.size

    fun updateKeys(newKeys: List<GeminiApiKey>) {
        this.keys = newKeys
        notifyDataSetChanged()
    }

    inner class ApiKeyViewHolder(private val binding: ItemApiKeyBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(apiKey: GeminiApiKey) {
            val maskedKey = if (apiKey.key.length > 8) {
                "${apiKey.key.take(4)}...${apiKey.key.takeLast(4)}"
            } else {
                apiKey.key
            }
            binding.tvApiKeyMasked.text = maskedKey
            binding.tvKeyStats.text = "Hôm nay: ${apiKey.requestsToday}/${GeminiApiKey.MAX_REQUESTS_PER_DAY} | Phút này: ${apiKey.requestsThisMinute}/${GeminiApiKey.MAX_REQUESTS_PER_MINUTE}"
            binding.btnDeleteKey.setOnClickListener {
                onDeleteClick(apiKey.key)
            }
        }
    }
}