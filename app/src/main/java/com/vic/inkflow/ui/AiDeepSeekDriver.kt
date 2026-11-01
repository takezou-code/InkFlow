package com.vic.inkflow.ui

/**
 * DeepSeek（chat.deepseek.com）。
 *
 * host 用 `chat.deepseek.com` 而不是 `deepseek.com`：後者是官網（含 API 文件），
 * 不是對話頁；記錯會讓「上次對話 URL」還原到行銷頁（Qwen 踩過同一個坑）。
 *
 * **送圖走站方自己的 file input；匯入用「段落圈選」。** 這裡的候選鏈全部是
 * 「先能命中就好」——DeepSeek 的 DOM 還沒實測，`PROBE3` 的 `replyCensus`
 * 會在第一次使用後告訴我們真正的容器，再把候選收斂成單一 selector。
 */
internal object AiDeepSeekDriver : CandidateChainDriver() {

    override val label = "DeepSeek"
    override val startUrl = "https://chat.deepseek.com/"
    override val host = "chat.deepseek.com"

    // 合成 paste 在 textarea 站台常常「回報失敗但其實進去了」，走它自己的上傳入口
    // 只有一條路徑，不會重複貼圖（Kimi／Qwen 的教訓）。
    override val capabilities = AiCapabilities(
        sendImage = true,
        importReply = true,
        fileInputFirst = true
    )

    override val inputCandidates = listOf(
        "textarea#chat-input",
        "textarea[placeholder]",
        "div[contenteditable=\"true\"]",
        "textarea"
    )

    override val sendCandidates = listOf(
        "div[role=\"button\"][aria-disabled=\"false\"]",
        "button[type=\"submit\"]",
        "button[aria-label*=\"Send\"]",
        "button[aria-label*=\"發送\"]",
        "[data-testid*=\"send\"]"
    )

    /**
     * 只用於探針量測（圈選走 `main`，見 [CandidateChainDriver.pickChain]）。
     * `div[class*="message"]` 刻意放最後：Qwen 的經驗是它會命中送鈕容器。
     */
    override val replyChain = listOf(
        "div[class*=\"ds-markdown\"]",
        "div[class*=\"markdown\"]",
        "div[class*=\"message-content\"]",
        "article",
        "div[class*=\"message\"]"
    )

    /** 未實測：先不設閘門，等實機確認停止鈕的語意。 */
    override val replyIdleSelector: String? = null
}
