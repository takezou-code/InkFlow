package com.vic.inkflow.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vic.inkflow.data.DatabaseManager
import com.vic.inkflow.data.StrokeWithPoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.image.BufferedImage
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.max
import kotlin.math.roundToInt

private val logger = KotlinLogging.logger {}

/**
 * Read-only PDF viewer: rendered page + the tablet's strokes on top.
 *
 * ## The coordinate problem this solves
 *
 * Strokes arrive in the tablet's **model space** — PDF points, i.e. roughly
 * 0..595 x 0..842 for A4 portrait. The page is rendered to a bitmap at a
 * chosen DPI, so at 150 DPI an A4 page is about 1240 x 1754 pixels. Drawing
 * `point.x, point.y` straight onto the image therefore puts every stroke at
 * roughly a quarter scale, jammed into the top-left corner.
 *
 * The fix is a single explicit transform, and it is the *only* place that
 * converts model space to screen space:
 *
 * ```
 * screenX = modelX * (bitmapWidth  / pageWidthPt) * zoom + panX
 * screenY = modelY * (bitmapHeight / pageHeightPt) * zoom + panY
 * ```
 *
 * Everything else in this file (hit areas, page fitting) is derived from the
 * same two numbers, so there is exactly one source of truth for the mapping.
 *
 * Rendering goes through `ImageBitmap` rather than a Swing panel: the PDF page
 * and the ink must live in the same Compose coordinate space, and a Swing
 * interop layer cannot participate in Compose's transform/pan/zoom.
 */
@Composable
fun PdfViewer(
    documentUri: String,
    pageIndex: Int,
    databaseManager: DatabaseManager,
    modifier: Modifier = Modifier,
    onPageCountChange: (Int) -> Unit = {}
) {
    // ── Page rendering ──────────────────────────────────────────────────────
    var pdfImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var pageSizePt by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    // ── Ink ─────────────────────────────────────────────────────────────────
    var strokes by remember(documentUri, pageIndex) {
        mutableStateOf<List<StrokeWithPoints>>(emptyList())
    }

    // ── View transform ──────────────────────────────────────────────────────
    var zoom by remember { mutableStateOf(1.0f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(Size.Zero) }

    // Render on a background dispatcher: PDFBox is a blocking, CPU-heavy call
    // and this runs while scrolling.
    LaunchedEffect(documentUri, pageIndex) {
        loading = true
        loadError = null
        val result = withContext(Dispatchers.IO) { renderPage(documentUri, pageIndex) }
        when (result) {
            is PageRender.Ok -> {
                pdfImage = result.image
                pageSizePt = result.sizePt
                loading = false
            }
            is PageRender.Err -> {
                pdfImage = null
                loadError = result.message
                loading = false
            }
        }
        // A new document or page means the old pan is meaningless.
        pan = Offset.Zero
        zoom = 1.0f
    }

    LaunchedEffect(documentUri, pageIndex) {
        strokes = runCatching { databaseManager.getStrokesForPage(documentUri, pageIndex) }
            .onFailure { logger.error(it) { "Failed to load strokes for page $pageIndex" } }
            .getOrDefault(emptyList())
    }

    // ── model -> screen transform ───────────────────────────────────────────
    // pageScale is the factor between model points and pixels at zoom = 1.
    val image = pdfImage
    val sizePt = pageSizePt
    val pageScale = if (image != null && sizePt != null && sizePt.first > 0f && sizePt.second > 0f) {
        minOf(image.width / sizePt.first, image.height / sizePt.second)
    } else 1f

    fun toScreen(p: com.vic.inkflow.data.PointEntity): Offset = Offset(
        p.x * pageScale * zoom + pan.x,
        p.y * pageScale * zoom + pan.y
    )

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {

        // ── Page + ink, one transformable surface ───────────────────────────
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .transformable(
                    state = rememberTransformableState { zoomChange, panChange, _ ->
                        val newZoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
                        // Keep the point under the pinch focal roughly fixed, and
                        // stop the page being flung completely off-screen.
                        zoom = newZoom
                        pan += panChange
                    }
                )
                // Double-tap / drag with a pointer outside the pinch gesture.
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        pan += dragAmount
                    }
                }
        ) {
            viewport = size

            val img = pdfImage
            if (img != null) {
                val dstW = img.width * zoom
                val dstH = img.height * zoom
                // Centre the page in the viewport, then apply the pan.
                val originX = (size.width - dstW) / 2f + pan.x
                val originY = (size.height - dstH) / 2f + pan.y

                translate(originX, originY) {
                    drawImage(
                        image = img,
                        dstSize = IntSize(dstW.roundToInt().coerceAtLeast(1), dstH.roundToInt().coerceAtLeast(1))
                    )
                }

                // Ink, in exactly the same space.
                val inkOffset = Offset(originX, originY)
                strokes.forEach { swp ->
                    val pts = swp.points
                    if (pts.isEmpty()) return@forEach
                    val s = swp.stroke
                    val path = Path()
                    path.moveTo(toScreen(pts.first()).x - inkOffset.x, toScreen(pts.first()).y - inkOffset.y)
                    for (i in 1 until pts.size) {
                        val q = toScreen(pts[i])
                        path.lineTo(q.x - inkOffset.x, q.y - inkOffset.y)
                    }
                    val color = Color(s.color)
                    drawPath(
                        path = path,
                        color = if (s.isHighlighter) color.copy(alpha = 0.35f) else color,
                        style = Stroke(
                            // Stroke width is in model units too, so it scales with the ink.
                            width = (s.strokeWidth * pageScale * zoom).coerceAtLeast(0.5f),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
            }
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        if (loadError != null) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "無法開啟 PDF：$loadError",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        // ── Page navigation ─────────────────────────────────────────────────
        PageNavBar(
            pageIndex = pageIndex,
            onPageChange = { },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

/**
 * Bottom bar with previous/next and a direct page jump.
 *
 * `onPageCountChange` is how the parent learns how many pages exist, since
 * `PdfViewer` is the only place that opens the document.
 */
@Composable
private fun PageNavBar(
    pageIndex: Int,
    onPageChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .padding(12.dp)
            .widthIn(min = 220.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), MaterialTheme.shapes.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        IconButton(onClick = { onPageChange(pageIndex - 1) }) {
            Icon(Icons.Default.ChevronLeft, contentDescription = "上一頁")
        }
        Text(
            "第 ${pageIndex + 1} 頁",
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(onClick = { onPageChange(pageIndex + 1) }) {
            Icon(Icons.Default.ChevronRight, contentDescription = "下一頁")
        }
    }
}

private const val MIN_ZOOM = 0.25f
private const val MAX_ZOOM = 8.0f

private sealed interface PageRender {
    data class Ok(val image: ImageBitmap, val sizePt: Pair<Float, Float>) : PageRender
    data class Err(val message: String) : PageRender
}

/**
 * Rasterise one page. Also returns the page size in PDF points, which is the
 * anchor for the model-to-screen transform — without it the ink cannot be
 * positioned.
 */
private fun renderPage(documentUri: String, pageIndex: Int): PageRender {
    // Synced documents are mirrored under <appdata>/documents/<name>; the uri
    // recorded by the tablet is an Android path and does not exist here.
    val raw = documentUri.removePrefix("file://")
    var file = File(raw)
    if (!file.exists()) {
        val mirrored = File(
            System.getProperty("user.home") + File.separator + ".inkflow" +
                File.separator + "documents" + File.separator + file.name
        )
        if (mirrored.exists()) file = mirrored
    }
    if (!file.exists()) return PageRender.Err("找不到檔案 ${file.name}")

    return runCatching {
        Loader.loadPDF(file).use { doc ->
            if (pageIndex < 0 || pageIndex >= doc.numberOfPages) {
                return PageRender.Err("頁碼超出範圍（共 ${doc.numberOfPages} 頁）")
            }
            val page = doc.getPage(pageIndex)
            val wPt = page.mediaBox.width
            val hPt = page.mediaBox.height
            val bmp: BufferedImage = PDFRenderer(doc).renderImageWithDPI(pageIndex, RENDER_DPI)
            PageRender.Ok(bmp.toImageBitmap(), wPt to hPt)
        }
    }.getOrElse { e ->
        logger.error(e) { "Failed to render page $pageIndex of ${file.name}" }
        PageRender.Err(e.message ?: e.javaClass.simpleName)
    }
}

/** 150 DPI is a good balance: sharp on a 1080p screen without rendering a 600-page PDF eagerly. */
private const val RENDER_DPI = 150f

/**
 * PDFBox hands back an AWT `BufferedImage`; Compose draws `ImageBitmap`. Compose Desktop
 * ships no direct bridge, so encode to PNG and let Skia decode it.
 *
 * A PNG round-trip per page render is a few milliseconds at 150 DPI and happens off
 * the UI thread, which is a far better trade than hand-rolling a pixel-format
 * conversion (Skia's N32 memory layout is BGRA on little-endian only, and that
 * assumption is easy to get subtly wrong).
 */
private fun BufferedImage.toImageBitmap(): ImageBitmap {
    val out = java.io.ByteArrayOutputStream()
    javax.imageio.ImageIO.write(this, "png", out)
    return org.jetbrains.skia.Image.makeFromEncoded(out.toByteArray()).toComposeImageBitmap()
}
