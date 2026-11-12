package com.vic.inkflow.util

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toPixelMap
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What the outline looks like when it is actually **filled**, not just measured.
 *
 * ## Why this exists
 *
 * The shared renderer emits the stroke as a *union of independent subpaths* — a disc
 * at every sample, a swept quad per segment. Under the non-zero winding rule that is
 * only correct while every subpath winds the same way: an oval wound the other way
 * from a quad would cancel to winding 0 in the overlap, and the fill would punch a
 * transparent hole exactly where two pieces of ink meet. No bounds test can see that,
 * and it would look like the tablet "eats" the middle of fast strokes.
 *
 * So this test fills the real path and reads the pixels back. It also pins the other
 * half of the contract: no ink may appear outside the nib's sweep, i.e. the shape may
 * only be smoother than the polyline, never fatter than the pen.
 */
class EnvelopeRenderTest {

    private val r = 5f

    private fun render(points: List<StrokePoint>, w: Int, h: Int): ImageBitmap {
        val image = ImageBitmap(w, h)
        val canvas = Canvas(image)
        canvas.drawRect(Rect(0f, 0f, w.toFloat(), h.toFloat()), Paint().apply { color = Color.White })
        canvas.drawPath(
            EnvelopeUtils.generateEnvelopePath(points),
            // No anti-aliasing: the question is binary (ink or no ink), and AA would
            // blur the very edge this test is about.
            Paint().apply {
                color = Color.Black
                isAntiAlias = false
            }
        )
        return image
    }

    private fun isInk(map: androidx.compose.ui.graphics.PixelMap, x: Int, y: Int): Boolean {
        val c = map[x, y]
        return c.red < 0.5f && c.green < 0.5f && c.blue < 0.5f
    }

    private fun distToPolyline(qx: Float, qy: Float, poly: List<StrokePoint>): Float {
        var best = Float.MAX_VALUE
        for (i in 0 until poly.size - 1) {
            val a = poly[i]
            val b = poly[i + 1]
            val vx = b.x - a.x
            val vy = b.y - a.y
            val vv = vx * vx + vy * vy
            val t = if (vv < 1e-6f) 0f
            else (((qx - a.x) * vx + (qy - a.y) * vy) / vv).coerceIn(0f, 1f)
            val d = hypot(qx - (a.x + vx * t), qy - (a.y + vy * t))
            if (d < best) best = d
        }
        return best
    }

    @Test
    fun `overlapping pieces of ink cannot cancel into holes`() {
        // A fast V: adjacent quads and the corner disc overlap heavily, which is where
        // a winding mismatch would show as a bite out of the stroke.
        val pts = (0..4).map { StrokePoint(100f + it * 50f, 90f, 10f) } +
            (0..4).map { StrokePoint(300f + it * 50f, 90f + it * 50f, 10f) } +
            (0..4).map { StrokePoint(500f - it * 50f, 290f, 10f) }

        val w = 620
        val h = 420
        val map = render(pts, w, h).toPixelMap()

        var checked = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val d = distToPolyline(x + 0.5f, y + 0.5f, pts)
                if (d <= r * 0.9f) {
                    checked++
                    assertTrue(isInk(map, x, y), "hole at ($x, $y): on the stroke, $d px from it, but not filled")
                }
            }
        }
        assertTrue(checked > 1000, "test drew almost nothing ($checked pixels) — it would pass vacuously")
    }

    @Test
    fun `no ink appears outside the nib sweep`() {
        // Same stroke, other side of the contract: smoothing may bend inside the
        // capsule, never outside it.
        val pts = (0..4).map { StrokePoint(100f + it * 50f, 90f, 10f) } +
            (0..4).map { StrokePoint(300f + it * 50f, 90f + it * 50f, 10f) } +
            (0..4).map { StrokePoint(500f - it * 50f, 290f, 10f) }

        val w = 620
        val h = 420
        val map = render(pts, w, h).toPixelMap()

        var checked = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val d = distToPolyline(x + 0.5f, y + 0.5f, pts)
                if (d > r + 1.2f) {
                    checked++
                    assertTrue(
                        !isInk(map, x, y),
                        "stray ink at ($x, $y): $d px from the stroke, pen radius is $r"
                    )
                }
            }
        }
        assertTrue(checked > 1000, "test checked almost nothing ($checked pixels)")
    }

    @Test
    fun `a tapered stroke keeps its width and its taper`() {
        // Width per sample is the whole reason this function exists; a filled path that
        // drops intermediate radii would erase the pressure response.
        val pts = (0..10).map { StrokePoint(60f + it * 30f, 100f, 4f + it * 2f) }
        val map = render(pts, 420, 220).toPixelMap()

        fun extentAt(x: Float, dir: Int): Float {
            var last = -1f
            var t = 0f
            while (t < 60f) {
                val y = 100f + dir * t
                if (y >= 0 && y < 220 && isInk(map, x.toInt(), y.toInt())) last = t
                t += 0.5f
            }
            return last
        }

        val atStart = extentAt(60f, 1)
        val atEnd = extentAt(360f, 1)
        assertTrue(atStart in 1f..3f, "start half-width = $atStart, expected 2")
        assertTrue(atEnd in 10f..14f, "end half-width = $atEnd, expected 12")
    }
}
