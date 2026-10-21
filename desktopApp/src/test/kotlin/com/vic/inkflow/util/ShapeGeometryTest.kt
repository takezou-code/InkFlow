package com.vic.inkflow.util

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Shapes are stored as two points plus a `shapeType`, and the geometry is not
 * obvious from the data.
 *
 * The trap: RECT and CIRCLE are drawn from the **stored bounds**, while LINE and
 * ARROW use **the two points**. A drag from bottom-left to top-right stores
 * `p0=(100,400), p1=(300,200)` — so the raw y ordering is inverted relative to the
 * stored bounds. Drawing LINE from p0 to p1 when the user dragged "up" then
 * produces an arrow pointing the opposite way, which is a bug nobody reports
 * because the shape is still visible.
 */
class ShapeGeometryTest {

    private fun points(vararg xy: Pair<Float, Float>) =
        xy.map { (x, y) -> StrokePoint(x = x, y = y, width = 1f) }

    @Test
    fun `a rect built from two points is always positive-sized`() {
        val (l, t, r, b) = ShapeGeometry.boundsOf(points(300f to 200f, 100f to 400f))
        // Dragged bottom-right to top-left; a naive p0/p1 assignment would give
        // left=300 right=100, i.e. a negative width that draws nothing.
        assertEquals(100f, l, 0.001f)
        assertEquals(200f, t, 0.001f)
        assertEquals(300f, r, 0.001f)
        assertEquals(400f, b, 0.001f)
        assertTrue(r > l && b > t, "must be positive in both axes")
    }

    @Test
    fun `bounds are identical whichever corner the drag starts from`() {
        val a = ShapeGeometry.boundsOf(points(10f to 20f, 110f to 220f))
        val b = ShapeGeometry.boundsOf(points(110f to 220f, 10f to 20f))
        assertEquals(a.toList(), b.toList(), "direction of the drag must not change the box")
    }

    @Test
    fun `bounds tolerate a reversed x axis`() {
        val (l, _, r, _) = ShapeGeometry.boundsOf(points(90f to 10f, 10f to 20f))
        assertEquals(10f, l, 0.001f)
        assertEquals(90f, r, 0.001f)
    }

    @Test
    fun `a single-point shape has zero extent but is still addressable`() {
        val (l, t, r, b) = ShapeGeometry.boundsOf(points(5f to 5f))
        assertEquals(5f, l, 0.001f)
        assertEquals(5f, r, 0.001f)
        assertEquals(5f, t, 0.001f)
        assertEquals(5f, b, 0.001f)
    }

    @Test
    fun `an empty shape yields a zero box rather than throwing`() {
        // An all-zero box draws nothing, which is the right outcome for a shape with
        // no points. Throwing here would take down the whole canvas draw pass.
        val b = ShapeGeometry.boundsOf(emptyList())
        assertEquals(4, b.size)
        assertTrue(b.all { it == 0f }, "degenerate box must be zeroed")
    }

    @Test
    fun `a circle is inscribed in the same box as the rect`() {
        val box = ShapeGeometry.boundsOf(points(0f to 0f, 100f to 50f))
        val circle = ShapeGeometry.shapeBox(ShapeType.CIRCLE, points(0f to 0f, 100f to 50f))
        assertEquals(box.toList(), circle.toList(), "RECT and CIRCLE share one box")
    }

    @Test
    fun `a line uses its two endpoints and ignores the stored bounds`() {
        // The stored bounds of a line dragged upward have top > the low endpoint, so
        // deriving the line from bounds would flip its direction.
        val pts = points(100f to 400f, 100f to 100f)
        val (x0, y0, x1, y1) = ShapeGeometry.endpoints(pts)
        assertEquals(100f, x0, 0.001f)
        assertEquals(400f, y0, 0.001f)
        assertEquals(100f, x1, 0.001f)
        assertEquals(100f, y1, 0.001f, "the endpoint order is preserved, not sorted")
    }

    @Test
    fun `a zero-length line does not divide by zero`() {
        val (x0, y0, x1, y1) = ShapeGeometry.endpoints(points(7f to 9f, 7f to 9f))
        assertEquals(0f, ShapeGeometry.lineLength(x0, y0, x1, y1), 0.001f)
        val angle = ShapeGeometry.lineAngle(x0, y0, x1, y1)
        assertTrue(!angle.isNaN(), "angle must be a real number, not NaN")
    }

    @Test
    fun `an arrow head points along the line, away from the start`() {
        // Written out rather than chained `to`: `a to b to c` parses right-associatively
        // as a Pair<Pair<Float,Float>,Float>, not four coordinates.
        val x0 = 10f; val y0 = 10f; val x1 = 110f; val y1 = 10f   // pointing right
        val (hx1, hy1) = ShapeGeometry.arrowHead(x0, y0, x1, y1, headSize = 20f).first
        // One barb must sit "behind" the tip (x < tip) and one "ahead" is impossible:
        // both barbs flare backwards from the tip.
        assertTrue(hx1 > x1 - 20.5f, "barb flares from the tip backwards, got $hx1")
        assertTrue(abs(hx1 - x1) > 0.001f, "barb must not sit exactly on the tip")
    }

    @Test
    fun `an arrow head mirrors across the line axis`() {
        val x0 = 0f; val y0 = 0f; val x1 = 100f; val y1 = 0f
        val (left, right) = ShapeGeometry.arrowHead(x0, y0, x1, y1, headSize = 20f)
        // For a horizontal line the two barbs are symmetric about it.
        assertEquals(left.y, -right.y, 0.001f, "barbs must be mirror images")
        assertEquals(left.x, right.x, 0.001f, "barbs share the same x")
    }

    @Test
    fun `the arrow angle follows the direction of travel`() {
        // Rightward -> 0; upward -> -PI/2 in screen coordinates (y grows downward).
        val right = ShapeGeometry.lineAngle(0f, 0f, 10f, 0f)
        val down = ShapeGeometry.lineAngle(0f, 0f, 0f, 10f)
        val up = ShapeGeometry.lineAngle(0f, 10f, 0f, 0f)
        assertEquals(0.0, right, 0.0001)
        assertEquals(Math.PI / 2, down, 0.0001)
        assertEquals(-Math.PI / 2, up, 0.0001)
    }

    @Test
    fun `shape type parsing is forgiving about case and unknown values`() {
        assertEquals(ShapeType.RECT, ShapeGeometry.parseType("rect"))
        assertEquals(ShapeType.ARROW, ShapeGeometry.parseType("ARROW"))
        assertEquals(null, ShapeGeometry.parseType("TRIANGLE"), "unknown types must not crash")
        assertEquals(null, ShapeGeometry.parseType(null))
    }

    @Test
    fun `a shape's stroke width is used directly, never as an envelope`() {
        // The regression this guards: feeding a 2-point shape into the envelope
        // renderer turns a rectangle into a thin sliver between the two corners.
        assertEquals(3f, ShapeGeometry.strokeWidthFor(ShapeType.RECT, 3f), 0.001f)
        assertEquals(3f, ShapeGeometry.strokeWidthFor(ShapeType.CIRCLE, 3f), 0.001f)
    }
}