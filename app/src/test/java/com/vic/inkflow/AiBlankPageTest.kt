package com.vic.inkflow

import com.vic.inkflow.data.ImageAnnotation
import com.vic.inkflow.data.Stroke
import com.vic.inkflow.data.TextAnnotation
import com.vic.inkflow.ui.continueTop
import com.vic.inkflow.ui.isBlankPage
import com.vic.inkflow.ui.isSelfPage
import com.vic.inkflow.ui.measureContentBottom
import com.vic.inkflow.ui.whiteRatioOfPixels
import com.vic.inkflow.ui.wrapTextLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ---- 接續前頁（自家頁＋量底＋空間三岔） ----

    @Test fun selfPage_gates() {
        assertFalse(isSelfPage(true, 1f)) // DB空→舊路（掃空白）
        assertTrue(isSelfPage(false, 1f))
        assertTrue(isSelfPage(false, 0.98f))
        assertFalse(isSelfPage(false, 0.97f)) // 原生 PDF 出局
        assertTrue(isSelfPage(false, 0.9f, whiteThreshold = 0.9f))
    }

    private val fakeMetrics: (Float) -> Pair<Float, Float> = { fs -> (fs * 1.2f) to (fs * 0.25f) }

    private fun stroke(bottom: Float) =
        Stroke(id = "s1", documentUri = "d", pageIndex = 0, color = 0, strokeWidth = 2f, boundsBottom = bottom)

    private fun text(y: Float, body: String, size: Float = 20f, stamp: Boolean = false, boxW: Float? = null) =
        TextAnnotation(id = "t1", documentUri = "d", pageIndex = 0, text = body, modelX = 48f, modelY = y, fontSize = size, isStamp = stamp, boxWidth = boxW)

    private fun image(y: Float, h: Float) =
        ImageAnnotation(id = "i1", documentUri = "d", pageIndex = 0, uri = "u", modelX = 48f, modelY = y, modelWidth = 100f, modelHeight = h)

    @Test fun measure_maxWins() {
        // 筆底300；字 100＋2×24＋5=153；圖 200＋120=320 → 320
        val b = measureContentBottom(listOf(stroke(300f)), listOf(text(100f, "a\nb\nc")), listOf(image(200f, 120f)), fakeMetrics)
        assertEquals(320f, b!!)
    }

    @Test fun measure_textOnly() {
        // 單行：100＋0×24＋5=105
        assertEquals(105f, measureContentBottom(emptyList(), listOf(text(100f, "hi")), emptyList(), fakeMetrics)!!)
    }

    @Test fun measure_stampAborts() {
        assertNull(measureContentBottom(emptyList(), listOf(text(100f, "hi", stamp = true)), emptyList(), fakeMetrics))
    }

    @Test fun measure_emptyZero_badMetrics() {
        assertEquals(0f, measureContentBottom(emptyList(), emptyList(), emptyList(), fakeMetrics)!!)
        assertNull(measureContentBottom(emptyList(), listOf(text(100f, "hi")), emptyList(), metrics = { _ -> 0f to 0f }))
    }

    @Test fun measure_boxedTextCountsWrappedLinesNotNewlines() {
        // 二十個全形字、boxWidth=100、fontSize=20 → 每行 5 字、折成多行。
        // 修好前：n = split("\n").size = 1 → 底只量到 105（單行高），
        // 「接續前頁同頁續寫」會把 AI 文字疊在這個折了行的框上。
        // 用碼點造字，不寫罕見 CJK 字面量（工具鏈吞字節，血淚）。
        val wide = String(CharArray(20) { 0x4E2D.toChar() })
        val boxed = text(100f, wide, boxW = 100f)
        val lines = wrapTextLines(wide, 20f, 100f)
        assertTrue("boxWidth=100/fontSize=20 應該折成多行，實際 ${lines.size}", lines.size > 1)
        // 合約：量到的底 ＝ 用「畫面／匯出同一支折行」算出的行數（兩邊同源，不各算各的）。
        val measured = measureContentBottom(emptyList(), listOf(boxed), emptyList(), fakeMetrics)!!
        assertEquals(100f + (lines.size - 1) * 24f + 5f, measured)
        // 舊的 \n 口徑（1 行 → 105）會少算——這條就是回歸護欄。
        assertTrue("折行的框不能被量成單行高（$measured 應 > 105）", measured > 105f)
    }

    @Test fun measure_legacyTextStillCountsNewlines() {
        // boxWidth=null（舊資料／AI 匯入文字）行為必須與以前完全相同：逐 \n 行數。
        assertEquals(105f, measureContentBottom(emptyList(), listOf(text(100f, "hi")), emptyList(), fakeMetrics)!!)
        assertEquals(153f, measureContentBottom(emptyList(), listOf(text(100f, "a\nb\nc")), emptyList(), fakeMetrics)!!)
    }

    @Test fun continueTop_room() {
        // modelH=842：底300→top=308（room 470夠兩行）
        assertEquals(308f, continueTop(300f, 842f, 20f)!!)
        // 底太高→頂到 marginTop
        assertEquals(64f, continueTop(10f, 842f, 20f)!!)
        // 底750→room 20 < 54 → 不接續
        assertNull(continueTop(750f, 842f, 20f))
    }
}
