package com.vic.inkflow.util

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文字擦除的命中幾何：逐段判相交，不是整條路徑的 bbox。
 *
 * 迴歸重點：一條斜穿頁面的橡皮擦路徑，舊碼用「所有擦除點的總 bbox」與文字框
 * 相交，會把路徑兩側完全沒碰到的字一起擦掉。
 */
class IntersectionUtilsTest {

    private fun hits(points: List<Offset>, radius: Float = 10f): Boolean =
        IntersectionUtils.polylineIntersectsRect(
            points = points,
            rectLeft = 250f,
            rectTop = 100f,
            rectRight = 350f,
            rectBottom = 120f,
            radius = radius
        )

    @Test
    fun `diagonal path does not erase text inside its bounding box but far from the line`() {
        // (0,0)→(400,300) 的直線在 x=250 處 y≈187，離文字框下緣 120 還有 67。
        assertFalse(hits(listOf(Offset(0f, 0f), Offset(400f, 300f))))
    }

    @Test
    fun `path through the text box erases it`() {
        assertTrue(hits(listOf(Offset(0f, 110f), Offset(400f, 110f))))
    }

    @Test
    fun `segment with both endpoints outside still hits when it crosses the box`() {
        assertTrue(hits(listOf(Offset(300f, -50f), Offset(300f, 250f))))
    }

    @Test
    fun `single tap within the radius erases the text`() {
        assertTrue(hits(listOf(Offset(244f, 110f))))
    }

    @Test
    fun `single tap outside the radius does not erase the text`() {
        assertFalse(hits(listOf(Offset(240f, 50f))))
    }
}
