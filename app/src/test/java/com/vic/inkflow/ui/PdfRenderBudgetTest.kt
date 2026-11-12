package com.vic.inkflow.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PDF 頁面點陣圖的記憶體上限與「要不要渲」兩道閘門。
 *
 * 背景（重大問題）：在 190 頁的 PDF 上上下滑動會先卡頓再閃退。
 * 根因有兩個，這裡把兩條決策都釘住：
 *
 *  1. **記憶體無上限**：`bitmapCache` 是 LruCache，但同一張圖還被 flow 的值
 *     拿著（UI 直接顯示它）。LruCache 逐出 ≠ 圖被釋放，於是捲過的每一頁
 *     都留在 native heap 裡（實測 173MB / 13 張頁面圖）→ native heap 撐爆 → 閃退。
 *  2. **白渲被捲過的頁**：捲動時經過的頁也排隊等高清渲染，排在你要看的那頁
 *     前面 → 明顯卡頓。
 */
class PdfRenderBudgetTest {

    // ── 預算 ──────────────────────────────────────────────────────────────

    @Test
    fun `live budget is bounded by heap and never below the floor`() {
        // 堆 512MB（largeHeap）→ 預算 = 堆的 1/5（≈102MB）；能容可見頁＋前 1–2 頁 hi 預取
        assertEquals(512L * 1024 * 1024 / 5, PdfViewModel.liveBitmapBudgetBytes(512L * 1024 * 1024))
        // 小堆也不能低於地板：低於地板會把可見頁也砍掉，畫面就閃白了
        assertEquals(
            64L * 1024 * 1024,
            PdfViewModel.liveBitmapBudgetBytes(16L * 1024 * 1024)
        )
    }

    @Test
    fun `live budget grows with the heap but stays a fraction of it`() {
        val small = PdfViewModel.liveBitmapBudgetBytes(128L * 1024 * 1024)
        val big = PdfViewModel.liveBitmapBudgetBytes(512L * 1024 * 1024)
        assertTrue("128MB 堆：預算落到地板 64MB", small == 64L * 1024 * 1024)
        assertTrue("大堆的預算必須比小堆大", big > small)
        assertTrue("預算不能超過堆的 1/4", big <= 512L * 1024 * 1024 / 4)
    }

    @Test
    fun `thumbnail budget is bounded too`() {
        // 小堆：落到地板
        assertEquals(
            16L * 1024 * 1024,
            PdfViewModel.liveThumbnailBudgetBytes(64L * 1024 * 1024)
        )
        // 大堆：是堆的 1/20，且有上限
        val big = PdfViewModel.liveThumbnailBudgetBytes(512L * 1024 * 1024)
        assertTrue(big <= 512L * 1024 * 1024 / 20)
        assertTrue(big >= 16L * 1024 * 1024)
    }

    // ── 要不要渲 ───────────────────────────────────────────────────────────

    @Test
    fun `a page someone is looking at is always rendered`() {
        assertTrue(PdfViewModel.shouldRenderPage(hasSubscriber = true, msSinceRequest = 999_999))
    }

    @Test
    fun `a page nobody wants is not rendered`() {
        // 捲過去的頁：需求時間戳已經很久以前，也沒人訂閱
        assertFalse(PdfViewModel.shouldRenderPage(hasSubscriber = false, msSinceRequest = 5_000))
    }

    @Test
    fun `a freshly requested page survives the subscribe race`() {
        // Compose 的 collectAsState 是下一幀才訂閱，剛組成的瞬間沒人訂閱。
        // 這段時間窗就是為了不誤殺它。
        assertTrue(PdfViewModel.shouldRenderPage(hasSubscriber = false, msSinceRequest = 0))
        assertTrue(
            PdfViewModel.shouldRenderPage(
                hasSubscriber = false,
                msSinceRequest = PdfViewModel.REQUEST_FRESH_MS
            )
        )
        assertFalse(
            PdfViewModel.shouldRenderPage(
                hasSubscriber = false,
                msSinceRequest = PdfViewModel.REQUEST_FRESH_MS + 1
            )
        )
    }

    // ── 回收政策 ───────────────────────────────────────────────────────────

    @Test
    fun `nothing is evicted while under budget`() {
        val candidates = listOf(
            PdfViewModel.BitmapCandidate(index = 1, bytes = 10, hasSubscriber = false, age = 1),
            PdfViewModel.BitmapCandidate(index = 2, bytes = 10, hasSubscriber = false, age = 2)
        )
        assertTrue(
            PdfViewModel.selectColdEvictions(candidates, liveBytes = 20, budget = 64).isEmpty()
        )
    }

    @Test
    fun `oldest cold pages are evicted first`() {
        val candidates = listOf(
            PdfViewModel.BitmapCandidate(index = 1, bytes = 10, hasSubscriber = false, age = 1),
            PdfViewModel.BitmapCandidate(index = 2, bytes = 10, hasSubscriber = false, age = 2),
            PdfViewModel.BitmapCandidate(index = 3, bytes = 10, hasSubscriber = false, age = 3)
        )
        // 活著 30、預算 10 → 砍到剩 10 就停 → 砍掉最舊的兩張
        val evicted = PdfViewModel.selectColdEvictions(candidates, liveBytes = 30, budget = 10)
        assertEquals(listOf(1, 2), evicted)
    }

    @Test
    fun `a page on screen is never evicted even if it is the oldest`() {
        val candidates = listOf(
            PdfViewModel.BitmapCandidate(index = 1, bytes = 10, hasSubscriber = true, age = 1),
            PdfViewModel.BitmapCandidate(index = 2, bytes = 10, hasSubscriber = false, age = 2)
        )
        val evicted = PdfViewModel.selectColdEvictions(candidates, liveBytes = 20, budget = 10)
        assertEquals(listOf(2), evicted)
    }

    @Test
    fun `eviction stops at the budget instead of overshooting`() {
        val candidates = listOf(
            PdfViewModel.BitmapCandidate(index = 1, bytes = 30, hasSubscriber = false, age = 1),
            PdfViewModel.BitmapCandidate(index = 2, bytes = 30, hasSubscriber = false, age = 2)
        )
        // 活著 60、預算 40 → 砍一張（30）就低於預算了，不該砍兩張
        val evicted = PdfViewModel.selectColdEvictions(candidates, liveBytes = 60, budget = 40)
        assertEquals(listOf(1), evicted)
    }

    @Test
    fun `visible pages are kept even when the budget cannot be met`() {
        // 三張都在螢幕上 → 一張都不能砍（寧可超預算也不能讓畫面跳掉）
        val candidates = listOf(
            PdfViewModel.BitmapCandidate(index = 1, bytes = 10, hasSubscriber = true, age = 1),
            PdfViewModel.BitmapCandidate(index = 2, bytes = 10, hasSubscriber = true, age = 2),
            PdfViewModel.BitmapCandidate(index = 3, bytes = 10, hasSubscriber = true, age = 3)
        )
        val evicted = PdfViewModel.selectColdEvictions(candidates, liveBytes = 30, budget = 10)
        assertTrue(evicted.isEmpty())
    }
}
