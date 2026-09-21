package com.vic.inkflow.data

import java.util.UUID

/**
 * Represents metadata for a single stroke.
 */
data class StrokeEntity(
    val id: String = UUID.randomUUID().toString(),
    val documentUri: String,
    val pageIndex: Int,
    val color: Int,
    val strokeWidth: Float,
    val boundsLeft: Float = 0f,
    val boundsTop: Float = 0f,
    val boundsRight: Float = 0f,
    val boundsBottom: Float = 0f,
    val isHighlighter: Boolean = false,
    val shapeType: String? = null
)

/**
 * Represents a single point within a stroke.
 */
data class PointEntity(
    val id: Long = 0,
    val strokeId: String,
    val x: Float,
    val y: Float,
    val width: Float = 0f
)

/**
 * Container for stroke with its points.
 */
data class StrokeWithPoints(
    val stroke: StrokeEntity,
    val points: List<PointEntity>
)
