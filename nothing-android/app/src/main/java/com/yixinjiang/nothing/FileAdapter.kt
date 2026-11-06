package com.yixinjiang.nothing

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

private const val PATH_MAX_LENGTH = 56

private val FILE_DIFF = object : DiffUtil.ItemCallback<FileEntry>() {
    override fun areItemsTheSame(oldItem: FileEntry, newItem: FileEntry): Boolean =
        oldItem.path == newItem.path

    override fun areContentsTheSame(oldItem: FileEntry, newItem: FileEntry): Boolean =
        oldItem == newItem
}

/**
 * Result list adapter: name, size/time meta line and the middle-elided path.
 */
class FileAdapter(
    private val onOpen: (FileEntry) -> Unit,
    private val onMenu: (FileEntry) -> Unit
) : ListAdapter<FileEntry, FileAdapter.FileViewHolder>(FILE_DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view, onOpen, onMenu)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class FileViewHolder(
        itemView: View,
        private val onOpen: (FileEntry) -> Unit,
        private val onMenu: (FileEntry) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val nameView: TextView = itemView.findViewById(R.id.fileName)
        private val metaView: TextView = itemView.findViewById(R.id.fileMeta)
        private val pathView: TextView = itemView.findViewById(R.id.filePath)

        fun bind(entry: FileEntry) {
            val context = itemView.context
            val sizeLabel = if (entry.isDirectory) {
                context.getString(R.string.folder_label)
            } else {
                FileFormatting.humanSize(entry.size)
            }
            nameView.text = entry.name
            metaView.text = context.getString(
                R.string.item_meta,
                sizeLabel,
                FileFormatting.formatTime(entry.modified)
            )
            pathView.text = FileFormatting.middleEllipsis(entry.path, PATH_MAX_LENGTH)
            itemView.setOnClickListener { onOpen(entry) }
            itemView.setOnLongClickListener {
                onMenu(entry)
                true
            }
        }
    }
}
