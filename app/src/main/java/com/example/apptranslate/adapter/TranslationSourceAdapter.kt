package com.example.apptranslate.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import com.example.apptranslate.R
import com.example.apptranslate.viewmodel.TranslationSource

/**
 * Custom Adapter cho Spinner nguồn dịch
 * Cho phép hiển thị icon cài đặt riêng cho mục "Dịch AI"
 */
class TranslationSourceAdapter(
    private val context: Context,
    private val sources: List<TranslationSource>,
    private val onSettingsClick: () -> Unit
) : BaseAdapter() {

    private val inflater: LayoutInflater = LayoutInflater.from(context)

    override fun getCount(): Int = sources.size

    override fun getItem(position: Int): TranslationSource = sources[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        return createView(position, convertView, parent, false)
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup?): View {
        return createView(position, convertView, parent, true)
    }

    private fun createView(position: Int, convertView: View?, parent: ViewGroup?, isDropDown: Boolean): View {
        val view = convertView ?: inflater.inflate(R.layout.item_translation_source, parent, false)
        
        val tvSourceName = view.findViewById<TextView>(R.id.tv_source_name)
        val ivSettings = view.findViewById<ImageView>(R.id.iv_settings)
        
        val source = sources[position]
        
        // Thiết lập tên nguồn dịch
        tvSourceName.text = when (source) {
            TranslationSource.AI -> context.getString(R.string.translation_source_ai)
            TranslationSource.GOOGLE -> context.getString(R.string.translation_source_google)
            TranslationSource.OFFLINE -> context.getString(R.string.translation_source_offline)
        }
        
        // Chỉ hiển thị icon cài đặt cho AI trong dropdown và chỉ khi đó là dropdown
        if (isDropDown && source == TranslationSource.AI) {
            ivSettings.visibility = View.VISIBLE
            ivSettings.setOnClickListener { 
                onSettingsClick()
            }
        } else {
            ivSettings.visibility = View.GONE
            ivSettings.setOnClickListener(null)
        }
        
        return view
    }
}
