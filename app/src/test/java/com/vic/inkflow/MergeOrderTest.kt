package com.vic.inkflow

import com.vic.inkflow.ui.PdfViewModel
import com.vic.inkflow.util.PdfManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeOrderTest {

    // ── PdfManager.resolveInsertionIndex ─────────────────────────────
    // 回歸測試：Int.MAX_VALUE（接尾慣例）不可溢位成 MIN_VALUE 而被箍到 0。
    // 元兇：(afterIndex + 1).coerceIn(0, N) 在 MAX_VALUE 時溢位 → 每次插到最前面 → 合併反轉。

    @Test
    fun `MAX_VALUE appends to tail`() {
        assertEquals(5, PdfManager.resolveInsertionIndex(Int.MAX_VALUE, 5))
        assertEquals(0, PdfManager.resolveInsertionIndex(Int.MAX_VALUE, 0))
    }

    @Test
    fun `minus one prepends at head`() {
        assertEquals(0, PdfManager.resolveInsertionIndex(-1, 5))
    }

    @Test
    fun `middle index inserts after`() {
        assertEquals(3, PdfManager.resolveInsertionIndex(2, 5))
    }

    @Test
    fun `last index appends to tail`() {
        assertEquals(5, PdfManager.resolveInsertionIndex(4, 5))
    }

    @Test
    fun `beyond tail clamps to tail`() {
        assertEquals(5, PdfManager.resolveInsertionIndex(99, 5))
    }

    @Test
    fun `merge simulation A B C keeps order`() {
        // 模擬 mergePdfs：base=A，逐份接尾。舊邏輯每次得 0 → [C,B,A]；新邏輯應為 [A,B,C]。
        val pages = mutableListOf("A")
        for (next in listOf("B", "C")) {
            val idx = PdfManager.resolveInsertionIndex(Int.MAX_VALUE, pages.size)
            pages.add(idx, next)
        }
        assertEquals(listOf("A", "B", "C"), pages)
    }

    // ── PdfViewModel.multiInsertStartCursor ────────────────────────────
    // 回歸測試：insertMultiplePdfs 的 cursor 禁拿 afterIndex 直寫＋禁裸 +1，
    // MAX_VALUE 時必須是 currentCount-1（接尾），否則 cursor+=n 溢位負數。

    @Test
    fun `multiInsert cursor normal index`() {
        assertEquals(2, PdfViewModel.multiInsertStartCursor(afterIndex = 2, currentCount = 10))
    }

    @Test
    fun `multiInsert cursor MAX_VALUE appends tail`() {
        assertEquals(9, PdfViewModel.multiInsertStartCursor(Int.MAX_VALUE, 10))
    }

    @Test
    fun `multiInsert cursor never overflows`() {
        // 逐份 cursor+=n 全程不可為負（含 MAX 起始）。
        listOf(2, 9, Int.MAX_VALUE).forEach { after ->
            var cursor = PdfViewModel.multiInsertStartCursor(after, 10)
            repeat(5) {
                assertTrue("cursor overflowed: $cursor", cursor >= -1)
                cursor += 3
                assertTrue("cursor overflowed after +=: $cursor", cursor >= 0)
            }
        }
    }
}
