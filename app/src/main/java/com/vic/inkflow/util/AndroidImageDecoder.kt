package com.vic.inkflow.util

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.vic.inkflow.render.ImageDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Android image decoder — `BitmapFactory`, with the size bound applied
 * **during** the decode.
 *
 * ## Why the bound matters
 *
 * A 4000×3000 photo decoded at full size is 48 MB of RGBA. Two of those in a
 * document, and the app is gone. `inSampleSize` halves the dimensions before the
 * pixels are allocated, so a 4000×3000 photo at the 2048 px bound costs a
 * quarter of that.
 *
 * The bound is in the **contract** (not in the implementation) because the
 * decision "how big may an image be" belongs to the shared code — the same
 * decision the canvas, the exporter and the AI import all have to agree on.
 */
internal class AndroidImageDecoder(
    private val context: Context
) : ImageDecoder {

    override suspend fun decode(uri: String, maxSidePx: Int): ImageBitmap? =
        withContext(Dispatchers.IO) {
            val parsed = Uri.parse(uri)
            val resolver = context.contentResolver
            val (width, height) = resolver.openInputStream(parsed)?.use { stream ->
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, opts)
                opts.outWidth to opts.outHeight
            } ?: return@withContext null

            if (width <= 0 || height <= 0) {
                // A format BitmapFactory does not know (or a broken file): one
                // more attempt without the bounds, then give up.
                return@withContext resolver.openInputStream(parsed)?.use {
                    BitmapFactory.decodeStream(it)?.asImageBitmap()
                }
            }

            var sample = 1
            while (width / sample > maxSidePx || height / sample > maxSidePx) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            resolver.openInputStream(parsed)?.use {
                BitmapFactory.decodeStream(it, null, opts)?.asImageBitmap()
            }
        }
}
