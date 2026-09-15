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
