package com.vic.inkflow.ui

import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.vic.inkflow.data.PointEntity
import com.vic.inkflow.data.StrokeEntity
import com.vic.inkflow.util.EnvelopeUtils
import com.vic.inkflow.util.StrokePoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// ── InkCanvas 形狀/筆跡渲染 helpers ─────────────────────────────────────────
// drawWithCache 呼叫的底層繪製（Canvas 路徑 + 包絡填充）。純函數。

// ---- Shape rendering helpers ----

internal fun drawShapeOnCanvas(
    canvas: Canvas,
    stroke: StrokeEntity,
    points: List<PointEntity>,
    tintColor: Color? = null
) {
    val color = tintColor ?: Color(stroke.color)
    val paint = Paint().apply {
        this.color   = color
        style        = PaintingStyle.Stroke
        strokeWidth  = stroke.strokeWidth
        strokeCap    = StrokeCap.Round
        strokeJoin   = StrokeJoin.Round
    }
    val r = Rect(stroke.boundsLeft, stroke.boundsTop, stroke.boundsRight, stroke.boundsBottom)
    when (stroke.shapeType) {
        "RECT"   -> canvas.drawRect(r, paint)
        "CIRCLE" -> canvas.drawOval(r, paint)
        "LINE"   -> if (points.size >= 2) {
            canvas.drawLine(Offset(points.first().x, points.first().y), Offset(points.last().x, points.last().y), paint)
        }
        "ARROW"  -> if (points.size >= 2) {
            val p0 = Offset(points.first().x, points.first().y)
            val p1 = Offset(points.last().x,  points.last().y)
            canvas.drawLine(p0, p1, paint)
            drawArrowHeadOnCanvas(canvas, p0, p1, paint, stroke.strokeWidth)
        }
    }
}

internal fun drawArrowHeadOnCanvas(
    canvas: Canvas,
    start: Offset, end: Offset, paint: Paint, sw: Float
) {
    val headSize   = sw * 5f + 10f
    val angle      = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
    val leftAngle  = angle + Math.PI * 0.75
    val rightAngle = angle - Math.PI * 0.75
    val lp = Offset(end.x + (headSize * cos(leftAngle)).toFloat(),  end.y + (headSize * sin(leftAngle)).toFloat())
    val rp = Offset(end.x + (headSize * cos(rightAngle)).toFloat(), end.y + (headSize * sin(rightAngle)).toFloat())
    canvas.drawLine(end, lp, paint)
    canvas.drawLine(end, rp, paint)
}

internal fun DrawScope.drawArrowHeadInScope(start: Offset, end: Offset, color: Color, sw: Float) {
    val headSize   = sw * 5f + 10f
    val angle      = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
    val leftAngle  = angle + Math.PI * 0.75
    val rightAngle = angle - Math.PI * 0.75
    val lp = Offset(end.x + (headSize * cos(leftAngle)).toFloat(),  end.y + (headSize * sin(leftAngle)).toFloat())
    val rp = Offset(end.x + (headSize * cos(rightAngle)).toFloat(), end.y + (headSize * sin(rightAngle)).toFloat())
    drawLine(color, end, lp, strokeWidth = sw, cap = StrokeCap.Round)
    drawLine(color, end, rp, strokeWidth = sw, cap = StrokeCap.Round)
}

// ---- Freehand path helpers ----

internal fun List<PointEntity>.toComposePath(): Path {
    val strokePoints = this.map { StrokePoint(it.x, it.y, it.width) }
    return EnvelopeUtils.generateEnvelopePath(strokePoints)
}

/**
 * 兩段式降採樣解碼：先讀尺寸再按 2 的冪降採樣，長邊不超過 [maxSidePx]。
 * 原圖直解（一張照片 4000×3000×4 = 48MB 起跳）會吃光記憶體，
 * 交給 drawImage 時還會觸發「too large bitmap」閃退。
 */
internal fun decodeBoundedBitmap(
    context: android.content.Context,
    uri: android.net.Uri,
    maxSidePx: Int = 2048
): android.graphics.Bitmap? {
    val cr = context.contentResolver
    val (w, h) = cr.openInputStream(uri)?.use { stream ->
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(stream, null, opts)
        opts.outWidth to opts.outHeight
    } ?: return null
    if (w <= 0 || h <= 0) {
        return cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    }
    var sample = 1
    while (w / sample > maxSidePx || h / sample > maxSidePx) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
}

internal fun drawPathOnCanvas(
    canvas: Canvas,
    path: Path, color: Color, strokeWidth: Float, isHighlighter: Boolean = false
) {
    canvas.drawPath(path, Paint().apply {
        this.color       = if (isHighlighter) color.copy(alpha = 0.4f) else color
        this.style       = PaintingStyle.Fill
        if (isHighlighter) this.blendMode = BlendMode.Multiply
    })
}
