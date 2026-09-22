package com.vic.inkflow.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface StrokeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStroke(stroke: StrokeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoints(points: List<PointEntity>)

    @Transaction
    @Query("SELECT * FROM strokes WHERE documentUri = :documentUri AND pageIndex = :pageIndex")
    fun getStrokesForPage(documentUri: String, pageIndex: Int): Flow<List<StrokeWithPoints>>

    @Transaction
    @Query("SELECT * FROM strokes WHERE documentUri = :documentUri AND pageIndex = :pageIndex")
    suspend fun getStrokesForPageSync(documentUri: String, pageIndex: Int): List<StrokeWithPoints>

    @Transaction
    @Query("SELECT * FROM strokes WHERE documentUri = :documentUri")
    suspend fun getAllStrokesForDocument(documentUri: String): List<StrokeWithPoints>

    @Query("DELETE FROM strokes WHERE id IN (:strokeIds)")
    suspend fun deleteStrokesByIds(strokeIds: List<String>): Int

    @Query("DELETE FROM strokes WHERE documentUri = :documentUri AND pageIndex = :pageIndex")
    suspend fun clearPage(documentUri: String, pageIndex: Int): Int

    @Query("UPDATE strokes SET pageIndex = pageIndex - 1 WHERE documentUri = :documentUri AND pageIndex > :deletedPageIndex")
    suspend fun shiftPageIndicesDown(documentUri: String, deletedPageIndex: Int)

    @Query("UPDATE strokes SET pageIndex = pageIndex + :amount WHERE documentUri = :documentUri AND pageIndex >= :startingIndex")
    suspend fun shiftPageIndicesUp(documentUri: String, startingIndex: Int, amount: Int)

    // For moving a page: Step 1 = change moved page to -1; Step 2 = shift others; Step 3 = change -1 to toIndex
    @Query("UPDATE strokes SET pageIndex = :tempIndex WHERE documentUri = :documentUri AND pageIndex = :fromIndex")
    suspend fun moveToTempIndex(documentUri: String, fromIndex: Int, tempIndex: Int = -1)

    @Query("UPDATE strokes SET pageIndex = pageIndex - 1 WHERE documentUri = :documentUri AND pageIndex > :fromIndex AND pageIndex <= :toIndex")
    suspend fun shiftForMoveDown(documentUri: String, fromIndex: Int, toIndex: Int)

    @Query("UPDATE strokes SET pageIndex = pageIndex + 1 WHERE documentUri = :documentUri AND pageIndex >= :toIndex AND pageIndex < :fromIndex")
    suspend fun shiftForMoveUp(documentUri: String, fromIndex: Int, toIndex: Int)

    @Query("DELETE FROM strokes WHERE documentUri = :documentUri")
    suspend fun deleteStrokesForDocument(documentUri: String)

    @Query("DELETE FROM points WHERE strokeId = :strokeId")
    suspend fun deletePointsForStroke(strokeId: String)

    // ── S1 單畫布：文件座標（docY = pageIndex × stride + boundsTop）─────────
    // S1 只寫不讀：回填＋新寫雙寫；範圍讀切換在 S2。

    /** 與 y 範圍相交的筆（S2 主查詢；S1 僅做一致性對帳）。 */
    @Transaction
    @Query("""
        SELECT * FROM strokes
        WHERE documentUri = :documentUri
          AND docY IS NOT NULL AND docY <= :y1
          AND (docY + (boundsBottom - boundsTop)) >= :y0
    """)
    suspend fun getStrokesForRange(documentUri: String, y0: Float, y1: Float): List<StrokeWithPoints>

    /** 頁管理（S4 用）：把 y 閾值以下的墨整批平移。NULL 行不受影響。 */
    @Query("UPDATE strokes SET docY = docY + :dy WHERE documentUri = :documentUri AND docY IS NOT NULL AND docY >= :yThreshold")
    suspend fun shiftDocYBelow(documentUri: String, yThreshold: Float, dy: Float): Int

    /** 頁搬移用：只平移 [y0, y1) 區間內的墨（有 (documentUri, docY) 索引）。NULL 行不受影響。 */
    @Query("UPDATE strokes SET docY = docY + :dy WHERE documentUri = :documentUri AND docY IS NOT NULL AND docY >= :y0 AND docY < :y1")
    suspend fun shiftDocYRange(documentUri: String, y0: Float, y1: Float, dy: Float): Int

    @Query("SELECT COUNT(*) FROM strokes WHERE documentUri = :documentUri AND docY IS NULL")
    suspend fun countMissingDocY(documentUri: String): Int

    /** 冪等全量重算（無 NULL 守衛）：docY = pageIndex × stride + boundsTop。
     * 每次開文件跑一次，頁增刪/紙改尺寸的陳舊值自動修正，無需碰 PdfViewModel。 */
    @Query("UPDATE strokes SET docY = pageIndex * :stride + boundsTop WHERE documentUri = :documentUri")
    suspend fun backfillStrokeDocY(documentUri: String, stride: Float): Int

    /** 不變式抽查：回填公式不合的列數（容差 0.01 防浮點噪聲；>0 即壞）。 */
    @Query("SELECT COUNT(*) FROM strokes WHERE documentUri = :documentUri AND ABS(docY - (pageIndex * :stride + boundsTop)) > 0.01")
    suspend fun countStrokeDocMismatch(documentUri: String, stride: Float): Int
}