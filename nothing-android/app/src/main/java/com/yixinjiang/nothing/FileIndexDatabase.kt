package com.yixinjiang.nothing

import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale

/**
 * SQLite index of the scanned file system. One row per absolute path.
 *
 * A scan writes every row with the generation id of that scan; when the scan
 * finishes, rows carrying an older generation are deleted, so a re-scan both
 * updates changed entries (INSERT OR REPLACE on the unique path) and drops the
 * entries that disappeared from disk.
 */
class FileIndexDatabase(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_TABLE)
        db.execSQL(CREATE_NAME_INDEX)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP INDEX IF EXISTS $NAME_INDEX")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }

    fun beginGeneration(): Long = System.currentTimeMillis()

    fun insertBatch(entries: List<FileEntry>, generation: Long) {
        if (entries.isEmpty()) {
            return
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val statement = db.compileStatement(INSERT_ENTRY)
            try {
                for (entry in entries) {
                    statement.clearBindings()
                    statement.bindString(1, entry.name)
                    statement.bindString(2, entry.path)
                    statement.bindLong(3, entry.size)
                    statement.bindLong(4, entry.modified)
                    statement.bindLong(5, if (entry.isDirectory) 1L else 0L)
                    statement.bindLong(6, generation)
                    statement.executeInsert()
                }
            } finally {
                statement.close()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun finishGeneration(generation: Long): Int =
        writableDatabase.delete(TABLE_NAME, "$COL_GENERATION <> ?", arrayOf(generation.toString()))

    fun countFiles(): Long =
        DatabaseUtils.longForQuery(readableDatabase, "SELECT COUNT(*) FROM $TABLE_NAME", null)

    /**
     * Case-insensitive name search. A typed '*' is treated as the wildcard '%';
     * a typed '%', '_' or '\' is escaped so it stays literal.
     */
    fun search(term: String, sortMode: SortMode, limit: Int): List<FileEntry> {
        val normalized = term.trim().lowercase(Locale.ROOT)
        val sql: String
        val args: Array<String>?
        if (normalized.isEmpty()) {
            sql = "SELECT $COLUMNS FROM $TABLE_NAME ORDER BY ${orderClause(sortMode, withRelevance = false)} LIMIT $limit"
            args = null
        } else {
            val escaped = escapeLike(normalized)
            val containsArg = "%$escaped%"
            val prefixArg = "$escaped%"
            val order = orderClause(sortMode, withRelevance = true)
            sql = "SELECT $COLUMNS FROM $TABLE_NAME" +
                " WHERE lower($COL_NAME) LIKE ? ESCAPE '\\'" +
                " ORDER BY $order LIMIT $limit"
            args = if (sortMode == SortMode.RELEVANCE) {
                arrayOf(containsArg, normalized, prefixArg)
            } else {
                arrayOf(containsArg)
            }
        }
        val entries = ArrayList<FileEntry>()
        readableDatabase.rawQuery(sql, args).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow(COL_NAME)
            val pathIndex = cursor.getColumnIndexOrThrow(COL_PATH)
            val sizeIndex = cursor.getColumnIndexOrThrow(COL_SIZE)
            val modifiedIndex = cursor.getColumnIndexOrThrow(COL_MODIFIED)
            val directoryIndex = cursor.getColumnIndexOrThrow(COL_IS_DIR)
            while (cursor.moveToNext()) {
                entries.add(
                    FileEntry(
                        name = cursor.getString(nameIndex),
                        path = cursor.getString(pathIndex),
                        size = cursor.getLong(sizeIndex),
                        modified = cursor.getLong(modifiedIndex),
                        isDirectory = cursor.getInt(directoryIndex) == 1
                    )
                )
            }
        }
        return entries
    }

    private fun orderClause(sortMode: SortMode, withRelevance: Boolean): String = when (sortMode) {
        SortMode.RELEVANCE -> if (withRelevance) {
            "CASE WHEN lower($COL_NAME) = ? THEN 0" +
                " WHEN lower($COL_NAME) LIKE ? ESCAPE '\\' THEN 1" +
                " ELSE 2 END," +
                " length($COL_PATH) ASC, $COL_NAME COLLATE NOCASE ASC"
        } else {
            "$COL_MODIFIED DESC, length($COL_PATH) ASC, $COL_NAME COLLATE NOCASE ASC"
        }

        SortMode.NAME -> "$COL_NAME COLLATE NOCASE ASC, length($COL_PATH) ASC"
        SortMode.SIZE -> "$COL_SIZE DESC, $COL_NAME COLLATE NOCASE ASC"
        SortMode.MODIFIED -> "$COL_MODIFIED DESC, $COL_NAME COLLATE NOCASE ASC"
    }

    private fun escapeLike(value: String): String {
        val builder = StringBuilder(value.length + 8)
        for (character in value) {
            when (character) {
                '\\', '%', '_' -> builder.append('\\').append(character)
                '*' -> builder.append('%')
                else -> builder.append(character)
            }
        }
        return builder.toString()
    }

    companion object {
        /** Hard cap on the number of rows a single search returns. */
        const val SEARCH_LIMIT = 300

        private const val DATABASE_NAME = "nothing_index.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_NAME = "files"
        private const val NAME_INDEX = "idx_files_name_lower"

        private const val COL_NAME = "name"
        private const val COL_PATH = "path"
        private const val COL_SIZE = "size"
        private const val COL_MODIFIED = "modified"
        private const val COL_IS_DIR = "is_dir"
        private const val COL_GENERATION = "gen"

        private const val COLUMNS =
            "$COL_NAME, $COL_PATH, $COL_SIZE, $COL_MODIFIED, $COL_IS_DIR"

        private val CREATE_TABLE = """
            CREATE TABLE $TABLE_NAME (
                $COL_NAME TEXT NOT NULL,
                $COL_PATH TEXT NOT NULL UNIQUE,
                $COL_SIZE INTEGER NOT NULL DEFAULT 0,
                $COL_MODIFIED INTEGER NOT NULL DEFAULT 0,
                $COL_IS_DIR INTEGER NOT NULL DEFAULT 0,
                $COL_GENERATION INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent()

        private val CREATE_NAME_INDEX =
            "CREATE INDEX $NAME_INDEX ON $TABLE_NAME(lower($COL_NAME))"

        private val INSERT_ENTRY = "INSERT OR REPLACE INTO $TABLE_NAME" +
            " ($COL_NAME, $COL_PATH, $COL_SIZE, $COL_MODIFIED, $COL_IS_DIR, $COL_GENERATION)" +
            " VALUES (?, ?, ?, ?, ?, ?)"
    }
}
