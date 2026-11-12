package com.vic.inkflow.data

import com.vic.inkflow.ui.wrapTextLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B3: the AI import pipeline now lives in `:shared`, so it runs on both the
 * tablet and the desktop. These tests pin the three things the two apps must
 * agree on — otherwise the same note looks different on each:
 *
 *  1. the wrap width stored with every text block is the one the layout used;
 *  2. the stored lines survive being wrapped again (idempotent), because the
 *     renderer wraps a second time;
 *  3. the page bottom is measured the way it is drawn, so "continue on this
 *     page" never writes on top of existing ink.
 */
class AiTextLayoutTest {

    private val modelW = 595f
    private val modelH = 842f

    /** Fixed metrics (ascent/descent/leading) → 16pt line height, independent of any device. */
    private val fontMetrics: (Float) -> FloatArray = { floatArrayOf(-12f, 4f, 0f) }
    private val lineH = 16f
    private val descent = 4f

    @Test
    fun proseAndDisplayMathBecomeSeparateBlocks() {
        val blocks = splitAiBlocks("前言\n\n\$\$x^2\$\$\n\n後記")
        assertTrue(blocks.any { it is AiMathBlock }, "display math should be its own block")
        assertTrue(blocks.any { it is AiTextBlock }, "prose should stay text")
    }

    @Test
    fun everyTextBlockCarriesTheContentWidth() {
        val pages = paginateAiBlocks(
            blocks = listOf(AiTextBlock(AiTextChunk("c1", "t", "中文內容"))),
            rendered = emptyMap(),
            modelW = modelW,
            modelH = modelH,
            fontMetrics = fontMetrics
        )
        val placed = pages.flatten().filterIsInstance<Placed.T>().map { it.t }
        assertEquals(1, placed.size)
        assertEquals(aiTextContentWidth(modelW), placed[0].boxWidth)
    }

    @Test
    fun storedTextRewrapsToTheSameLines() {
        // 存的是**原文**（自然段落），不是折好的行：畫面會再折一次，要求的是冪等
        //（折第二次 == 折第一次），而不是「存進去的字串等於折好的行」。
        // 這一條同時守住「拉寬框會真的重排」——硬換行一旦寫進 DB，折行就不跟框寬走了。
        val body = "中文字元測試 ".repeat(40) + "mixed English words here"
        val pages = paginateAiBlocks(
            blocks = listOf(AiTextBlock(AiTextChunk("c1", "t", body))),
            rendered = emptyMap(),
            modelW = modelW,
            modelH = 260f,
            fontMetrics = fontMetrics
        )
        for (text in pages.flatten().filterIsInstance<Placed.T>().map { it.t }) {
            val once = wrapTextLines(text.text, text.fontSize, text.boxWidth)
            val twice = wrapTextLines(once.joinToString("\n"), text.fontSize, text.boxWidth)
            assertEquals(once, twice)
        }
    }

    @Test
    fun theStoredTextIsTheOriginalParagraphNotPreWrappedLines() {
        // 一整段放得下 → 存的就是原文，一個字都不少、也沒有被塞進硬換行。
        val body = "這是一段很長的中文內容，用來確認匯入後存的是原文。".repeat(6)
        val pages = paginateAiBlocks(
            blocks = listOf(AiTextBlock(AiTextChunk("c1", "t", body))),
            rendered = emptyMap(),
            modelW = modelW,
            modelH = modelH,
            fontMetrics = fontMetrics
        )
        val placed = pages.flatten().filterIsInstance<Placed.T>().map { it.t }
        assertEquals(1, placed.size)
        assertEquals(body, placed[0].text)
        assertTrue(
            wrapTextLines(placed[0].text, placed[0].fontSize, placed[0].boxWidth).size > 1,
            "這一段應該會折行（否則這條測試證明不了什麼）"
        )
    }

    @Test
    fun startsAtTheGivenCursorWhenContinuingAPage() {
        val notes = listOf(AiTextBlock(AiTextChunk("c1", "t", "接續")))
        val topPage = paginateAiBlocks(notes, emptyMap(), modelW, modelH, fontMetrics = fontMetrics)
        val continued = paginateAiBlocks(
            notes, emptyMap(), modelW, modelH,
            startTop = 300f, fontMetrics = fontMetrics
        )
        val firstTop = topPage.first().filterIsInstance<Placed.T>().first().t.modelY
        val continuedTop = continued.first().filterIsInstance<Placed.T>().first().t.modelY
        assertTrue(continuedTop > firstTop, "continued note must start below the existing content")
    }

    @Test
    fun measuresTheBottomOfStrokesTextAndImages() {
        val strokes = listOf(stroke(boundsBottom = 300f))
        val texts = listOf(text(modelY = 100f, body = "a\nb\nc"))
        val images = listOf(image(modelY = 200f, height = 120f))
        // stroke 300, text 136, image 200+120=320 → the bottom is the image
        assertEquals(320f, measureContentBottom(strokes, texts, images, { lineH to descent }))
        assertEquals(136f, measureContentBottom(emptyList(), texts, emptyList(), { lineH to descent }))
        assertNull(
            measureContentBottom(
                emptyList(),
                listOf(text(100f, "hi", stamp = true)),
                emptyList(),
                metrics = { lineH to descent }
            )
        )
    }

    @Test
    fun continuesOnlyWhenTwoLinesFit() {
        assertEquals(308f, continueTop(300f, modelH, lineH)!!)
        assertEquals(64f, continueTop(10f, modelH, lineH)!!)
        assertNull(continueTop(750f, modelH, lineH))
    }

    private fun stroke(boundsBottom: Float): Stroke = Stroke(
        id = "s",
        documentUri = "doc",
        pageIndex = 0,
        color = 0,
        strokeWidth = 1f,
        boundsLeft = 0f,
        boundsTop = boundsBottom - 10f,
        boundsRight = 10f,
        boundsBottom = boundsBottom
    )

    private fun text(modelY: Float, body: String, stamp: Boolean = false): TextAnnotation = TextAnnotation(
        id = "t",
        documentUri = "doc",
        pageIndex = 0,
        text = body,
        modelX = 0f,
        modelY = modelY,
        fontSize = 16f,
        isStamp = stamp
    )

    private fun image(modelY: Float, height: Float): ImageAnnotation = ImageAnnotation(
        id = "i",
        documentUri = "doc",
        pageIndex = 0,
        uri = "file:///x.png",
        modelX = 0f,
        modelY = modelY,
        modelWidth = 100f,
        modelHeight = height
    )
}
