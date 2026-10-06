package com.vic.inkflow.ui

import com.vic.inkflow.data.PointEntity
import com.vic.inkflow.data.StrokeEntity
import com.vic.inkflow.data.StrokeWithPoints
import com.vic.inkflow.util.ShapeGeometry
import com.vic.inkflow.util.ShapeType
import com.vic.inkflow.util.StrokePoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A shape must come back from storage looking the way it was drawn.
 *
 * The storage format is two points plus a type tag, and that is enough for the
 * tablet only because its renderer knows which of the two representations to use.
 * These cases check the desktop's read side agrees, since a mismatch here shows up
 * as a shape pointing the wrong way or a rectangle rendered as a sliver — both of
 * which look like drawing bugs to the user and like storage bugs to us.
 */
class ShapeRoundTripTest {

    private fun shape(type: String, p0: Pair<Float, Float>, p1: Pair<Float, Float>) =
        StrokeWithPoints(
            stroke = StrokeEntity(
                id = "shape-1", documentUri = "file:///a.pdf", pageIndex = 0,
                color = 0xFF000000.toInt(), strokeWidth = 2f,
                boundsLeft = minOf(p0.first, p1.first), boundsTop = minOf(p0.second, p1.second),
                boundsRight = maxOf(p0.first, p1.first), boundsBottom = maxOf(p0.second, p1.second),
                shapeType = type
            ),
            points = listOf(
                PointEntity(id = 1, strokeId = "shape-1", x = p0.first, y = p0.second, width = 2f),
                PointEntity(id = 2, strokeId = "shape-1", x = p1.first, y = p1.second, width = 2f)
            )
        )

    private fun modelPts(s: StrokeWithPoints) =
        s.points.map { StrokePoint(it.x, it.y, it.width) }

    @Test
    fun `a rectangle drawn bottom-to-top still has positive bounds`() {
        // The drag that breaks naive implementations: bottom-right to top-left.
        val s = shape("RECT", 300f to 400f, 100f to 200f)
        val b = ShapeGeometry.boundsOf(modelPts(s))
        assertEquals(100f, b[0], 0.001f)
        assertEquals(200f, b[1], 0.001f)
        assertEquals(300f, b[2], 0.001f)
        assertEquals(400f, b[3], 0.001f)
        assertTrue(b[2] > b[0] && b[3] > b[1], "must draw as a positive-sized rect")
    }

    @Test
    fun `stored bounds match what the points say`() {
        // commitShape computes bounds with min/max while the renderer derives them
        // from the points. If those two ever disagree, the drawn shape sits
        // somewhere other than where it was clicked.
        listOf(
            shape("RECT", 10f to 10f, 110f to 210f),
            shape("RECT", 110f to 210f, 10f to 10f),
            shape("CIRCLE", 300f to 50f, 40f to 400f)
        ).forEach { s ->
            val fromPoints = ShapeGeometry.boundsOf(modelPts(s))
            assertEquals(s.stroke.boundsLeft, fromPoints[0], 0.001f, "left")
            assertEquals(s.stroke.boundsTop, fromPoints[1], 0.001f, "top")
            assertEquals(s.stroke.boundsRight, fromPoints[2], 0.001f, "right")
            assertEquals(s.stroke.boundsBottom, fromPoints[3], 0.001f, "bottom")
        }
    }

    @Test
    fun `a line keeps its drag direction through storage`() {
        // Drawn downward: p0 is above p1. A renderer that sorted the endpoints would
        // draw it upward — visible, but not something a user would report.
        val s = shape("LINE", 50f to 50f, 50f to 400f)
        val e = ShapeGeometry.endpoints(modelPts(s))
        assertEquals(50f, e[0], 0.001f)
        assertEquals(50f, e[1], 0.001f, "start stays at the top")
        assertEquals(400f, e[3], 0.001f, "end stays at the bottom")
    }

    @Test
    fun `an arrow points where the drag ended`() {
        val s = shape("ARROW", 100f to 300f, 100f to 60f)   // dragged upward
        val e = ShapeGeometry.endpoints(modelPts(s))
        val angle = ShapeGeometry.lineAngle(e[0], e[1], e[2], e[3])
        assertTrue(angle < 0, "dragging up must yield an upward angle, got $angle")
    }

    @Test
    fun `freehand strokes carry no shape type and must not be shape-rendered`() {
        val freehand = StrokeWithPoints(
            stroke = StrokeEntity(
                id = "free", documentUri = "file:///a.pdf", pageIndex = 0,
                color = 0, strokeWidth = 2f, shapeType = null
            ),
            points = listOf(
                PointEntity(id = 1, strokeId = "free", x = 0f, y = 0f, width = 2f),
                PointEntity(id = 2, strokeId = "free", x = 10f, y = 10f, width = 2f)
            )
        )
        assertNull(ShapeGeometry.parseType(freehand.stroke.shapeType), "null means envelope path")
    }

    @Test
    fun `an unrecognised shape type falls back to freehand rather than crashing`() {
        // Forward compatibility: a newer tablet could write a type this build has
        // never heard of. Rendering it as ink is wrong but survivable; throwing in
        // the canvas draw pass would blank the whole page.
        assertNull(ShapeGeometry.parseType("HEXAGON"))
        assertNull(ShapeGeometry.parseType(""))
    }

    @Test
    fun `every supported type round trips through its stored name`() {
        ShapeType.entries.forEach { t ->
            assertEquals(t, ShapeGeometry.parseType(t.name), "${t.name} must parse back")
        }
    }

    @Test
    fun `a shape survives selection bounds and deletion like any other stroke`() {
        // Selection and delete key off stroke ids, so a shape needs no special
        // handling — this asserts that assumption rather than the renderer.
        val s = shape("RECT", 0f to 0f, 100f to 100f)
        val all = listOf(s)
        val selected = all.filter { it.stroke.id in setOf("shape-1") }
        assertEquals(1, selected.size)
        assertNotNull(selected.first().stroke.shapeType, "the type survives to the delete path")
    }

    @Test
    fun `moving a shape translates both points and both bounds together`() {
        // Move rewrites points and bounds; if only one moved, the rendered position
        // and the hit box would disagree and the shape would resist being grabbed.
        val s = shape("RECT", 10f to 10f, 110f to 210f)
        val dx = 25f; val dy = -5f
        val moved = s.copy(
            stroke = s.stroke.copy(
                boundsLeft = s.stroke.boundsLeft + dx, boundsTop = s.stroke.boundsTop + dy,
                boundsRight = s.stroke.boundsRight + dx, boundsBottom = s.stroke.boundsBottom + dy
            ),
            points = s.points.map { it.copy(x = it.x + dx, y = it.y + dy) }
        )
        val before = ShapeGeometry.boundsOf(modelPts(s))
        val after = ShapeGeometry.boundsOf(modelPts(moved))
        assertEquals(before[0] + dx, after[0], 0.001f)
        assertEquals(before[1] + dy, after[1], 0.001f)
        assertEquals(before[2] + dx, after[2], 0.001f)
        assertEquals(before[3] + dy, after[3], 0.001f)
        assertEquals(moved.stroke.boundsLeft, after[0], 0.001f, "stored bounds track the points")
        assertEquals(moved.stroke.boundsBottom, after[3], 0.001f)
    }

    @Test
    fun `the arrow head is larger than the line width at thin settings`() {
        // sw*5 + 10: the +10 floor is what keeps a thin arrow from getting a head
        // smaller than its own cap, which stops reading as an arrow.
        assertTrue(
            ShapeGeometry.arrowHeadSize(0.5f) > 0.5f,
            "head must exceed the line width even for a hairline"
        )
        assertEquals(5f * 2f + 10f, ShapeGeometry.arrowHeadSize(2f), 0.001f)
    }
}