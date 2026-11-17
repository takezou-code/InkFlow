package com.vic.inkflow.ui.selection

import androidx.compose.ui.geometry.Offset
import com.vic.inkflow.data.ImageAnnotation
import com.vic.inkflow.data.StrokeWithSamples
import com.vic.inkflow.data.TextAnnotation
import com.vic.inkflow.ui.isImageSelectedByLasso
import com.vic.inkflow.ui.isPointInPolygon
import com.vic.inkflow.ui.isTextSelectedByLasso

/** 判定當下某一頁的快照。呼叫端在主執行緒取一次，背景只用這份。 */
data class SelectionPageSnapshot(
    val page: Int,
    val strokes: List<StrokeWithSamples> = emptyList(),
    val images: List<ImageAnnotation> = emptyList(),
    val texts: List<TextAnnotation> = emptyList()
)

data class ResolvedSelection(
    val items: SelectionItems,
    val frame: SelectionFrame?
)

/**
 * 圈選命中判定（純函式）。
 *
 * 規則刻意與舊版一致，避免「重構順手改語義」：
 * - 墨：筆畫重心落在該頁任一圈內（不看線寬、不看部分進入）。
 * - 圖：與圈相交（角點／中心在圈內，或圈的頂點落在圖框內）。
 * - 字：baseline 點在圈內，或圈的頂點落在字框內。
 */
object SelectionResolver {

    fun resolve(
        region: SelectionRegion,
        pages: Map<Int, SelectionPageSnapshot>
    ): ResolvedSelection {
        if (region.isEmpty) return ResolvedSelection(SelectionItems(), null)

        val strokes = ArrayList<StrokeWithSamples>()
        val images = ArrayList<ImageAnnotation>()
        val texts = ArrayList<TextAnnotation>()

        for ((page, polygons) in region.polygonsByPage) {
            val closed = polygons.filter { it.size >= 3 }
            if (closed.isEmpty()) continue
            val snapshot = pages[page] ?: continue

            if (snapshot.strokes.isNotEmpty()) {
                for (swp in snapshot.strokes) {
                    val pts = swp.points
                    if (pts.isEmpty()) continue
                    var sx = 0.0
                    var sy = 0.0
                    for (p in pts) {
                        sx += p.x
                        sy += p.y
                    }
                    val cx = (sx / pts.size).toFloat()
                    val cy = (sy / pts.size).toFloat()
                    if (isCentroidInAny(cx, cy, closed)) strokes += swp
                }
            }
            if (snapshot.images.isNotEmpty()) {
                for (polygon in closed) {
                    snapshot.images.forEach { ann ->
                        if (isImageSelectedByLasso(ann, polygon)) images += ann
                    }
                }
            }
            if (snapshot.texts.isNotEmpty()) {
                for (polygon in closed) {
                    snapshot.texts.forEach { ann ->
                        if (isTextSelectedByLasso(ann, polygon)) texts += ann
                    }
                }
            }
        }

        val items = SelectionItems(
            strokes = strokes.distinctBy { it.stroke.id },
            images = images.distinctBy { it.id },
            texts = texts.distinctBy { it.id }
        )
        return ResolvedSelection(items = items, frame = LassoGeometry.frameOf(items, region))
    }

    private fun isCentroidInAny(cx: Float, cy: Float, polygons: List<List<Offset>>): Boolean {
        if (polygons.isEmpty()) return false
        return polygons.any { isPointInPolygon(Offset(cx, cy), it) }
    }
}
