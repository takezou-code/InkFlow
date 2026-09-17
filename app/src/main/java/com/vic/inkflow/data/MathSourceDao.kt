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
}
