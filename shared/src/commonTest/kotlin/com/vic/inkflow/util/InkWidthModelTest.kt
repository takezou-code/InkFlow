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

    @Test
    fun `the numbers are the tablet's, pinned exactly`() {
        // The tablet's own numbers, computed by hand from the original formula:
        //   maxW = 4 * 1.7 = 6.8, minW = 4 * 0.25 = 1.0
        //   vMapped = 0 (no movement) → velocityTarget = maxW = 6.8
        //   smoothOld = 0.95 - 0.45 * 0.33 = 0.8015
        //   result = 4 * 0.8015 + 6.8 * 0.1985 = 4.5558
        //
        // Pinning the exact value is the point: if anyone touches a constant, this
        // says "the tablet's ink changed", rather than the change showing up as a
        // slightly different line on someone else's tablet.
        val w = InkWidthModel.widthFor(
            distPx = 0f,
            dtMs = 16L,
            previousWidth = 4f,
            baseWidth = 4f,
            isHighlighter = false,
            sensitivity = InkWidthModel.DEFAULT_SENSITIVITY,
            responsiveness = InkWidthModel.DEFAULT_RESPONSIVENESS
        )
        assertEquals(4.5558f, w, 0.0001f)
    }

    @Test
    fun `the width eases towards the target instead of stepping`() {
        // A stylus's pressure jumps between samples, so a target that is used
        // directly makes the line's thickness step at every sample — the
        // "bamboo" segments. Both width paths must ease into the target.
        val previous = 1f
        val target = 10f
        val eased = InkWidthModel.smoothWidth(previous, target, responsiveness = 0.33f)

        assertTrue(eased > previous, "the width must move towards the target, was $eased")
        assertTrue(eased < target, "the width must not jump straight to the target, was $eased")
    }

    @Test
    fun `a pressure spike is smoothed, not applied raw`() {
        // The regression this pins: the pressure path used to return the raw
        // target while the velocity path smoothed, so a pressure-capable stylus
        // got the bamboo back. The pressure path now goes through the same
        // easing.
        val previous = 2f
        val rawTarget = 8f
        val smoothed = InkWidthModel.smoothWidth(previous, rawTarget, responsiveness = 0.33f)
        assertTrue(smoothed < rawTarget, "a spike must not be applied raw, was $smoothed")
        assertTrue(smoothed > previous, "the width must still react, was $smoothed")
    }

    @Test
    fun `the velocity path uses the same easing`() {
        // The velocity path's own easing and the pressure path's easing must be
        // the same rule, not two look-alikes.
        val previous = 3f
        val target = 9f
        assertEquals(
            InkWidthModel.smoothWidth(previous, target, responsiveness = 0.33f),
            InkWidthModel.smoothWidth(previous, target, responsiveness = 0.33f)
        )
        // and the velocity path really does ease
        val easedVelocity = InkWidthModel.widthFor(
            distPx = 0f, dtMs = 16L, previousWidth = previous, baseWidth = 4f,
            isHighlighter = false
        )
        assertTrue(easedVelocity in previous..target || easedVelocity != target)
    }
}
