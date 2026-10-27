package com.thekoil.koilcloud

import com.google.android.material.bottomsheet.BottomSheetDialog
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.thekoil.koilcloud.api.ApiClient
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import com.thekoil.koilcloud.ui.FileAdapter
import com.thekoil.koilcloud.util.DownloadHelper
import com.thekoil.koilcloud.util.formatBytes
import kotlinx.coroutines.launch
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import android.net.Uri

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: FileAdapter
    private lateinit var filesList: RecyclerView
    private lateinit var loading: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var pathTitle: TextView
    private lateinit var backButton: TextView

    private var currentPath: String = "/"
    private var loadingJob: kotlinx.coroutines.Job? = null


    // Лаунчер для выбора медиа
    private val pickMedia = registerForActivityResult(PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            // Пользователь выбрал файл. Здесь можно начать загрузку на сервер.
            uploadFile(uri)
        }
        // Если null — пользователь закрыл пикер, ничего не делаем.
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
                // Тап по папке = навигация, по файлу = (пока ничего, превью позже)
                if (item.type == "folder") {
                    navigateTo(item.path)
                }
            },
            onDownload = { item ->
                val id = DownloadHelper.download(this, item.id, item.name)
                if (id != -1L) {
                    Toast.makeText(this, "Скачивание: ${item.name}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Ошибка скачивания", Toast.LENGTH_SHORT).show()
                }
            }
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

    // Лаунчер для документов (любой тип файла)
    private val pickDocument = registerForActivityResult(OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            uploadFile(uri)
        }
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
        android.util.Log.d("Upload", "Selected: $uri")
        Toast.makeText(this, "Выбран файл: $uri", Toast.LENGTH_SHORT).show()
        // Здесь будет OkHttp multipart
    }
}