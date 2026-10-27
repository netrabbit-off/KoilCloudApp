package com.thekoil.koilcloud

import android.os.Bundle
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.thekoil.koilcloud.api.ApiClient
import com.thekoil.koilcloud.ui.FileAdapter
import com.thekoil.koilcloud.util.DownloadHelper
import com.thekoil.koilcloud.util.formatBytes
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: FileAdapter
    private lateinit var filesList: RecyclerView
    private lateinit var loading: ProgressBar
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)

        filesList = findViewById(R.id.filesList)
        loading = findViewById(R.id.loading)
        emptyText = findViewById(R.id.emptyText)

        adapter = FileAdapter(
            onPreview = { /* preview later */ },
            onDownload = { item ->
                val id = DownloadHelper.download(this, item.id, item.name)
                if (id != -1L) {
                    Toast.makeText(this, "Скачивание запущено: ${item.name}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Не удалось запустить скачивание", Toast.LENGTH_SHORT).show()
                }
            }
        )
        filesList.layoutManager = LinearLayoutManager(this)
        filesList.adapter = adapter

        // Storage card — демо-данные
        val used = 47.3 * 1024 * 1024 * 1024
        val total = 128.0 * 1024 * 1024 * 1024
        findViewById<TextView>(R.id.storageRemaining).text = "${formatBytes((total - used).toLong())} free"
        findViewById<TextView>(R.id.storageDetail).text = "${formatBytes(used.toLong())} / ${formatBytes(total.toLong())}"
        findViewById<ProgressBar>(R.id.storageProgress).progress = ((used / total) * 100).toInt()

        loadFiles()
    }

    private fun loadFiles() {
        loading.visibility = android.view.View.VISIBLE
        emptyText.visibility = android.view.View.GONE

        lifecycleScope.launch {
            try {
                val files = ApiClient.getFiles()
                adapter.submit(files)
                if (files.isEmpty()) emptyText.visibility = android.view.View.VISIBLE
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Ошибка загрузки: ${e.message}", Toast.LENGTH_LONG).show()
                emptyText.visibility = android.view.View.VISIBLE
            } finally {
                loading.visibility = android.view.View.GONE
            }
        }
    }
}