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
}