package com.vic.inkflow.data.repository

import com.vic.inkflow.data.AppDatabase
import com.vic.inkflow.data.Folder
import com.vic.inkflow.data.FolderDao
import com.vic.inkflow.data.toModel
import com.vic.inkflow.data.toRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 分類（資料夾）資料存取邊界的 **Room 實作**。
 *
 * 介面在 `:shared`（`FolderRepository`），講共用文件模型；這裡是儲存的形狀。
 */
class RoomFolderRepository(
    private val db: AppDatabase,
    private val dao: FolderDao
) : FolderRepository {

    override suspend fun insert(folder: Folder) = dao.insert(folder.toRow())

    override fun getAllFolders(): Flow<List<Folder>> =
        dao.getAllFolders().map { rows -> rows.map { it.toModel() } }

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
