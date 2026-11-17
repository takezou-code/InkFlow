package com.vic.inkflow.ui.selection

import androidx.compose.ui.geometry.Offset
import com.vic.inkflow.data.Point
import com.vic.inkflow.data.Stroke
import com.vic.inkflow.data.StrokeWithSamples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 套索幾何是純函式：手勢點 → 分頁／裁剪 → 區域。
 *
 * 這裡釘住「跨頁怎麼切」「退化輸入怎麼退」「換頁怎麼繞」，
 * 避免重構時把舊版的邊界行為順手改掉。
 */
class LassoGeometryTest {

    private fun stroke(
        id: String,
        page: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): StrokeWithSamples {
        val s = Stroke(
            id = id,
            documentUri = "u",
            pageIndex = page,
            color = 0,
            strokeWidth = 2f,
            boundsLeft = left,
            boundsTop = top,
            boundsRight = right,
            boundsBottom = bottom
        )
        return StrokeWithSamples(
            stroke = s,
            points = listOf(Point(strokeId = id, x = left, y = bottom))
        )
    }

    @Test
    fun simplifyRemovesCollinearMiddlePoint() {
        val out = LassoGeometry.simplify(
            listOf(Offset(0f, 0f), Offset(5f, 0f), Offset(10f, 0f), Offset(10f, 10f)),
            tolerance = 1f
        )
        assertEquals(3, out.size)
        assertEquals(Offset(0f, 0f), out[0])
        assertEquals(Offset(10f, 0f), out[1])
        assertEquals(Offset(10f, 10f), out[2])
    }

    @Test
    fun simplifyDropsDuplicateSamples() {
        val out = LassoGeometry.simplify(
            listOf(Offset(0f, 0f), Offset(0f, 0f), Offset(10f, 0f), Offset(10f, 0f)),
            tolerance = 1f
        )
        assertEquals(2, out.size)
    }

    @Test
    fun freeformSinglePageMapsToModelSpace() {
        // canvas 400×300 ↔ model 200×150（2:1）
        val region = LassoGeometry.fromFreeform(
            pointsCanvas = listOf(
                Offset(40f, 30f), Offset(360f, 30f),
                Offset(360f, 270f), Offset(40f, 270f)
            ),
            srcPage = 0,
            pageCount = 1,
            gapPx = 0f,
            canvasW = 400f,
            canvasH = 300f,
            modelW = 200f,
            modelH = 150f
        )
        assertNotNull(region)
        assertEquals(0, region!!.srcPage)
        assertEquals(setOf(0), region.polygonsByPage.keys)
        val poly = region.srcPolygon
        assertTrue(poly.size >= 3)
        assertTrue(poly.all { it.x in 0f..200f && it.y in 0f..150f })
        // 對角保留：左上 → 右下
        assertEquals(20f, poly.minOf { it.x }, 0.001f)
        assertEquals(135f, poly.maxOf { it.y }, 0.001f)
    }

    @Test
    fun freeformCrossingPagesSplitsPerPage() {
        // 同一頁至少 3 點才會閉合；跨頁 loop 在每頁各留一段。
        val canvasH = 300f
        val region = LassoGeometry.fromFreeform(
            pointsCanvas = listOf(
                Offset(50f, 50f), Offset(350f, 50f), Offset(350f, 250f), Offset(50f, 250f),
                Offset(50f, 350f), Offset(350f, 350f), Offset(350f, 550f), Offset(50f, 550f)
            ),
            srcPage = 0,
            pageCount = 2,
            gapPx = 0f,
            canvasW = 400f,
            canvasH = canvasH,
            modelW = 400f,
            modelH = canvasH
        )
        assertNotNull(region)
        assertEquals(setOf(0, 1), region!!.polygonsByPage.keys)
        region.polygonsByPage.forEach { (_, polys) ->
            assertTrue(polys.isNotEmpty())
            polys.forEach { poly ->
                assertTrue(poly.size >= 3)
                assertTrue(poly.all { it.y in 0f..canvasH })
            }
        }
    }

    @Test
    fun freeformDegenerateReturnsNull() {
        assertNull(
            LassoGeometry.fromFreeform(
                pointsCanvas = listOf(Offset(1f, 1f), Offset(2f, 2f)),
                srcPage = 0, pageCount = 1, gapPx = 0f,
                canvasW = 400f, canvasH = 300f, modelW = 200f, modelH = 150f
            )
        )
        assertNull(
            LassoGeometry.fromFreeform(
                pointsCanvas = listOf(Offset(10f, 10f), Offset(10f, 10f), Offset(10f, 10f)),
                srcPage = 0, pageCount = 1, gapPx = 0f,
                canvasW = 400f, canvasH = 300f, modelW = 200f, modelH = 150f
            )
        )
    }

    @Test
    fun rectCrossingPagesProducesQuadPerPage() {
        val region = LassoGeometry.fromRect(
            startCanvas = Offset(50f, 200f),
            endCanvas = Offset(350f, 400f),
            srcPage = 0,
            pageCount = 2,
            gapPx = 0f,
            canvasW = 400f,
            canvasH = 300f,
            modelW = 400f,
            modelH = 300f
        )
        assertNotNull(region)
        assertEquals(setOf(0, 1), region!!.polygonsByPage.keys)
        region.polygonsByPage.forEach { (_, polys) ->
            assertEquals(1, polys.size)
            assertEquals(4, polys.first().size)
            assertTrue(polys.first().all { it.y in 0f..300f })
        }
    }

    @Test
    fun rectZeroAreaReturnsNull() {
        assertNull(
            LassoGeometry.fromRect(
                startCanvas = Offset(50f, 50f),
                endCanvas = Offset(50f, 50f),
                srcPage = 0, pageCount = 1, gapPx = 0f,
                canvasW = 400f, canvasH = 300f, modelW = 400f, modelH = 300f
            )
        )
    }

    @Test
    fun shiftRegionWrapsPageAndLocalY() {
        val region = SelectionRegion(
            polygonsByPage = mapOf(
                0 to listOf(
                    listOf(Offset(10f, 20f), Offset(30f, 20f), Offset(30f, 40f))
                )
            ),
            srcPage = 0
        )
        val shifted = LassoGeometry.shiftRegion(region, pageShift = 1, pageHeight = 300f)
        assertEquals(1, shifted.srcPage)
        assertEquals(setOf(1), shifted.polygonsByPage.keys)
        val poly = shifted.polygonsByPage.getValue(1).first()
        assertEquals(10f, poly[0].x, 0.001f)
        assertEquals(20f - 300f, poly[0].y, 0.001f)
    }

    @Test
    fun frameOfUsesDominantPage() {
        val items = SelectionItems(
            strokes = listOf(
                stroke("a", page = 0, 10f, 10f, 20f, 20f),
                stroke("b", page = 1, 100f, 100f, 140f, 150f),
                stroke("c", page = 1, 120f, 110f, 160f, 170f)
            )
        )
        val frame = LassoGeometry.frameOf(items, region = null)
        assertNotNull(frame)
        assertEquals(1, frame!!.page)
        assertEquals(100f, frame.bounds.left, 0.001f)
        assertEquals(170f, frame.bounds.bottom, 0.001f)
    }

    @Test
    fun frameOfFallsBackToBlankRegion() {
        val region = SelectionRegion(
            polygonsByPage = mapOf(
                2 to listOf(
                    listOf(Offset(10f, 10f), Offset(90f, 10f), Offset(90f, 70f), Offset(10f, 70f))
                )
            ),
            srcPage = 2
        )
        val frame = LassoGeometry.frameOf(SelectionItems(), region)
        assertNotNull(frame)
        assertEquals(2, frame!!.page)
        assertEquals(10f, frame.bounds.left, 0.001f)
        assertEquals(90f, frame.bounds.right, 0.001f)
    }
}
