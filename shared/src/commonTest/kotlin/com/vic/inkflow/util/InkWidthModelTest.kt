package com.vic.inkflow.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ink width rule — pinned, because it used to be a hand-copy.
 *
 * The desktop's `InkWidthModel` carried the tablet's constants and smoothing as a
 * transcription, with a comment saying "if either side is tuned, the other has to
 * be tuned to match or the same gesture produces different ink on each device".
 *
 * Now the rule lives in one place (`:shared`), and this test runs on every
 * platform: desktop, Android host, iOS.
 */
class InkWidthModelTest {

    private val base = 4f

    @Test
    fun `a highlighter never varies in thickness`() {
        // A translucent highlighter that thins on fast strokes stops reading as a
        // marker and starts reading as a gap.
        assertEquals(base, InkWidthModel.widthFor(200f, 8L, 1f, base, isHighlighter = true))
        assertEquals(base, InkWidthModel.widthFor(0f, 500L, 9f, base, isHighlighter = true))
    }

    @Test
    fun `a slow stroke is at the thick end and a fast one at the thin end`() {
        val slow = InkWidthModel.widthFor(1f, 100L, base, base, isHighlighter = false)
        val fast = InkWidthModel.widthFor(120f, 8L, base, base, isHighlighter = false)
        assertTrue(slow > fast, "slow=$slow should be thicker than fast=$fast")
        // The range is 0.25x .. 1.7x of the base width.
        assertTrue(slow <= base * 1.7f, "slow=$slow exceeds the 1.7x cap")
        assertTrue(fast >= base * 0.25f, "fast=$fast is below the 0.25x floor")
    }

    @Test
    fun `a zero time delta cannot divide by zero`() {
        // A coalesced event can report dt = 0; the velocity would be infinite and
        // the width would be NaN.
        val w = InkWidthModel.widthFor(10f, 0L, base, base, isHighlighter = false)
        assertTrue(w.isFinite(), "width was $w")
    }

    @Test
    fun `the width eases instead of stepping`() {
        // The previous sample is weighted heavily (smoothOld = 0.95 - 0.45*r), so a
        // sudden speed change does not jump the width. A mouse that jitters would
        // otherwise draw visible "bamboo" segments.
        val first = InkWidthModel.widthFor(0f, 16L, base, base, isHighlighter = false)
        val sudden = InkWidthModel.widthFor(400f, 8L, first, base, isHighlighter = false)
        val target = base * 0.25f
        assertTrue(
            sudden > target,
            "the width should not jump straight to the floor, was $sudden (floor $target)"
        )
    }

    @Test
    fun `the defaults are the ones the settings screen shows`() {
        assertEquals(0.80f, InkWidthModel.DEFAULT_SENSITIVITY)
        assertEquals(0.33f, InkWidthModel.DEFAULT_RESPONSIVENESS)
    }
}
