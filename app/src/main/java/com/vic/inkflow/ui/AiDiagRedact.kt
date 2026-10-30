package com.vic.inkflow.ui

/**
 * 診斷用的網址脫敏。**純函式，不碰 Android**（單測直接跑）。
 *
 * 為什麼非做不可：OAuth 回呼頁把登入憑證放在網址上，例如 Kimi 的
 * `https://www.kimi.ai/google-callback#iss=...&id_token=eyJ...`。整串原封不動
 * 寫進 `ai_diag.log` 等於把**可用的登入權杖**（JWT 裡還帶著姓名與信箱）
 * 抄進一個明文檔，還可能被貼到對話裡給人看。診斷只需要「是哪個站、哪條路徑」，
 * 參數值幾乎沒有價值——除了 `state`／`hd` 這種，所以只遮值、保留鍵。
 */

/** 參數名含這些片段就視為機密（子字串比對即可：`id_token` 也含 `token`）。 */
private val SECRET_KEY_PARTS = listOf(
    "token", "code", "secret", "password", "passwd", "session", "sig",
    "credential", "assertion", "state"
)

/**
 * 把網址的查詢字串／片段裡的機密值換成 `<redacted>`，並截短到 [maxLen]。
 *
 * 保留 host、path 與參數名：`#iss=https://accounts.google.com&id_token=<redacted>`
 * 一眼看得出「這是 Google 回呼」，但看不出權杖內容。
 */
fun redactSecrets(url: String?, maxLen: Int = 240): String {
    val s = url.orEmpty().trim()
    if (s.isEmpty()) return ""
    val cut = s.indexOfFirst { it == '?' || it == '#' }
    if (cut < 0) return clip(s, maxLen)
    val head = s.substring(0, cut)
    val sep = s[cut]
    val body = s.substring(cut + 1)
    // 不是 `k=v&k=v` 就沒有「值」可遮，只截短。
    val redacted = if (body.contains('=')) {
        body.split('&').joinToString("&") { pair ->
            val key = pair.substringBefore('=')
            if (isSecretKey(key)) "$key=<redacted>" else pair
        }
    } else {
        body
    }
    return clip(head + sep + redacted, maxLen)
}

/** 這段字串裡是否帶著機密參數（用來清掉早期版本寫進 prefs 的舊記錄）。 */
fun containsSecret(raw: String?): Boolean {
    val s = raw.orEmpty()
    if (s.isEmpty()) return false
    return s.split('&', '?', '#').any { part ->
        part.contains('=') && isSecretKey(part.substringBefore('='))
    }
}

private fun isSecretKey(rawKey: String): Boolean {
    val key = rawKey.trim().lowercase()
    if (key.isEmpty()) return false
    return SECRET_KEY_PARTS.any { key.contains(it) }
}

private fun clip(s: String, maxLen: Int): String =
    if (s.length <= maxLen) s else s.take(maxLen) + "…"
