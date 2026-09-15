package com.vic.inkflow.ui

import androidx.compose.ui.geometry.Offset
import com.vic.inkflow.data.ImageAnnotationEntity
import com.vic.inkflow.util.StrokeTransformUtils

// ── 套索/選取幾何：座標換算、多邊形變換、命中測試 ──────────────────────────
// 純函數（原 EditorViewModel 私有方法），供手勢與提交路徑共用。

internal fun canvasToModel(
    offset: Offset,
    modelW: Float,
    modelH: Float,
    canvasW: Float,
    canvasH: Float
): Offset = Offset(
    x = offset.x * modelW / canvasW,
    y = offset.y * modelH / canvasH
)

internal fun transformPolygon(
    polygon: List<Offset>,
    translation: Offset,
    scale: Float,
    anchor: Offset
): List<Offset> {
    val clampedScale = StrokeTransformUtils.clampUniformScale(scale)
    return polygon.map { point ->
        val anchoredX = point.x - anchor.x
        val anchoredY = point.y - anchor.y
        Offset(
            x = anchor.x + anchoredX * clampedScale + translation.x,
            y = anchor.y + anchoredY * clampedScale + translation.y
        )
    }
}

internal fun isPointInPolygon(point: Offset, polygon: List<Offset>): Boolean {
    if (polygon.size < 3) return false
    var inside = false
    var j = polygon.lastIndex
    for (i in polygon.indices) {
        val xi = polygon[i].x
        val yi = polygon[i].y
        val xj = polygon[j].x
        val yj = polygon[j].y
        val intersects = ((yi > point.y) != (yj > point.y)) &&
            (point.x < (xj - xi) * (point.y - yi) / (yj - yi) + xi)
        if (intersects) inside = !inside
        j = i
    }
    return inside
}

/** M5: axis-aligned bounds of the (possibly rotated) image, in model space. */
internal fun rotatedImageBounds(annotation: ImageAnnotationEntity): android.graphics.RectF {
    val cx = annotation.modelX + annotation.modelWidth / 2f
    val cy = annotation.modelY + annotation.modelHeight / 2f
    val rad = Math.toRadians(annotation.rotation.toDouble())
    val cos = kotlin.math.cos(rad).toFloat()
    val sin = kotlin.math.sin(rad).toFloat()
    val corners = listOf(
        Offset(annotation.modelX, annotation.modelY),
        Offset(annotation.modelX + annotation.modelWidth, annotation.modelY),
        Offset(annotation.modelX, annotation.modelY + annotation.modelHeight),
        Offset(annotation.modelX + annotation.modelWidth, annotation.modelY + annotation.modelHeight)
    ).map { p ->
        val dx = p.x - cx
        val dy = p.y - cy
        Offset(cx + dx * cos - dy * sin, cy + dx * sin + dy * cos)
    }
    return android.graphics.RectF(
        corners.minOf { it.x }, corners.minOf { it.y },
        corners.maxOf { it.x }, corners.maxOf { it.y }
    )
}

internal fun isImageSelectedByLasso(annotation: ImageAnnotationEntity, polygon: List<Offset>): Boolean {
    if (polygon.size < 3) return false

    val imageRect = rotatedImageBounds(annotation)
    val minX = polygon.minOf { it.x }
    val minY = polygon.minOf { it.y }
    val maxX = polygon.maxOf { it.x }
    val maxY = polygon.maxOf { it.y }
    val polygonBounds = android.graphics.RectF(minX, minY, maxX, maxY)
    if (!android.graphics.RectF.intersects(imageRect, polygonBounds)) return false

    val samplePoints = listOf(
        Offset(imageRect.left, imageRect.top),
        Offset(imageRect.right, imageRect.top),
        Offset(imageRect.left, imageRect.bottom),
        Offset(imageRect.right, imageRect.bottom),
        Offset(imageRect.centerX(), imageRect.centerY())
    )

    if (samplePoints.any { isPointInPolygon(it, polygon) }) return true

    return polygon.any { p -> p.x in imageRect.left..imageRect.right && p.y in imageRect.top..imageRect.bottom }
}
