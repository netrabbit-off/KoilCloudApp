package com.thekoil.koilcloud.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.thekoil.koilcloud.R
import com.thekoil.koilcloud.model.FileItem
import com.thekoil.koilcloud.util.formatBytes
import com.thekoil.koilcloud.util.getFileIcon

class FileAdapter(
    private val onPreview: (FileItem) -> Unit,
    private val onDownload: (FileItem) -> Unit,
) : RecyclerView.Adapter<FileAdapter.VH>() {

    private val items = mutableListOf<FileItem>()

    fun submit(list: List<FileItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.drawable.item_file, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: TextView = itemView.findViewById(R.id.fileIcon)
        private val name: TextView = itemView.findViewById(R.id.fileName)
        private val meta: TextView = itemView.findViewById(R.id.fileMeta)
        private val download: TextView = itemView.findViewById(R.id.downloadButton)

        fun bind(item: FileItem) {
            icon.text = getFileIcon(item.name, item.type)
            name.text = item.name

            val sizePart = if (item.type == "folder") "" else formatBytes(item.size) + " · "
            val datePart = item.modifiedAt.ifBlank { "—" }
            meta.text = sizePart + datePart

            itemView.setOnClickListener { if (item.type != "folder") onPreview(item) }

            if (item.type == "folder") {
                download.visibility = View.GONE
            } else {
                download.visibility = View.VISIBLE
                download.setOnClickListener { onDownload(item) }
            }
        }
    }
}