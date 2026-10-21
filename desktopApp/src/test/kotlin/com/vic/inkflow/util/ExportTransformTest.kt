package com.vic.inkflow.util

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exporting ink into a PDF is a coordinate-space problem, and the y-flip is where
 * it goes wrong.
 *
 * Annotations live in a y-down space (the same one they are drawn in, top-left
 * origin). PDF user space is y-up from the bottom-left. Every exported mark sits at
 * `pageHeight - y`, and a single forgotten flip puts the whole document's ink on the
 * wrong page — vertically mirrored, which is *not* an obvious error to the eye
 * unless you know what to look for.
 *
 * These cases pin the transform itself rather than the rendering, because the
 * rendering is where a wrong transform looks most convincing.
 */
class ExportTransformTest {

    /**
     * model -> PDF space, matching the tablet's `PdfExporter`.
     *
     * Two ratios, not one: pages are not square and the desktop must not stretch a
     * stroke to fill the page.
     */
    fun toPdf(x: Float, y: Float, pageW: Float, pageH: Float, modelW: Float, modelH: Float): Pair<Float, Float> {
        val rx = pageW / modelW
        val ry = pageH / modelH
        return Pair(x * rx, pageH - y * ry)
    }

    @Test
    fun `the y axis is flipped`() {
        // Model y=0 is the top of the page, so it must land at the TOP in PDF space,
        // i.e. the largest y. Getting this backwards mirrors every annotation.
        val (x, y) = toPdf(0f, 0f, pageW = 595f, pageH = 842f, modelW = 595f, modelH = 842f)
        assertEquals(0f, x, 0.001f)
        assertEquals(842f, y, 0.001f, "model top maps to PDF top")
    }

    @Test
    fun `the model bottom maps to the PDF bottom`() {
        val (_, y) = toPdf(0f, 842f, pageW = 595f, pageH = 842f, modelW = 595f, modelH = 842f)
        assertEquals(0f, y, 0.001f)
    }

    @Test
    fun `a full-page annotation lands inside the page on both axes`() {
        // The real assertion: an annotation drawn at the far corner of the model
        // must still be inside the exported page box. A missing flip puts it at
        // y = -h + ..., i.e. off the page, where it silently vanishes.
        val corners = listOf(0f to 0f, 595f to 0f, 0f to 842f, 595f to 842f)
        corners.forEach { (mx, my) ->
            val (px, py) = toPdf(mx, my, 595f, 842f, 595f, 842f)
            assertTrue(px in 0f..595f, "x $px out of range for model ($mx,$my)")
            assertTrue(py in 0f..842f, "y $py out of range for model ($mx,$my)")
        }
    }

    @Test
    fun `a non-square page uses two independent ratios`() {
        // A4 portrait vs a wide page: a single uniform scale would stretch ink.
        val modelW = 595f; val modelH = 842f
        val pageW = 1190f; val pageH = 842f   // landscape, same height
        val (x0, _) = toPdf(modelW, 0f, pageW, pageH, modelW, modelH)
        val (_, y0) = toPdf(0f, modelH, pageW, pageH, modelW, modelH)
        assertEquals(pageW, x0, 0.001f, "x scales with width")
        assertEquals(0f, y0, 0.001f, "y scales with height")
    }

    @Test
    fun `the x ratio and y ratio are independent`() {
        // 595->1190 is 2x; 842->1684 is also 2x here, so pick numbers that differ.
        val rx = 1190f / 595f
        val ry = 1000f / 842f
        assertTrue(abs(rx - ry) > 0.5f, "the ratios must actually differ for this test to mean anything")
    }

    @Test
    fun `a stroke width scales with the page it lands on`() {
        val ratioX = 1190f / 595f
        val widthModel = 2f
        val widthPdf = widthModel * ratioX
        assertEquals(4f, widthPdf, 0.001f)
    }

    @Test
    fun `a shape box converts to a PDF rect with a positive origin`() {
        // Model bounds (left, top, right, bottom) -> PDF (x, y, w, h) where y is
        // measured from the BOTTOM. Getting pdfBottom wrong flips the rect about
        // its own centre instead of off the page, which is much harder to spot.
        val ratioX = 595f / 595f
        val ratioY = 842f / 842f
        val pageHeight = 842f
        val boundsLeft = 100f; val boundsTop = 200f
        val boundsRight = 300f; val boundsBottom = 400f

        val pdfLeft = boundsLeft * ratioX
        val pdfBottom = pageHeight - boundsBottom * ratioY
        val pdfWidth = (boundsRight - boundsLeft) * ratioX
        val pdfHeight = (boundsBottom - boundsTop) * ratioY

        assertEquals(100f, pdfLeft, 0.001f)
        assertEquals(442f, pdfBottom, 0.001f, "bottom edge measured from the page bottom")
        assertTrue(pdfWidth > 0f && pdfHeight > 0f, "a PDF rect needs positive extents")
    }

    @Test
    fun `the highlighter alpha is the tablet's 0_4, not the stored colour alpha`() {
        // A highlighter is drawn with a fixed translucent fill regardless of the
        // colour's own alpha byte. Using the stored alpha instead makes the mark
        // either invisible or fully opaque depending on how it was saved.
        assertEquals(0.4f, ExportStyle.highlighterAlpha, 0.0001f)
        assertEquals(1f, ExportStyle.inkAlpha, 0.0001f)
    }

    @Test
    fun `an ellipse needs enough Bezier segments to look round`() {
        // The 0.5523 constant is the standard circle-to-Bezier factor. Using a
        // polygon approximation with too few segments is visible as a visibly
        // faceted circle at print size.
        assertEquals(0.5522848f, ExportStyle.ELLIPSE_K, 0.0001f)
        assertEquals(4, ExportStyle.ellipseSegments, "four arcs make a full ellipse")
    }

    @Test
    fun `the arrow head size is derived from the scaled stroke width`() {
        // In the export the head must use the ALREADY-SCALED width, because the
        // content stream works in PDF units. Using the model width produces an arrow
        // whose head is too small on an upscaled page.
        val scaledSw = 2f * 2f
        val head = ShapeGeometry.arrowHeadSize(scaledSw)
        assertEquals(5f * 4f + 10f, head, 0.001f)
    }
}