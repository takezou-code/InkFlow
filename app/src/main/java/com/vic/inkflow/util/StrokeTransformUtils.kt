package com.vic.inkflow.util

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.vic.inkflow.data.PointEntity
import com.vic.inkflow.data.StrokeEntity
import com.vic.inkflow.data.StrokeWithPoints

object StrokeTransformUtils {
    private const val MIN_SCALE = 0.2f

    fun computeSelectionBounds(strokes: List<StrokeWithPoints>): Rect? {
        if (strokes.isEmpty()) return null
        val left = strokes.minOf { it.stroke.boundsLeft }
        val top = strokes.minOf { it.stroke.boundsTop }
        val right = strokes.maxOf { it.stroke.boundsRight }
        val bottom = strokes.maxOf { it.stroke.boundsBottom }
        return Rect(left, top, right, bottom)
    }

    fun clampUniformScale(scale: Float): Float = scale.coerceAtLeast(MIN_SCALE)

    fun transformStrokes(
        strokes: List<StrokeWithPoints>,
        translation: Offset = Offset.Zero,
        scale: Float = 1f,
        anchor: Offset = Offset.Zero
    ): List<StrokeWithPoints> = strokes.map { transformStroke(it, translation, scale, anchor) }

    fun transformStroke(
        strokeWithPoints: StrokeWithPoints,
        translation: Offset = Offset.Zero,
        scale: Float = 1f,
        anchor: Offset = Offset.Zero
    ): StrokeWithPoints {
        val clampedScale = clampUniformScale(scale)
        val transformedPoints = strokeWithPoints.points.map { pt ->
            val transformed = transformPoint(Offset(pt.x, pt.y), translation, clampedScale, anchor)
            pt.copy(
                x = transformed.x,
                y = transformed.y,
                width = pt.width * clampedScale
            )
        }
        val bounds = computeStrokeBounds(strokeWithPoints.stroke, transformedPoints)
        val transformedStroke = strokeWithPoints.stroke.copy(
            strokeWidth = strokeWithPoints.stroke.strokeWidth * clampedScale,
            boundsLeft = bounds.left,
            boundsTop = bounds.top,
            boundsRight = bounds.right,
            boundsBottom = bounds.bottom
        )
        return StrokeWithPoints(transformedStroke, transformedPoints)
    }

    private fun transformPoint(
        point: Offset,
        translation: Offset,
        scale: Float,
        anchor: Offset
    ): Offset {
        val anchoredX = point.x - anchor.x
        val anchoredY = point.y - anchor.y
        return Offset(
            x = anchor.x + anchoredX * scale + translation.x,
            y = anchor.y + anchoredY * scale + translation.y
        )
    }

    private fun computeStrokeBounds(stroke: StrokeEntity, points: List<PointEntity>): Rect {
        if (points.isEmpty()) {
            return Rect(stroke.boundsLeft, stroke.boundsTop, stroke.boundsRight, stroke.boundsBottom)
        }
        return if (stroke.shapeType == null) {
            val envelope = EnvelopeUtils.generateEnvelopePath(
                points.map { StrokePoint(it.x, it.y, it.width) }
            )
            envelope.getBounds()
        } else {
            val left = points.minOf { it.x }
            val top = points.minOf { it.y }
            val right = points.maxOf { it.x }
            val bottom = points.maxOf { it.y }
            Rect(left, top, right, bottom)
        }
    }
}
