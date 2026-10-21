package com.vic.inkflow.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shape rules that the tablet's renderer and the desktop's used to keep
 * separately.
 *
 * ## Why this test exists
 *
 * The desktop's `ShapeGeometry` said, in its own comment, that it was "matching the
 * tablet's `InkCanvasRendering.drawShapeOnCanvas`". Two copies of the rule that
 * decides **which end of a line the line points at** is two chances for the rule to
 * disagree with itself — and the disagreement is invisible: the shape is still
 * drawn, it just points the wrong way, so nobody reports it.
 *
 * The geometry now lives in `:shared` (`ShapeGeometry`), and this test runs on every
 * platform (desktop / Android host / iOS), so the two ends cannot drift apart
 * again without a test going red.
 */
class ShapeGeometryTest {

    @Test
    fun `parseType reads the stored shape tags`() {
        assertEquals(ShapeType.RECT, ShapeGeometry.parseType("RECT"))
        assertEquals(ShapeType.CIRCLE, ShapeGeometry.parseType("circle"))
        assertEquals(ShapeType.LINE, ShapeGeometry.parseType("LINE"))
        assertEquals(ShapeType.ARROW, ShapeGeometry.parseType(" arrow "))
        // Freehand strokes have no shape type, and an unknown tag must not be
        // guessed into one.
        assertNull(ShapeGeometry.parseType(null))
        assertNull(ShapeGeometry.parseType(""))
        assertNull(ShapeGeometry.parseType("TRIANGLE"))
    }

    @Test
    fun `endpoints keep the drag direction`() {
        // Dragged from bottom-left to top-right: the "from" must stay the
        // bottom-left one. Sorting the points would draw every upward drag as a
        // downward line.
        val points = listOf(StrokePoint(300f, 400f, 1f), StrokePoint(100f, 200f, 1f))
        val e = ShapeGeometry.endpoints(points)
        assertEquals(300f, e[0])
        assertEquals(400f, e[1])
        assertEquals(100f, e[2])
        assertEquals(200f, e[3])
    }

    @Test
    fun `endpoints of a degenerate point are that point`() {
        val e = ShapeGeometry.endpoints(listOf(StrokePoint(7f, 9f, 1f)))
        assertEquals(listOf(7f, 9f, 7f, 9f), e)
        assertEquals(listOf(0f, 0f, 0f, 0f), ShapeGeometry.endpoints(emptyList<StrokePoint>()))
    }

    @Test
    fun `bounds cover every point`() {
        val b = ShapeGeometry.boundsOf(
            listOf(StrokePoint(10f, 20f, 1f), StrokePoint(-5f, 30f, 1f), StrokePoint(4f, 2f, 1f))
        )
        assertEquals(listOf(-5f, 2f, 10f, 30f), b.toList())
    }

    @Test
    fun `the arrow head flares backwards from the tip`() {
        val headSize = ShapeGeometry.arrowHeadSize(2f)
        assertEquals(20f, headSize)   // 2 * 5 + 10

        // A horizontal arrow pointing right: the tip is at the right, the barbs
        // must be behind it (smaller x) and above/below.
        val (left, right) = ShapeGeometry.arrowHead(0f, 0f, 100f, 0f, headSize)
        assertTrue(left.x < 100f, "left barb must be behind the tip, was ${left.x}")
        assertTrue(right.x < 100f, "right barb must be behind the tip, was ${right.x}")
        assertTrue(left.y > 0f, "left barb must be below the axis, was ${left.y}")
        assertTrue(right.y < 0f, "right barb must be above the axis, was ${right.y}")
    }

    @Test
    fun `the head size has a floor for thin pens`() {
        // A 0.5pt pen would otherwise get a 2.5pt head — smaller than the line's
        // own cap — and the arrow stops looking like an arrow.
        assertEquals(12.5f, ShapeGeometry.arrowHeadSize(0.5f))
        assertEquals(60f, ShapeGeometry.arrowHeadSize(10f))
    }

    @Test
    fun `a zero length line has a finite angle`() {
        // atan2(0, 0) is 0 in Kotlin, but the degenerate case still has to
        // produce a finite number because it feeds a rotation.
        assertEquals(0.0, ShapeGeometry.lineAngle(5f, 5f, 5f, 5f))
        assertEquals(0f, ShapeGeometry.lineLength(5f, 5f, 5f, 5f))
    }
}
