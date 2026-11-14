package com.vic.inkflow.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 圖片註解共享快取的「夠不夠吃」判斷。
 *
 * 為什麼要釘住：快取以 uri 為 key，存的是「第一次解出來的那一份」。若小尺寸先被
 * 側欄要走（512），畫布後面要 2048——判斷錯了就用一張 512 的圖硬撐整頁（糊），
 * 或整頁縮圖被迫跟著重解（卡）。邊界就是快取能否同時餵飽兩種尺寸的關鍵。
 *
 * 「為這次需求解出來的」= 解碼的原生上限，1020px 寬的公式圖請 2048 也只拿到 1020，
 * 再請多少次都不會更大——這種圖必須算「夠吃」，否則永遠空白（2026-11-14 事故）。
 */
class ImageAnnotationCacheTest {

    @Test
    fun `bucketPx rounds up to the four decode buckets`() {
        assertTrue(ImageAnnotationCache.bucketPx(100) == 256)
        assertTrue(ImageAnnotationCache.bucketPx(256) == 256)
        assertTrue(ImageAnnotationCache.bucketPx(257) == 512)
        assertTrue(ImageAnnotationCache.bucketPx(512) == 512)
        assertTrue(ImageAnnotationCache.bucketPx(513) == 1024)
        assertTrue(ImageAnnotationCache.bucketPx(1024) == 1024)
        assertTrue(ImageAnnotationCache.bucketPx(1025) == 2048)
        assertTrue(ImageAnnotationCache.bucketPx(4096) == 2048)
    }

    @Test
    fun `serves the bitmap that was decoded for this request`() {
        assertTrue(cacheAccepts(1020, 2048, 2048))    // 公式圖 native 1020，請 2048 → 拿到 1020 就是上限，必須吃
        assertTrue(cacheAccepts(900, 2048, 2048))     // 900px 的圖也一樣：不能永遠重解
        assertTrue(cacheAccepts(999, 2048, 2048))     // 半數邊界 1023/2048 以下也算夠（它是上限）
        assertTrue(cacheAccepts(1020, 1024, 1024))    // 請 1024 拿到 1020，直接吃
        assertTrue(cacheAccepts(512, 512, 512))       // 剛好等於請求：吃
        assertTrue(cacheAccepts(2048, 2048, 512))     // 大圖餵小請求：直接吃
    }

    @Test
    fun `half of the request is enough when decoded for an earlier request`() {
        assertTrue(cacheAccepts(1024, 512, 2048))     // 1024 是 2048 的一半 → 算夠
        assertTrue(cacheAccepts(1024, 2048, 2048))    // 等於請求：必吃
        assertTrue(cacheAccepts(2048, 2048, 2048))
        assertTrue(cacheAccepts(512, 512, 512))
        assertTrue(cacheAccepts(2048, 1024, 512))     // decode 出來比請求大：吃
    }

    @Test
    fun `thumbnail served to a bigger request is rejected so canvas re-decodes`() {
        assertFalse(cacheAccepts(512, 512, 2048))     // 側欄先解 512 → 畫布要 2048 時必須重解
        assertFalse(cacheAccepts(1023, 512, 2048))    // 512 的縮圖不管多寬都冒充不了 2048 的畫布
        assertFalse(cacheAccepts(800, 512, 2048))     // 即使比請求的一半大很多，也是縮圖
        assertTrue(cacheAccepts(1, 512, 512))         // 為 512 解出來的就是上限，不能再要求更多了
    }
}
