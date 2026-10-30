package com.vic.inkflow

import com.vic.inkflow.ui.AiProvider
import com.vic.inkflow.ui.containsSecret
import com.vic.inkflow.ui.looksLikeAuthCallback
import com.vic.inkflow.ui.redactProbeUrl
import com.vic.inkflow.ui.redactSecrets
import com.vic.inkflow.ui.resolveRestoreUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 診斷字串的脫敏與「登入回呼頁」判定。
 *
 * 為什麼值得釘：這兩件事都不是畫面問題，錯了不會有人發現——
 * 一個是**把登入權杖寫進明文檔**，一個是把使用者丟在一片空白頁。
 * 實測 Kimi 的 Google 登入就是 `#id_token=…` 的回呼頁卡住，
 * 而那一整串（JWT 裡含姓名與信箱）當時已經被抄進 `ai_diag.log`。
 */
class AiDiagRedactTest {

    // 真實形狀（權杖截短，結構照抄）。
    private val kimiCallback =
        "https://www.kimi.ai/google-callback#iss=https://accounts.google.com" +
            "&id_token=eyJhbGciOiJSUzI1NiJ9.eyJlbWFpbCI6ImUyNDE0MTA0MkBncy5uY2t1LmVkdS50dyJ9.c2ln" +
            "&authuser=0&hd=gs.ncku.edu.tw&prompt=none"

    @Test
    fun `回呼網址的權杖被遮掉但站與路徑留著`() {
        val out = redactSecrets(kimiCallback)
        assertFalse("權杖不可留在字串裡", out.contains("eyJhbGciOiJSUzI1NiJ9"))
        assertTrue(out.contains("https://www.kimi.ai/google-callback#"))
        assertTrue(out.contains("id_token=<redacted>"))
        // 非機密參數原樣保留：排錯時要知道是哪一種回呼。
        assertTrue(out.contains("hd=gs.ncku.edu.tw"))
        assertTrue(out.contains("iss=https://accounts.google.com"))
    }

    @Test
    fun `普通網址不動`() {
        assertEquals("https://gemini.google.com/app", redactSecrets("https://gemini.google.com/app"))
        assertEquals(
            "https://chatgpt.com/?model=gpt-5",
            redactSecrets("https://chatgpt.com/?model=gpt-5")
        )
    }

    @Test
    fun `超長網址截短`() {
        val long = "https://kimi.ai/chat/" + "a".repeat(500)
        assertTrue(redactSecrets(long, maxLen = 60).length <= 61)
    }

    @Test
    fun `機密判定用參數名`() {
        assertTrue(containsSecret(kimiCallback))
        assertTrue(containsSecret("https://x.ai/cb?code=abc&state=xyz"))
        assertTrue(containsSecret("https://x.ai/cb?session=abc"))
        assertFalse(containsSecret("https://kimi.ai/chat/c123"))
        assertFalse(containsSecret(""))
        // 選擇器裡出現 = 不代表機密：診斷字串常長這樣。
        assertFalse(containsSecret("input=[div[contenteditable=\"true\"]=3]"))
    }

    @Test
    fun `PROBE3 原始資料只遮網址不切選擇器`() {
        val raw = "PROBE3 url=$kimiCallback" +
            " | input=[div[contenteditable=\"true\"].chat-input-editor=1 | textarea=0]" +
            " | send=[button[type=\"submit\"]=1] | katex=0 | mathjax=0 | fileInput=0"
        val out = redactProbeUrl(raw)
        assertFalse(out.contains("eyJhbGciOiJSUzI1NiJ9"))
        // 選擇器一字不動：這是存 PROBE_RAW 的唯一目的。
        assertTrue(out.contains("input=[div[contenteditable=\"true\"].chat-input-editor=1 | textarea=0]"))
        assertTrue(out.contains("send=[button[type=\"submit\"]=1]"))
        assertTrue(out.contains("fileInput=0"))
    }

    @Test
    fun `看得出登入回呼頁`() {
        assertTrue(looksLikeAuthCallback(kimiCallback))
        assertTrue(looksLikeAuthCallback("https://x.ai/oauth?code=1&state=2"))
        assertTrue(looksLikeAuthCallback("https://chatgpt.com/api/auth/callback/google?code=1&state=2"))
        assertFalse(looksLikeAuthCallback("https://kimi.ai/"))
        assertFalse(looksLikeAuthCallback("https://gemini.google.com/app/ee9b5d34"))
        assertFalse(looksLikeAuthCallback("https://chatgpt.com/c/abc-123"))
        assertFalse(looksLikeAuthCallback(""))
    }

    @Test
    fun `壞掉的回呼網址不會被當成還原目標`() {
        // 舊記憶（帶著權杖的回呼頁）要當成沒記過，回首頁：否則每次切回來都空白。
        assertEquals(
            "https://kimi.ai/",
            AiProvider.KIMI.resolveRestoreUrl(kimiCallback, null)
        )
        // 正常對話頁照舊還原（原樣保留，含 www）。
        assertEquals(
            "https://www.kimi.ai/chat/abc",
            AiProvider.KIMI.resolveRestoreUrl("https://www.kimi.ai/chat/abc", null)
        )
    }
}
