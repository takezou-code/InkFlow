package com.vic.inkflow.data.repository

import com.vic.inkflow.data.AppDatabase
import com.vic.inkflow.data.ImageAnnotationDao
import com.vic.inkflow.data.ImageAnnotationEntity
import kotlinx.coroutines.flow.Flow

/** 圖片註記的資料存取邊界。見 [StrokeRepository] 的說明。 */
interface ImageAnnotationRepository : PageShiftTarget {

    suspend fun insert(annotation: ImageAnnotationEntity)
    suspend fun update(annotation: ImageAnnotationEntity)

    fun getForPage(documentUri: String, pageIndex: Int): Flow<List<ImageAnnotationEntity>>
    suspend fun getForPageSync(documentUri: String, pageIndex: Int): List<ImageAnnotationEntity>
    suspend fun getAllForDocument(documentUri: String): List<ImageAnnotationEntity>
    suspend fun getForRange(documentUri: String, y0: Float, y1: Float): List<ImageAnnotationEntity>

    suspend fun deleteById(id: String): Int
    suspend fun deleteForPage(documentUri: String, pageIndex: Int)
    suspend fun deleteForDocument(documentUri: String)

    // ── 頁操作五件套 ──────────────────────────────────────────────────────
    suspend fun shiftPageIndicesDown(documentUri: String, deletedPageIndex: Int)
    suspend fun shiftPageIndicesUp(documentUri: String, startingIndex: Int, amount: Int)

    // ── S1 單畫布 docY（= pageIndex × stride + modelY）─────────────────────
    suspend fun shiftDocYBelow(documentUri: String, yThreshold: Float, dy: Float): Int
    suspend fun shiftDocYRange(documentUri: String, y0: Float, y1: Float, dy: Float): Int
    suspend fun countMissingDocY(documentUri: String): Int
    suspend fun backfillImageDocY(documentUri: String, stride: Float): Int
    suspend fun countImageDocMismatch(documentUri: String, stride: Float): Int
}

class RoomImageAnnotationRepository(
    private val db: AppDatabase,
    private val dao: ImageAnnotationDao
) : ImageAnnotationRepository {

    override suspend fun insert(annotation: ImageAnnotationEntity) = dao.insert(annotation)
    override suspend fun update(annotation: ImageAnnotationEntity) = dao.update(annotation)
    override fun getForPage(documentUri: String, pageIndex: Int): Flow<List<ImageAnnotationEntity>> =
        dao.getForPage(documentUri, pageIndex)
    override suspend fun getForPageSync(documentUri: String, pageIndex: Int) = dao.getForPageSync(documentUri, pageIndex)
    override suspend fun getAllForDocument(documentUri: String) = dao.getAllForDocument(documentUri)
    override suspend fun getForRange(documentUri: String, y0: Float, y1: Float) = dao.getForRange(documentUri, y0, y1)
    override suspend fun deleteById(id: String): Int = dao.deleteById(id)
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
    override suspend fun shiftDocYBelow(documentUri: String, yThreshold: Float, dy: Float): Int =
        dao.shiftDocYBelow(documentUri, yThreshold, dy)
    override suspend fun shiftDocYRange(documentUri: String, y0: Float, y1: Float, dy: Float): Int =
        dao.shiftDocYRange(documentUri, y0, y1, dy)
    override suspend fun countMissingDocY(documentUri: String): Int = dao.countMissingDocY(documentUri)
    override suspend fun backfillImageDocY(documentUri: String, stride: Float): Int = dao.backfillImageDocY(documentUri, stride)
    override suspend fun countImageDocMismatch(documentUri: String, stride: Float): Int = dao.countImageDocMismatch(documentUri, stride)
}
