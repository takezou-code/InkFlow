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
    fun localYRoundTrip() {        val spec = DocLayout.DocSpec(
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

    @Test
    fun splitSinglePagePassthrough() {
        val ys = listOf(10f, 100f, 500f)
        val segs = DocLayout.splitByPage(ys, { it }, 800f, 18f, 1, 3) { _, ly -> ly }
        assertEquals(1, segs.size)
        assertEquals(1, segs[0].first)
        assertEquals(ys, segs[0].second)
    }

    @Test
    fun splitCrossesDownOnePage() {
        // canvasH=800, gap=18 → stride=818；起點第 1 頁 y=700，一路寫到 y=900（跨過 818 中線）。
        val ys = listOf(700f, 750f, 800f, 850f, 900f)
        val segs = DocLayout.splitByPage(ys, { it }, 800f, 18f, 1, 3) { _, ly -> ly }
        assertEquals(2, segs.size)
        assertEquals(1, segs[0].first)
        assertEquals(2, segs[1].first)
        // 下頁段 localY = y - stride
        assertEquals(850f - 818f, segs[1].second[0], 0.01f)
        // 保序：合起來與原序一致
        assertEquals(ys.size, segs[0].second.size + segs[1].second.size)
    }

    @Test
    fun splitClampsAtDocumentEdges() {
        // 首頁往上溢出：併入第 0 頁並鉗到紙界
        val up = DocLayout.splitByPage(listOf(-100f, -10f), { it }, 800f, 18f, 0, 3) { _, ly -> ly }
        assertEquals(1, up.size)
        assertEquals(0, up[0].first)
        assertTrue(up[0].second.all { it in 0f..800f })
        // 末頁往下溢出：併入最後一頁並鉗到紙界
        val down = DocLayout.splitByPage(listOf(2500f), { it }, 800f, 18f, 2, 3) { _, ly -> ly }
        assertEquals(2, down[0].first)
        assertEquals(800f, down[0].second[0], 0.01f)
    }

    @Test
    fun splitEmptyOrDegenerate() {
        assertEquals(1 to emptyList<Float>(), DocLayout.splitByPage(emptyList<Float>(), { it }, 800f, 18f, 1, 3) { _, ly -> ly }.single())
        val zero = DocLayout.splitByPage(listOf(5f), { it }, 0f, 18f, 1, 3) { _, ly -> ly }
        assertEquals(1, zero.single().first)
    }
}
