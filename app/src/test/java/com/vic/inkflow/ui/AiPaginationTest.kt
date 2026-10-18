package com.vic.inkflow.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 匯入排版（M3/M5）。`paginateAiBlocks` 現在跟畫面用**同一支折行**，而且除了字型量測
 * （已抽成注入點）之外全是純 Kotlin——所以整個分頁引擎可以在 JVM 單測（以前用
 * `StaticLayout`／`Paint.fontMetrics`，在單測是 stub，根本跑不動）。
 *
 * 釘住三件事：
 * 1. 每個文字段落都帶 `boxWidth`（＝內容寬）→ AI 文字就是一般文字框；
 * 2. 存進去的行**再折一次一模一樣**（冪等）→ 匯入當下的外觀＝之後重排的外觀，
 *    也讓 v31 回填舊資料不會改變外觀；
 * 3. 跨頁切段無漏、無亂序：整份文件的行 ＝ 原文直接折出來的行。
 */
class AiPaginationTest {

    private val modelW = 595f
    private val modelH = 842f
    private val fontSize = 16f

    // 固定量測（ascent/descent/leading）→ 行高 16 = fontSize，與裝置字型無關。
    private val metrics: (Float) -> FloatArray = { floatArrayOf(-12f, 4f, 0f) }
    private val lineH = 16f
    private val descent = 4f
    private val contentW = modelW - AI_MARGIN_H * 2f

    /** 夠長到會跨頁（260pt 高的頁面約 8 行容納量）。 */
    private val LONG_BODY = "中文字元測試內容".repeat(100)

    private fun paginate(body: String, pageH: Float = modelH): List<List<Placed>> =
        paginateAiBlocks(
            blocks = listOf(AiTextBlock(AiTextChunk(id = "c1", title = "t", body = body))),
            rendered = emptyMap(),
            modelW = modelW,
            modelH = pageH,
            fontSize = fontSize,
            fontMetrics = metrics
        )

    private fun textsOf(pages: List<List<Placed>>): List<PlacedText> =
        pages.flatten().filterIsInstance<Placed.T>().map { it.t }

    @Test
    fun everyTextBlockIsABoxOfTheContentWidth() {
        val texts = textsOf(paginate("中文內容"))
        assertEquals(1, texts.size)
        // 兩件事一起釘：分頁輸出＝唯一算法 aiTextContentWidth（VM 也用它寫 boxWidth），
        // 而且那個算法就是「頁寬 − 兩側邊距」。
        assertEquals(modelW - AI_MARGIN_H * 2f, aiTextContentWidth(modelW), 0.001f)
        assertEquals(contentW, texts[0].boxWidth, 0.001f)
        assertEquals(aiTextContentWidth(modelW), texts[0].boxWidth, 0.001f)
    }

    @Test
    fun storedLinesSurviveARewrap() {
        val body = "中文字元測試 ".repeat(40) + "mixed English words here"
        for (t in textsOf(paginate(body))) {
            assertEquals(t.text.split("\n"), wrapTextLines(t.text, t.fontSize, t.boxWidth))
        }
    }

    @Test
    fun longBodySpillsOntoMorePagesWithoutLosingLines() {
        val body = LONG_BODY
        val pages = paginate(body, pageH = 260f)
        assertTrue("應分成多頁（實際 ${pages.size}）", pages.size >= 2)
        val stored = textsOf(pages).flatMap { it.text.split("\n") }
        assertEquals(wrapTextLines(body, fontSize, contentW), stored)
    }

    @Test
    fun textNeverCrossesTheBottomMargin() {
        val pageH = 260f
        val bottomMargin = 64f
        for (t in textsOf(paginate(LONG_BODY, pageH = pageH))) {
            val rows = t.text.split("\n").size
            val bottom = t.modelY + (rows - 1) * lineH + descent
            assertTrue("文字越過下緣：bottom=$bottom > ${pageH - bottomMargin}", bottom <= pageH - bottomMargin + 0.001f)
        }
    }
}
