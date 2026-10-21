package com.vic.inkflow.data.repository

import com.vic.inkflow.data.ImageAnnotation
import com.vic.inkflow.data.PageTextCount
import com.vic.inkflow.data.StrokeVersionRow
import com.vic.inkflow.data.TextAnnotation
import kotlinx.coroutines.flow.Flow

/**
 * 文字註記的資料存取邊界。見 [StrokeRepository] 的說明。
 *
 * 2026-10-21 搬到 `:shared` 並改用**共用文件模型**：介面描述的是「文件長什麼樣」，
 * 不是「Android 的資料庫長什麼樣」。
 */
interface TextAnnotationRepository : PageShiftTarget {

    suspend fun insert(annotation: TextAnnotation)
    suspend fun update(annotation: TextAnnotation)

    fun getForPage(documentUri: String, pageIndex: Int): Flow<List<TextAnnotation>>
    suspend fun getForPageSync(documentUri: String, pageIndex: Int): List<TextAnnotation>
    suspend fun getAllForDocument(documentUri: String): List<TextAnnotation>
    suspend fun getForRange(documentUri: String, y0: Float, y1: Float): List<TextAnnotation>

    /**
     * Per-page note counts, for the `page_counts` sync verb.
     * See [StrokeRepository.countStrokesByPage] for why this exists.
     */
    suspend fun countTextsByPage(documentUri: String): List<PageTextCount>

    suspend fun deleteById(id: String): Int

    /**
     * 刪除「使用者看得見的」文字註解：寫墓碑而不是硬刪除（§15.4）。
     *
     * 與筆跡同一套理由與同一個警告 —— 見 [StrokeRepository.tombstoneStrokesByIds]。
     * 不要把所有 [deleteById] 換成這個：undo/redo 一筆「剛新增」時，那筆從來沒被
     * 同步看過，硬刪才對。
     */
    suspend fun tombstoneByIds(ids: List<String>): Int

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

    /**
     * 改寫＋序號前進（文字修改的寫入漏斗）。
     * 用這個，不要直接呼叫 update —— 序號不動的話仲裁看不出這筆被改過（§15.1）。
     */
    suspend fun updateAndBump(annotation: TextAnnotation)
    suspend fun getTextVersions(documentUri: String): List<StrokeVersionRow>
    suspend fun getTextVersion(id: String): StrokeVersionRow?
    suspend fun countLiveTexts(documentUri: String): Int
    /** 回收比 [cutoff] 更早的墓碑。回傳刪掉幾列。 */
    suspend fun purgeTombstonedTexts(cutoff: Long): Int
    suspend fun tombstoneTexts(ids: List<String>): Int
    suspend fun reviveTexts(ids: List<String>): Int
}

/**
 * 圖片註記的資料存取邊界。見 [StrokeRepository] 的說明。
 *
 * 2026-10-21 搬到 `:shared` 並改用**共用文件模型**。
 */
interface ImageAnnotationRepository : PageShiftTarget {

    suspend fun insert(annotation: ImageAnnotation)
    suspend fun update(annotation: ImageAnnotation)

    fun getForPage(documentUri: String, pageIndex: Int): Flow<List<ImageAnnotation>>
    suspend fun getForPageSync(documentUri: String, pageIndex: Int): List<ImageAnnotation>
    suspend fun getAllForDocument(documentUri: String): List<ImageAnnotation>
    suspend fun getForRange(documentUri: String, y0: Float, y1: Float): List<ImageAnnotation>

    suspend fun deleteById(id: String): Int
    suspend fun deleteForPage(documentUri: String, pageIndex: Int)
    suspend fun deleteForDocument(documentUri: String)

    /** 還有多少列指向這個圖檔 uri；0 = 可以安全刪檔。見 [ImageAnnotationDao.countByUri]。 */
    suspend fun countByUri(uri: String): Int

    /** 全庫圖檔 uri（去重）；開機清掃比對用。見 [ImageAnnotationDao.allUris]。 */
    suspend fun allUris(): List<String>

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
