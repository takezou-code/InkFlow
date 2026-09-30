package com.vic.inkflow.data.repository

import com.vic.inkflow.data.AppDatabase
import com.vic.inkflow.data.BookmarkDao
import com.vic.inkflow.data.BookmarkEntity
import kotlinx.coroutines.flow.Flow

/** 書籤的資料存取邊界。見 [StrokeRepository] 的說明。 */
interface BookmarkRepository : PageShiftTarget {

    fun getBookmarkedPages(documentUri: String): Flow<List<Int>>

    suspend fun insert(bookmark: BookmarkEntity)
    suspend fun deleteForPage(documentUri: String, pageIndex: Int)
    suspend fun deleteForDocument(documentUri: String)

    // ── 頁操作五件套 ──────────────────────────────────────────────────────
    suspend fun shiftPageIndicesDown(documentUri: String, deletedPageIndex: Int)
    suspend fun shiftPageIndicesUp(documentUri: String, startingIndex: Int, amount: Int)
}

class RoomBookmarkRepository(
    private val db: AppDatabase,
    private val dao: BookmarkDao
) : BookmarkRepository {

    override fun getBookmarkedPages(documentUri: String): Flow<List<Int>> = dao.getBookmarkedPages(documentUri)
    override suspend fun insert(bookmark: BookmarkEntity) = dao.insert(bookmark)
    override suspend fun deleteForPage(documentUri: String, pageIndex: Int) = dao.deleteForPage(documentUri, pageIndex)
    override suspend fun deleteForDocument(documentUri: String) = dao.deleteForDocument(documentUri)
    override suspend fun shiftPageIndicesDown(documentUri: String, deletedPageIndex: Int) =
        dao.shiftPageIndicesDown(documentUri, deletedPageIndex)
    override suspend fun shiftPageIndicesUp(documentUri: String, startingIndex: Int, amount: Int) =
        dao.shiftPageIndicesUp(documentUri, startingIndex, amount)
    override suspend fun moveToTempIndex(documentUri: String, fromIndex: Int, tempIndex: Int) =
        dao.moveToTempIndex(documentUri, fromIndex, tempIndex)
    override suspend fun shiftForMoveDown(documentUri: String, fromIndex: Int, toIndex: Int) =
        dao.shiftForMoveDown(documentUri, fromIndex, toIndex)
    override suspend fun shiftForMoveUp(documentUri: String, fromIndex: Int, toIndex: Int) =
        dao.shiftForMoveUp(documentUri, fromIndex, toIndex)
}
