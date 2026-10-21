package com.vic.inkflow.ui

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
