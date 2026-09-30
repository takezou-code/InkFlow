package com.vic.inkflow.data

/**
 * Represents a document in the library.
 *
 * [Android-compatible] Field-for-field mirror of the Room entity
 * com.vic.inkflow.data.DocumentEntity (table "documents", AppDatabase v23):
 *   uri TEXT PK            – file:// Uri recorded by PdfManager.copyPdfToAppDir();
 *                            unique across devices and used as sync identity.
 *   displayName TEXT       – original file name shown in the library.
 *   lastOpenedAt INTEGER   – epoch millis; updated on every open/edit, therefore
 *                            doubles as the sync conflict timestamp (newer wins).
 *   lastPageIndex INTEGER  – resume position.
 *   isFavorite INTEGER     – stored as 0/1 in SQLite.
 *   folderId TEXT NULL     – FK to folders.id (category), SET_NULL on delete.
 */
data class DocumentEntity(
    val uri: String,
    val displayName: String,
    val lastOpenedAt: Long = System.currentTimeMillis(),
    val lastPageIndex: Int = 0,
    val isFavorite: Boolean = false,
    val folderId: String? = null
) {
    /** Local filesystem path derived from the file:// uri (empty for content:// URIs). */
    val localPath: String
        get() = uri.removePrefix("file://")
}
