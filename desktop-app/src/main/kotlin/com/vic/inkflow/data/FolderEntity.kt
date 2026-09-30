package com.vic.inkflow.data

/**
 * Mirrors the Android Room FolderEntity (table "folders").
 * Used by the desktop Library view for category filtering.
 */
data class FolderEntity(
    val id: String,
    val name: String,
    val parentFolderId: String? = null,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
