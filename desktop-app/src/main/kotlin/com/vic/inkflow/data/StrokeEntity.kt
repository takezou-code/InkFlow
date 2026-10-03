package com.vic.inkflow.data

import java.util.UUID

/**
 * Stroke metadata.
 *
 * v3 (sync): `docY` was added. The tablet's S1 single-canvas coordinate system
 * stores `docY = pageIndex × stride + boundsTop` for every stroke; without it the
 * desktop cannot place a stroke in the continuous canvas, and the pre-v24 rows
 * that arrive over the wire would silently land at y=0.
 *
 * Nullable on the wire, exactly as the tablet stores it (NULL = pre-v24 data
 * that has not been backfilled yet). The desktop must not invent a value here —
 * the tablet backfills lazily using the live model height when the document is
 * opened, and guessing a stride on this side produces strokes in the wrong place.
 */
data class StrokeEntity(
    val id: String = UUID.randomUUID().toString(),
    val documentUri: String,
    val pageIndex: Int,
    val docY: Float? = null,
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
 * A single point within a stroke.
 *
 * Note the point ORDER matters — it is the polyline order. The tablet's Room
 * `@Relation` does not guarantee ordering, so the tablet sorts by row id before
 * serialising; the desktop must preserve the order it receives.
 */
data class PointEntity(
    val id: Long = 0,
    val strokeId: String,
    val x: Float,
    val y: Float,
    val width: Float = 0f
)

/** A stroke together with its ordered points. */
data class StrokeWithPoints(
    val stroke: StrokeEntity,
    val points: List<PointEntity>
)
