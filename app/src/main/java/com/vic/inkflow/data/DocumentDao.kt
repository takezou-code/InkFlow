package com.vic.inkflow.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(document: DocumentEntity)

    /** Library / sync / backup view: trashed rows are invisible everywhere. */
    @Query("SELECT * FROM documents WHERE deletedAt IS NULL ORDER BY lastOpenedAt DESC")
    fun getAllDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE deletedAt IS NULL")
    suspend fun getAllDocumentsSync(): List<DocumentEntity>

    /** Hard delete. Only the trash purge path may call this. */
    @Query("DELETE FROM documents WHERE uri = :uri")
    suspend fun delete(uri: String): Int

    // ----- Trash -----------------------------------------------------------

    /** Soft delete: the row, its annotations and its file all stay put. */
    @Query("UPDATE documents SET deletedAt = :timestamp WHERE uri = :uri")
    suspend fun markDeleted(uri: String, timestamp: Long): Int

    @Query("UPDATE documents SET deletedAt = NULL WHERE uri = :uri")
    suspend fun restore(uri: String): Int

    @Query("SELECT * FROM documents WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun getTrashedDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    suspend fun getTrashedDocumentsSync(): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun getExpiredTrashed(cutoff: Long): List<DocumentEntity>

    @Query("UPDATE documents SET lastPageIndex = :pageIndex WHERE uri = :uri")
    suspend fun updateLastPage(uri: String, pageIndex: Int)

    @Query("UPDATE documents SET displayName = :newName WHERE uri = :uri")
    suspend fun renameDocument(uri: String, newName: String)

    @Query("UPDATE documents SET isFavorite = :isFavorite WHERE uri = :uri")
    suspend fun updateFavoriteStatus(uri: String, isFavorite: Boolean)

    @Query("UPDATE documents SET folderId = :folderId WHERE uri = :uri")
    suspend fun updateFolder(uri: String, folderId: String?)

    @Query("UPDATE documents SET folderId = :folderId WHERE uri IN (:uris) AND deletedAt IS NULL")
    suspend fun moveDocumentsToFolder(uris: List<String>, folderId: String?)

    @Query("UPDATE documents SET folderId = NULL WHERE folderId IN (:folderIds)")
    suspend fun clearFolderAssignmentsInFolders(folderIds: List<String>)

    @Query("UPDATE documents SET lastOpenedAt = :timestamp WHERE uri = :uri AND deletedAt IS NULL")
    suspend fun updateLastOpenedAt(uri: String, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT lastPageIndex FROM documents WHERE uri = :uri AND deletedAt IS NULL")
    suspend fun getLastPageIndex(uri: String): Int?
}
