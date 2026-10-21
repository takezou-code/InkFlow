package com.vic.inkflow.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import androidx.compose.ui.geometry.Offset
import com.vic.inkflow.data.ImageAnnotationEntity
import com.vic.inkflow.data.repository.InkFlowRepositories
import com.vic.inkflow.ui.measureTextWidth
import com.vic.inkflow.ui.textAlignOffsetModel
import com.vic.inkflow.ui.wrapTextLines
import com.vic.inkflow.util.PageRaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 整頁合成、套索裁切與「裁完去哪」的落點。
 *
 * 為什麼從 [com.vic.inkflow.ui.EditorViewModel] 拆出來：
 * 這個區塊（整頁點陣合成、三圖層疊圖、套索裁切、裁切結果落檔）有它自己的一整套
 * 生命週期——**一次提取是一個不可分割的動作**（上鎖 → 渲染 → 裁切 → 落檔 → 復原），
 * 卻原來跟「選取」「undo 棧」「目前畫頁」共用同一個類別的 2,800 行狀態。
 *
 * 拆開之後：
 *  - 提取的互斥鎖跟提取的程式碼住在同一個類別裡，不會散落；
 *  - 這個類別**不認識** ViewModel 的 private 狀態，只認得建構時注入的東西；
 *  - ViewModel 保留同名 public 方法轉發，**呼叫點（EditorScreen、PageWorkspace）零改動**。
 *
 * 依賴以「當下要用的值」注入成 lambda：紙張尺寸會變（[modelWidth]/[modelHeight]），
 * 套索多邊形會變（[lassoPolygon]），所以不能在建構時取值快照。
 * 唯一的例外是 [documentUri]：一份 ViewModel 只服務一份文件，它是固定的。
 */
internal class ExportController(
    private val repos: InkFlowRepositories,
    private val documentUri: String,
    private val modelWidth: () -> Float,
    private val modelHeight: () -> Float,
    /** 目前生效的套索多邊形（現行套索優先，沒有就用上一次committed 的）。 */
    private val lassoPolygon: () -> List<Offset>,
    /**
     * 裁切結果落地後要做的事：記一格 undo、清選取、切回筆。
     * 由 ViewModel 端實作——undo 棧與選取是它的地盤，這裡不該伸手。
     */
    private val onRegionExtracted: suspend (ImageAnnotationEntity, Int) -> Unit,
    /**
     * 只裁切不落庫（分享用）之後要做的 事：清選取、切回筆。
     * 分成兩個 callback，是因為「要不要記 undo」是兩條路徑真正的差別。
     */
    private val onShareFinished: suspend () -> Unit
) {

    /**
     * 一次只准一個提取流程。
     *
     * 兩個提取同時跑會互相搶同一塊點陣記憶體，而且套索多邊形是共用的狀態——
     * 併發提取會拿到彼此的框，裁出來的東西是別人的選取。
     */
    private val extractionMutex = Mutex()

    /** 整頁合成結果（PDF＋圖/墨/字三層）＋渲染尺寸，供套索裁剪與整頁截圖共用。 */
    private data class FullPage(
        val bmp: Bitmap,
        val scale: Float,
        val w: Int,
        val h: Int
    )

    /** 整頁截圖存分享檔（工具列 AI 區「整頁送 AI」用；不裁剪不遮罩不寫 DB）。 */
    suspend fun capturePageToShareFile(
        context: Context,
        sourcePageIndex: Int,
        pdfPageBitmap: Bitmap?
    ): File? {
        if (!extractionMutex.tryLock()) return null
        return try {
            withContext(Dispatchers.IO) {
                val fp = renderFullPageComposite(context, sourcePageIndex, pdfPageBitmap)
                    ?: return@withContext null
                val sharedDir = File(context.cacheDir, "shared")
                if (!sharedDir.exists()) sharedDir.mkdirs()
                val file = File(sharedDir, "page_${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { out ->
                    fp.bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                Log.d("InkFlowDbg", "PAGESHOT file=${file.name} bytes=${file.length()} ${fp.w}x${fp.h}")
                fp.bmp.recycle()
                file
            }
        } finally {
            extractionMutex.unlock()
        }
    }

    /**
     * Shared pipeline for both lasso extraction flows: renders the full-page
     * composite (PDF layer + image/stroke/text annotations), crops it to the
     * lasso bounding box, applies the polygon mask and trims transparent
     * edges. Returns the trimmed bitmap or null when the region is degenerate
     * or the PDF layer is unavailable.
     */
    private suspend fun renderLassoExtraction(
        context: Context,
        sourcePageIndex: Int,
        pdfPageBitmap: Bitmap?,
        polygon: List<Offset>
    ): Bitmap? = withContext(Dispatchers.IO) {
        // Step 1（整頁合成＋三圖層）已抽成 renderFullPageComposite 共用
        val fp = renderFullPageComposite(context, sourcePageIndex, pdfPageBitmap)
            ?: return@withContext null
        val fullBitmap = fp.bmp
        val renderScale = fp.scale
        val fullW = fp.w
        val fullH = fp.h

        val minX = polygon.minOf { it.x }
        val minY = polygon.minOf { it.y }
        val maxX = polygon.maxOf { it.x }
        val maxY = polygon.maxOf { it.y }
        if (maxX <= minX || maxY <= minY) return@withContext null

        val cropW = maxX - minX
        val cropH = maxY - minY

        // ── Step 2: Crop lasso bounding box from full bitmap ──────────────────
        val cropPixX    = (minX * renderScale).toInt().coerceIn(0, fullW - 1)
        val cropPixY    = (minY * renderScale).toInt().coerceIn(0, fullH - 1)
        val cropPixW    = (cropW * renderScale).toInt().coerceAtLeast(1).coerceAtMost(fullW - cropPixX)
        val cropPixH    = (cropH * renderScale).toInt().coerceAtLeast(1).coerceAtMost(fullH - cropPixY)
        val croppedBitmap = Bitmap.createBitmap(fullBitmap, cropPixX, cropPixY, cropPixW, cropPixH)
        fullBitmap.recycle()

        // ── Step 3: Apply lasso polygon mask on the cropped bitmap ────────────
        val maskedBitmap = Bitmap.createBitmap(cropPixW, cropPixH, Bitmap.Config.ARGB_8888)
        val maskCanvas = Canvas(maskedBitmap)
        maskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val polyPath = Path()
        polyPath.moveTo((polygon.first().x - minX) * renderScale, (polygon.first().y - minY) * renderScale)
        for (i in 1 until polygon.size) {
            polyPath.lineTo((polygon[i].x - minX) * renderScale, (polygon[i].y - minY) * renderScale)
        }
        polyPath.close()
        maskCanvas.clipPath(polyPath)
        maskCanvas.drawBitmap(croppedBitmap, 0f, 0f, null)
        croppedBitmap.recycle()

        // Trim transparent borders so placement/aspect matches the actually selected region.
        val trimmedBitmap = PageRaster.trimTransparentEdges(maskedBitmap)
        if (trimmedBitmap !== maskedBitmap) maskedBitmap.recycle()
        trimmedBitmap
    }

    /**
     * 整頁合成：PDF 層＋image/stroke/text 三層畫到整頁點陣
     * （原 renderLassoExtraction Step 1 原樣搬出；呼叫方負責 recycle 回傳點陣）。
     */
    private suspend fun renderFullPageComposite(
        context: Context,
        sourcePageIndex: Int,
        pdfPageBitmap: Bitmap?
    ): FullPage? = withContext(Dispatchers.IO) {
        val sourceStrokes = repos.strokes.getStrokesForPage(documentUri, sourcePageIndex).first()
        val sourceImageAnnotations = repos.images.getForPage(documentUri, sourcePageIndex).first()
        val sourceTextAnnotations = repos.texts.getForPage(documentUri, sourcePageIndex).first()

        // ── Step 1: Render full model page onto a bitmap ──────────────────────
        // Each model unit = renderScale pixels; capped so huge pages cannot OOM.
        val modelW = modelWidth()
        val modelH = modelHeight()
        val renderScale = minOf(2f, 4096f / maxOf(modelW, modelH))
        val fullW = (modelW * renderScale).toInt()
        val fullH = (modelH * renderScale).toInt()
        val fullBitmap = Bitmap.createBitmap(fullW, fullH, Bitmap.Config.ARGB_8888)
        val fullCanvas = Canvas(fullBitmap)
        fullCanvas.scale(renderScale, renderScale)
        fullCanvas.drawColor(Color.WHITE)

        // PDF layer: prefer UI snapshot; if unavailable, render directly from source PDF.
        // Track whether the bitmap was created locally so we can recycle it after drawing.
        val localFallbackBitmap = if (pdfPageBitmap == null) {
            PageRaster.renderPdfPageFromDocumentUri(documentUri, sourcePageIndex, fullW, fullH)
        } else null
        val resolvedPdfBitmap = pdfPageBitmap ?: localFallbackBitmap
        if (resolvedPdfBitmap == null) {
            // Avoid generating a wrong composite (missing PDF layer).
            return@withContext null
        }
        fullCanvas.drawBitmap(
            resolvedPdfBitmap,
            Rect(0, 0, resolvedPdfBitmap.width, resolvedPdfBitmap.height),
            RectF(0f, 0f, modelW, modelH),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        // Caller-owned pdfPageBitmap must NOT be recycled here (it lives in PdfViewModel's LruCache).
        localFallbackBitmap?.recycle()

        // Image annotations layer
        for (img in sourceImageAnnotations) {
            val bmp = PageRaster.loadBitmapFromUri(context, img.uri)
            if (bmp != null) {
                fullCanvas.drawBitmap(
                    bmp, null,
                    RectF(img.modelX, img.modelY, img.modelX + img.modelWidth, img.modelY + img.modelHeight),
                    Paint(Paint.FILTER_BITMAP_FLAG)
                )
                bmp.recycle()
            }
        }

        // Strokes layer
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (swp in sourceStrokes) {
            val isHL = swp.stroke.isHighlighter
            strokePaint.color = swp.stroke.color
            strokePaint.strokeWidth = swp.stroke.strokeWidth * (if (isHL) 3f else 1f)
            strokePaint.alpha = if (isHL) (255 * 0.4f).toInt() else 255
            strokePaint.xfermode = if (isHL) PorterDuffXfermode(PorterDuff.Mode.MULTIPLY) else null

            if (swp.stroke.shapeType != null) {
                val r = RectF(swp.stroke.boundsLeft, swp.stroke.boundsTop, swp.stroke.boundsRight, swp.stroke.boundsBottom)
                when (swp.stroke.shapeType) {
                    "RECT"  -> fullCanvas.drawRect(r, strokePaint)
                    "OVAL"  -> fullCanvas.drawOval(r, strokePaint)
                    "LINE", "ARROW" -> if (swp.points.size >= 2) {
                        val p0 = swp.points.first(); val p1 = swp.points.last()
                        fullCanvas.drawLine(p0.x, p0.y, p1.x, p1.y, strokePaint)
                    }
                }
            } else {
                val pts = swp.points
                if (pts.size >= 2) {
                    val path = Path()
                    path.moveTo(pts[0].x, pts[0].y)
                    if (pts.size < 3) {
                        for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
                    } else {
                        for (i in 1 until pts.size) {
                            val prev = pts[i - 1]; val curr = pts[i]
                            path.quadTo(prev.x, prev.y, (prev.x + curr.x) / 2f, (prev.y + curr.y) / 2f)
                        }
                        path.lineTo(pts.last().x, pts.last().y)
                    }
                    fullCanvas.drawPath(path, strokePaint)
                }
            }
        }

        // Text / stamp layer
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        for (txt in sourceTextAnnotations) {
            textPaint.textSize = txt.fontSize
            textPaint.color = txt.colorArgb
            val boxW = txt.boxWidth?.takeIf { it > 0f }
            val lines = if (boxW != null) wrapTextLines(txt.text, txt.fontSize, boxW)
                        else txt.text.split("\n")
            var y = txt.modelY + txt.fontSize
            lines.forEach { line ->
                val off = if (boxW != null)
                    textAlignOffsetModel(measureTextWidth(line, txt.fontSize), boxW, txt.alignment) else 0f
                fullCanvas.drawText(line, txt.modelX + off, y, textPaint)
                y += txt.fontSize * 1.2f
            }
        }

        FullPage(fullBitmap, renderScale, fullW, fullH)
    }

    /** 套索裁切 → 成一張新頁上的圖片註解（並記一格可復原的 undo）。 */
    suspend fun extractRegionToNewPage(
        context: Context,
        sourcePageIndex: Int,
        targetPageIndex: Int,
        pdfPageBitmap: Bitmap?
    ): Boolean {
        val polygon = lassoPolygon()
        if (polygon.isEmpty()) return false

        if (!extractionMutex.tryLock()) return false
        try {
            return withContext(Dispatchers.IO) {
            val trimmedBitmap = renderLassoExtraction(context, sourcePageIndex, pdfPageBitmap, polygon)
                ?: return@withContext false
            val modelW = modelWidth()
            val modelH = modelHeight()
            val renderScale = minOf(2f, 4096f / maxOf(modelW, modelH))

            // Save PNG（經 PdfManager 漏斗落 workingDir：持久化註解圖同一種命，還原不斷鏈）
            val trimmedPixelW = trimmedBitmap.width.coerceAtLeast(1)
            val trimmedPixelH = trimmedBitmap.height.coerceAtLeast(1)
            val file = com.vic.inkflow.util.PdfManager.newAnnotationImageFile(context, ".png")
            FileOutputStream(file).use { out ->
                trimmedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            trimmedBitmap.recycle()

            // Place image on new page — centred horizontally, near top
            val finalW = trimmedPixelW / renderScale
            val finalH = trimmedPixelH / renderScale

            val topMarginPt = 24f   // ~8.5 mm from top edge (PDF points)
            val newModelX = ((modelW - finalW) / 2f).coerceAtLeast(0f)
            val newModelY = topMarginPt

        val newImage = ImageAnnotationEntity(
            id          = UUID.randomUUID().toString(),
            documentUri = documentUri,
            pageIndex   = targetPageIndex,
            // S1 雙寫：docY 錨點同步存。
            docY        = targetPageIndex * modelH.coerceAtLeast(1f) + newModelY,
            modelX      = newModelX,
            modelY      = newModelY,
                modelWidth  = finalW,
                modelHeight = finalH,
                uri         = android.net.Uri.fromFile(file).toString()
            )

            repos.transaction { repos.images.insert(newImage) }

            withContext(Dispatchers.Main) {
                // R3：整組記一格（圖＋新建頁），復原整組撤銷。
                onRegionExtracted(newImage, targetPageIndex)
            }
            true
            }
        } finally {
            extractionMutex.unlock()
        }
    }

    /** 套索裁切 → 存成分享用的 PNG（不寫 DB）。 */
    suspend fun extractRegionToShareFile(
        context: Context,
        sourcePageIndex: Int,
        pdfPageBitmap: Bitmap?
    ): File? {
        val polygon = lassoPolygon()
        if (polygon.isEmpty()) return null

        if (!extractionMutex.tryLock()) return null
        return try {
            withContext(Dispatchers.IO) {
                val trimmedBitmap = renderLassoExtraction(context, sourcePageIndex, pdfPageBitmap, polygon)
                    ?: return@withContext null

                val sharedDir = File(context.cacheDir, "shared")
                if (!sharedDir.exists()) sharedDir.mkdirs()
                val file = File(sharedDir, "share_${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { out ->
                    trimmedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                trimmedBitmap.recycle()

                withContext(Dispatchers.Main) {
                    onShareFinished()
                }

                file
            }
        } finally {
            extractionMutex.unlock()
        }
    }
}
