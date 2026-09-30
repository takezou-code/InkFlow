package com.vic.inkflow.data.repository

import com.vic.inkflow.data.AppDatabase
import com.vic.inkflow.data.DocumentDao
import com.vic.inkflow.data.DocumentEntity
import kotlinx.coroutines.flow.Flow

/**
 * 文件的資料存取邊界。
 *
 * [updateLastOpenedAt] 同時是同步協定的衝突解決時間戳（SYNC_PROTOCOL §6），
 * 所以它不只是「順便更新一下」——任何改動文件內容的路徑都必須碰到它，
 * 否則桌面端永遠看不到更新。改這個方法前先看那份協定。
 */
interface DocumentRepository {

    suspend fun upsert(document: DocumentEntity)

    fun getAllDocuments(): Flow<List<DocumentEntity>>
    suspend fun getAllDocumentsSync(): List<DocumentEntity>

    suspend fun delete(uri: String): Int
    suspend fun updateLastPage(uri: String, pageIndex: Int)
    suspend fun renameDocument(uri: String, newName: String)
    suspend fun updateFavoriteStatus(uri: String, isFavorite: Boolean)
    suspend fun updateFolder(uri: String, folderId: String?)
    suspend fun moveDocumentsToFolder(uris: List<String>, folderId: String?)

    /** 刪除分類時把底下文件的 folderId 清成 NULL（FK 是 SET NULL，但顯式呼叫較可控）。 */
    suspend fun clearFolderAssignmentsInFolders(folderIds: List<String>)

    /** 同步協定的衝突時間戳。預設帶現在時間是刻意的，不要改成必填。 */
    suspend fun updateLastOpenedAt(uri: String, timestamp: Long = System.currentTimeMillis())

    suspend fun getLastPageIndex(uri: String): Int?
}

class RoomDocumentRepository(
    private val db: AppDatabase,
    private val dao: DocumentDao
) : DocumentRepository {

    override suspend fun upsert(document: DocumentEntity) = dao.upsert(document)
    override fun getAllDocuments(): Flow<List<DocumentEntity>> = dao.getAllDocuments()
    override suspend fun getAllDocumentsSync(): List<DocumentEntity> = dao.getAllDocumentsSync()
    override suspend fun delete(uri: String): Int = dao.delete(uri)
    override suspend fun updateLastPage(uri: String, pageIndex: Int) = dao.updateLastPage(uri, pageIndex)
    override suspend fun renameDocument(uri: String, newName: String) = dao.renameDocument(uri, newName)
    override suspend fun updateFavoriteStatus(uri: String, isFavorite: Boolean) = dao.updateFavoriteStatus(uri, isFavorite)
    override suspend fun updateFolder(uri: String, folderId: String?) = dao.updateFolder(uri, folderId)
    override suspend fun moveDocumentsToFolder(uris: List<String>, folderId: String?) = dao.moveDocumentsToFolder(uris, folderId)
    override suspend fun clearFolderAssignmentsInFolders(folderIds: List<String>) = dao.clearFolderAssignmentsInFolders(folderIds)
    override suspend fun updateLastOpenedAt(uri: String, timestamp: Long) = dao.updateLastOpenedAt(uri, timestamp)
    override suspend fun getLastPageIndex(uri: String): Int? = dao.getLastPageIndex(uri)
}
