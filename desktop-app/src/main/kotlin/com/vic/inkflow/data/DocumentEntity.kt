package com.vic.inkflow.data

/**
 * Represents a document in the library.
 * Uses URI as primary key for cross-platform compatibility.
 */
data class DocumentEntity(
    val uri: String,
    val displayName: String,
    val lastOpenedAt: Long = System.currentTimeMillis(),
    val lastPageIndex: Int = 0,
    val isFavorite: Boolean = false,
    val folderId: String? = null
)
