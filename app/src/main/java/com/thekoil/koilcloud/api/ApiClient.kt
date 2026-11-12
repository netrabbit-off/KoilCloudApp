package com.thekoil.koilcloud.api

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.thekoil.koilcloud.auth.AuthError
import com.thekoil.koilcloud.auth.AuthManager
import com.thekoil.koilcloud.auth.AuthResult
import com.thekoil.koilcloud.auth.UnauthorizedException
import com.thekoil.koilcloud.model.AuthResponse
import com.thekoil.koilcloud.model.FileItem
import com.thekoil.koilcloud.model.FilesResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

object ApiClient {
    var baseUrl: String = "http://77.91.66.163:8088"
    var authUrl: String = "http://77.91.66.163:8089"

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun accessToken(): String? = appContext?.let { AuthManager.getAccessToken(it) }
    fun refreshToken(): String? = appContext?.let { AuthManager.getRefreshToken(it) }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMedia = "application/json".toMediaType()

    /** Гарантирует, что одновременно идёт только один refresh. */
    private val refreshMutex = Mutex()

    fun authHeaders(): Map<String, String> =
        accessToken()?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()

    // TODO: заменить на значение из API, когда появится эндпоинт
    const val TOTAL_STORAGE_BYTES: Long = 100 * 1024 * 1024  // 100MB

    /**
     * Возвращает сумму размеров всех файлов (папки не считаются).
     * Использует тот же /files, что и getFiles, но берёт все записи,
     * а не только детей текущей папки.
     */
    suspend fun getUsedStorage(): Long = withContext(Dispatchers.IO) {
        val response = executeWithAuth { access ->
            Request.Builder()
                .url("$baseUrl/files")
                .addHeader("Authorization", "Bearer $access")
                .get()
                .build()
        }

        response.use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val body = r.body?.string() ?: error("Empty body")
            val all = gson.fromJson(body, FilesResponse::class.java).data ?: emptyList()
            all.filter { it.type != "folder" }.sumOf { it.size }
        }
    }

    // ==================== AUTH ====================

    suspend fun login(login: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        val body = gson.toJson(mapOf("user" to login, "password" to password))
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

                if (response.isSuccessful && !json?.accessToken.isNullOrBlank()) {
                    AuthResult.Success(
                        accessToken = json!!.accessToken!!,
                        refreshToken = json.refreshToken.orEmpty(),
                        userId = json.message
                    )
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
                "user" to login,
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

                if (response.isSuccessful && !json?.accessToken.isNullOrBlank()) {
                    AuthResult.Success(
                        accessToken = json!!.accessToken!!,
                        refreshToken = json.refreshToken.orEmpty(),
                        userId = json.message
                    )
                } else {
                    AuthResult.Failure(mapAuthError(json?.error, response.code))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Auth", "register failed", e)
            AuthResult.Failure(AuthError.NETWORK)
        }
    }

    /**
     * Обновляет access через POST /update.
     *
     * @param failedAccess access, с которым запрос получил 401. Если пока мы
     *        ждали мьютекс, другой поток уже обновил токен — вернёт его, не
     *        дёргая сервер. Это защищает от гонки, когда два параллельных
     *        запроса одновременно получили 401, и бэкенд инвалидирует refresh
     *        после первого использования.
     *
     * @return новый access или null, если обновить не удалось.
     */
    private suspend fun refreshAccessToken(failedAccess: String?): String? = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            val currentAccess = accessToken()

            // Кто-то уже обновил, пока мы ждали лок
            if (!failedAccess.isNullOrBlank()
                && !currentAccess.isNullOrBlank()
                && currentAccess != failedAccess
            ) {
                android.util.Log.d("Auth", "refresh skipped: already updated by other request")
                return@withContext currentAccess
            }

            val refresh = refreshToken() ?: run {
                android.util.Log.w("Auth", "refresh: no refresh token")
                return@withContext null
            }

            val body = gson.toJson(mapOf("refresh_token" to refresh))
            android.util.Log.d("Auth", "refresh request: refresh=${refresh.take(8)}...")

            val request = Request.Builder()
                .url("$authUrl/update")
                .post(body.toRequestBody(jsonMedia))
                .build()

            try {
                http.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    android.util.Log.d("Auth", "refresh response: code=${response.code}, body=$text")

                    if (!response.isSuccessful) {
                        return@withContext null
                    }

                    val json = try {
                        gson.fromJson(text, AuthResponse::class.java)
                    } catch (e: Exception) {
                        android.util.Log.e("Auth", "refresh parse error", e)
                        null
                    }

                    val newAccess = json?.accessToken
                    val newRefresh = json?.refreshToken
                    if (newAccess.isNullOrBlank() || newRefresh.isNullOrBlank()) {
                        android.util.Log.w(
                            "Auth",
                            "refresh parsed empty: access=${newAccess?.take(8)}, refresh=${newRefresh?.take(8)}"
                        )
                        return@withContext null
                    }

                    appContext?.let { AuthManager.saveTokens(it, newAccess, newRefresh) }
                    android.util.Log.d("Auth", "refresh success")
                    newAccess
                }
            } catch (e: Exception) {
                android.util.Log.e("Auth", "refresh error", e)
                null
            }
        }
    }

    /**
     * Выполняет запрос с access token. При 401 — обновляет access и повторяет
     * запрос один раз. Бросает UnauthorizedException, если refresh не удался.
     */
    private suspend fun executeWithAuth(builder: (String) -> Request): Response =
        withContext(Dispatchers.IO) {
            val access = accessToken() ?: run {
                android.util.Log.w("Auth", "executeWithAuth: no access token")
                throw UnauthorizedException()
            }

            val first = http.newCall(builder(access)).execute()
            if (first.code != 401) return@withContext first
            first.close()

            android.util.Log.d("Auth", "executeWithAuth: got 401, trying refresh")
            val newAccess = refreshAccessToken(access) ?: throw UnauthorizedException()

            http.newCall(builder(newAccess)).execute().also { second ->
                if (second.code == 401) {
                    second.close()
                    throw UnauthorizedException()
                }
            }
        }

    /**
     * Проверяет, жива ли сессия. При старте приложения — если access протух,
     * обновляет. Возвращает false, если обновить не удалось.
     */
    suspend fun ensureAuthorized(): Boolean = withContext(Dispatchers.IO) {
        val access = accessToken()
        if (access.isNullOrBlank()) {
            android.util.Log.w("Auth", "ensureAuthorized: no access token stored")
            return@withContext false
        }

        val check = Request.Builder()
            .url("$authUrl/check")
            .addHeader("Authorization", "Bearer $access")
            .get()
            .build()

        try {
            http.newCall(check).execute().use { response ->
                android.util.Log.d("Auth", "ensureAuthorized: check code=${response.code}")
                if (response.isSuccessful) return@withContext true
                if (response.code != 401) return@withContext false
            }
            refreshAccessToken(access) != null
        } catch (e: Exception) {
            android.util.Log.e("Auth", "ensureAuthorized failed", e)
            false
        }
    }

    private fun mapAuthError(errorCode: String?, httpCode: Int): AuthError = when {
        errorCode == "invalid_credentials" -> AuthError.INVALID_CREDENTIALS
        errorCode == "login_taken" -> AuthError.LOGIN_TAKEN
        errorCode == "invalid_invite" -> AuthError.INVALID_INVITE
        errorCode == "UNAUTHORIZED" -> AuthError.UNAUTHORIZED
        httpCode == 401 -> AuthError.UNAUTHORIZED
        httpCode == 409 -> AuthError.LOGIN_TAKEN
        httpCode == 403 -> AuthError.INVALID_INVITE
        else -> AuthError.UNKNOWN
    }

    // ==================== READ ====================

    suspend fun getFiles(path: String = "/"): List<FileItem> = withContext(Dispatchers.IO) {
        val response = executeWithAuth { access ->
            Request.Builder()
                .url("$baseUrl/files")
                .addHeader("Authorization", "Bearer $access")
                .get()
                .build()
        }

        response.use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val body = r.body?.string() ?: error("Empty body")
            val all = gson.fromJson(body, FilesResponse::class.java).data ?: emptyList()

            filterChildren(all, path)
                .sortedWith(
                    compareBy<FileItem> { if (it.type == "folder") 0 else 1 }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                )
        }
    }

    private fun filterChildren(all: List<FileItem>, parentPath: String): List<FileItem> {
        val trimmed = parentPath.trim('/')
        val normalizedParent = if (trimmed.isEmpty()) "/" else "/$trimmed"

        return all.filter { item ->
            val itemTrimmed = item.path.trim('/')
            val itemParent = if (itemTrimmed.isEmpty()) "/" else "/$itemTrimmed"
            itemParent == normalizedParent
        }
    }

    // ==================== DOWNLOAD URL ====================

    fun downloadUrl(fileId: String): String = "$baseUrl/files/$fileId/download"

    // ==================== UPLOAD ====================

    suspend fun uploadFile(context: Context, uri: Uri, parentPath: String = "/"): Boolean = withContext(Dispatchers.IO) {
        val rawName = getFileName(context, uri) ?: "upload_${System.currentTimeMillis()}"
        val safeName = rawName.replace("/", "_")

        val trimmed = parentPath.trim('/')
        val normalizedParent = if (trimmed.isEmpty()) "/" else "/$trimmed"

        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Cannot open input stream for $uri")

        val url = "$baseUrl/upload?path=$normalizedParent/"
        android.util.Log.d("Upload", "url=$url, name=$safeName")

        val response = executeWithAuth { access ->
            val fileBody = bytes.toRequestBody("application/octet-stream".toMediaTypeOrNull())
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", safeName, fileBody)
                .build()

            Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $access")
                .post(multipart)
                .build()
        }

        response.use { it.isSuccessful }
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

    suspend fun createFolder(parentPath: String, name: String): Boolean = withContext(Dispatchers.IO) {
        val trimmed = parentPath.trim('/')
        val normalizedParent = if (trimmed.isEmpty()) "/" else "/$trimmed/"

        val body = gson.toJson(
            mapOf(
                "name" to name,
                "path" to normalizedParent,
                "type" to "folder",
                "size" to 0,
                "content" to "",
                "owner" to "root"
            )
        )

        android.util.Log.d("CreateFolder", "parentPath=$parentPath -> path=$normalizedParent, name=$name")

        val response = executeWithAuth { access ->
            Request.Builder()
                .url("$baseUrl/create_folder")
                .addHeader("Authorization", "Bearer $access")
                .post(body.toRequestBody(jsonMedia))
                .build()
        }

        response.use { it.isSuccessful }
    }

    // ==================== DELETE ====================

    suspend fun deleteItem(id: String): Boolean = withContext(Dispatchers.IO) {
        val body = gson.toJson(mapOf("id" to id))
        val response = executeWithAuth { access ->
            Request.Builder()
                .url("$baseUrl/delete")
                .addHeader("Authorization", "Bearer $access")
                .post(body.toRequestBody(jsonMedia))
                .build()
        }
        response.use { it.isSuccessful }
    }

    // ==================== RENAME ====================

    suspend fun renameItem(id: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val body = gson.toJson(mapOf("id" to id, "name" to newName))
        val response = executeWithAuth { access ->
            Request.Builder()
                .url("$baseUrl/rename")
                .addHeader("Authorization", "Bearer $access")
                .post(body.toRequestBody(jsonMedia))
                .build()
        }
        response.use { it.isSuccessful }
    }

    // ==================== MOVE ====================

    suspend fun moveItem(id: String, newPath: String): Boolean = withContext(Dispatchers.IO) {
        val body = gson.toJson(mapOf("id" to id, "path" to newPath))
        val response = executeWithAuth { access ->
            Request.Builder()
                .url("$baseUrl/move")
                .addHeader("Authorization", "Bearer $access")
                .post(body.toRequestBody(jsonMedia))
                .build()
        }
        response.use { it.isSuccessful }
    }
}