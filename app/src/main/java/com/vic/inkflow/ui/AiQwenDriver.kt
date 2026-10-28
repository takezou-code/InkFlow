package com.vic.inkflow.ui

/**
 * Qwen（chat.qwen.ai）。
 *
 * **Tier 1：開站＋送圖已開，匯入／數學裁圖尚未實測**。
 *
 * host 用 `chat.qwen.ai` 而不是 `qwen.ai`：後者是官網，不是對話頁；
 * 記錯會讓「上次對話 URL」還原到行銷頁。
 */
internal object AiQwenDriver : CandidateChainDriver() {

    override val label = "Qwen"
    override val startUrl = "https://chat.qwen.ai/"
    override val host = "chat.qwen.ai"
    override val capabilities = AiCapabilities(sendImage = true)

    override val inputCandidates = listOf(
        "textarea#chat-input",
        "textarea[placeholder]",
        "div[contenteditable=\"true\"]",
        "textarea"
    )

    override val sendCandidates = listOf(
        "button#send-message-button",
        "button[aria-label*=\"Send\"]",
        "button[aria-label*=\"發送\"]",
        "button[class*=\"send\"]",
        "button[type=\"submit\"]"
    )

    override val replyChain = listOf(
        "div[class*=\"markdown-content\"]",
        "div.qwen-markdown",
        "div[class*=\"response\"]",
        "div[class*=\"message\"]"
    )

    /** 尚未實測：先不設閘門，匯入開啟前必須先確認這站的串流語意。 */
    override val replyIdleSelector: String? = null
}
