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

/** Returns the resize-handle hit rect anchored to the bottom-right of [textRect], centered on that corner. */
internal fun textResizeHandleRect(textRect: Rect, hitRadiusPx: Float): Rect {
    val h = hitRadiusPx * 2f
    return Rect(textRect.right - h / 2f, textRect.bottom - h / 2f, textRect.right + h / 2f, textRect.bottom + h / 2f)
}

// ---- Image annotation hit-testing helpers ----

/**
 * Returns the canvas-pixel bounding rect for [ann] (committed DB values, no in-flight delta).
 */
internal fun imageAnnotationRect(ann: ImageAnnotationEntity, sx: Float, sy: Float): Rect =
    Rect(ann.modelX * sx, ann.modelY * sy,
         (ann.modelX + ann.modelWidth) * sx, (ann.modelY + ann.modelHeight) * sy)

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

/**
 * 無錨點時的落位原點：把 [imgW]×[imgH] 的圖置中在 [modelW]×[modelH] 的頁面裡。
 *
 * 圖片插入改由工具列觸發（不再靠「點紙面哪裡插哪裡」），所以沒有錨點可依，
 * 固定落在頁面中央——位置可預期，不會像舊的左上 10% 那樣疊在一起。
 *
 * 圖比頁面大時夾到 0（不產生負座標；橫向捲動交給文件級 pan，不靠負 offset 溢出）。
 */
internal fun centeredImageOrigin(modelW: Float, modelH: Float, imgW: Float, imgH: Float): Offset =
    Offset(
        ((modelW - imgW) / 2f).coerceIn(0f, maxOf(0f, modelW - imgW)),
        ((modelH - imgH) / 2f).coerceIn(0f, maxOf(0f, modelH - imgH))
    )

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
//
// 尺寸全走 ui/theme/Handles.kt（dp），這裡只留「純 px 換算 + 純幾何」。
// 舊版把 SEL_HANDLE_* 寫死物理 px，在 density 2.75 的平板上命中框只剩 17.5dp
// （Material 最低 48dp），使用者必須瞄準才抓得到——那個 bug 的根因。
//
// 命中半徑另有一層「畫布縮放補償」：紙放大時畫布變寬，把手若跟著放大，
// 在螢幕上的實體觸控目標反而變大／縮小不定。handleScaleFor 把它校正回
// 「螢幕上恆定」（同 Editframe transform handles 的 canvas-scale 做法）。
internal const val SEL_FRAME_CORNER_PX = 14f
internal const val SEL_FRAME_HALO_PX = 6f
internal const val SEL_FRAME_INNER_PX = 2.2f
internal val SEL_FRAME_HALO = BrandIndigo.copy(alpha = 0.22f)
internal val SEL_FRAME_FILL = Color.White.copy(alpha = 0.10f)
internal val SEL_FRAME_EDGE_LIGHT = Color.White.copy(alpha = 0.25f)
internal val SEL_DASH_PATTERN = floatArrayOf(12f, 8f)
internal const val SEL_HANDLE_RING_PX = 2f

// 把手尺寸不在這裡換算：紙面放大是「layout 尺寸變大」（PageWorkspace 的
// listWdp = viewportWpx * max(docZoom,1) + Surface.fillMaxWidth(docZoom)），
// **不是**對畫布做像素縮放——畫布鏈路上沒有任何 graphicsLayer（只在 overlay 用）。
// 所以 1 個畫布 px 恆等於 1 個螢幕 px，把手直接用 dp.toPx() 即可，
// 在任何 docZoom 下螢幕上都是同一個大小。
//
// 曾有 handleScaleFor(dpPx, canvasScale) = dpPx / scale 做「螢幕恆定」補償，
// 但那個假設錯了（誤以為有像素縮放層），結果 docZoom=2 把手只剩螢幕 12dp、
// 4x 只剩 6dp——實機回報「放大後把手變小，整個倒過來」。已移除。

/**
 * 命中測試：取**離 [point] 最近**且在 [hitRadiusPx] 內的把手索引，沒有則 -1。
 *
 * 為什麼要「最近」而不是固定順序：命中框放到 48dp 後，小圖的四角命中框會互相重疊，
 * 旋轉把手也會蓋到角（舊碼註解自己承認這件事）。Material 官方也點出「小而靠很近的
 * 控制項無法在觸控區不重疊的前提下放大」——所以放大命中區必須配套最近優先，
 * 否則重疊時會穩定抓到錯誤的那個把手。
 *
 * @param candidates 把手中心點，順序即 [result] 的索引對應
 * @param preferLastTies 同距離時是否讓後面的候選勝出（旋轉把手放最後時傳 true：
 *   同距離下角把手優先，角比旋轉常用）
 */
internal fun nearestHandleIndex(
    point: Offset,
    candidates: List<Offset>,
    hitRadiusPx: Float,
    preferLastTies: Boolean = false,
): Int {
    var bestIdx = -1
    var bestDist = Float.MAX_VALUE
    candidates.forEachIndexed { i, c ->
        val d = (c - point).getDistance()
        if (d <= hitRadiusPx && (d < bestDist || (preferLastTies && d == bestDist))) {
            bestDist = d
            bestIdx = i
        }
    }
    return bestIdx
}

/**
 * 液態玻璃選取框：淡白填充 + 靛暈外框 + 白虛線內框 + 內緣高光，
 * 手柄是白圓玻璃體（靛圈 + 左上高光點 + 外暈）。深淺紙都可讀。
 */
/**
 * 一顆把手的視覺狀態。半徑全部是「螢幕上的大小」經 handleScaleFor 換算後的畫布 px。
 */
internal data class HandleVisual(
    val center: Offset,
    /** 0f = 靜置；1f = 正在被拖。繪製時乘到半徑上做放大回饋。 */
    val pressed: Float = 0f,
    /** 旋轉把手畫成缺口環＋箭頭（跟四角的實心點區分）。 */
    val isRotation: Boolean = false,
)

/** 依 [Handles.PressedScale](ui/theme/Handles.kt) 由 pressed(0→1) 插出的放大倍率。 */
internal fun handlePressedScale(pressed: Float, maxScale: Float): Float =
    1f + (maxScale - 1f) * pressed.coerceIn(0f, 1f)

internal fun DrawScope.drawLiquidGlassSelectionFrame(
    rect: Rect,
    dashPhase: Float = 0f,
    animateDash: Boolean = false,
    handleCenters: List<Offset> = emptyList(),
    visualRadiusPx: Float = 8f,
    haloRadiusPx: Float = 12f,
    handles: List<HandleVisual> = emptyList(),
    /** 邊框線寬倍率（按住把手時 >1，只加粗線不改框大小，不遮內容）。 */
    strokeScale: Float = 1f,
) {
    val topLeft = Offset(rect.left, rect.top)
    val size = Size(rect.width, rect.height)
    val corner = CornerRadius(SEL_FRAME_CORNER_PX, SEL_FRAME_CORNER_PX)
    val innerStroke = if (animateDash) {
        Stroke(
            width = SEL_FRAME_INNER_PX * strokeScale,
            pathEffect = PathEffect.dashPathEffect(SEL_DASH_PATTERN, dashPhase)
        )
    } else {
        Stroke(width = SEL_FRAME_INNER_PX * strokeScale)
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
    // 舊簽名路徑：只給中心點、沒有狀態（跨頁 overlay 用）。
    if (handles.isEmpty()) {
        handleCenters.forEach { c ->
            drawHandle(c, visualRadiusPx, haloRadiusPx, pressed = 0f, isRotation = false)
        }
        return
    }
    handles.forEach { h ->
        drawHandle(
            h.center, visualRadiusPx, haloRadiusPx, h.pressed, h.isRotation
        )
    }
}

/**
 * 單顆把手。靜置＝halo + 白色實心點 + indigo 環 + 高光點（同原設計）；
 * 旋轉＝外圈加粗成環、中心挖空畫箭頭，一眼跟四角分開；
 * 按住＝整顆放大（Handles.PressedScale）且 halo 加亮＝「抓到了」。
 */
private fun DrawScope.drawHandle(
    center: Offset,
    visualRadiusPx: Float,
    haloRadiusPx: Float,
    pressed: Float,
    isRotation: Boolean,
) {
    val maxScale = com.vic.inkflow.ui.theme.Handles.PressedScale
    val s = handlePressedScale(pressed, maxScale)
    val r = visualRadiusPx * s
    val halo = haloRadiusPx * s
    val haloAlpha = if (pressed > 0.01f) 0.22f + 0.22f * pressed else 0.22f

    drawCircle(color = SEL_FRAME_HALO.copy(alpha = haloAlpha), radius = halo, center = center)
    if (isRotation) {
        // 旋轉把手＝空心環（vs 四角的實心點），一眼分得出哪個能轉。
        // 曾試過在中間加旋轉箭頭做二次區分，但那條弧畫在環內、白色環寬又幾乎填滿
        // 內部，兩者疊成髒色塊（實機回報「看起來像雜質」），已移除。
        // 白色環做內圈、indigo 環做外圈，雙環本身就有層次感。
        drawCircle(
            color = Color.White.copy(alpha = 0.9f),
            radius = r,
            center = center,
            style = Stroke(width = (r * 0.5f).coerceAtLeast(1.5f))
        )
        drawCircle(
            color = BrandIndigo,
            radius = r,
            center = center,
            style = Stroke(width = SEL_HANDLE_RING_PX * s)
        )
        return
    }
    drawCircle(color = Color.White.copy(alpha = 0.85f), radius = r, center = center)
    drawCircle(color = BrandIndigo, radius = r, center = center, style = Stroke(width = SEL_HANDLE_RING_PX * s))
    // Specular dot: sells the glass.
    drawCircle(color = Color.White, radius = r * 0.3f, center = center + Offset(-r * 0.3f, -r * 0.3f))
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
    animateDash: Boolean,
    visualRadiusPx: Float = 8f,
    haloRadiusPx: Float = 12f,
    pressedHandleCenter: Offset? = null,
    strokeScale: Float = 1f,
) {
    val centers = if (showHandles) strokeSelectionHandleCenters(selectionRect) else emptyList()
    drawLiquidGlassSelectionFrame(
        rect = selectionRect,
        dashPhase = dashPhase,
        animateDash = animateDash,
        handles = centers.map { c ->
            HandleVisual(center = c, pressed = if (c == pressedHandleCenter) 1f else 0f)
        },
        visualRadiusPx = visualRadiusPx,
        haloRadiusPx = haloRadiusPx,
        strokeScale = strokeScale
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

/** Centers of the four stroke-selection corner handles, in [StrokeSelectionHandle] enum order. */
internal fun strokeSelectionHandleCenters(selectionRect: Rect): List<Offset> =
    StrokeSelectionHandle.entries.map { strokeSelectionHandleCenter(selectionRect, it) }

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
