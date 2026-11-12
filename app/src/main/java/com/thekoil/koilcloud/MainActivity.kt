package com.thekoil.koilcloud

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.thekoil.koilcloud.api.ApiClient
import com.thekoil.koilcloud.auth.AuthManager
import com.thekoil.koilcloud.auth.UnauthorizedException
import com.thekoil.koilcloud.model.FileItem
import com.thekoil.koilcloud.ui.FileAdapter
import com.thekoil.koilcloud.util.DownloadHelper
import com.thekoil.koilcloud.util.formatBytes
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: FileAdapter
    private lateinit var filesList: RecyclerView
    private lateinit var loading: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var pathTitle: TextView
    private lateinit var backButton: TextView

    private var currentPath: String = "/"
    private var loadingJob: Job? = null
    private var cutItem: FileItem? = null

    private val pickMedia = registerForActivityResult(PickMultipleVisualMedia(10)) { uris: List<Uri> ->
        if (uris.isNotEmpty()) uploadFiles(uris)
    }

    private val pickDocument = registerForActivityResult(OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) uploadFiles(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ApiClient.init(applicationContext)

        if (!AuthManager.isLoggedIn(this)) {
            goToLogin()
            return
        }

        setContentView(R.layout.main_activity)

        filesList = findViewById(R.id.filesList)
        loading = findViewById(R.id.loading)
        emptyText = findViewById(R.id.emptyText)
        pathTitle = findViewById(R.id.pathTitle)
        backButton = findViewById(R.id.backButton)

        adapter = FileAdapter(
            onPreview = { item -> handlePreview(item) },
            onDownload = { item -> downloadItem(item) },
            onLongClick = { item -> showFileActions(item) }
        )
        filesList.layoutManager = LinearLayoutManager(this)
        filesList.adapter = adapter

        backButton.setOnClickListener { goUp() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentPath != "/") goUp() else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        findViewById<View>(R.id.uploadButton).setOnClickListener { showUploadSheet() }
        findViewById<View>(R.id.avatar).setOnClickListener { showProfileDialog() }

        val used = 47.3 * 1024 * 1024 * 1024
        val total = 128.0 * 1024 * 1024 * 1024
        findViewById<TextView>(R.id.storageRemaining).text = "${formatBytes((total - used).toLong())} free"
        findViewById<TextView>(R.id.storageDetail).text = "${formatBytes(used.toLong())} / ${formatBytes(total.toLong())}"
        findViewById<ProgressBar>(R.id.storageProgress).progress = ((used / total) * 100).toInt()

        updatePathUi()

        lifecycleScope.launch {
            val ok = ApiClient.ensureAuthorized()
            if (!ok) {
                handleUnauthorized()
                return@launch
            }
            loadFiles()
        }
    }

    // ---------- AUTH ----------

    private fun goToLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun handleUnauthorized() {
        AuthManager.logout(this)
        goToLogin()
    }

    private fun showProfileDialog() {
        val login = AuthManager.getLogin(this) ?: "—"
        AlertDialog.Builder(this)
            .setTitle("Профиль")
            .setMessage(login)
            .setPositiveButton("Выйти") { _, _ ->
                AuthManager.logout(this)
                goToLogin()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------- PREVIEW ----------

    private fun handlePreview(item: FileItem) {
        when {
            item.type == "folder" -> navigateTo(item.path, item.name)

            isImageFile(item.name) -> {
                val media = adapter.getCurrentItems().filter { isImageFile(it.name) }
                val index = media.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
                val intent = Intent(this, ImagePreviewActivity::class.java).apply {
                    putExtra(ImagePreviewActivity.EXTRA_FILE_ID, item.id)
                    putExtra(ImagePreviewActivity.EXTRA_FILE_NAME, item.name)
                    putExtra(ImagePreviewActivity.EXTRA_MEDIA_IDS, media.map { it.id }.toTypedArray())
                    putExtra(ImagePreviewActivity.EXTRA_MEDIA_NAMES, media.map { it.name }.toTypedArray())
                    putExtra(ImagePreviewActivity.EXTRA_MEDIA_INDEX, index)
                }
                startActivity(intent)
            }

            isVideoOrAudioFile(item.name) -> {
                val media = adapter.getCurrentItems().filter { isVideoOrAudioFile(it.name) }
                val index = media.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
                val intent = Intent(this, MediaPlayerActivity::class.java).apply {
                    putExtra(MediaPlayerActivity.EXTRA_FILE_ID, item.id)
                    putExtra(MediaPlayerActivity.EXTRA_FILE_NAME, item.name)
                    putExtra(MediaPlayerActivity.EXTRA_MEDIA_IDS, media.map { it.id }.toTypedArray())
                    putExtra(MediaPlayerActivity.EXTRA_MEDIA_NAMES, media.map { it.name }.toTypedArray())
                    putExtra(MediaPlayerActivity.EXTRA_MEDIA_INDEX, index)
                }
                startActivity(intent)
            }
        }
    }

    private fun isImageFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")
    }

    private fun isVideoOrAudioFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in listOf(
            "mp4", "mov", "mkv", "webm", "avi",
            "mp3", "m4a", "wav", "flac", "ogg", "aac"
        )
    }

    // ---------- DOWNLOAD ----------

    private fun downloadItem(item: FileItem) {
        lifecycleScope.launch {
            if (!ApiClient.ensureAuthorized()) {
                handleUnauthorized()
                return@launch
            }
            val id = DownloadHelper.download(this@MainActivity, item.id, item.name)
            if (id == -1L) {
                Toast.makeText(this@MainActivity, "Ошибка скачивания", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- ACTIONS SHEET ----------

    private fun showFileActions(item: FileItem) {
        val dialog = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        val view = layoutInflater.inflate(R.layout.sheet_file_actions, null)
        dialog.setContentView(view)

        view.findViewById<View>(R.id.actionRename).setOnClickListener {
            dialog.dismiss()
            showRenameDialog(item)
        }

        view.findViewById<View>(R.id.actionCut).setOnClickListener {
            dialog.dismiss()
            cutItem = item
            Toast.makeText(this, "Вырезано: ${item.name}", Toast.LENGTH_SHORT).show()
        }

        val pasteView = view.findViewById<View>(R.id.actionPaste)
        if (cutItem != null) {
            pasteView.visibility = View.VISIBLE
            pasteView.setOnClickListener {
                dialog.dismiss()
                pasteIntoCurrent()
            }
        } else {
            pasteView.visibility = View.GONE
        }

        view.findViewById<View>(R.id.actionDelete).setOnClickListener {
            dialog.dismiss()
            confirmDelete(item)
        }

        dialog.show()
    }

    private fun showRenameDialog(item: FileItem) {
        val input = EditText(this).apply { setText(item.name) }
        AlertDialog.Builder(this)
            .setTitle("Переименовать")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != item.name) {
                    lifecycleScope.launch {
                        try {
                            if (ApiClient.renameItem(item.id, newName)) loadFiles()
                            else Toast.makeText(this@MainActivity, "Ошибка переименования", Toast.LENGTH_SHORT).show()
                        } catch (e: UnauthorizedException) {
                            handleUnauthorized()
                        }
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmDelete(item: FileItem) {
        AlertDialog.Builder(this)
            .setTitle("Удалить?")
            .setMessage("«${item.name}» будет удалён безвозвратно.")
            .setPositiveButton("Удалить") { _, _ ->
                lifecycleScope.launch {
                    try {
                        if (ApiClient.deleteItem(item.id)) loadFiles()
                        else Toast.makeText(this@MainActivity, "Ошибка удаления", Toast.LENGTH_SHORT).show()
                    } catch (e: UnauthorizedException) {
                        handleUnauthorized()
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun pasteIntoCurrent() {
        val item = cutItem ?: return
        val newPath = if (currentPath == "/") "/${item.name}" else "${currentPath.trimEnd('/')}/${item.name}"
        lifecycleScope.launch {
            try {
                if (ApiClient.moveItem(item.id, newPath)) {
                    cutItem = null
                    loadFiles()
                    Toast.makeText(this@MainActivity, "Перемещено", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Ошибка перемещения", Toast.LENGTH_SHORT).show()
                }
            } catch (e: UnauthorizedException) {
                handleUnauthorized()
            }
        }
    }

    // ---------- UPLOAD SHEET ----------

    private fun showUploadSheet() {
        val dialog = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        val view = layoutInflater.inflate(R.layout.sheet_upload, null)
        dialog.setContentView(view)

        view.findViewById<View>(R.id.optionMedia).setOnClickListener {
            dialog.dismiss()
            openMediaPicker()
        }
        view.findViewById<View>(R.id.optionDocument).setOnClickListener {
            dialog.dismiss()
            openDocumentPicker()
        }
        view.findViewById<View>(R.id.optionFolder).setOnClickListener {
            dialog.dismiss()
            showCreateFolderDialog()
        }
        dialog.show()
    }

    private fun showCreateFolderDialog() {
        val input = EditText(this).apply { hint = "Имя папки" }
        AlertDialog.Builder(this)
            .setTitle("Новая папка")
            .setView(input)
            .setPositiveButton("Создать") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch {
                        try {
                            if (ApiClient.createFolder(currentPath, name)) loadFiles()
                            else Toast.makeText(this@MainActivity, "Ошибка создания", Toast.LENGTH_SHORT).show()
                        } catch (e: UnauthorizedException) {
                            handleUnauthorized()
                        }
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun openDocumentPicker() {
        pickDocument.launch(arrayOf("*/*"))
    }

    private fun openMediaPicker() {
        pickMedia.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo))
    }

    private fun uploadFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val progressToast = Toast.makeText(this, "Загрузка 0/${uris.size}", Toast.LENGTH_LONG)
        progressToast.show()

        lifecycleScope.launch {
            var success = 0
            for ((index, uri) in uris.withIndex()) {
                progressToast.setText("Загрузка ${index + 1}/${uris.size}")
                try {
                    if (ApiClient.uploadFile(this@MainActivity, uri, currentPath)) success++
                } catch (e: UnauthorizedException) {
                    progressToast.cancel()
                    handleUnauthorized()
                    return@launch
                } catch (e: Exception) {
                    android.util.Log.e("Upload", "Failed: $uri", e)
                }
            }
            progressToast.cancel()
            Toast.makeText(this@MainActivity, "Загружено $success из ${uris.size}", Toast.LENGTH_LONG).show()
            loadFiles()
        }
    }

    // ---------- NAVIGATION ----------

    private fun navigateTo(parentPath: String, name: String) {
        val parent = parentPath.trim('/')
        val trimmedName = name.trim('/')
        currentPath = if (parent.isEmpty()) "/$trimmedName" else "/$parent/$trimmedName"
        updatePathUi()
        loadFiles()
    }

    private fun goUp() {
        if (currentPath == "/" || currentPath.isEmpty()) return
        val trimmed = currentPath.trim('/')
        if (trimmed.isEmpty()) {
            currentPath = "/"
        } else {
            val parent = trimmed.substringBeforeLast('/', "")
            currentPath = if (parent.isEmpty()) "/" else "/$parent"
        }
        updatePathUi()
        loadFiles()
    }

    private fun updatePathUi() {
        pathTitle.text = if (currentPath == "/") "My Drive" else currentPath.substringAfterLast('/')
        backButton.visibility = if (currentPath == "/") View.GONE else View.VISIBLE
    }

    private fun loadFiles() {
        loadingJob?.cancel()
        loading.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
        adapter.submit(emptyList())

        val path = currentPath
        loadingJob = lifecycleScope.launch {
            try {
                val files = ApiClient.getFiles(path)
                adapter.submit(files)
                if (files.isEmpty()) {
                    emptyText.visibility = View.VISIBLE
                    emptyText.text = "Папка пуста"
                }
            } catch (e: UnauthorizedException) {
                handleUnauthorized()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                emptyText.visibility = View.VISIBLE
                emptyText.text = "Не удалось загрузить"
            } finally {
                loading.visibility = View.GONE
            }
        }
    }
}