package com.vic.inkflow.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.vic.inkflow.util.PdfManager
import com.vic.inkflow.util.PdfResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * First page of a local PDF as a small thumbnail, rendered off the UI thread.
 *
 * ## Cost
 *
 * A library grid calls this once per visible card, so the expensive part — PDFBox opening
 * and parsing the document — is memoised in [PdfManager] by file path, mtime, size and
 * DPI. Scroll steps therefore hit the cache instead of re-parsing every document, and
 * only newly visible rows pay the render. [dpi] is clamped to a thumbnail range well
 * below the viewer's, because a card never magnifies the page it shows.
 *
 * ## Failure
 *
 * Nothing here throws. Every failure ends as a placeholder card, which is why a row whose
 * file has gone missing since the list was built simply renders as "no cover image" and
 * leaves the card's own background and title visible. The negative result is cached too,
 * so such a row is not re-probed on every recomposition — but because the cache key
 * carries the file's mtime, the entry is bypassed automatically if the file later appears
 * (a sync finishing, a download completing), and the thumbnail appears without needing
 * the list to be rebuilt.
 *
 * Note what is deliberately *not* done: the file is not watched. A row that already holds
 * a bitmap keeps showing it even if the file is deleted afterwards, and only notices when
 * it leaves and re-enters composition — at which point it degrades to the placeholder
 * instead of blanking the card. The document itself reports the real error on open, which
 * is the point where a missing file actually matters.
 */
@Composable
fun PdfThumbnail(
    uri: String,
    modifier: Modifier = Modifier,
    dpi: Float = 48f
) {
    var bitmap by remember(uri, dpi) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(uri, dpi) { mutableStateOf(false) }

    LaunchedEffect(uri, dpi) {
        bitmap = null
        failed = false
        when (val result = withContext(Dispatchers.IO) { PdfManager.renderThumbnail(uri, dpi) }) {
            is PdfResult.Ok -> bitmap = result.value
            // A placeholder is a perfectly good outcome for a cover image; the document
            // itself will report the real error if the user opens it.
            is PdfResult.Err -> failed = true
        }
    }

    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = modifier.semantics {
                contentDescription = if (failed) "無法預覽縮圖" else "載入縮圖中"
            },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxSize(0.45f)
            )
        }
    }
}
