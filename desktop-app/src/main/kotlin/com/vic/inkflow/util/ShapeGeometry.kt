package com.vic.inkflow.util

import androidx.compose.ui.geometry.Offset
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The four shapes the tablet supports, stored in `strokes.shapeType`.
 *
 * The storage is deliberate: a shape is a [com.vic.inkflow.data.StrokeEntity] with
 * exactly two points and a type tag. No separate table, no geometry blob. That is
 * why shapes travel over sync for free — and also why they are so easy to break:
 * the two points are *drag endpoints*, not a canonical representation, and the
 * renderer has to know which of them to use.
 */
enum class ShapeType { RECT, CIRCLE, LINE, ARROW }

/**
 * Shape geometry, matching the tablet's `InkCanvasRendering.drawShapeOnCanvas`.
 *
 * ## The trap this exists to close
 *
 * RECT and CIRCLE are drawn from the stored **bounds**; LINE and ARROW from the
 * **two points**. A drag from bottom-left to top-right stores
 * `p0 = (300, 200)`, `p1 = (100, 400)`, whose y ordering is inverted relative to
 * the stored bounds. A renderer that uses the points for a LINE draws it "up"
 * when the user dragged "down". The shape is still visible, so nobody reports it —
 * it just points the wrong way.
 *
 * ## Why shapes must never go through the envelope renderer
 *
 * Freehand strokes are variable-width polygons produced by
 * [EnvelopeUtils.generateEnvelopePath]. Running a two-point shape through it
 * produces a thin lens between the two corners — a rectangle becomes a sliver.
 * Shapes are stroked outlines and need [strokeWidthFor] to be the width itself.
 */
object ShapeGeometry {

    /** Parse a stored `shapeType`. Null for freehand or anything unrecognised. */
    fun parseType(raw: String?): ShapeType? = when (raw?.trim()?.uppercase()) {
        "RECT" -> ShapeType.RECT
        "CIRCLE" -> ShapeType.CIRCLE
        "LINE" -> ShapeType.LINE
        "ARROW" -> ShapeType.ARROW
        else -> null
    }

    /** Left, top, right, bottom over any number of points. Null when empty. */
    fun boundsOf(points: List<StrokePoint>): FloatArray {
        if (points.isEmpty()) return floatArrayOf(0f, 0f, 0f, 0f)
        return floatArrayOf(
            points.minOf { it.x }, points.minOf { it.y },
            points.maxOf { it.x }, points.maxOf { it.y }
        )
    }

    /**
     * The box a shape is drawn in.
     *
     * For RECT and CIRCLE this is the stored bounds. For LINE and ARROW it is the
     * box of the endpoints, which is the same thing — provided the caller derives
     * the *direction* from [endpoints], not from this box.
     */
    fun shapeBox(type: ShapeType, points: List<StrokePoint>): FloatArray =
        boundsOf(points)

    /**
     * Direction-preserving endpoints: `first` to `last`.
     *
     * This is the function a LINE/ARROW renderer must use. Sorting the points into
     * "top then bottom" would make every upward drag render as a downward line.
     */
    fun endpoints(points: List<StrokePoint>): List<Float> {
        if (points.isEmpty()) return listOf(0f, 0f, 0f, 0f)
        if (points.size == 1) return listOf(points[0].x, points[0].y, points[0].x, points[0].y)
        val a = points.first()
        val b = points.last()
        return listOf(a.x, a.y, b.x, b.y)
    }

    fun lineLength(x0: Float, y0: Float, x1: Float, y1: Float): Float =
        hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()

    /**
     * Angle of the line in screen space (y grows downward, so "up" is negative).
     *
     * Returns 0 for a zero-length line rather than NaN: `atan2(0, 0)` is 0 in Kotlin
     * but the degenerate case still has to produce a finite number, because it
     * feeds a rotation.
     */
    fun lineAngle(x0: Float, y0: Float, x1: Float, y1: Float): Double =
        if (x0 == x1 && y0 == y1) 0.0 else atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())

    /**
     * The two arrow-head barbs, as offsets from the tip.
     *
     * Both barbs flare backwards from the tip — that is what makes it read as an
     * arrow pointing at [x1]/[y1]. Half-angle is 0.75π (135°), matching the tablet:
     * a narrower head looks like a chevron and a wider one like a paper plane.
     */
    fun arrowHead(x0: Float, y0: Float, x1: Float, y1: Float, headSize: Float): Pair<Offset, Offset> {
        val angle = lineAngle(x0, y0, x1, y1)
        val left = angle + Math.PI * 0.75
        val right = angle - Math.PI * 0.75
        return Offset(
            x1 + (headSize * cos(left)).toFloat(),
            y1 + (headSize * sin(left)).toFloat()
        ) to Offset(
            x1 + (headSize * cos(right)).toFloat(),
            y1 + (headSize * sin(right)).toFloat()
        )
    }

    /**
     * Head size from the stroke width, matching the tablet's `sw * 5 + 10`.
     *
     * The `+10` floor matters at small widths: a 0.5pt pen would otherwise get a
     * 2.5pt head that is smaller than the line's own cap, and the arrow stops
     * looking like an arrow.
     */
    fun arrowHeadSize(strokeWidth: Float): Float = strokeWidth * 5f + 10f

    /**
     * The width to stroke a shape with.
     *
     * Deliberately the identity: a shape's width is its outline width, and passing
     * it through the envelope model would scale it by velocity for a shape that has
     * no velocity.
     */
    fun strokeWidthFor(type: ShapeType, width: Float): Float = width
}