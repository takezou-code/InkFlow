package com.vic.inkflow.data.repository

import com.vic.inkflow.data.AppDatabase
import com.vic.inkflow.data.DocumentDao
import com.vic.inkflow.data.Document
import com.vic.inkflow.data.toModel
import com.vic.inkflow.data.toRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 文件的資料存取邊界的 **Room 實作**。
 *
 * 介面在 `:shared`（`com.vic.inkflow.data.repository.DocumentRepository`），講的是
 * **共用文件模型**；這裡是儲存的形狀（Room 的 row）。兩者之間的映射發生在**邊界**：
 * 進來的是模型、出去的是 row。
 *
 * 為什麼要分兩層：介面描述的是「文件長什麼樣」，不是「Android 的資料庫長什麼樣」。
 * 介面一旦講 Room 的 row，它就只能被 Android 用；講模型，桌面端（JDBC）與之後的
 * iOS（Room KMP）才能實作同一個介面——而編輯器核心（要下沉到 `:shared` 的那部分）
 * 只需要知道介面。
 */
class RoomDocumentRepository(
    private val db: AppDatabase,
    private val dao: DocumentDao
) : DocumentRepository {

    override suspend fun upsert(document: Document) = dao.upsert(document.toRow())

    override fun getAllDocuments(): Flow<List<Document>> =
        dao.getAllDocuments().map { rows -> rows.map { it.toModel() } }

    override suspend fun getAllDocumentsSync(): List<Document> =
        dao.getAllDocumentsSync().map { it.toModel() }

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
