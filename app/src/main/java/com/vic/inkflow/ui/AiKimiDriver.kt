package com.vic.inkflow.ui

/**
 * Kimi（kimi.com）。
 *
 * **Tier 1：開站＋送圖已開，匯入／數學裁圖尚未實測**。
 *
 * 注意 host 只寫 `kimi.com`，startUrl 卻是 `www.kimi.com`——這是刻意的：
 * [AiProvider.matchesHost] 接受子網域，所以 `www.` 變體與登入後的跳轉都認得，
 * 但持久化的 host 不會因為使用者剛好落在哪個子網域而分家。
 */
internal object AiKimiDriver : CandidateChainDriver() {

    override val label = "Kimi"
    override val startUrl = "https://www.kimi.com/"
    override val host = "kimi.com"
    override val capabilities = AiCapabilities(sendImage = true)

    override val inputCandidates = listOf(
        "div[contenteditable=\"true\"].chat-input-editor",
        "div[contenteditable=\"true\"][data-lexical-editor]",
        "div[contenteditable=\"true\"]",
        "textarea"
    )

    override val sendCandidates = listOf(
        "div.send-button",
        "button.send-button",
        "button[class*=\"send\"]",
        "button[aria-label*=\"Send\"]",
        "button[aria-label*=\"發送\"]",
        "button[type=\"submit\"]"
    )

    override val replyChain = listOf(
        "div.segment-content",
        "div.markdown-container",
        "div[class*=\"assistant\"]",
        "div[class*=\"message\"]:last-child"
    )

    /** 尚未實測：先不設閘門，匯入開啟前必須先確認這站的串流語意。 */
    override val replyIdleSelector: String? = null
}
