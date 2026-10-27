package com.thekoil.koilcloud.model

data class FileItem(
    val id: String,
    val name: String,
    val size: Long,
    val modifiedAt: String,
    val type: String // "file" | "folder"
)

data class FilesResponse(
    val data: List<FileItem>?,
    val error: Boolean = false
)