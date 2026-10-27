package com.thekoil.koilcloud.util

import java.util.Locale

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val k = 1024.0
    val units = listOf("B", "KB", "MB", "GB", "TB")
    val i = (Math.log(bytes.toDouble()) / Math.log(k)).toInt().coerceIn(0, units.size - 1)
    return String.format(Locale.getDefault(), "%.1f %s", bytes / Math.pow(k, i.toDouble()), units[i])
}

fun getFileIcon(name: String, type: String): String {
    if (type == "folder") return "📁"
    return when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> "🖼️"
        "mp4", "mov", "mkv", "avi", "webm" -> "🎬"
        "mp3", "wav", "m4a", "flac", "ogg" -> "🎵"
        "zip", "rar", "7z", "tar", "gz" -> "📦"
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv" -> "📄"
        else -> "📄"
    }
}

fun getMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "pdf" -> "application/pdf"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "mp4" -> "video/mp4"
    "mov" -> "video/quicktime"
    "mp3" -> "audio/mpeg"
    "m4a" -> "audio/mp4"
    "wav" -> "audio/wav"
    "txt" -> "text/plain"
    "zip" -> "application/zip"
    else -> "application/octet-stream"
}