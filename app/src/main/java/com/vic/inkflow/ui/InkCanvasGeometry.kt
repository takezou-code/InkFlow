package com.vic.inkflow.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.vic.inkflow.data.ImageAnnotationEntity
import com.vic.inkflow.data.StrokeWithPoints
import com.vic.inkflow.data.TextAnnotationEntity
import com.vic.inkflow.ui.theme.BrandIndigo
import com.vic.inkflow.util.StrokeTransformUtils
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// ── InkCanvas 命中測試與幾何 ──────────────────────────────────────────────
// 文字/圖片/套索框的 canvas-space 命中矩形、旋轉、選取框幾何。純函數。

/**
 * Returns the bounding rect of [ann] in canvas-pixel space.
 * [fontSizeDelta] is an in-flight resize delta in model units (applied during drag).
 */
internal fun textAnnotationHitRect(
    ann: TextAnnotationEntity, sx: Float, sy: Float, fontSizeDelta: Float = 0f
): Rect {
    val canvasX         = ann.modelX * sx
    val canvasY         = ann.modelY * sy
    val effectiveFontPx = (ann.fontSize + fontSizeDelta).coerceAtLeast(4f) * sy
    // Multiline bounds: modelY is the first-line baseline; each extra line adds one line-height.
    val lines           = ann.text.split("\n")
    val maxLineLen      = lines.maxOfOrNull { it.length } ?: 0
    // Approximate text width; drawText baseline is at (canvasX, canvasY)
    val textWidth       = maxLineLen * effectiveFontPx * 0.65f + 8f
    val textHeight      = effectiveFontPx + (lines.size - 1) * effectiveFontPx * 1.2f
    return Rect(canvasX - 4f, canvasY - effectiveFontPx - 4f, canvasX + textWidth, canvasY - effectiveFontPx + textHeight + 4f)
}

/** Returns the 48×48 px resize handle hit rect anchored to the bottom-right of [textRect] (24px hit radius; visual is a shared glass handle). */
internal fun textResizeHandleRect(textRect: Rect): Rect {
    val h = SEL_HANDLE_HIT_R_PX * 2f
    return Rect(textRect.right - h / 2f, textRect.bottom - h / 2f, textRect.right + h / 2f, textRect.bottom + h / 2f)
}

// ---- Image annotation hit-testing helpers ----

/** Returns the canvas-pixel bounding rect for [ann] (committed DB values, no in-flight delta). */
internal fun imageAnnotationRect(ann: ImageAnnotationEntity, sx: Float, sy: Float): Rect =
    Rect(ann.modelX * sx, ann.modelY * sy,
         (ann.modelX + ann.modelWidth) * sx, (ann.modelY + ann.modelHeight) * sy)

/** Returns the 48×48 px resize handle hit rect centered on [center] (24px hit radius; visual is a shared glass handle). */
internal fun imageResizeHandleRect(center: Offset): Rect {
    val h = SEL_HANDLE_HIT_R_PX * 2f
    return Rect(center.x - h / 2f, center.y - h / 2f,
                center.x + h / 2f, center.y + h / 2f)
}

/** Uniform-scales [rect] about [anchor] (corner-drag resize keeps aspect). */
internal fun scaleRectAbout(rect: Rect, anchor: Offset, scale: Float): Rect {
    fun map(p: Offset): Offset = anchor + (p - anchor) * scale
    val a = map(rect.topLeft)
    val b = map(rect.bottomRight)
    return Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
}

/** Effective IMAGE-tool rect: committed + move preview + uniform resize preview. */
internal fun effectiveImageRect(base: Rect, move: Offset, anchor: Offset?, scale: Float): Rect {
    val moved = base.translate(move)
    return if (anchor != null && abs(scale - 1f) > 0.0001f) scaleRectAbout(moved, anchor, scale)
    else moved
}

// ---- M5: image rotation helpers (canvas space; positive degrees = clockwise, matches DrawScope.rotate) ----
internal const val IMAGE_ROT_HANDLE_GAP_PX = 56f

/** Rotates canvas point [p] about [center] by [degrees] clockwise. */
internal fun rotatePoint(p: Offset, center: Offset, degrees: Float): Offset {
    if (degrees == 0f) return p
    val rad = Math.toRadians(degrees.toDouble())
    val cos = cos(rad).toFloat()
    val sin = sin(rad).toFloat()
    val dx = p.x - center.x
    val dy = p.y - center.y
    return Offset(center.x + dx * cos - dy * sin, center.y + dx * sin + dy * cos)
}

/** Hit-tests a possibly-rotated rect by unrotating the point about the rect center first. */
internal fun rotatedRectContains(rect: Rect, degrees: Float, point: Offset): Boolean {
    if (degrees == 0f) return rect.contains(point)
    return rect.contains(rotatePoint(point, rect.center, -degrees))
}

internal enum class StrokeSelectionHandle {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT
}

internal data class SelectedStrokeRenderData(
    val strokeWithPoints: StrokeWithPoints,
    val path: Path?
)

internal data class CachedStrokePath(
    val path: Path,
    val pointCount: Int,
    val boundsLeft: Float,
    val boundsTop: Float,
    val boundsRight: Float,
    val boundsBottom: Float
)

internal fun StrokeWithPoints.matches(cached: CachedStrokePath?): Boolean {
    if (cached == null) return false
    return cached.pointCount == points.size &&
        cached.boundsLeft == stroke.boundsLeft &&
        cached.boundsTop == stroke.boundsTop &&
        cached.boundsRight == stroke.boundsRight &&
        cached.boundsBottom == stroke.boundsBottom
}

// ---- Liquid-glass selection frame, shared by lasso / image / text ----
internal const val SEL_FRAME_CORNER_PX = 14f
internal const val SEL_FRAME_HALO_PX = 6f
internal const val SEL_FRAME_INNER_PX = 2.2f
internal val SEL_FRAME_HALO = BrandIndigo.copy(alpha = 0.22f)
internal val SEL_FRAME_FILL = Color.White.copy(alpha = 0.10f)
internal val SEL_FRAME_EDGE_LIGHT = Color.White.copy(alpha = 0.25f)
internal val SEL_DASH_PATTERN = floatArrayOf(12f, 8f)
internal const val SEL_HANDLE_R_PX = 10f
internal const val SEL_HANDLE_HALO_R_PX = 14f
internal const val SEL_HANDLE_HIT_R_PX = 24f
internal const val SEL_HANDLE_RING_PX = 2f

/**
 * 液態玻璃選取框：淡白填充 + 靛暈外框 + 白虛線內框 + 內緣高光，
 * 手柄是白圓玻璃體（靛圈 + 左上高光點 + 外暈）。深淺紙都可讀。
 */
internal fun DrawScope.drawLiquidGlassSelectionFrame(
    rect: Rect,
    dashPhase: Float = 0f,
    animateDash: Boolean = false,
    handleCenters: List<Offset> = emptyList()
) {
    val topLeft = Offset(rect.left, rect.top)
    val size = Size(rect.width, rect.height)
    val corner = CornerRadius(SEL_FRAME_CORNER_PX, SEL_FRAME_CORNER_PX)
    val innerStroke = if (animateDash) {
        Stroke(
            width = SEL_FRAME_INNER_PX,
            pathEffect = PathEffect.dashPathEffect(SEL_DASH_PATTERN, dashPhase)
        )
    } else {
        Stroke(width = SEL_FRAME_INNER_PX)
    }
    // Glass body: faint white fill so the frame reads on dark paper too.
    drawRoundRect(
        color = SEL_FRAME_FILL,
        topLeft = topLeft,
        size = size,
        cornerRadius = corner
    )
    // Outer halo + dashed inner line carry the brand color.
    drawRoundRect(
        color = SEL_FRAME_HALO,
        topLeft = topLeft,
        size = size,
        cornerRadius = corner,
        style = Stroke(width = SEL_FRAME_HALO_PX)
    )
    drawRoundRect(
        color = BrandIndigo,
        topLeft = topLeft,
        size = size,
        cornerRadius = corner,
        style = innerStroke
    )
    // Inner edge light: the glassy top-left sheen.
    val inset = SEL_FRAME_HALO_PX / 2f + 2f
    if (rect.width > inset * 2f + SEL_FRAME_CORNER_PX && rect.height > inset * 2f + SEL_FRAME_CORNER_PX) {
        drawRoundRect(
            color = SEL_FRAME_EDGE_LIGHT,
            topLeft = Offset(rect.left + inset, rect.top + inset),
            size = Size(rect.width - inset * 2f, rect.height - inset * 2f),
            cornerRadius = CornerRadius(SEL_FRAME_CORNER_PX - inset, SEL_FRAME_CORNER_PX - inset),
            style = Stroke(width = 1.5f)
        )
    }
    handleCenters.forEach { c ->
        drawCircle(
            color = SEL_FRAME_HALO,
            radius = SEL_HANDLE_HALO_R_PX,
            center = c
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.85f),
            radius = SEL_HANDLE_R_PX,
            center = c
        )
        drawCircle(
            color = BrandIndigo,
            radius = SEL_HANDLE_R_PX,
            center = c,
            style = Stroke(width = SEL_HANDLE_RING_PX)
        )
        // Specular dot: sells the glass.
        drawCircle(
            color = Color.White,
            radius = 3f,
            center = c + Offset(-3f, -3f)
        )
    }
}

internal fun applySelectionTransform(
    canvas: androidx.compose.ui.graphics.Canvas,
    translation: Offset,
    scale: Float,
    anchor: Offset
) {
    val clampedScale = StrokeTransformUtils.clampUniformScale(scale)
    canvas.translate(anchor.x, anchor.y)
    canvas.scale(clampedScale, clampedScale)
    canvas.translate(-anchor.x, -anchor.y)
    canvas.translate(translation.x, translation.y)
}

internal fun DrawScope.drawLassoSelectionFrame(
    selectionRect: Rect,
    showHandles: Boolean,
    dashPhase: Float,
    animateDash: Boolean
) {
    drawLiquidGlassSelectionFrame(
        rect = selectionRect,
        dashPhase = dashPhase,
        animateDash = animateDash,
        handleCenters = if (showHandles)
            strokeSelectionHandleRects(selectionRect).map { (_, handleRect) -> handleRect.center }
        else emptyList()
    )
}

internal fun strokeSelectionHandleCenter(selectionRect: Rect, handle: StrokeSelectionHandle): Offset = when (handle) {
    StrokeSelectionHandle.TOP_LEFT -> Offset(selectionRect.left, selectionRect.top)
    StrokeSelectionHandle.TOP_RIGHT -> Offset(selectionRect.right, selectionRect.top)
    StrokeSelectionHandle.BOTTOM_LEFT -> Offset(selectionRect.left, selectionRect.bottom)
    StrokeSelectionHandle.BOTTOM_RIGHT -> Offset(selectionRect.right, selectionRect.bottom)
}

internal fun strokeSelectionResizeAnchor(selectionRect: Rect, handle: StrokeSelectionHandle): Offset = when (handle) {
    StrokeSelectionHandle.TOP_LEFT -> Offset(selectionRect.right, selectionRect.bottom)
    StrokeSelectionHandle.TOP_RIGHT -> Offset(selectionRect.left, selectionRect.bottom)
    StrokeSelectionHandle.BOTTOM_LEFT -> Offset(selectionRect.right, selectionRect.top)
    StrokeSelectionHandle.BOTTOM_RIGHT -> Offset(selectionRect.left, selectionRect.top)
}

internal fun strokeSelectionHandleRects(selectionRect: Rect): List<Pair<StrokeSelectionHandle, Rect>> = listOf(
    StrokeSelectionHandle.TOP_LEFT,
    StrokeSelectionHandle.TOP_RIGHT,
    StrokeSelectionHandle.BOTTOM_LEFT,
    StrokeSelectionHandle.BOTTOM_RIGHT
).map { handle ->
    handle to strokeSelectionHandleHitRect(strokeSelectionHandleCenter(selectionRect, handle))
}

internal fun strokeSelectionHandleHitRect(center: Offset): Rect = Rect(
    left = center.x - SEL_HANDLE_HIT_R_PX,
    top = center.y - SEL_HANDLE_HIT_R_PX,
    right = center.x + SEL_HANDLE_HIT_R_PX,
    bottom = center.y + SEL_HANDLE_HIT_R_PX
)

internal fun modelTransformedPolygonBoundsToCanvasRect(
    polygon: List<Offset>,
    translation: Offset,
    scale: Float,
    anchor: Offset,
    sx: Float,
    sy: Float
): Rect? {
    if (polygon.isEmpty()) return null
    val clampedScale = StrokeTransformUtils.clampUniformScale(scale)
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    polygon.forEach { point ->
        val anchoredX = point.x - anchor.x
        val anchoredY = point.y - anchor.y
        val transformedX = anchor.x + anchoredX * clampedScale + translation.x
        val transformedY = anchor.y + anchoredY * clampedScale + translation.y
        if (transformedX < minX) minX = transformedX
        if (transformedY < minY) minY = transformedY
        if (transformedX > maxX) maxX = transformedX
        if (transformedY > maxY) maxY = transformedY
    }
    if (maxX <= minX || maxY <= minY) return null
    return Rect(minX * sx, minY * sy, maxX * sx, maxY * sy)
}

internal fun modelTransformedImageRectToCanvasRect(
    image: ImageAnnotationEntity,
    translation: Offset,
    scale: Float,
    anchor: Offset,
    sx: Float,
    sy: Float
): Rect {
    val clampedScale = StrokeTransformUtils.clampUniformScale(scale)
    val transformedLeft = anchor.x + (image.modelX - anchor.x) * clampedScale + translation.x
    val transformedTop = anchor.y + (image.modelY - anchor.y) * clampedScale + translation.y
    val transformedWidth = (image.modelWidth * clampedScale).coerceAtLeast(1f)
    val transformedHeight = (image.modelHeight * clampedScale).coerceAtLeast(1f)
    return Rect(
        left = transformedLeft * sx,
        top = transformedTop * sy,
        right = (transformedLeft + transformedWidth) * sx,
        bottom = (transformedTop + transformedHeight) * sy
    )
}
