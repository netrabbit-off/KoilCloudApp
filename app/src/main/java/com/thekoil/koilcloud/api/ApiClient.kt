package com.thekoil.koilcloud.api

import com.google.gson.Gson
import com.thekoil.koilcloud.model.FilesResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object ApiClient {
    // Для эмулятора Android: 10.0.2.2 = localhost вашего компьютера.
    // Для реального телефона: замените на IP компьютера в локальной сети, например http://192.168.1.42:8080
    var baseUrl: String = "http://77.91.66.163:8088"
    var token: String? = "123"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    fun authHeaders(): Map<String, String> =
        token?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()

    suspend fun getFiles(): List<com.thekoil.koilcloud.model.FileItem> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/files")
            .apply { authHeaders().forEach { (k, v) -> addHeader(k, v) } }
            .get()
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body?.string() ?: error("Empty body")
            gson.fromJson(body, FilesResponse::class.java).data ?: emptyList()
        }
    }

    fun downloadUrl(fileId: String): String = "$baseUrl/files/$fileId/download"
}