package com.seagull.htv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.seagull.htv.R
import com.seagull.htv.data.Video

class VideoAdapter(
    private val onClick: (Video) -> Unit,
    private val onLongClick: (Video) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VH>() {

    private val items = ArrayList<Video>()

    fun add(list: List<Video>, append: Boolean) {
        if (!append) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
            return
        }
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    fun size() = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)
        val dur: TextView = v.findViewById(R.id.dur)
        val hd: TextView = v.findViewById(R.id.hd)
        val meta: TextView = v.findViewById(R.id.meta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val v = items[position]
        h.title.text = v.title
        h.dur.text = v.duration
        h.dur.visibility = if (v.duration.isBlank()) View.GONE else View.VISIBLE
        h.hd.visibility = if (v.hd) View.VISIBLE else View.GONE
        h.meta.text = v.author
        h.meta.visibility = if (v.author.isBlank()) View.GONE else View.VISIBLE

        // Coil：缩略图无需 Referer，实测直连 200 image/webp
        h.thumb.load(v.thumb) {
            crossfade(true)
            placeholder(R.drawable.ph_thumb)
        }

        h.itemView.setOnClickListener { onClick(v) }
        h.itemView.setOnLongClickListener { onLongClick(v); true }
    }
}
