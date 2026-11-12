package com.thekoil.koilcloud.util

import android.content.Context
import android.net.Uri
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import okio.ForwardingSink
import okio.Sink
import okio.buffer
import okio.source

class ProgressRequestBody(
    private val context: Context,
    private val uri: Uri,
    private val contentType: MediaType? = "application/octet-stream".toMediaTypeOrNull(),
    private val onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit
) : RequestBody() {

    private var totalBytes: Long = -1L

    override fun contentType(): MediaType? = contentType

    override fun contentLength(): Long {
        if (totalBytes == -1L) {
            totalBytes = getFileSize(context, uri)
        }
        return totalBytes
    }

    override fun writeTo(sink: BufferedSink) {
        val countingSink = CountingSink(sink)
        val bufferedSink = countingSink.buffer()

        context.contentResolver.openInputStream(uri)?.use { input ->
            bufferedSink.writeAll(input.source())
        } ?: throw IllegalStateException("Cannot open input stream for $uri")

        bufferedSink.flush()
    }

    private inner class CountingSink(delegate: Sink) : ForwardingSink(delegate) {
        private var bytesWritten = 0L

        override fun write(source: okio.Buffer, byteCount: Long) {
            super.write(source, byteCount)
            bytesWritten += byteCount
            val total = contentLength()
            onProgress(bytesWritten, total)
        }
    }

    private fun getFileSize(context: Context, uri: Uri): Long {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIndex != -1) {
                        val size = cursor.getLong(sizeIndex)
                        if (size > 0) return size
                    }
                }
            }
        }
        return -1L
    }
}