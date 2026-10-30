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
    val fileInputs: Int,
    /** 登入表單的密碼欄數量：>0 幾乎等於「還沒登入」。 */
    val passwordFields: Int = 0,
    /** 驗證／擋機器人元素（captcha、turnstile、challenge）數量 >0 就是被擋。 */
    val captcha: Int = 0,
    /** 頁面標題，判斷「這是登入頁還是對話頁」最快的一條線索。 */
    val title: String = ""
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

/** 這家 AI 現在到底能不能用。由探針量到的 DOM 推論，不猜。 */
enum class AiReadiness {
    /** 輸入框與回覆區都抓到。 */
    READY,

    /** 能送出，但抓不到回覆區（匯入會拿不到東西）。 */
    SEND_ONLY,

    /** 頁面上有登入表單。 */
    LOGIN_REQUIRED,

    /** 有驗證／擋機器人頁面。 */
    CAPTCHA,

    /** 兩者都沒有：未登入，或版面改了。 */
    NOT_READY
}

data class AiReadinessResult(val state: AiReadiness, val hint: String) {
    /** 設定頁那一小格用的短標。 */
    val label: String
        get() = when (state) {
            AiReadiness.READY -> "就緒"
            AiReadiness.SEND_ONLY -> "只能送"
            AiReadiness.LOGIN_REQUIRED -> "需登入"
            AiReadiness.CAPTCHA -> "驗證頁"
            AiReadiness.NOT_READY -> "未就緒"
        }
}

/**
 * 由探針結果推論可用狀態。
 *
 * 判斷順序刻意是「驗證頁 → 登入頁 → 找輸入框」：被擋或被登入頁卡住時，
 * 硬去比對輸入框只會得到「沒有輸入框」，然後被誤判成「版面改了」，
 * 害人去改選擇器。先把真正的原因說出來，才不會修錯地方。
 */
fun AiProbeReport.readiness(): AiReadinessResult = when {
    firstInput() == null && captcha > 0 ->
        AiReadinessResult(AiReadiness.CAPTCHA, "被驗證／擋機器人頁面擋住，登入與送圖都不會動")

    firstInput() == null && passwordFields > 0 ->
        AiReadinessResult(AiReadiness.LOGIN_REQUIRED, "頁面有登入表單，先用這個面板登入")

    firstInput() == null ->
        AiReadinessResult(AiReadiness.NOT_READY, "找不到輸入框（未登入或版面改了；看標題判斷）")

    firstReply() == null ->
        AiReadinessResult(AiReadiness.SEND_ONLY, "可以送出，但抓不到回覆區（匯入不可用）")

    else ->
        AiReadinessResult(AiReadiness.READY, "可以送圖；回覆區也抓得到")
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

    return AiProbeReport(
        url = url,
        inputs = parseHits(inputSec),
        sends = parseHits(sendSec),
        replies = parseHits(replySec),
        idle = idleSec.takeIf { it != "none" }?.let { parseHits(it).firstOrNull() },
        katex = katexSec.toIntOrNull() ?: 0,
        mathjax = mathjaxSec.toIntOrNull() ?: 0,
        fileInputs = fieldInt(s, " | fileInput="),
        passwordFields = fieldInt(s, " | pwd="),
        captcha = fieldInt(s, " | captcha="),
        title = s.substringAfter(" | title=", "").trim()
    )
}

/**
 * 取 `key=數字` 的數字。
 *
 * 用「到第一個 ` | ` 為止」而不是吃整段尾巴：新欄位一直往後加，
 * 舊的解析器若吃尾巴，`fileInput=1 | pwd=0` 會變成 `toIntOrNull() == null` → 0，
 * 看起來像「這頁沒有 file input」，是安靜的錯資料。
 */
private fun fieldInt(s: String, marker: String): Int =
    s.substringAfter(marker, "").substringBefore(" | ").trim().toIntOrNull() ?: 0

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

/**
 * 這個網址是不是「登入回呼頁」？
 *
 * 判準刻意窄（誤判成本高：會被當成該回首頁）：路徑含 `callback`／`oauth`，
 * 或查詢／片段帶著 `id_token=`／`access_token=`／`code=`＋`state=`。
 * 一般對話頁不會長這樣，所以命中就能安全地「回首頁」。
 *
 * 為什麼需要：站方用 popup 登入時，回呼頁不該蓋掉主頁；但有些站走整頁跳轉，
 * 一旦回呼頁在 App 內壞掉（opener 為 null、腳本 TypeError），使用者就卡在
 * 一片空白，而這個網址還會被記成「上次待的地方」，下次進來繼續空白。
 */
fun looksLikeAuthCallback(url: String?): Boolean {
    val s = url.orEmpty().lowercase()
    if (s.isEmpty()) return false
    val path = s.substringAfter("://", "")
        .substringAfter('/', "")
        .substringBefore('?')
        .substringBefore('#')
    if (path.contains("callback") || path.contains("oauth")) return true
    val tail = if (s.contains('?')) s.substringAfter('?') else s.substringAfter('#', "")
    return tail.contains("id_token=") ||
        tail.contains("access_token=") ||
        (tail.contains("code=") && tail.contains("state="))
}

/**
 * PROBE3 原始字串裡的網址欄位先脫敏，其餘原封不動（選擇器要完整留著才鎖得住）。
 */
fun redactProbeUrl(raw: String): String {
    val start = raw.indexOf("url=")
    if (start < 0) return raw
    val valueStart = start + 4
    val valueEnd = raw.indexOf(" | ", valueStart).let { if (it < 0) raw.length else it }
    return raw.substring(0, valueStart) +
        redactSecrets(raw.substring(valueStart, valueEnd), maxLen = 200) +
        raw.substring(valueEnd)
}

/**
 * 清掉早期版本留下的壞資料（一次性掃描，啟動時呼叫）。兩種：
 *
 * 1. **含登入憑證**：那時網址還沒脫敏，`google-callback#id_token=…` 整串進了
 *    prefs 與還原網址。留著等於把可用的權杖留在磁碟上，而且對診斷沒有價值。
 * 2. **記錯站的探針**：切站瞬間，前一個站的延遲探針會打回來，於是
 *    `ai_probe_CLAUDE` 裡存著 Gemini 的 DOM（實測）。這種資料比沒有更糟——
 *    照著它去改 CLAUDE 的選擇器只會愈改愈歪。
 */
fun sanitizeAiDiagnostics(prefs: android.content.SharedPreferences) {
    val edit = prefs.edit()
    var dirty = false
    prefs.all.forEach { (key, value) ->
        val v = value as? String ?: return@forEach
        val ours = key.startsWith("ai_probe_") || key.startsWith("ai_doc_url_")
        if (ours && containsSecret(v)) {
            edit.remove(key)
            dirty = true
        }
    }
    AiProvider.entries.forEach { p ->
        val key = aiProbeKey(p)
        val stored = prefs.getString(key, null) ?: return@forEach
        val report = decodeProbeRecord(stored)?.report ?: return@forEach
        // 自訂 AI 沒有內建 host，比不了站，就不動它。
        if (p.host.isNotBlank() && !p.matchesHost(report.url)) {
            edit.remove(key)
            dirty = true
        }
    }
    if (dirty) edit.apply()
}
