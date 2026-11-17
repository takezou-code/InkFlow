package com.vic.inkflow.ui.selection

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.vic.inkflow.ui.clipPolygonToRect
import com.vic.inkflow.ui.transformPolygon
import com.vic.inkflow.util.DocLayout
import com.vic.inkflow.util.DocTransform
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 套索幾何：手勢點 → 分頁／裁剪／正規化 → 區域；以及區域的平移、縮放、換頁。
 *
 * 全部是純函式，不碰 Compose、不碰 DB；可以在 JVM 單測裡直接釘住。
 */
object LassoGeometry {

    /** 手勢點簡化容差（canvas px）。1.25px 去抖但不改變形狀。 */
    const val SIMPLIFY_TOLERANCE_PX = 1.25f

    /**
     * 自由圈選：把 canvas 點列切成每頁的閉合多邊形（model／頁內座標）。
     *
     * 回傳 null = 退化圈選（點太少／面積為零）。
     */
    fun fromFreeform(
        pointsCanvas: List<Offset>,
        srcPage: Int,
        pageCount: Int,
        gapPx: Float,
        canvasW: Float,
        canvasH: Float,
        modelW: Float,
        modelH: Float
    ): SelectionRegion? {
        if (pointsCanvas.size < 3 || canvasW <= 0f || canvasH <= 0f) return null
        val simplified = simplify(pointsCanvas, SIMPLIFY_TOLERANCE_PX)
        if (simplified.size < 3) return null

        val byPage = LinkedHashMap<Int, MutableList<List<Offset>>>()
        DocLayout.splitByPage(
            items = simplified,
            yOf = { it.y },
            canvasH = canvasH,
            gapPx = gapPx,
            srcPage = srcPage,
            pageCount = pageCount,
            local = { p, localY -> Offset(p.x, localY) }
        ).forEach { (pg, segment) ->
            val clipped = clipPolygonToRect(segment, 0f, 0f, canvasW, canvasH)
            if (clipped.size >= 3) {
                val normalized = clipped.map {
                    DocTransform.canvasToModel(it, canvasW, canvasH, modelW, modelH)
                }
                byPage.getOrPut(pg) { mutableListOf() }.add(normalized)
            }
        }
        if (byPage.isEmpty()) return null
        // 起手點落在頁縫時，srcPage 可能沒有自己的圈；錨點改用第一個有圈的那頁。
        val effectiveSrc = if (byPage.containsKey(srcPage)) srcPage else byPage.keys.first()
        return SelectionRegion(polygonsByPage = byPage, srcPage = effectiveSrc)
    }

    /** 矩形圈選：按頁重疊切成每頁一個四邊形（model／頁內座標）。 */
    fun fromRect(
        startCanvas: Offset,
        endCanvas: Offset,
        srcPage: Int,
        pageCount: Int,
        gapPx: Float,
        canvasW: Float,
        canvasH: Float,
        modelW: Float,
        modelH: Float
    ): SelectionRegion? {
        if (canvasW <= 0f || canvasH <= 0f) return null
        val quads = DocLayout.rectsByPage(
            left = min(startCanvas.x, endCanvas.x),
            top = min(startCanvas.y, endCanvas.y),
            right = max(startCanvas.x, endCanvas.x),
            bottom = max(startCanvas.y, endCanvas.y),
            canvasW = canvasW,
            canvasH = canvasH,
            gapPx = gapPx,
            srcPage = srcPage,
            pageCount = pageCount
        )
        if (quads.isEmpty()) return null
        val byPage = LinkedHashMap<Int, MutableList<List<Offset>>>()
        quads.forEach { (pg, quad) ->
            if (quad.size >= 3) {
                byPage.getOrPut(pg) { mutableListOf() }.add(
                    quad.map { DocTransform.canvasToModel(it, canvasW, canvasH, modelW, modelH) }
                )
            }
        }
        if (byPage.isEmpty()) return null
        val effectiveSrc = if (byPage.containsKey(srcPage)) srcPage else byPage.keys.first()
        return SelectionRegion(polygonsByPage = byPage, srcPage = effectiveSrc)
    }

    // ── 區域變換 ─────────────────────────────────────────────────────────────

    fun transformRegion(
        region: SelectionRegion,
        translation: Offset,
        scale: Float,
        anchor: Offset
    ): SelectionRegion {
        if (translation == Offset.Zero && abs(scale - 1f) < 0.0001f) return region
        return region.copy(
            polygonsByPage = region.polygonsByPage.mapValues { (_, polys) ->
                polys.map { transformPolygon(it, translation, scale, anchor) }
            }
        )
    }

    /** 整組換頁：頁索引 +[pageShift]，頁內 Y 減掉 [pageShift] × 頁高。 */
    fun shiftRegion(region: SelectionRegion, pageShift: Int, pageHeight: Float): SelectionRegion {
        if (pageShift == 0) return region
        val dy = pageShift * pageHeight
        val shifted = LinkedHashMap<Int, MutableList<List<Offset>>>()
        region.polygonsByPage.forEach { (page, polys) ->
            shifted.getOrPut(page + pageShift) { mutableListOf() }.addAll(
                polys.map { poly -> poly.map { Offset(it.x, it.y - dy) } }
            )
        }
        return SelectionRegion(polygonsByPage = shifted, srcPage = region.srcPage + pageShift)
    }

    // ── 顯示框 ───────────────────────────────────────────────────────────────

    /**
     * 主導頁顯示框：選中物最多的那一頁（同票取頁碼小者，結果穩定）。
     * 沒有選中物時退回圈出的區域（空白圈選仍要能開 AI／提取）。
     */
    fun frameOf(items: SelectionItems, region: SelectionRegion?): SelectionFrame? {
        val counts = HashMap<Int, Int>()
        items.strokes.forEach { counts.merge(it.stroke.pageIndex, 1, Int::plus) }
        items.images.forEach { counts.merge(it.pageIndex, 1, Int::plus) }
        items.texts.forEach { counts.merge(it.pageIndex, 1, Int::plus) }

        val dominant = counts.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()?.key
        if (dominant != null) {
            val b = items.forPage(dominant).bounds()
            if (b != null) return SelectionFrame(dominant, b)
        }
        val r = region ?: return null
        val b = polygonBounds(r.srcPolygon) ?: return null
        return SelectionFrame(r.srcPage, b)
    }

    fun polygonBounds(points: List<Offset>): Rect? {
        if (points.size < 3) return null
        val minX = points.minOf { it.x }
        val minY = points.minOf { it.y }
        val maxX = points.maxOf { it.x }
        val maxY = points.maxOf { it.y }
        if (maxX <= minX || maxY <= minY) return null
        return Rect(minX, minY, maxX, maxY)
    }

    // ── Ramer–Douglas–Peucker ────────────────────────────────────────────────

    fun simplify(points: List<Offset>, tolerance: Float): List<Offset> {
        val deduped = ArrayList<Offset>(points.size)
        var last: Offset? = null
        for (p in points) {
            if (last == null || abs(p.x - last.x) > 0.01f || abs(p.y - last.y) > 0.01f) {
                deduped.add(p)
                last = p
            }
        }
        if (deduped.size <= 2) return deduped
        val keep = BooleanArray(deduped.size)
        keep[0] = true
        keep[deduped.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to deduped.lastIndex)
        while (stack.isNotEmpty()) {
            val (start, end) = stack.removeLast()
            if (end <= start + 1) continue
            val a = deduped[start]
            val b = deduped[end]
            var maxDist = 0f
            var maxIndex = -1
            for (i in start + 1 until end) {
                val d = perpendicularDistance(deduped[i], a, b)
                if (d > maxDist) {
                    maxDist = d
                    maxIndex = i
                }
            }
            if (maxDist > tolerance && maxIndex > 0) {
                keep[maxIndex] = true
                stack.addLast(start to maxIndex)
                stack.addLast(maxIndex to end)
            }
        }
        return deduped.filterIndexed { index, _ -> keep[index] }
    }

    private fun perpendicularDistance(p: Offset, a: Offset, b: Offset): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lenSq = dx * dx + dy * dy
        if (lenSq <= 0.0001f) return kotlin.math.hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq).coerceIn(0f, 1f)
        val projX = a.x + t * dx
        val projY = a.y + t * dy
        return kotlin.math.hypot(p.x - projX, p.y - projY)
    }
}
