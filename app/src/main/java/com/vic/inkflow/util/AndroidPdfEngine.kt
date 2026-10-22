package com.vic.inkflow.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.vic.inkflow.render.PageBox
import com.vic.inkflow.render.PdfEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Android PDF engine — `android.graphics.pdf.PdfRenderer`, the framework's own.
 *
 * ## Why the contract asks for pixels and not for a page
 *
 * The editor, the exporter and the sync preview all want "the page as an image",
 * and each of the three engines rasterises at a different DPI with different
 * anti-aliasing. So the shared code asks for a page **in pixels** and gets
 * whatever the platform produces — it never sees a PDF object, and it never has
 * to know that Android has `PdfRenderer`, Windows has PDFBox and iOS has PDFKit
 * (which is the best of the three: it is what Preview uses).
 *
 * ## The page's origin
 *
 * A page whose CropBox does not start at (0, 0) has that origin subtracted from
 * every coordinate, or the ink lands next to the page instead of on it. That is
 * why [pageSize] reports the origin and not just the size.
 */
internal class AndroidPdfEngine(
    private val context: Context
) : PdfEngine {

    override suspend fun pageCount(uri: String): Int = withContext(Dispatchers.IO) {
        val pfd = PdfManager.openPdfFileDescriptor(context, Uri.parse(uri)) ?: return@withContext 0
        var renderer: PdfRenderer? = null
        try {
            renderer = PdfRenderer(pfd)
            renderer.pageCount
        } catch (e: Exception) {
            0
        } finally {
            renderer?.close()
            PdfManager.closePdfRenderer(renderer)
        }
    }

    override suspend fun pageSize(uri: String, pageIndex: Int): PageBox? =
        withContext(Dispatchers.IO) {
            val pfd = PdfManager.openPdfFileDescriptor(context, Uri.parse(uri)) ?: return@withContext null
            var renderer: PdfRenderer? = null
            var page: PdfRenderer.Page? = null
            try {
                renderer = PdfRenderer(pfd)
                if (pageIndex !in 0 until renderer.pageCount) return@withContext null
                page = renderer.openPage(pageIndex)
                PageBox(widthPt = page.width.toFloat(), heightPt = page.height.toFloat())
            } catch (e: Exception) {
                null
            } finally {
                page?.close()
                renderer?.close()
                PdfManager.closePdfRenderer(renderer)
            }
        }

    override suspend fun renderPage(uri: String, pageIndex: Int, widthPx: Int): ImageBitmap? =
        withContext(Dispatchers.IO) {
            if (widthPx <= 0) return@withContext null
            val pfd = PdfManager.openPdfFileDescriptor(context, Uri.parse(uri)) ?: return@withContext null
            var renderer: PdfRenderer? = null
            var page: PdfRenderer.Page? = null
            try {
                renderer = PdfRenderer(pfd)
                if (pageIndex !in 0 until renderer.pageCount) return@withContext null
                page = renderer.openPage(pageIndex)
                // The height follows the page's aspect ratio: the caller asked for
                // a width, not a size.
                val scale = widthPx.toFloat() / page.width.toFloat().coerceAtLeast(1f)
                val heightPx = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap.asImageBitmap()
            } catch (e: Exception) {
                null
            } finally {
                page?.close()
                renderer?.close()
                PdfManager.closePdfRenderer(renderer)
            }
        }
}
