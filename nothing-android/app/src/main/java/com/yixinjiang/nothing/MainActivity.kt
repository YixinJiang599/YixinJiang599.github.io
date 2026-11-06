package com.yixinjiang.nothing

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var searchInput: EditText
    private lateinit var refreshButton: Button
    private lateinit var sortSpinner: Spinner
    private lateinit var statusText: TextView
    private lateinit var resultsList: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var permissionCard: LinearLayout
    private lateinit var grantButton: Button

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adapter = FileAdapter(
        onOpen = { entry -> openEntry(entry) },
        onMenu = { entry -> showEntryMenu(entry) }
    )
    private val preferences: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private lateinit var database: FileIndexDatabase
    private lateinit var indexer: FileIndexer

    private var searchJob: Job? = null
    private var indexJob: Job? = null
    private var sortMode = SortMode.RELEVANCE
    private var lastQuery = ""
    private var hasStorageAccess = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        searchInput = findViewById(R.id.searchInput)
        refreshButton = findViewById(R.id.refreshButton)
        sortSpinner = findViewById(R.id.sortSpinner)
        statusText = findViewById(R.id.statusText)
        resultsList = findViewById(R.id.resultsList)
        emptyText = findViewById(R.id.emptyText)
        permissionCard = findViewById(R.id.permissionCard)
        grantButton = findViewById(R.id.grantButton)

        database = FileIndexDatabase(applicationContext)
        indexer = FileIndexer(database)

        resultsList.layoutManager = LinearLayoutManager(this)
        resultsList.adapter = adapter

        val sortLabels = resources.getStringArray(R.array.sort_modes).toList()
        sortSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            sortLabels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        sortSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val modes = SortMode.values()
                sortMode = if (position in modes.indices) modes[position] else SortMode.RELEVANCE
                if (hasStorageAccess) {
                    scheduleSearch(lastQuery)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        searchInput.doAfterTextChanged { editable ->
            val text = editable?.toString().orEmpty()
            lastQuery = text
            scheduleSearch(text)
        }

        refreshButton.setOnClickListener { startIndexing() }
        grantButton.setOnClickListener { requestStorageAccess() }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    override fun onDestroy() {
        scope.cancel()
        if (this::database.isInitialized) {
            database.close()
        }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_READ_STORAGE) {
            refreshPermissionState()
        }
    }

    private fun refreshPermissionState() {
        hasStorageAccess = checkStorageAccess()
        searchInput.isEnabled = hasStorageAccess
        refreshButton.isEnabled = hasStorageAccess
        sortSpinner.isEnabled = hasStorageAccess

        if (hasStorageAccess) {
            permissionCard.visibility = View.GONE
            resultsList.visibility = View.VISIBLE
            updateStatusLine()
            scheduleSearch(lastQuery)
        } else {
            permissionCard.visibility = View.VISIBLE
            resultsList.visibility = View.GONE
            emptyText.visibility = View.GONE
            adapter.submitList(emptyList())
            statusText.text = getString(R.string.status_no_permission)
        }
    }

    private fun updateStatusLine() {
        if (indexJob?.isActive == true) {
            return
        }
        scope.launch {
            val count = withContext(Dispatchers.IO) { database.countFiles() }
            val lastIndexed = preferences.getLong(KEY_LAST_INDEX_TIME, 0L)
            statusText.text = if (lastIndexed > 0L) {
                getString(R.string.status_idle, count, FileFormatting.formatTime(lastIndexed))
            } else {
                getString(R.string.status_never_indexed)
            }
            if (count == 0L && lastIndexed == 0L) {
                startIndexing()
            }
        }
    }

    private fun scheduleSearch(rawQuery: String) {
        searchJob?.cancel()
        if (!hasStorageAccess) {
            return
        }
        val query = rawQuery
        searchJob = scope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            performSearch(query)
        }
    }

    private suspend fun performSearch(rawQuery: String) {
        val mode = sortMode
        val entries = withContext(Dispatchers.IO) {
            database.search(rawQuery, mode, FileIndexDatabase.SEARCH_LIMIT)
        }
        adapter.submitList(entries)
        emptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        if (entries.isNotEmpty()) {
            resultsList.scrollToPosition(0)
        }
    }

    private fun startIndexing() {
        if (!hasStorageAccess) {
            Toast.makeText(this, R.string.permission_required_toast, Toast.LENGTH_SHORT).show()
            return
        }
        if (indexJob?.isActive == true) {
            Toast.makeText(this, R.string.indexing_running_toast, Toast.LENGTH_SHORT).show()
            return
        }
        indexJob = scope.launch {
            refreshButton.isEnabled = false
            statusText.text = getString(R.string.status_indexing, 0)
            try {
                val summary = indexer.index(currentRoot()) { scanned ->
                    statusText.text = getString(R.string.status_indexing, scanned)
                }
                preferences.edit().putLong(KEY_LAST_INDEX_TIME, summary.finishedAt).apply()
                val count = withContext(Dispatchers.IO) { database.countFiles() }
                statusText.text = getString(
                    R.string.status_idle,
                    count,
                    FileFormatting.formatTime(preferences.getLong(KEY_LAST_INDEX_TIME, 0L))
                )
                performSearch(lastQuery)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.index_done_toast, summary.scanned),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                statusText.text = getString(R.string.status_index_failed)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.index_failed_toast, error.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                refreshButton.isEnabled = hasStorageAccess
            }
        }
    }

    private fun currentRoot(): File {
        val external = Environment.getExternalStorageDirectory()
        if (external != null && external.exists()) {
            return external
        }
        return File(LEGACY_EXTERNAL_ROOT)
    }

    private fun checkStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appSettings = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            )
            try {
                startActivity(appSettings)
            } catch (error: ActivityNotFoundException) {
                val allFilesSettings = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                try {
                    startActivity(allFilesSettings)
                } catch (inner: ActivityNotFoundException) {
                    Toast.makeText(
                        this,
                        R.string.settings_unavailable_toast,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                REQUEST_READ_STORAGE
            )
        }
    }

    private fun showEntryMenu(entry: FileEntry) {
        val actions = arrayOf(
            getString(R.string.action_open_with),
            getString(R.string.action_copy_path),
            getString(R.string.action_share)
        )
        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> openEntry(entry)
                    1 -> copyPath(entry)
                    else -> shareEntry(entry)
                }
            }
            .show()
    }

    private fun openEntry(entry: FileEntry) {
        if (entry.isDirectory) {
            Toast.makeText(
                this,
                getString(R.string.directory_tap_toast, entry.path),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val file = File(entry.path)
        if (!file.exists()) {
            Toast.makeText(this, R.string.file_missing_toast, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, FILE_PROVIDER_AUTHORITY, file)
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeTypeOf(entry.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(viewIntent, getString(R.string.open_with)))
        } catch (error: IllegalArgumentException) {
            Toast.makeText(this, R.string.file_unavailable_toast, Toast.LENGTH_SHORT).show()
        } catch (error: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_to_open_toast, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareEntry(entry: FileEntry) {
        val file = File(entry.path)
        val shareIntent = if (!entry.isDirectory && file.exists()) {
            try {
                val uri = FileProvider.getUriForFile(this, FILE_PROVIDER_AUTHORITY, file)
                Intent(Intent.ACTION_SEND).apply {
                    type = mimeTypeOf(entry.name)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, entry.path)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } catch (error: IllegalArgumentException) {
                pathShareIntent(entry)
            }
        } else {
            pathShareIntent(entry)
        }
        try {
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_with)))
        } catch (error: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_to_share_toast, Toast.LENGTH_SHORT).show()
        }
    }

    private fun pathShareIntent(entry: FileEntry): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, entry.path)
        }

    private fun copyPath(entry: FileEntry) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            Toast.makeText(this, R.string.copy_failed_toast, Toast.LENGTH_SHORT).show()
            return
        }
        clipboard.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.action_copy_path), entry.path)
        )
        Toast.makeText(this, R.string.path_copied_toast, Toast.LENGTH_SHORT).show()
    }

    private fun mimeTypeOf(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension.isEmpty()) {
            return DEFAULT_MIME_TYPE
        }
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: DEFAULT_MIME_TYPE
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 150L
        const val REQUEST_READ_STORAGE = 1001
        const val FILE_PROVIDER_AUTHORITY = "com.yixinjiang.nothing.files"
        const val DEFAULT_MIME_TYPE = "*/*"
        const val PREFS_NAME = "nothing_prefs"
        const val KEY_LAST_INDEX_TIME = "last_index_time"
        const val LEGACY_EXTERNAL_ROOT = "/storage/emulated/0"
    }
}
