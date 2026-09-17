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
 * M3/M5 文字量測：StaticLayout 只取斷行，行高用渲染器 fontMetrics 公式，保證量畫一致。
 */
private fun layoutLines(body: String, textPaint: android.text.TextPaint, contentW: Int): List<String> {
    if (body.isEmpty()) return emptyList()
    val layout = android.text.StaticLayout.Builder.obtain(body, 0, body.length, textPaint, contentW)
        .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
        .setLineSpacing(0f, 1f)
        .setIncludePad(false)
        .build()
    return (0 until layout.lineCount).map { i ->
        body.substring(layout.getLineStart(i), layout.getLineEnd(i)).trimEnd()
    }.filter { it.isNotEmpty() }
}

// ---- M5：圖文混合排版（文字 + KaTeX 數學圖） ----

/** 數學渲染結果（已存檔 PNG）。 */
data class RenderedMath(val file: java.io.File, val pxW: Int, val pxH: Int)

/** 排版輸出：文字或數學圖。 */
sealed interface Placed {
    data class T(val t: PlacedText) : Placed
    data class I(val file: java.io.File, val modelX: Float, val modelY: Float, val modelW: Float, val modelH: Float, val blockId: String) : Placed
}

/** 引入內容塊：純文字或數學（待渲染），保原文順序。 */
sealed interface AiBlock { val id: String }
data class AiTextBlock(val chunk: AiTextChunk) : AiBlock { override val id: String get() = chunk.id }
data class AiMathBlock(override val id: String, val html: String, val display: Boolean, val fallback: String) : AiBlock

private val DISPLAY_MATH = Regex("\\$\\$[\\s\\S]*?\\$\\$|\\\\\\[[\\s\\S]*?\\\\\\]|\\\\begin\\{(equation|align|gather|multline|alignat|flalign|matrix|pmatrix|bmatrix|vmatrix|cases|aligned|array)\\}[\\s\\S]*?\\\\end\\{\\1\\}")
private val COMPLEX_CMD = Regex("\\\\(frac|d?frac|sum|prod|int|iint|oint|sqrt|lim|begin|overline|underline|hat|check|vec|dot|ddot|tilde|bar|mathbb|mathcal|operatorname)\\b")

private fun chunkTitleOf(s: String): String {
    val first = s.lineSequence().firstOrNull()?.trim().orEmpty()
    return when {
        first.isEmpty() -> "（空塊）"
        first.length <= 20 -> first
        else -> first.take(20) + "…"
    }
}

private fun stripDisplayShell(s: String): String {
    val t = s.trim()
    if (t.startsWith("$$") && t.endsWith("$$") && t.length > 4) return t.substring(2, t.length - 2)
    if (t.startsWith("\\[") && t.endsWith("\\]") && t.length > 4) return t.substring(2, t.length - 2)
    return t
}

private fun isComplexPara(p: String): Boolean {
    if (DISPLAY_MATH.containsMatchIn(p)) return true
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

/** 把原文切成保序的文字/數學塊（code fence 內不找數學）。 */
fun splitAiBlocks(raw: String, maxChars: Int = AI_CHUNK_MAX_CHARS): List<AiBlock> {
    val text = raw.replace("\r\n", "\n").trim()
    if (text.isEmpty()) return emptyList()
    val out = mutableListOf<AiBlock>()
    var ti = 0
    fun textBlocksOf(seg: String): List<AiTextBlock> {
        val bodies = mutableListOf<String>()
        splitProse(convertLatexInProse(seg), maxChars, bodies)
        return bodies.map { b -> ti++; AiTextBlock(AiTextChunk(id = "c$ti", title = chunkTitleOf(b), body = b)) }
    }
    fun paraBlocks(seg: String) {
        for (para in seg.split(Regex("\n\\s*\n"))) {
            val p = para.trim()
            if (p.isEmpty()) continue
            if (isComplexPara(p)) {
                ti++
                out.add(AiMathBlock(id = "m$ti", html = p, display = DISPLAY_MATH.containsMatchIn(p), fallback = convertLatexInProse(p)))
            } else {
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
        val code = m.value.trim()
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

/** 未渲染成功的數學塊退回 Unicode 文字塊（保序 splice 用）。 */
fun mathFallbackBlocks(b: AiMathBlock, maxChars: Int = AI_CHUNK_MAX_CHARS): List<AiTextBlock> {
    val bodies = mutableListOf<String>()
    splitProse(b.fallback, maxChars, bodies)
    return bodies.mapIndexed { i, s -> AiTextBlock(AiTextChunk(id = "${b.id}f$i", title = chunkTitleOf(s), body = s)) }
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
 * M3/M5 流式分頁（圖文混合）：文字用 StaticLayout 折行，數學圖按寬等比，
 * 由上往下放，放不下就開新頁。全部放新頁 → 與原文/墨水零重疊（永不疊字）。
 */
fun paginateAiBlocks(
    blocks: List<AiBlock>,
    rendered: Map<String, RenderedMath>,
    modelW: Float,
    modelH: Float,
    fontSize: Float = 16f,
    marginH: Float = 48f,
    marginTop: Float = 64f,
    marginBottom: Float = 64f,
    blockGap: Float = 14f
): List<List<Placed>> {
    // 與 InkCanvas 同字體/字號量測（DEFAULT_BOLD），行高公式與渲染器一致
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textSize = fontSize
    }
    val fm = paint.fontMetrics
    val lineH = -fm.ascent + fm.descent + fm.leading
    if (lineH <= 0f) return emptyList()
    val contentWpt = modelW - marginH * 2
    if (contentWpt <= 100f) return emptyList()
    val contentW = contentWpt.toInt().coerceAtLeast(1)
    val textPaint = android.text.TextPaint(paint)

    val pages = mutableListOf<MutableList<Placed>>()
    var cur = mutableListOf<Placed>()
    var cursorTop = marginTop
    fun newPage() {
        if (cur.isNotEmpty()) pages.add(cur)
        cur = mutableListOf()
        cursorTop = marginTop
    }
    // lines：已折好的行；必要時跨頁切段（一段一 annotation）
    fun emitLines(lines: List<String>) {
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
                Placed.T(
                    PlacedText(
                        text = seg.joinToString("\n") { it.trimEnd() },
                        modelX = marginH,
                        modelY = cursorTop - fm.ascent,
                        fontSize = fontSize
                    )
                )
            )
            cursorTop += take * lineH + blockGap
            idx += take
        }
    }
    fun emitImage(blockId: String, rm: RenderedMath) {
        var w = contentWpt
        var h = if (rm.pxW > 0 && rm.pxH > 0) rm.pxH.toFloat() / rm.pxW * w else contentWpt * 0.3f
        val maxH = modelH - marginTop - marginBottom
        if (h > maxH && h > 0f) {
            h = maxH
            w = rm.pxW.toFloat() / rm.pxH * h
        }
        if (modelH - marginBottom - cursorTop < h) newPage()
        cur.add(Placed.I(rm.file, marginH + (contentWpt - w) / 2f, cursorTop, w, h, blockId))
        cursorTop += h + blockGap
    }

    for (b in blocks) {
        when (b) {
            is AiTextBlock -> {
                val lines = layoutLines(b.chunk.body.trim(), textPaint, contentW)
                if (lines.isNotEmpty()) emitLines(lines)
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
    // 注意：殘留分組括號不 blanket 清除（未知指令原樣保留優先，如 \qwerty{z}）
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

/** M6：點陣紙白比例（取樣步長 stridePx；大點陣圖 IO 執行緒呼叫）。 */
fun whiteRatioOfBitmap(
    bmp: android.graphics.Bitmap,
    stridePx: Int = 8,
    luminanceThreshold: Int = 200
): Float {
    val w = bmp.width
    val h = bmp.height
    if (w <= 0 || h <= 0) return 0f
    val xs = (0 until w step stridePx).toList()
    if (xs.isEmpty()) return 0f
    val rows = (h + stridePx - 1) / stridePx
    val buf = IntArray(xs.size * rows)
    var k = 0
    var y = 0
    while (y < h) {
        for (x in xs) buf[k++] = bmp.getPixel(x, y)
        y += stridePx
    }
    return whiteRatioOfPixels(buf.copyOf(k), luminanceThreshold)
}
