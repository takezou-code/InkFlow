package com.vic.inkflow.data.repository

import androidx.room.withTransaction
import com.vic.inkflow.data.AppDatabase
import com.vic.inkflow.data.MergeVersion
import com.vic.inkflow.data.PageTextCount
import com.vic.inkflow.data.StrokeVersionRow
import com.vic.inkflow.data.TextAnnotation
import com.vic.inkflow.data.TextAnnotationDao
import com.vic.inkflow.data.toModel
import com.vic.inkflow.data.toRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 文字註記資料存取邊界的 **Room 實作**。
 *
 * 介面在 `:shared`（`TextAnnotationRepository`），講共用文件模型；這裡是儲存的形狀。
 */
class RoomTextAnnotationRepository(
    private val db: AppDatabase,
    private val dao: TextAnnotationDao
) : TextAnnotationRepository {

    override suspend fun insert(annotation: TextAnnotation) = dao.insert(annotation.toRow())
    override suspend fun update(annotation: TextAnnotation) = dao.update(annotation.toRow())

    override fun getForPage(documentUri: String, pageIndex: Int): Flow<List<TextAnnotation>> =
        dao.getForPage(documentUri, pageIndex).map { rows -> rows.map { it.toModel() } }

    override suspend fun getForPageSync(documentUri: String, pageIndex: Int): List<TextAnnotation> =
        dao.getForPageSync(documentUri, pageIndex).map { it.toModel() }

    override suspend fun getAllForDocument(documentUri: String): List<TextAnnotation> =
        dao.getAllForDocument(documentUri).map { it.toModel() }

    override suspend fun countTextsByPage(documentUri: String): List<PageTextCount> =
        dao.countTextsByPage(documentUri)

    override suspend fun getForRange(documentUri: String, y0: Float, y1: Float): List<TextAnnotation> =
        dao.getForRange(documentUri, y0, y1).map { it.toModel() }

    override suspend fun deleteById(id: String): Int = dao.deleteById(id)

    override suspend fun tombstoneByIds(ids: List<String>): Int =
        if (ids.isEmpty()) 0
        else dao.tombstoneTexts(ids, System.currentTimeMillis(), MergeVersion.nextNonce())

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
    override suspend fun backfillTextDocY(documentUri: String, stride: Float): Int = dao.backfillTextDocY(documentUri, stride)
    override suspend fun countTextDocMismatch(documentUri: String, stride: Float): Int = dao.countTextDocMismatch(documentUri, stride)

    override suspend fun bumpTextVersion(id: String): Int = dao.bumpTextVersion(id, MergeVersion.nextNonce())

    /**
     * 改寫＋序號前進（文字修改的寫入漏斗）。
     *
     * 與筆跡的 [StrokeRepository.replaceStrokeSnapshots] 同一套理由：呼叫端傳進來的
     * entity 帶舊 version，直接 update 會讓序號停在原地，仲裁看不出改過。
     * update 與 bump 必須在同一交易里（update 先、bump 後）。
     */
    override suspend fun updateAndBump(annotation: TextAnnotation) {
        db.withTransaction {
            dao.update(annotation.toRow())
            dao.bumpTextVersion(annotation.id, MergeVersion.nextNonce())
        }
    }

    override suspend fun getTextVersions(documentUri: String): List<StrokeVersionRow> = dao.getTextVersions(documentUri)
    override suspend fun getTextVersion(id: String): StrokeVersionRow? = dao.getTextVersion(id)
    override suspend fun countLiveTexts(documentUri: String): Int = dao.countLiveTexts(documentUri)

    override suspend fun purgeTombstonedTexts(cutoff: Long): Int = dao.purgeTombstonedTexts(cutoff)
    override suspend fun tombstoneTexts(ids: List<String>): Int =
        dao.tombstoneTexts(ids, System.currentTimeMillis(), MergeVersion.nextNonce())
    override suspend fun reviveTexts(ids: List<String>): Int = dao.reviveTexts(ids)
}
