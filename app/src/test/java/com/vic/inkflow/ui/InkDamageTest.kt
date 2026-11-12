package com.vic.inkflow.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 髒矩形增量重建的數學回歸網。
 *
 * 背景：擦除卡頓實測 draw 17.6ms/幀，主因是每批都全量重建（清空 3072px＋重畫全筆）。
 * 修法是髒區：只清髒區、只重畫相交的。這裡釘住三個純函數——聯集、外擴相交的
 * 單調性、覆蓋率閾值。畫布真正的 clip/draw 走不了 JVM（要 Android Canvas），
 * 那部分靠實機幀率驗。
 */
class InkDamageTest {

    @Test
    fun unionOfNullIsTheOther() {
        val r = Rect(10f, 10f, 20f, 20f)
        assertEquals(r, unionRect(null, r))
    }

    @Test
    fun unionCoversBoth() {
        val u = unionRect(Rect(0f, 0f, 10f, 10f), Rect(5f, 5f, 20f, 30f))
        assertEquals(Rect(0f, 0f, 20f, 30f), u)
    }

    @Test
    fun unionIsMonotone() {
        // 髒區只增不減：漏一塊就是殘影，多一塊只是多畫。這條保證「宁多勿少」。
        var d: Rect? = null
        val rs = listOf(
            Rect(0f, 0f, 10f, 10f),
            Rect(100f, 100f, 110f, 105f),
            Rect(-50f, 0f, 5f, 5f)
        )
        for (r in rs) d = unionRect(d, r)
        val final = d!!
        for (r in rs) {
            assertTrue("left", final.left <= r.left)
            assertTrue("top", final.top <= r.top)
            assertTrue("right", final.right >= r.right)
            assertTrue("bottom", final.bottom >= r.bottom)
        }
    }

    @Test
    fun smallDirtyStaysPartial() {
        // A4 紙上一筆：髒區佔比远小于一半 → 增量。
        val page = Rect(0f, 0f, 595f, 842f)
        val stroke = Rect(100f, 100f, 200f, 120f)
        assertTrue(damageCoverage(stroke, page) < INK_FULL_REBUILD_COVERAGE)
    }

    @Test
    fun hugeDirtyFallsBackToFull() {
        // 半頁以上的變化：clip 來回不如一次清，直接全量。
        val page = Rect(0f, 0f, 595f, 842f)
        val half = Rect(0f, 0f, 595f, 500f)
        assertTrue(damageCoverage(half, page) >= INK_FULL_REBUILD_COVERAGE)
    }

    @Test
    fun degeneratePageFallsBackToFull() {
        // 紙尺寸異常（0 面積）時不除零，直接全量——錯了也只是多畫，不會少畫。
        assertEquals(1f, damageCoverage(Rect(0f, 0f, 10f, 10f), Rect.Zero), 0.001f)
    }

    @Test
    fun nonFiniteBoundsAreDetected() {
        // 包絡退化输入可能产出 NaN/Inf：调用端看到一个就走全量重建，
        // 绝不能进增量路径（minOf/maxOf 会静默吞掉 NaN，相交全 false，该画的全跳过）。
        assertTrue(Rect(0f, 0f, 10f, 10f).hasFiniteBounds())
        assertTrue(!Rect(Float.NaN, 0f, 10f, 10f).hasFiniteBounds())
        assertTrue(!Rect(0f, 0f, Float.POSITIVE_INFINITY, 10f).hasFiniteBounds())
        assertTrue(!Rect(0f, 0f, 10f, Float.NEGATIVE_INFINITY).hasFiniteBounds())
    }

    @Test
    fun inflatedZoneStillOverlapsTheStroke() {
        // 外擴后的髒區必須蓋住原筆（否則跨區筆觸邊緣留縫）。
        val stroke = Rect(100f, 100f, 200f, 120f)
        val zone = stroke.inflate(INK_DIRTY_INFLATE_PX)
        assertTrue(zone.overlaps(stroke))
        assertTrue(zone.left <= stroke.left && zone.top <= stroke.top)
    }

    // ── 位圖有序簽名（純換序要全量，加筆/刪筆不要） ─────────────────────────

    @Test
    fun reorderOfSameSetForcesFullRebuild() {
        // 同步換 rowid／桌機端覆寫可能讓同一批筆換序；bounds 沒變 → 髒區算不出來。
        assertTrue(bitmapOrderChanged(listOf("a", "b", "c"), listOf("c", "a", "b")))
    }

    @Test
    fun appendOrDeleteKeepsIncrementalPath() {
        // 加筆／刪筆是大小不同：那是髒區的日常工作，不該全頁重畫。
        assertTrue(!bitmapOrderChanged(listOf("a", "b"), listOf("a", "b", "c")))
        assertTrue(!bitmapOrderChanged(listOf("a", "b", "c"), listOf("a", "b")))
    }

    @Test
    fun identicalOrderIsNotAChange() {
        assertTrue(!bitmapOrderChanged(listOf("a", "b"), listOf("a", "b")))
        // 第一次建圖（沒有上次簽名）不算換序：新圖本來就走全量。
        assertTrue(!bitmapOrderChanged(null, listOf("a", "b")))
    }

    // ── 墨跡快取解析度（提筆「糊一下」的元凶） ───────────────────────────────

    @Test
    fun normalPageRendersAtOneToOne() {
        // 一般紙面（遠小於兩個預算）必須逐像素一致：這裡掉到 0.9 以下，
        // 提筆那一筆就會從向量換成「縮小再放大」的糊版。
        assertEquals(1f, inkCacheScale(2000, 2800), 0.0001f)
        // 3200×4500 的平板整頁：長邊預算讓它落在 0.91，放大 1.09 倍以內＝看不出來。
        val scale = inkCacheScale(3200, 4500)
        assertTrue("整頁比例 $scale 應 >= 0.9", scale >= 0.9f)
        assertTrue("整頁比例 $scale 不可 > 1", scale <= 1f)
    }

    @Test
    fun zoomedCanvasIsDownsampledToTheBudget() {
        // 縮放到上萬 px：一定要降，否則 1:1 建圖會直接爆記憶體／超過材質上限。
        val scale = inkCacheScale(9000, 12000)
        assertTrue("縮放後比例 $scale 應 <= 0.4", scale <= 0.4f)
        assertTrue("縮放後比例 $scale 應 > 0", scale > 0f)
    }

    @Test
    fun scaleNeverExceedsOneAndStaysWithinAreaBudget() {
        val sizes = listOf(1 to 1, 512 to 512, 3200 to 4500, 5000 to 5000, 12000 to 16000)
        sizes.forEach { (w, h) ->
            val s = inkCacheScale(w, h)
            assertTrue("scale($w,$h)=$s 不可 > 1", s <= 1f)
            assertTrue("scale($w,$h)=$s 不可 <= 0", s > 0f)
            val area = (w * s) * (h * s)
            assertTrue(
                "scale($w,$h)=$s 面積 $area 超出預算 $INK_CACHE_MAX_PX",
                area <= INK_CACHE_MAX_PX * 1.001f
            )
        }
    }
}
