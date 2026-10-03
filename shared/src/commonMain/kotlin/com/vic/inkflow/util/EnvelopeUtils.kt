package com.vic.inkflow.util

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** One sample of a stroke: position plus the width *at that sample*. */
data class StrokePoint(val x: Float, val y: Float, val width: Float)

/**
 * 預覽用中心線平滑：3 點滑動平均 x/y（寬度保留），只餵給即時預覽包絡，
 * 入庫點列不動 → 匯出零影響。消除電容筆高頻抖動的毛邊。
 *
 * Preview only. Nothing on the persisted or synced path may call this — see the
 * note on [EnvelopeUtils] about why.
 */
fun smoothCenterline(points: List<StrokePoint>): List<StrokePoint> {
    if (points.size < 3) return points
    val last = points.size - 1
    return points.mapIndexed { i, p ->
        val a = points[(i - 1).coerceAtLeast(0)]
        val b = points[(i + 1).coerceAtMost(last)]
        StrokePoint((a.x + p.x + b.x) / 3f, (a.y + p.y + b.y) / 3f, p.width)
    }
}

/** Half-circle resolution at a stroke's end. Higher is rounder, and costs path size. */
private const val CAP_STEPS = 12

/**
 * How far apart two samples must be before one is trusted as a tangent
 * neighbour. Below this the normal is dominated by sensor jitter and the outline
 * inverts locally.
 */
private const val NEIGHBOUR_MIN_PX = 1f

/**
 * Builds the filled outline ("envelope") of a variable-width stroke.
 *
 * ## Why one implementation
 *
 * This file used to exist twice — once in `:app` and once in `:desktopApp` — with
 * the desktop's copy annotated "ported **verbatim** from the tablet". It was not
 * verbatim: the two had drifted apart structurally (named constants vs inline
 * literals, different comments), and keeping them in sync needed a third copy of
 * the algorithm in the desktop's *test* source set purely to diff against. That
 * arrangement is one careless edit away from a silent ink-format divergence, since
 * the same rows in the database are rendered by both apps.
 *
 * The tablet is the source of truth for the ink format, so its implementation is
 * the one that moved here, with the desktop's documentation folded in. The
 * arithmetic is unchanged.
 *
 * ## Why an envelope at all
 *
 * The tablet records `points.width` **per sample** — velocity-derived, not a
 * single value per stroke. In the synced data that comes out as ~2,100 distinct
 * widths across ~16,000 points, and a single 1,174-point stroke carries 457 of
 * them. That is the pressure response of a real pen.
 *
 * `DrawScope.drawPath(style = Stroke(width = ...))` has one width for the whole
 * path, so it cannot express this at all; using the stroke's nominal
 * `strokeWidth` (which the tablet deliberately stores as the *base* width, "not
 * the velocity-derived per-point width") renders a tapering brush stroke as a flat
 * hairline. Hence a filled polygon.
 *
 * ## The three non-obvious parts
 *
 * 1. **A circle is added at every sample.** Two reasons, both load-bearing: it
 *    fills the holes that the non-zero winding rule leaves where the envelope
 *    self-intersects on a tight curve, and it rounds every corner no matter how
 *    ragged the Bezier contour got. Removing it does not degrade gracefully — it
 *    punches transparent holes through the middle of fast strokes.
 * 2. **Tangent from far-apart neighbours**, skipping samples closer than
 *    [NEIGHBOUR_MIN_PX], so one jittery sample cannot swing the normal and
 *    locally invert the outline.
 * 3. **The caps subtract the angle**, which sweeps the arc *outward*. Adding it
 *    carves a concave dish into the end of every stroke, and because the ink is
 *    filled, that dish shows as background.
 *
 * ## What must not change
 *
 * [smoothCenterline] lives in this file because the tablet has it, but it is
 * preview-only. If either app ever runs *stored* points through it, that app
 * starts drawing different geometry from the other for the same stroke — which is
 * the exact class of bug this file was moved to eliminate.
 */
object EnvelopeUtils {

    /**
     * Generates a filled polygon (Envelope) path from a list of stroke points with
     * individual widths. This mimics perfect-freehand style to render
     * variable-width strokes efficiently in a single draw call.
     */
    fun generateEnvelopePath(points: List<StrokePoint>): Path {
        val path = Path()
        if (points.isEmpty()) return path

        if (points.size == 1) {
            val p = points.first()
            val r = p.width / 2f
            path.addOval(Rect(p.x - r, p.y - r, p.x + r, p.y + r))
            return path
        }

        val leftPoints = mutableListOf<Offset>()
        val rightPoints = mutableListOf<Offset>()

        // 1. Calculate left and right offset points based on normals
        for (i in points.indices) {
            val curr = points[i]

            // To find the tangent, we look at the previous and next points that are
            // sufficiently far apart.
            var prevIndex = i - 1
            while (prevIndex >= 0 &&
                hypot(curr.x - points[prevIndex].x, curr.y - points[prevIndex].y) < NEIGHBOUR_MIN_PX
            ) {
                prevIndex--
            }
            val prev = if (prevIndex >= 0) points[prevIndex] else curr

            var nextIndex = i + 1
            while (nextIndex < points.size &&
                hypot(points[nextIndex].x - curr.x, points[nextIndex].y - curr.y) < NEIGHBOUR_MIN_PX
            ) {
                nextIndex++
            }
            val next = if (nextIndex < points.size) points[nextIndex] else curr

            var dx = next.x - prev.x
            var dy = next.y - prev.y

            // For endpoints, fallback to current to prev/next
            if (prev === curr && next !== curr) {
                dx = next.x - curr.x
                dy = next.y - curr.y
            } else if (next === curr && prev !== curr) {
                dx = curr.x - prev.x
                dy = curr.y - prev.y
            }

            val len = hypot(dx, dy)
            if (len > 0.01f) {
                dx /= len
                dy /= len
            } else {
                dx = 1f
                dy = 0f
            }

            // Normal vector (rotated 90 degrees)
            val nx = -dy
            val ny = dx

            val r = curr.width / 2f
            leftPoints.add(Offset(curr.x + nx * r, curr.y + ny * r))
            rightPoints.add(Offset(curr.x - nx * r, curr.y - ny * r))

            // 核心修復：在每一個繪圖點都加上一個圓形 (Oval)。
            // 1. 消除留白：這能完美彌補自交錯 (Self-intersection) 時因為 NonZero
            //    演算法產生的中空透明問題。
            // 2. 消除稜角：不管 Envelope 的外緣因為貝茲曲線拉伸得多凌亂，圓形會
            //    確實將每一個轉角都補成豐滿的「純圓角」。
            path.addOval(Rect(curr.x - r, curr.y - r, curr.x + r, curr.y + r))
        }

        // 2. Begin Path at the first left point
        path.moveTo(leftPoints.first().x, leftPoints.first().y)

        // 3. Draw left edge using Beziers for smooth contours
        for (i in 1 until leftPoints.size) {
            val p1 = leftPoints[i - 1]
            val p2 = leftPoints[i]
            path.quadraticTo(p1.x, p1.y, (p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)
        }
        path.lineTo(leftPoints.last().x, leftPoints.last().y)

        // 4. Cap the end with a semi-circle
        val endPt = points.last()
        val endR = endPt.width / 2f
        val endDx = leftPoints.last().x - endPt.x
        val endDy = leftPoints.last().y - endPt.y
        val endAngle = atan2(endDy, endDx)
        for (i in 0..CAP_STEPS) { // Start at 0 to connect seamlessly
            // 使用「減去」角度 (-)，確保弧線是往筆畫「外部（前方）」畫，而不是往筆畫內部凹陷
            val a = endAngle - (Math.PI.toFloat() * i / CAP_STEPS)
            path.lineTo(endPt.x + cos(a) * endR, endPt.y + sin(a) * endR)
        }

        // 5. Draw right edge backwards
        path.lineTo(rightPoints.last().x, rightPoints.last().y)
        for (i in rightPoints.indices.reversed().drop(1)) {
            val p1 = rightPoints[i + 1]
            val p2 = rightPoints[i]
            path.quadraticTo(p1.x, p1.y, (p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)
        }
        path.lineTo(rightPoints.first().x, rightPoints.first().y)

        // 6. Cap the start with a semi-circle
        val startPt = points.first()
        val startR = startPt.width / 2f
        val startDx = rightPoints.first().x - startPt.x
        val startDy = rightPoints.first().y - startPt.y
        val startAngle = atan2(startDy, startDx)
        for (i in 0..CAP_STEPS) { // Start at 0 to connect seamlessly
            // 使用「減去」角度 (-)，確保弧線是往筆畫「外部（後方）」畫，而不是往筆畫內部凹陷
            val a = startAngle - (Math.PI.toFloat() * i / CAP_STEPS)
            path.lineTo(startPt.x + cos(a) * startR, startPt.y + sin(a) * startR)
        }

        path.close()
        return path
    }
}