package com.vic.inkflow

import androidx.compose.ui.geometry.Offset
import com.vic.inkflow.ui.clipPolygonToRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionGeometryTest {

    private fun rectPoly(l: Float, t: Float, r: Float, b: Float) = listOf(
        Offset(l, t), Offset(r, t), Offset(r, b), Offset(l, b)
    )

    @Test
    fun interiorPolygonUnchanged() {
        val poly = listOf(Offset(100f, 100f), Offset(200f, 100f), Offset(150f, 200f))
        val out = clipPolygonToRect(poly, 0f, 0f, 800f, 800f)
        assertEquals(poly.size, out.size)
        for (i in poly.indices) {
            assertEquals(poly[i].x, out[i].x, 0.001f)
            assertEquals(poly[i].y, out[i].y, 0.001f)
        }
    }

    @Test
    fun exteriorPolygonEmptied() {
        val poly = listOf(Offset(900f, 900f), Offset(950f, 900f), Offset(925f, 950f))
        assertTrue(clipPolygonToRect(poly, 0f, 0f, 800f, 800f).isEmpty())
    }

    @Test
    fun openArcBecomesClosedBoundedPolygon() {
        // 跨頁開弧：從上頁下來、沒回去。裁後必須閉合且不出紙界，
        // 不能讓弦閉合整頁誤選。
        val arc = listOf(
            Offset(100f, 700f), Offset(200f, 790f), Offset(300f, 850f),
            Offset(400f, 900f), Offset(500f, 950f)
        )
        val out = clipPolygonToRect(arc, 0f, 0f, 800f, 800f)
        assertTrue("clipped has >=3 pts, got ${out.size}", out.size >= 3)
        assertTrue(out.all { it.x in 0f..800f && it.y in 0f..800f })
        // 上界交點存在（弧被紙界切斷再閉合，而非弦連首尾）
        assertTrue(out.any { it.y == 800f })
    }

    @Test
    fun fullPageLoopStaysWhole() {
        val out = clipPolygonToRect(rectPoly(0f, 0f, 800f, 800f), 0f, 0f, 800f, 800f)
        assertTrue(out.size >= 4)
        assertTrue(out.all { it.x in 0f..800f && it.y in 0f..800f })
    }

    @Test
    fun emptyInEmptyOut() {
        assertTrue(clipPolygonToRect(emptyList(), 0f, 0f, 800f, 800f).isEmpty())
    }
}
