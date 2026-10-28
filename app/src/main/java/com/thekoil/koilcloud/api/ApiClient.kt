package com.thekoil.koilcloud.api

import android.content.Context
import android.net.Uri
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import com.google.gson.Gson
import com.thekoil.koilcloud.model.FileItem
import com.thekoil.koilcloud.model.FilesResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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


    suspend fun uploadFile(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        // 1. Получаем имя файла из URI (для multipart filename)
        val fileName = getFileName(context, uri) ?: "upload_${System.currentTimeMillis()}"

        // 2. Читаем байты из content:// URI
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Cannot open input stream for $uri")

        // 3. Собираем multipart-запрос
        //    Имя поля "file" должно совпадать с тем, что ждёт ваш бэкенд (в Expo обычно "file")
        val fileBody = bytes.toRequestBody("application/octet-stream".toMediaTypeOrNull())
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM) // критично, иначе сервер может не распарсить [citation:1][citation:8]
            .addFormDataPart("file", fileName, fileBody)
            .build()

        val request = Request.Builder()
            .url("$baseUrl/upload") // замените на реальный эндпоинт загрузки
            .apply { authHeaders().forEach { (k, v) -> addHeader(k, v) } }
            .post(multipart)
            .build()

        http.newCall(request).execute().use { response ->
            response.isSuccessful
        }
    }

    // Вспомогательная функция: вытащить имя файла из content:// URI
    private fun getFileName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) return cursor.getString(nameIndex)
                }
            }
        }
        return uri.lastPathSegment
    }

    fun downloadUrl(fileId: String): String = "$baseUrl/files/$fileId/download"
}
