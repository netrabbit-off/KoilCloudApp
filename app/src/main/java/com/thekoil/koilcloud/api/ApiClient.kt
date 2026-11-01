package com.thekoil.koilcloud.api

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.thekoil.koilcloud.model.FileItem
import com.thekoil.koilcloud.model.FilesResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

object ApiClient {
    var baseUrl: String = "http://77.91.66.163:8088"
    var token: String? = "123"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMedia = "application/json".toMediaType()

    fun authHeaders(): Map<String, String> =
        token?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()

    // ==================== READ ====================

    suspend fun getFiles(path: String = "/"): List<FileItem> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/files")
            .apply { authHeaders().forEach { (k, v) -> addHeader(k, v) } }
            .get()
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body?.string() ?: error("Empty body")
            val all = gson.fromJson(body, FilesResponse::class.java).data ?: emptyList()

            filterChildren(all, path)
                .sortedWith(
                    compareBy<FileItem> { if (it.type == "folder") 0 else 1 }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                )
        }
    }

    private fun filterChildren(all: List<FileItem>, parentPath: String): List<FileItem> {
        // "/" -> "", "/123" -> "123", "/123/" -> "123", "123" -> "123"
        val normalizedParent = parentPath.trim('/')

        return all.filter { item ->
            // "/123/test.txt" -> "123/test.txt", "123/test.txt" -> "123/test.txt", "/test.txt" -> "test.txt"
            val itemPath = item.path.trim('/')

            // Родитель = всё до последнего слеша
            // "123/test.txt" -> "123"
            // "test.txt"     -> "" (корень)
            val itemParent = if (itemPath.contains('/')) {
                itemPath.substringBeforeLast('/')
            } else {
                ""
            }

            // Исключаем саму папку, в которой находимся
            // Например, для parentPath="/123" элемент с path="/123" не должен показываться
            val isSelf = itemPath == normalizedParent

            !isSelf && itemParent == normalizedParent
        }
    }

    // ==================== URL для скачивания/превью ====================

    /**
     * Прямая ссылка для DownloadManager / Glide / ExoPlayer.
     * Бэкенд отдаёт raw-файл через GET /files/{id}/download.
     */
    fun downloadUrl(fileId: String): String = "$baseUrl/files/$fileId/download"

    // ==================== UPLOAD ====================

    suspend fun uploadFile(context: Context, uri: Uri, parentPath: String = "/"): Boolean = withContext(Dispatchers.IO) {
        val rawName = getFileName(context, uri) ?: "upload_${System.currentTimeMillis()}"
        val safeName = rawName.replace("/", "_")

        // Нормализуем parentPath: всегда с ведущим слешем, без хвостового
        val normalizedParent = "/" + parentPath.trim('/')

        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Cannot open input stream for $uri")

        val fileBody = bytes.toRequestBody("application/octet-stream".toMediaTypeOrNull())
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", safeName, fileBody)   // только имя, без пути
            .build()

        // Путь — в query-параметре, как в curl
        val url = "$baseUrl/upload?path=$normalizedParent/"

        android.util.Log.d("Upload", "url=$url, name=$safeName")

        val request = Request.Builder()
            .url(url)
            .apply { authHeaders().forEach { (k, v) -> addHeader(k, v) } }
            .post(multipart)
            .build()

        http.newCall(request).execute().use { it.isSuccessful }
    }

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

    // ==================== CREATE FOLDER ====================

    /**
     * Бэкенд ждёт POST /create_folder с телом File.
     * В CreateFolderHandler он берёт file.Path и сохраняет как имя папки.
     * Поэтому в "path" кладём ИМЯ папки, а не полный путь.
     */
    suspend fun createFolder(parentPath: String, name: String): Boolean = withContext(Dispatchers.IO) {
        // Нормализуем родителя: "/" -> "", "/123" -> "123", "123" -> "123"
        val trimmed = parentPath.trim('/')

        // Полный путь новой папки: "/name" в корне, "/123/name" в папке
        val fullPath = if (trimmed.isEmpty()) "/$name" else "/$trimmed/$name"

        val body = gson.toJson(
            mapOf(
                "name" to name,
                "path" to fullPath,      // ⬅ полный путь, а не просто имя
                "type" to "folder",
                "size" to 0,
                "content" to "",
                "owner" to "root"
            )
        )

        android.util.Log.d("CreateFolder", "parentPath=$parentPath -> fullPath=$fullPath")

        val request = Request.Builder()
            .url("$baseUrl/create_folder")
            .apply { authHeaders().forEach { (k, v) -> addHeader(k, v) } }
            .post(body.toRequestBody(jsonMedia))
            .build()

        http.newCall(request).execute().use { it.isSuccessful }
    }

    // ==================== DELETE ====================

    /**
     * Бэкенд ждёт POST /delete с телом { "id": "..." }.
     */
    suspend fun deleteItem(id: String): Boolean = withContext(Dispatchers.IO) {
        val body = gson.toJson(mapOf("id" to id))
        val request = Request.Builder()
            .url("$baseUrl/delete")
            .apply { authHeaders().forEach { (k, v) -> addHeader(k, v) } }
            .post(body.toRequestBody(jsonMedia))
            .build()

        http.newCall(request).execute().use { it.isSuccessful }
    }

    // ==================== RENAME (заглушка) ====================

    /**
     * TODO: на бэкенде пока нет эндпоинта переименования.
     * Когда добавите (например, POST /rename с { "id", "name" }) —
     * раскомментируйте код ниже и поправьте URL.
     */
    suspend fun renameItem(id: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val body = gson.toJson(mapOf("id" to id, "name" to newName))
        val request = Request.Builder()
            .url("$baseUrl/rename")
            .apply { authHeaders().forEach { (k, v) -> addHeader(k, v) } }
            .post(body.toRequestBody(jsonMedia))
            .build()
        http.newCall(request).execute().use { it.isSuccessful }
    }

    // ==================== MOVE (заглушка) ====================

    /**
     * TODO: на бэкенде пока нет эндпоинта перемещения.
     * Когда добавите — раскомментируйте и поправьте URL.
     */
    suspend fun moveItem(id: String, newPath: String): Boolean = withContext(Dispatchers.IO) {
        // Пока возвращаем false
        // val body = gson.toJson(mapOf("id" to id, "path" to newPath))
        // val request = Request.Builder()
        //     .url("$baseUrl/move")
        //     .apply { authHeaders().forEach { (k, v) -> addHeader(k, v) } }
        //     .post(body.toRequestBody(jsonMedia))
        //     .build()
        // http.newCall(request).execute().use { it.isSuccessful }

        false
    }
}