package com.vic.inkflow.ui

/**
 * Kimi（kimi.ai，Moonshot 國際版）。
 *
 * **Tier 1：開站＋送圖已開，匯入／數學裁圖尚未實測**。
 *
 * 預設刻意指國際版：中國版 `kimi.com` 需要中國手機號，海外帳號登不進去。
 * 若使用者在中國或已有 `kimi.com` 帳號，到「設定 → AI 來源 → Kimi 網址」
 * 改成 `https://www.kimi.com/` 即可（自訂網址會一併改掉注入閘門的 host）。
 */
internal object AiKimiDriver : CandidateChainDriver() {

    override val label = "Kimi"
    override val startUrl = "https://kimi.ai/"
    override val host = "kimi.ai"
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
