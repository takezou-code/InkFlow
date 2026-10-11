package com.vic.inkflow

import com.vic.inkflow.ui.AiAction
import com.vic.inkflow.ui.AiCapabilities
import com.vic.inkflow.ui.AiOutcome
import com.vic.inkflow.ui.AiProvider
import com.vic.inkflow.ui.AiRequest
import com.vic.inkflow.ui.domProbeJs
import com.vic.inkflow.ui.imagePasteInputSelector
import com.vic.inkflow.ui.collectJs
import com.vic.inkflow.ui.pickJs
import com.vic.inkflow.ui.promptSendJs
import com.vic.inkflow.ui.replyChain
import com.vic.inkflow.ui.replyIdleSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S0 契約層回歸測試。
 *
 * 這組測試存在的理由：S0 把三個各自為政的隱性變數（fileUri／prompt／autoSend）
 * 換成一個 [AiRequest]。其中**去重語意**改變最多——舊版只比對 Uri，
 * 新版用 [AiRequest.dedupeKey]。同一張圖配不同提示詞在舊版會被誤判成「同一個」，
 * 所以這裡把每個組合都釘死，避免下一個 provider 又把它改歪。
 *
 * 注意：去重鍵一律測 [AiRequest.dedupeKey] 的**純函數**多載。
 * android.net.Uri 在 plain JVM 單測裡是 stub（Uri.parse 回 null），
 * 直接構造帶圖的 AiRequest 測不了，這也是契約把鍵做成字串函式的原因。
 */
class AiContractTest {

    private fun key(id: Long, action: AiAction = AiAction.SEND_PROMPT) =
        AiRequest.dedupeKey(id, action)

    @Test
    fun `空請求不算數`() {
        assertTrue(AiRequest(0L, AiAction.SEND_PROMPT).isEmpty)
        assertFalse(AiRequest(0L, AiAction.SEND_PROMPT, prompt = "解釋").isEmpty)
    }

    @Test
    fun `同一序號的請求去重鍵相同`() {
        assertEquals(key(1L), key(1L))
    }

    @Test
    fun `不同序號就是不同請求`() {
        // 同一張圖連續送兩次（圈選同一區域再送）必須送得出去，所以不能只靠圖判斷。
        assertNotEquals(key(1L), key(2L))
    }

    @Test
    fun `action 不同視為不同請求`() {
        assertNotEquals(key(1L, AiAction.SEND_PROMPT), key(1L, AiAction.IMPORT_REPLY))
    }

    @Test
    fun `PROMPT 消費後投遞識別碼不變（雙份貼圖回歸測試）`() {
        // 這是實機踩到的 bug：貼完圖後 onPromptConsumed 把 prompt 清成 null，
        // 若去重鍵含 prompt，下一輪重組就會再貼一次 → 使用者看到兩張一樣的圖。
        // copy() 必須保留 id，投遞識別碼才不會變。
        val before = AiRequest(7L, AiAction.SEND_PROMPT, image = null, prompt = "解釋", autoSend = false)
        val after = before.copy(prompt = null)
        assertEquals(before.dedupeKey(), after.dedupeKey())
        assertNull(after.prompt)
    }

    @Test
    fun `prompt 與 autoSend 改變不影響投遞識別碼`() {
        val base = AiRequest(9L, AiAction.SEND_PROMPT, prompt = "解釋", autoSend = false)
        assertEquals(base.dedupeKey(), base.copy(prompt = "總結").dedupeKey())
        assertEquals(base.dedupeKey(), base.copy(autoSend = true).dedupeKey())
    }

    @Test
    fun `provider 的 other 互為對手且標籤不為空`() {
        assertEquals(AiProvider.CHATGPT, AiProvider.GEMINI.other)
        assertEquals(AiProvider.GEMINI, AiProvider.CHATGPT.other)
        AiProvider.values().forEach { assertTrue("provider 必須有顯示名", it.label.isNotBlank()) }
    }

    @Test
    fun `每個 provider 都有自己專屬的 host`() {
        // host 是注入總閘門，兩個 provider 共用同一個 host 會讓閘門失效。
        val hosts = AiProvider.values().map { it.host }
        assertEquals("provider 的 host 不可重複", hosts.size, hosts.toSet().size)
        AiProvider.values().forEach {
            assertTrue("${it.name} 的 startUrl 必須含自己的 host", it.startUrl.contains(it.host))
        }
    }

    @Test
    fun `預設 capabilities 全部關閉`() {
        // 新 provider 必須明確宣告能力才會開啟按鈕；
        // 預設全 false 才不會讓沒接的功能看起來可用。
        val caps = AiCapabilities()
        assertFalse(caps.sendImage)
        assertFalse(caps.importReply)
        assertFalse(caps.mathCrop)
    }

    @Test
    fun `S2 Gemini 宣告全部能力且行為不可退化`() {
        // Gemini 是既有、已驗收的功能。三項能力必須全開，
        // 否則 S2 的守衛會把原本能用的按鈕擋掉。
        val gemini = AiProvider.GEMINI.capabilities
        assertTrue("Gemini 必須能送圖", gemini.sendImage)
        assertTrue("Gemini 必須能匯入", gemini.importReply)
        assertTrue("Gemini 必須能做數學裁圖", gemini.mathCrop)
    }

    @Test
    fun `S3 ChatGPT 只開啟已實作的送圖`() {
        // S3 交付截圖送 AI。S4 之後匯入由另一條測試覆蓋。
        assertTrue(AiProvider.CHATGPT.capabilities.sendImage)
    }

    @Test
    fun `S4 兩個 provider 都具備完整匯入能力`() {
        // S4 交付 ChatGPT 匯入；Gemini 的三項能力不許因為重構而退化。
        AiProvider.values().forEach {
            assertTrue("${it.name} 必須能送圖", it.capabilities.sendImage)
            assertTrue("${it.name} 必須能匯入", it.capabilities.importReply)
        }
        // 數學裁圖只有 Gemini 驗過，ChatGPT 仍保守關閉。
        assertTrue(AiProvider.GEMINI.capabilities.mathCrop)
        assertFalse(AiProvider.CHATGPT.capabilities.mathCrop)
    }

    @Test
    fun `S4 回覆容器候選必須是各自的角色標記`() {
        // 混用就會抓錯對象：Gemini 是 model、ChatGPT 是 assistant。
        assertTrue(AiProvider.GEMINI.replyChain.any { it.contains("data-message-author-role=\"model\"") })
        assertTrue(AiProvider.CHATGPT.replyChain.any { it.contains("data-message-author-role=\"assistant\"") })
        assertFalse(
            "兩個 provider 的候選鏈不可相同",
            AiProvider.GEMINI.replyChain == AiProvider.CHATGPT.replyChain
        )
    }

    @Test
    fun `S4 圈選與收集腳本必須依 provider 換容器鏈`() {
        val gemini = AiProvider.GEMINI.pickJs()
        val chatgpt = AiProvider.CHATGPT.pickJs()
        assertTrue("Gemini 腳本要帶 model 角色", gemini.contains("data-message-author-role=\"model\""))
        assertTrue("ChatGPT 腳本要帶 assistant 角色", chatgpt.contains("data-message-author-role=\"assistant\""))
        assertFalse("兩者不可共用同一份", gemini == chatgpt)
    }

    @Test
    fun `S4 只有 ChatGPT 需要等回覆生成完`() {
        // Gemini 沒有停止鈕語意，必須完全不受這道閘門影響（行為零變化）。
        assertNull(AiProvider.GEMINI.replyIdleSelector)
        assertEquals("[data-testid=\"stop-button\"]", AiProvider.CHATGPT.replyIdleSelector)
    }

    @Test
    fun `S3 每個 provider 都給得出自己的注入腳本`() {
        // 面板不得再有 provider 分支，注入分歧只存在於 driver。
        val gemini = AiProvider.GEMINI.promptSendJs("\"hi\"", true)
        val chatgpt = AiProvider.CHATGPT.promptSendJs("\"hi\"", true)
        assertTrue("Gemini 仍走 rich-textarea", gemini.contains("rich-textarea"))
        assertTrue("ChatGPT 走 ProseMirror 的 prompt-textarea", chatgpt.contains("#prompt-textarea"))
        assertFalse("兩者不可共用同一份腳本", gemini == chatgpt)
    }

    @Test
    fun `S3 ChatGPT 探針必須穿 shadow 並回報候選命中數`() {
        // 上次探針量到全 0 就是沒穿 shadow 的後果；這條釘死避免再退回去。
        val probe = AiProvider.CHATGPT.domProbeJs()
        assertTrue("探針必須穿 shadowRoot", probe.contains("shadowRoot"))
        assertTrue("探針必須檢查 iframe 可達性", probe.contains("iframe"))
        assertTrue("探針必須回報候選清單", probe.contains("hit="))
    }

    @Test
    fun `S3 兩個 provider 的貼圖輸入框 selector 各不相同`() {
        // selector 綁在 driver，不該再散落在面板裡。
        assertTrue(AiProvider.GEMINI.imagePasteInputSelector.contains("rich-textarea"))
        assertTrue(AiProvider.CHATGPT.imagePasteInputSelector.contains("#prompt-textarea"))
    }

    @Test
    fun `outcome 失敗必須帶使用者提示`() {
        val bad = AiOutcome.fail("NO_CHAT_INPUT_FOUND", "找不到輸入框")
        assertFalse(bad.ok)
        assertEquals("找不到輸入框", bad.userHint)
        val good = AiOutcome.ok("PASTE_DISPATCHED")
        assertTrue(good.ok)
        assertNull(good.userHint)
    }
}