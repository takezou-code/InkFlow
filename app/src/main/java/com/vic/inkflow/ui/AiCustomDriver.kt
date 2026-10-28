package com.vic.inkflow.ui

/**
 * 自訂 AI：**使用者自己填名稱與網址**，沒有預設網站。
 *
 * 存在理由：新 AI 一直出，不該每次都要等 App 發版；網站改版、擋區、換網域時，
 * 使用者自己改一次就能繼續用。它跟其他家走完全相同的契約（貼圖、注入、探針都共用），
 * 只有 `startUrl`／`host` 是空的——實際值由 `ai_custom_url_CUSTOM` 推導。
 *
 * 候選鏈刻意寫得**很泛用**：不知道對方的 DOM，就先猜常見寫法，讓
 * `PROBE3` 探針回報命中數，之後再收斂。（匯入未實測，所以 `importReply` 關著。）
 */
internal object AiCustomDriver : CandidateChainDriver() {

    override val label = "自訂 AI"

    /** 沒有預設網站：沒填網址＝不可用（見 [coerceUsableProvider]）。 */
    override val startUrl = ""
    override val host = ""

    override val capabilities = AiCapabilities(sendImage = true)

    override val inputCandidates = listOf(
        "div[contenteditable=\"true\"]",
        "textarea",
        "[role=\"textbox\"]",
        "input[type=\"text\"]"
    )

    override val sendCandidates = listOf(
        "button[type=\"submit\"]",
        "button[aria-label*=\"Send\"]",
        "button[aria-label*=\"送出\"]",
        "button[data-testid*=\"send\"]",
        "button[class*=\"send\"]"
    )

    override val replyChain = listOf(
        "div[class*=\"markdown\"]",
        "article",
        "div[class*=\"message\"]",
        "div[class*=\"response\"]"
    )

    /** 各站串流語意不同，無法預測；匯入開啟前必須先實測。 */
    override val replyIdleSelector: String? = null
}
