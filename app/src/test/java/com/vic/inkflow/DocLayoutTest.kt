package com.vic.inkflow

import com.vic.inkflow.util.DocLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocLayoutTest {

    private fun uniform3() = DocLayout.DocSpec.uniform(pageCount = 3, width = 595f, height = 842f)

    @Test
    fun emptyDocIsSafe() {
        val spec = DocLayout.DocSpec(emptyList(), emptyList())
        assertEquals(0, spec.pageCount)
        assertEquals(0f, spec.docHeight, 0.001f)
        assertEquals(-1, spec.pageIndexAt(100f))
        assertEquals(100f, spec.localY(100f), 0.001f)
    }

    @Test
    fun uniformMatchesS1Formula() {
        // 等大文件必須退化為 S1 公式：tops[i] = i × stride
        val spec = uniform3()
        assertEquals(3 * 842f, spec.docHeight, 0.001f)
        assertEquals(0f, spec.pageTop(0), 0.001f)
        assertEquals(842f, spec.pageTop(1), 0.001f)
        assertEquals(1684f, spec.pageTop(2), 0.001f)
        assertEquals(2, spec.pageIndexAt(1684f))
        assertEquals(0f, spec.localY(1684f), 0.001f)
        assertEquals(841f, spec.localY(841f), 0.001f)
    }

    @Test
    fun anchorOwnershipAtBoundaries() {
        val spec = uniform3()
        // 頁界（含）歸下頁：錨點語義「頂邊 ≤ docY 的最後一頁」
        assertEquals(1, spec.pageIndexAt(842f))
        assertEquals(0, spec.pageIndexAt(841.99f))
        // 越界夾取
        assertEquals(0, spec.pageIndexAt(-50f))
        assertEquals(2, spec.pageIndexAt(99999f))
    }

    @Test
    fun mixedSizesUsePrefixSums() {
        val spec = DocLayout.DocSpec(
            pageWidths = listOf(595f, 842f),
            pageHeights = listOf(842f, 595f)
        )
        assertEquals(842f + 595f, spec.docHeight, 0.001f)
        assertEquals(0f, spec.pageTop(0), 0.001f)
        assertEquals(842f, spec.pageTop(1), 0.001f)
        assertEquals(1, spec.pageIndexAt(900f))
        assertEquals(58f, spec.localY(900f), 0.001f)
        assertEquals(842f, spec.widthOf(1), 0.001f)
    }

    @Test
    fun gapMidlineCut() {
        assertTrue(DocLayout.gapOwnerIsUpper(0f, 18f))
        assertTrue(DocLayout.gapOwnerIsUpper(8.99f, 18f))
        assertTrue(!DocLayout.gapOwnerIsUpper(9f, 18f))
        assertTrue(!DocLayout.gapOwnerIsUpper(17f, 18f))
        assertTrue(DocLayout.gapOwnerIsUpper(5f, 0f))
    }

    @Test
    fun degenerateSizesClamped() {
        val spec = DocLayout.DocSpec(listOf(0f, -3f), listOf(0f, 842f))
        assertEquals(1f + 842f, spec.docHeight, 0.001f)
        assertEquals(1, spec.pageIndexAt(50f))
    }

    @Test
    fun localYRoundTrip() {
        val spec = DocLayout.DocSpec(
            pageWidths = listOf(595f, 612f, 595f),
            pageHeights = listOf(842f, 792f, 842f)
        )
        val samples = listOf(0f, 841f, 842f, 1000f, 1633f, 1634f, 2000f, 2475f)
        for (y in samples) {
            val idx = spec.pageIndexAt(y)
            assertTrue("idx in range for y=$y", idx in 0 until spec.pageCount)
            assertEquals("roundtrip y=$y", y, spec.pageTop(idx) + spec.localY(y), 0.01f)
        }
    }
}
