package com.thekoil.koilcloud.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.thekoil.koilcloud.R
import com.thekoil.koilcloud.api.ApiClient
import com.thekoil.koilcloud.model.FileItem
import com.thekoil.koilcloud.util.formatBytes
import com.thekoil.koilcloud.util.getFileIcon

class FileAdapter(
    private val onPreview: (FileItem) -> Unit,
    private val onDownload: (FileItem) -> Unit,
    private val onLongClick: (FileItem) -> Unit,
) : RecyclerView.Adapter<FileAdapter.VH>() {

    private val items = mutableListOf<FileItem>()
    private var cutItemIds: Set<String> = emptySet()
    private var selectedIds: Set<String> = emptySet()

    fun submit(list: List<FileItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun setCutItems(ids: Set<String>) {
        val old = cutItemIds
        cutItemIds = ids
        items.forEachIndexed { index, item ->
            if (old.contains(item.id) || ids.contains(item.id)) {
                notifyItemChanged(index, "cut")
            }
        }
    }

    fun setSelectedIds(ids: Set<String>) {
        selectedIds = ids
        notifyDataSetChanged()
    }

    fun isSelectionMode(): Boolean = selectedIds.isNotEmpty()

    fun getCurrentItems(): List<FileItem> = items.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        when {
            payloads.contains("cut") -> holder.updateCutAlpha(items[position])
            payloads.contains("selection") -> holder.updateSelection(items[position])
            else -> super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun getItemCount() = items.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: TextView = itemView.findViewById(R.id.fileIcon)
        private val thumbnail: ImageView = itemView.findViewById(R.id.fileThumbnail)
        private val name: TextView = itemView.findViewById(R.id.fileName)
        private val meta: TextView = itemView.findViewById(R.id.fileMeta)
        private val download: TextView = itemView.findViewById(R.id.downloadButton)

        fun bind(item: FileItem) {
            name.text = item.name

            val sizePart = if (item.type == "folder") "" else formatBytes(item.size) + " · "
            val datePart = item.modifiedAt.ifBlank { "—" }
            meta.text = sizePart + datePart

            itemView.setOnClickListener {
                if (isSelectionMode()) onLongClick(item)
                else onPreview(item)
            }
            itemView.setOnLongClickListener {
                onLongClick(item)
                true
            }

            if (item.type == "folder") {
                download.visibility = View.GONE
            } else {
                download.visibility = View.VISIBLE
                download.setOnClickListener { onDownload(item) }
            }

            val ext = item.name.substringAfterLast('.', "").lowercase()
            val isImage = item.type != "folder" && ext in listOf(
                "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic"
            )

            if (isImage) {
                icon.visibility = View.GONE
                thumbnail.visibility = View.VISIBLE

                val imageUrl = ApiClient.downloadUrl(item.id)
                val token = ApiClient.accessToken()
                val loadModel: Any = if (token.isNullOrBlank()) {
                    imageUrl
                } else {
                    GlideUrl(
                        imageUrl,
                        LazyHeaders.Builder()
                            .addHeader("Authorization", "Bearer $token")
                            .build()
                    )
                }

                Glide.with(itemView.context)
                    .load(loadModel)
                    .placeholder(R.drawable.bg_icon)
                    .error(R.drawable.bg_icon)
                    .centerCrop()
                    .into(thumbnail)
            } else {
                thumbnail.visibility = View.GONE
                icon.visibility = View.VISIBLE
                icon.text = getFileIcon(item.name, item.type)
            }

            updateCutAlpha(item)
            updateSelection(item)
        }

        fun updateCutAlpha(item: FileItem) {
            itemView.alpha = if (cutItemIds.contains(item.id)) 0.4f else 1.0f
        }

        fun updateSelection(item: FileItem) {
            itemView.setBackgroundResource(
                if (selectedIds.contains(item.id)) R.drawable.bg_card_selected else R.drawable.bg_card
            )
        }
    }
}