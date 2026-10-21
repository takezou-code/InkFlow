package com.vic.inkflow.ui

import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.vic.inkflow.data.Point
import com.vic.inkflow.data.Stroke
import com.vic.inkflow.util.ShapeGeometry
import com.vic.inkflow.util.ShapeType

// ── InkCanvas 形狀/筆跡渲染 helpers ─────────────────────────────────────────
// drawWithCache 呼叫的底層繪製（Canvas 路徑 + 包絡填充）。純函數。

// ---- Shape rendering helpers ----

internal fun drawShapeOnCanvas(
    canvas: Canvas,
    stroke: Stroke,
    points: List<Point>,
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
    // **形狀的幾何規則只有一份**（`:shared` 的 `ShapeGeometry`）：RECT/CIRCLE 用儲存的
    // 框，LINE/ARROW 用**拖曳的兩個端點**（順序有意義）。兩端各寫一份這條規則的
    // 時候，「往上拖的線被畫成往下」這種錯誤就會只在一邊出現。
    val type = ShapeGeometry.parseType(stroke.shapeType) ?: return
    val r = Rect(stroke.boundsLeft, stroke.boundsTop, stroke.boundsRight, stroke.boundsBottom)
    when (type) {
        ShapeType.RECT   -> canvas.drawRect(r, paint)
        ShapeType.CIRCLE -> canvas.drawOval(r, paint)
        ShapeType.LINE   -> if (points.size >= 2) {
            canvas.drawLine(Offset(points.first().x, points.first().y), Offset(points.last().x, points.last().y), paint)
        }
        ShapeType.ARROW  -> if (points.size >= 2) {
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
    val (left, right) = ShapeGeometry.arrowHead(
        start.x, start.y, end.x, end.y, ShapeGeometry.arrowHeadSize(sw)
    )
    canvas.drawLine(end, left, paint)
    canvas.drawLine(end, right, paint)
}

internal fun DrawScope.drawArrowHeadInScope(start: Offset, end: Offset, color: Color, sw: Float) {
    val (left, right) = ShapeGeometry.arrowHead(
        start.x, start.y, end.x, end.y, ShapeGeometry.arrowHeadSize(sw)
    )
    drawLine(color, end, left, strokeWidth = sw, cap = StrokeCap.Round)
    drawLine(color, end, right, strokeWidth = sw, cap = StrokeCap.Round)
}

// ---- Freehand path helpers ----
//
// `toComposePath()`（筆跡的包絡路徑）與 `strokePaint()`（畫法）都在 `:shared`
// （`com.vic.inkflow.ui.StrokePaint`）：平板、桌面、PDF 匯出必須是同一份，
// 否則螢幕上看到的粗細與匯出的 PDF 會對不上。

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
    // 畫法（高亮的 Multiply 混合與透明度）共用 `:shared` 的 `strokePaint`。
    canvas.drawPath(path, strokePaint(color, isHighlighter))
}
