package com.vic.inkflow.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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

/**
 * Sutherland–Hodgman 多邊形裁剪：把（可能開口的）點列裁到軸對齊矩形內，回傳閉合多邊形。
 * 跨頁套索用：每頁只拿自己窗口內的閉合圈去測，不開口弧＋弦閉合造成的整頁誤選。
 * 完全在內的圈原樣返回（頂點順序不變）；完全在外的回空表。
 */
internal fun clipPolygonToRect(
    polygon: List<Offset>,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float
): List<Offset> {
    if (polygon.isEmpty()) return emptyList()
    var output = polygon
    // 左(x>=l)、右(x<=r)、上(y>=t)、下(y<=b)，逐邊裁。
    output = clipAgainstEdge(output, inside = { it.x >= left }) { a, b ->
        intersectX(a, b, left)
    }
    output = clipAgainstEdge(output, inside = { it.x <= right }) { a, b ->
        intersectX(a, b, right)
    }
    output = clipAgainstEdge(output, inside = { it.y >= top }) { a, b ->
        intersectY(a, b, top)
    }
    output = clipAgainstEdge(output, inside = { it.y <= bottom }) { a, b ->
        intersectY(a, b, bottom)
    }
    return output
}

private fun clipAgainstEdge(
    input: List<Offset>,
    inside: (Offset) -> Boolean,
    intersect: (Offset, Offset) -> Offset
): List<Offset> {
    if (input.isEmpty()) return emptyList()
    val output = ArrayList<Offset>(input.size + 1)
    var prev = input.last()
    var prevIn = inside(prev)
    for (cur in input) {
        val curIn = inside(cur)
        if (curIn) {
            if (!prevIn) output.add(intersect(prev, cur))
            output.add(cur)
        } else if (prevIn) {
            output.add(intersect(prev, cur))
        }
        prev = cur
        prevIn = curIn
    }
    return output
}

private fun intersectX(a: Offset, b: Offset, x: Float): Offset {
    val dx = b.x - a.x
    if (dx == 0f) return Offset(x, a.y)
    val t = (x - a.x) / dx
    return Offset(x, a.y + t * (b.y - a.y))
}

private fun intersectY(a: Offset, b: Offset, y: Float): Offset {
    val dy = b.y - a.y
    if (dy == 0f) return Offset(a.x, y)
    val t = (y - a.y) / dy
    return Offset(a.x + t * (b.x - a.x), y)
}

/** M5: axis-aligned bounds of the (possibly rotated) image, in model space.
 * 回傳 compose Rect（刻意不用 android.graphics.RectF——後者在 JVM 單測是空殼，
 * 構造器不賦值、方法全樁，純幾何測不了；血淚教訓見 textEstimatedBounds）。 */
internal fun rotatedImageBounds(annotation: ImageAnnotationEntity): Rect {
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
    return Rect(
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
    val polygonBounds = Rect(minX, minY, maxX, maxY)
    if (!imageRect.overlaps(polygonBounds)) return false

    val samplePoints = listOf(
        Offset(imageRect.left, imageRect.top),
        Offset(imageRect.right, imageRect.top),
        Offset(imageRect.left, imageRect.bottom),
        Offset(imageRect.right, imageRect.bottom),
        imageRect.center
    )

    if (samplePoints.any { isPointInPolygon(it, polygon) }) return true

    return polygon.any { p -> p.x in imageRect.left..imageRect.right && p.y in imageRect.top..imageRect.bottom }
}

/**
 * 文字估算框（model 座標）：與橡皮擦命中同一口徑（寬≈字數×字號×0.6，高≈字號×1.2；
 * baseline 為底邊）。套索命中＋選取框共用，單一真相。
 */
internal fun textEstimatedBounds(ann: com.vic.inkflow.data.TextAnnotationEntity): Rect {
    val w = if (ann.isStamp) ann.fontSize else ann.text.length * ann.fontSize * 0.6f
    val h = if (ann.isStamp) ann.fontSize else ann.fontSize * 1.2f
    return Rect(ann.modelX, ann.modelY - h, ann.modelX + w, ann.modelY)
}

/** 文字套索命中：baseline 點在圈內，或圈的任一頂點落在字框內。 */
internal fun isTextSelectedByLasso(
    ann: com.vic.inkflow.data.TextAnnotationEntity,
    polygon: List<Offset>
): Boolean {
    if (polygon.size < 3) return false
    if (isPointInPolygon(Offset(ann.modelX, ann.modelY), polygon)) return true
    val b = textEstimatedBounds(ann)
    return polygon.any { p -> p.x in b.left..b.right && p.y in b.top..b.bottom }
}
