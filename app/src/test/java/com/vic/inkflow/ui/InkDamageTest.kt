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
    fun inflatedZoneStillOverlapsTheStroke() {
        // 外擴后的髒區必須蓋住原筆（否則跨區筆觸邊緣留縫）。
        val stroke = Rect(100f, 100f, 200f, 120f)
        val zone = stroke.inflate(INK_DIRTY_INFLATE_PX)
        assertTrue(zone.overlaps(stroke))
        assertTrue(zone.left <= stroke.left && zone.top <= stroke.top)
    }
}
