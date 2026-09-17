package com.vic.inkflow

import com.vic.inkflow.ui.isBlankPage
import com.vic.inkflow.ui.whiteRatioOfPixels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M6 空白頁偵測測試（JVM、全離線）：
 *  - isBlankPage：DB 空＋紙白比例雙門檻
 *  - whiteRatioOfPixels：合成 ARGB 陣列（全白／半黑／噪點／門檻邊界）
 */
class AiBlankPageTest {

    @Test fun blank_needsBothGates() {
        assertTrue(isBlankPage(true, 1f))
        assertTrue(isBlankPage(true, 0.99f))
        assertFalse(isBlankPage(true, 0.98f))
        assertFalse(isBlankPage(false, 1f))
        assertFalse(isBlankPage(false, 0f))
        assertTrue(isBlankPage(true, 0.95f, whiteThreshold = 0.9f))
        assertFalse(isBlankPage(true, 0.85f, whiteThreshold = 0.9f))
    }

    @Test fun ratio_allWhite_allBlack() {
        val white = IntArray(1000) { -0x1 } // 0xFFFFFFFF
        assertEquals(1f, whiteRatioOfPixels(white))
        val black = IntArray(1000) { -0x1000000 } // 0xFF000000
        assertEquals(0f, whiteRatioOfPixels(black))
        assertEquals(0f, whiteRatioOfPixels(IntArray(0)))
    }

    @Test fun ratio_halfInk() {
        val half = IntArray(1000) { i -> if (i < 500) -0x1 else -0x1000000 }
        assertEquals(0.5f, whiteRatioOfPixels(half))
    }

    @Test fun ratio_thresholdEdge() {
        // 門檻 200：57 全通道→墨，200→白
        val dark = IntArray(100) { -0xC6C6C7 } // bits = 0xFF393939
        assertEquals(0f, whiteRatioOfPixels(dark))
        val edge = IntArray(100) { -0x373738 } // bits = 0xFFC8C8C8 = 200
        assertEquals(1f, whiteRatioOfPixels(edge))
        // 掃描灰（常見掃描底色 ~245）算白
        val scan = IntArray(100) { -0x0B0B0B } // bits = 0xFFF4F4F5
        assertEquals(1f, whiteRatioOfPixels(scan))
    }

    @Test fun ratio_sparseInkStillBlank() {
        // 幾個髒點（<1%）不推翻空白判定
        val mostly = IntArray(10000) { -0x1 }
        for (i in 0 until 50) mostly[i * 197] = -0x1000000
        assertTrue(whiteRatioOfPixels(mostly) > 0.99f)
        assertTrue(isBlankPage(true, whiteRatioOfPixels(mostly)))
    }
}
