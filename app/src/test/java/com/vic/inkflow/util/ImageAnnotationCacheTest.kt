package com.vic.inkflow.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 圖片註解共享快取的「夠大」判斷。
 *
 * 為什麼要釘住：快取以 uri 為 key，存的是「第一次解出來的那一份」。若小尺寸先被
 * 側欄要走（512），畫布後面要 2048——判斷錯了就用一張 512 的圖硬撐整頁（糊），
 * 或整頁縮圖被迫跟著重解（卡）。邊界就是快取能否同時餵飽兩種尺寸的關鍵。
 */
class ImageAnnotationCacheTest {

    @Test
    fun `big enough when cached long side is at least half the request`() {
        assertTrue(isLargeEnough(1024, 2048))   // 畫布要 2048，手上有 1024 的一半 → 算夠
        assertTrue(isLargeEnough(2048, 2048))
        assertTrue(isLargeEnough(512, 512))
        assertTrue(isLargeEnough(2048, 512))    // 大圖餵小請求：直接吃
    }

    @Test
    fun `too small triggers re-decode`() {
        assertFalse(isLargeEnough(512, 2048))   // 側欄先解 512 → 畫布要 2048 時必須重解
        assertFalse(isLargeEnough(1023, 2048))
        assertFalse(isLargeEnough(1, 512))
    }
}
