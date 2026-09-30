package com.vic.inkflow.data.repository

import com.vic.inkflow.data.AppDatabase
import com.vic.inkflow.data.MathSourceDao
import com.vic.inkflow.data.MathSourceEntity

/** 公式來源（M7 sidecar）的資料存取邊界。見 [StrokeRepository] 的說明。 */
interface MathSourceRepository : PageShiftTarget {

    suspend fun insert(source: MathSourceEntity)
    suspend fun getByImageUri(imageUri: String): MathSourceEntity?
    suspend fun getForPage(documentUri: String, pageIndex: Int): List<MathSourceEntity>
    suspend fun deleteByImageUri(imageUri: String): Int
    suspend fun deleteForPage(documentUri: String, pageIndex: Int)
    suspend fun deleteForDocument(documentUri: String)

    // ── 頁操作五件套 ──────────────────────────────────────────────────────
    suspend fun shiftPageIndicesDown(documentUri: String, deletedPageIndex: Int)
    suspend fun shiftPageIndicesUp(documentUri: String, startingIndex: Int, amount: Int)
}

class RoomMathSourceRepository(
    private val db: AppDatabase,
    private val dao: MathSourceDao
) : MathSourceRepository {

    override suspend fun insert(source: MathSourceEntity) = dao.insert(source)
    override suspend fun getByImageUri(imageUri: String): MathSourceEntity? = dao.getByImageUri(imageUri)
    override suspend fun getForPage(documentUri: String, pageIndex: Int): List<MathSourceEntity> =
        dao.getForPage(documentUri, pageIndex)
    override suspend fun deleteByImageUri(imageUri: String): Int = dao.deleteByImageUri(imageUri)
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
