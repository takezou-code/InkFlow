package com.vic.inkflow.data

import com.vic.inkflow.ui.sliceParagraphAtLines
import com.vic.inkflow.ui.wrapTextLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 文字流的自動重排（Word 式排版）。
 *
 * 這裡釘住的是「使用者按了什麼 → 畫面上什麼會動」：
 *  - 改第一段 → 第一段基線不動，下面的往下推；
 *  - 改短了 → 下面的**往上拉**，不留洞；
 *  - 段落間距沿用改動前的幾何（同段落的續塊 0、不同段落 14）；
 *  - 刪掉中間一段 → 下面的剛好上移「那一段的高度 ＋ 一段間距」（真正收合）。
 *
 * 行高由呼叫端注入，測試用固定值（lineHeight = fontSize、ascent = 0.75 × fontSize），
 * 所以期望值都是整數，失敗時看得出差了幾行。
 */
class TextFlowLayoutTest {

    private val metrics: (Float) -> TextFlowLineMetrics = { size ->
        TextFlowLineMetrics(lineHeight = size, ascent = size * 0.75f)
    }

    /** 一行 10 個全形字（10 × 16 = 160 = boxWidth）→ [rows] 行剛好折成 [rows] 行。 */
    private fun rows(rows: Int): String = "字".repeat(rows * 10)

    private fun box(id: String, text: String, modelY: Float) =
        TextFlowBox(id = id, text = text, fontSize = 16f, boxWidth = 160f, modelY = modelY)

    @Test
    fun growthPushesTheFollowingBoxesDownAndKeepsTheAnchor() {
        // A 一行、B 緊接（0 間距）。A 多兩行 → B 正好下移兩行。
        val current = listOf(box("a", rows(1), 100f), box("b", rows(1), 116f))
        val updated = listOf(box("a", rows(3), 100f), box("b", rows(1), 116f))

        val y = reflowTextFlowY(current, updated, metrics)

        assertEquals(100f, y[0], "第一格是錨點：使用者放的位置不能自己跑掉")
        assertEquals(148f, y[1], "A 高了 2 行（32pt）→ B 的基線也要 +32")
    }

    @Test
    fun shrinkingPullsTheFollowingBoxesUpInsteadOfLeavingAHole() {
        // A 三行、B 在它下面留 4pt。A 縮回一行 → B 上移 32，間距仍是 4。
        val current = listOf(box("a", rows(3), 100f), box("b", rows(1), 152f))
        val updated = listOf(box("a", rows(1), 100f), box("b", rows(1), 152f))

        val y = reflowTextFlowY(current, updated, metrics)

        assertEquals(100f, y[0])
        assertEquals(120f, y[1])
        // 間距（B 的 top − A 的 bottom）仍然是 4：縮短不該把段落黏起來，也不該留下空洞。
        assertEquals(4f, (y[1] - 12f) - (88f + 16f))
    }

    @Test
    fun theParagraphGapIsKeptWhenABoxGrows() {
        // A 一行、B 與 A 相距 14pt（不同段落）。A 變兩行 → B 下移 16（只增加一行），間距仍 14。
        val current = listOf(box("a", rows(1), 100f), box("b", rows(1), 130f))
        val updated = listOf(box("a", rows(2), 100f), box("b", rows(1), 130f))

        val y = reflowTextFlowY(current, updated, metrics)

        assertEquals(146f, y[1])
        assertEquals(14f, (y[1] - 12f) - (88f + 32f), "段落間距必須原封不動")
    }

    @Test
    fun deletingTheMiddleBoxClosesTheSpaceItOccupied() {
        // A(100) B(130) C(160)，每格一行、段落間距 14。
        // 刪掉 B 之後 C 應該上移到 130（= B 的高度 16 + B 上方那 14 的間距）。
        val current = listOf(
            box("a", rows(1), 100f),
            box("b", rows(1), 130f),
            box("c", rows(1), 160f)
        )
        val kept = listOf(current[0], current[2])

        val y = reflowTextFlowY(current, kept, metrics) { i -> if (i == 0) 0 else 2 }

        assertEquals(100f, y[0])
        assertEquals(130f, y[1], "下面的字要真的收回來，不能只是略過被刪的那格")
    }

    @Test
    fun unchangedContentProducesTheSameBaselines() {
        val current = listOf(
            box("a", rows(2), 100f),
            box("b", rows(1), 146f),
            box("c", rows(3), 176f)
        )
        assertEquals(
            current.map { it.modelY },
            reflowTextFlowY(current, current, metrics)
        )
    }

    @Test
    fun emptyInputsDoNotCrash() {
        assertTrue(reflowTextFlowY(emptyList(), emptyList(), metrics).isEmpty())
        // 還沒有 current 幾何（例如第一次匯入）→ 維持呼叫端給的位置。
        val fresh = listOf(box("a", rows(1), 50f), box("b", rows(1), 70f))
        assertEquals(listOf(50f, 70f), reflowTextFlowY(emptyList(), fresh, metrics))
    }

    @Test
    fun anEmptyParagraphStillCountsAsOneLine() {
        assertEquals(1, textFlowLineCount("", 16f, 160f))
        assertEquals(16f, textFlowHeight("", 16f, 160f, 16f))
        assertEquals(3, textFlowLineCount(rows(3), 16f, 160f))
    }

    @Test
    fun slicingCutsAtAWrapBoundaryAndLosesNoCharacter() {
        val paragraph = "字".repeat(25) // 10/10/5 → 三行

        val (head, tail) = sliceParagraphAtLines(paragraph, 16f, 160f, 1)

        assertEquals(10, head.length, "切點要落在折行邊界，不能把字切一半")
        assertEquals(paragraph, head + tail, "兩半接起來必須還是原文")
        assertEquals(1, wrapTextLines(head, 16f, 160f).size)
        assertEquals(3, wrapTextLines(paragraph, 16f, 160f).size)
    }

    @Test
    fun slicingDegradesCleanlyWhenEverythingOrNothingFits() {
        val paragraph = "字".repeat(25)
        assertEquals(paragraph to "", sliceParagraphAtLines(paragraph, 16f, 160f, 99))
        assertEquals("" to paragraph, sliceParagraphAtLines(paragraph, 16f, 160f, 0))
    }
}
