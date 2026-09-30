package com.vic.inkflow.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.jetbrains.skia.Image as SkiaImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders page 0 of a local PDF as a small thumbnail, off the UI thread.
 * Silently shows nothing if the file cannot be read (card keeps its gradient).
 */
@Composable
fun PdfThumbnail(
    uri: String,
    modifier: Modifier = Modifier,
    dpi: Float = 48f
) {
    var bitmap by remember(uri) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            try {
                val f = File(uri.removePrefix("file://"))
                if (!f.exists()) return@withContext null
                Loader.loadPDF(f).use { doc ->
                    if (doc.numberOfPages == 0) return@withContext null
                    val awt = org.apache.pdfbox.rendering.PDFRenderer(doc)
                        .renderImageWithDPI(0, dpi)
                    val baos = ByteArrayOutputStream()
                    ImageIO.write(awt, "png", baos)
                     SkiaImage.makeFromEncoded(baos.toByteArray()).toComposeImageBitmap()
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = "Document thumbnail",
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    }
}
