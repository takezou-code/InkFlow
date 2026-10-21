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
import com.vic.inkflow.data.ImageAnnotation
import com.vic.inkflow.data.StrokeWithSamples
import com.vic.inkflow.data.TextAnnotation
import com.vic.inkflow.util.StrokeTransformUtils
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// ── InkCanvas 命中測試與幾何 ──────────────────────────────────────────────
// 文字/圖片/套索框的 canvas-space 命中矩形、旋轉、選取框幾何。純函數。

/**
 * 觸控墊片（px）：字尾多給這麼多，讓細瘦的標籤（例如「1」「→」）不會因為
 * 字形實際沒有填滿 em 框而點不到。
 */
internal const val TEXT_HIT_PAD_PX = 10f

/**
 * Returns the bounding rect of [ann] in canvas-pixel space.
 * [fontSizeDelta] is an in-flight resize delta in model units (applied during drag).
 *
 * 寬度走 [measureTextWidth]（全形感知），**不是** `字數 × 0.65`：後者是這裡曾經的寫法，
 * 而 [SelectionGeometry] 的註解已經點名它是錯的——中文字會比實際窄三分之一，
 * 結果是「標籤看得見、點不到右半邊」。同一份寬度公式有兩個版本時，使用者一定會
 * 遇到比較差的那個；所以這裡只保留一個真相來源。
 */
internal fun textAnnotationHitRect(
    ann: TextAnnotation, sx: Float, sy: Float, fontSizeDelta: Float = 0f,
    boxWidthDelta: Float = 0f
): Rect {
    val canvasX         = ann.modelX * sx
    val canvasY         = ann.modelY * sy
    val effectiveFont   = (ann.fontSize + fontSizeDelta).coerceAtLeast(4f)
    val effectiveFontPx = effectiveFont * sy
    // 文字框（boxWidth 有值）：寬度就是框，行數用同一套折行算（與繪製同一口徑）。
    // 舊資料（boxWidth null）：維持「貼合內容」的量法，行為不變。
    val boxWidthModel   = ann.boxWidth?.takeIf { it > 0f }?.plus(boxWidthDelta)
    val lines           = if (boxWidthModel != null) wrapTextLines(ann.text, effectiveFont, boxWidthModel)
                          else ann.text.split("\n")
    val lineCount       = lines.size.coerceAtLeast(1)
    val textWidth       = if (boxWidthModel != null) boxWidthModel * sx
                          else (lines.maxOfOrNull { measureTextWidth(it, effectiveFontPx) } ?: 0f)
    val textHeight      = effectiveFontPx + (lineCount - 1) * effectiveFontPx * 1.2f
    // 四邊同一個墊片：以前左 4 右 8 是不對稱且沒寫下來的原因，現在明示。
    // 對稱的好處是命中框與選取框的外觀跟觸控目標是同一個東西，不會出現
    // 「框畫在這裡、但手指得按旁邊才選得到」。
    return Rect(
        canvasX - TEXT_HIT_PAD_PX,
        canvasY - effectiveFontPx - TEXT_HIT_PAD_PX,
        canvasX + textWidth + TEXT_HIT_PAD_PX,
        canvasY - effectiveFontPx + textHeight + TEXT_HIT_PAD_PX
    )
}

/**
 * 文字框「內容」命中框：只包住**實際畫出來的字**（含對齊位移），不含框的空白。
 *
 * 選字用這個，不用整個框：框寬預設是「滿版扣邊距」，若拿整個框當命中區，
 * 一條滿版寬的短標籤會讓它右邊整條空白都變成它的選取區，使用者連第二個標籤
 * 都放不下去（點右邊只會選到左邊那個）。選取框／移動／框寬把手仍用
 * [textAnnotationHitRect]（整個框），所以選中後框與把手的位置還是清楚的。
 */
internal fun textAnnotationContentRect(
    ann: TextAnnotation, sx: Float, sy: Float, fontSizeDelta: Float = 0f
): Rect {
    val canvasX    = ann.modelX * sx
    val canvasY    = ann.modelY * sy
    val effFont    = (ann.fontSize + fontSizeDelta).coerceAtLeast(4f)
    val effFontPx  = effFont * sy
    val boxWidth   = ann.boxWidth?.takeIf { it > 0f }
    val lines      = if (boxWidth != null) wrapTextLines(ann.text, effFont, boxWidth)
                     else ann.text.split("\n")
    val lineOffsets = if (boxWidth != null)
        lines.map { textAlignOffsetModel(measureTextWidth(it, effFont), boxWidth, ann.alignment) }
    else
        lines.map { 0f }
    val leftOffset = lineOffsets.minOrNull() ?: 0f
    val rightMost  = lines.indices.maxOfOrNull { i -> lineOffsets[i] + measureTextWidth(lines[i], effFont) } ?: 0f
    val textHeight = effFontPx + (lines.size.coerceAtLeast(1) - 1) * effFontPx * 1.2f
    return Rect(
        canvasX + leftOffset * sx - TEXT_HIT_PAD_PX,
        canvasY - effFontPx - TEXT_HIT_PAD_PX,
        canvasX + rightMost * sx + TEXT_HIT_PAD_PX,
        canvasY - effFontPx + textHeight + TEXT_HIT_PAD_PX
    )
}

// 文字框把手（右下角＝字級、右緣中點＝框寬）不在這裡用 Rect 表示：
// 兩顆的 48dp 命中圈在短框時必然重疊，改用 nearestHandleIndex 做「最近優先」
// （呼叫端在 InkCanvas 的 TEXT 分支組 candidates）。見 nearestHandleIndex 的註解。

// ---- Image annotation hit-testing helpers ----

/**
 * Returns the canvas-pixel bounding rect for [ann] (committed DB values, no in-flight delta).
 */
internal fun imageAnnotationRect(ann: ImageAnnotation, sx: Float, sy: Float): Rect =
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
    val strokeWithPoints: StrokeWithSamples,
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

internal fun StrokeWithSamples.matches(cached: CachedStrokePath?): Boolean {
    if (cached == null) return false
    return cached.pointCount == points.size &&
        cached.boundsLeft == stroke.boundsLeft &&
        cached.boundsTop == stroke.boundsTop &&
        cached.boundsRight == stroke.boundsRight &&
        cached.boundsBottom == stroke.boundsBottom
}

// ── 髒矩形（damage region）增量重建 ─────────────────────────────────────────
// 全量重建（清空整張墨圖＋重畫全筆）是擦除卡頓的最大頭（實測 draw 17.6ms/幀）。
// 標準解法跟 Android View.invalidate(rect)／Skia clip 同一思想：重繪成本跟著
// 變化量走——加筆只畫新增，刪筆只清髒區＋重畫相交的，髒區過大才全量。

/** 髒區佔頁面積超過這個比例，全量重建反而划算（clip 來回不如一次清）。 */
internal const val INK_FULL_REBUILD_COVERAGE = 0.5f

/**
 * 髒區外擴（model px）：包絡 bounds 本來就含線寬，只需再吞掉抗鋸齒的 1~2px。
 * 跨區筆觸的重繪以此為界，邊界外舊像素原樣保留、保證無縫。
 */
internal const val INK_DIRTY_INFLATE_PX = 3f

/** null 容忍的包圍盒聯集（任一為 null 回另一；都被呼叫端保證非空才進來）。 */
internal fun unionRect(a: Rect?, b: Rect): Rect {
    if (a == null || a.isEmpty) return b
    if (b.isEmpty) return a
    return Rect(
        left = minOf(a.left, b.left),
        top = minOf(a.top, b.top),
        right = maxOf(a.right, b.right),
        bottom = maxOf(a.bottom, b.bottom)
    )
}

/**
 * bounds 是否全是有限數。包絡數學在退化輸入（重合點、零長段）下可能產出
 * NaN/Inf——minOf/maxOf 會靜默吞掉 NaN（當不存在），導致髒區算小、相交判定
 * 全 false，該畫的筆一支也畫不出來（且無崩潰、無 log，純隱形）。
 * 看到非有限，一律走全量重建（畫了一定對，多花幾毫秒而已）。
 */
internal fun Rect.hasFiniteBounds(): Boolean =
    left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()

/** 髒區佔頁面積比（0~1；頁面積為 0 回 1＝全量）。 */
internal fun damageCoverage(dirty: Rect, page: Rect): Float {
    val pageArea = page.width * page.height
    if (pageArea <= 0f) return 1f
    val dirtyArea = dirty.width.coerceAtLeast(0f) * dirty.height.coerceAtLeast(0f)
    return (dirtyArea / pageArea).coerceIn(0f, 1f)
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
internal val SEL_FRAME_FILL = Color.White.copy(alpha = 0.10f)
internal val SEL_FRAME_EDGE_LIGHT = Color.White.copy(alpha = 0.25f)
internal val SEL_DASH_PATTERN = floatArrayOf(12f, 8f)
internal const val SEL_HANDLE_RING_PX = 2f

/**
 * 文字框「框寬」把手畫成橫向膠囊時的長度（＝把手視覺半徑的幾倍）。
 *
 * 2.5 讓膠囊成為 2.5:1 的橫長條（半徑 4dp → 20dp × 8dp），形狀本身就說明
 * 「只能橫向拉」；與角落的圓點（字級）用**形狀**分家，不動色票——
 * 跟旋轉把手用空心環分家是同一套作法（見 [HandleVisual.isRotation] 的註解）。
 *
 * 刻意不做圖示：從前把旋轉箭頭塞進白色實心圓，實機回報「看起來像雜質」已移除。
 */
internal const val SEL_HANDLE_CAPSULE_LEN_MULT = 2.5f

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
    /**
     * 橫向膠囊把手（文字框的「框寬」）：跟圓點用形狀分家，一眼看出哪顆只能橫向拉。
     * 與 [isRotation] 互斥；兩者都 false ＝ 一般圓點。
     */
    val isCapsule: Boolean = false,
)

/** 依 [Handles.PressedScale](ui/theme/Handles.kt) 由 pressed(0→1) 插出的放大倍率。 */
internal fun handlePressedScale(pressed: Float, maxScale: Float): Float =
    1f + (maxScale - 1f) * pressed.coerceIn(0f, 1f)

internal fun DrawScope.drawLiquidGlassSelectionFrame(
    rect: Rect,
    /** 重點色：由呼叫端從 MaterialTheme 傳入（同一個主題色變數，不在這裡寫死）。 */
    accent: Color,
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
        color = accent.copy(alpha = 0.22f),
        topLeft = topLeft,
        size = size,
        cornerRadius = corner,
        style = Stroke(width = SEL_FRAME_HALO_PX)
    )
    drawRoundRect(
        color = accent,
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
            drawHandle(c, accent, visualRadiusPx, haloRadiusPx, pressed = 0f, isRotation = false)
        }
        return
    }
    handles.forEach { h ->
        drawHandle(
            h.center, accent, visualRadiusPx, haloRadiusPx, h.pressed, h.isRotation, h.isCapsule
        )
    }
}

/**
 * 單顆把手。靜置＝halo + 白色實心點 + indigo 環 + 高光點（同原設計）；
 * 旋轉＝外圈加粗成環、中心挖空畫箭頭，一眼跟四角分開；
 * 橫向膠囊＝文字框的框寬把手，**形狀**本身就說「只能橫向拉」（見 SEL_HANDLE_CAPSULE_LEN_MULT）；
 * 按住＝整顆放大（Handles.PressedScale）且 halo 加亮＝「抓到了」。
 */
private fun DrawScope.drawHandle(
    center: Offset,
    accent: Color,
    visualRadiusPx: Float,
    haloRadiusPx: Float,
    pressed: Float,
    isRotation: Boolean,
    isCapsule: Boolean = false,
) {
    val maxScale = com.vic.inkflow.ui.theme.Handles.PressedScale
    val s = handlePressedScale(pressed, maxScale)
    val r = visualRadiusPx * s
    val halo = haloRadiusPx * s
    val haloAlpha = if (pressed > 0.01f) 0.22f + 0.22f * pressed else 0.22f

    if (isCapsule) {
        // 橫向膠囊：同 halo/白心/indigo 環/高光的玻璃語言，只是外形拉長。
        val halfW = r * SEL_HANDLE_CAPSULE_LEN_MULT
        val halfH = r
        // halo 與點一致：往外擴 (haloRadiusPx - visualRadiusPx)。
        val inflate = halo - r
        val haloTopLeft = Offset(center.x - halfW - inflate, center.y - halfH - inflate)
        val haloSize = Size(halfW * 2f + inflate * 2f, halfH * 2f + inflate * 2f)
        drawRoundRect(
            color = accent.copy(alpha = haloAlpha),
            topLeft = haloTopLeft,
            size = haloSize,
            cornerRadius = CornerRadius(halfH + inflate, halfH + inflate)
        )
        val topLeft = Offset(center.x - halfW, center.y - halfH)
        val size = Size(halfW * 2f, halfH * 2f)
        val corner = CornerRadius(halfH, halfH)
        drawRoundRect(color = Color.White.copy(alpha = 0.85f), topLeft = topLeft, size = size, cornerRadius = corner)
        drawRoundRect(
            color = accent,
            topLeft = topLeft,
            size = size,
            cornerRadius = corner,
            style = Stroke(width = SEL_HANDLE_RING_PX * s)
        )
        // Specular dot: sells the glass (往左上偏，同圓點那顆)。
        drawCircle(
            color = Color.White,
            radius = r * 0.3f,
            center = Offset(center.x - halfW * 0.5f, center.y - halfH * 0.35f)
        )
        return
    }

    drawCircle(color = accent.copy(alpha = haloAlpha), radius = halo, center = center)
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
            color = accent,
            radius = r,
            center = center,
            style = Stroke(width = SEL_HANDLE_RING_PX * s)
        )
        return
    }
    drawCircle(color = Color.White.copy(alpha = 0.85f), radius = r, center = center)
    drawCircle(color = accent, radius = r, center = center, style = Stroke(width = SEL_HANDLE_RING_PX * s))
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
    /** 重點色：由呼叫端從 MaterialTheme 傳入（全 App 同一個主題色變數）。 */
    accent: Color,
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
        accent = accent,
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
    image: ImageAnnotation,
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
