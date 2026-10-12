package com.seagull.htv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.seagull.htv.R

/** 热词 / 搜索历史 的标签网格 */
class WordAdapter(private val onClick: (String) -> Unit) : RecyclerView.Adapter<WordAdapter.VH>() {

    private val items = ArrayList<String>()

    fun submit(list: List<String>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val word: TextView = v.findViewById(R.id.word)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_word, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val w = items[position]
        h.word.text = w
        h.word.setOnClickListener { onClick(w) }
    }
}
