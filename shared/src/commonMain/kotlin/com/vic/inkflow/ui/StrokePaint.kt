package com.vic.inkflow.ui

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import com.vic.inkflow.data.Point
import com.vic.inkflow.util.EnvelopeUtils
import com.vic.inkflow.util.StrokePoint

/**
 * How a stroke is painted — **one rule for both apps and for the PDF export**.
 *
 * ## The highlighter
 *
 * A highlighter is not "a pen with low opacity". It is a pen that *multiplies*:
 * the paper stays bright, the ink under it darkens. That is why a highlighter over
 * text still shows the text.
 *
 * The two apps had drifted into three different answers:
 *
 *  - the tablet's screen: alpha 0.4 with `BlendMode.Multiply`
 *  - the desktop's screen: alpha **0.35**, and **no Multiply at all** (a plain
 *    translucent overlay — so the ink under a desktop highlighter does *not*
 *    darken, it gets *covered*)
 *  - the desktop's own PDF export: alpha 0.4
 *
 * So the desktop showed a highlighter at 35% that washed the ink out, and then
 * exported the same strokes at 40% — the exported PDF was darker than the screen
 * the user was looking at.
 *
 * [HIGHLIGHTER_ALPHA] and [strokePaint] are now the only place that decision
 * lives.
 */

/** The highlighter's opacity. The screen and the PDF export must agree. */
const val HIGHLIGHTER_ALPHA = 0.4f

/**
 * The colour a stroke is drawn with.
 *
 * Highlighter → the shared [HIGHLIGHTER_ALPHA]; pen → the colour as it is.
 */
fun strokeColor(color: Color, isHighlighter: Boolean): Color =
    if (isHighlighter) color.copy(alpha = HIGHLIGHTER_ALPHA) else color

/**
 * The blend mode a stroke is drawn with.
 *
 * A highlighter **multiplies**: the paper stays bright and the ink under it
 * darkens — that is why a highlighter over text still shows the text. A plain
 * translucent overlay instead *covers* what is underneath.
 */
fun strokeBlendMode(isHighlighter: Boolean): BlendMode =
    if (isHighlighter) BlendMode.Multiply else BlendMode.SrcOver

/**
 * The paint for one stroke — for the `Canvas` API (the tablet caches the path, so
 * it draws through the raw canvas rather than `DrawScope`).
 */
fun strokePaint(color: Color, isHighlighter: Boolean): Paint = Paint().apply {
    this.color = strokeColor(color, isHighlighter)
    style = PaintingStyle.Fill
    blendMode = strokeBlendMode(isHighlighter)
}

/**
 * The outline of a freehand stroke: a variable-width polygon around its samples.
 *
 * A stroke is not a polyline — it is the *envelope* of the samples, so a fast
 * stroke is thin and a slow one is thick. Both apps already used
 * [EnvelopeUtils.generateEnvelopePath]; this is the shared spelling of it.
 */
fun List<Point>.toComposePath(): Path =
    EnvelopeUtils.generateEnvelopePath(map { StrokePoint(it.x, it.y, it.width) })
