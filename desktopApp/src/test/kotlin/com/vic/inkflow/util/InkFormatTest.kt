package com.vic.inkflow.util

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ink model, checked against the tablet's.
 *
 * The tablet is the source of truth for the ink format. Two files here are
 * verbatim ports of its code:
 *
 *  - [EnvelopeUtils]  <- `app/.../util/EnvelopeUtils.kt`  (the outline)
 *  - [InkWidthModel]  <- `app/.../ui/InkCanvas.kt` calcWidth (the per-sample width)
 *
 * A mismatch is invisible until two devices disagree about what a stroke looks
 * like, which is exactly the class of bug nobody reports clearly. These tests
 * lock down the parts that are easy to "tidy" into being wrong.
 */
class InkFormatTest {

    /**
     * Re-derives the expected widths straight from the tablet's formula, written
     * out independently rather than calling the implementation. If the two ever
     * disagree, the port has drifted.
     */
    private fun tabletWidth(
        distPx: Float,
        dtMs: Long,
        prevWidth: Float,
        baseWidth: Float,
        highlighter: Boolean,
        sensitivity: Float = 0.80f,
        responsiveness: Float = 0.33f
    ): Float {
        if (highlighter) return baseWidth
        val dt = dtMs.coerceAtLeast(1L).toFloat()
        val velocity = distPx / dt
        val sens = sensitivity.coerceAtLeast(0.1f)
        val maxW = baseWidth * 1.7f
        val minW = baseWidth * 0.25f
        val smoothOld = 0.95f - 0.45f * responsiveness.coerceIn(0f, 1f)
        val threshold = 0.7f / sens
        val vMapped = (velocity / threshold).coerceIn(0f, 1f)
        val target = minW + (maxW - minW) * (1f - vMapped)
        return prevWidth * smoothOld + target * (1f - smoothOld)
    }

    @Test
    fun `width matches the tablet formula`() {
        val base = 1.6f
        listOf(0L, 8L, 16L, 120L).forEach { dt ->
            listOf(0.5f, 3f, 18f, 90f).forEach { dist ->
                listOf(base * 0.25f, base, base * 1.7f).forEach { prev ->
                    val got = InkWidthModel.widthFor(dist, dt, prev, base, isHighlighter = false)
                    val want = tabletWidth(dist, dt, prev, base, highlighter = false)
                    assertTrue(
                        abs(got - want) < 1e-4f,
                        "dist=$dist dt=$dt prev=$prev: got $got, tablet would give $want"
                    )
                }
            }
        }
    }

    @Test
    fun `a slow pen is thick and a fast pen is thin`() {
        val base = 1.6f
        var w = base
        // Settle at a slow pace.
        repeat(40) { w = InkWidthModel.widthFor(0.4f, 16L, w, base, isHighlighter = false) }
        val slow = w

        var w2 = base
        repeat(40) { w2 = InkWidthModel.widthFor(120f, 8L, w2, base, isHighlighter = false) }
        val fast = w2

        assertTrue(
            slow > fast * 1.5f,
            "a slow stroke ($slow) must be clearly thicker than a fast one ($fast)"
        )
        assertTrue(slow <= base * 1.7f + 1e-3f, "width must not exceed 1.7x base ($slow)")
        assertTrue(fast >= base * 0.25f - 1e-3f, "width must not fall below 0.25x base ($fast)")
    }

    @Test
    fun `highlighter never varies in width`() {
        val base = 8f
        listOf(0f, 5f, 500f).forEach { dist ->
            assertEquals(base, InkWidthModel.widthFor(dist, 8L, base * 1.7f, base, isHighlighter = true))
        }
    }

    @Test
    fun `a zero or negative dt cannot explode the width`() {
        // Coalesced pointer events can report the same timestamp. The tablet
        // coerces dt to at least 1ms; without that this produces Infinity and
        // then a NaN that silently passes every bounds check downstream.
        val base = 1.6f
        val w = InkWidthModel.widthFor(50f, 0L, base, base, isHighlighter = false)
        assertTrue(w.isFinite(), "width must stay finite, got $w")
        assertTrue(w <= base * 1.7f + 1e-3f)
    }

    @Test
    fun `a single sample produces a disc`() {
        val p = StrokePoint(10f, 10f, 4f)
        val path = EnvelopeUtils.generateEnvelopePath(listOf(p))
        // The tablet adds an oval of radius width/2, so the disc's diameter equals
        // the stroke width — a dot is the same thickness as the line it starts.
        val b = path.getBounds()
        assertEquals(4f, b.width, 0.01f)
        assertEquals(4f, b.height, 0.01f)
        assertEquals(8f, b.left, 0.01f, "centred on the sample")
        assertEquals(8f, b.top, 0.01f)
    }

    @Test
    fun `the envelope covers the whole stroke including the width`() {
        // A straight horizontal stroke of width 4 must produce a shape exactly
        // 4 tall — not 0 (a centreline) and not more than 4.
        val pts = (0..10).map { StrokePoint(it * 5f, 0f, 4f) }
        val b = EnvelopeUtils.generateEnvelopePath(pts).getBounds()
        assertEquals(4f, b.height, 0.05f)
        assertEquals(10f * 5f + 4f, b.width, 0.2f)
    }

    @Test
    fun `a tapering stroke really does taper`() {
        // Thick start, thin end. This is the whole point of the envelope: the old
        // renderer drew one Stroke() at a single width and lost the taper, which
        // flattened every brush stroke into a hairline.
        val pts = (0..20).map { i -> StrokePoint(i * 4f, 0f, if (i < 10) 9f else 1f) }
        val b = EnvelopeUtils.generateEnvelopePath(pts).getBounds()
        assertTrue(b.height > 8f, "the thick half must be ~9 tall, got ${b.height}")
    }

    @Test
    fun `an empty stroke produces an empty path`() {
        val b = EnvelopeUtils.generateEnvelopePath(emptyList()).getBounds()
        assertTrue(b.width == 0f && b.height == 0f)
    }

    @Test
    fun `coincident samples do not produce NaN bounds`() {
        // A double-click or a stuttering mouse yields repeated identical points;
        // the tangent search skips them, so every normal degenerates.
        val pts = listOf(
            StrokePoint(5f, 5f, 3f),
            StrokePoint(5f, 5f, 3f),
            StrokePoint(5f, 5f, 3f)
        )
        val b = EnvelopeUtils.generateEnvelopePath(pts).getBounds()
        assertTrue(!b.left.isNaN() && !b.top.isNaN(), "bounds must not be NaN: $b")
        assertTrue(b.width.isFinite() && b.height.isFinite())
    }
}