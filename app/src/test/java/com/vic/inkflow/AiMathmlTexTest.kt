package com.vic.inkflow

import com.vic.inkflow.ui.MATHML_TO_TEX_JS
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * MathML → LaTeX（Qwen 匯入公式的路徑）。
 *
 * 為什麼要測：這段是**字串裡的 JS**，編譯器看不到它；而它壞掉的樣子很容易被誤認成
 * 「公式本來就長這樣」（`x2` 看起來只是排版醜，其實是上標消失）。
 *
 * 做法：把 `MATHML_TO_TEX_JS` 原文交給 node，配一個最小的假 DOM（只實作
 * `nodeType / tagName / childNodes / children / textContent / getAttribute`，
 * 也就是 `mmlTex` 真的會用到的那幾個），逐句比對輸出。
 */
class AiMathmlTexTest {

    private val harness = """
        $MATHML_TO_TEX_JS

        function T(s) { return { nodeType: 3, nodeValue: s, textContent: s }; }
        function E(tag, kids, attrs) {
            kids = kids || [];
            return {
                nodeType: 1,
                tagName: tag.toUpperCase(),
                childNodes: kids,
                children: kids,
                textContent: kids.map(function (k) {
                    return k.nodeValue !== undefined ? k.nodeValue : k.textContent;
                }).join(''),
                getAttribute: function (n) { return (attrs && attrs[n] !== undefined) ? attrs[n] : null; }
            };
        }
        function math(kids, attrs) { return E('math', kids, attrs); }

        var cases = [
            // 上標：無 annotation 的 MathML 就是靠這條救回來的
            ['sup', E('msup', [E('mi', [T('x')]), E('mn', [T('2')])])],
            ['sub', E('msub', [E('mi', [T('a')]), E('mi', [T('n')])])],
            ['frac', E('mfrac', [E('mn', [T('1')]), E('mi', [T('x')])])],
            ['sqrt', E('msqrt', [E('mi', [T('a')])])],
            ['root', E('mroot', [E('mi', [T('x')]), E('mn', [T('3')])])],
            ['func', E('mrow', [
                E('mi', [T('sin')]), E('mo', [T('\u2061')]), E('mi', [T('x')]),
                E('mo', [T('+')]), E('mn', [T('1')])
            ])],
            ['times', E('mo', [T('\u00D7')])],
            ['greek', E('mi', [T('\u03C0')])],
            ['word', E('mi', [T('abc')])],
            ['fenced', E('mfenced', [E('mi', [T('x')]), E('mn', [T('1')])], { open: '[', close: ']' })],
            ['semantics', E('semantics', [
                E('mrow', [E('mn', [T('2')])]),
                E('annotation', [T('2')], { encoding: 'application/x-tex' })
            ])],
            ['container', E('mjx-container', [
                E('mjx-assistive-mml', [E('math', [E('mi', [T('y')])])]),
                E('mjx-math', [E('mjx-mi', [T('DUPLICATE')])])
            ])]
        ];

        var out = {};
        cases.forEach(function (c) { out[c[0]] = mmlTex(c[1]); });
        console.log(JSON.stringify(out));
    """.trimIndent()

    private fun runNode(): String {
        val dir = File("build/tmp/jscheck").apply { mkdirs() }
        val file = File(dir, "mathml_tex.js").apply { writeText(harness) }
        val node = try {
            ProcessBuilder("node", file.absolutePath).redirectErrorStream(true).start()
        } catch (_: Exception) {
            null
        }
        assumeTrue("沒有 node，跳過 JS 行為測試", node != null)
        val out = node!!.inputStream.bufferedReader().readText().trim()
        val exit = node.waitFor()
        assertEquals("mathml_tex.js 執行失敗\n$out", 0, exit)
        return out
    }

    @Test
    fun `MathML 還原成可再渲染的 LaTeX`() {
        // JSON 是扁平的 key→string，直接比對子字串比解析 JSON 更不容易被跳脫搞混。
        val out = runNode()
        assertEquals("上標", true, out.contains("\"sup\":\"{x}^{2}\""))
        assertEquals("下標", true, out.contains("\"sub\":\"{a}_{n}\""))
        assertEquals("分數", true, out.contains("\"frac\":\"\\\\frac{1}{x}\""))
        assertEquals("根號", true, out.contains("\"sqrt\":\"\\\\sqrt{a}\""))
        assertEquals("n 次方根", true, out.contains("\"root\":\"\\\\sqrt[3]{x}\""))
        assertEquals("函數名與運算子", true, out.contains("\"func\":\"\\\\sin x+ 1\""))
        assertEquals("乘號", true, out.contains("\"times\":\"\\\\times \""))
        assertEquals("希臘字母", true, out.contains("\"greek\":\"\u03C0\""))
        assertEquals("多字母識別字", true, out.contains("\"word\":\"\\\\mathrm{abc}\""))
        assertEquals("括號", true, out.contains("\"fenced\":\"\\\\left[x, 1\\\\right]\""))
        // semantics 只能取第一個式子，annotation 是同一份內容的另一種寫法
        assertEquals("semantics", true, out.contains("\"semantics\":\"2\""))
        // MathJax 的無障礙 MathML 與畫面用 CHTML 同時存在時，只能取一份
        assertEquals("mjx 容器", true, out.contains("\"container\":\"y\""))
    }
}
