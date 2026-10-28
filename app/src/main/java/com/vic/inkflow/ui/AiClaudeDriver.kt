package com.vic.inkflow.ui

/**
 * Claude（claude.ai）。
 *
 * **Tier 1：開站＋送圖已開，匯入／數學裁圖尚未實測**。
 * 匯入要等平板實測鎖定回覆容器與串流停止鈕之後才能開（見 `PROBE3` log）。
 *
 * 輸入框是 ProseMirror（contenteditable），不是 textarea——貼圖候選因此一定要
 * 涵蓋 `div[contenteditable="true"]` 這條泛用退路。
 */
internal object AiClaudeDriver : CandidateChainDriver() {

    override val label = "Claude"
    override val startUrl = "https://claude.ai/new"
    override val host = "claude.ai"
    override val capabilities = AiCapabilities(sendImage = true)

    override val inputCandidates = listOf(
        "div.ProseMirror[contenteditable=\"true\"]",
        "div[contenteditable=\"true\"][data-placeholder]",
        "div[contenteditable=\"true\"]",
        "textarea"
    )

    override val sendCandidates = listOf(
        "button[aria-label=\"Send message\"]",
        "button[aria-label=\"Send Message\"]",
        "button[aria-label*=\"Send\"]",
        "button[data-testid=\"send-button\"]",
        "button[type=\"submit\"]"
    )

    override val replyChain = listOf(
        "div[data-testid=\"assistant-message\"]",
        "div.font-claude-message",
        "div[data-testid=\"chat-message\"]",
        "div[data-is-streaming]"
    )

    /** 串流中 `data-is-streaming="true"`；生成完會轉 false，停止鈕同時消失。 */
    override val replyIdleSelector: String? =
        "[data-is-streaming=\"true\"], button[aria-label=\"Stop response\"]"
}
