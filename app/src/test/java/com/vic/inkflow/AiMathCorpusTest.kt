package com.vic.inkflow

import com.vic.inkflow.ui.AiMathBlock
import com.vic.inkflow.ui.AiTextBlock
import com.vic.inkflow.ui.convertLatexInProse
import com.vic.inkflow.ui.convertLatexMath
import com.vic.inkflow.ui.escHtml
import com.vic.inkflow.ui.hasMathScent
import com.vic.inkflow.ui.looksLikeInlineMath
import com.vic.inkflow.ui.normalizeDollarMath
import com.vic.inkflow.ui.splitAiBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 數學語料測試（JVM、全離線）：
 * - gemini_calculus_80.txt：使用者貼回之 Gemini 自然輸出 74 條（天然 regression）
 * - 錨點：手挑高價值格式，斷言精確切分/轉換
 * - 不變量：不 crash、不丟字、未知指令原樣保留
 */
class AiMathCorpusTest {

    private fun corpus(): String {
        val url = requireNotNull(javaClass.getResource("/ai-math/gemini_calculus_80.txt")) { "corpus missing" }
        return url.readText(Charsets.UTF_8)
    }

    @Test fun corpus_loads() {
        val t = corpus()
        assertTrue("corpus too small: ${t.length}", t.length > 40000)
        assertTrue("no display math", t.split("\$\$").size > 200)
    }

    @Test fun corpus_split_noCrash_invariants() {
        val t = corpus()
        val blocks = splitAiBlocks(t)
        assertTrue("too few blocks: ${blocks.size}", blocks.size >= 200)
        val ids = blocks.map { it.id }
        assertEquals("ids not unique", ids.size, ids.toSet().size)
        val math = blocks.filterIsInstance<AiMathBlock>()
        assertTrue("too few math blocks: ${math.size}", math.size >= 100)
        assertTrue("blank math html", math.all { it.html.isNotBlank() })
        // 不丟字：關鍵詞必須存活（散落在 text body 或 math fallback）
        val joined = blocks.joinToString("\n") {
            when (it) {
                is AiTextBlock -> it.chunk.body
                is AiMathBlock -> it.fallback
            }
        }
        assertTrue("mass loss: ${joined.length} vs ${t.length}", joined.length >= t.length * 0.5)
        for (kw in listOf("泰勒", "貝氏", "拉格朗日", "奇異值", "鴿籠")) {
            assertTrue("lost keyword: $kw", joined.contains(kw))
        }
    }

    // ---- 錨點：切分 ----

    @Test fun anchor_inlineLimit() {
        val blocks = splitAiBlocks("求極限 \$\\lim_{x \\to 0} \\frac{\\sin(3x) - 3x}{x^3}\$。")
        assertEquals(1, blocks.size)
        val m = blocks[0] as AiMathBlock
        assertEquals(false, m.display)
    }

    @Test fun anchor_casesDisplay() {
        val blocks = splitAiBlocks("\$\$\\begin{cases} x \\equiv 2 \\\\ x \\equiv 3 \\end{cases}\$\$")
        assertEquals(1, blocks.size)
        val m = blocks[0] as AiMathBlock
        assertEquals(true, m.display)
    }

    @Test fun anchor_bmatrixInline() {
        // 裸 \begin..\end 即 display（KaTeX auto-render 同語義），整段進數學圖
        val blocks = splitAiBlocks("矩陣 \$A = \\begin{bmatrix} 4 & 1 \\\\ 2 & 3 \\end{bmatrix}\$ 求特徵值。")
        val maths = blocks.filterIsInstance<AiMathBlock>()
        assertTrue(maths.isNotEmpty())
        assertEquals(true, maths[0].display)
    }

    @Test fun anchor_brokenDelimiters_noCrash_noLoss() {
        // 天然髒數據（語料 #47 型）：分隔符錯位，不 crash，主體文字不丟
        val blocks = splitAiBlocks("拆解：\$\$\\frac{1}{x(x+1)}\$\$x = 0 \\implies A = 1\$\$x = -1 尾巴")
        val joined = blocks.joinToString("|") {
            when (it) {
                is AiTextBlock -> it.chunk.body
                is AiMathBlock -> it.fallback
            }
        }
        assertTrue("lost body: $joined", joined.contains("尾巴"))
    }

    @Test fun anchor_shortInline() {
        assertEquals(1, splitAiBlocks("設 \$f(x)\$ 連續。").filterIsInstance<AiMathBlock>().size)
        assertEquals(1, splitAiBlocks("區間 \$[a,b]\$ 上。").filterIsInstance<AiMathBlock>().size)
        assertEquals(1, splitAiBlocks("變數 \$x\$ 滿足。").filterIsInstance<AiMathBlock>().size)
    }

    @Test fun anchor_moneyNotMath() {
        val blocks = splitAiBlocks("價格 \$100 不變，\$200 也行。")
        assertEquals(0, blocks.filterIsInstance<AiMathBlock>().size)
    }

    // ---- 錨點：Unicode 轉換（語料缺口表全覆蓋） ----

    @Test fun convert_brackets_arrows() {
        assertEquals("⟨ 2, 1, 7 ⟩", convertLatexMath("\\langle 2, 1, 7 \\rangle"))
        assertEquals("| x |", convertLatexMath("\\vert x \\vert"))
        assertEquals("A ⟹ B", convertLatexMath("A \\implies B"))
    }

    @Test fun convert_funcNames_stripped() {
        assertEquals("sin x", convertLatexMath("\\sin x"))
        assertEquals("ln x", convertLatexMath("\\ln x"))
        assertEquals("det A", convertLatexMath("\\det A"))
    }

    @Test fun convert_mathAlphabets() {
        assertEquals("ℝ", convertLatexMath("\\mathbb{R}"))
        assertEquals("ℤ", convertLatexMath("\\mathbb{Z}"))
        assertEquals("L", convertLatexMath("\\mathcal{L}"))
        assertEquals("v", convertLatexMath("\\mathbf{v}"))
        assertEquals("x", convertLatexMath("\\mathit{x}"))
    }

    @Test fun convert_bigOps_ceil() {
        assertEquals("⋃ᵢ₌₁ⁿ Aᵢ", convertLatexMath("\\bigcup_{i=1}^n A_i"))
        assertEquals("⌈ (N)/(k) ⌉", convertLatexMath("\\left\\lceil \\frac{N}{k} \\right\\rceil"))
        assertEquals("∭ E", convertLatexMath("\\iiint E"))
        // sizing 指令只吃分隔符，不准吃掉運算子（bigcupleftrightarrow 回歸）
        assertEquals("A ( B )", convertLatexMath("A \\left( B \\right)"))
        assertEquals("a ↔ b", convertLatexMath("a \\leftrightarrow b"))
    }

    @Test fun convert_frac_sqrt_sup_sub() {
        assertEquals("(1)/(2)", convertLatexMath("\\frac{1}{2}"))
        assertEquals("3√(16)", convertLatexMath("\\sqrt[3]{16}"))
        assertEquals("x²", convertLatexMath("x^2"))
        assertEquals("aₙ", convertLatexMath("a_n"))
    }

    @Test fun convert_unknown_preserved() {
        assertEquals("\\qwerty{z}", convertLatexMath("\\qwerty{z}"))
    }

    // ---- 錨點：gate / HTML ----

    @Test fun gate_looksLikeInlineMath() {
        assertEquals(true, looksLikeInlineMath("f(x)"))
        assertEquals(true, looksLikeInlineMath("[a,b]"))
        assertEquals(true, looksLikeInlineMath("x"))
        assertEquals(true, looksLikeInlineMath("E=mc^2"))
        assertEquals(true, looksLikeInlineMath("\\alpha"))
        assertEquals(false, looksLikeInlineMath("100"))
        assertEquals(false, looksLikeInlineMath("5 and "))
        assertEquals(false, looksLikeInlineMath("hello world"))
        assertEquals(false, looksLikeInlineMath(""))
    }

    @Test fun normalize_and_escape() {
        assertEquals("解 \\(f(x)\\) 得", normalizeDollarMath("解 \$f(x)\$ 得"))
        assertEquals("價格 \$100 不變", normalizeDollarMath("價格 \$100 不變"))
        assertEquals("&lt;a&gt;&amp;&quot;", escHtml("<a>&\""))
    }

    @Test fun prose_money_untouched() {
        assertEquals("價格 \$100 不變", convertLatexInProse("價格 \$100 不變"))
    }

    @Test fun scent_catchesMath() {
        // 使用者回報案例：0 到無限大 e^(-2x) 積分題（三重命中）
        assertTrue(hasMathScent("計算瑕積分 \$\\int_0^\\infty x e^{-2x} \\, dx\$。"))
        assertTrue(hasMathScent("\$\$x^2 + y^2 = r^2\$\$"))
        assertTrue(hasMathScent("解 \$f(x)\$ 得"))
        assertTrue(hasMathScent("其中 \\(a+b\\) 成立"))
        assertTrue(hasMathScent("面積 ∫01 積分"))
        assertTrue(hasMathScent("求和 \\sum_{i=1}^n i"))
    }

    @Test fun scent_rejectsProse() {
        assertTrue(!hasMathScent("價格 \$100 不變"))
        assertTrue(!hasMathScent("今天天氣很好，適合出門散步"))
        assertTrue(!hasMathScent(""))
        assertTrue(!hasMathScent("hello world"))
        assertTrue(!hasMathScent("\$5 and \$10"))
    }
}
