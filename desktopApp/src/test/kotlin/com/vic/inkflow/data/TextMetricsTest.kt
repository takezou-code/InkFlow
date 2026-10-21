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

    /**
     * The reference is now the **shared rule** (`:shared`'s `com.vic.inkflow.ui.measureTextWidth`),
     * not a third copy of it.
     *
     * This file used to carry its own transcription of the tablet's rule — and the
     * transcription had drifted: the desktop's ranges and the tablet's ranges
     * disagreed on `0x3000..0x303E`, `0xFFE0..0xFFE6`, `0xFE30..0xFE4F` and
     * everything above `0x9FFF`. The samples below did not happen to contain any
     * of those characters, so the divergence was invisible: a note written with a
     * full-width space was measured wider on one device than on the other, and
     * the hit box is drawn from that measurement.
     */
    private fun sharedWidth(text: String, fontSize: Float): Float =
        com.vic.inkflow.ui.measureTextWidth(text, fontSize)

    @Test
    fun `desktop width matches the shared rule for a range of strings`() {
        val samples = listOf(
            "", "a", "hello", "abc def",
            "中", "中文", "繁體中文測試",
            "混合mixed文字", "1234567890",
            "ABC中文123", "，。！？", "ひらがな", "カタカナ"
        )
        samples.forEach { s ->
            assertEquals(
                sharedWidth(s, 16f), TextMetrics.measureWidth(s, 16f), 0.0001f,
                "width disagrees for \"$s\""
            )
        }
    }

    @Test
    fun `the characters the two copies used to disagree on are now measured the same way`() {
        // 　(full-width space) ｱ(half-width katakana) Ａ(full-width A)
        // ＋(full-width plus) and a Yi syllable. Each of these fell in exactly one
        // of the two old range sets, so one device measured it full-width and the
        // other half-width.
        val samples = listOf("　", "ｱ", "Ａ", "＋", "ꀀ")
        samples.forEach { s ->
            assertEquals(
                sharedWidth(s, 16f), TextMetrics.measureWidth(s, 16f), 0.0001f,
                "width disagrees for ${s.map { it.code.toString(16) }}"
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

    // ---- Text boxes (tablet DB v30 boxWidth / alignment) ----

    @Test
    fun `a box is that wide and wraps like the tablet`() {
        // 10 full-width chars @10pt in a 50pt box → 5 per line, two lines.
        assertEquals(
            listOf("一二三四五", "六七八九十"),
            TextMetrics.wrapLines("一二三四五六七八九十", 10f, 50f)
        )
        val b = TextMetrics.bounds(0f, 100f, "一二三四五六七八九十", 10f, boxWidth = 50f)
        assertNotNull(b)
        assertEquals(0f, b[0], 0.0001f, "left is the anchor")
        assertEquals(50f, b[2], 0.0001f, "width is the box, not the longest line")
        assertEquals(100f - 10f, b[1], 0.0001f, "top = baseline - fontSize")
        assertEquals(100f + 1 * 10f * 1.2f, b[3], 0.0001f, "two lines")
    }

    @Test
    fun `latin words are not split mid-word`() {
        assertEquals(listOf("hello", "world"), TextMetrics.wrapLines("hello world", 10f, 45f))
    }

    @Test
    fun `alignment offsets sit inside the box`() {
        assertEquals(0f, TextMetrics.alignOffset(20f, 100f, 0), 0.0001f)
        assertEquals(40f, TextMetrics.alignOffset(20f, 100f, 1), 0.0001f)
        assertEquals(80f, TextMetrics.alignOffset(20f, 100f, 2), 0.0001f)
        // A line wider than its box must not push text left of the box.
        assertEquals(0f, TextMetrics.alignOffset(120f, 100f, 2), 0.0001f)
    }

    @Test
    fun `legacy note without a box keeps hugging its content`() {
        val b = TextMetrics.bounds(0f, 100f, "ab\n中文", 16f, boxWidth = null)
        assertNotNull(b)
        assertEquals(32f, b[2], 0.0001f, "widest line = 中文 = 2em")
    }

    @Test
    fun `boundsFor carries the annotation's box width`() {
        val note = TextAnnotationEntity(
            documentUri = "u", pageIndex = 0, text = "hi",
            modelX = 5f, modelY = 50f, fontSize = 16f, boxWidth = 120f
        )
        val b = TextMetrics.boundsFor(note)
        assertNotNull(b)
        assertEquals(5f, b[0], 0.0001f)
        assertEquals(125f, b[2], 0.0001f)
    }
}