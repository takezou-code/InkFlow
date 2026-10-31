package com.vic.inkflow.ui

/**
 * Grok（grok.com）。
 *
 * 送圖走站方自己的 file input（合成 paste 的 dispatch 回傳值不可信）。
 * 匯入暫不開：回覆容器只知道是 `div[class*="message"]`，還沒實測，等 census 收斂。
 * 選的候選都是「先能命中就好」，等 `PROBE3` 回報命中數再收斂成單一 selector。
 */
internal object AiGrokDriver : CandidateChainDriver() {

    override val label = "Grok"
    override val startUrl = "https://grok.com/"
    override val host = "grok.com"
    override val capabilities = AiCapabilities(sendImage = true, fileInputFirst = true)

    override val inputCandidates = listOf(
        "textarea[aria-label=\"Ask Grok anything\"]",
        "textarea[placeholder]",
        "div[contenteditable=\"true\"]",
        "textarea"
    )

    override val sendCandidates = listOf(
        "button[aria-label=\"Submit\"]",
        "button[type=\"submit\"]",
        "button[aria-label*=\"Send\"]",
        "button[aria-label*=\"Submit\"]"
    )

    override val replyChain = listOf(
        "div.message-bubble",
        "div[class*=\"response-content\"]",
        "div[data-testid=\"message\"]",
        "div[class*=\"message\"]"
    )

    override val replyIdleSelector: String? =
        "button[aria-label=\"Stop model response\"], button[aria-label*=\"Stop\"]"
}
