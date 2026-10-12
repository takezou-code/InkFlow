package com.vic.inkflow.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ImageAnnotationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(annotation: ImageAnnotationEntity)

    @Update
    suspend fun update(annotation: ImageAnnotationEntity)

    @Query("SELECT * FROM image_annotations WHERE documentUri = :documentUri AND pageIndex = :pageIndex")
    fun getForPage(documentUri: String, pageIndex: Int): Flow<List<ImageAnnotationEntity>>

    @Query("SELECT * FROM image_annotations WHERE documentUri = :documentUri AND pageIndex = :pageIndex")
    suspend fun getForPageSync(documentUri: String, pageIndex: Int): List<ImageAnnotationEntity>

    @Query("SELECT * FROM image_annotations WHERE documentUri = :documentUri")
    suspend fun getAllForDocument(documentUri: String): List<ImageAnnotationEntity>

    @Query("DELETE FROM image_annotations WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM image_annotations WHERE documentUri = :documentUri AND pageIndex = :pageIndex")
    suspend fun deleteForPage(documentUri: String, pageIndex: Int)

    @Query("UPDATE image_annotations SET pageIndex = pageIndex - 1 WHERE documentUri = :documentUri AND pageIndex > :deletedPageIndex")
    suspend fun shiftPageIndicesDown(documentUri: String, deletedPageIndex: Int)

    @Query("UPDATE image_annotations SET pageIndex = pageIndex + :amount WHERE documentUri = :documentUri AND pageIndex >= :startingIndex")
    suspend fun shiftPageIndicesUp(documentUri: String, startingIndex: Int, amount: Int)

    @Query("UPDATE image_annotations SET pageIndex = :tempIndex WHERE documentUri = :documentUri AND pageIndex = :fromIndex")
    suspend fun moveToTempIndex(documentUri: String, fromIndex: Int, tempIndex: Int = -1)

    @Query("UPDATE image_annotations SET pageIndex = pageIndex - 1 WHERE documentUri = :documentUri AND pageIndex > :fromIndex AND pageIndex <= :toIndex")
    suspend fun shiftForMoveDown(documentUri: String, fromIndex: Int, toIndex: Int)

    @Query("UPDATE image_annotations SET pageIndex = pageIndex + 1 WHERE documentUri = :documentUri AND pageIndex >= :toIndex AND pageIndex < :fromIndex")
    suspend fun shiftForMoveUp(documentUri: String, fromIndex: Int, toIndex: Int)

    @Query("DELETE FROM image_annotations WHERE documentUri = :documentUri")
    suspend fun deleteForDocument(documentUri: String)

    /**
     * 還有多少列仍然指向這個圖檔 uri。
     *
     * 資產回收用：刪除一份文件時，它帶走的 image_annotations 列會先被交易刪掉，
     * 這時才知道「沒人再引用了」——但套圈貼（DrawCommand.AddSelectionCopies）會讓
     * **多份文件共用同一個 uri 字串**，直接刪檔會把別人正在看的圖弄壞。
     * 刪檔前必須回頭確認一次。
     *
     * math_sources.imageUri 雖也存同一個路徑，但公式圖必然同時是 image_annotations
     * 的一列（AiImportFlow 先 insertImportedImage 再寫 TeX sidecar），所以只查這一張表
     * 就足以涵蓋兩個引用來源。
     */
    @Query("SELECT COUNT(*) FROM image_annotations WHERE uri = :uri")
    suspend fun countByUri(uri: String): Int

    /**
     * 全庫圖檔 uri 清單（去重）：開機清掃用。
     *
     * 磁碟上的圖（貼圖／公式／裁圖）只有被這張表指著才算活著；掃描 app 目錄時，
     * 不在這份清單裡的圖檔就是孤兒（單張刪除、復原、拷貝中斷都會產生，見
     * PageOpJournal.sweepUnreferencedFiles）。只取 uri 欄，不碰點不碰座標。
     */
    @Query("SELECT DISTINCT uri FROM image_annotations")
    suspend fun allUris(): List<String>

    // ── S1 單畫布：docY = pageIndex × stride + modelY（只寫不讀，見 StrokeDao）──

    @Query("""
        SELECT * FROM image_annotations
        WHERE documentUri = :documentUri
          AND docY IS NOT NULL AND docY <= :y1
          AND (docY + modelHeight) >= :y0
    """)
    suspend fun getForRange(documentUri: String, y0: Float, y1: Float): List<ImageAnnotationEntity>

    @Query("UPDATE image_annotations SET docY = docY + :dy WHERE documentUri = :documentUri AND docY IS NOT NULL AND docY >= :yThreshold")
    suspend fun shiftDocYBelow(documentUri: String, yThreshold: Float, dy: Float): Int

    /** 頁搬移用：只平移 [y0, y1) 區間內的圖（有 (documentUri, docY) 索引）。NULL 行不受影響。 */
    @Query("UPDATE image_annotations SET docY = docY + :dy WHERE documentUri = :documentUri AND docY IS NOT NULL AND docY >= :y0 AND docY < :y1")
    suspend fun shiftDocYRange(documentUri: String, y0: Float, y1: Float, dy: Float): Int

    @Query("SELECT COUNT(*) FROM image_annotations WHERE documentUri = :documentUri AND docY IS NULL")
    suspend fun countMissingDocY(documentUri: String): Int

    @Query("UPDATE image_annotations SET docY = pageIndex * :stride + modelY WHERE documentUri = :documentUri")
    suspend fun backfillImageDocY(documentUri: String, stride: Float): Int

    @Query("SELECT COUNT(*) FROM image_annotations WHERE documentUri = :documentUri AND ABS(docY - (pageIndex * :stride + modelY)) > 0.01")
    suspend fun countImageDocMismatch(documentUri: String, stride: Float): Int
}
