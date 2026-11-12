package com.vic.inkflow.data

import com.vic.inkflow.ui.wrapTextLines
import kotlin.math.max

/**
 * 文字流（AI 匯入的整段文字）的**自動重排**——Word 式排版的部分。
 *
 * ## 為什麼需要這一層
 *
 * AI 匯入把一段回答切成好幾格（一個段落／一個續塊／一頁的份量），每格是一個普通的
 * `TextAnnotation`（有 `boxWidth`）。問題是它們的位置在**匯入當下**就算死了：
 * 使用者把某一段改長、或把框拉窄，那一格會變高，**下面的字不會動** → 疊字；
 * 改短則留下一個洞。使用者要的不是「一格一格的文字框」，而是「一份會自己排版的文字」。
 *
 * 這裡只做一件事：**保持每格「之間的間距」不變，重算它們的第一行基線**。
 *  - 第一格是錨點（使用者放的位置不動），其餘由上一格的實際高度往後推；
 *  - 間距取自**改動前**的幾何，所以「同段落的續塊 = 0 間距、不同段落 = 14pt」這些
 *    既有語意會原封不動保留下來；
 *  - 內容變短時上面的格子會把下面的**往上拉**（不是只往下推），改完不會留洞。
 *
 * 純函數、不碰 DB、不碰平台量測（行高由呼叫端注入），所以兩端與 JVM 單測都能跑。
 */
data class TextFlowBox(
    val id: String,
    val text: String,
    val fontSize: Float,
    val boxWidth: Float,
    /** 第一行基線（不是左上角）——與 [TextAnnotation.modelY] 同語意。 */
    val modelY: Float
)

/** 一行的垂直度量（model pt）：[lineHeight] = -ascent + descent + leading。 */
data class TextFlowLineMetrics(
    val lineHeight: Float,
    /** 第一行基線到文字頂端的距離（正數）。 */
    val ascent: Float
)

/** 這一格折行後佔幾行。空字串算一行（空段落＝空行，仍是排版的一部分）。 */
fun textFlowLineCount(text: String, fontSize: Float, boxWidth: Float): Int =
    wrapTextLines(text, fontSize, boxWidth).size.coerceAtLeast(1)

/** 這一格的實際高度（model pt）。 */
fun textFlowHeight(
    text: String,
    fontSize: Float,
    boxWidth: Float,
    lineHeight: Float
): Float = textFlowLineCount(text, fontSize, boxWidth) * lineHeight

/**
 * 重算一條文字流每一格的第一行基線。
 *
 * @param current 改動前的完整幾何（順序由上而下）。只用來量**格子之間的間距**與錨點。
 * @param updated 改動後的內容（順序同上；可少於 [current]，例如刪掉一格）。
 * @param metricsOf 字級 → 行高／ascent（由平台注入：平板用 `Paint.fontMetrics`）。
 * @param sourceIndex `updated[i]` 對應 [current] 的哪一格（刪除後會跳號；預設逐一對應）。
 * @return 與 [updated] 同長度的新基線；第一格固定不動。
 */
fun reflowTextFlowY(
    current: List<TextFlowBox>,
    updated: List<TextFlowBox>,
    metricsOf: (Float) -> TextFlowLineMetrics,
    sourceIndex: (Int) -> Int = { it }
): List<Float> {
    if (updated.isEmpty()) return emptyList()
    if (current.isEmpty()) return updated.map { it.modelY }

    val curMetrics = current.map { metricsOf(it.fontSize) }
    val curTop = current.mapIndexed { i, b -> b.modelY - curMetrics[i].ascent }
    val curHeight = current.mapIndexed { i, b ->
        textFlowHeight(b.text, b.fontSize, b.boxWidth, curMetrics[i].lineHeight)
    }

    // 每一格「上方」原本留了多少空白——量自它**自己的前一個鄰居**，不是量自 kept 的前一格。
    //
    // 為什麼不能用 kept 的前一格：刪掉中間一段時，kept 的前一格會跳過被刪的那格，量到的
    // 間距 = 被刪那格的高度 ＋ 兩段間距 → 下面的字原地不動（等於沒收合）。用「原本的鄰居」
    // 量，A→B→C 刪掉 B 時，C 沿用 B→C 的 14pt，剛好上移「B 的高度 ＋ 一段間距」。
    val gapAbove = FloatArray(current.size)
    for (i in 1 until current.size) {
        gapAbove[i] = max(0f, curTop[i] - (curTop[i - 1] + curHeight[i - 1]))
    }

    val anchor = sourceIndex(0).coerceIn(0, current.lastIndex)
    val out = FloatArray(updated.size)
    out[0] = current[anchor].modelY
    var top = curTop[anchor]

    for (i in 1 until updated.size) {
        val src = sourceIndex(i).coerceIn(0, current.lastIndex)
        // 沿用改動前的段落間距：某一格變高不會吃掉間距，也不會把原本分開的段落黏起來。
        val gap = gapAbove[src]
        val prevMetrics = metricsOf(updated[i - 1].fontSize)
        top += textFlowHeight(
            updated[i - 1].text,
            updated[i - 1].fontSize,
            updated[i - 1].boxWidth,
            prevMetrics.lineHeight
        ) + gap
        out[i] = top + metricsOf(updated[i].fontSize).ascent
    }
    return out.toList()
}
