package com.vic.inkflow

import com.vic.inkflow.ui.AiAction
import com.vic.inkflow.ui.AiCapabilities
import com.vic.inkflow.ui.AiOutcome
import com.vic.inkflow.ui.AiProvider
import com.vic.inkflow.ui.AiRequest
import com.vic.inkflow.ui.domProbeJs
import com.vic.inkflow.ui.imagePasteInputSelector
import com.vic.inkflow.ui.promptSendJs
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

    private fun key(
        action: AiAction = AiAction.SEND_PROMPT,
        imageId: String? = null,
        prompt: String? = null,
        autoSend: Boolean = true
    ) = AiRequest.dedupeKey(action, imageId, prompt, autoSend)

    @Test
    fun `空請求不算數`() {
        assertTrue(AiRequest(AiAction.SEND_PROMPT).isEmpty)
        assertFalse(AiRequest(AiAction.SEND_PROMPT, prompt = "解釋").isEmpty)
    }

    @Test
    fun `完全相同的請求去重鍵相同`() {
        assertEquals(
            key(imageId = "content://x/1", prompt = "解釋", autoSend = false),
            key(imageId = "content://x/1", prompt = "解釋", autoSend = false)
        )
    }

    @Test
    fun `同一張圖不同提示詞是不同的請求`() {
        // 這是最容易退化的一條：舊版只比對 Uri，整頁(解釋)與圈選(總結)
        // 若剛好同一張 Uri 會被當成同一個而漏送。
        assertNotEquals(
            key(imageId = "content://x/1", prompt = "解釋", autoSend = false),
            key(imageId = "content://x/1", prompt = "總結", autoSend = true)
        )
    }

    @Test
    fun `autoSend 不同視為不同請求`() {
        // 整頁鈕只填不送、圈選自動送，同一張圖語意不同。
        assertNotEquals(
            key(imageId = "content://x/1", prompt = "解釋", autoSend = false),
            key(imageId = "content://x/1", prompt = "解釋", autoSend = true)
        )
    }

    @Test
    fun `action 不同視為不同請求`() {
        assertNotEquals(
            key(action = AiAction.SEND_PROMPT, imageId = "content://x/1"),
            key(action = AiAction.IMPORT_REPLY)
        )
    }

    @Test
    fun `不同圖是不同請求`() {
        assertNotEquals(
            key(imageId = "content://x/1", prompt = "解釋"),
            key(imageId = "content://x/2", prompt = "解釋")
        )
    }

    @Test
    fun `prompt 消費後鍵會改變`() {
        // onPromptConsumed 會把 prompt 清成 null。釘住「圖還在、字已送」這個中間狀態，
        // 讓面板不會把同一段字重複注入。
        val before = key(imageId = "content://x/1", prompt = "解釋", autoSend = false)
        val after = key(imageId = "content://x/1", prompt = null, autoSend = false)
        assertNotEquals(before, after)
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
    fun `S3 ChatGPT 只開啟已實作的送圖，匯入仍關閉`() {
        // S3 交付截圖送 AI；匯入（圈選＋抓回覆）還沒做，必須保持關閉。
        val chatgpt = AiProvider.CHATGPT.capabilities
        assertTrue("S3 已實作截圖送 AI", chatgpt.sendImage)
        assertFalse("匯入尚未移植，不得宣稱可用", chatgpt.importReply)
        assertFalse("數學裁圖尚未驗證，不得宣稱可用", chatgpt.mathCrop)
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