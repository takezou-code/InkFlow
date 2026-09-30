package com.inkflow.windows.data

import kotlinx.serialization.Serializable

@Serializable
data class Document(
    val id: Long,
    val title: String,
    val filePath: String,
    val category: String = "未分類",
    val lastModified: Long = System.currentTimeMillis(),
    val pageCount: Int = 0
)

@Serializable
data class Stroke(
    val id: Long,
    val documentId: Long,
    val pageNumber: Int,
    val points: List<Point>,
    val color: Int,
    val strokeWidth: Float,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class Point(
    val x: Float,
    val y: Float,
    val pressure: Float = 1.0f
)
