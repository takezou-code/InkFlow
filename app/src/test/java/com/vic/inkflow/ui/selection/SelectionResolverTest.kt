package com.vic.inkflow.ui.selection

import androidx.compose.ui.geometry.Offset
import com.vic.inkflow.data.ImageAnnotation
import com.vic.inkflow.data.Point
import com.vic.inkflow.data.Stroke
import com.vic.inkflow.data.StrokeWithSamples
import com.vic.inkflow.data.TextAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命中判定（純函式）：墨看重心、圖看相交、字看 baseline／頂點。
 *
 * 這些規則刻意與舊版一致——重構可以換寫法，不能換語義。
 */
class SelectionResolverTest {

    private val box = listOf(
        Offset(100f, 100f), Offset(300f, 100f),
        Offset(300f, 300f), Offset(100f, 300f)
    )

    private fun region(page: Int = 0, poly: List<Offset> = box) =
        SelectionRegion(polygonsByPage = mapOf(page to listOf(poly)), srcPage = page)

    private fun stroke(id: String, page: Int, cx: Float, cy: Float): StrokeWithSamples {
        val s = Stroke(
            id = id, documentUri = "u", pageIndex = page, color = 0, strokeWidth = 2f,
            boundsLeft = cx - 10f, boundsTop = cy - 10f,
            boundsRight = cx + 10f, boundsBottom = cy + 10f
        )
        // 兩點重心 = 中點，方便釘住「看重心」而不是「看任一點」。
        return StrokeWithSamples(
            stroke = s,
            points = listOf(
                Point(strokeId = id, x = cx - 10f, y = cy),
                Point(strokeId = id, x = cx + 10f, y = cy)
            )
        )
    }

    private fun image(id: String, page: Int, x: Float, y: Float, w: Float = 80f, h: Float = 80f) =
        ImageAnnotation(
            id = id, documentUri = "u", pageIndex = page, uri = "file://$id",
            modelX = x, modelY = y, modelWidth = w, modelHeight = h
        )

    private fun text(id: String, page: Int, x: Float, y: Float, body: String = "hello") =
        TextAnnotation(
            id = id, documentUri = "u", pageIndex = page,
            text = body, modelX = x, modelY = y, fontSize = 20f
        )

    @Test
    fun strokeCentroidInsideIsSelected() {
        val snapshot = SelectionPageSnapshot(
            page = 0,
            strokes = listOf(stroke("in", 0, 200f, 200f), stroke("out", 0, 500f, 500f))
        )
        val resolved = SelectionResolver.resolve(region(), mapOf(0 to snapshot))
        assertEquals(listOf("in"), resolved.items.strokes.map { it.stroke.id })
        assertTrue(resolved.frame?.page == 0)
    }

    @Test
    fun strokeCentroidOutsideButPointsInsideIsNotSelected() {
        // 一端在圈內、一端在圈外：重心中點落在圈外 → 不選（與舊版一致）。
        val s = StrokeWithSamples(
            stroke = Stroke(
                id = "half", documentUri = "u", pageIndex = 0, color = 0, strokeWidth = 2f,
                boundsLeft = 250f, boundsTop = 200f, boundsRight = 450f, boundsBottom = 200f
            ),
            points = listOf(
                Point(strokeId = "half", x = 250f, y = 200f),
                Point(strokeId = "half", x = 450f, y = 200f)
            )
        )
        val resolved = SelectionResolver.resolve(
            region(),
            mapOf(0 to SelectionPageSnapshot(page = 0, strokes = listOf(s)))
        )
        assertTrue(resolved.items.strokes.isEmpty())
    }

    @Test
    fun imageOverlappingPolygonIsSelected() {
        val snapshot = SelectionPageSnapshot(
            page = 0,
            images = listOf(
                image("hit", 0, x = 250f, y = 250f),
                image("miss", 0, x = 700f, y = 700f)
            )
        )
        val resolved = SelectionResolver.resolve(region(), mapOf(0 to snapshot))
        assertEquals(listOf("hit"), resolved.items.images.map { it.id })
    }

    @Test
    fun textBaselineInsideIsSelected() {
        val snapshot = SelectionPageSnapshot(
            page = 0,
            texts = listOf(
                text("hit", 0, x = 120f, y = 200f),
                text("miss", 0, x = 600f, y = 600f)
            )
        )
        val resolved = SelectionResolver.resolve(region(), mapOf(0 to snapshot))
        assertEquals(listOf("hit"), resolved.items.texts.map { it.id })
    }

    @Test
    fun pageWithoutSnapshotContributesNothing() {
        // 圈到第 1 頁，但快照只有第 0 頁：不能拿第 0 頁的筆充數。
        val snapshot = SelectionPageSnapshot(
            page = 0,
            strokes = listOf(stroke("p0", 0, 200f, 200f))
        )
        val resolved = SelectionResolver.resolve(
            region(page = 1),
            mapOf(0 to snapshot)
        )
        assertTrue(resolved.items.isEmpty)
    }

    @Test
    fun crossPageSelectionCollectsEachPageOnce() {
        val region = SelectionRegion(
            polygonsByPage = mapOf(0 to listOf(box), 1 to listOf(box)),
            srcPage = 0
        )
        val resolved = SelectionResolver.resolve(
            region,
            mapOf(
                0 to SelectionPageSnapshot(page = 0, strokes = listOf(stroke("a", 0, 200f, 200f))),
                1 to SelectionPageSnapshot(page = 1, strokes = listOf(stroke("b", 1, 200f, 200f)))
            )
        )
        assertEquals(listOf("a", "b"), resolved.items.strokes.map { it.stroke.id })
    }

    @Test
    fun duplicateImageHitAcrossPolygonsIsDeduplicated() {
        val poly2 = listOf(
            Offset(200f, 200f), Offset(400f, 200f),
            Offset(400f, 400f), Offset(200f, 400f)
        )
        val region = SelectionRegion(
            polygonsByPage = mapOf(0 to listOf(box, poly2)),
            srcPage = 0
        )
        val snapshot = SelectionPageSnapshot(
            page = 0,
            images = listOf(image("shared", 0, x = 250f, y = 250f))
        )
        val resolved = SelectionResolver.resolve(region, mapOf(0 to snapshot))
        assertEquals(1, resolved.items.images.size)
    }
}
