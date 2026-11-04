package com.vic.inkflow

import com.vic.inkflow.ui.AiGeminiDriver
import com.vic.inkflow.ui.DOM_TEXT_JS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * `domText`：把（離線的）DOM 節點轉成保留段落與換行的純文字。
 *
 * 為什麼一定要測：collect 腳本讀的是 `cloneNode` 出來的節點，而 `innerText`
 * 對「未被渲染」的元素會退回 `textContent`——**沒有換行**。使用者的症状是
 * 「AI 有換行的地方匯入後沒有換行，要自己重排格式」。
 *
 * 做法：把 `DOM_TEXT_JS` 原文交給 node，配一個最小的假 DOM（只實作
 * `nodeType / nodeValue / tagName / childNodes`），逐句比對輸出。
 */
class AiDomTextTest {

    private val harness = """
        $DOM_TEXT_JS

        function T(s) { return { nodeType: 3, nodeValue: s, childNodes: [] }; }
        function E(tag, kids, cls) { return { nodeType: 1, tagName: tag.toUpperCase(), childNodes: kids || [], className: cls || '' }; }

        var cases = {
            twoParagraphs: E('div', [E('p', [T('a')]), E('p', [T('b')])]),
            twoDivs:       E('div', [E('div', [T('a')]), E('div', [T('b')])]),
            list:          E('ul', [E('li', [T('a')]), E('li', [T('b')])]),
            ordered:       E('ol', [E('li', [T('a')]), E('li', [T('b')])]),
            listWithP:     E('ul', [E('li', [E('p', [T('a')])]), E('li', [E('p', [T('b')])])]),
            table:         E('table', [E('tr', [E('td', [T('a')]), E('td', [T('b')])]), E('tr', [E('td', [T('c')]), E('td', [T('d')])])]),
            a11y:          E('div', [E('span', [T('dup')], 'katex-mathml'), T('a')]),
            lineBreak:     E('p', [T('a'), E('br'), T('b')]),
            pre:           E('pre', [T('x\n  y')]),
            inline:        E('div', [E('span', [T('a')]), T(' '), E('span', [T('b')])]),
            whitespace:    E('p', [T('a   b')]),
            script:        E('div', [E('script', [T('bad()')]), T('a')]),
            nested:        E('div', [E('p', [T('a')]), E('ul', [E('li', [T('b')]), E('li', [T('c')])])]),
            trailingNl:    E('div', [E('p', [T('a')]), E('p', [T('')])])
        };

        var out = {};
        for (var k in cases) out[k] = domText(cases[k]);
        console.log(JSON.stringify(out));
    """.trimIndent()

    private fun runNode(): String {
        val dir = File("build/tmp/jscheck").apply { mkdirs() }
        val file = File(dir, "dom_text.js").apply { writeText(harness) }
        val node = try {
            ProcessBuilder("node", file.absolutePath).redirectErrorStream(true).start()
        } catch (_: Exception) {
            null
        }
        assumeTrue("沒有 node，跳過 JS 行為測試", node != null)
        val out = node!!.inputStream.bufferedReader().readText().trim()
        val exit = node.waitFor()
        assertEquals("dom_text.js 執行失敗\n$out", 0, exit)
        return out
    }

    @Test
    fun `段落保留空行、清單保留逐行`() {
        val out = runNode()
        assertEquals("兩個 <p> 之間要空一行", true, out.contains("\"twoParagraphs\":\"a\\n\\nb\""))
        assertEquals("兩個 <div> 之間只要換行", true, out.contains("\"twoDivs\":\"a\\nb\""))
        assertEquals("無序清單要補圓點", true, out.contains("\"list\":\"• a\\n• b\""))
        assertEquals("有序清單要補號碼", true, out.contains("\"ordered\":\"1. a\\n2. b\""))
        assertEquals("清單項包 <p> 時圓點不能自己佔一行", true, out.contains("\"listWithP\":\"• a\\n• b\""))
        assertEquals("表格一列一行、格子用 | 分隔", true, out.contains("\"table\":\"a | b\\nc | d\""))
        assertEquals("看不見的副本不進正文", true, out.contains("\"a11y\":\"a\""))
        assertEquals("<br> 是硬換行", true, out.contains("\"lineBreak\":\"a\\nb\""))
        assertEquals("<pre> 保留原樣", true, out.contains("\"pre\":\"x\\n  y\""))
        assertEquals("行內元素之間不亂斷", true, out.contains("\"inline\":\"a b\""))
        assertEquals("多餘空白收斂", true, out.contains("\"whitespace\":\"a b\""))
        assertEquals("script 不進正文", true, out.contains("\"script\":\"a\""))
        assertEquals("段落＋清單", true, out.contains("\"nested\":\"a\\n\\n• b\\n• c\""))
        assertEquals("結尾不留下空行", true, out.contains("\"trailingNl\":\"a\""))
    }

    /**
     * 機械護欄：collect 腳本不准再從複本讀 innerText。
     *
     * 這個 bug 的代價是「整份 AI 回覆的段落全黏在一起」，而它不會讓任何東西壞掉、
     * 也不會丟例外——只能靠測試把它擋住。
     */
    @Test
    fun `collect 腳本只用 domText 讀複本`() {
        val js = AiGeminiDriver.collectJs()
        assertTrue("collect 腳本沒有注入 domText", js.contains("function domText(root)"))
        assertTrue("文字仍直接讀複本的 text 屬性", !js.contains("clone.innerText") && !js.contains("bclone.innerText"))
        assertTrue("picks 必須經過 domText", js.contains("domText(clone)"))
    }
}
