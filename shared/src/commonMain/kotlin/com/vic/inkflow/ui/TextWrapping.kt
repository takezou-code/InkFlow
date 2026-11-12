package com.vic.inkflow.ui

// ── 文字框折行與對齊（純函數，可單測）──────────────────────────────────────
//
// 文字框化（DB v30 的 boxWidth / alignment）把「一行到底」變成「框內折行」。
// 這裡刻意留在純 Kotlin：量寬走 measureTextWidth（全形感知，與命中框同一口徑），
// 不用 StaticLayout / Paint——那些要 Android framework，單元測試跑不動，而且
// 會跟命中框的量法分岔成兩份真相（SelectionGeometry 的註解已經記過這個坑）。
//
// 折行是**純量測**、與繪製縮放無關：boxWidth 與 fontSize 都是 model pt，
// 平板上畫的是 (fontSize × sy)，比例不變 → 在 model 空間折出來的行，畫出來也一樣。

/** 新建文字框距離頁面右緣的留白（model pt）。 */
const val TEXT_BOX_DEFAULT_MARGIN = 24f

/** 文字框最小寬（model pt）：小到不會退化成每個字一行。 */
const val TEXT_BOX_MIN_WIDTH = 40f

/** 對齊：0=左 1=中 2=右（與 TextAnnotationEntity.alignment 同一組常數）。 */
const val TEXT_ALIGN_LEFT = 0
const val TEXT_ALIGN_CENTER = 1
const val TEXT_ALIGN_RIGHT = 2

/**
 * 依 [boxWidth]（model pt）把 [text] 折成多行。
 *
 * - [boxWidth] <= 0 或 [fontSize] <= 0 → 不折行，只按 `\n` 切（舊資料的行為）。
 * - `\n` 永遠是硬換行（段落）。
 * - 折行點：CJK 逐字可斷；連續的非空白非 CJK（英數/符號）視為一個 word，整塊搬行，
 *   除非單一 word 比整個框還寬才逐字硬切。
 * - 回傳至少一行；空字串回傳空清單。
 */
fun wrapTextLines(
    text: String,
    fontSize: Float,
    boxWidth: Float,
    measurer: ((String, Float) -> Float)? = null
): List<String> {
    if (text.isEmpty()) return emptyList()
    if (boxWidth <= 0f || fontSize <= 0f) return text.split("\n")
    val out = ArrayList<String>()
    for (paragraph in text.split("\n")) out.addAll(wrapParagraph(paragraph, fontSize, boxWidth, measurer))
    return out
}

private fun wrapParagraph(
    paragraph: String,
    fontSize: Float,
    boxWidth: Float,
    measurer: ((String, Float) -> Float)?
): List<String> = wrapParagraphWithEnds(paragraph, fontSize, boxWidth, measurer).map { it.first }

/**
 * 單一段落（不含 `\n`）折行，並回報**每一行在原字串的結束索引**。
 *
 * 為什麼要索引：AI 分頁若把段落切到兩頁，切點必須落在**折行邊界**（不能把字切一半），
 * 但存進 DB 的必須是**原文**而不是折好的行。存折好的行會有兩個後遺症：
 *  1. 拉寬框時那些硬換行不會消失 → 文字不會重新填滿，看起來像「沒重排」；
 *  2. 編輯時每個折行點都變成真正的 Enter，跟 Word 的手感相反。
 * 有了索引就能「切在折行邊界、存回原文」。
 *
 * [wrapTextLines] 是這支的投影（丟掉索引），兩者永遠同一套斷行規則。
 */
fun wrapParagraphWithEnds(
    paragraph: String,
    fontSize: Float,
    boxWidth: Float,
    measurer: ((String, Float) -> Float)? = null
): List<Pair<String, Int>> {
    if (paragraph.isEmpty()) return listOf("" to 0)
    val out = ArrayList<Pair<String, Int>>()
    val line = StringBuilder()
    var lineW = 0f
    // 已處理完的字元數（含行尾與行首空白）。切段時用它取原文前綴，空白留給 trim 處理。
    var consumed = 0
    val measure: (String, Float) -> Float = measurer ?: { t, s -> measureTextWidth(t, s) }

    fun flushLine() {
        out.add(line.toString().trimEnd() to consumed)
        line.setLength(0)
        lineW = 0f
    }

    for (tok in tokenizeForWrap(paragraph)) {
        if (tok.isBlank()) {
            // 空白不當行首；行內空白保留（字本身有寬度）
            if (line.isNotEmpty()) {
                line.append(tok)
                lineW += measure(tok, fontSize)
            }
            consumed += tok.length
            continue
        }
        val w = measure(tok, fontSize)
        // 先結算上一行、再計入這個 token：否則行尾索引會多吃一個字。
        if (lineW > 0f && lineW + w > boxWidth) flushLine()
        if (w > boxWidth) {
            // 單一 word 比框還寬 → 逐字硬切
            for (ch in tok) {
                val cw = measure(ch.toString(), fontSize)
                if (lineW > 0f && lineW + cw > boxWidth) flushLine()
                line.append(ch)
                lineW += cw
                consumed++
            }
        } else {
            line.append(tok)
            lineW += w
            consumed += tok.length
        }
    }
    out.add(line.toString().trimEnd() to consumed)
    return out
}

/**
 * 把一段原文切成「前 [lines] 行」與「其餘」——切點落在折行邊界，兩塊都還是原文。
 *
 * 用於「這一段比本頁剩餘空間高」：前半留在本頁、後半下一頁接著排，而存進 DB 的
 * 是自然段落（不是折好的行），所以之後拉寬／縮窄框都會重新排版。
 * [lines] <= 0 或整段都放得下時，另一塊是空字串。
 */
fun sliceParagraphAtLines(
    paragraph: String,
    fontSize: Float,
    boxWidth: Float,
    lines: Int,
    measurer: ((String, Float) -> Float)? = null
): Pair<String, String> {
    if (lines <= 0) return "" to paragraph.trimStart()
    val ends = wrapParagraphWithEnds(paragraph, fontSize, boxWidth, measurer)
    if (lines >= ends.size) return paragraph.trimEnd() to ""
    val cut = ends[lines - 1].second
    return paragraph.substring(0, cut).trimEnd() to paragraph.substring(cut).trimStart()
}

/**
 * 斷行單位：CJK 每字一個、連續非空白非 CJK 視為一個 word、空白各自成 token。
 * 這個切法讓中文能逐字斷、英文不會被從中間切開。
 */
private fun tokenizeForWrap(text: String): List<String> {
    val tokens = ArrayList<String>()
    val word = StringBuilder()
    fun flushWord() {
        if (word.isNotEmpty()) {
            tokens.add(word.toString())
            word.setLength(0)
        }
    }
    for (ch in text) {
        when {
            ch.isWhitespace() -> { flushWord(); tokens.add(ch.toString()) }
            isWideChar(ch) -> { flushWord(); tokens.add(ch.toString()) }
            else -> word.append(ch)
        }
    }
    flushWord()
    return tokens
}

/**
 * 某一行在框內的水平偏移（model pt）。
 * 框比行寬時才有作用；行比框寬（理論上折行後不會發生）夾成 0，不讓文字跑到框左邊外。
 */
fun textAlignOffsetModel(lineWidth: Float, boxWidth: Float, alignment: Int): Float = when (alignment) {
    TEXT_ALIGN_CENTER -> ((boxWidth - lineWidth) / 2f).coerceAtLeast(0f)
    TEXT_ALIGN_RIGHT -> (boxWidth - lineWidth).coerceAtLeast(0f)
    else -> 0f
}

/**
 * 「貼合內容」的框寬（model pt）：舊文字（`boxWidth == null`）要轉成文字框時，給這個寬度
 * **保證不會把現有的行重折**。
 *
 * 取的是「現有每一個 `\n` 行，用 [measureTextWidth] 量出來的最大值」。因為 [wrapTextLines]
 * 折行用的就是同一支量寬，每一行的寬度都 ≤ 這個值 → 再折一次得到一模一樣的行。
 * 所以回填後畫面與回填前完全相同，只是文字從此可以改寬／對齊。
 */
fun hugBoxWidthForLines(text: String, fontSize: Float): Float {
    if (fontSize <= 0f) return TEXT_BOX_MIN_WIDTH
    val widest = text.split("\n").maxOfOrNull { measureTextWidth(it, fontSize) } ?: 0f
    return widest.coerceAtLeast(TEXT_BOX_MIN_WIDTH)
}
