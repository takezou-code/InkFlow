package com.vic.inkflow.ui

/**
 * M2：從 Gemini 抓回的文字分塊（一塊 = 一次插入單位，M3 排版引擎消費）。
 * 純 Kotlin、無 Android 依賴，方便單元測試。
 */
data class AiTextChunk(
    val id: String,
    val title: String,
    val body: String,
    val charCount: Int = body.length
)

private const val AI_CHUNK_MAX_CHARS = 300

/**
 * 把抓回的文字切成可勾選塊：
 * - ``` 程式碼 fence 整塊保留不切
 * - 空行分段；單段超過上限按句號/換行打包續切，無句讀才硬切
 */
fun chunkAiText(raw: String, maxChars: Int = AI_CHUNK_MAX_CHARS): List<AiTextChunk> {
    val text = raw.replace("\r\n", "\n").trim()
    if (text.isEmpty()) return emptyList()
    val bodies = mutableListOf<String>()
    val fence = Regex("```[\\s\\S]*?(?:```|$)")
    var cursor = 0
    for (m in fence.findAll(text)) {
        splitProse(convertLatexInProse(text.substring(cursor, m.range.first)), maxChars, bodies)
        val code = m.value.trim()
        if (code.isNotEmpty()) bodies.add(code)
        cursor = m.range.last + 1
    }
    splitProse(convertLatexInProse(text.substring(cursor)), maxChars, bodies)
    return bodies.mapIndexed { i, b ->
        val firstLine = b.lineSequence().firstOrNull()?.trim().orEmpty()
        AiTextChunk(
            id = "c$i",
            title = when {
                firstLine.isEmpty() -> "（空塊）"
                firstLine.length <= 20 -> firstLine
                else -> firstLine.take(20) + "…"
            },
            body = b
        )
    }
}

private fun splitProse(seg: String, maxChars: Int, out: MutableList<String>) {
    for (para in seg.split(Regex("\n\\s*\n"))) {
        val p = para.trim()
        if (p.isEmpty()) continue
        if (p.length <= maxChars) {
            out.add(p)
            continue
        }
        // 長段：按句打包（保留句末標點），無句讀才硬切
        val sentences = p.split(Regex("(?<=[。！？!?\\n])")).map { it.trim() }.filter { it.isNotEmpty() }
        if (sentences.size <= 1) {
            var i = 0
            while (i < p.length) {
                out.add(p.substring(i, minOf(i + maxChars, p.length)))
                i += maxChars
            }
        } else {
            val buf = StringBuilder()
            for (s in sentences) {
                if (buf.length + s.length > maxChars && buf.isNotEmpty()) {
                    out.add(buf.toString())
                    buf.clear()
                }
                buf.append(s)
            }
            if (buf.isNotEmpty()) out.add(buf.toString())
        }
    }
}

/** M3：排版輸出（一塊 = 一個 TextAnnotationEntity，文字已按寬折好行，modelY=首行 baseline）。 */
data class PlacedText(
    val text: String,
    val modelX: Float,
    val modelY: Float,
    val fontSize: Float
)

/**
 * M3 流式分頁：每塊用 StaticLayout 按內容寬折行（取其斷行、高度仍用渲染器的
 * fontMetrics 行高，保證量畫一致），由上往下放，放不下就開新頁。
 * 全部放新頁 → 與原文/墨水零重疊（永不疊字）。
 */
fun paginateAiChunks(
    chunks: List<AiTextChunk>,
    modelW: Float,
    modelH: Float,
    fontSize: Float = 16f,
    marginH: Float = 48f,
    marginTop: Float = 64f,
    marginBottom: Float = 64f,
    blockGap: Float = 14f
): List<List<PlacedText>> {
    // 與 InkCanvas 同字體/字號量測（DEFAULT_BOLD），行高公式與渲染器一致
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textSize = fontSize
    }
    val fm = paint.fontMetrics
    val lineH = -fm.ascent + fm.descent + fm.leading
    if (lineH <= 0f) return emptyList()
    val contentW = (modelW - marginH * 2).coerceAtLeast(100f).toInt().coerceAtLeast(1)
    val textPaint = android.text.TextPaint(paint)

    val pages = mutableListOf<MutableList<PlacedText>>()
    var cur = mutableListOf<PlacedText>()
    var cursorTop = marginTop
    fun newPage() {
        if (cur.isNotEmpty()) pages.add(cur)
        cur = mutableListOf()
        cursorTop = marginTop
    }
    // lines：已折好的行；必要時跨頁切段（一段一 annotation）
    fun emit(lines: List<String>) {
        var idx = 0
        while (idx < lines.size) {
            val room = modelH - marginBottom - cursorTop
            val fit = (room / lineH).toInt()
            if (fit <= 0) {
                newPage()
                continue
            }
            val take = minOf(fit, lines.size - idx)
            val seg = lines.subList(idx, idx + take)
            cur.add(
                PlacedText(
                    text = seg.joinToString("\n") { it.trimEnd() },
                    modelX = marginH,
                    modelY = cursorTop - fm.ascent,
                    fontSize = fontSize
                )
            )
            cursorTop += take * lineH + blockGap
            idx += take
        }
    }

    for (chunk in chunks) {
        val body = chunk.body.trim()
        if (body.isEmpty()) continue
        val layout = android.text.StaticLayout.Builder.obtain(body, 0, body.length, textPaint, contentW)
            .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .build()
        val lines = (0 until layout.lineCount).map { i ->
            body.substring(layout.getLineStart(i), layout.getLineEnd(i)).trimEnd()
        }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) continue
        emit(lines)
    }
    if (cur.isNotEmpty()) pages.add(cur)
    return pages
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
    "leftrightarrow" to "↔", "in" to "∈", "notin" to "∉",
    "subset" to "⊂", "supset" to "⊃", "subseteq" to "⊆", "supseteq" to "⊇",
    "cup" to "∪", "cap" to "∩", "forall" to "∀", "exists" to "∃",
    "emptyset" to "∅", "angle" to "∠", "perp" to "⊥", "propto" to "∝",
    "sim" to "∼", "cong" to "≅", "equiv" to "≡", "ldots" to "…",
    "cdots" to "⋯", "vdots" to "⋮", "ddots" to "⋱", "ast" to "∗",
    "circ" to "∘", "bullet" to "•", "hbar" to "ℏ", "ell" to "ℓ",
    "aleph" to "ℵ", "prime" to "′", "dagger" to "†", "star" to "★"
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
        val next = m.replace(Regex("\\\\text(?:bf|it|rm|sf|tt)?\\{([^{}]*)\\}"), "$1")
        if (next == m) break
        m = next
    }
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
    m = m.replace(Regex("\\\\(?:left|right|big|Big|bigg|Bigg)\\s?"), "")
    m = m.replace(Regex("\\\\(?:quad|qquad|displaystyle|limits|nonumber)"), " ")
    m = m.replace(Regex("\\\\[,;:! ]"), " ")
    m = Regex("\\\\([A-Za-z]+)").replace(m) { r ->
        val name = r.groupValues[1]
        LATEX_GREEK[name] ?: LATEX_SYM[name] ?: r.value
    }
    m = m.replace("{", "").replace("}", "")
    return m.split("\n").joinToString("\n") { it.replace(Regex("[ \\t]+"), " ").trim() }.trim()
        .replace("\u0001", "{").replace("\u0002", "}")
}

private val DISPLAY_DOLLAR = Regex("\\$\\$([\\s\\S]*?)\\$\\$")
private val DISPLAY_BRACK = Regex("\\\\\\[([\\s\\S]*?)\\\\\\]")
private val INLINE_PAREN = Regex("\\\\\\(([\\s\\S]*?)\\\\\\)")
private val INLINE_DOLLAR = Regex("\\$([^\\$\n]+?)\\$")

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
        if (inner.contains('\\') || inner.contains('^') || inner.contains('_')) convertLatexMath(inner)
        else r.value
    }
    return s
}
