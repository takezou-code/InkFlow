package com.vic.inkflow.util

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.vic.inkflow.render.ImageDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO

/**
 * The desktop's image decoder — ImageIO, bounded to [maxSidePx] on the long side.
 *
 * ## Why it decodes first and scales afterwards (and the phone does not)
 *
 * The Android implementation subsamples **during** the decode, because a phone
 * that allocates a 48 MB bitmap for a 2048 px bound may simply be killed. On the
 * desktop the same picture is a transient allocation on a machine with plenty of
 * RAM, and the desktop's decoder is not on any hot path (an image annotation is
 * drawn once per page).
 *
 * The **result** is the same in both: an image whose long side is at most the
 * bound. That is what the contract promises, and that is what the two platforms
 * deliver.
 *
 * ## The PNG round trip
 *
 * AWT's `BufferedImage` and Compose's `ImageBitmap` do not share a memory
 * layout, and Compose Desktop ships no direct bridge. The desktop's `PdfManager`
 * already solved this by encoding to PNG and letting Skia decode it, with a
 * comment explaining why the "faster" direct copy is not worth it (the channel
 * order assumption fails as garbled colours rather than as an exception). This
 * decoder does the same thing rather than inventing a second conversion.
 */
internal class DesktopImageDecoder : ImageDecoder {

    override suspend fun decode(uri: String, maxSidePx: Int): ImageBitmap? =
        withContext(Dispatchers.IO) {
            val file = File(uri.removePrefix("file://").removePrefix("/"))
            if (!file.isFile) return@withContext null

            val decoded = ImageIO.read(file) ?: return@withContext null
            val longSide = maxOf(decoded.width, decoded.height)
            val bounded = if (maxSidePx > 0 && longSide > maxSidePx) {
                val factor = maxSidePx.toFloat() / longSide
                val w = (decoded.width * factor).toInt().coerceAtLeast(1)
                val h = (decoded.height * factor).toInt().coerceAtLeast(1)
                val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                val g = scaled.createGraphics()
                try {
                    g.drawImage(decoded, 0, 0, w, h, null)
                } finally {
                    g.dispose()
                }
                scaled
            } else {
                decoded
            }
            bounded.toImageBitmap()
        }

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
}
