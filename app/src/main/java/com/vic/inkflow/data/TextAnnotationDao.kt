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
}
