package com.vic.inkflow.data

import com.vic.inkflow.ui.wrapTextLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AI 匯入的**文字結構**：段落、換行、清單、標題、code fence。
 *
 * 為什麼要有這一組：使用者回報「AI 有換行的地方，匯入後沒有換行，要自己重排格式」。
 * 根因有兩個，一個在 collect（`clone.innerText` 對離線節點沒有換行，見 `AiDomTextTest`），
 * 一個在這裡：舊版 `splitProse` 把 `\n` 當斷句點、`trim()` 掉之後直接串接，
 * 超過 300 字的段落一定被黏成一大段（英文字還會黏在一起）。
 *
 * 這些測試釘住的是「結構不變量」：換行不消失、字不黏、標題不留 `#`、
 * code fence 不留反引號。
 */
class AiTextStructureTest {

    private fun bodies(raw: String): List<String> =
        splitAiBlocks(raw).filterIsInstance<AiTextBlock>().map { it.chunk.body }

    @Test
    fun keepsSingleNewlinesInsideAParagraph() {
        val b = bodies("第一行\n第二行")
        assertEquals(listOf("第一行\n第二行"), b)
    }

    @Test
    fun keepsEveryListLineWhenTheParagraphIsLong() {
        // 20 行 × 8 字＝遠超 AI_CHUNK_MAX_CHARS（300），舊版會在這裡把行黏掉。
        val lines = (1..20).map { "第${it}項說明文字" }
        val got = splitAiBlocks(lines.joinToString("\n"))
            .filterIsInstance<AiTextBlock>()
            .flatMap { it.chunk.body.split("\n") }
            .filter { it.isNotBlank() }
        assertEquals(lines, got)
    }

    @Test
    fun neverCutsOrGluesWordsWhenHardCutting() {
        // 沒有句讀的長串：只能在空白處切，且每一塊的每個 token 都必須是完整單字。
        val words = (1..80).map { "word$it" }
        val bodies = bodies(words.joinToString(" "))
        assertTrue(bodies.all { it.length <= AI_CHUNK_MAX_CHARS }, "每一塊都不超過上限")
        for (b in bodies) {
            for (tok in b.split(" ").filter { it.isNotBlank() }) {
                assertTrue(tok.matches(Regex("word\\d+")), "切到單字中間或黏字：$tok")
            }
        }
        assertTrue(bodies.size >= 2, "應該切成多塊（實際 ${bodies.size}）")
    }

    @Test
    fun splitsEnglishAtSentenceEndNotMidWord() {
        val text = "First sentence. " + "Second sentence. ".repeat(30)
        val bodies = bodies(text)
        assertTrue(bodies.all { it.length <= AI_CHUNK_MAX_CHARS }, "每一塊都不超過上限")
        assertTrue(
            bodies.dropLast(1).all { it.trimEnd().endsWith(".") },
            "非最後一塊應停在句點：${bodies.take(2)}"
        )
    }

    @Test
    fun markdownHeadingBecomesLargerTextWithoutHashes() {
        val blocks = splitAiBlocks("## 第二章 極限")
        val b = blocks.single() as AiTextBlock
        assertEquals("第二章 極限", b.chunk.body)
        assertTrue(b.chunk.fontSize > DEFAULT_AI_FONT_SIZE, "標題要比正文大（實際 ${b.chunk.fontSize}）")
    }

    @Test
    fun bulletsBecomeDots() {
        assertEquals(listOf("• 甲\n• 乙"), bodies("- 甲\n- 乙"))
    }

    @Test
    fun codeFenceMarkersAreStripped() {
        assertEquals(listOf("val x = 1"), bodies("```kotlin\nval x = 1\n```"))
    }

    @Test
    fun blankLinesSurvivePagination() {
        val pages = paginateAiBlocks(
            blocks = listOf(AiTextBlock(AiTextChunk("c1", "t", "甲\n\n乙"))),
            rendered = emptyMap(),
            modelW = 595f,
            modelH = 842f,
            fontMetrics = { floatArrayOf(-12f, 4f, 0f) }
        )
        val t = pages.flatten().filterIsInstance<Placed.T>().single().t
        assertEquals("甲\n\n乙", t.text)
    }

    /** 量到的 `modelY` 是首行 baseline；行高固定 16（metrics 注入）。 */
    private fun place(blocks: List<AiBlock>, blockGap: Float = 14f): List<PlacedText> =
        paginateAiBlocks(
            blocks = blocks,
            rendered = emptyMap(),
            modelW = 595f,
            modelH = 842f,
            blockGap = blockGap,
            fontMetrics = { floatArrayOf(-12f, 4f, 0f) }
        ).flatten().filterIsInstance<Placed.T>().map { it.t }

    @Test
    fun splitParagraphIsMarkedAsContinuation() {
        val blocks = splitAiBlocks("字".repeat(AI_CHUNK_MAX_CHARS + 60))
        val texts = blocks.filterIsInstance<AiTextBlock>()
        assertEquals(2, texts.size, "應該切成兩塊（實際 ${texts.size}）")
        assertTrue(!texts[0].chunk.continuesPrevious, "第一塊是新段落")
        assertTrue(texts[1].chunk.continuesPrevious, "第二塊是同一段落的續塊")
    }

    @Test
    fun continuationChunkGetsNoParagraphGap() {
        val blocks = splitAiBlocks("字".repeat(AI_CHUNK_MAX_CHARS + 60))
        val placed = place(blocks)
        assertEquals(2, placed.size, "兩塊都應落在同一頁（實際 ${placed.size}）")
        val w = aiTextContentWidth(595f)
        val lines1 = wrapTextLines(placed[0].text, placed[0].fontSize, w).size
        assertEquals(
            lines1 * 16f,
            placed[1].modelY - placed[0].modelY,
            0.01f,
            "續塊要接著排（若補了段落間距，一段會看成兩段）"
        )
    }

    @Test
    fun separateParagraphsStillGetTheGap() {
        val placed = place(
            listOf(
                AiTextBlock(AiTextChunk("c1", "t", "甲")),
                AiTextBlock(AiTextChunk("c2", "t", "乙"))
            )
        )
        assertEquals(2, placed.size)
        assertEquals(16f + 14f, placed[1].modelY - placed[0].modelY, 0.01f, "不同段落要保留段落間距")
    }
}
