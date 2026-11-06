package com.yixinjiang.nothing

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One indexed entry: a file or a directory, as stored in the SQLite index.
 */
data class FileEntry(
    val name: String,
    val path: String,
    val size: Long,
    val modified: Long,
    val isDirectory: Boolean
)

/**
 * Result ordering. The list order of the entries must match R.array.sort_modes.
 */
enum class SortMode {
    RELEVANCE,
    NAME,
    SIZE,
    MODIFIED
}

/**
 * Display helpers shared by the adapter and the status line.
 */
object FileFormatting {

    private const val ELLIPSIS = "…"

    private val UNITS = arrayOf("KB", "MB", "GB", "TB")

    fun humanSize(bytes: Long): String {
        if (bytes < 1024L) {
            return "$bytes B"
        }
        var value = bytes.toDouble() / 1024.0
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < UNITS.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return String.format(Locale.US, "%.1f %s", value, UNITS[unitIndex])
    }

    fun formatTime(millis: Long): String {
        if (millis <= 0L) {
            return "-"
        }
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
    }

    fun middleEllipsis(text: String, maxLength: Int): String {
        if (maxLength <= 3 || text.length <= maxLength) {
            return text
        }
        val head = (maxLength - 1) / 2
        val tail = maxLength - 1 - head
        return text.take(head) + ELLIPSIS + text.takeLast(tail)
    }
}
