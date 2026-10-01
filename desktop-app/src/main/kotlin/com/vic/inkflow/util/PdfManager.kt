package com.vic.inkflow.util

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import mu.KotlinLogging
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.jetbrains.skia.Image as SkiaImage
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.LinkedHashMap
import javax.imageio.ImageIO
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val logger = KotlinLogging.logger {}

/** PDF points per inch — the conversion factor between the PDF user space and raster pixels. */
private const val POINTS_PER_INCH = 72f

/**
 * The single PDFBox entry point for the whole app.
 *
 * Everything that touches PDFBox lives here: [PdfViewer] and [PdfThumbnail] both call
 * these functions, so path resolution, error mapping and the DPI policy exist exactly once
 * and cannot drift apart.
 *
 * ## Threading
 *
 * Every function here is **blocking** (PDFBox parses and rasterises on the calling thread).
 * Callers must wrap them in `withContext(Dispatchers.IO)`. Nothing here touches Compose
 * state, so the object is safe to share across coroutines; the only shared mutable state is
 * the thumbnail LRU cache, which is guarded by its own monitor.
 */
object PdfManager {

    // ── Rendering policy ───────────────────────────────────────────────────

    /**
     * Raster resolution bounds for the full-size viewer. 72 DPI is PDFBox's native scale
     * (1 point = 1 pixel); anything below that is wasteful because the page is never
     * drawn smaller than 1:1 at the zoom floor, and 300 DPI is where a full A4 page
     * becomes ~8.7 MP, which is already the point where the PNG encode dominates the
     * frame time.
     */
    const val MIN_RENDER_DPI = 72f
    const val MAX_RENDER_DPI = 300f

    /**
     * Thumbnail resolution bounds. A library card shows a page at roughly 200 px wide, so
     * the 72 DPI floor the viewer uses would waste 2.3x the memory and encode time per
     * visible card, and the top end is lower because nothing is ever magnified.
     */
    const val MIN_THUMBNAIL_DPI = 24f
    const val MAX_THUMBNAIL_DPI = 144f

    /** Fallback DPI used before the page size or the viewport is known. */
    const val BASE_RENDER_DPI = 150f

    /**
     * Render a little above what the screen strictly needs, so that the resampling
     * filter has headroom and text stays crisp instead of landing exactly on pixel
     * centres.
     */
    private const val RENDER_HEADROOM = 1.15f

    /**
     * Desired DPI is rounded to multiples of this before it is used as a render key.
     * A pinch gesture produces a continuous stream of DPI values; bucketing them means
     * the debounced renderer can fire at most a handful of times per gesture instead
     * of once per pixel of zoom change.
     */
    const val DPI_BUCKET_SIZE = 12f

    /**
     * Upper bound on one raster, in pixels. Guards against poster-sized pages at 300 DPI,
     * which would take the heap down with an OOM rather than merely look wrong. A4 at
     * 300 DPI is 8.7 MP, so this is roughly "A2 at 300 DPI" — far above any real page.
     */
    private const val MAX_RENDER_PIXELS = 24_000_000f

    /**
     * Below this DPI the result is not worth showing: a page whose whole raster cannot
     * fit the budget even at this resolution is a poster, and a scaled-down smear of it
     * is worse than an honest error.
     */
    private const val MIN_USABLE_RENDER_DPI = 18f

    /** A PDF larger than this is refused rather than paged into memory. */
    private const val MAX_FILE_BYTES = 512L * 1024L * 1024L

    /** Number of rendered thumbnails kept in memory. Sized for one screenful of a dense grid. */
    private const val THUMBNAIL_CACHE_ENTRIES = 48

    // ── Path resolution ────────────────────────────────────────────────────

    /**
     * Where synced documents land on Windows. The tablet records an Android path in
     * `DocumentEntity.uri`, which does not exist here, so the file is looked up by name
     * under this directory. This fallback is the *only* way synced PDFs are found on
     * this machine — never remove it.
     */
    fun mirroredDir(): File =
        File(System.getProperty("user.home") ?: ".", ".inkflow" + File.separator + "documents")

    /**
     * Resolve a stored document URI to a real file, trying the recorded path first and
     * falling back to the mirrored copy. Returns `null` when neither exists.
     */
    fun resolveFile(documentUri: String): File? {
        if (documentUri.isBlank()) return null
        val raw = documentUri.removePrefix("file://")
        val direct = File(raw)
        if (direct.isFile) return direct
        val mirrored = File(mirroredDir(), direct.name)
        if (mirrored.isFile) return mirrored
        return null
    }

    // ── Metadata ───────────────────────────────────────────────────────────

    /**
     * Page count and the raster box of one page, read *without* rasterising.
     *
     * The viewer needs this before it can pick a DPI: fit-to-window scale needs the page
     * size in points, and the DPI needed to fill that scale needs the viewport. Doing
     * this as a cheap separate read avoids rendering a throwaway 150 DPI frame first.
     *
     * An out-of-range [pageIndex] is **clamped, not rejected**, and the count is reported
     * either way. The tablet can sync a stroke for a page the desktop's copy does not
     * have (different revision), so a stale index must not be able to wedge the viewer on
     * an error screen — it has to recover onto the nearest real page, which is only
     * possible if the caller learns the count first. [PageInfo.clampedFrom] says whether
     * that happened.
     */
    fun readPageInfo(documentUri: String, pageIndex: Int): PdfResult<PageInfo> =
        withDocument(documentUri) { doc ->
            val count = doc.numberOfPages
            if (count <= 0) return@withDocument PdfResult.Err("PDF 沒有任何頁面")
            val clamped = pageIndex.coerceIn(0, count - 1)
            if (clamped != pageIndex) {
                logger.warn {
                    "Page index $pageIndex is out of range for $documentUri ($count pages); using $clamped"
                }
            }
            PdfResult.Ok(PageInfo(count, boxInfo(doc, clamped), pageIndex.takeIf { it != clamped }))
        }

    // ── Rasterisation ──────────────────────────────────────────────────────

    /**
     * Rasterise one page at [dpi]. Blocking — call from a background dispatcher.
     *
     * @param dpi clamped into [MIN_RENDER_DPI]..[MAX_RENDER_DPI] and then down to what the
     *   pixel budget allows; see [renderDpiFor] and [dpiWithinBudget].
     */
    fun renderPage(documentUri: String, pageIndex: Int, dpi: Float): PdfResult<RenderedPage> =
        withDocument(documentUri) { doc ->
            rasterise(doc, documentUri, pageIndex, dpi, MIN_RENDER_DPI, MAX_RENDER_DPI)
        }

    /**
     * Render page 1 of a document at thumbnail size, memoised.
     *
     * A scrolling library grid calls this once per visible card, and without the cache
     * each scroll step re-parses and re-rasterises the whole document. Failures are
     * cached too, so a row whose file has gone missing is not re-probed on every
     * recomposition. The cache key includes the file's mtime and size, so a file that
     * is later synced in (or replaced) invalidates the entry automatically.
     *
     * Thumbnail resolution is capped much lower than [renderPage] — a card shows a page at
     * a couple of hundred pixels — and the same pixel budget applies, so one pathological
     * page in a grid cannot exhaust the heap.
     */
    fun renderThumbnail(documentUri: String, dpi: Float): PdfResult<ImageBitmap> {
        val file = resolveFile(documentUri)
        // A missing file gets a zero stamp, so the moment the file appears the key
        // changes and the stale negative entry is bypassed.
        val key = ThumbKey(
            path = file?.absolutePath ?: documentUri,
            mtime = file?.lastModified() ?: 0L,
            size = file?.length() ?: 0L,
            dpi = dpi
        )
        // The cache is in access order, so even the lookup mutates it — both halves are
        // under the same monitor.
        synchronized(thumbnailCache) { thumbnailCache[key] }?.let { return it }

        val result = when {
            file == null -> PdfResult.Err("找不到檔案")
            file.length() > MAX_FILE_BYTES -> PdfResult.Err("檔案過大（${file.length() / 1024 / 1024} MB）")
            else -> withDocument(file) { doc ->
                rasterise(doc, documentUri, 0, dpi, MIN_THUMBNAIL_DPI, MAX_THUMBNAIL_DPI)
                    .map { it.bitmap }
            }
        }
        if (result is PdfResult.Err) {
            logger.debug { "Thumbnail unavailable for $documentUri: ${result.message}" }
        }
        synchronized(thumbnailCache) { thumbnailCache[key] = result }
        return result
    }

    /** Drop every cached thumbnail. Used when the document library changes under us. */
    fun clearThumbnailCache() {
        synchronized(thumbnailCache) { thumbnailCache.clear() }
    }

    // ── DPI policy ─────────────────────────────────────────────────────────

    /**
     * The DPI at which this page should be rasterised for the current view.
     *
     * `zoom = 1` means "fit the page to the window", so the number of screen pixels per
     * PDF point is `fitScale * zoom` and a 1:1 raster would need `fitScale * zoom * 72` DPI.
     * Anything below that upscales the bitmap and the text visibly softens, so this
     * returns "1:1 plus headroom", clamped into [MIN_RENDER_DPI]..[MAX_RENDER_DPI]. An
     * oversized page is capped further down, inside [dpiWithinBudget].
     */
    fun renderDpiFor(
        pageWidthPt: Float,
        pageHeightPt: Float,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        zoom: Float
    ): Float {
        if (pageWidthPt <= 0f || pageHeightPt <= 0f) return BASE_RENDER_DPI
        if (viewportWidthPx <= 0f || viewportHeightPx <= 0f) return BASE_RENDER_DPI
        val fit = min(viewportWidthPx / pageWidthPt, viewportHeightPx / pageHeightPt)
        val needed = fit * zoom * POINTS_PER_INCH * RENDER_HEADROOM
        return needed.coerceIn(MIN_RENDER_DPI, MAX_RENDER_DPI)
    }

    /**
     * Quantise a desired DPI into a stable render key. The viewer only re-renders when
     * this bucket changes, which is what stops a pinch from queueing hundreds of frames.
     */
    fun dpiBucket(dpi: Float): Int = (dpi / DPI_BUCKET_SIZE).roundToInt()

    /** The DPI actually used for a bucket — snapping the output keeps it stable across runs. */
    fun dpiForBucket(bucket: Int): Float =
        (bucket * DPI_BUCKET_SIZE).coerceIn(MIN_RENDER_DPI, MAX_RENDER_DPI)

    /**
     * The DPI [renderPage] would *actually* use for [box], i.e. after the pixel budget.
     *
     * The viewer compares this against the DPI of the bitmap it already has, so a page
     * that is capped by the budget does not re-rasterise on every zoom step to produce a
     * pixel-identical result.
     */
    fun effectiveRenderDpi(dpi: Float, box: PageBox): Float =
        dpiWithinBudget(dpi.coerceIn(MIN_RENDER_DPI, MAX_RENDER_DPI), box) ?: MAX_RENDER_DPI

    /**
     * Whether it is worth re-rasterising to move from [currentDpi] to [targetDpi].
     *
     * Hysteresis: without it, a zoom that hovers on a bucket boundary re-renders forever.
     * Upscaling needs a real gain to be visible; downscaling is allowed sooner because the
     * bitmap is pure memory pressure.
     */
    fun shouldRerender(currentDpi: Float, targetDpi: Float): Boolean {
        if (currentDpi <= 0f) return true
        if (targetDpi > currentDpi * UPSCALE_RERENDER_GAIN) return true
        return targetDpi < currentDpi * DOWNSCALE_RERENDER_RATIO
    }

    private const val UPSCALE_RERENDER_GAIN = 1.25f
    private const val DOWNSCALE_RERENDER_RATIO = 0.8f

    // ── Internals ──────────────────────────────────────────────────────────

    /**
     * The one place a page becomes a bitmap. Both [renderPage] and [renderThumbnail] go
     * through here, so index clamping, the pixel budget, the DPI policy and the error
     * mapping exist exactly once — a thumbnail and the full-size page of the same
     * document cannot disagree about geometry or about how a failure reads.
     */
    private fun rasterise(
        doc: PDDocument,
        documentUri: String,
        pageIndex: Int,
        dpi: Float,
        minDpi: Float,
        maxDpi: Float
    ): PdfResult<RenderedPage> {
        val count = doc.numberOfPages
        if (count <= 0) return PdfResult.Err("PDF 沒有任何頁面")
        val index = pageIndex.coerceIn(0, count - 1)
        if (index != pageIndex) {
            logger.warn { "Page index $pageIndex clamped to $index for a $count page document ($documentUri)" }
        }
        val box = boxInfo(doc, index)

        val effectiveDpi = dpiWithinBudget(dpi.coerceIn(minDpi, maxDpi), box)
            ?: return PdfResult.Err(
                "頁面尺寸過大（${box.widthPt.toInt()}x${box.heightPt.toInt()} pt），無法繪製"
            )

        return runCatching {
            val awt = PDFRenderer(doc).renderImageWithDPI(index, effectiveDpi, ImageType.RGB)
            val bitmap = awt.toImageBitmap()
            logger.info {
                "Loaded PDF page: $documentUri page=${index + 1}/$count " +
                    "dpi=$effectiveDpi raster=${bitmap.width}x${bitmap.height} " +
                    "page=${box.widthPt}x${box.heightPt}pt"
            }
            RenderedPage(bitmap, box, effectiveDpi)
        }.fold(
            onSuccess = { PdfResult.Ok(it) },
            onFailure = { PdfResult.Err(describe(it), it) }
        )
    }

    /**
     * Cap [dpi] so the raster fits [MAX_RENDER_PIXELS], or `null` if even
     * [MIN_USABLE_RENDER_DPI] does not fit.
     *
     * Degrading the resolution is the right answer here, not refusing: the requested DPI
     * comes from the zoom, so a poster-sized page would otherwise error out exactly when
     * the user zooms in, replacing a viewable page with an error. A 24 MP ceiling still
     * keeps a single frame to a few tens of MB.
     */
    private fun dpiWithinBudget(dpi: Float, box: PageBox): Float? {
        val ptArea = box.widthPt.toDouble() * box.heightPt.toDouble()
        if (ptArea <= 0.0) return dpi
        val pxPerDpiSq = ptArea / (POINTS_PER_INCH * POINTS_PER_INCH).toDouble()
        val affordable = sqrt(MAX_RENDER_PIXELS / pxPerDpiSq)
        if (affordable < MIN_USABLE_RENDER_DPI) {
            logger.warn {
                "Page ${box.widthPt.toInt()}x${box.heightPt.toInt()}pt is too large to rasterise " +
                    "within ${MAX_RENDER_PIXELS.toInt()} px"
            }
            return null
        }
        if (dpi > affordable) {
            logger.info {
                "Capping render DPI from $dpi to ${affordable.toInt()} for a " +
                    "${box.widthPt.toInt()}x${box.heightPt.toInt()}pt page"
            }
        }
        return min(dpi, affordable.toFloat())
    }

    /**
     * Open, use and close the document, mapping every failure onto a user-facing
     * Traditional Chinese message. Nothing below this point throws.
     */
    private fun <T> withDocument(documentUri: String, block: (PDDocument) -> PdfResult<T>): PdfResult<T> {
        val file = resolveFile(documentUri)
            ?: return PdfResult.Err("找不到檔案，已尋找：${File(documentUri.removePrefix("file://")).name} 與 ${mirroredDir().absolutePath}")
        return withDocument(file, block)
    }

    private fun <T> withDocument(file: File, block: (PDDocument) -> PdfResult<T>): PdfResult<T> {
        if (!file.isFile) return PdfResult.Err("找不到檔案 ${file.name}")
        if (file.length() > MAX_FILE_BYTES) {
            return PdfResult.Err("檔案過大（${file.length() / 1024 / 1024} MB），無法開啟")
        }
        if (file.length() == 0L) return PdfResult.Err("檔案是空的（${file.name}）")
        return try {
            // The empty-password overload is explicit so an encrypted file fails with
            // InvalidPasswordException instead of a generic IOException.
            Loader.loadPDF(file, "").use { doc -> block(doc) }
        } catch (e: OutOfMemoryError) {
            logger.error(e) { "Out of memory rendering ${file.name}" }
            PdfResult.Err("PDF 太大，記憶體不足")
        } catch (e: Throwable) {
            logger.error(e) { "PDFBox failed on ${file.absolutePath}" }
            PdfResult.Err(describe(e), e)
        }
    }

/**
     * The box PDFRenderer actually rasterises, expressed in model space.
     *
     * `PDFRenderer` renders the **CropBox**, not the MediaBox, and places the raster's
     * (0,0) at the box's lower-left corner. Using the MediaBox width (as this code
     * previously did) silently mis-scales the ink on every PDF whose two boxes differ,
     * and [PageBox.originX]/[originY] let the caller undo the box offset.
     */
    private fun boxInfo(doc: PDDocument, pageIndex: Int): PageBox {
        val page = doc.getPage(pageIndex)
        val crop: PDRectangle = page.cropBox
        val media: PDRectangle = page.mediaBox
        if (crop.width <= 0 || crop.height <= 0) {
            logger.warn { "Page ${pageIndex + 1} of ${doc.numberOfPages} pages has a degenerate crop box" }
            return PageBox(media.width, media.height, 0f, 0f)
        }
        if (crop.width != media.width || crop.height != media.height ||
            crop.lowerLeftX != media.lowerLeftX || crop.lowerLeftY != media.lowerLeftY
        ) {
            logger.debug {
                "Page ${pageIndex + 1}: crop box ${crop.width}x${crop.height}@" +
                    "(${crop.lowerLeftX},${crop.lowerLeftY}) differs from media box " +
                    "${media.width}x${media.height}; ink is aligned to the crop box"
            }
        }
        return PageBox(crop.width, crop.height, crop.lowerLeftX, crop.lowerLeftY)
    }

    /**
     * Map a PDFBox failure onto a message a user can act on. The distinction that matters
     * is "password" vs "damaged" — the two need completely different responses.
     */
    private fun describe(e: Throwable): String = when (e) {
        is InvalidPasswordException -> "PDF 已加密，需要移除密碼保護才能開啟"
        is OutOfMemoryError -> "PDF 太大，記憶體不足"
        is IOException -> "PDF 檔案損毀或格式不正確：${e.message ?: "無法解析"}"
        else -> e.message ?: e.javaClass.simpleName
    }

    /**
     * PDFBox hands back an AWT `BufferedImage`; Compose draws `ImageBitmap`, and Compose
     * Desktop ships no direct bridge. Encode to PNG and let Skia decode it.
     *
     * A direct pixel copy would be several times faster, but it means hard-coding Skia's
     * `N32` layout (BGRA, little-endian only) against `BufferedImage`'s channel order.
     * That assumption is easy to get subtly wrong — e.g. PDFBox returning `TYPE_3BYTE_BGR`
     * for one page and `TYPE_INT_RGB` for another — and it fails as garbled colours rather
     * than as an exception. The round trip is a few milliseconds at thumbnail size and
     * happens off the UI thread.
     */
    private fun BufferedImage.toImageBitmap(): ImageBitmap {
        val out = ByteArrayOutputStream(1 shl 16)
        if (!ImageIO.write(this, "png", out)) {
            throw IOException("PNG 編碼失敗")
        }
        val encoded = out.toByteArray()
        val skia = SkiaImage.makeFromEncoded(encoded)
            ?: throw IOException("Skia 解碼 PNG 失敗（${encoded.size} bytes）")
        return skia.toComposeImageBitmap()
    }

    private data class ThumbKey(
        val path: String,
        val mtime: Long,
        val size: Long,
        val dpi: Float
    )

    /**
     * Bounded LRU of rasterised thumbnails. Synchronised on itself: reads happen from
     * whichever dispatcher the caller picked, and [renderThumbnail] is called from a
     * scrolling list where several rows can miss at once.
     */
    private val thumbnailCache = object : LinkedHashMap<ThumbKey, PdfResult<ImageBitmap>>(
        THUMBNAIL_CACHE_ENTRIES, 0.75f, true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<ThumbKey, PdfResult<ImageBitmap>>?
        ): Boolean = size > THUMBNAIL_CACHE_ENTRIES
    }
}

// ── Result type ──────────────────────────────────────────────────────────────

/**
 * A PDF operation outcome. Failures carry a message that is safe to show to the user
 * (Traditional Chinese, no stack traces) so no caller has to wrap anything in
 * `runCatching` to stay alive.
 */
sealed interface PdfResult<out T> {
    data class Ok<T>(val value: T) : PdfResult<T>
    data class Err(val message: String, val cause: Throwable? = null) : PdfResult<Nothing>

    fun valueOrNull(): T? = (this as? Ok)?.value
    fun errorOrNull(): String? = (this as? Err)?.message
    fun <R> map(transform: (T) -> R): PdfResult<R> = when (this) {
        is Ok -> Ok(transform(value))
        is Err -> this
    }
}

/** One page's raster geometry, in PDF points. See [PdfManager.boxInfo]. */
data class PageBox(
    val widthPt: Float,
    val heightPt: Float,
    val originX: Float,
    val originY: Float
)

/** Page count plus the geometry of one page. */
data class PageInfo(
    val pageCount: Int,
    val box: PageBox,
    /** The index that was asked for when it had to be clamped, otherwise `null`. */
    val clampedFrom: Int? = null
)

/** A rasterised page: the bitmap plus the exact geometry it was rendered at. */
data class RenderedPage(
    val bitmap: ImageBitmap,
    /**
     * The box the raster covers, in PDF points. The ink must be positioned against
     * *this* and not a separately-read page box, so the two can never disagree.
     */
    val box: PageBox,
    /** The DPI actually used, after clamping — lower than requested for oversized pages. */
    val dpi: Float
)
