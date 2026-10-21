package com.vic.inkflow.ui

import androidx.compose.ui.geometry.Rect

/**
 * Text measurement — the width of a line, in model units.
 *
 * ## Why it is its own file
 *
 * The width of a text annotation decides three things at once: where the label is
 * drawn, how wide its hit box is, and where the text wraps inside its box. If
 * those three use different measurements, the label is visible but cannot be
 * grabbed, or the box breaks in a different place on the two devices.
 *
 * So the measurement is one function, and it lives in the shared module where
 * every platform uses the same one.
 *
 * ## Why not `StaticLayout` / `Paint.measureText`
 *
 * Those are the *exact* widths, and they need the Android framework — a JVM unit
 * test cannot run them, and the desktop would have to re-implement them. A
 * coefficient model (full-width glyphs advance 1.0 em, everything else 0.65 em) is
 * slightly less exact but is **portable and testable**, and it is the same
 * approximation on every platform, which is the property that actually matters
 * here: the two devices must agree with *each other*.
 *
 * ## The CJK ranges are written as code points
 *
 * Not as literals: the toolchain has swallowed rare character bytes before, and a
 * range written as a literal is a range that can be silently mangled.
 */
fun isWideChar(c: Char): Boolean {
    val code = c.code
    return code in 0x2E80..0xA4CF ||
        code in 0xAC00..0xD7AF ||
        code in 0xF900..0xFAFF ||
        code in 0xFE30..0xFE4F ||
        code in 0xFF00..0xFFEF
}

/** The width of one line, in model units: full-width 1.0 em, half-width 0.65 em. */
fun measureTextWidth(text: String, fontSize: Float): Float {
    var w = 0f
    for (c in text) w += if (isWideChar(c)) fontSize else fontSize * 0.65f
    return w
}

/**
 * The estimated box of a text annotation, in model space.
 *
 * **The one number both devices must agree on**: the box is what the label is
 * drawn into, what the hit test uses, and what the selection outline shows. If
 * the two devices compute it differently, a note is visible on both and grabbable
 * on one.
 *
 * This is the **tablet's** rule (the tablet is the writer, so its rule is the
 * contract): the box ends at the baseline and grows **upwards**, so
 * `top = modelY − height`, `bottom = modelY`.
 *
 * ## Known divergence — the desktop does not use this yet
 *
 * The desktop's `TextMetrics.bounds` grows the other way for multi-line notes
 * (`top = modelY − fontSize`, `bottom = modelY + extra × line spacing`), so a
 * two-line note has two different hit boxes on the two devices.
 *
 * It is left that way on purpose for now, because the **third** implementation
 * has to be read first: the tablet's own canvas hit rect
 * (`textAnnotationHitRect`) grows **downwards**, like the desktop's, and the
 * tablet's canvas *draws* the first line's baseline at `modelY` with the
 * following lines below it. So the tablet's lasso/erase box (this function) and
 * the tablet's own tap box disagree by one line height for multi-line notes —
 * and the desktop's convention is the one that matches the drawing.
 *
 * Fixing that means changing which box the tablet lasso/erase uses, which is a
 * **behaviour change on the tablet** and needs a device to verify. Until then
 * both implementations stay where they are, and the divergence is written down
 * here rather than papered over.
 *
 * @param boxWidth the text box width in model units, or null for the old
 *   "hug the content" notes that break only on `\n`.
 * @param measurer an optional real measurement (the desktop's PDF font metrics).
 *   When absent the portable coefficient model is used — the same on every
 *   platform, which is the property that matters.
 *
 * @return null for blank text: a blank annotation has no box, so it cannot be
 *   hit-tested and should not be drawn.
 */
fun textEstimatedBounds(
    modelX: Float,
    modelY: Float,
    text: String,
    fontSize: Float,
    boxWidth: Float?,
    measurer: ((String, Float) -> Float)? = null
): Rect? {
    if (text.isBlank()) return null
    val boxW = boxWidth?.takeIf { it > 0f }
    val lines = if (boxW != null) wrapTextLines(text, fontSize, boxW, measurer) else text.split("\n")
    val measure: (String, Float) -> Float = measurer ?: { t, s -> measureTextWidth(t, s) }
    val w = boxW ?: (lines.maxOfOrNull { measure(it, fontSize) } ?: 0f)
    // Height: the first line's font size plus one line spacing per extra line.
    val h = fontSize + (lines.size - 1).coerceAtLeast(0) * fontSize * 1.2f
    return Rect(modelX, modelY - h, modelX + w, modelY)
}
