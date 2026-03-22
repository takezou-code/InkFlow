package com.vic.inkflow.data

import androidx.room.Entity

@Entity(tableName = "bookmarks", primaryKeys = ["documentUri", "pageIndex"])
data class BookmarkEntity(
    val documentUri: String,
    val pageIndex: Int
)
