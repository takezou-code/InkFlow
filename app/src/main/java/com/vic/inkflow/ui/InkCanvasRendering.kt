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
import androidx.compose.ui.graphics.asAndroidPath
import com.vic.inkflow.data.Point
import com.vic.inkflow.data.Stroke
import com.vic.inkflow.data.StrokeWithSamples
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
// 否則蟳幕上看到的粗細與匯出的 PDF 會對不上。

/**
 * 兩段式降採樣解碼：先讀尺寸再按 2 的冪降採樣，長邊不超過 [maxSidePx]。
 * 原圖直解（一張照片 4000×3000×4 = 48MB 起跳）會吃光記憶體，
 * 交給 drawImage 時還會觸發「too large bitmap」閃退。
 */
private fun openDecodedStream(
    context: android.content.Context,
    uri: android.net.Uri
): java.io.InputStream? =
    try {
        // file:// 必須走 FileInputStream —— ContentResolver 在這台上不吃 file://，
        // 圖片註解因此靜默解不了（快取 swallow 失敗，畫布只剩空框）。content:// 照舊。
        if (uri.scheme == "file") {
            uri.path?.takeIf { it.isNotEmpty() }?.let { java.io.FileInputStream(it) }
        } else {
            context.contentResolver.openInputStream(uri)
        }
    } catch (_: Exception) {
        null
    }

/**
 * 顯示路徑專用解碼：像素直接進 GPU（AHardwareBuffer/DMABUF），CPU 側不留拷貝。
 *
 * 為什麼：ARGB_8888 解碼會在 CPU 側配置像素緩衝，HWUI 首次繪製時還要上傳 GPU 紋理，
 * 同一張圖被配置兩份實體記憶體（2026-11-15 M5：每頁 30 張公式圖 ≈ 32MB/頁的 CPU 側陰影）。
 * HARDWARE 位圖沒有 CPU 副本、也沒有上傳一步。
 *
 * 限制：HARDWARE 位圖不能被軟體 Canvas 繪製（`IllegalStateException: Software rendering
 * doesn't support hardware bitmaps`）。本函數只給 ImageAnnotationCache 使用，而它
 * 唯一的消費點都是 Compose `drawImage`——PDF 匯出走 PdfExporter 自己的
 * decodeBoundedForExport（ARGB_8888）。新增任何軟體繪製消費點前先換路徑。
 */
internal fun decodeBoundedBitmap(
    context: android.content.Context,
    uri: android.net.Uri,
    maxSidePx: Int = 2048
): android.graphics.Bitmap? {
    val (w, h) = openDecodedStream(context, uri)?.use { stream ->
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(stream, null, opts)
        opts.outWidth to opts.outHeight
    } ?: return null
    if (w <= 0 || h <= 0) {
        return openDecodedStream(context, uri)?.use {
            BitmapFactory.decodeStream(it, null, hardwareOpts())
        }
    }
    var sample = 1
    while (w / sample > maxSidePx || h / sample > maxSidePx) sample *= 2
    val opts = hardwareOpts().apply { inSampleSize = sample }
    return openDecodedStream(context, uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
}

private fun hardwareOpts(): BitmapFactory.Options =
    BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.HARDWARE }

internal fun drawPathOnCanvas(
    canvas: Canvas,
    path: Path, color: Color, strokeWidth: Float, isHighlighter: Boolean = false
) {
    // 畫法（高亮的 Multiply 混合與透明度）共用 `:shared` 的 `strokePaint`。
    canvas.drawPath(path, strokePaint(color, isHighlighter))
}

// ── P0-A 背景墨跡重烘（drawWithCache 派發，Default 執行緒執行）──────────────

/**
 * 背景重烘請求。帶快照：strokes/excludeIds 在派發瞬間凍結；背景烘到一半
 * 使用者又落筆時，快照不變——下一幀 order 檢測會再派發一次（收斂）。
 */
internal data class InkBakeRequest(
    val pageIndex: Int,
    val targetWidth: Int,
    val targetHeight: Int,
    /** model px → bitmap px 的縮放（sx * cacheScale） */
    val scaleX: Float,
    val scaleY: Float,
    /** 縮放/解析度 key（inkScaleKey，只認 sx/sy）：swap 時回寫 lastInkScale */
    val contentKey: Long,
    val strokes: List<StrokeWithSamples>,
    val excludeIds: Set<String>,
    /**
     * 這張位圖**實際裝了哪些筆**（非高亮、且不在 [excludeIds] 裡的全部）。
     *
     * 為什麼要帶這份清單：熱筆（最後提筆那筆）刻意不烙進位圖、改用向量層畫，
     * 所以「熱筆換人」時舊熱筆必須等下一次 swap 才回到位圖。若向量層一換人就
     * 放掉舊熱筆，中間會有一段「位圖裡沒有、向量層也沒有」的空窗（實測 53ms）
     * ——那就是使用者回報的「上一個筆畫閃一下」。
     *
     * swap 時把這份清單寫進 InkCanvas 的 `bakedIds`，向量層只畫「不在帳上」的筆，
     * 於是「舊熱筆退場」與「含它的位圖換上」變成同一瞬間。
     */
    val includedIds: Set<String>
)

private val bakeSlots = java.util.concurrent.Semaphore(2)
internal fun tryAcquireBakeSlot(): Boolean = bakeSlots.tryAcquire()
internal fun releaseBakeSlot() = bakeSlots.release()

/**
 * 在背景執行緒把整頁「已提交、非高亮、非 preview/overlay」筆跡烙到新 Bitmap。
 * 語意與主執行緒 full-rebuild 一致（高亮不烙入、走 live 疊層）；不共用 Compose
 * 的 committedPathCache（跨執行緒不安全），直接從 points 重建包絡路徑。
 * 異常由呼叫端（InkCanvas）捕捉——失敗就等下一幀的檢測重派。
 */
internal fun rasterizeInkPageBitmap(req: InkBakeRequest): android.graphics.Bitmap {
    val bmp = android.graphics.Bitmap.createBitmap(
        req.targetWidth, req.targetHeight, android.graphics.Bitmap.Config.ARGB_8888
    )
    val canvas = android.graphics.Canvas(bmp)
    canvas.scale(req.scaleX, req.scaleY)
    for (swp in req.strokes) {
        val st = swp.stroke
        if (st.isHighlighter) continue
        if (st.id in req.excludeIds) continue
        if (st.shapeType != null) {
            drawShapeAndroid(canvas, st, swp.points)
            continue
        }
        val path = swp.points.toComposePath().asAndroidPath()
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            color = st.color
        }
        canvas.drawPath(path, paint)
    }
    return bmp
}

private fun drawShapeAndroid(
    canvas: android.graphics.Canvas,
    stroke: Stroke,
    points: List<Point>
) {
    val r = android.graphics.RectF(
        stroke.boundsLeft, stroke.boundsTop, stroke.boundsRight, stroke.boundsBottom
    )
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = stroke.strokeWidth
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        color = stroke.color
    }
    val type = ShapeGeometry.parseType(stroke.shapeType) ?: return
    when (type) {
        ShapeType.RECT -> canvas.drawRect(r, paint)
        ShapeType.CIRCLE -> canvas.drawOval(r, paint)
        ShapeType.LINE -> if (points.size >= 2) {
            canvas.drawLine(points.first().x, points.first().y, points.last().x, points.last().y, paint)
        }
        ShapeType.ARROW -> if (points.size >= 2) {
            val p0 = points.first()
            val p1 = points.last()
            canvas.drawLine(p0.x, p0.y, p1.x, p1.y, paint)
            val (left, right) = ShapeGeometry.arrowHead(
                p0.x, p0.y, p1.x, p1.y, ShapeGeometry.arrowHeadSize(stroke.strokeWidth)
            )
            canvas.drawLine(p1.x, p1.y, left.x, left.y, paint)
            canvas.drawLine(p1.x, p1.y, right.x, right.y, paint)
        }
    }
}
