package com.vic.inkflow.data.repository

import com.vic.inkflow.data.AppDatabase
import com.vic.inkflow.data.MergeVersion
import com.vic.inkflow.data.PageTextCount
import com.vic.inkflow.data.TextAnnotationDao
import com.vic.inkflow.data.TextAnnotationEntity
import kotlinx.coroutines.flow.Flow

/** 文字註記的資料存取邊界。見 [StrokeRepository] 的說明。 */
interface TextAnnotationRepository : PageShiftTarget {

    suspend fun insert(annotation: TextAnnotationEntity)
    suspend fun update(annotation: TextAnnotationEntity)

    fun getForPage(documentUri: String, pageIndex: Int): Flow<List<TextAnnotationEntity>>
    suspend fun getForPageSync(documentUri: String, pageIndex: Int): List<TextAnnotationEntity>
    suspend fun getAllForDocument(documentUri: String): List<TextAnnotationEntity>
    suspend fun getForRange(documentUri: String, y0: Float, y1: Float): List<TextAnnotationEntity>

    /**
     * Per-page note counts, for the `page_counts` sync verb.
     * See [StrokeRepository.countStrokesByPage] for why this exists.
     */
    suspend fun countTextsByPage(documentUri: String): List<PageTextCount>

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
    suspend fun backfillTextDocY(documentUri: String, stride: Float): Int
    suspend fun countTextDocMismatch(documentUri: String, stride: Float): Int

    // ── v6 併發合併（§15.1／§15.2／§15.4）───────────────────────────────
    suspend fun bumpTextVersion(id: String): Int
    suspend fun getTextVersions(documentUri: String): List<com.vic.inkflow.data.StrokeVersionRow>
    suspend fun getTextVersion(id: String): com.vic.inkflow.data.StrokeVersionRow?
    suspend fun countLiveTexts(documentUri: String): Int
    suspend fun tombstoneTexts(ids: List<String>): Int
    suspend fun reviveTexts(ids: List<String>): Int
}

class RoomTextAnnotationRepository(
    private val db: AppDatabase,
    private val dao: TextAnnotationDao
) : TextAnnotationRepository {

    override suspend fun insert(annotation: TextAnnotationEntity) = dao.insert(annotation)
    override suspend fun update(annotation: TextAnnotationEntity) = dao.update(annotation)
    override fun getForPage(documentUri: String, pageIndex: Int): Flow<List<TextAnnotationEntity>> =
        dao.getForPage(documentUri, pageIndex)
    override suspend fun getForPageSync(documentUri: String, pageIndex: Int) = dao.getForPageSync(documentUri, pageIndex)
    override suspend fun getAllForDocument(documentUri: String) = dao.getAllForDocument(documentUri)

    override suspend fun countTextsByPage(documentUri: String) = dao.countTextsByPage(documentUri)
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
    override suspend fun backfillTextDocY(documentUri: String, stride: Float): Int = dao.backfillTextDocY(documentUri, stride)
    override suspend fun countTextDocMismatch(documentUri: String, stride: Float): Int = dao.countTextDocMismatch(documentUri, stride)

    override suspend fun bumpTextVersion(id: String): Int = dao.bumpTextVersion(id, MergeVersion.nextNonce())
    override suspend fun getTextVersions(documentUri: String): List<com.vic.inkflow.data.StrokeVersionRow> = dao.getTextVersions(documentUri)
    override suspend fun getTextVersion(id: String): com.vic.inkflow.data.StrokeVersionRow? = dao.getTextVersion(id)
    override suspend fun countLiveTexts(documentUri: String): Int = dao.countLiveTexts(documentUri)
    override suspend fun tombstoneTexts(ids: List<String>): Int =
        dao.tombstoneTexts(ids, System.currentTimeMillis(), MergeVersion.nextNonce())
    override suspend fun reviveTexts(ids: List<String>): Int = dao.reviveTexts(ids)
}
