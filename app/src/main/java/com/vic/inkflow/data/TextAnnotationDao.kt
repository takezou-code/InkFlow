package com.vic.inkflow.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TextAnnotationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(annotation: TextAnnotationEntity)

    @Query("SELECT * FROM text_annotations WHERE documentUri = :documentUri AND pageIndex = :pageIndex")
    fun getForPage(documentUri: String, pageIndex: Int): Flow<List<TextAnnotationEntity>>

    @Query("SELECT * FROM text_annotations WHERE documentUri = :documentUri AND pageIndex = :pageIndex")
    suspend fun getForPageSync(documentUri: String, pageIndex: Int): List<TextAnnotationEntity>

    @Query("SELECT * FROM text_annotations WHERE documentUri = :documentUri")
    suspend fun getAllForDocument(documentUri: String): List<TextAnnotationEntity>

    @Update
    suspend fun update(annotation: TextAnnotationEntity)

    @Query("DELETE FROM text_annotations WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM text_annotations WHERE documentUri = :documentUri AND pageIndex = :pageIndex")
    suspend fun deleteForPage(documentUri: String, pageIndex: Int)

    @Query("UPDATE text_annotations SET pageIndex = pageIndex - 1 WHERE documentUri = :documentUri AND pageIndex > :deletedPageIndex")
    suspend fun shiftPageIndicesDown(documentUri: String, deletedPageIndex: Int)

    @Query("UPDATE text_annotations SET pageIndex = pageIndex + :amount WHERE documentUri = :documentUri AND pageIndex >= :startingIndex")
    suspend fun shiftPageIndicesUp(documentUri: String, startingIndex: Int, amount: Int)

    @Query("UPDATE text_annotations SET pageIndex = :tempIndex WHERE documentUri = :documentUri AND pageIndex = :fromIndex")
    suspend fun moveToTempIndex(documentUri: String, fromIndex: Int, tempIndex: Int = -1)

    @Query("UPDATE text_annotations SET pageIndex = pageIndex - 1 WHERE documentUri = :documentUri AND pageIndex > :fromIndex AND pageIndex <= :toIndex")
    suspend fun shiftForMoveDown(documentUri: String, fromIndex: Int, toIndex: Int)

    @Query("UPDATE text_annotations SET pageIndex = pageIndex + 1 WHERE documentUri = :documentUri AND pageIndex >= :toIndex AND pageIndex < :fromIndex")
    suspend fun shiftForMoveUp(documentUri: String, fromIndex: Int, toIndex: Int)

    @Query("DELETE FROM text_annotations WHERE documentUri = :documentUri")
    suspend fun deleteForDocument(documentUri: String)

    // ── S1 單畫布：docY = pageIndex × stride + modelY（只寫不讀，見 StrokeDao）──

    /** 高度取 fontSize×2 寬容估計（多行不漏接；精確裁切在 S2 讀側）。 */
    @Query("""
        SELECT * FROM text_annotations
        WHERE documentUri = :documentUri
          AND docY IS NOT NULL AND docY <= :y1
          AND (docY + fontSize * 2) >= :y0
    """)
    suspend fun getForRange(documentUri: String, y0: Float, y1: Float): List<TextAnnotationEntity>

    @Query("UPDATE text_annotations SET docY = docY + :dy WHERE documentUri = :documentUri AND docY IS NOT NULL AND docY >= :yThreshold")
    suspend fun shiftDocYBelow(documentUri: String, yThreshold: Float, dy: Float): Int

    /** 頁搬移用：只平移 [y0, y1) 區間內的字（有 (documentUri, docY) 索引）。NULL 行不受影響。 */
    @Query("UPDATE text_annotations SET docY = docY + :dy WHERE documentUri = :documentUri AND docY IS NOT NULL AND docY >= :y0 AND docY < :y1")
    suspend fun shiftDocYRange(documentUri: String, y0: Float, y1: Float, dy: Float): Int

    @Query("SELECT COUNT(*) FROM text_annotations WHERE documentUri = :documentUri AND docY IS NULL")
    suspend fun countMissingDocY(documentUri: String): Int

    @Query("UPDATE text_annotations SET docY = pageIndex * :stride + modelY WHERE documentUri = :documentUri")
    suspend fun backfillTextDocY(documentUri: String, stride: Float): Int

    @Query("SELECT COUNT(*) FROM text_annotations WHERE documentUri = :documentUri AND ABS(docY - (pageIndex * :stride + modelY)) > 0.01")
    suspend fun countTextDocMismatch(documentUri: String, stride: Float): Int
}
