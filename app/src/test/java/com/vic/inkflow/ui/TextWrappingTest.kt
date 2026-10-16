package com.vic.inkflow.ui

import com.vic.inkflow.data.TextAnnotationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文字框折行與對齊（tablet DB v30 的 boxWidth / alignment）。
 *
 * 釘住的是「折行只有一份真相」這件事：渲染、命中框、套索、縮圖、PDF 匯出
 * 全部呼叫同一個 [wrapTextLines]，量寬走同一支 [measureTextWidth]（全形感知）。
 * 一旦有人另寫一份折法（例如猜「字數 × 0.65」），中文標籤就會框不住——
 * 這個坑 SelectionGeometry 的註解已經記過一次。
 */
class TextWrappingTest {

    private fun ann(
        text: String,
        modelX: Float = 0f,
        modelY: Float = 100f,
        fontSize: Float = 10f,
        boxWidth: Float? = null,
        alignment: Int = TEXT_ALIGN_LEFT
    ) = TextAnnotationEntity(
        documentUri = "u", pageIndex = 0, text = text,
        modelX = modelX, modelY = modelY, fontSize = fontSize,
        boxWidth = boxWidth, alignment = alignment
    )

    // ---- wrapTextLines ----

    @Test
    fun noBoxMeansOnlyHardNewlines() {
        assertEquals(listOf("abc"), wrapTextLines("abc", 10f, 0f))
        assertEquals(listOf("a", "b"), wrapTextLines("a\nb", 10f, -5f))
    }

    @Test
    fun emptyTextWrapsToNothing() {
        assertTrue(wrapTextLines("", 10f, 100f).isEmpty())
    }

    @Test
    fun cjkBreaksPerCharacterAtTheBoxWidth() {
        // 全形字 @10pt = 10pt 寬；框 50 → 每行 5 字。
        val lines = wrapTextLines("一二三四五六七八九十", 10f, 50f)
        assertEquals(listOf("一二三四五", "六七八九十"), lines)
    }

    @Test
    fun latinWordsAreNotSplitMidWord() {
        // hello=32.5, world=32.5 @10pt；框 45 放不下兩個 → 整字搬行。
        assertEquals(listOf("hello", "world"), wrapTextLines("hello world", 10f, 45f))
    }

    @Test
    fun anOversizedWordIsHardSplit() {
        // 10 個半形字 @6.5 = 65pt，框 30 → 每行 4 字。
        assertEquals(listOf("abcd", "efgh", "ij"), wrapTextLines("abcdefghij", 10f, 30f))
    }

    @Test
    fun blankLineSurvivesAsItsOwnRow() {
        assertEquals(listOf("aa", "", "bb"), wrapTextLines("aa\n\nbb", 10f, 100f))
    }

    @Test
    fun trailingWhitespaceIsTrimmedPerLine() {
        assertEquals(listOf("aa", "bb"), wrapTextLines("aa bb", 10f, 22f))
    }

    // ---- textAlignOffsetModel ----

    @Test
    fun alignmentShiftsInsideTheBox() {
        assertEquals(0f, textAlignOffsetModel(20f, 100f, TEXT_ALIGN_LEFT), 0.001f)
        assertEquals(40f, textAlignOffsetModel(20f, 100f, TEXT_ALIGN_CENTER), 0.001f)
        assertEquals(80f, textAlignOffsetModel(20f, 100f, TEXT_ALIGN_RIGHT), 0.001f)
    }

    @Test
    fun alignmentNeverPushesTextLeftOfTheBox() {
        // 行比框寬（理論上折行後不會發生）時夾成 0，不讓字跑到框外左邊。
        assertEquals(0f, textAlignOffsetModel(120f, 100f, TEXT_ALIGN_CENTER), 0.001f)
        assertEquals(0f, textAlignOffsetModel(120f, 100f, TEXT_ALIGN_RIGHT), 0.001f)
    }

    // ---- 幾何：命中框/套索框跟著框寬 ----

    @Test
    fun boxHitRectSpansTheWholeBox() {
        val r = textAnnotationHitRect(ann("hi", modelX = 10f, boxWidth = 200f), 1f, 1f)
        assertEquals(10f - TEXT_HIT_PAD_PX, r.left, 0.001f)
        assertEquals(10f + 200f + TEXT_HIT_PAD_PX, r.right, 0.001f)
    }

    @Test
    fun legacyHitRectHugsTheContent() {
        // boxWidth null（舊資料）：寬度貼合最寬行（h=6.5×2=13），行為不變。
        val r = textAnnotationHitRect(ann("hi"), 1f, 1f)
        assertEquals(13f + TEXT_HIT_PAD_PX, r.right, 0.001f)
    }

    @Test
    fun boxBoundsUseTheBoxWidth() {
        val b = textEstimatedBounds(ann("hi", modelX = 5f, boxWidth = 120f))
        assertEquals(5f, b.left, 0.001f)
        assertEquals(125f, b.right, 0.001f)
    }

    @Test
    fun wrappedLinesGrowTheHitRectHeight() {
        // 10 個全形字 @10pt、框 50 → 2 行；高度含第二行的 1.2 行距。
        val r = textAnnotationHitRect(ann("一二三四五六七八九十", modelY = 100f, boxWidth = 50f), 1f, 1f)
        val expectedHeight = 10f + 1 * 10f * 1.2f
        assertEquals(100f - 10f - TEXT_HIT_PAD_PX, r.top, 0.001f)
        assertEquals(100f - 10f + expectedHeight + TEXT_HIT_PAD_PX, r.bottom, 0.001f)
    }

    // ---- 內容命中框：選字貼合畫出來的字，不佔滿整個框 ----

    @Test
    fun contentRectHugsTheTextNotTheBox() {
        // 左對齊：內容框＝文字（13pt 寬），不是 200pt 的框。
        val r = textAnnotationContentRect(ann("hi", modelX = 10f, boxWidth = 200f), 1f, 1f)
        assertEquals(10f - TEXT_HIT_PAD_PX, r.left, 0.001f)
        assertEquals(10f + 13f + TEXT_HIT_PAD_PX, r.right, 0.001f)
    }

    @Test
    fun contentRectFollowsCentreAlignment() {
        // 置中位移 (200-13)/2 = 93.5：內容框跟著字走。
        val r = textAnnotationContentRect(
            ann("hi", modelX = 10f, boxWidth = 200f, alignment = TEXT_ALIGN_CENTER), 1f, 1f
        )
        assertEquals(10f + 93.5f - TEXT_HIT_PAD_PX, r.left, 0.001f)
        assertEquals(10f + 93.5f + 13f + TEXT_HIT_PAD_PX, r.right, 0.001f)
    }

    @Test
    fun contentRectMatchesLegacyForUnboxedText() {
        // 舊資料（boxWidth null）＝原本的貼合行為，一個位元都不能變。
        val r = textAnnotationContentRect(ann("hi"), 1f, 1f)
        assertEquals(13f + TEXT_HIT_PAD_PX, r.right, 0.001f)
    }
}
