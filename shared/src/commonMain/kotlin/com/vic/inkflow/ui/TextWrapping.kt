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
fun wrapTextLines(text: String, fontSize: Float, boxWidth: Float): List<String> {
    if (text.isEmpty()) return emptyList()
    if (boxWidth <= 0f || fontSize <= 0f) return text.split("\n")
    val out = ArrayList<String>()
    for (paragraph in text.split("\n")) out.addAll(wrapParagraph(paragraph, fontSize, boxWidth))
    return out
}

private fun wrapParagraph(paragraph: String, fontSize: Float, boxWidth: Float): List<String> {
    if (paragraph.isEmpty()) return listOf("")
    val lines = ArrayList<String>()
    val line = StringBuilder()
    var lineW = 0f

    fun flushLine() {
        lines.add(line.toString().trimEnd())
        line.setLength(0)
        lineW = 0f
    }

    for (tok in tokenizeForWrap(paragraph)) {
        if (tok.isBlank()) {
            // 空白不當行首；行內空白保留（字本身有寬度）
            if (line.isNotEmpty()) {
                line.append(tok)
                lineW += measureTextWidth(tok, fontSize)
            }
            continue
        }
        val w = measureTextWidth(tok, fontSize)
        if (lineW > 0f && lineW + w > boxWidth) flushLine()
        if (w > boxWidth) {
            // 單一 word 比框還寬 → 逐字硬切
            for (ch in tok) {
                val cw = measureTextWidth(ch.toString(), fontSize)
                if (lineW > 0f && lineW + cw > boxWidth) flushLine()
                line.append(ch)
                lineW += cw
            }
        } else {
            line.append(tok)
            lineW += w
        }
    }
    lines.add(line.toString().trimEnd())
    return lines
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
