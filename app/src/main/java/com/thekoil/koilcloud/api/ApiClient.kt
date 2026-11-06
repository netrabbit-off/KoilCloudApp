package com.thekoil.koilcloud.api

import com.thekoil.koilcloud.auth.AuthError
import com.thekoil.koilcloud.auth.AuthResult

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.thekoil.koilcloud.auth.AuthManager
import com.thekoil.koilcloud.model.AuthResponse
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
    var authUrl: String = "http://77.91.66.163:8089"
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    var token: String?
        get() = appContext?.let { AuthManager.getToken(it) }
        set(value) {
            val ctx = appContext ?: return
            if (value == null) AuthManager.logout(ctx)
            else AuthManager.save(ctx, value, AuthManager.getLogin(ctx) ?: "")
        }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMedia = "application/json".toMediaType()

    fun authHeaders(): Map<String, String> =
        token?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()

    // ==================== AUTH ====================

    suspend fun login(login: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        val body = gson.toJson(mapOf("login" to login, "password" to password))
        val request = Request.Builder()
            .url("$authUrl/login")
            .post(body.toRequestBody(jsonMedia))
            .build()

        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                android.util.Log.d("Auth", "login: code=${response.code}, body=$text")

                val json = try {
                    gson.fromJson(text, AuthResponse::class.java)
                } catch (e: Exception) {
                    null
                }

                if (response.isSuccessful && !json?.token.isNullOrBlank()) {
                    AuthResult.Success(json.token!!)
                } else {
                    AuthResult.Failure(mapAuthError(json?.error, response.code))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Auth", "login failed", e)
            AuthResult.Failure(AuthError.NETWORK)
        }
    }

    suspend fun register(login: String, password: String, inviteCode: String): AuthResult = withContext(Dispatchers.IO) {
        val body = gson.toJson(
            mapOf(
                "login" to login,
                "password" to password,
                "invite" to inviteCode
            )
        )
        val request = Request.Builder()
            .url("$authUrl/register")
            .post(body.toRequestBody(jsonMedia))
            .build()

        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                android.util.Log.d("Auth", "register: code=${response.code}, body=$text")

                val json = try {
                    gson.fromJson(text, AuthResponse::class.java)
                } catch (e: Exception) {
                    null
                }

                if (response.isSuccessful && !json?.token.isNullOrBlank()) {
                    AuthResult.Success(json.token!!)
                } else {
                    AuthResult.Failure(mapAuthError(json?.error, response.code))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Auth", "register failed", e)
            AuthResult.Failure(AuthError.NETWORK)
        }
    }

    private fun mapAuthError(errorCode: String?, httpCode: Int): AuthError = when {
        errorCode == "invalid_credentials" -> AuthError.INVALID_CREDENTIALS
        errorCode == "login_taken" -> AuthError.LOGIN_TAKEN
        errorCode == "invalid_invite" -> AuthError.INVALID_INVITE
        httpCode == 401 -> AuthError.INVALID_CREDENTIALS
        httpCode == 409 -> AuthError.LOGIN_TAKEN
        httpCode == 403 -> AuthError.INVALID_INVITE
        else -> AuthError.UNKNOWN
    }

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
        // Нормализуем текущую папку: "/" -> "/", "/123" -> "/123", "123" -> "/123"
        val trimmed = parentPath.trim('/')
        val normalizedParent = if (trimmed.isEmpty()) "/" else "/$trimmed"

        return all.filter { item ->
            // Нормализуем path элемента: "/" -> "/", "/123/" -> "/123", "123" -> "/123"
            val itemTrimmed = item.path.trim('/')
            val itemParent = if (itemTrimmed.isEmpty()) "/" else "/$itemTrimmed"

            itemParent == normalizedParent
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

        val trimmed = parentPath.trim('/')
        val normalizedParent = if (trimmed.isEmpty()) "/" else "/$trimmed/"

        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Cannot open input stream for $uri")

        val fileBody = bytes.toRequestBody("application/octet-stream".toMediaTypeOrNull())
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", safeName, fileBody)
            .build()

        val url = "$baseUrl/upload?path=$normalizedParent"

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
        // "/" -> "", "/123" -> "123", "123" -> "123"
        val trimmed = parentPath.trim('/')

        // Всегда: ведущий + (путь или ничего) + хвостовой
        // "/"    -> "/"      (корень)
        // "/123" -> "/123/"  (папка)
        val normalizedParent = if (trimmed.isEmpty()) "/" else "/$trimmed/"

        val body = gson.toJson(
            mapOf(
                "name" to name,
                "path" to normalizedParent,   // только расположение, с хвостовым слешем
                "type" to "folder",
                "size" to 0,
                "content" to "",
                "owner" to "root"
            )
        )

        android.util.Log.d("CreateFolder", "parentPath=$parentPath -> path=$normalizedParent, name=$name")

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