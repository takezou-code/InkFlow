package com.vic.inkflow.util

import com.vic.inkflow.data.DatabaseManager
import com.vic.inkflow.data.StrokeWithPoints
import com.vic.inkflow.data.TextAnnotationEntity
import mu.KotlinLogging
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import java.awt.geom.Point2D
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.hypot

private val logger = KotlinLogging.logger {}

/**
 * Writes the desktop's annotations into a copy of the source PDF.
 *
 * ## Why this exists
 *
 * Without it the desktop can draw but cannot deliver: there is no way to get an
 * annotated PDF out of the app, so every note made on Windows is trapped here.
 *
 * ## The transform
 *
 * Annotations live in a y-down model space with a top-left origin. PDF user space is
 * y-up from the bottom-left, so every point goes through `pageHeight - y`. Forgetting
 * that one flip mirrors the whole document — the ink is still *there*, on the right
 * pages, just upside-down relative to the page content, which is exactly the kind of
 * bug that survives a casual look at the file.
 *
 * ## Why shapes bypass the ink path
 *
 * Freehand strokes are variable-width polygons and have to be filled to look right.
 * Shapes are stroked outlines. Running a two-point shape through the envelope filler
 * produces a thin lens between its corners instead of a rectangle.
 *
 * ## Per-page loading
 *
 * A long document is loaded a page at a time. Pulling every stroke and note for a
 * 1,500-page document into memory at once is how an export turns into an OutOfMemory,
 * and the failure happens after a long wait with no output.
 */
object PdfExporter {

    /**
     * Export [strokes] and [texts] onto a copy of [sourcePdf].
     *
     * Returns the number of pages written. Throws on failure rather than returning a
     * half-written file: a silently truncated PDF is worse than an error, because the
     * user will not find out until they hand it in.
     */
    fun export(
        sourcePdf: File,
        destination: File,
        modelWidth: Float,
        modelHeight: Float,
        strokes: List<StrokeWithPoints>,
        texts: List<TextAnnotationEntity>
    ): Int {
        // Copy the source first and draw on the copy: the source PDF is never opened
        // for modification, so a failed export cannot damage the original.
        copySource(sourcePdf, destination)

        // Reopen the copy, stamp the annotations on, save beside it, then replace.
        // Loading from `destination` while saving to `destination` is exactly the
        // read/write collision PDFBox warns about.
        val tmp = newTempOutput(destination)
        try {
            val count = Loader.loadPDF(destination).use { doc ->
                val strokesByPage = strokes.groupBy { it.stroke.pageIndex }
                val textsByPage = texts.groupBy { it.pageIndex }

                doc.pages.forEachIndexed { index, page ->
                    val pageStrokes = strokesByPage[index].orEmpty()
                    val pageTexts = textsByPage[index].orEmpty()
                    if (pageStrokes.isEmpty() && pageTexts.isEmpty()) return@forEachIndexed

                    val media = page.mediaBox
                    val pageW = media.width
                    val pageH = media.height

                    // The annotation model space is the PAGE's own size, not a fixed A4.
                    // Assuming A4 for a Letter document shifts every mark, because the
                    // model->page ratio is wrong even though the page itself is untouched.
                    val mW = if (modelWidth > 0f) modelWidth else pageW
                    val mH = if (modelHeight > 0f) modelHeight else pageH

                    PDPageContentStream(doc, page, org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode.APPEND, true).use { cs ->
                        pageStrokes.forEach { swp ->
                            val type = ShapeGeometry.parseType(swp.stroke.shapeType)
                            if (type != null) drawShape(cs, swp, type, pageW, pageH, mW, mH)
                            else drawInk(cs, swp, pageW, pageH, mW, mH)
                        }
                        // Ink last: a note written over a highlight must sit on top of it,
                        // matching the on-screen z-order.
                        pageTexts.forEach { drawText(cs, it, pageW, pageH, mW, mH) }
                    }
                }

                doc.save(tmp)
                doc.numberOfPages
            }
            replaceWithTemp(tmp, destination)
            logger.info { "Exported ${strokes.size} stroke(s) and ${texts.size} note(s) across $count page(s)" }
            return count
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /**
     * Export straight from the database, one page at a time.
     *
     * Returns the page count. This is the path the UI uses, and the one that has to
     * avoid loading a whole document at once.
     */
    fun exportFromDatabase(
        db: DatabaseManager,
        documentUri: String,
        sourcePdf: File,
        destination: File,
        modelWidth: Float,
        modelHeight: Float,
        maxPages: Int = MAX_EXPORT_PAGES
    ): Int {
        copySource(sourcePdf, destination)

        val tmp = newTempOutput(destination)
        try {
            val count = Loader.loadPDF(destination).use { doc ->
                val pageCount = doc.numberOfPages
                // Refuse rather than truncate: a user who exports 1,500 pages and gets
                // 800 has a file that looks complete and is not.
                if (pageCount > maxPages) {
                    throw IllegalArgumentException("文件有 $pageCount 頁，超過匯出上限 $maxPages 頁")
                }

                doc.pages.forEachIndexed { index, page ->
                    val strokes = db.getStrokesForPage(documentUri, index)
                    val texts = db.getTextAnnotationsForPage(documentUri, index)
                    if (strokes.isEmpty() && texts.isEmpty()) return@forEachIndexed

                    val pageW = page.mediaBox.width
                    val pageH = page.mediaBox.height
                    val mW = if (modelWidth > 0f) modelWidth else pageW
                    val mH = if (modelHeight > 0f) modelHeight else pageH

                    PDPageContentStream(doc, page, org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode.APPEND, true).use { cs ->
                        strokes.forEach { swp ->
                            val type = ShapeGeometry.parseType(swp.stroke.shapeType)
                            if (type != null) drawShape(cs, swp, type, pageW, pageH, mW, mH)
                            else drawInk(cs, swp, pageW, pageH, mW, mH)
                        }
                        texts.forEach { drawText(cs, it, pageW, pageH, mW, mH) }
                    }
                }

                doc.save(tmp)
                doc.numberOfPages
            }
            replaceWithTemp(tmp, destination)
            return count
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /**
     * The one font every note is drawn with.
     *
     * A `PDType1Font` wraps a standard PDF base font, which costs a little to
     * construct; building one per note would dominate the cost of exporting a page
     * that is mostly text. The 14 standard fonts need no embedding, so a CJK note
     * will fall back to whatever the reader substitutes — real CJK output needs an
     * embedded font, which is a separate piece of work.
     */
    private val exportFont: org.apache.pdfbox.pdmodel.font.PDType1Font by lazy {
        org.apache.pdfbox.pdmodel.font.PDType1Font(
            org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA
        )
    }

    /** Refuse absurd exports up front rather than after an hour of work. */
    private const val MAX_EXPORT_PAGES = 3_000

    /**
     * Copy the source PDF to the working destination.
     *
     * Copying bytes is the only safe first step. Loading the source with PDFBox and
     * immediately saving elsewhere still leaves the source open while the destination
     * is written; a byte copy makes "never modify the source" structural.
     */
    private fun copySource(sourcePdf: File, destination: File) {
        if (sourcePdf.canonicalFile == destination.canonicalFile) {
            throw IllegalArgumentException("匯出位置不能與原始檔案相同，否則會破壞原檔")
        }
        val parent = destination.parentFile
            ?: throw java.io.IOException("匯出位置沒有父目錄：$destination")
        parent.mkdirs()
        // A directory is not a file. Copying/moving over it would either fail deep
        // inside NIO or, worse, replace an empty directory on some platforms — so
        // refuse up front with the destination in the message.
        if (destination.exists() && destination.isDirectory) {
            throw java.io.IOException("匯出位置是一個目錄，不是檔案：$destination")
        }
        Files.copy(sourcePdf.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /**
     * Save to a temp file next to the destination, then replace it.
     *
     * PDFBox warns when a document is saved back to the file it was loaded from, and
     * it is right: reading and writing the same file handle is how an export becomes
     * a truncated PDF. Writing beside it and moving after close keeps either a valid
     * old file or a valid new file, never a half-written one.
     */
    private fun newTempOutput(destination: File): File {
        val parent = destination.parentFile
            ?: throw java.io.IOException("匯出位置沒有父目錄：$destination")
        parent.mkdirs()
        val stem = destination.nameWithoutExtension.ifEmpty { "export" }
        return File.createTempFile("$stem-inkflow-", ".pdf", parent)
    }

    private fun replaceWithTemp(temp: File, destination: File) {
        try {
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    // ─── Ink ─────────────────────────────────────────────────────────────────

    /**
     * One freehand stroke, filled as a variable-width ribbon.
     *
     * Each point contributes a disc of its own sampled width, and consecutive discs
     * are joined by quadratic curves along their common tangent. This is what makes
     * the exported stroke match what was on screen — a plain stroked polyline would
     * come out uniformly thin and lose the pressure variation entirely.
     */
    private fun drawInk(
        cs: PDPageContentStream,
        swp: StrokeWithPoints,
        pageW: Float,
        pageH: Float,
        mW: Float,
        mH: Float
    ) {
        val pts = swp.points.map {
            com.vic.inkflow.util.StrokePoint(it.x, it.y, if (it.width > 0f) it.width else swp.stroke.strokeWidth)
        }
        if (pts.isEmpty()) return

        val rx = pageW / mW
        val ry = pageH / mH
        fun px(x: Float) = x * rx
        fun py(y: Float) = pageH - y * ry

        val argb = swp.stroke.color
        val alpha = if (swp.stroke.isHighlighter) ExportStyle.highlighterAlpha else ((argb ushr 24) and 0xFF) / 255f
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF

        cs.saveGraphicsState()
        cs.setGraphicsStateParameters(
            PDExtendedGraphicsState().apply {
                // Fill only: the ribbon is built as a filled outline, so stroking it
                // as well would double the apparent width at every join.
                setNonStrokingAlphaConstant(alpha)
                setStrokingAlphaConstant(alpha)
            }
        )
        cs.setNonStrokingColor(r / 255f, g / 255f, b / 255f)
        cs.setStrokingColor(r / 255f, g / 255f, b / 255f)

        if (pts.size == 1) {
        // A tap is a dot. Stored as two coincident points, but a one-point stroke
            // can still arrive from an older tablet build, and it must not vanish.
            val p = pts.first()
            val rad = (p.width / 2f) * rx
            appendDisc(cs, px(p.x), py(p.y), rad)
            cs.fill()
            cs.restoreGraphicsState()
            return
        }

        // Left/right offset rails, then a closed ribbon between them.
        val left = ArrayList<Point2D.Float>(pts.size)
        val right = ArrayList<Point2D.Float>(pts.size)

        for (i in pts.indices) {
            val cur = pts[i]
            // Skip neighbours closer than a model point: two samples a fraction of a
            // point apart give a tangent that is numerically garbage, which shows up
            // as a spike in the exported ink.
            var prevI = i - 1
            while (prevI >= 0 && hypot(cur.x - pts[prevI].x, cur.y - pts[prevI].y) < 1f) prevI--
            val prev = if (prevI >= 0) pts[prevI] else cur

            var nextI = i + 1
            while (nextI < pts.size && hypot(pts[nextI].x - cur.x, pts[nextI].y - cur.y) < 1f) nextI++
            val next = if (nextI < pts.size) pts[nextI] else cur

            var dx: Float; var dy: Float
            when {
                prev === cur && next !== cur -> { dx = next.x - cur.x; dy = next.y - cur.y }
                next === cur && prev !== cur -> { dx = cur.x - prev.x; dy = cur.y - prev.y }
                else -> { dx = next.x - prev.x; dy = next.y - prev.y }
            }
            val len = hypot(dx, dy)
            if (len > 0.01f) { dx /= len; dy /= len } else { dx = 1f; dy = 0f }

            val rad = cur.width / 2f
            left.add(Point2D.Float(cur.x - dy * rad, cur.y + dx * rad))
            right.add(Point2D.Float(cur.x + dy * rad, cur.y - dx * rad))
        }

        cs.moveTo(px(left.first().x), py(left.first().y))
        for (i in 1 until left.size) {
            joinRibbon(cs, left[i - 1], left[i], rx, ry, pageH)
        }
        // Round cap at the far end, drawn as its own subpath so the ribbon keeps a
        // continuous outline: an open path filled in PDF closes itself along a chord,
        // which would cut the cap flat.
        appendDisc(cs, px(pts.last().x), py(pts.last().y), pts.last().width / 2f * rx)
        for (i in right.indices.reversed()) {
            if (i == right.size - 1) {
                appendDisc(cs, px(pts.first().x), py(pts.first().y), pts.first().width / 2f * rx)
            } else {
                joinRibbon(cs, right[i], right[i + 1], rx, ry, pageH)
            }
        }
        cs.fill()
        cs.restoreGraphicsState()
    }

    /** A filled circle as four cubic arcs, for stroke end caps. */
    private fun appendDisc(cs: PDPageContentStream, cx: Float, cy: Float, r: Float) {
        val k = ExportStyle.ELLIPSE_K
        cs.moveTo(cx, cy + r)
        cs.curveTo(cx + k * r, cy + r, cx + r, cy + k * r, cx + r, cy)
        cs.curveTo(cx + r, cy - k * r, cx + k * r, cy - r, cx, cy - r)
        cs.curveTo(cx - k * r, cy - r, cx - r, cy - k * r, cx - r, cy)
        cs.curveTo(cx - r, cy + k * r, cx - k * r, cy + r, cx, cy + r)
    }

    private fun joinRibbon(
        cs: PDPageContentStream,
        from: Point2D.Float,
        to: Point2D.Float,
        rx: Float,
        ry: Float,
        pageH: Float
    ) {
        // Curve through the segment midpoint: a quadratic-ish blend of the two ends.
        // A plain lineTo here is visibly polygonal on a long fast stroke, and the
        // exported ink no longer matches what the user drew on screen.
        val cx = (from.x + to.x) / 2f
        val cy = (from.y + to.y) / 2f
        fun X(v: Float) = v * rx
        fun Y(v: Float) = pageH - v * ry
        cs.curveTo(
            X(from.x), Y(from.y),
            X((from.x + cx) / 2f), Y((from.y + cy) / 2f),
            X(cx), Y(cy)
        )
        cs.lineTo(X(to.x), Y(to.y))
    }

    // ─── Shapes ──────────────────────────────────────────────────────────────

    /**
     * One shape, stroked as an outline.
     *
     * RECT and CIRCLE come from the stored bounds; LINE and ARROW from the two
     * points **in stored order** — sorting them would flip the direction of every
     * shape drawn upwards.
     */
    private fun drawShape(
        cs: PDPageContentStream,
        swp: StrokeWithPoints,
        type: ShapeType,
        pageW: Float,
        pageH: Float,
        mW: Float,
        mH: Float
    ) {
        val s = swp.stroke
        // Two ratios, not one: pages are not square and a shape drawn on a Letter
        // page must not be stretched to fill it.
        val rx = pageW / mW
        val ry = pageH / mH
        val argb = s.color
        val alpha = if (s.isHighlighter) ExportStyle.highlighterAlpha else ((argb ushr 24) and 0xFF) / 255f
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF

        val modelPts = swp.points.map { StrokePoint(it.x, it.y, it.width) }
        val bounds = ShapeGeometry.boundsOf(modelPts)

        fun px(x: Float) = x * rx
        fun py(y: Float) = pageH - y * ry

        cs.saveGraphicsState()
        cs.setGraphicsStateParameters(
            PDExtendedGraphicsState().apply {
                setStrokingAlphaConstant(alpha)
                setNonStrokingAlphaConstant(alpha)
            }
        )
        cs.setStrokingColor(r / 255f, g / 255f, b / 255f)
        val scaledWidth = s.strokeWidth * rx
        cs.setLineWidth(scaledWidth)
        cs.setLineCapStyle(1)
        cs.setLineJoinStyle(1)

        when (type) {
            ShapeType.RECT -> {
                cs.addRect(px(bounds[0]), py(bounds[3]), (bounds[2] - bounds[0]) * rx, (bounds[3] - bounds[1]) * ry)
                cs.stroke()
            }

            ShapeType.CIRCLE -> appendEllipse(
                cs, px(bounds[0]), py(bounds[3]),
                (bounds[2] - bounds[0]) * rx, (bounds[3] - bounds[1]) * ry
            )

            ShapeType.LINE -> {
                val e = ShapeGeometry.endpoints(modelPts)
                cs.moveTo(px(e[0]), py(e[1]))
                cs.lineTo(px(e[2]), py(e[3]))
                cs.stroke()
            }

            ShapeType.ARROW -> {
                val e = ShapeGeometry.endpoints(modelPts)
                val x0 = px(e[0]); val y0 = py(e[1])
                val x1 = px(e[2]); val y1 = py(e[3])
                cs.moveTo(x0, y0)
                cs.lineTo(x1, y1)
                cs.stroke()
                // The head uses the already-scaled width: the content stream is in PDF
                // units, so a model-space head would come out far too small on an
                // upscaled page.
                val head = ShapeGeometry.arrowHead(x0, y0, x1, y1, ShapeGeometry.arrowHeadSize(scaledWidth))
                cs.moveTo(x1, y1)
                cs.lineTo(head.first.x, head.first.y)
                cs.moveTo(x1, y1)
                cs.lineTo(head.second.x, head.second.y)
                cs.stroke()
            }
        }
        cs.restoreGraphicsState()
    }

    /** A full ellipse as four cubic Bezier arcs, using the standard 0.5523 factor. */
    private fun appendEllipse(cs: PDPageContentStream, x: Float, y: Float, w: Float, h: Float) {
        val cx = x + w / 2f
        val cy = y + h / 2f
        val rx = w / 2f
        val ry = h / 2f
        val k = ExportStyle.ELLIPSE_K
        cs.moveTo(cx, cy + ry)
        cs.curveTo(cx + k * rx, cy + ry, cx + rx, cy + k * ry, cx + rx, cy)
        cs.curveTo(cx + rx, cy - k * ry, cx + k * rx, cy - ry, cx, cy - ry)
        cs.curveTo(cx - k * rx, cy - ry, cx - rx, cy - k * ry, cx - rx, cy)
        cs.curveTo(cx - rx, cy + k * ry, cx - k * rx, cy + ry, cx, cy + ry)
        cs.closeAndStroke()
    }

    // ─── Text ────────────────────────────────────────────────────────────────

    /**
     * One note, drawn line by line.
     *
     * `modelY` is the first line's **baseline**, matching the tablet. Each
     * subsequent line drops by 1.2em, which is the tablet's line height — using a
     * different value here would make a two-line note overflow its own hit box.
     *
     * A blank note is skipped: it has no bounds, so it is invisible on screen too,
     * and drawing it would put a text object in the PDF that nobody can see or edit.
     */
    private fun drawText(
        cs: PDPageContentStream,
        note: TextAnnotationEntity,
        pageW: Float,
        pageH: Float,
        mW: Float,
        mH: Float
    ) {
        if (note.text.isBlank()) return

        val rx = pageW / mW
        val ry = pageH / mH
        val argb = note.colorArgb
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        val size = note.fontSize * rx

        cs.saveGraphicsState()
        // One text object with one text-showing run per non-blank line. Calling
        // beginText() inside beginText() is illegal and produces a content stream
        // some readers drop; setting the matrix again for each line is the legal way
        // to move the baseline down the page.
        cs.beginText()
        cs.setFont(exportFont, size)
        cs.setNonStrokingColor(r / 255f, g / 255f, b / 255f)

        // PDF's text matrix has y growing upward from the baseline, so a baseline at
        // model y maps to pageH - y*ry and each later line drops further. `modelY` is
        // the FIRST baseline, not the top — the same convention the hit box uses.
        var baseline = note.modelY
        note.text.split('\n').forEach { line ->
            if (line.isNotBlank()) {
                cs.setTextMatrix(
                    org.apache.pdfbox.util.Matrix(1f, 0f, 0f, 1f, note.modelX * rx, pageH - baseline * ry)
                )
                cs.showText(line)
            }
            baseline -= note.fontSize * com.vic.inkflow.data.TextMetrics.LINE_SPACING
        }
        cs.endText()
        cs.restoreGraphicsState()
    }
}