package com.vic.inkflow.data.repository

import com.vic.inkflow.data.Document
import com.vic.inkflow.data.Folder
import kotlinx.coroutines.flow.Flow

/**
 * 文件的資料存取邊界。
 *
 * [updateLastOpenedAt] 同時是同步協定的衝突解決時間戳（SYNC_PROTOCOL §6），
 * 所以它不只是「順便更新一下」——任何改動文件內容的路徑都必須碰到它，
 * 否則桌面端永遠看不到更新。改這個方法前先看那份協定。
 *
 * 2026-10-21 搬到 `:shared` 並改用**共用文件模型**。
 */
interface DocumentRepository {

    suspend fun upsert(document: Document)

    fun getAllDocuments(): Flow<List<Document>>
    suspend fun getAllDocumentsSync(): List<Document>

    suspend fun delete(uri: String): Int
    suspend fun updateLastPage(uri: String, pageIndex: Int)
    suspend fun renameDocument(uri: String, newName: String)
    suspend fun updateFavoriteStatus(uri: String, isFavorite: Boolean)
    suspend fun updateFolder(uri: String, folderId: String?)
    suspend fun moveDocumentsToFolder(uris: List<String>, folderId: String?)

    /** 刪分類時把底下文件的 folderId 清成 NULL（FK 是 SET NULL，但顯式呼叫較可控）。 */
    suspend fun clearFolderAssignmentsInFolders(folderIds: List<String>)

    /** 同步協定的衝突時間戳。預設帶現在時間是刻意的，不要改成必填。 */
    suspend fun updateLastOpenedAt(uri: String, timestamp: Long = currentTimeMillis())

    suspend fun getLastPageIndex(uri: String): Int?
}

/**
 * 分類（資料夾）的資料存取邊界。見 [StrokeRepository] 的說明。
 *
 * 2026-10-21 搬到 `:shared` 並改用**共用文件模型**。
 */
interface FolderRepository {

    suspend fun insert(folder: Folder)

    fun getAllFolders(): Flow<List<Folder>>

    suspend fun rename(folderId: String, newName: String, updatedAt: Long = currentTimeMillis())
    suspend fun updateSortOrder(folderId: String, sortOrder: Int, updatedAt: Long = currentTimeMillis())

    suspend fun moveToParent(
        folderId: String,
        parentFolderId: String?,
        sortOrder: Int,
        updatedAt: Long = currentTimeMillis()
    )

    suspend fun getNextSortOrder(parentFolderId: String?): Int

    /** 遞迴取出自己＋所有後代。刪分類前必呼叫，否則留下孤兒子分類。 */
    suspend fun getFolderAndDescendantIds(folderId: String): List<String>

    suspend fun deleteByIds(folderIds: List<String>)
}

/**
 * 「現在」是幾毫秒。
 *
 * The only platform primitive the shared store interfaces need: the tablet and the
 * desktop both default a few timestamps to "now".
 *
 * `kotlin.time.Clock.System.now()` is in the **common** stdlib, so this needs no
 * `expect`/`actual` and no per-platform clock — which is exactly why it is written
 * this way: a "current time" default that has to be re-implemented per platform is
 * a place where two devices can quietly disagree.
 */
internal fun currentTimeMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
