package com.vic.inkflow.ui

/**
 * Qwen（chat.qwen.ai）。
 *
 * **送圖走站方自己的 file input；匯入用「段落圈選」（容器選擇器待實測收斂）。**
 *
 * host 用 `chat.qwen.ai` 而不是 `qwen.ai`：後者是官網，不是對話頁；
 * 記錯會讓「上次對話 URL」還原到行銷頁。
 */
internal object AiQwenDriver : CandidateChainDriver() {

    override val label = "Qwen"
    override val startUrl = "https://chat.qwen.ai/"
    override val host = "chat.qwen.ai"
    // 合成 paste 對 <textarea> 不會改 .value，而且 Qwen 自己會非同步吃 paste →
    // 舊路徑「以為失敗→再點 file input」就是重複貼圖的來源。改走它自己的上傳入口。
    // mathCrop：Qwen 的公式同樣沒有可還原的 TeX 來源，靠像素裁圖（見 Kimi 的說明）。
    override val capabilities = AiCapabilities(
        sendImage = true,
        importReply = true,
        mathCrop = true,
        fileInputFirst = true
    )

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

    /**
     * 回覆容器候選。**只用於探針量測**（圈選走 `main`，見 CandidateChainDriver.pickChain）：
     * 舊的 `div[class*="message"]` 實測命中 7 個元素，最後一個是**送鈕容器**
     * （`div.message-input-right-button-send`）——用它圈選等於圈到輸入列。
     */
    override val replyChain = listOf(
        "div[class*=\"markdown-content\"]",
        "div.qwen-markdown",
        "div.markdown-body",
        "div[class*=\"message-content\"]",
        "article"
    )

    /** 尚未實測：先不設閘門，匯入開啟前必須先確認這站的串流語意。 */
    override val replyIdleSelector: String? = null
}
