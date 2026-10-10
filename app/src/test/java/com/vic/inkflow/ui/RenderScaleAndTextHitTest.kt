package com.vic.inkflow.ui

import com.vic.inkflow.data.TextAnnotationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 縮放感知的 PDF 渲染倍率（v6 分級渲染）＋ 文字命中框寬度。
 *
 * 兩組規則各自對應一個「看起來正常但其實壞掉」的故障：
 *
 *  - **倍率**：墨是向量、PDF 是點陣。倍率若不跟縮放走，放大時同一頁上墨銳利而
 *    PDF 糊——那是最容易被當成「渲染有 bug」回報的現象。倍率必須由**紙寬**
 *    （含 docZoom）推導，而不是視窗寬。
 *  - **文字命中框**：全形字按半形算寬，中文標籤的框比字窄三分之一，看得見點不到。
 *
 * 全部是純函式，所以不需要真機——這正是它們該被釘住的原因。
 */
class RenderScaleAndTextHitTest {

    // ---- maxScaleForPage：記憶體守門 ----

    @Test
    fun budgetBoundsTheBitmapNotTheDimensionAlone() {
        // A4 在 6x 是 3570×5052 px ×4B ≈ 72MB。堆只有 256MB 時單頁預算 21MB，
        // 倍率必須被預算壓低——否則「先分配後 OOM」，而 OOM 是整個 App 死掉。
        val scale = PdfViewModel.maxScaleForPage(
            pageWidthPt = 595f, pageHeightPt = 842f, maxMemoryBytes = 256L * 1024 * 1024
        )
        val bytes = (595f * scale).toInt() * (842f * scale).toInt() * 4L
        assertTrue(
            "單頁 ${bytes} bytes 超過預算",
            bytes <= PdfViewModel.pageBudgetBytes(256L * 1024 * 1024)
        )
    }

    @Test
    fun aHugePageGetsALowerScaleThanAnA4Page() {
        // 海報／藍圖：同樣的堆，紙越大能用的倍率越低。這是預算守門的核心性質。
        val a4 = PdfViewModel.maxScaleForPage(595f, 842f, 1024L * 1024 * 1024)
        val poster = PdfViewModel.maxScaleForPage(2384f, 3370f, 1024L * 1024 * 1024)
        assertTrue("海報倍率 $poster 不該高於 A4 $a4", poster <= a4)
    }

    @Test
    fun scaleNeverDropsBelowTheReadableFloor() {
        // 這裡要的是「上限算得太小時不能真的照著畫」。超大幅面（1405mm 寬的工程圖）
        // 會讓 4096px 的絕對上限壓出 <2 的倍率，而低於 2 倍連 100% 的紙都是糊的——
        // 那比記憶體超一點更糟（超一點有 LruCache 頂著，全糊沒有救）。
        val giant = PdfViewModel.maxScaleForPage(3978f, 5618f, 256L * 1024 * 1024)
        assertEquals(PdfViewModel.MIN_RENDER_SCALE, giant, 0.0001f)
    }

    @Test
    fun degeneratePageSizesDoNotDivideByZero() {
        assertEquals(PdfViewModel.MIN_RENDER_SCALE, PdfViewModel.maxScaleForPage(0f, 0f, 1L shl 30), 0.0001f)
        assertEquals(PdfViewModel.MIN_RENDER_SCALE, PdfViewModel.maxScaleForPage(-5f, 10f, 1L shl 30), 0.0001f)
    }

    // ---- resolveRenderScale：倍率必須跟上縮放 ----

    @Test
    fun renderScaleTracksPaperWidthNotViewportWidth() {
        // 這一條就是整個修改存在的理由：視窗 1600px 不變、docZoom 從 1 到 2，
        // 紙寬變 2 倍，倍率就必須跟著上去——否則 PDF 被拉大而糊，墨卻銳利。
        val at1x = PdfViewModel.resolveRenderScale(1600f, 595f, 6f)
        val at2x = PdfViewModel.resolveRenderScale(3200f, 595f, 6f)
        assertEquals(2f, at2x / at1x, 0.001f)
    }

    @Test
    fun renderScaleStopsAtTheCeilingInsteadOfRunningAway() {
        // 4x 會撞到 6 倍率的天花板（記憶體守門）。撞到之後就不再往上——
        // 「越多越好」在這裡的正確表現是「不再變糊」，不是「把堆吃光」。
        val at4x = PdfViewModel.resolveRenderScale(6400f, 595f, 6f)
        assertEquals(PdfViewModel.MAX_ZOOM_RENDER_SCALE, at4x, 0.0001f)
    }

    @Test
    fun renderScaleIsCappedByTheMemoryCeiling() {
        // 紙再寬也不能無限追：夾在 maxScale 之內，否則一頁就把堆吃掉。
        val wild = PdfViewModel.resolveRenderScale(100000f, 595f, 6f)
        assertEquals(6f, wild, 0.0001f)
    }

    @Test
    fun renderScaleHasAFloorSoTextStaysLegible() {
        assertEquals(PdfViewModel.MIN_RENDER_SCALE, PdfViewModel.resolveRenderScale(10f, 595f, 6f), 0.0001f)
    }

    @Test
    fun degenerateInputsDoNotProduceNaN() {
        // NaN 會一路傳到 Bitmap.createBitmap 然後整頁變黑，不會報錯——最難查的一種。
        for ((w, pt) in listOf(0f to 595f, 1600f to 0f, -1f to -1f)) {
            val s = PdfViewModel.resolveRenderScale(w, pt, 6f)
            assertTrue("得到 $s", s.isFinite() && s >= PdfViewModel.MIN_RENDER_SCALE)
        }
    }

    // ---- 文字命中框：中文點得到 ----

    private fun ann(text: String, fontSize: Float = 16f) =
        TextAnnotationEntity(
            id = "t1", documentUri = "file:///a.pdf", pageIndex = 0,
            text = text, modelX = 100f, modelY = 200f, fontSize = fontSize
        )

    @Test
    fun cjkLabelIsHitAcrossItsWholeWidth() {
        // 這是回報過的現象：「看得見但點不到右半邊」。四個全形字在 16pt 下，
        // 命中框必須覆蓋 4 個 em——舊公式（0.65/字）只有 2.6 個 em，右邊 35% 點不到。
        val rect = textAnnotationHitRect(ann("測試文字"), sx = 1f, sy = 1f)
        val labelWidth = 4f * 16f
        assertTrue(
            "命中框寬 ${rect.width} 不足以覆蓋 $labelWidth 的全形標籤",
            rect.width >= labelWidth
        )
    }

    @Test
    fun mixedScriptUsesTheWidestLine() {
        // 多行取各行最大寬：短行不該讓長行的尾巴點不到。
        val rect = textAnnotationHitRect(ann("ab\n中文"), sx = 1f, sy = 1f)
        val widest = maxOf(2f * 16f * 0.65f, 2f * 16f)
        assertTrue("命中框寬 ${rect.width} < 最寬行 $widest", rect.width >= widest)
    }

@Test
fun theTextPartScalesWithTheCanvas() {
    // 縮放後字本身跟著放大（紙放大時同一個字要真的變大）。
    val small = textAnnotationHitRect(ann("測試"), sx = 1f, sy = 1f)
    val large = textAnnotationHitRect(ann("測試"), sx = 3f, sy = 3f)
    // 扣掉不縮放的墊片後，剩餘寬度正好是 3 倍。
    val labelAt1x = 2f * 16f // 兩個全形字
    assertEquals(
        labelAt1x.toDouble(),
        (small.width - 2 * TEXT_HIT_PAD_PX).toDouble(),
        0.01
    )
    assertEquals(
        (labelAt1x * 3f).toDouble(),
        (large.width - 2 * TEXT_HIT_PAD_PX).toDouble(),
        0.01
    )
}

@Test
fun theTouchPadStaysScreenSpaceAndDoesNotScale() {
    // 墊片是「螢幕上的手指容忍度」。1 畫布 px ＝ 1 螢幕 px，所以它不該跟著縮放長大：
    // 放大 3 倍後觸控目標變 3 倍大，會蓋到鄰近物件、搶走它們的點擊。
    val labelAt1x = 16f * 0.65f // 半形字寬 @16pt
    val labelAt3x = 16f * 3f * 0.65f
    assertEquals(
        "墊片被跟著放大了——觸控目標會蓋到鄰居",
        (labelAt1x + 2 * TEXT_HIT_PAD_PX).toDouble(),
        textAnnotationHitRect(ann("1"), sx = 1f, sy = 1f).width.toDouble(),
        0.01
    )
    assertEquals(
        (labelAt3x + 2 * TEXT_HIT_PAD_PX).toDouble(),
        textAnnotationHitRect(ann("1"), sx = 3f, sy = 3f).width.toDouble(),
        0.01
    )
}

@Test
fun thePadIsSymmetricSoTheFrameMatchesTheTouchTarget() {
    // 框畫在哪、手指要按在哪，必須是同一個矩形——舊碼左 4 右 8 不對稱，
    // 於是選取框比觸控目標左移了，視覺與實際對不上。
    val r = textAnnotationHitRect(ann("測試"), sx = 1f, sy = 1f)
    val leftPad = r.left - 100f
    val rightPad = (100f + 32f) - r.right
    assertEquals(leftPad.toDouble(), rightPad.toDouble(), 0.01)
}

    @Test
    fun multilineHeightGrowsWithLineCount() {
        val one = textAnnotationHitRect(ann("一行"), sx = 1f, sy = 1f)
        val three = textAnnotationHitRect(ann("一\n二\n三"), sx = 1f, sy = 1f)
        assertTrue("三行 ${three.height} 未比一行 ${one.height} 高", three.height > one.height)
    }

    @Test
    fun aNarrowLabelStillGetsATappableWidth() {
        // 單一字元在數學上幾乎沒有寬度，但手指要有東西點：觸控墊片保底。
        val rect = textAnnotationHitRect(ann("1"), sx = 1f, sy = 1f)
        assertTrue("單字命中寬 ${rect.width} 不足以點", rect.width >= TEXT_HIT_PAD_PX)
    }
}