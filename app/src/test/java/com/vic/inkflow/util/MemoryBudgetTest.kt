package com.vic.inkflow.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 記憶體總帳的守門測試（2026-11-15 M8）。
 *
 * 這支釘住三件事，因為它們一錯就是「體感退化」而不是「編譯失敗」：
 * 1. **與舊行為對齊**：PDF 高清層仍是 heap/5、縮圖層仍是 heap/20
 *    （PdfViewModel 的預取頁靠這個數字才不會被 LruCache 靜默擠掉）。
 * 2. **總量有天花板**：四層加起來不能超過 heap 的 40%，其餘留給筆跡烘焙與 Java heap。
 * 3. **壓力階梯單調**：等級越高（越缺記憶體）倍率越小，而且 UI_HIDDEN 以上
 *    不算「永久水位」（否則使用者切出去再回來，快取永遠長不回去）。
 */
class MemoryBudgetTest {

    private val mb = 1024L * 1024

    @Test
    fun `page layer keeps the old heap-over-5 budget`() {
        assertEquals(
            512L * mb / 5,
            MemoryBudget.quotaBytesFor(MemoryBudget.Layer.PDF_PAGE, 512L * mb, 1f)
        )
        assertEquals(
            512L * mb / 20,
            MemoryBudget.quotaBytesFor(MemoryBudget.Layer.PDF_THUMBNAIL, 512L * mb, 1f)
        )
    }

    @Test
    fun `small heaps fall back to per-layer floors`() {
        assertEquals(
            64L * mb,
            MemoryBudget.quotaBytesFor(MemoryBudget.Layer.PDF_PAGE, 16L * mb, 1f)
        )
        assertEquals(
            16L * mb,
            MemoryBudget.quotaBytesFor(MemoryBudget.Layer.PDF_THUMBNAIL, 16L * mb, 1f)
        )
    }

    @Test
    fun `all layers together stay under forty percent of the heap`() {
        val heap = 512L * mb
        val total = MemoryBudget.totalBytesFor(heap)
        assertTrue("總預算必須低於 heap 的 40%", total <= heap * 40 / 100)
        assertTrue("總預算不該低到讓畫面常駐頁被砍", total >= heap * 30 / 100)
    }

    @Test
    fun `image annotations give memory back to the ink layer`() {
        val heap = 512L * mb
        val image = MemoryBudget.quotaBytesFor(MemoryBudget.Layer.IMAGE_ANNOTATION, heap, 1f)
        assertTrue("圖片註解層必須小於舊的 heap/8", image < heap / 8)
        assertTrue("但仍要能容納上百張公式圖", image >= 32L * mb)
    }

    @Test
    fun `trim levels shrink quotas monotonically`() {
        val heap = 512L * mb
        val levels = intArrayOf(0, 5, 10, 15, 20, 40, 60, 80)
        val quotas = levels.map {
            MemoryBudget.quotaBytesFor(MemoryBudget.Layer.PDF_PAGE, heap, MemoryBudget.factorForTrimLevel(it))
        }
        for (i in 1 until quotas.size) {
            assertTrue("等級 ${levels[i]} 的配額必須不大於前一級", quotas[i] <= quotas[i - 1])
        }
        assertEquals(1f, MemoryBudget.factorForTrimLevel(0), 0f)
        assertEquals(0.60f, MemoryBudget.factorForTrimLevel(10), 0f)
        assertEquals(0.05f, MemoryBudget.factorForTrimLevel(80), 0f)
    }

    @Test
    fun `only running levels are persistent watermarks`() {
        // RUNNING_MODERATE / LOW / CRITICAL：真的記憶體不夠 → 記住新水位
        assertTrue(MemoryBudget.isPersistentLevel(5))
        assertTrue(MemoryBudget.isPersistentLevel(10))
        assertTrue(MemoryBudget.isPersistentLevel(15))
        // UI_HIDDEN 以上：只是「沒人看」→ 一次性降載，回前景要能長回來
        assertFalse(MemoryBudget.isPersistentLevel(20))
        assertFalse(MemoryBudget.isPersistentLevel(40))
        assertFalse(MemoryBudget.isPersistentLevel(60))
        assertFalse(MemoryBudget.isPersistentLevel(80))
    }

    @Test
    fun `a shrunk quota never goes below a usable floor`() {
        val heap = 512L * mb
        val tiny = MemoryBudget.quotaBytesFor(
            MemoryBudget.Layer.PDF_PAGE, heap, MemoryBudget.factorForTrimLevel(80)
        )
        assertTrue("極端壓力下仍要留一點（避免每幀重解）", tiny > 0)
        assertTrue("但一定要比正常水位小得多", tiny < heap / 10)
    }
}
