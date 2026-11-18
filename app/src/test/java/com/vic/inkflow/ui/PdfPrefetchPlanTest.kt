package com.vic.inkflow.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「往前預取幾頁」的決策（[PdfViewModel.prefetchDepthFor]）。
 *
 * 為什麼要釘住：預取是唯一「在使用者滑到之前就先清楚」的手段，但它也是
 * 唯一會主動多吃一份 hi 位元圖的地方（單頁 14–40MB，住在 GC 看不到的 native heap）。
 * 深度算錯的兩個方向都難看：
 *
 *  - 太大：預取頁跟可見頁搶預算 → [PdfViewModel.selectColdEvictions] 從最舊砍起，
 *    砍掉的正是預取頁 → 白渲，而且下一次翻頁還是糊。
 *  - 太小（或 0）：回到「滑到那頁才開始渲」，使用者看到的就是糊一下再變清楚。
 */
class PdfPrefetchPlanTest {

    private companion object {
        const val MB = 1024L * 1024L
        /** 512MB heap 的 PDF_PAGE 配額＝heap×0.2 ≈ 102MB。 */
        const val QUOTA_102MB = 102L * MB
        /** 平板 100% 閱讀的 A4 hi 單頁（1600×2264 ARGB_8888）≈ 14.5MB。 */
        const val PAGE_145MB = 15L * MB
    }

    @Test
    fun `normal reading prefetches the full depth budget`() {
        assertEquals(
            PdfViewModel.MAX_PREFETCH_DEPTH,
            PdfViewModel.prefetchDepthFor(QUOTA_102MB, PAGE_145MB, visiblePages = 1)
        )
    }

    @Test
    fun `zoomed in pages eat the budget and reduce depth`() {
        // docZoom=4 的 A4 單頁 hi ≈ 58MB：可見一頁就吃掉一半以上，只剩一頁的餘裕
        assertEquals(
            1,
            PdfViewModel.prefetchDepthFor(QUOTA_102MB, 58L * MB, visiblePages = 1)
        )
    }

    @Test
    fun `visible pages are paid for before prefetch pages`() {
        val oneVisible = PdfViewModel.prefetchDepthFor(QUOTA_102MB, PAGE_145MB, visiblePages = 1)
        val threeVisible = PdfViewModel.prefetchDepthFor(QUOTA_102MB, PAGE_145MB, visiblePages = 3)
        assertTrue("可見頁變多，預取深度不能反而變大", threeVisible <= oneVisible)
        assertEquals(3, threeVisible)
    }

    @Test
    fun `always keeps at least one page of lead time`() {
        // 預算連一頁都裝不下（壓力收縮、超大版面）也不能回 0：
        // 使用者等一次 hi 渲染，比「每一頁都要等」好得多。
        assertEquals(1, PdfViewModel.prefetchDepthFor(QUOTA_102MB, 4096L * MB, visiblePages = 1))
        assertEquals(1, PdfViewModel.prefetchDepthFor(0L, PAGE_145MB, visiblePages = 1))
    }

    @Test
    fun `unknown page size still yields a usable depth`() {
        // 頁尺寸還沒掃到（剛開檔）→ pageBytes=0：只留一頁，不要憑空放大
        assertEquals(1, PdfViewModel.prefetchDepthFor(QUOTA_102MB, 0L, visiblePages = 1))
    }

    @Test
    fun `zero depth is possible for callers that opt out`() {
        assertEquals(0, PdfViewModel.prefetchDepthFor(QUOTA_102MB, PAGE_145MB, 1, maxDepth = 0))
    }

    @Test
    fun `depth never exceeds the configured cap`() {
        val depth = PdfViewModel.prefetchDepthFor(1024L * MB, PAGE_145MB, visiblePages = 1)
        assertEquals(PdfViewModel.MAX_PREFETCH_DEPTH, depth)
    }
}
