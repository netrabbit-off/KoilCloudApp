package com.thekoil.koilcloud.util

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.net.toUri
import com.thekoil.koilcloud.api.ApiClient

object DownloadHelper {

    /**
     * Запускает системное скачивание файла в публичную папку Downloads/KoilCloud.
     * DownloadManager сам качает в фоне, показывает уведомление с прогрессом
     * и сохраняет файл доступным для других приложений.
     *
     * Возвращает id задачи или -1 при ошибке.
     */
    fun download(context: Context, fileId: String, fileName: String): Long {
        val url = ApiClient.downloadUrl(fileId)
        val request = DownloadManager.Request(url.toUri())
            .setTitle(fileName)
            .setDescription("Downloading from KoilCloud")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "KoilCloud/$fileName"
            )
            .setMimeType(getMimeType(fileName))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        // Заголовок авторизации — DownloadManager умеет передавать headers
        ApiClient.token?.let { request.addRequestHeader("Authorization", "Bearer $it") }

        return try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
        }  catch (e: Exception) {
            android.util.Log.e("DownloadHelper", "enqueue failed: url=$url, mime=${getMimeType(fileName)}, name=$fileName", e)
            -1L
        }
    }
}