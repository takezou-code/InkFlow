package com.vic.inkflow.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "documents",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index(value = ["folderId"])]
)
data class DocumentEntity(
    @PrimaryKey
    val uri: String,
    val displayName: String,
    val lastOpenedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val lastPageIndex: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val isFavorite: Boolean = false,
    @ColumnInfo(defaultValue = "NULL")
    val folderId: String? = null,
    /**
     * Non-null while the document sits in the trash (epoch millis when it was
     * moved there). Trashed rows are hidden from every library/sync/backup query
     * and only purged — with their files and annotations — after the retention
     * window. This is local-only: the sync protocol has no document-level ops.
     */
    @ColumnInfo(defaultValue = "NULL")
    val deletedAt: Long? = null
)
