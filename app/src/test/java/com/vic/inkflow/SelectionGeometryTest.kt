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

    private fun textAt(x: Float, y: Float, text: String = "hello", fontSize: Float = 20f) =
        com.vic.inkflow.data.TextAnnotationEntity(
            documentUri = "u",
            pageIndex = 0,
            text = text,
            modelX = x,
            modelY = y,
            fontSize = fontSize
        )

    @Test
    fun textHitByBaselineInside() {
        val ann = textAt(100f, 100f)
        val poly = listOf(Offset(50f, 50f), Offset(200f, 50f), Offset(200f, 200f), Offset(50f, 200f))
        assertTrue(com.vic.inkflow.ui.isTextSelectedByLasso(ann, poly))
    }

    @Test
    fun textHitByVertexInBox() {
        // baseline 在圈外 (y=500)，但圈的頂點 (140,490) 深深落在字框 (100..160, 476..500) 內。
        val ann = textAt(100f, 500f)
        val poly = listOf(Offset(120f, 480f), Offset(140f, 480f), Offset(140f, 490f), Offset(120f, 490f))
        val b = com.vic.inkflow.ui.textEstimatedBounds(ann)
        assertTrue(
            "l=${b.left} t=${b.top} r=${b.right} b=${b.bottom} model=(${ann.modelX},${ann.modelY}) len=${ann.text.length}",
            com.vic.inkflow.ui.isTextSelectedByLasso(ann, poly)
        )
    }

    @Test
    fun textMissOutside() {
        val ann = textAt(100f, 100f)
        val poly = listOf(Offset(400f, 400f), Offset(500f, 400f), Offset(500f, 500f), Offset(400f, 500f))
        assertTrue(!com.vic.inkflow.ui.isTextSelectedByLasso(ann, poly))
    }

    @Test
    fun textHitDegeneratePoly() {
        val ann = textAt(100f, 100f)
        assertTrue(!com.vic.inkflow.ui.isTextSelectedByLasso(ann, listOf(Offset(0f, 0f), Offset(1f, 1f))))
    }

    @Test
    fun wideCharsCountFullWidth() {
        // 中文 4 字 ×20 = 80（舊 0.65 公式只給 52，框包不住）。
        // 字面量只用 ASCII 拼碼點，避免工具鏈吞字節，見 isWideChar 註解。
        val s = String(intArrayOf(0x4E2D, 0x6587, 0x6E2C, 0x8A66).map { it.toChar() }.toCharArray())
        assertEquals(80f, com.vic.inkflow.ui.measureTextWidth(s, 20f), 0.01f)
        // 混排：2 半形 + 2 全形 = 2*13 + 2*20 = 66
        val mixed = "ab" + String(intArrayOf(0x4E2D, 0x6587).map { it.toChar() }.toCharArray())
        assertEquals(66f, com.vic.inkflow.ui.measureTextWidth(mixed, 20f), 0.01f)
        assertTrue(!com.vic.inkflow.ui.isWideChar('a'))
        assertTrue(com.vic.inkflow.ui.isWideChar(0x4E2D.toChar()))
    }

    @Test
    fun textBoundsCoversMultiline() {
        // 三行字：框高必須含全部行（之前只算一行，框包不住）。
        val ann = textAt(100f, 300f, text = "ab\ncdef\ng", fontSize = 20f)
        val b = com.vic.inkflow.ui.textEstimatedBounds(ann)
        // 最長行 4 字：寬 = 4*20*0.65 = 52；高 = 20 + 2*24 = 68
        assertEquals(100f, b.left, 0.01f)
        assertEquals(152f, b.right, 0.01f)
        assertEquals(300f - 68f, b.top, 0.01f)
        assertEquals(300f, b.bottom, 0.01f)
        // 第二行區域的頂點命中：單行公式 top=276 會 miss，多行 top=232 才中。
        // baseline (100,300) 在圈外，走頂點分支。
        val poly = listOf(Offset(120f, 240f), Offset(140f, 240f), Offset(140f, 260f), Offset(120f, 260f))
        assertTrue(com.vic.inkflow.ui.isTextSelectedByLasso(ann, poly))
    }
}
