package com.vic.inkflow.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MathSourceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: MathSourceEntity)

    @Query("SELECT * FROM math_sources WHERE imageUri = :imageUri LIMIT 1")
    suspend fun getByImageUri(imageUri: String): MathSourceEntity?

    @Query("SELECT * FROM math_sources WHERE documentUri = :documentUri AND pageIndex = :pageIndex")
    suspend fun getForPage(documentUri: String, pageIndex: Int): List<MathSourceEntity>

    @Query("DELETE FROM math_sources WHERE imageUri = :imageUri")
    suspend fun deleteByImageUri(imageUri: String): Int

    @Query("DELETE FROM math_sources WHERE documentUri = :documentUri")
    suspend fun deleteForDocument(documentUri: String)

    // ── 頁操作三件套（與 Stroke/Text/Image/Bookmark 四表對齊）：公式 TeX 跟頁走 ──

    @Query("DELETE FROM math_sources WHERE documentUri = :documentUri AND pageIndex = :pageIndex")
    suspend fun deleteForPage(documentUri: String, pageIndex: Int)

    @Query("UPDATE math_sources SET pageIndex = pageIndex - 1 WHERE documentUri = :documentUri AND pageIndex > :deletedPageIndex")
    suspend fun shiftPageIndicesDown(documentUri: String, deletedPageIndex: Int)

    @Query("UPDATE math_sources SET pageIndex = pageIndex + :amount WHERE documentUri = :documentUri AND pageIndex >= :startingIndex")
    suspend fun shiftPageIndicesUp(documentUri: String, startingIndex: Int, amount: Int)

    @Query("UPDATE math_sources SET pageIndex = :tempIndex WHERE documentUri = :documentUri AND pageIndex = :fromIndex")
    suspend fun moveToTempIndex(documentUri: String, fromIndex: Int, tempIndex: Int = -1)

    @Query("UPDATE math_sources SET pageIndex = pageIndex - 1 WHERE documentUri = :documentUri AND pageIndex > :fromIndex AND pageIndex <= :toIndex")
    suspend fun shiftForMoveDown(documentUri: String, fromIndex: Int, toIndex: Int)

    @Query("UPDATE math_sources SET pageIndex = pageIndex + 1 WHERE documentUri = :documentUri AND pageIndex >= :toIndex AND pageIndex < :fromIndex")
    suspend fun shiftForMoveUp(documentUri: String, fromIndex: Int, toIndex: Int)
}
