package com.yixinjiang.nothing

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Summary of one completed scan.
 */
data class IndexSummary(
    val scanned: Int,
    val removed: Int,
    val finishedAt: Long
)

/**
 * Recursive scanner. Runs entirely on Dispatchers.IO, writes the index in
 * batches and reports the running entry count on the main dispatcher.
 */
class FileIndexer(private val database: FileIndexDatabase) {

    suspend fun index(root: File, onProgress: suspend (Int) -> Unit): IndexSummary =
        withContext(Dispatchers.IO) {
            val generation = database.beginGeneration()
            val pending = ArrayList<FileEntry>(BATCH_SIZE)
            val queue = ArrayDeque<File>()
            queue.addLast(root)
            var scanned = 0
            var lastReported = 0

            while (queue.isNotEmpty()) {
                ensureActive()
                val directory = queue.removeLast()
                val children = directory.listFiles()
                if (children == null) {
                    continue
                }
                for (child in children) {
                    val name = child.name
                    val path = child.absolutePath
                    if (name == null || path == null) {
                        continue
                    }
                    val isDirectory = child.isDirectory
                    pending.add(
                        FileEntry(
                            name = name,
                            path = path,
                            size = if (isDirectory) 0L else child.length(),
                            modified = child.lastModified(),
                            isDirectory = isDirectory
                        )
                    )
                    if (isDirectory && !isSymbolicLink(child)) {
                        queue.addLast(child)
                    }
                    scanned++
                    if (pending.size >= BATCH_SIZE) {
                        database.insertBatch(pending, generation)
                        pending.clear()
                    }
                    if (scanned - lastReported >= PROGRESS_STEP) {
                        lastReported = scanned
                        withContext(Dispatchers.Main) { onProgress(scanned) }
                    }
                }
            }

            if (pending.isNotEmpty()) {
                database.insertBatch(pending, generation)
                pending.clear()
            }
            val removed = database.finishGeneration(generation)
            withContext(Dispatchers.Main) { onProgress(scanned) }
            IndexSummary(scanned = scanned, removed = removed, finishedAt = System.currentTimeMillis())
        }

    private fun isSymbolicLink(file: File): Boolean = try {
        Files.isSymbolicLink(file.toPath())
    } catch (error: Exception) {
        false
    }

    private companion object {
        const val BATCH_SIZE = 500
        const val PROGRESS_STEP = 200
    }
}
