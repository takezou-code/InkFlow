package com.vic.inkflow.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.vic.inkflow.data.StrokeWithSamples
import com.vic.inkflow.util.EnvelopeUtils
import com.vic.inkflow.util.ShapeGeometry
import com.vic.inkflow.util.ShapeType
import com.vic.inkflow.util.StrokePoint

/**
 * Drawing one stroke or shape onto a page — the shared renderer.
 *
 * ## Why it is shared
 *
 * The desktop had its own copy of this drawing code, and the tablet had another
 * (through the `Canvas` API, because it caches the expensive envelope path). Same
 * ink, two renderers, two sets of constants — the same class of drift that the
 * shape geometry and the highlighter rule had.
 *
 * This one is the **`DrawScope`** flavour, so it works on every platform that has
 * Compose: Android, the desktop, and (once it exists) iOS.
 *
 * ## The two rules that matter
 *
 *  - **Shapes never go through the envelope renderer.** A shape is two drag
 *    endpoints plus a type tag, not a variable-width polygon; feeding it to
 *    [com.vic.inkflow.util.EnvelopeUtils.generateEnvelopePath] turns a rectangle
 *    into a thin lens between its two corners.
 *  - **A shape's direction comes from the endpoints, not from its box.** A drag
 *    from bottom-left to top-right stores inverted y values relative to the box;
 *    drawing from the box flips every upward drag. See [ShapeGeometry.endpoints].
 *
 * @param scale model → screen pixels.
 * @param originX the page origin on screen (the page is drawn with its own origin
 *   inside the viewport, so the origin has to be subtracted per point).
 */
fun DrawScope.drawModelStroke(
    swp: StrokeWithSamples,
    scale: Float,
    originX: Float,
    originY: Float
) {
    val pts = swp.points
    if (pts.isEmpty()) return

    // Shapes branch out here, before the envelope renderer.
    val shapeType = ShapeGeometry.parseType(swp.stroke.shapeType)
    if (shapeType != null) {
        drawModelShape(swp, shapeType, scale, originX, originY)
        return
    }

    val strokePoints = pts.map {
        StrokePoint(
            x = (it.x - originX) * scale,
            y = (it.y - originY) * scale,
            width = (it.width.takeIf { w -> w > 0f } ?: swp.stroke.strokeWidth)
                .coerceAtLeast(MIN_INK_PT)
                .times(scale)
                .coerceAtLeast(MIN_INK_PX)
        )
    }
    val ink = Color(swp.stroke.color)
    drawPath(
        path = EnvelopeUtils.generateEnvelopePath(strokePoints),
        color = strokeColor(ink, swp.stroke.isHighlighter),
        blendMode = strokeBlendMode(swp.stroke.isHighlighter)
    )
}

/**
 * Draw one shape, matching the tablet's `drawShapeOnCanvas`.
 *
 * The split that matters: RECT and CIRCLE use the box, LINE and ARROW use the two
 * endpoints **in stored order**. Sorting the endpoints would flip the direction of
 * every shape drawn upwards — visible, but not something a user would report,
 * since the shape is still there.
 */
fun DrawScope.drawModelShape(
    swp: StrokeWithSamples,
    type: ShapeType,
    scale: Float,
    originX: Float,
    originY: Float
) {
    val ink = Color(swp.stroke.color)
    val width = ShapeGeometry.strokeWidthFor(type, swp.stroke.strokeWidth)
        .times(scale).coerceAtLeast(MIN_INK_PX)
    val style = Stroke(
        width = width, cap = StrokeCap.Round,
        join = StrokeJoin.Round
    )

    fun px(mx: Float, my: Float) = Offset(
        (mx - originX) * scale, (my - originY) * scale
    )

    val modelPts = swp.points.map {
        StrokePoint(it.x, it.y, it.width)
    }
    val b = ShapeGeometry.boundsOf(modelPts)

    when (type) {
        ShapeType.RECT -> drawRect(
            color = ink,
            topLeft = px(b[0], b[1]),
            size = Size((b[2] - b[0]) * scale, (b[3] - b[1]) * scale),
            style = style
        )

        ShapeType.CIRCLE -> drawOval(
            color = ink,
            topLeft = px(b[0], b[1]),
            size = Size((b[2] - b[0]) * scale, (b[3] - b[1]) * scale),
            style = style
        )

        ShapeType.LINE -> {
            val e = ShapeGeometry.endpoints(modelPts)
            drawLine(
                color = ink,
                start = px(e[0], e[1]), end = px(e[2], e[3]),
                strokeWidth = width, cap = StrokeCap.Round
            )
        }

        ShapeType.ARROW -> {
            val e = ShapeGeometry.endpoints(modelPts)
            val a = px(e[0], e[1])
            val z = px(e[2], e[3])
            drawLine(
                color = ink, start = a, end = z,
                strokeWidth = width, cap = StrokeCap.Round
            )
            // The head size is computed in model units and scaled with everything
            // else, so the arrow does not keep a constant pixel size while the
            // page zooms.
            val head = ShapeGeometry.arrowHead(
                e[0], e[1], e[2], e[3],
                ShapeGeometry.arrowHeadSize(swp.stroke.strokeWidth)
            )
            drawLine(
                color = ink, start = z, end = px(head.first.x, head.first.y),
                strokeWidth = width, cap = StrokeCap.Round
            )
            drawLine(
                color = ink, start = z, end = px(head.second.x, head.second.y),
                strokeWidth = width, cap = StrokeCap.Round
            )
        }
    }
}

/** Never let ink vanish entirely when zoomed far out. */
private const val MIN_INK_PX = 0.6f

/** Smallest sensible drawn width in model units, mirroring the tablet's floor. */
private const val MIN_INK_PT = 0.05f
