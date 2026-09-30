package com.vic.inkflow.data.repository

import com.vic.inkflow.data.AppDatabase
import com.vic.inkflow.data.FolderDao
import com.vic.inkflow.data.FolderEntity
import kotlinx.coroutines.flow.Flow

/** 分類（資料夾）的資料存取邊界。見 [StrokeRepository] 的說明。 */
interface FolderRepository {

    suspend fun insert(folder: FolderEntity)

    fun getAllFolders(): Flow<List<FolderEntity>>

    suspend fun rename(folderId: String, newName: String, updatedAt: Long = System.currentTimeMillis())
    suspend fun updateSortOrder(folderId: String, sortOrder: Int, updatedAt: Long = System.currentTimeMillis())

    suspend fun moveToParent(
        folderId: String,
        parentFolderId: String?,
        sortOrder: Int,
        updatedAt: Long = System.currentTimeMillis()
    )

    suspend fun getNextSortOrder(parentFolderId: String?): Int

    /** 遞迴取出自己＋所有後代。刪分類前必呼叫，否則留下孤兒子分類。 */
    suspend fun getFolderAndDescendantIds(folderId: String): List<String>

    suspend fun deleteByIds(folderIds: List<String>)
}

class RoomFolderRepository(
    private val db: AppDatabase,
    private val dao: FolderDao
) : FolderRepository {

    override suspend fun insert(folder: FolderEntity) = dao.insert(folder)
    override fun getAllFolders(): Flow<List<FolderEntity>> = dao.getAllFolders()
    override suspend fun rename(folderId: String, newName: String, updatedAt: Long) = dao.rename(folderId, newName, updatedAt)
    override suspend fun updateSortOrder(folderId: String, sortOrder: Int, updatedAt: Long) =
        dao.updateSortOrder(folderId, sortOrder, updatedAt)
    override suspend fun moveToParent(folderId: String, parentFolderId: String?, sortOrder: Int, updatedAt: Long) =
        dao.moveToParent(folderId, parentFolderId, sortOrder, updatedAt)
    override suspend fun getNextSortOrder(parentFolderId: String?): Int = dao.getNextSortOrder(parentFolderId)
    override suspend fun getFolderAndDescendantIds(folderId: String): List<String> =
        dao.getFolderAndDescendantIds(folderId)
    override suspend fun deleteByIds(folderIds: List<String>) = dao.deleteByIds(folderIds)
}
