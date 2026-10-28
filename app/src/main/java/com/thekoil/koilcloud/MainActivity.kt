package com.thekoil.koilcloud

import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch
import android.widget.Toast
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.thekoil.koilcloud.api.ApiClient
import com.thekoil.koilcloud.ui.FileAdapter
import com.thekoil.koilcloud.util.DownloadHelper
import com.thekoil.koilcloud.util.formatBytes

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: FileAdapter
    private lateinit var filesList: RecyclerView
    private lateinit var loading: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var pathTitle: TextView
    private lateinit var backButton: TextView

    private var currentPath: String = "/"
    private var loadingJob: kotlinx.coroutines.Job? = null

    // Фото и видео — до 10 файлов за раз
    private val pickMedia = registerForActivityResult(
        PickMultipleVisualMedia(10)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) uploadFiles(uris)
    }

    // Документы — любые, без лимита по типам
    private val pickDocument = registerForActivityResult(
        OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) uploadFiles(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)

        filesList = findViewById(R.id.filesList)
        loading = findViewById(R.id.loading)
        emptyText = findViewById(R.id.emptyText)
        pathTitle = findViewById(R.id.pathTitle)
        backButton = findViewById(R.id.backButton)

        adapter = FileAdapter(
            onPreview = { item ->
                when {
                    item.type == "folder" -> navigateTo(item.path)

                    isImageFile(item.name) -> {
                        val intent = android.content.Intent(this, ImagePreviewActivity::class.java).apply {
                            putExtra(ImagePreviewActivity.EXTRA_FILE_ID, item.id)
                            putExtra(ImagePreviewActivity.EXTRA_FILE_NAME, item.name)
                        }
                        startActivity(intent)
                    }

                    // Позже: видео, PDF, документы
                }
            },
            onDownload = { item -> /* ... */ }
        )
        filesList.layoutManager = LinearLayoutManager(this)
        filesList.adapter = adapter

        // Кнопка «назад»
        backButton.setOnClickListener {
            goUp()
        }

        // Системная кнопка «назад»
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentPath != "/") {
                    goUp()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        findViewById<View>(R.id.uploadButton).setOnClickListener {
            showUploadSheet()
        }

        // Storage card
        val used = 47.3 * 1024 * 1024 * 1024
        val total = 128.0 * 1024 * 1024 * 1024
        findViewById<TextView>(R.id.storageRemaining).text = "${formatBytes((total - used).toLong())} free"
        findViewById<TextView>(R.id.storageDetail).text = "${formatBytes(used.toLong())} / ${formatBytes(total.toLong())}"
        findViewById<ProgressBar>(R.id.storageProgress).progress = ((used / total) * 100).toInt()

        updatePathUi()
        loadFiles()
    }

    private fun isImageFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")
    }

    // Вызов для документов — передаём "*/*", чтобы показать все файлы
    private fun openDocumentPicker() {
        pickDocument.launch(arrayOf("*/*"))
    }

    private fun openMediaPicker() {
        pickMedia.launch(
            PickVisualMediaRequest(PickVisualMedia.ImageAndVideo)
        )
    }

    private fun uploadFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return

        val progressToast = Toast.makeText(
            this,
            "Загрузка 0/${uris.size}",
            Toast.LENGTH_LONG
        )
        progressToast.show()

        lifecycleScope.launch {
            var success = 0
            for ((index, uri) in uris.withIndex()) {
                progressToast.setText("Загрузка ${index + 1}/${uris.size}")
                try {
                    if (ApiClient.uploadFile(this@MainActivity, uri)) {
                        success++
                    } else {
                        android.util.Log.w("Upload", "Server rejected: $uri")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("Upload", "Failed: $uri", e)
                }
            }
            progressToast.cancel()

            Toast.makeText(
                this@MainActivity,
                "Загружено $success из ${uris.size}",
                Toast.LENGTH_LONG
            ).show()

            loadFiles()
        }
    }

    private fun navigateTo(path: String) {
        currentPath = path
        updatePathUi()
        loadFiles()
    }

    private fun goUp() {
        if (currentPath == "/") return
        currentPath = currentPath.substringBeforeLast('/').ifEmpty { "/" }
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
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                emptyText.visibility = View.VISIBLE
                emptyText.text = "Не удалось загрузить"
            } finally {
                loading.visibility = View.GONE
            }
        }
    }

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

        dialog.show()
    }

    private fun uploadFile(uri: Uri) {
        Toast.makeText(this, "Загрузка...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val success = ApiClient.uploadFile(this@MainActivity, uri)
                if (success) {
                    Toast.makeText(this@MainActivity, "Файл загружен", Toast.LENGTH_SHORT).show()
                    loadFiles() // обновить список
                } else {
                    Toast.makeText(this@MainActivity, "Ошибка загрузки", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}