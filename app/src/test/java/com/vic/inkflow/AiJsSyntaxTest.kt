package com.vic.inkflow

import com.vic.inkflow.ui.AiChatGptDriver
import com.vic.inkflow.ui.AiGeminiDriver
import com.vic.inkflow.ui.AiGenericScripts
import com.vic.inkflow.ui.AiQwenDriver
import com.vic.inkflow.ui.IMAGE_MODE_CLICK
import com.vic.inkflow.ui.IMAGE_MODE_PASTE
import com.vic.inkflow.ui.buildImagePasteJs
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 注入用的 JS 是「Kotlin 字串裡的程式」：編譯器只檢查插值，**不檢查 JS 語法**。
 * 一個漏掉的括號會讓整段腳本靜默變 no-op——貼圖沒反應、探針全 0、填字永遠失敗，
 * 而且錯誤只出現在 WebView 的 console（沒人看得到）。所以這裡直接叫 node 檢查。
 *
 * 沒裝 node 就跳過（測的是 JS，不是 JVM 邏輯），不會讓別人的機器紅燈。
 */
class AiJsSyntaxTest {

    private fun check(name: String, js: String) {
        val dir = File("build/tmp/jscheck").apply { mkdirs() }
        val file = File(dir, "$name.js").apply { writeText(js) }
        val node = try {
            ProcessBuilder("node", "--check", file.absolutePath)
                .redirectErrorStream(true)
                .start()
        } catch (_: Exception) {
            null
        }
        assumeTrue("找不到 node，跳過 JS 語法檢查", node != null)
        val out = node!!.inputStream.bufferedReader().readText()
        val exit = node.waitFor()
        assertEquals("$name.js 語法錯誤：\n$out", 0, exit)
    }

    @Test
    fun `三支注入腳本語法都合法`() {
        check(
            "prompt",
            AiGenericScripts.promptSendJs(
                inputCandidates = listOf("textarea", "div[contenteditable=\"true\"]"),
                sendCandidates = listOf("button[type=\"submit\"]", "div.send-button"),
                promptQuoted = "\"請解釋這一段\"",
                send = true
            )
        )
        check(
            "probe",
            AiGenericScripts.domProbeJs(
                inputCandidates = listOf("textarea", "div[contenteditable=\"true\"]"),
                sendCandidates = listOf("button[type=\"submit\"]"),
                replyCandidates = listOf("div[class*=\"message\"]", "div.segment-content"),
                idleSelector = "button[aria-label=\"Stop\"]"
            )
        )
        check(
            "image_click",
            buildImagePasteJs(
                base64 = "",
                attempts = 3,
                inputSelector = "textarea, div[class*=\"chat-input\"]",
                requestKey = "7_INSERT_PAGE",
                mode = IMAGE_MODE_CLICK
            )
        )
        check(
            "image_paste",
            buildImagePasteJs(
                base64 = "iVBORw0KGgo=",
                attempts = 3,
                inputSelector = "textarea, div[class*=\"chat-input\"]",
                requestKey = "7_INSERT_PAGE",
                mode = IMAGE_MODE_PASTE
            )
        )
        // 收集腳本（含 MathML → LaTeX 那段）是**字串裡的 JS**：Kotlin 編譯器看不到它，
        // 少了這一步，JS 打錯字要等到實機匯入才發現（錯誤只會出現在 WebView console，
        // 不進 ai_diag）。
        check("collect_gemini", AiGeminiDriver.collectJs())
        check("collect_qwen", AiQwenDriver.collectJs())
        check("collect_chatgpt", AiChatGptDriver.collectJs())
    }
}
