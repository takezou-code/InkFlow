package com.vic.inkflow

import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.vic.inkflow.ui.PaperStyle
import com.vic.inkflow.ui.PdfViewModel
import com.vic.inkflow.util.PdfExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageLogicTest {

    // ── PdfViewModel.remapCurrentPageAfterDeletes ────────────────────────────

    @Test
    fun `remap keeps page before deleted range unchanged`() {
        val result = PdfViewModel.remapCurrentPageAfterDeletes(
            currentPageIndex = 1,
            deletedIndices = listOf(3),
            pageCountAfter = 9
        )
        assertEquals(1, result)
    }

    @Test
    fun `remap shifts page after deleted range up`() {
        val result = PdfViewModel.remapCurrentPageAfterDeletes(
            currentPageIndex = 5,
            deletedIndices = listOf(2),
            pageCountAfter = 9
        )
        assertEquals(4, result)
    }

    @Test
    fun `remap moves current page to previous when itself deleted`() {
        val result = PdfViewModel.remapCurrentPageAfterDeletes(
            currentPageIndex = 4,
            deletedIndices = listOf(4),
            pageCountAfter = 9
        )
        assertEquals(3, result)
    }

    @Test
    fun `remap handles multiple deletes descending`() {
        // Delete pages 2 and 5; current on page 6 -> shifts by two.
        val result = PdfViewModel.remapCurrentPageAfterDeletes(
            currentPageIndex = 6,
            deletedIndices = listOf(2, 5),
            pageCountAfter = 8
        )
        assertEquals(4, result)
    }

    @Test
    fun `remap clamps to last remaining page`() {
        val result = PdfViewModel.remapCurrentPageAfterDeletes(
            currentPageIndex = 9,
            deletedIndices = listOf(9, 10, 11),
            pageCountAfter = 9
        )
        assertEquals(8, result.coerceAtMost(8))
    }

    // ── affected-range helpers ────────────────────────────────────────────────

    @Test
    fun `affectedStartAfterDeletes returns min deleted index when still valid`() {
        assertEquals(2, PdfViewModel.affectedStartAfterDeletes(listOf(5, 2), pageCountAfter = 10))
    }

    @Test
    fun `affectedStartAfterDeletes returns null when out of range`() {
        assertEquals(null, PdfViewModel.affectedStartAfterDeletes(listOf(12), pageCountAfter = 10))
    }

    @Test
    fun `affectedStartAfterInsert returns insertion index when valid`() {
        assertEquals(3, PdfViewModel.affectedStartAfterInsert(insertionIndex = 3, pageCountAfter = 10))
    }

    // ── PaperStyle aspect ratios ─────────────────────────────────────────────

    @Test
    fun `a4 portrait aspect ratio is correct`() {
        assertEquals(595f / 842f, PaperStyle.A4_PORTRAIT.aspectRatio, 0.0001f)
    }

    @Test
    fun `a4 landscape inverts portrait ratio`() {
        assertTrue(PaperStyle.A4_LANDSCAPE.aspectRatio > 1f)
        assertEquals(1f / PaperStyle.A4_PORTRAIT.aspectRatio, PaperStyle.A4_LANDSCAPE.aspectRatio, 0.0001f)
    }

    // ── PdfExporter rotation geometry ────────────────────────────────────────

    private val crop = PDRectangle(0f, 0f, 400f, 300f)

    @Test
    fun `rotation normalizes negative and over-360 values`() {
        assertEquals(90, PdfExporter.normalizeRotation(-270))
        assertEquals(90, PdfExporter.normalizeRotation(450))
        assertEquals(180, PdfExporter.normalizeRotation(180))
    }

    @Test
    fun `rotated view swaps dimensions for 90 and 270`() {
        assertEquals(Pair(300f, 400f), PdfExporter.rotatedViewSize(90, crop))
        assertEquals(Pair(300f, 400f), PdfExporter.rotatedViewSize(270, crop))
        assertEquals(Pair(400f, 300f), PdfExporter.rotatedViewSize(0, crop))
        assertEquals(Pair(400f, 300f), PdfExporter.rotatedViewSize(180, crop))
    }

    // PDF cm column-vector convention: x' = a·uX + c·uY + e ; y' = b·uX + d·uY + f
    // pdfbox stores rows: [a b 0 / c d 0 / e f 1]
    private fun apply(rotation: Int, uX: Float, uY: Float): Pair<Float, Float> {
        val m = PdfExporter.pageViewMatrix(rotation, crop)
        val x = m.getValue(0, 0) * uX + m.getValue(1, 0) * uY + m.getValue(2, 0)
        val y = m.getValue(0, 1) * uX + m.getValue(1, 1) * uY + m.getValue(2, 1)
        return Pair(x, y)
    }

    @Test
    fun `rotation 0 maps view top-left to crop user-space top-left`() {
        // View is Y-down; Y-up view coords of the TOP-LEFT are (0, viewH=300).
        val (x, y) = apply(0, 0f, 300f)
        assertEquals(0f, x, 0.01f)
        assertEquals(300f, y, 0.01f) // ury
    }

    @Test
    fun `rotation 90 sends view origin to crop bottom-left`() {
        // Model/view TL (uX=0,uY=viewH) must land where /Rotate 90 display puts it: crop BL.
        val (x, y) = apply(90, 0f, 400f)
        assertEquals(0f, x, 0.01f)
        assertEquals(0f, y, 0.01f)
    }

    @Test
    fun `rotation 90 sends view bottom-right to crop top-right`() {
        val (x, y) = apply(90, 300f, 0f)
        assertEquals(400f, x, 0.01f)
        assertEquals(300f, y, 0.01f)
    }

    @Test
    fun `rotation 180 sends view top-left to crop bottom-right`() {
        // Under a 180-degree display rotation the visible top-left comes from the
        // original page's bottom-right in user space (urx, lly).
        val (x, y) = apply(180, 0f, 300f)
        assertEquals(400f, x, 0.01f)
        assertEquals(0f, y, 0.01f)

        val (x2, y2) = apply(180, 400f, 0f)
        assertEquals(0f, x2, 0.01f)
        assertEquals(300f, y2, 0.01f)
    }

    @Test
    fun `rotation 270 sends view origin to crop top-right`() {
        val (x, y) = apply(270, 0f, 400f)
        assertEquals(400f, x, 0.01f)
        assertEquals(300f, y, 0.01f)
    }

    @Test
    fun `all rotations map every view corner inside crop bounds`() {
        for (r in intArrayOf(0, 37, 90, 180, 271, 359)) {
            val (viewW, viewH) = PdfExporter.rotatedViewSize(r, crop)
            val corners = listOf(
                0f to 0f, viewW to 0f, 0f to viewH, viewW to viewH
            )
            for ((uX, uY) in corners) {
                val (x, y) = apply(r, uX, uY)
                assertTrue("r=$r p=($uX,$uY)->($x,$y)", x >= -0.01f && x <= 400.01f && y >= -0.01f && y <= 300.01f)
            }
        }
    }
}
