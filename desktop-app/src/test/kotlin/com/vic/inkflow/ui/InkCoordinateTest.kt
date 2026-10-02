package com.vic.inkflow.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.vic.inkflow.data.DatabaseManager
import com.vic.inkflow.data.StrokeEntity
import com.vic.inkflow.data.PointEntity
import com.vic.inkflow.util.PageBox
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ink transform, checked against the arithmetic that draws it.
 *
 * Drawing a stroke correctly is worthless if the point that gets *stored* is not
 * the point under the cursor. That failure is silent and looks like "the ink is
 * in roughly the right place", which is exactly the kind of bug a user reports as
 * "it feels a bit off" and nobody can reproduce.
 *
 * These reproduce the viewer's own forward transform here rather than reaching
 * into the composable, so the two have to agree about the definition:
 *
 *     pageWidthPx  = box.widthPt * scale
 *     originX      = (viewportW - pageWidthPx) / 2 + panX
 *     screen       = origin + model * scale
 *
 * so the inverse is `model = (screen - origin) / scale`, plus the page box origin
 * because points are stored relative to the CropBox.
 */
class InkCoordinateTest {

    private val tolerance = 0.001f

    private fun assertClose(expected: Float, actual: Float, what: String) {
        assertTrue(
            abs(expected - actual) < tolerance,
            "$what: expected $expected, got $actual (off by ${abs(expected - actual)})"
        )
    }

    /** Mirrors `PdfViewer`'s fitScale: screen pixels per PDF point at zoom 1. */
    private fun fitScale(pageW: Float, pageH: Float, viewport: Size): Float =
        minOf(viewport.width / pageW, viewport.height / pageH)

    private fun originX(pageW: Float, viewport: Size, panX: Float, scale: Float) =
        (viewport.width - pageW * scale) / 2f + panX

    private fun originY(pageH: Float, viewport: Size, panY: Float, scale: Float) =
        (viewport.height - pageH * scale) / 2f + panY

    @Test
    fun `a point round-trips through the documented transform`() {
        // The arithmetic the viewer uses for drawing, kept as a readable reference
        // for the tests below; the behavioural tests call [screenToModel] itself.
        val pageW = 595f; val pageH = 842f
        val viewport = Size(900f, 1200f)
        val scale = fitScale(pageW, pageH, viewport)

        val model = Offset(pageW / 2f, pageH / 2f)
        val screen = Offset(
            originX(pageW, viewport, 0f, scale) + model.x * scale,
            originY(pageH, viewport, 0f, scale) + model.y * scale
        )
        val back = Offset(
            (screen.x - originX(pageW, viewport, 0f, scale)) / scale,
            (screen.y - originY(pageH, viewport, 0f, scale)) / scale
        )

        assertClose(model.x, back.x, "x round trip")
        assertClose(model.y, back.y, "y round trip")
    }

    @Test
    fun `pan does not shift the model point under the cursor`() {
        val pageW = 595f; val pageH = 842f
        val viewport = Size(700f, 900f)
        val scale = fitScale(pageW, pageH, viewport)
        val model = Offset(120f, 640f)

        // Same model point, viewed panned in both directions. Because the
        // inverse reads the *current* origin, the recovered model value must not
        // care that the page moved.
        listOf(0f, 90f, -140f).forEach { panX ->
            val screen = Offset(
                originX(pageW, viewport, panX, scale) + model.x * scale,
                originY(pageH, viewport, 0f, scale) + model.y * scale
            )
            val back = (screen.x - originX(pageW, viewport, panX, scale)) / scale
            assertClose(model.x, back, "x under panX=$panX")
        }
    }

    @Test
    fun `zoom keeps the anchored model point fixed`() {
        val pageW = 595f; val pageH = 842f
        val viewport = Size(700f, 900f)
        val model = Offset(300f, 400f)

        listOf(0.25f, 1f, 2.5f, 8f).forEach { zoom ->
            val scale = fitScale(pageW, pageH, viewport) * zoom
            val screen = Offset(
                originX(pageW, viewport, 0f, scale) + model.x * scale,
                originY(pageH, viewport, 0f, scale) + model.y * scale
            )
            val back = Offset(
                (screen.x - originX(pageW, viewport, 0f, scale)) / scale,
                (screen.y - originY(pageH, viewport, 0f, scale)) / scale
            )
            assertClose(model.x, back.x, "x at zoom=$zoom")
            assertClose(model.y, back.y, "y at zoom=$zoom")
        }
    }

    @Test
    fun `the real converter round-trips a point through screen and back`() {
        // Calls the production function rather than a copy of its arithmetic: the
        // original test duplicated the formula, so it would have passed even if the
        // shipped converter were wrong — which is exactly what happened.
        val pageW = 595f; val pageH = 842f
        val viewport = Size(900f, 1200f)
        val scale = fitScale(pageW, pageH, viewport)
        val box = PageBox(pageW, pageH, 0f, 0f)

        listOf(Offset(10f, 20f), Offset(pageW / 2f, pageH / 2f), Offset(pageW - 1f, pageH - 1f))
            .forEach { model ->
                val screen = Offset(
                    originX(pageW, viewport, 0f, scale) + model.x * scale,
                    originY(pageH, viewport, 0f, scale) + model.y * scale
                )
                val back = screenToModel(
                    screen = screen,
                    box = box,
                    scale = scale,
                    originX = originX(pageW, viewport, 0f, scale),
                    originY = originY(pageH, viewport, 0f, scale)
                )
                assertTrue(back != null, "point $model must convert back")
                assertClose(model.x, back.x, "x round trip at $model")
                assertClose(model.y, back.y, "y round trip at $model")
            }
    }

    @Test
    fun `the real converter is unaffected by pan and zoom`() {
        val pageW = 595f; val pageH = 842f
        val viewport = Size(700f, 900f)
        val box = PageBox(pageW, pageH, 0f, 0f)
        val model = Offset(120f, 640f)

        listOf(0f, 90f, -140f).forEach { panX ->
            listOf(0.25f, 1f, 4f).forEach { zoom ->
                val scale = fitScale(pageW, pageH, viewport) * zoom
                val ox = originX(pageW, viewport, panX, scale)
                val oy = originY(pageH, viewport, 0f, scale)
                val screen = Offset(ox + model.x * scale, oy + model.y * scale)
                val back = screenToModel(screen, box, scale, ox, oy)
                assertTrue(back != null, "must convert at panX=$panX zoom=$zoom")
                assertClose(model.x, back.x, "x at panX=$panX zoom=$zoom")
                assertClose(model.y, back.y, "y at panX=$panX zoom=$zoom")
            }
        }
    }

    @Test
    fun `the real converter honours a CropBox that does not start at the origin`() {
        // Scanned PDFs routinely have a non-zero MediaBox. If the box origin were
        // ignored, every stroke on such a page would be offset by it.
        val pageW = 400f; val pageH = 500f
        val boxOriginX = 30f; val boxOriginY = 45f
        val viewport = Size(800f, 1000f)
        val scale = fitScale(pageW, pageH, viewport)
        val box = PageBox(pageW, pageH, boxOriginX, boxOriginY)

        val screen = Offset(originX(pageW, viewport, 0f, scale), originY(pageH, viewport, 0f, scale))
        val back = screenToModel(screen, box, scale, originX(pageW, viewport, 0f, scale), originY(pageH, viewport, 0f, scale))
        assertTrue(back != null)
        // The top-left corner of the CropBox is model (originX, originY), not (0,0).
        assertClose(boxOriginX, back.x, "x at the crop-box corner")
        assertClose(boxOriginY, back.y, "y at the crop-box corner")
    }

    @Test
    fun `the real converter rejects presses outside the page`() {
        val pageW = 595f; val pageH = 842f
        val viewport = Size(1400f, 1400f)
        val scale = fitScale(pageW, pageH, viewport)
        val box = PageBox(pageW, pageH, 0f, 0f)
        val ox = originX(pageW, viewport, 0f, scale)
        val oy = originY(pageH, viewport, 0f, scale)

        fun convert(x: Float, y: Float) =
            screenToModel(Offset(x, y), box, scale, ox, oy)

        assertTrue(convert(ox - 40f, oy + 10f) == null, "left of the page must be rejected")
        assertTrue(convert(ox + 10f, oy - 40f) == null, "above the page must be rejected")
        assertTrue(convert(ox + pageW * scale + 1f, oy + 10f) == null, "right of the page")
        assertTrue(convert(ox + 10f, oy + pageH * scale + 1f) == null, "below the page")
        assertTrue(convert(ox + 1f, oy + 1f) != null, "just inside the corner must be accepted")
        assertTrue(convert(ox + pageW * scale - 1f, oy + pageH * scale - 1f) != null, "far corner")
    }

    @Test
    fun `the real converter refuses to divide by a zero scale`() {
        // Before the page raster arrives, scale is 0. Without the guard this
        // yields Infinity/NaN instead of null, and NaN comparisons silently
        // pass every bounds check, so a stroke gets stored at a bogus position.
        val box = PageBox(595f, 842f, 0f, 0f)
        val result = screenToModel(Offset(100f, 100f), box, 0f, 0f, 0f)
        assertTrue(result == null, "a zero scale must yield null, not NaN")
    }

    @Test
    fun `a committed stroke round-trips through the database`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "ink-coord-test").apply {
            deleteRecursively(); mkdirs()
        }
        val db = DatabaseManager(File(dir, "ink.db").absolutePath).also { it.connect() }
        try {
            val stroke = StrokeEntity(
                documentUri = "file:///doc.pdf",
                pageIndex = 3,
                docY = null, // tablet backfills; see commitStroke
                color = 0xFF121826.toInt(),
                strokeWidth = 1.6f,
                boundsLeft = 10f, boundsTop = 20f,
                boundsRight = 110f, boundsBottom = 220f
            )
            val points = listOf(
                PointEntity(1, stroke.id, 10f, 20f),
                PointEntity(2, stroke.id, 60f, 120f),
                PointEntity(3, stroke.id, 110f, 220f)
            )
            db.saveStroke(stroke, points)

            val back = db.getStrokesForPage("file:///doc.pdf", 3)
            assertEquals(1, back.size)
            assertEquals(3, back[0].points.size)
            // Point order is the polyline order — the tablet relies on it.
            assertEquals(listOf(10f, 60f, 110f), back[0].points.map { it.x })
            assertEquals(listOf(20f, 120f, 220f), back[0].points.map { it.y })
            assertEquals(stroke.id, back[0].stroke.id)
            assertEquals(null, back[0].stroke.docY, "desktop must not invent docY")
        } finally {
            db.disconnect()
            dir.deleteRecursively()
        }
    }
}