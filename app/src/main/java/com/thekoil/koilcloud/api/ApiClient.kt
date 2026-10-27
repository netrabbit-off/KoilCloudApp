package com.thekoil.koilcloud.api

import com.google.gson.Gson
import com.thekoil.koilcloud.model.FileItem
import com.thekoil.koilcloud.model.FilesResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object ApiClient {
    var baseUrl: String = "http://77.91.66.163:8088"
    var token: String? = "123"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    fun authHeaders(): Map<String, String> =
        token?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()

    /**
     * Загружает содержимое конкретной папки.
     * Если сервер поддерживает ?path=, он отфильтрует на своей стороне.
     * Если нет — клиент сам отфильтрует по parent-пути.
     */
    suspend fun getFiles(path: String = "/"): List<FileItem> = withContext(Dispatchers.IO) {
        // Если сервер поддерживает ?path= — раскомментируйте и уберите фильтр ниже
        // val url = "$baseUrl/files".toHttpUrl().newBuilder()
        //     .addQueryParameter("path", path)
        //     .build()
        val url = "$baseUrl/files"

        val request = Request.Builder()
            .url(url)
            .apply { authHeaders().forEach { (k, v) -> addHeader(k, v) } }
            .get()
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body?.string() ?: error("Empty body")
            val all = gson.fromJson(body, FilesResponse::class.java).data ?: emptyList()

            // Клиентская фильтрация: показываем только прямых детей текущей папки.
            // У папки path = /fuck, у файла внутри — /fuck/xyu.txt
            filterChildren(all, path)
        }
    }

    private fun filterChildren(all: List<FileItem>, parentPath: String): List<FileItem> {
        val normalizedParent = if (parentPath == "/") "" else parentPath.trimEnd('/')

        return all.filter { item ->
            // Родитель файла — это его path без последнего сегмента
            val itemParent = item.path.substringBeforeLast('/', missingDelimiterValue = "")
            itemParent == normalizedParent && item.path != parentPath
        }
    }

    fun downloadUrl(fileId: String): String = "$baseUrl/files/$fileId/download"
}