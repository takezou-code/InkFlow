package com.vic.inkflow.ui

/**
 * 一次 `PROBE3` 的解析結果。
 *
 * 為什麼要解析而不是把整行字串直接顯示：那行字有十幾個 selector 與命中數，
 * 使用者看不懂，我也只能靠人工比對。解析後「輸入 3/4・送出 1/2」一眼就知道
 * 還差哪一段，副標再指出實際命中的那個 selector——鎖定選擇器只需要這一條。
 *
 * 純資料＋純函數，不碰 Android/Compose，單測釘住格式（見 `AiProbeReportTest`）。
 */
data class AiProbeHit(val selector: String, val count: Int) {
    val isHit: Boolean get() = count > 0
}

data class AiProbeReport(
    val url: String,
    val inputs: List<AiProbeHit>,
    val sends: List<AiProbeHit>,
    val replies: List<AiProbeHit>,
    /** 串流停止鈕 selector；null＝這站沒宣告（或探針回了 `none`）。 */
    val idle: AiProbeHit?,
    val katex: Int,
    val mathjax: Int,
    /** 頁面上的 `input[type=file]` 數量（送圖退路用得到）。 */
    val fileInputs: Int
) {
    /** 實際命中的輸入框（候選鏈由具體到泛用，第一個命中最可信）。 */
    fun firstInput(): AiProbeHit? = inputs.firstOrNull { it.isHit }

    fun firstSend(): AiProbeHit? = sends.firstOrNull { it.isHit }

    fun firstReply(): AiProbeHit? = replies.firstOrNull { it.isHit }

    /** 「3/4」：命中幾個候選／共幾個。 */
    fun inputScore(): String = score(inputs)

    fun sendScore(): String = score(sends)

    fun replyScore(): String = score(replies)

    /** 一行摘要，設定頁副標用。 */
    fun summary(): String =
        "輸入 ${inputScore()}・送出 ${sendScore()}・回覆 ${replyScore()}" +
            (if (katex > 0 || mathjax > 0) "・公式 ${katex + mathjax}" else "")

    private fun score(list: List<AiProbeHit>): String =
        "${list.count { it.isHit }}/${list.size}"
}

/**
 * 解析探針輸出。認不得就回 null（呼叫端顯「尚未記錄」，不要顯示半截垃圾）。
 *
 * 格式由 [AiGenericScripts.domProbeJs] 產生，段落順序固定：
 * `PROBE3 url=… | input=[sel=n | …] | send=[…] | reply=[…] | idle=… | katex=… | mathjax=… | fileInput=…`
 *
 * 刻意用「段落標記」切而不是解析括號：selector 自己就含 `[` `]`
 * （`input[type="file"]`），照括號配對會切錯。
 */
fun parseProbeReport(raw: String?): AiProbeReport? {
    // 傳進來的必須是解碼後的字串：JSON 外層（`"PROBE3 … \"…\""`）由呼叫端負責拆，
    // 這裡若自作聰明去掉引號，會把 selector 裡的 `"` 一起吃掉。
    val s = raw.orEmpty().trim()
    if (!s.startsWith("PROBE3 ")) return null

    val url = s.substringAfter("url=", "").substringBefore(" | input=[")
    val inputSec = s.section(" | input=[", "] | send=[") ?: return null
    val sendSec = s.section("] | send=[", "] | reply=[") ?: return null
    val replySec = s.section("] | reply=[", "] | idle=") ?: return null
    val idleSec = s.section("] | idle=", " | katex=") ?: return null
    val katexSec = s.section(" | katex=", " | mathjax=") ?: return null
    val mathjaxSec = s.section(" | mathjax=", " | fileInput=") ?: return null
    val fileSec = s.substringAfter(" | fileInput=", "")

    return AiProbeReport(
        url = url,
        inputs = parseHits(inputSec),
        sends = parseHits(sendSec),
        replies = parseHits(replySec),
        idle = idleSec.takeIf { it != "none" }?.let { parseHits(it).firstOrNull() },
        katex = katexSec.toIntOrNull() ?: 0,
        mathjax = mathjaxSec.toIntOrNull() ?: 0,
        fileInputs = fileSec.toIntOrNull() ?: 0
    )
}

/** 取 [from] 之後、[to] 之前的內容；任一標記缺席回 null（代表格式不符，不要硬湊）。 */
private fun String.section(from: String, to: String): String? {
    val start = indexOf(from)
    if (start < 0) return null
    val contentStart = start + from.length
    val end = indexOf(to, contentStart)
    if (end < 0) return null
    return substring(contentStart, end)
}

/** `sel=n | sel2=m` → 清單。計數壞掉的那一條直接跳過，不讓一行壞資料毀掉整份報告。 */
private fun parseHits(section: String): List<AiProbeHit> =
    section.split(" | ").mapNotNull { item ->
        val at = item.lastIndexOf('=')
        if (at <= 0) return@mapNotNull null
        val count = item.substring(at + 1).trim().toIntOrNull() ?: return@mapNotNull null
        AiProbeHit(item.substring(0, at).trim(), count)
    }

// ── 持久化（prefs 一格一字串）─────────────────────────────────────────────

/** 一次探針紀錄：`load`（剛載入）或 `hydrated`（SPA 渲染後，4 秒那次）。 */
data class AiProbeRecord(
    val tag: String,
    val at: Long,
    val report: AiProbeReport
) {
    /** `hydrated` 才是真的 DOM；設定頁標一下，避免拿空殼資料判斷。 */
    val isHydrated: Boolean get() = tag == "hydrated"
}

/** 某家 AI 上次探針結果的 prefs key。 */
fun aiProbeKey(provider: AiProvider): String = "ai_probe_${provider.name}"

/**
 * 存成 `tag|at|raw`（不用 JSON）。
 *
 * 理由：`raw` 本身就含大量 `|` 與 `"`，包 JSON 一來要轉義、二來單測得靠
 * Android 的 org.json stub（本地 JVM 測試拿不到真實作）。前兩段格式固定
 * （`load`／`hydrated` 與毫秒數字），用 `split(limit = 3)` 就夠且能單測。
 */
fun encodeProbeRecord(tag: String, at: Long, raw: String): String? {
    if (parseProbeReport(raw) == null) return null
    return "$tag|$at|$raw"
}

fun decodeProbeRecord(stored: String?): AiProbeRecord? {
    val parts = stored.orEmpty().split('|', limit = 3)
    if (parts.size < 3) return null
    val report = parseProbeReport(parts[2]) ?: return null
    return AiProbeRecord(
        tag = parts[0],
        at = parts[1].toLongOrNull() ?: 0L,
        report = report
    )
}

/**
 * 拆 `WebView.evaluateJavascript` 的字串回傳值。
 *
 * WebView 回的是 **JSON 編碼後**的字串（外層有引號、內部 `"` 轉義成 `\"`），
 * 直接存進 prefs 會讓報告滿是反斜線，人也讀不懂。非字串（`null`／數字）回 null。
 */
fun decodeJsString(raw: String?): String? {
    val s = raw.orEmpty()
    if (s.isEmpty() || s == "null") return null
    if (!s.startsWith("\"")) return s
    return try {
        org.json.JSONTokener(s).nextValue() as? String
    } catch (_: Throwable) {
        null
    }
}
