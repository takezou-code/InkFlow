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
        splitProse(text.substring(cursor, m.range.first), maxChars, bodies)
        val code = m.value.trim()
        if (code.isNotEmpty()) bodies.add(code)
        cursor = m.range.last + 1
    }
    splitProse(text.substring(cursor), maxChars, bodies)
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
