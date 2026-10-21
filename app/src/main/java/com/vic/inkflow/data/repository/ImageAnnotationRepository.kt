package com.vic.inkflow.data.repository

import com.vic.inkflow.data.AppDatabase
import com.vic.inkflow.data.ImageAnnotation
import com.vic.inkflow.data.ImageAnnotationDao
import com.vic.inkflow.data.toModel
import com.vic.inkflow.data.toRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 圖片註記資料存取邊界的 **Room 實作**。
 *
 * 介面在 `:shared`（`ImageAnnotationRepository`），講共用文件模型；這裡是儲存的形狀。
 */
class RoomImageAnnotationRepository(
    private val db: AppDatabase,
    private val dao: ImageAnnotationDao
) : ImageAnnotationRepository {

    override suspend fun insert(annotation: ImageAnnotation) = dao.insert(annotation.toRow())
    override suspend fun update(annotation: ImageAnnotation) = dao.update(annotation.toRow())

    override fun getForPage(documentUri: String, pageIndex: Int): Flow<List<ImageAnnotation>> =
        dao.getForPage(documentUri, pageIndex).map { rows -> rows.map { it.toModel() } }

    override suspend fun getForPageSync(documentUri: String, pageIndex: Int): List<ImageAnnotation> =
        dao.getForPageSync(documentUri, pageIndex).map { it.toModel() }

    override suspend fun getAllForDocument(documentUri: String): List<ImageAnnotation> =
        dao.getAllForDocument(documentUri).map { it.toModel() }

    override suspend fun getForRange(documentUri: String, y0: Float, y1: Float): List<ImageAnnotation> =
        dao.getForRange(documentUri, y0, y1).map { it.toModel() }

    override suspend fun deleteById(id: String): Int = dao.deleteById(id)
    override suspend fun deleteForPage(documentUri: String, pageIndex: Int) = dao.deleteForPage(documentUri, pageIndex)
    override suspend fun deleteForDocument(documentUri: String) = dao.deleteForDocument(documentUri)

    override suspend fun countByUri(uri: String) = dao.countByUri(uri)

    override suspend fun allUris() = dao.allUris()
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
