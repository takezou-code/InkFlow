package com.vic.inkflow.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The text metrics are a two-app contract, not a rendering detail.
 *
 * If the desktop measures a string at a different width than the tablet, the
 * selection box is the wrong size and the failure is invisible: the label is
 * plainly on screen, and clicking it does nothing. These cases pin the exact
 * coefficients the tablet uses so that divergence fails here instead of on a
 * user's document.
 */
class TextMetricsTest {

    /** Reference implementation copied from the tablet's `SelectionGeometry`. */
    private fun tabletWidth(text: String, fontSize: Float): Float {
        var ems = 0f
        text.forEach { c ->
            ems += if (c.code in 0x2E80..0x9FFF ||
                c.code in 0xAC00..0xD7AF ||
                c.code in 0xF900..0xFAFF ||
                c.code in 0xFF00..0xFF60 ||
                c.code in 0xFFE0..0xFFE6 ||
                c.code in 0x3000..0x303E
            ) 1.0f else 0.65f
        }
        return ems * fontSize
    }

    @Test
    fun `desktop width matches the tablet for a range of strings`() {
        val samples = listOf(
            "", "a", "hello", "abc def",
            "中", "中文", "繁體中文測試",
            "混合mixed文字", "1234567890",
            "ABC中文123", "，。！？", "ひらがな", "カタカナ"
        )
        samples.forEach { s ->
            assertEquals(
                tabletWidth(s, 16f), TextMetrics.measureWidth(s, 16f), 0.0001f,
                "width disagrees for \"$s\""
            )
        }
    }

    @Test
    fun `full-width glyphs advance a full em and half-width 0_65`() {
        assertEquals(16f, TextMetrics.measureWidth("中", 16f), 0.0001f)
        assertEquals(32f, TextMetrics.measureWidth("中文", 16f), 0.0001f)
        assertEquals(0.65f * 16f, TextMetrics.measureWidth("a", 16f), 0.0001f)
    }

    @Test
    fun `width scales linearly with font size`() {
        assertEquals(
            TextMetrics.measureWidth("測試abc", 16f) * 2f,
            TextMetrics.measureWidth("測試abc", 32f),
            0.0001f
        )
    }

    @Test
    fun `an injected measurer overrides the coefficient fallback`() {
        // The app passes a real measurement; the coefficients are only for
        // headless tests. This proves the hook is actually wired.
        val viaHook = TextMetrics.measureWidth("abc", 10f) { _, _ -> 42f }
        assertEquals(42f, viaHook, 0.0001f)
    }

    @Test
    fun `bounds place the top edge one font size above the baseline`() {
        // modelY is a BASELINE. Reading it as a top edge is the bug that makes
        // notes unselectable while still visibly present.
        val b = TextMetrics.bounds(10f, 100f, "note", 16f)
        assertNotNull(b)
        assertEquals(10f, b[0], 0.0001f, "left")
        assertEquals(84f, b[1], 0.0001f, "top = baseline - fontSize")
        assertEquals(100f, b[3], 0.0001f, "bottom = baseline for a single line")
        assertEquals(10f + 0.65f * 4 * 16f, b[2], 0.0001f, "right = left + width")
    }

    @Test
    fun `multi-line bounds grow downward by 1_2 em per extra line`() {
        val b = TextMetrics.bounds(0f, 200f, "one\ntwo\nthree", 20f)
        assertNotNull(b)
        assertEquals(180f, b[1], 0.0001f, "top is still relative to the FIRST baseline")
        // 2 extra lines * 20pt * 1.2 = 48
        assertEquals(200f + 48f, b[3], 0.0001f)
    }

    @Test
    fun `bounds width is the widest line not the first`() {
        val b = TextMetrics.bounds(0f, 100f, "ab\n中文", 16f)
        assertNotNull(b)
        // "中文" = 2 em = 32pt; "ab" = 1.3 em = 20.8pt
        assertEquals(32f, b[2], 0.0001f)
    }

    @Test
    fun `blank text has no box`() {
        // An empty annotation is invisible and ungrabbable; returning a box would
        // let the user select something that renders nothing.
        assertNull(TextMetrics.bounds(0f, 0f, "", 16f))
        assertNull(TextMetrics.bounds(0f, 0f, "   ", 16f))
        assertNull(TextMetrics.bounds(0f, 0f, "\n\n", 16f))
    }

    @Test
    fun `bounds are ordered and non-degenerate for real content`() {
        val samples = listOf("x", "中文", "a\nb", "InkFlow 桌面版", "😀")
        samples.forEach { s ->
            val b = TextMetrics.bounds(5f, 50f, s, 12f)
            assertNotNull(b, "no box for \"$s\"")
            assertTrue(b[0] < b[2], "width must be positive for \"$s\"")
            assertTrue(b[1] < b[3], "height must be positive for \"$s\"")
            assertEquals(5f, b[0], 0.0001f, "left is the anchor")
        }
    }
}