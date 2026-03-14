package com.vic.inkflow.util

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidPath
import com.vic.inkflow.data.PointEntity
import com.vic.inkflow.data.StrokeWithPoints
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.atan2
import kotlin.math.sqrt

object IntersectionUtils {

    /**
     * Helper to add thickness to a path.
     */
    private fun Path.stroked(width: Float): Path {
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val outPath = Path()
        paint.getFillPath(this, outPath)
        return outPath
    }

    /**
     * Calculates the shortest distance from a point (px, py) to a line segment (vx, vy)-(wx, wy).
     */
    private fun distancePointToSegment(px: Float, py: Float, vx: Float, vy: Float, wx: Float, wy: Float): Float {
        val l2 = (wx - vx) * (wx - vx) + (wy - vy) * (wy - vy)
        if (l2 == 0f) return sqrt((px - vx) * (px - vx) + (py - vy) * (py - vy))
        var t = ((px - vx) * (wx - vx) + (py - vy) * (wy - vy)) / l2
        t = Math.max(0f, Math.min(1f, t))
        val projX = vx + t * (wx - vx)
        val projY = vy + t * (wy - vy)
        return sqrt((px - projX) * (px - projX) + (py - projY) * (py - projY))
    }

    /**
     * Calculates the shortest distance between two line segments (p1-p2 and p3-p4).
     */
    private fun distanceSegmentToSegment(
        x1: Float, y1: Float, x2: Float, y2: Float,
        x3: Float, y3: Float, x4: Float, y4: Float
    ): Float {
        val rx = x2 - x1
        val ry = y2 - y1
        val sx = x4 - x3
        val sy = y4 - y3
        val qx = x3 - x1
        val qy = y3 - y1

        val rCrossS = rx * sy - ry * sx
        val qCrossR = qx * ry - qy * rx
        val epsilon = 1e-6f

        // Check for actual crossing intersection
        if (kotlin.math.abs(rCrossS) > epsilon) {
            val qCrossS = qx * sy - qy * sx
            val t = qCrossS / rCrossS
            val u = qCrossR / rCrossS
            if (t in 0f..1f && u in 0f..1f) return 0f // They cross
        }

        // If they don't cross (or are parallel), the shortest distance must be
        // from an endpoint of one segment to the other segment.
        val d1 = distancePointToSegment(x1, y1, x3, y3, x4, y4)
        val d2 = distancePointToSegment(x2, y2, x3, y3, x4, y4)
        val d3 = distancePointToSegment(x3, y3, x1, y1, x2, y2)
        val d4 = distancePointToSegment(x4, y4, x1, y1, x2, y2)

        return minOf(d1, d2, d3, d4)
    }

    /**
     * Eraser: Two-phase collision detection using AABB broad phase + segment-to-segment distance.
     */
    fun findIntersectingStrokes(eraserPoints: List<Offset>, strokes: List<StrokeWithPoints>, eraserRadius: Float = 10f): List<StrokeWithPoints> {
        val intersectingStrokes = mutableListOf<StrokeWithPoints>()
        if (eraserPoints.isEmpty()) return intersectingStrokes

        // Combine AABB for all eraser points
        var eMinX = Float.POSITIVE_INFINITY
        var eMinY = Float.POSITIVE_INFINITY
        var eMaxX = Float.NEGATIVE_INFINITY
        var eMaxY = Float.NEGATIVE_INFINITY
        for (p in eraserPoints) {
            if (p.x < eMinX) eMinX = p.x
            if (p.x > eMaxX) eMaxX = p.x
            if (p.y < eMinY) eMinY = p.y
            if (p.y > eMaxY) eMaxY = p.y
        }

        for (strokeWithPoints in strokes) {
            val stroke = strokeWithPoints.stroke
            // Fast AABB check with combined bounds (+ eraser radius + stroke width / 2)
            val R = eraserRadius + (stroke.strokeWidth / 2f)
            if (stroke.boundsRight < eMinX - R || stroke.boundsLeft > eMaxX + R ||
                stroke.boundsBottom < eMinY - R || stroke.boundsTop > eMaxY + R) {
                continue
            }

            var hit = false
            if (stroke.shapeType != null) {
                // For shapes, generate its outline and use Path.op to handle interior/thickness
                val strokePath = strokeWithPoints.toShapePath()
                val eraserPath = Path().apply {
                    moveTo(eraserPoints.first().x, eraserPoints.first().y)
                    for (i in 1 until eraserPoints.size) {
                        lineTo(eraserPoints[i].x, eraserPoints[i].y)
                    }
                }
                val intersectionPath = Path()
                intersectionPath.op(strokePath, eraserPath.stroked(eraserRadius * 2f), Path.Op.INTERSECT)
                if (!intersectionPath.isEmpty) {
                    hit = true
                }
            } else {
                // Freehand strokes: fast segment-to-segment distance
                val strokePoints = strokeWithPoints.points
                if (strokePoints.size <= 1) {
                    // Stroke is just a dot
                    val sx = strokePoints.firstOrNull()?.x ?: continue
                    val sy = strokePoints.firstOrNull()?.y ?: continue
                    var hitDot = false
                    for (i in 0 until eraserPoints.size - 1) {
                        val p1 = eraserPoints[i]
                        val p2 = eraserPoints[i + 1]
                        if (distancePointToSegment(sx, sy, p1.x, p1.y, p2.x, p2.y) <= R) {
                            hitDot = true
                            break
                        }
                    }
                    hit = hitDot
                } else {
                    // Both stroke and eraser are paths
                    for (i in 0 until strokePoints.size - 1) {
                        val s1 = strokePoints[i]
                        val s2 = strokePoints[i + 1]
                        var dist = Float.MAX_VALUE
                        
                        if (eraserPoints.size == 1) {
                            val ex = eraserPoints[0].x
                            val ey = eraserPoints[0].y
                            dist = distancePointToSegment(ex, ey, s1.x, s1.y, s2.x, s2.y)
                        } else {
                            for (j in 0 until eraserPoints.size - 1) {
                                val e1 = eraserPoints[j]
                                val e2 = eraserPoints[j + 1]
                                val d = distanceSegmentToSegment(s1.x, s1.y, s2.x, s2.y, e1.x, e1.y, e2.x, e2.y)
                                if (d < dist) dist = d
                            }
                        }
                        
                        if (dist <= R) {
                            hit = true
                            break
                        }
                    }
                }
            }

            if (hit) {
                intersectingStrokes.add(strokeWithPoints)
            }
        }
        return intersectingStrokes
    }

    /**
     * Lasso: Ray-casting (Even-Odd Rule) to find strokes whose centroid lies inside the polygon.
     * [polygon] is the ordered list of lasso vertices in model coordinates.
     */
    fun findStrokesInLasso(polygon: List<Offset>, strokes: List<StrokeWithPoints>): List<StrokeWithPoints> {
        if (polygon.size < 3) return emptyList()
        return strokes.filter { strokeWithPoints ->
            val points = strokeWithPoints.points
            if (points.isEmpty()) return@filter false
            val cx = points.sumOf { it.x.toDouble() } / points.size
            val cy = points.sumOf { it.y.toDouble() } / points.size
            isPointInPolygon(cx.toFloat(), cy.toFloat(), polygon)
        }
    }

    /**
     * Even-Odd Rule ray-casting: cast a ray in the +X direction from (px, py).
     * Returns true if the point is inside the polygon.
     */
    private fun isPointInPolygon(px: Float, py: Float, polygon: List<Offset>): Boolean {
        var inside = false
        val n = polygon.size
        var j = n - 1
        for (i in 0 until n) {
            val xi = polygon[i].x; val yi = polygon[i].y
            val xj = polygon[j].x; val yj = polygon[j].y
            val intersects = ((yi > py) != (yj > py)) &&
                    (px < (xj - xi) * (py - yi) / (yj - yi) + xi)
            if (intersects) inside = !inside
            j = i
        }
        return inside
    }

    /**
     * Build the actual geometric outline path for a shape stroke so the eraser
     * can intersect against it correctly.  For freehand strokes use [toPath] instead.
     */
    private fun StrokeWithPoints.toShapePath(): Path {
        val path = Path()
        val s = stroke
        val bounds = RectF(s.boundsLeft, s.boundsTop, s.boundsRight, s.boundsBottom)
        when (s.shapeType) {
            "RECT" -> path.addRect(bounds, Path.Direction.CW)
            "CIRCLE" -> path.addOval(bounds, Path.Direction.CW)
            "LINE" -> {
                val pts = points
                if (pts.size >= 2) {
                    buildThickSegment(
                        path,
                        pts.first().x, pts.first().y,
                        pts.last().x,  pts.last().y,
                        s.strokeWidth / 2f + 8f
                    )
                }
            }
            "ARROW" -> {
                val pts = points
                if (pts.size >= 2) {
                    val p0x = pts.first().x; val p0y = pts.first().y
                    val p1x = pts.last().x;  val p1y = pts.last().y
                    val hw = s.strokeWidth / 2f + 8f
                    // Main shaft
                    buildThickSegment(path, p0x, p0y, p1x, p1y, hw)
                    // Arrowhead wings
                    val headSize = s.strokeWidth * 5f + 10f
                    val angle = atan2((p1y - p0y).toDouble(), (p1x - p0x).toDouble())
                    val la = angle + Math.PI * 0.75
                    val ra = angle - Math.PI * 0.75
                    buildThickSegment(
                        path, p1x, p1y,
                        (p1x + headSize * cos(la)).toFloat(), (p1y + headSize * sin(la)).toFloat(),
                        hw
                    )
                    buildThickSegment(
                        path, p1x, p1y,
                        (p1x + headSize * cos(ra)).toFloat(), (p1y + headSize * sin(ra)).toFloat(),
                        hw
                    )
                }
            }
            else -> path.addRect(bounds, Path.Direction.CW)
        }
        return path
    }

    /**
     * Builds a filled rectangular polygon (thick line) around the segment from (x0,y0) to (x1,y1)
     * with the given half-width [hw].  The resulting path has area so Path.Op.INTERSECT works correctly.
     */
    private fun buildThickSegment(path: Path, x0: Float, y0: Float, x1: Float, y1: Float, hw: Float) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        if (len < 0.001f) return
        val nx = -dy / len * hw
        val ny =  dx / len * hw
        path.moveTo(x0 + nx, y0 + ny)
        path.lineTo(x1 + nx, y1 + ny)
        path.lineTo(x1 - nx, y1 - ny)
        path.lineTo(x0 - nx, y0 - ny)
        path.close()
    }
}

