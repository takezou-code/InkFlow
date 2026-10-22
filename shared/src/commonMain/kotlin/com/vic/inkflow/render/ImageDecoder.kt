package com.vic.inkflow.render

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decoding an image file into something the canvas can draw.
 *
 * The editor draws images as annotations; every platform has a different decoder
 * (Android: `BitmapFactory`; the desktop: `ImageIO`; iOS: `UIImage`), and each
 * one has its own way of eating memory: a 4000×3000 photo decoded at full size
 * is 48 MB, which is how "the app died when I pasted a photo" happens.
 *
 * So the shared code asks for an image and states the **bound** it may not exceed.
 * The implementation is responsible for honouring it (downscale during decode,
 * not after).
 */
interface ImageDecoder {

    /**
     * Decodes the image at [uri], with its longest side no larger than
     * [maxSidePx].
     *
     * @return null when the image cannot be decoded (a broken file, a format the
     *   platform does not know).
     */
    suspend fun decode(uri: String, maxSidePx: Int = DEFAULT_MAX_SIDE_PX): ImageBitmap?

    companion object {
        /**
         * 2048 px on the long side: enough for a full A4 page at 300 dpi, and
         * 16 MB of RGBA at most.
         */
        const val DEFAULT_MAX_SIDE_PX = 2048
    }
}
