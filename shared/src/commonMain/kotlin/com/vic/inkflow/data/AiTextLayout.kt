package com.vic.inkflow.data

import com.vic.inkflow.ui.sliceParagraphAtLines
import com.vic.inkflow.ui.wrapTextLines

/**
 * AI 匯入的切塊／分頁／版面量測——平台無關的唯一一份。
 *
 * 這裡的每一支都曾經只存在於平板（`app/ui/AiTextImport.kt`），桌面的 AI 筆記另外
 * 寫了一套單頁排版。兩套的差別不是風格，而是**行為**：平板會在既有內容下方接續、
 * 溢出時開新頁；桌面固定從頁頂開始，寫在有墨的頁上就是疊字。
 *
 * 平台相依只剩三根手指，全部由呼叫端注入：
 *  - [paginateAiBlocks] 的 `fontMetrics`（ascent/descent/leading，model pt）；
 *  - [measureContentBottom] 的 `metrics`（行高/descent）；
 *  - [fitMathSize] 的 `referenceWidthPx`（渲染器的參考寬，平板是 KaTeX 的 1500px）。
 */

/** 東亞寬字（用來判斷句與句之間要不要補空格）。 */
private fun isWideChar(ch: Char): Boolean {
    val c = ch.code
    return c in 0x1100..0x115F || c in 0x2E80..0xA4CF || c in 0xAC00..0xD7A3 ||
        c in 0xF900..0xFAFF || c in 0xFE30..0xFE4F || c in 0xFF00..0xFF60 ||
        c in 0xFFE0..0xFFE6
}

/** 兩句之間的連接字串：中文之間不補空白，英文之間補一個。 */
private fun between(a: String, b: String): String =
    if (a.isEmpty() || b.isEmpty()) ""
    else if (isWideChar(a.last()) || isWideChar(b.first())) "" else " "

/**
 * 一句一句切（句末標點留在句尾）。
 *
 * 英文只認「句點＋空白／結尾」，所以 `3.14`、`e.g.`、`example.com` 不會被切開；
 * 中文認 `。！？；`。這是「把長段落切小」用的，不是斷行規則——切完仍用原標點串回去。
 */
private fun sentencesOf(line: String): List<String> {
    val out = ArrayList<String>()
    val sb = StringBuilder()
    var i = 0
    while (i < line.length) {
        val c = line[i]
        sb.append(c)
        val end =
            c == '。' || c == '！' || c == '？' || c == '；' ||
                c == '!' || c == '?' || c == ';' ||
                (c == '.' && (i + 1 >= line.length || line[i + 1].isWhitespace()))
        if (end) {
            val s = sb.toString().trim()
            if (s.isNotEmpty()) out.add(s)
            sb.setLength(0)
        }
        i++
    }
    val tail = sb.toString().trim()
    if (tail.isNotEmpty()) out.add(tail)
    return out
}

/** 超長且無句讀：找一個「不切開英文單字」的位置，最多 [maxChars] 個字。 */
private fun cutAt(line: String, from: Int, maxChars: Int): Int {
    val hard = minOf(from + maxChars, line.length)
    if (hard >= line.length) return line.length
    val sp = line.substring(from, hard).lastIndexOf(' ')
    return if (sp > maxChars / 2) from + sp else hard
}

/** Markdown 清單符號 → 紙上看得懂的圓點（只在行首，不動數學）。 */
private fun normalizeBullets(s: String): String = s.split("\n").joinToString("\n") { line ->
    when {
        line.startsWith("- ") -> "• " + line.substring(2)
        line.startsWith("* ") -> "• " + line.substring(2)
        line.startsWith("+ ") -> "• " + line.substring(2)
        else -> line
    }
}

/**
 * 一段文字切出來的塊。
 *
 * @property continuesPrevious 這塊是**同一段落**上一塊的續塊（不是新段落）。
 *   排版端據此把段落間距歸零，否則一段長正文會被排成好幾段。
 */
private data class ProsePiece(val text: String, val continuesPrevious: Boolean = false)

/**
 * 段落 → 文字塊。
 *
 * **保結構**（2026-11-04 改寫）：空白行是段落邊界（呼叫端已按此切塊），
 * 段落內的**單一換行**是結構（清單、表格、詩、AI 自己斷的行），一律保留；
 * 只有「單行且超過 [maxChars]」才按句打包，打包時不丟字、不黏字。
 *
 * 舊版把 `\n` 當斷句點、`trim()` 掉之後直接串接，於是
 * `第一行\n第二行` 變成 `第一行第二行`，英文單字也會黏在一起——這是
 * 「AI 匯入後要自己重排格式」的另一個來源。
 *
 * 回傳的續塊帶 [ProsePiece.continuesPrevious]，讓分頁不必靠猜（例如用「上一塊
 * 有沒有以標點結尾」判斷，AI 的段落本來就不一定收在標點）。
 */
private fun splitProse(seg: String, maxChars: Int): List<ProsePiece> {
    val out = ArrayList<ProsePiece>()
    for (para in seg.split(Regex("\n\\s*\n"))) {
        val p = normalizeBullets(para.trim())
        if (p.isEmpty()) continue
        if (p.length <= maxChars) {
            out.add(ProsePiece(p))
            continue
        }
        // 先把段落拆成「單位」：每個單位自帶它前面的分隔符（換行＝結構、空白＝句界）。
        val units = ArrayList<Pair<String, String>>()
        val lines = p.split("\n").map { it.trimEnd() }.filter { it.isNotEmpty() }
        for ((li, line) in lines.withIndex()) {
            val head = if (li == 0) "" else "\n"
            val text = line.trim()
            if (text.isEmpty()) continue
            if (text.length <= maxChars) {
                units.add(text to head)
                continue
            }
            val sentences = sentencesOf(text)
            if (sentences.size <= 1) {
                var i = 0
                while (i < text.length) {
                    val cut = cutAt(text, i, maxChars)
                    val piece = text.substring(i, cut).trim()
                    if (piece.isNotEmpty()) units.add(piece to if (i == 0) head else " ")
                    i = cut
                }
            } else {
                for ((si, s) in sentences.withIndex()) {
                    val sep = if (si == 0) head else between(sentences[si - 1], s)
                    units.add(s to sep)
                }
            }
        }
        // 打包：不超過 maxChars；換塊時丟掉分隔符（續塊之間不補段落間距，接得剛好）。
        var buf = StringBuilder()
        var firstOfPara = true
        for ((text, sep) in units) {
            val add = if (buf.isEmpty()) text else sep + text
            if (buf.isNotEmpty() && buf.length + add.length > maxChars) {
                out.add(ProsePiece(buf.toString(), continuesPrevious = !firstOfPara))
                firstOfPara = false
                buf = StringBuilder(text)
            } else {
                buf.append(add)
            }
        }
        if (buf.isNotEmpty()) out.add(ProsePiece(buf.toString(), continuesPrevious = !firstOfPara))
    }
    return out
}

private val DISPLAY_MATH = Regex(
    "\\$\\$[\\s\\S]*?\\$\\$|\\\\\\[[\\s\\S]*?\\\\\\]" +
        "|\\\\begin\\{(equation|align|gather|multline|alignat|flalign|matrix|pmatrix|bmatrix|vmatrix|cases|aligned|array)\\}[\\s\\S]*?\\\\end\\{\\1\\}"
)

private fun chunkTitleOf(s: String): String {
    val first = s.lineSequence().firstOrNull()?.trim().orEmpty()
    return when {
        first.isEmpty() -> "（空塊）"
        first.length <= 20 -> first
        else -> first.take(20) + "…"
    }
}

/** Markdown 標題：`## 標題`。只在整段就是一行標題時成立（標題＋正文沒有空行 → 交給一般文字）。 */
private val HEADING = Regex("^(#{1,6})\\s+(.+)$")

/** 標題字級：紙上要看得出來是標題，但不能大到換頁換得很難看。 */
private fun headingSize(level: Int): Float = when (level) {
    1 -> 22f
    2 -> 19f
    3 -> 17f
    else -> DEFAULT_AI_FONT_SIZE
}

/**
 * ```lang … ``` → 只留內容。
 *
 * 反引號是 Markdown 語法，不是內容；舊版把整段（含 ``` 與語言名）寫到紙上，
 * 匯入後看起來像壞掉。
 */
private fun stripFence(raw: String): String {
    val t = raw.trim()
    if (!t.startsWith("```")) return t
    val nl = t.indexOf('\n')
    val body = if (nl >= 0) t.substring(nl + 1) else ""
    return body.trimEnd().removeSuffix("```").trim()
}

private fun stripDisplayShell(s: String): String {
    val t = s.trim()
    if (t.startsWith("$$") && t.endsWith("$$") && t.length > 4) return t.substring(2, t.length - 2)
    if (t.startsWith("\\[") && t.endsWith("\\]") && t.length > 4) return t.substring(2, t.length - 2)
    return t
}

private fun isComplexPara(p: String, promoteInlineMath: Boolean = true): Boolean {
    if (DISPLAY_MATH.containsMatchIn(p)) return true
    if (!promoteInlineMath) return false
    // \(...\) 是顯式數學殼，有字就算數（ChatGPT 風格的 \(f(x)\) 不能漏）
    for (m in INLINE_PAREN.findAll(p)) if (m.groupValues[1].isNotBlank()) return true
    for (m in INLINE_DOLLAR.findAll(p)) if (looksLikeInlineMath(m.groupValues[1])) return true
    return false
}

/**
 * 短行內數學判定：$f(x)$、$[a,b]$、$x$ 這種沒反斜線的不能漏；
 * 金額/普通文字不能誤殺（$100、$5 and $10、$hello world 全放行）。
 */
fun looksLikeInlineMath(inner0: String): Boolean {
    val t = inner0.trim()
    if (t.isEmpty() || t.length > 60) return false
    if (t.contains('\\') || t.contains('^') || t.contains('_')) return true
    if (t.any { it.isWhitespace() }) return false
    if (t[0].isDigit()) return false
    if (t.length == 1) return t[0].isLetter()
    return t.any { it in "()[]{}+-*/=,|<>!" }
}

private val SCENT_TEX_CMD = Regex("\\\\[A-Za-z]+")
private val SCENT_KEYWORDS = setOf(
    "frac", "sum", "prod", "int", "sqrt", "lim", "begin",
    "log", "ln", "sin", "cos", "tan", "exp", "det",
    "alpha", "beta", "gamma", "delta", "theta", "lambda", "mu",
    "pi", "sigma", "phi", "omega", "infty", "partial", "nabla",
    "forall", "exists", "equiv", "approx", "neq", "leq", "geq",
    "times", "cdot", "pm", "to", "rightarrow", "leftarrow", "oint", "iint"
)

/**
 * 數學味判定（純函數，可單測）：整段文字有強數學訊號就整塊截圖，
 * 不認 DOM 徽章（徽章會變，內容不會變）。金額/普通文字放行。
 */
fun hasMathScent(text0: String): Boolean {
    val t = text0.trim()
    if (t.isEmpty() || t.length > 8000) return false
    if (t.contains("\$\$") || t.contains("\\(") || t.contains("\\[")) return true
    if (t.any { it in "∫∑∏√∞∂∇∈∀∃≤≥≠≈→←⇒⇔∧∨¬⊤⊥" }) return true
    for (m in INLINE_DOLLAR.findAll(t)) {
        if (looksLikeInlineMath(m.groupValues[1])) return true
    }
    for (m in SCENT_TEX_CMD.findAll(t)) {
        if (m.groupValues[0].substring(1) in SCENT_KEYWORDS) return true
    }
    return false
}

/** 把原文切成保序的文字/數學塊（code fence 內不找數學）。 */
fun splitAiBlocks(raw: String, maxChars: Int = AI_CHUNK_MAX_CHARS, promoteInlineMath: Boolean = true): List<AiBlock> {
    val text = raw.replace("\r\n", "\n").trim()
    if (text.isEmpty()) return emptyList()
    val out = mutableListOf<AiBlock>()
    var ti = 0
    fun textBlocksOf(seg: String): List<AiTextBlock> =
        splitProse(convertLatexInProse(seg), maxChars).map { piece ->
            ti++
            AiTextBlock(
                AiTextChunk(
                    id = "c$ti",
                    title = chunkTitleOf(piece.text),
                    body = piece.text,
                    continuesPrevious = piece.continuesPrevious
                )
            )
        }
    fun paraBlocks(seg: String) {
        for (para in seg.split(Regex("\n\\s*\n"))) {
            val p = para.trim()
            if (p.isEmpty()) continue
            if (isComplexPara(p, promoteInlineMath)) {
                ti++
                out.add(AiMathBlock(id = "m$ti", html = p, display = DISPLAY_MATH.containsMatchIn(p), fallback = convertLatexInProse(p)))
            } else {
                // Markdown 標題：去掉 `#`，用較大字級排，否則紙上會出現字面的「##」。
                val h = HEADING.matchEntire(p)
                if (h != null) {
                    val body = h.groupValues[2].trim()
                    if (body.isNotEmpty()) {
                        ti++
                        out.add(
                            AiTextBlock(
                                AiTextChunk(
                                    id = "c$ti",
                                    title = chunkTitleOf(body),
                                    body = body,
                                    fontSize = headingSize(h.groupValues[1].length)
                                )
                            )
                        )
                        continue
                    }
                }
                out.addAll(textBlocksOf(p))
            }
        }
    }
    fun pushOutside(seg: String) {
        var rest = seg
        while (true) {
            val m = DISPLAY_MATH.find(rest) ?: break
            if (m.range.first > 0) paraBlocks(rest.substring(0, m.range.first))
            ti++
            val rawMath = m.value
            out.add(AiMathBlock(id = "m$ti", html = rawMath, display = true, fallback = convertLatexMath(stripDisplayShell(rawMath))))
            rest = rest.substring(m.range.last + 1)
        }
        paraBlocks(rest)
    }
    val fence = Regex("```[\\s\\S]*?(?:```|$)")
    var cursor = 0
    for (m in fence.findAll(text)) {
        pushOutside(text.substring(cursor, m.range.first))
        val code = stripFence(m.value)
        if (code.isNotEmpty()) { ti++; out.add(AiTextBlock(AiTextChunk(id = "c$ti", title = chunkTitleOf(code), body = code))) }
        cursor = m.range.last + 1
    }
    pushOutside(text.substring(cursor))
    return out
}

/** $..$ 行內數學（有 \ ^ _ 才認，避開金額）轉 \(..\)，供 KaTeX auto-render（不配單 $ delimiter）。 */
fun normalizeDollarMath(seg: String): String =
    INLINE_DOLLAR.replace(seg) { r ->
        val inner = r.groupValues[1]
        // 超長不碰（防遠距離 $ 配對吞整段）；短數學轉 \(..\) 供 auto-render
        if (inner.length > 300) r.value
        else if (looksLikeInlineMath(inner)) "\\(" + inner.trim() + "\\)"
        else r.value
    }

fun escHtml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** 數學塊轉渲染用 HTML（原文 delimiter 保留，auto-render 處理）。 */
fun mathBlockHtml(source: String): String = "<div>" + escHtml(normalizeDollarMath(source)) + "</div>"

/**
 * 第二引擎要餵給 KaTeX 的原文。
 *
 * 兩種塊的來源不同：`splitAiBlocks` 產生的塊把 TeX 放在 [AiMathBlock.html]；
 * 裁圖路徑（collect 撿到的塊）`html` 是空的，TeX 留在 [AiMathBlock.fallback]
 * （`AiGeminiDriver` 的 texify 會把 data-math / annotation 還原成 `$…$`、`$$…$$`）。
 */
fun katexSourceOf(mb: AiMathBlock): String =
    if (mb.html.isNotBlank()) mb.html else mb.fallback

/**
 * 第二引擎的目標：**還沒拿到圖**、且有原文可渲染的數學塊。
 *
 * 必須在 [resolveAiBlocks] **之前**呼叫：那一步會把沒有圖的數學塊換成文字，
 * 之後再篩「id 不在 rendered」永遠是空集合（原本的死碼就是這樣來的）。
 */
fun katexTargets(blocks: List<AiBlock>, rendered: Map<String, RenderedMath>): List<AiMathBlock> =
    blocks.filterIsInstance<AiMathBlock>()
        .filter { it.id !in rendered && katexSourceOf(it).isNotBlank() }

/** 未渲染成功的數學塊退回 Unicode 文字塊（保序 splice 用）。 */
fun mathFallbackBlocks(b: AiMathBlock, maxChars: Int = AI_CHUNK_MAX_CHARS): List<AiTextBlock> =
    splitProse(b.fallback, maxChars).mapIndexed { i, piece ->
        AiTextBlock(
            AiTextChunk(
                id = "${b.id}f$i",
                title = chunkTitleOf(piece.text),
                body = piece.text,
                continuesPrevious = piece.continuesPrevious
            )
        )
    }

/** 把未渲染的數學換成 fallback 文字，保序。 */
fun resolveAiBlocks(blocks: List<AiBlock>, rendered: Map<String, RenderedMath>, maxChars: Int = AI_CHUNK_MAX_CHARS): List<AiBlock> {
    val out = mutableListOf<AiBlock>()
    for (b in blocks) {
        if (b is AiMathBlock && b.id !in rendered) out.addAll(mathFallbackBlocks(b, maxChars))
        else out.add(b)
    }
    return out
}

/**
 * M3/M5 流式分頁（圖文混合）：文字用 [wrapTextLines] 折行（**與畫面同一支**），
 * 數學圖按寬等比，由上往下放，放不下就開新頁。全部放新頁 → 與原文/墨水零重疊（永不疊字）。
 * 每個文字段落的 annotation 都帶 `boxWidth`，所以它是一般的文字框（可改寬、可對齊）。
 *
 * **存的是原文**：折行只用來算行數與分頁位置（見 `emitBody`）。若把折好的行存進去，
 * 拉寬框時那些硬換行不會消失，文字就不會重新填滿。
 *
 * @param fontMetrics 字型量測注入點：回傳 `[ascent, descent, leading]`（model pt）。
 *   平板用 `Paint`（DEFAULT_BOLD），桌面用自己的係數模型。抽出來是為了讓本函數能在
 *   JVM 單測跑（`android.graphics.Paint` 在單測是 stub，`fontMetrics` 回 null），
 *   也讓兩個 app 用各自**渲染時真正用的**那組數字排版。
 *   字級不是參數：它在 [AiTextChunk.fontSize] 上，逐塊量測。
 */
fun paginateAiBlocks(
    blocks: List<AiBlock>,
    rendered: Map<String, RenderedMath>,
    modelW: Float,
    modelH: Float,
    marginH: Float = AI_MARGIN_H,
    marginTop: Float = 64f,
    marginBottom: Float = 64f,
    blockGap: Float = 14f,
    // 接續前頁：首頁游標起始高度（預設 marginTop = 舊行為，從空白頁頂開始排）
    startTop: Float = marginTop,
    fontMetrics: (Float) -> FloatArray,
    /** 數學圖的渲染參考寬（px）；預設是平板的 KaTeX 寬。 */
    mathReferenceWidthPx: Float = MATH_RENDER_REFERENCE_W_PX
): List<List<Placed>> {
    // 與 InkCanvas 同字體/字號量測（DEFAULT_BOLD），行高公式與渲染器一致。
    // 字級跟著塊走（[AiTextChunk.fontSize]）：平板全是 [DEFAULT_AI_FONT_SIZE]，
    // 桌面用 22 排章節標題，所以不能假設全篇同一個字級。
    val contentWpt = modelW - marginH * 2
    // 預設邊距下 contentWpt == aiTextContentWidth(modelW)：分頁寬與寫入端同源。
    if (contentWpt <= 100f) return emptyList()

    val pages = mutableListOf<MutableList<Placed>>()
    var cur = mutableListOf<Placed>()
    var cursorTop = startTop
    fun newPage() {
        if (cur.isNotEmpty()) pages.add(cur)
        cur = mutableListOf()
        cursorTop = marginTop
    }
    // 一段＝一個 annotation；必要時跨頁切段。
    // 量測逐塊做：每塊可能有自己的字級（平板全 16，桌面標題 22）。
    // gapAfter：這塊排完要留多少間距。同一段落被切開的續塊傳 0，
    // 否則一段長正文會在紙上看起來變成好幾段。
    //
    // ⚠️ 存的是**原文**（自然段落），不是折好的行。舊版把 `wrapTextLines` 的結果
    // 用 `\n` 串起來存，等於把每個折行點都變成硬換行：之後把框拉寬，那些換行不會
    // 消失，文字不會重新填滿（實機看起來就是「拉寬了卻沒重排」），編輯時也會發現
    // 每個折行都變成真的 Enter。分頁仍按折行後的行數算，但切點落在折行邊界
    // （`sliceParagraphAtLines`），兩半都還是原文。
    fun emitBody(body: String, size: Float, gapAfter: Float) {
        val m = fontMetrics(size)
        val lineH = -m[0] + m[1] + m[2]
        val ascent = m[0]
        if (lineH <= 0f || body.isEmpty()) return
        // 空段落＝空行，必須留著（段落間距）；純空白段落也正規化成空行。
        val paras = body.split("\n").mapTo(ArrayList()) { if (it.isBlank()) "" else it }
        var pi = 0
        while (pi < paras.size) {
            val room = modelH - marginBottom - cursorTop
            val fit = (room / lineH).toInt()
            if (fit <= 0) {
                // 已經在頁頂還放不下一行 → 這本頁比一行還矮，避免無限迴圈。
                if (cursorTop <= marginTop + 0.01f) return
                newPage()
                continue
            }
            // 貪心塞入整段（保住原文）；連一段都塞不下就走「切行」分支。
            val text = StringBuilder()
            var used = 0
            var appended = false
            while (pi < paras.size) {
                val p = paras[pi]
                val n = wrapTextLines(p, size, contentWpt).size.coerceAtLeast(1)
                if (used + n > fit) break
                if (appended) text.append('\n')
                text.append(p)
                appended = true
                used += n
                pi++
            }
            if (appended) {
                cur.add(
                    Placed.T(
                        PlacedText(
                            text = text.toString(),
                            modelX = marginH,
                            modelY = cursorTop - ascent,
                            fontSize = size,
                            boxWidth = contentWpt
                        )
                    )
                )
                // 段落間距只在這一塊真的排完時補；還有下文＝接著排，不補。
                val done = pi >= paras.size
                cursorTop += used * lineH + if (done) gapAfter else 0f
            } else {
                // 單一段落比本頁剩下的空間還高：切在折行邊界，前半留本頁、後半下一頁。
                val (head, tail) = sliceParagraphAtLines(paras[pi], size, contentWpt, fit)
                if (head.isEmpty()) {
                    newPage()
                    continue
                }
                cur.add(
                    Placed.T(
                        PlacedText(
                            text = head,
                            modelX = marginH,
                            modelY = cursorTop - ascent,
                            fontSize = size,
                            boxWidth = contentWpt
                        )
                    )
                )
                cursorTop += fit * lineH
                paras[pi] = tail
                newPage()
            }
        }
    }
    fun emitImage(blockId: String, rm: RenderedMath) {
        val (w0, h0) = fitMathSize(rm.pxW, rm.pxH, contentWpt, modelH - marginTop - marginBottom, mathReferenceWidthPx)
        if (w0 <= 0f || h0 <= 0f) return
        if (modelH - marginBottom - cursorTop < h0) newPage()
        cur.add(Placed.I(rm.path, marginH + (contentWpt - w0) / 2f, cursorTop, w0, h0, blockId))
        cursorTop += h0 + blockGap
    }

    for ((bi, b) in blocks.withIndex()) {
        when (b) {
            is AiTextBlock -> {
                // 下一塊若是同段落的續塊 → 之間只留行距，不補段落間距。
                val next = blocks.getOrNull(bi + 1)
                val gapAfter = if (next is AiTextBlock && next.chunk.continuesPrevious) 0f else blockGap
                emitBody(b.chunk.body.trim(), b.chunk.fontSize, gapAfter)
            }
            is AiMathBlock -> {
                val rm = rendered[b.id] ?: continue
                emitImage(b.id, rm)
            }
        }
    }
    if (cur.isNotEmpty()) pages.add(cur)
    return pages
}

// ---- 接續前頁：sourcePage 是自家頁且下方有空間 → 首頁從 contentBottom 往下排 ----

/** 接續安全墊（量測底再往下墊，防疊字）。 */
const val CONTINUE_SAFETY_PAD = 8f
/** 自家頁白紙門檻（原生 PDF 有字通常 <0.95；自家墨壓不下來）。 */
const val SELF_PAGE_WHITE = 0.98f

/** 自家頁判定：DB 非空＋紙大致白。 */
fun isSelfPage(dbEmpty: Boolean, whiteRatio: Float, whiteThreshold: Float = SELF_PAGE_WHITE): Boolean =
    !dbEmpty && whiteRatio >= whiteThreshold

/**
 * 量 contentBottom（model pt）。null = 不接續（stamp 高度算不準／metrics 異常）。
 * 筆用快取 boundsBottom；字 = 首行 baseline＋(n-1)行高＋descent；
 * 圖用 modelY＋H（rotation 極少，安全墊吸收）。
 *
 * `n` ＝**畫面上真正的行數**：文字框（`boxWidth` 有值）走與畫面／匯出同一支
 * [wrapTextLines]，舊資料（`boxWidth` null）才是逐 `\n` 行數。
 * 這裡曾寫死 `text.split("\n").size`，前提是「螢幕不折行」——文字框上線後該前提
 * 不再成立，折了行的框若仍照 `\n` 數算，量到的底會**偏小**，接著寫的 AI 文字會疊上去。
 *
 * @param metrics fontSize → (行高, descent)，由呼叫端注入平台量測。
 * @param trace 診斷收集器（單測傳 null 保持純潔；device 端傳 list 再由呼叫方打 log）。
 */
fun measureContentBottom(
    strokes: List<Stroke>,
    texts: List<TextAnnotation>,
    images: List<ImageAnnotation>,
    metrics: (Float) -> Pair<Float, Float>,
    trace: MutableList<String>? = null
): Float? {
    if (texts.any { it.isStamp }) return null
    var bottom = 0f
    var strokeB = 0f
    for (s in strokes) {
        strokeB = maxOf(strokeB, s.boundsBottom)
    }
    bottom = maxOf(bottom, strokeB)
    var textB = 0f
    for ((i, t) in texts.withIndex()) {
        val (lineH, descent) = metrics(t.fontSize)
        if (lineH <= 0f) return null
        // 行數 = 畫面真正的行數：有框用 wrapTextLines（同渲染／匯出），沒框才是 \n 行數。
        val boxW = t.boxWidth?.takeIf { it > 0f }
        val n = if (boxW != null) wrapTextLines(t.text, t.fontSize, boxW).size.coerceAtLeast(1)
                else t.text.split("\n").size.coerceAtLeast(1)
        val tb = t.modelY + (n - 1) * lineH + descent
        trace?.add("text[$i] modelY=${t.modelY} n=$n boxW=$boxW fontSize=${t.fontSize} lineH=$lineH descent=$descent bottom=$tb")
        textB = maxOf(textB, tb)
    }
    bottom = maxOf(bottom, textB)
    var imageB = 0f
    for ((i, img) in images.withIndex()) {
        val ib = img.modelY + img.modelHeight
        trace?.add("image[$i] modelY=${img.modelY} h=${img.modelHeight} rot=${img.rotation} bottom=$ib")
        imageB = maxOf(imageB, ib)
    }
    bottom = maxOf(bottom, imageB)
    trace?.add("measure strokes=${strokes.size} strokeB=$strokeB texts=${texts.size} textB=$textB images=${images.size} imageB=$imageB bottom=$bottom")
    return bottom
}

/**
 * 剩餘空間夠兩行才接續；回傳首頁起始游標（已含安全墊），不夠回 null。
 */
fun continueTop(
    contentBottom: Float,
    modelH: Float,
    importLineH: Float,
    marginTop: Float = 64f,
    marginBottom: Float = 64f,
    blockGap: Float = 14f
): Float? {
    val top = maxOf(contentBottom + CONTINUE_SAFETY_PAD, marginTop)
    val room = modelH - marginBottom - top
    return if (room >= 2 * importLineH + blockGap) top else null
}

// ---- M4b：LaTeX → Unicode 可讀版（免渲染引擎、離線即時；程式碼 fence 不進這裡） ----

private val LATEX_GREEK = mapOf(
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
    "epsilon" to "ε", "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η",
    "theta" to "θ", "vartheta" to "θ", "iota" to "ι", "kappa" to "κ",
    "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π",
    "varpi" to "ϖ", "rho" to "ρ", "varrho" to "ϱ", "sigma" to "σ",
    "varsigma" to "ς", "tau" to "τ", "upsilon" to "υ", "phi" to "φ",
    "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
    "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ",
    "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Upsilon" to "Υ",
    "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω"
)

private val LATEX_SYM = mapOf(
    "pm" to "±", "mp" to "∓", "times" to "×", "div" to "÷", "cdot" to "·",
    "neq" to "≠", "ne" to "≠", "approx" to "≈", "leq" to "≤", "le" to "≤",
    "geq" to "≥", "ge" to "≥", "infty" to "∞", "sum" to "∑", "prod" to "∏",
    "int" to "∫", "iint" to "∬", "partial" to "∂", "nabla" to "∇",
    "to" to "→", "rightarrow" to "→", "leftarrow" to "←",
    "Rightarrow" to "⇒", "Leftarrow" to "⇐", "Leftrightarrow" to "⇔",
    "leftrightarrow" to "↔", "longleftarrow" to "⟵", "longrightarrow" to "⟶",
    "longleftrightarrow" to "⟷", "Longleftarrow" to "⟸", "Longrightarrow" to "⟹",
    "Longleftrightarrow" to "⟺", "in" to "∈", "notin" to "∉",
    "subset" to "⊂", "supset" to "⊃", "subseteq" to "⊆", "supseteq" to "⊇",
    "cup" to "∪", "cap" to "∩", "forall" to "∀", "exists" to "∃",
    "emptyset" to "∅", "angle" to "∠", "perp" to "⊥", "propto" to "∝",
    "sim" to "∼", "cong" to "≅", "equiv" to "≡", "ldots" to "…",
    "cdots" to "⋯", "vdots" to "⋮", "ddots" to "⋱", "ast" to "∗",
    "circ" to "∘", "bullet" to "•", "hbar" to "ℏ", "ell" to "ℓ",
    "aleph" to "ℵ", "prime" to "′", "dagger" to "†", "star" to "★",
    "langle" to "⟨", "rangle" to "⟩", "vert" to "|", "Vert" to "∥",
    "lvert" to "|", "rvert" to "|", "lVert" to "∥", "rVert" to "∥",
    "mid" to "|", "parallel" to "∥", "implies" to "⟹", "impliedby" to "⟸",
    "iff" to "⟺", "dots" to "…", "exists" to "∃", "nexists" to "∄",
    "ni" to "∋", "cong" to "≅", "simeq" to "≃", "land" to "∧", "lor" to "∨",
    "neg" to "¬", "lnot" to "¬", "top" to "⊤", "bot" to "⊥",
    "bigcup" to "⋃", "bigcap" to "⋂", "bigvee" to "⋁", "bigwedge" to "⋀",
    "bigoplus" to "⊕", "bigotimes" to "⊗", "bigodot" to "⊙", "bigsqcup" to "⊔",
    "coprod" to "∐", "iiint" to "∭", "oint" to "∮",
    "lceil" to "⌈", "rceil" to "⌉", "lfloor" to "⌊", "rfloor" to "⌋",
    "mapsto" to "↦"
)

private val SUP_MAP = mapOf(
    '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
    '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
    '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
    'a' to 'ᵃ', 'b' to 'ᵇ', 'c' to 'ᶜ', 'd' to 'ᵈ', 'e' to 'ᵉ',
    'f' to 'ᶠ', 'g' to 'ᵍ', 'h' to 'ʰ', 'i' to 'ⁱ', 'j' to 'ʲ',
    'k' to 'ᵏ', 'l' to 'ˡ', 'm' to 'ᵐ', 'n' to 'ⁿ', 'o' to 'ᵒ',
    'p' to 'ᵖ', 'r' to 'ʳ', 's' to 'ˢ', 't' to 'ᵗ', 'u' to 'ᵘ',
    'v' to 'ᵛ', 'w' to 'ʷ', 'x' to 'ˣ', 'y' to 'ʸ', 'z' to 'ᶻ',
    'A' to 'ᴬ', 'B' to 'ᴮ', 'D' to 'ᴰ', 'E' to 'ᴱ', 'G' to 'ᴳ',
    'H' to 'ᴴ', 'I' to 'ᴵ', 'J' to 'ᴶ', 'K' to 'ᴷ', 'L' to 'ᴸ',
    'M' to 'ᴹ', 'N' to 'ᴺ', 'O' to 'ᴼ', 'P' to 'ᴾ', 'R' to 'ᴿ',
    'T' to 'ᵀ', 'U' to 'ᵁ', 'V' to 'ⱽ', 'W' to 'ᵂ'
)

private val SUB_MAP = mapOf(
    '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
    '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
    '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
    'a' to 'ₐ', 'e' to 'ₑ', 'h' to 'ₕ', 'i' to 'ᵢ', 'j' to 'ⱼ',
    'k' to 'ₖ', 'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ', 'o' to 'ₒ',
    'p' to 'ₚ', 'r' to 'ᵣ', 's' to 'ₛ', 't' to 'ₜ', 'u' to 'ᵤ',
    'v' to 'ᵥ', 'x' to 'ₓ'
)

private fun supOf(s: String): String =
    if (s.isNotEmpty() && s.all { it in SUP_MAP }) s.map { SUP_MAP.getValue(it) }.joinToString("")
    else "^($s)"

private fun subOf(s: String): String =
    if (s.isNotEmpty() && s.all { it in SUB_MAP }) s.map { SUB_MAP.getValue(it) }.joinToString("")
    else "_($s)"

/** 單段數學轉 Unicode（不含 $ 殼）。未知指令保留原樣，不破壞內容。 */
fun convertLatexMath(m0: String): String {
    var m = m0.trim()
    if (m.isEmpty()) return m
    // 轉義大括號先佔位（最後還原），避免被分組括號清理吃掉
    m = m.replace("\\{", "\u0001").replace("\\}", "\u0002")
    m = m.replace(Regex("\\\\begin\\{[^{}]*\\}"), "")
    m = m.replace(Regex("\\\\end\\{[^{}]*\\}"), "")
    // 跳脫字元先還原（要在 & 清理之前）
    m = m.replace(Regex("\\\\([%$#_&])"), "$1")
    m = m.replace("&", "")
    m = m.replace("\\\\", "\n")
    m = m.replace(Regex("\\\\label\\{[^{}]*\\}"), "")
    m = m.replace(Regex("\\\\tag\\{[^{}]*\\}"), "")
    var guard = 0
    while (guard++ < 20) {
        val next = m.replace(Regex("\\\\(?:text(?:bf|it|rm|sf|tt)?|math(?:bf|it|rm|sf|tt))\\{([^{}]*)\\}"), "$1")
        if (next == m) break
        m = next
    }
    // 黑板體常用字母 / 花體脫殼（Unicode fallback 可讀優先）
    val bb = mapOf("R" to "ℝ", "Z" to "ℤ", "N" to "ℕ", "Q" to "ℚ", "C" to "ℂ")
    m = Regex("\\\\mathbb\\{([A-Za-z])\\}").replace(m) { r -> bb[r.groupValues[1]] ?: r.groupValues[1] }
    m = Regex("\\\\mathcal\\{([A-Za-z])\\}").replace(m) { it.groupValues[1] }
    // 常用函數名脫反斜線（\sin x → sin x，fallback 可讀）
    m = Regex("\\\\(sin|cos|tan|sec|csc|cot|sinh|cosh|tanh|arcsin|arccos|arctan|log|lg|ln|exp|lim|sup|inf|max|min|det|gcd|ker|dim|arg|deg)\\b").replace(m, "$1")
    guard = 0
    while (guard++ < 20) {
        val next = m.replace(Regex("\\\\d?frac\\{([^{}]*)\\}\\{([^{}]*)\\}"), "($1)/($2)")
        if (next == m) break
        m = next
    }
    m = m.replace(Regex("\\\\sqrt\\[([^{}]*)\\]\\{([^{}]*)\\}"), "$1√($2)")
    m = m.replace(Regex("\\\\sqrt\\{([^{}]*)\\}"), "√($1)")
    m = Regex("\\^\\{([^{}]*)\\}").replace(m) { r -> supOf(r.groupValues[1]) }
    m = Regex("_\\{([^{}]*)\\}").replace(m) { r -> subOf(r.groupValues[1]) }
    m = Regex("\\^([^\\s{}])").replace(m) { r -> supOf(r.groupValues[1]) }
    m = Regex("_([^\\s{}])").replace(m) { r -> subOf(r.groupValues[1]) }
    m = m.replace(Regex("\\\\(?:left|right)(?=[\\(\\[\\{\\)\\]\\}\\|\\.\\/\\\\])|\\\\(?:big|Big|bigg|Bigg)[lmr]?(?=[\\(\\[\\]\\)\\|\\.\\/])"), "")
    m = m.replace(Regex("\\\\(?:quad|qquad|displaystyle|limits|nonumber)"), " ")
    m = m.replace(Regex("\\\\[,;:! ]"), " ")
    m = Regex("\\\\([A-Za-z]+)").replace(m) { r ->
        val name = r.groupValues[1]
        LATEX_GREEK[name] ?: LATEX_SYM[name] ?: r.value
    }
    m = m.replace(Regex("\\\\displaystyle"), " ")
    m = m.replace(Regex("\\\\boxed"), "")
    // 注意：殘留分組括號不 blanket 清除（未知指令原樣保留優先，如 \qwerty{z}）
    return m.split("\n").joinToString("\n") { it.replace(Regex("[ \\t]+"), " ").trim() }.trim()
        .replace("\u0001", "{").replace("\u0002", "}")
}

private val DISPLAY_DOLLAR = Regex("\\$\\$([\\s\\S]*?)\\$\\$")
private val DISPLAY_BRACK = Regex("\\\\\\[([\\s\\S]*?)\\\\\\]")
private val INLINE_PAREN = Regex("\\\\\\(([\\s\\S]*?)\\\\\\)")
// 前後各夾一個負向斷言：`$$…$$`（display）不能被拆成 `$` + `\(…\)` + `$`。
// 少了它，第二引擎把整塊 display TeX 餵進 KaTeX 時會留下兩個錢字號（實測會）。
private val INLINE_DOLLAR = Regex("(?<!\\$)\\$(?!\\$)([^\\$\\n]+?)\\$(?!\\$)")

/**
 * 散文區 LaTeX 轉換：$$..$$ 與 \[..\] 獨立成段，\(..\) 行內轉，
 * $..$ 只有含 \ ^ _ 才當數學（避開金額如 $100）。
 */
fun convertLatexInProse(seg: String): String {
    var s = seg
    s = DISPLAY_DOLLAR.replace(s) { "\n" + convertLatexMath(it.groupValues[1]) + "\n" }
    s = DISPLAY_BRACK.replace(s) { "\n" + convertLatexMath(it.groupValues[1]) + "\n" }
    s = INLINE_PAREN.replace(s) { convertLatexMath(it.groupValues[1]) }
    s = INLINE_DOLLAR.replace(s) { r ->
        val inner = r.groupValues[1]
        if (looksLikeInlineMath(inner)) convertLatexMath(inner)
        else r.value
    }
    return s
}

// ---- M6：空白頁偵測（整頁才用，不摳零碎縫） ----

/** M6：空白頁判定（純函數，可單測）。dbEmpty = DB 無墨無字無圖；whiteRatio = 點陣紙白比例。 */
fun isBlankPage(dbEmpty: Boolean, whiteRatio: Float, whiteThreshold: Float = 0.99f): Boolean =
    dbEmpty && whiteRatio >= whiteThreshold

/** M6：ARGB 陣列紙白比例（純函數，可單測；亮度門檻預設 200）。 */
fun whiteRatioOfPixels(pixels: IntArray, luminanceThreshold: Int = 200): Float {
    if (pixels.isEmpty()) return 0f
    var white = 0
    for (p in pixels) {
        val r = (p shr 16) and 0xff
        val g = (p shr 8) and 0xff
        val b = p and 0xff
        if (r >= luminanceThreshold && g >= luminanceThreshold && b >= luminanceThreshold) white++
    }
    return white.toFloat() / pixels.size
}

/**
 * 數學圖放置尺寸（純函數，可單測）：Device 像素按固定比例映射 model，
 * 小圖不拉伸（有多小擺多小），超寬縮到頁寬，超高縮到頁高。
 * 比例基準 = 渲染器的參考寬（平板是 KaTeX `MathSnapshot.RENDER_W_PX` → 內容寬），
 * 截圖同此換算。
 *
 * @param referenceWidthPx 渲染當時的圖寬（px）。由呼叫端給，因為它屬於平台渲染器，
 *   不是排版規則的一部分。
 */
fun fitMathSize(
    pxW: Int,
    pxH: Int,
    contentWpt: Float,
    maxHpt: Float,
    referenceWidthPx: Float = MATH_RENDER_REFERENCE_W_PX
): Pair<Float, Float> {
    if (pxW <= 0 || pxH <= 0 || contentWpt <= 0f || maxHpt <= 0f || referenceWidthPx <= 0f) return 0f to 0f
    var w = pxW * contentWpt / referenceWidthPx
    var h = pxH.toFloat() / pxW * w
    if (w > contentWpt) {
        w = contentWpt
        h = pxH.toFloat() / pxW * w
    }
    if (h > maxHpt) {
        h = maxHpt
        w = pxW.toFloat() / pxH * h
    }
    return w to h
}
