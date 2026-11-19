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
import android.widget.ImageView
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
    private lateinit var backButton: ImageView
    private lateinit var pasteButton: ImageView
    private lateinit var cancelCutButton: ImageView
    private lateinit var clearSelectionButton: ImageView
    private lateinit var menuButton: ImageView

    private var currentPath: String = "/"
    private var loadingJob: Job? = null

    private val cutItems = linkedMapOf<String, FileItem>()
    private val selectedItems = linkedMapOf<String, FileItem>()
    private lateinit var breadcrumbs: android.widget.LinearLayout

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
        backButton = findViewById(R.id.backButton)
        pasteButton = findViewById(R.id.pasteButton)
        cancelCutButton = findViewById(R.id.cancelCutButton)
        clearSelectionButton = findViewById(R.id.clearSelectionButton)
        menuButton = findViewById(R.id.menuButton)
        breadcrumbs = findViewById(R.id.breadcrumbs)

        adapter = FileAdapter(
            onPreview = { item -> handlePreview(item) },
            onDownload = { item -> downloadItem(item) },
            onLongClick = { item -> toggleSelection(item) }
        )
        filesList.layoutManager = LinearLayoutManager(this)
        filesList.adapter = adapter

        backButton.setOnClickListener {
            if (isSelectionMode()) clearSelection()
            else goUp()
        }
        clearSelectionButton.setOnClickListener { clearSelection() }
        pasteButton.setOnClickListener { pasteIntoCurrent() }
        cancelCutButton.setOnClickListener { cancelCut() }
        menuButton.setOnClickListener { showActionsMenu() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    isSelectionMode() -> clearSelection()
                    currentPath != "/" -> goUp()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        findViewById<View>(R.id.uploadButton).setOnClickListener { showUploadSheet() }
        findViewById<View>(R.id.avatar).setOnClickListener { showProfileDialog() }

        updatePathUi()
        updatePasteButton()

        lifecycleScope.launch {
            val ok = ApiClient.ensureAuthorized()
            if (!ok) {
                handleUnauthorized()
                return@launch
            }
            loadFiles()
            refreshStorage()
        }
    }

    private fun updatePathUi() {
        updateBreadcrumbs()

        val inSelection = selectedItems.isNotEmpty()
        backButton.visibility = if (currentPath != "/") View.VISIBLE else View.GONE
        clearSelectionButton.visibility = if (inSelection) View.VISIBLE else View.GONE
        menuButton.visibility = View.VISIBLE
    }

    private fun updateBreadcrumbs() {
        breadcrumbs.removeAllViews()

        val segments = mutableListOf<Pair<String, String>>()
        segments.add("KoilCloud" to "/")

        if (currentPath != "/") {
            val parts = currentPath.trim('/').split('/')
            var acc = ""
            for (part in parts) {
                acc = "$acc/$part"
                segments.add(part to acc)
            }
        }

        for ((index, segment) in segments.withIndex()) {
            val (label, path) = segment

            val tv = TextView(this).apply {
                text = label
                setTextColor(
                    if (index == segments.lastIndex) getColor(R.color.text_primary)
                    else getColor(R.color.primary)
                )
                textSize = 16f
                setTypeface(
                    null,
                    if (index == segments.lastIndex) android.graphics.Typeface.BOLD
                    else android.graphics.Typeface.NORMAL
                )
                isClickable = index != segments.lastIndex
                isFocusable = index != segments.lastIndex
                setOnClickListener {
                    if (path != currentPath) {
                        currentPath = path
                        updatePathUi()
                        loadFiles()
                    }
                }
            }
            breadcrumbs.addView(tv)

            if (index < segments.lastIndex) {
                val sep = TextView(this).apply {
                    text = " / "
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 16f
                }
                breadcrumbs.addView(sep)
            }
        }

        breadcrumbs.post {
            (breadcrumbs.parent as? android.widget.HorizontalScrollView)
                ?.fullScroll(android.view.View.FOCUS_RIGHT)
        }
    }

    // ---------- SELECTION ----------

    private fun isSelectionMode(): Boolean = selectedItems.isNotEmpty()

    private fun toggleSelection(item: FileItem) {
        if (selectedItems.containsKey(item.id)) selectedItems.remove(item.id)
        else selectedItems[item.id] = item
        adapter.setSelectedIds(selectedItems.keys)
        updatePathUi()
    }

    private fun clearSelection() {
        if (selectedItems.isEmpty()) return
        selectedItems.clear()
        adapter.setSelectedIds(emptySet())
        updatePathUi()
    }

    private fun selectAll() {
        val all = adapter.getCurrentItems()
        selectedItems.clear()
        all.forEach { selectedItems[it.id] = it }
        adapter.setSelectedIds(selectedItems.keys)
        updatePathUi()
    }

    // ---------- ACTIONS MENU ----------

    private fun showActionsMenu() {
        val dialog = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        val view = layoutInflater.inflate(R.layout.sheet_selection_actions, null)
        dialog.setContentView(view)

        val single = selectedItems.size == 1
        val singleItem = if (single) selectedItems.values.firstOrNull() else null
        val renameView = view.findViewById<View>(R.id.selRename)

        if (single && singleItem != null) {
            renameView.visibility = View.VISIBLE
            renameView.setOnClickListener {
                dialog.dismiss()
                showRenameDialog(singleItem)
            }
        } else {
            renameView.visibility = View.GONE
        }

        // Выделить всё — только если есть что выделять и не всё выделено
        val selectAllView = view.findViewById<View>(R.id.selSelectAll)
        val allItems = adapter.getCurrentItems()
        val allSelected = allItems.isNotEmpty() && selectedItems.keys.containsAll(allItems.map { it.id })
        if (allItems.isNotEmpty() && !allSelected) {
            selectAllView.visibility = View.VISIBLE
            selectAllView.setOnClickListener {
                dialog.dismiss()
                selectAll()
            }
        } else {
            selectAllView.visibility = View.GONE
        }

        // Вырезать / Удалить — только если что-то выделено
        val hasSelection = selectedItems.isNotEmpty()
        val cutView = view.findViewById<View>(R.id.selCut)
        val deleteView = view.findViewById<View>(R.id.selDelete)
        cutView.visibility = if (hasSelection) View.VISIBLE else View.GONE
        deleteView.visibility = if (hasSelection) View.VISIBLE else View.GONE

        cutView.setOnClickListener {
            dialog.dismiss()
            cutSelected()
        }
        deleteView.setOnClickListener {
            dialog.dismiss()
            deleteSelected()
        }

        dialog.show()
    }

    // ---------- CUT / PASTE ----------

    private fun cutSelected() {
        if (selectedItems.isEmpty()) return
        cutItems.clear()
        cutItems.putAll(selectedItems)
        adapter.setCutItems(cutItems.keys)
        updatePasteButton()
        Toast.makeText(this, "Вырезано: ${cutItems.size}", Toast.LENGTH_SHORT).show()
        clearSelection()
    }

    private fun cancelCut() {
        cutItems.clear()
        adapter.setCutItems(emptySet())
        updatePasteButton()
    }

    private fun pasteIntoCurrent() {
        if (cutItems.isEmpty()) return
        val items = cutItems.values.toList()

        for (item in items) {
            if (item.type == "folder") {
                val folderFullPath = buildFolderFullPath(item)
                val target = currentPath.trimEnd('/')
                if (target == folderFullPath || target.startsWith("$folderFullPath/")) {
                    Toast.makeText(this, "Нельзя переместить папку «${item.name}» внутрь себя", Toast.LENGTH_LONG).show()
                    return
                }
            }
        }

        val newParentPath = if (currentPath == "/") "/" else "${currentPath.trimEnd('/')}/"
        lifecycleScope.launch {
            var success = 0
            for (item in items) {
                try {
                    if (ApiClient.moveItem(item.id, newParentPath)) success++
                } catch (e: UnauthorizedException) {
                    cancelCut()
                    handleUnauthorized()
                    return@launch
                } catch (e: Exception) {
                    android.util.Log.e("Move", "failed: ${item.name}", e)
                }
            }
            cancelCut()
            loadFiles()
            Toast.makeText(this@MainActivity, "Перемещено $success из ${items.size}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updatePasteButton() {
        if (!::pasteButton.isInitialized) return
        val visible = cutItems.isNotEmpty()
        pasteButton.visibility = if (visible) View.VISIBLE else View.GONE
        if (::cancelCutButton.isInitialized) {
            cancelCutButton.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    private fun buildFolderFullPath(item: FileItem): String {
        val parent = item.path.trim('/')
        return if (parent.isEmpty()) "/${item.name}" else "/$parent/${item.name}"
    }

    // ---------- DELETE ----------

    private fun deleteSelected() {
        val items = selectedItems.values.toList()
        if (items.isEmpty()) return

        AlertDialog.Builder(this)
            .setTitle("Удалить ${items.size}?")
            .setMessage("Выбранные элементы будут удалены безвозвратно.")
            .setPositiveButton("Удалить") { _, _ ->
                lifecycleScope.launch {
                    var success = 0
                    for (item in items) {
                        try {
                            if (ApiClient.deleteItem(item.id)) success++
                        } catch (e: UnauthorizedException) {
                            clearSelection()
                            handleUnauthorized()
                            return@launch
                        } catch (e: Exception) {
                            android.util.Log.e("Delete", "failed: ${item.name}", e)
                        }
                    }
                    clearSelection()
                    loadFiles()
                    refreshStorage()
                    Toast.makeText(this@MainActivity, "Удалено $success из ${items.size}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
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
        val dialog = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        val view = layoutInflater.inflate(R.layout.sheet_profile, null)
        dialog.setContentView(view)

        val loginView = view.findViewById<TextView>(R.id.profileLogin)
        val avatarView = view.findViewById<TextView>(R.id.profileAvatar)
        val idView = view.findViewById<TextView>(R.id.profileId)

        loginView.text = "Загрузка..."
        idView.text = "—"

        view.findViewById<View>(R.id.profileLogout).setOnClickListener {
            dialog.dismiss()
            AuthManager.logout(this)
            goToLogin()
        }

        dialog.show()

        lifecycleScope.launch {
            try {
                val user = ApiClient.getMe()
                if (user == null) {
                    loginView.text = "Не удалось загрузить"
                    return@launch
                }
                val login = user.login
                loginView.text = login
                avatarView.text = login?.firstOrNull()?.uppercase() ?: "?"
                idView.text = user.id ?: "—"
            } catch (e: UnauthorizedException) {
                dialog.dismiss()
                handleUnauthorized()
            } catch (e: Exception) {
                loginView.text = "Ошибка загрузки"
            }
        }
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

    // ---------- RENAME ----------

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
                            if (ApiClient.renameItem(item.id, newName)) {
                                clearSelection()
                                loadFiles()
                            } else {
                                Toast.makeText(this@MainActivity, "Ошибка переименования", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: UnauthorizedException) {
                            handleUnauthorized()
                        }
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------- STORAGE ----------

    private fun refreshStorage() {
        lifecycleScope.launch {
            try {
                val used = ApiClient.getUsedStorage()
                val total = ApiClient.TOTAL_STORAGE_BYTES
                val remaining = (total - used).coerceAtLeast(0)

                findViewById<TextView>(R.id.storageRemaining).text = "${formatBytes(remaining)} free"
                findViewById<TextView>(R.id.storageDetail).text = "${formatBytes(used)} / ${formatBytes(total)}"
                findViewById<ProgressBar>(R.id.storageProgress).progress =
                    if (total > 0) ((used * 100) / total).toInt().coerceIn(0, 100) else 0
            } catch (e: UnauthorizedException) {
                handleUnauthorized()
            } catch (e: Exception) {
                android.util.Log.e("Storage", "refreshStorage failed", e)
            }
        }
    }

    // ---------- UPLOAD ----------

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
            refreshStorage()
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
                adapter.setSelectedIds(selectedItems.keys)
                adapter.setCutItems(cutItems.keys)
                updatePathUi()
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