package com.vic.inkflow

import com.vic.inkflow.ui.AiReadiness
import com.vic.inkflow.ui.aiProbeKey
import com.vic.inkflow.ui.decodeProbeRecord
import com.vic.inkflow.ui.encodeProbeRecord
import com.vic.inkflow.ui.parseProbeReport
import com.vic.inkflow.ui.readiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROBE3 解析與持久化的單測。
 *
 * 為什麼值得釘：探針輸出是**唯一**能鎖定新 AI 選擇器的證據。解析一旦悄悄壞掉，
 * 設定頁只會顯示「0/0」，看起來像「那家沒抓到」，實際上是我方解析錯誤——
 * 這種假訊號會讓人改錯地方，比直接當掉更貴。
 */
class AiProbeReportTest {

    // 貼近真實輸出：selector 自帶引號與方括號，正是「照括號切」會切錯的地方。
    private val sample =
        "PROBE3 url=https://kimi.ai/chat/c123 " +
            "| input=[div[contenteditable=\"true\"]=3 | textarea=0 | [role=\"textbox\"]=3] " +
            "| send=[button[type=\"submit\"]=0 | button[aria-label*=\"Send\"]=1] " +
            "| reply=[div[class*=\"markdown\"]=0 | article=5] " +
            "| idle=none | katex=0 | mathjax=2 | fileInput=1"

    @Test
    fun `解析真實探針輸出`() {
        val r = parseProbeReport(sample)
        assertNotNull(r)
        r!!
        assertEquals("https://kimi.ai/chat/c123", r.url)
        assertEquals(3, r.inputs.size)
        // 命中數不是候選數：textarea 這條是 0（這站用 contenteditable），所以是 2/3。
        assertEquals("2/3", r.inputScore())
        assertEquals("1/2", r.sendScore())
        assertEquals("1/2", r.replyScore())
        // 第一個命中才是可信的：候選鏈由具體到泛用。
        assertEquals("div[contenteditable=\"true\"]", r.firstInput()?.selector)
        assertEquals("button[aria-label*=\"Send\"]", r.firstSend()?.selector)
        assertEquals("article", r.firstReply()?.selector)
        assertEquals(5, r.firstReply()?.count)
        assertNull("idle=none 代表這站沒有停止鈕語意", r.idle)
        assertEquals(0, r.katex)
        assertEquals(2, r.mathjax)
        assertEquals(1, r.fileInputs)
    }

    @Test
    fun `摘要只列有資料的段落`() {
        val r = parseProbeReport(sample)!!
        assertEquals("輸入 2/3・送出 1/2・回覆 1/2・公式 2", r.summary())

        // 沒有公式就不要多一個「・公式 0」的尾巴。
        val noMath = parseProbeReport(sample.replace("mathjax=2", "mathjax=0"))!!
        assertFalse(noMath.summary().contains("公式"))
    }

    @Test
    fun `停止鈕有宣告時會解析出 selector`() {
        val r = parseProbeReport(
            sample.replace("idle=none", "idle=button[data-is-streaming=\"true\"]=2")
        )!!
        assertEquals("button[data-is-streaming=\"true\"]", r.idle?.selector)
        assertEquals(2, r.idle?.count)
    }

    @Test
    fun `認不得的字串回 null 而不是半截資料`() {
        assertNull(parseProbeReport(null))
        assertNull(parseProbeReport(""))
        assertNull(parseProbeReport("PROBE3"))          // 有前綴沒內容
        assertNull(parseProbeReport("hello world"))
        // 舊版探針（PROBE 而不是 PROBE3）格式不同，不可硬解。
        assertNull(parseProbeReport("PROBE url=https://x | input=[a=1]"))
        // 少一段就整份不認，避免顯示出看起來正常、其實錯位的數字。
        assertNull(parseProbeReport(sample.substringBefore(" | reply=[")))
    }

    @Test
    fun `紀錄可以原樣存回再讀出`() {
        // raw 內含大量 `|`：持久化用 split(limit=3) 才不會把資料切成碎片。
        val stored = encodeProbeRecord("hydrated", 1_700_000_000_000L, sample)
        assertNotNull(stored)
        val rec = decodeProbeRecord(stored)
        assertNotNull(rec)
        rec!!
        assertEquals("hydrated", rec.tag)
        assertTrue(rec.isHydrated)
        assertEquals(1_700_000_000_000L, rec.at)
        // 存回再讀出必須是同一份報告（不是只對一半的欄位）。
        assertEquals(parseProbeReport(sample), rec.report)
    }

    @Test
    fun `壞資料不進 prefs`() {
        assertNull("解析不了的東西不要存，否則設定頁會一直顯示垃圾", encodeProbeRecord("load", 1L, "壞掉"))
        assertNull(decodeProbeRecord(null))
        assertNull(decodeProbeRecord(""))
        assertNull(decodeProbeRecord("hydrated"))            // 少欄位
        assertNull(decodeProbeRecord("hydrated|123|壞掉"))   // raw 不合法
    }

    @Test
    fun `每家 AI 有自己的診斷格`() {
        val keys = com.vic.inkflow.ui.AiProvider.entries.map { aiProbeKey(it) }
        assertEquals("診斷格不可與別家共用", keys.size, keys.toSet().size)
        assertTrue(aiProbeKey(com.vic.inkflow.ui.AiProvider.KIMI).contains("KIMI"))
    }

    // ── 新欄位：登入／驗證／標題 ──────────────────────────────────

    private fun probe(
        input: String = "div[contenteditable=\"true\"]=1",
        reply: String = "article=1",
        pwd: Int = 0,
        captcha: Int = 0,
        title: String = ""
    ) = "PROBE3 url=https://x.ai/ | input=[$input] | send=[button=1] | reply=[$reply]" +
        " | idle=none | katex=0 | mathjax=0 | fileInput=0 | pwd=$pwd | captcha=$captcha | title=$title"

    @Test
    fun `舊格式沒有新欄位也能解析`() {
        val r = parseProbeReport(sample)!!
        assertEquals(0, r.passwordFields)
        assertEquals(0, r.captcha)
        assertEquals("", r.title)
        // 舊格式的 fileInput 仍要讀得到（吃整段尾巴會變成 0）。
        assertEquals(1, r.fileInputs)
    }

    @Test
    fun `新欄位解析密碼欄與驗證元素`() {
        val r = parseProbeReport(probe(pwd = 2, captcha = 3, title = "Just a moment"))!!
        assertEquals(2, r.passwordFields)
        assertEquals(3, r.captcha)
        assertEquals("Just a moment", r.title)
    }

    @Test
    fun `多出回覆容器 census 也不影響其他欄位`() {
        // census 是後來加在中段的欄位（fileInput 之後、pwd 之前），而且內容含
        // `{n=1,anc=div.a>div.b,kids=div:12,p:3,...}` 這種大量 `,` 與 `=`。
        // 它若把後面的 pwd／title 擠歪，設定頁會顯示「0 個密碼欄」這種假訊號。
        val censusProbe = probe(pwd = 1, title = "Kimi AI 官網")
            .replace(
                " | pwd=",
                " | replyCensus=[div.segment-content=>div.segment-content{n=2," +
                    "anc=div.chat>div.main,kids=div:12,p:3,span:8,txt=540,run=none}] | pwd="
            )
        val r = parseProbeReport(censusProbe)!!
        assertEquals(1, r.passwordFields)
        assertEquals("Kimi AI 官網", r.title)
        assertEquals(1, r.inputs.size)
        assertEquals("article", r.firstReply()?.selector)
        // census 內部若混進 `|`（例如使用者回的內容含直線），欄位會被切壞；
        // JS 端已把 `|` 換成 `/`，這裡釘住「就算有，也不進 parser 的判斷」。
        val withPipe = censusProbe.replace("txt=540", "txt=5/40")
        assertEquals(1, parseProbeReport(withPipe)!!.passwordFields)
    }

    @Test
    fun `可用狀態由 DOM 推論而不是猜`() {
        assertEquals(AiReadiness.READY, parseProbeReport(sample)!!.readiness().state)
        // 有輸入框但抓不到回覆區 → 只能送。
        assertEquals(
            AiReadiness.SEND_ONLY,
            parseProbeReport(probe(reply = "div[class*=\"nope\"]=0"))!!.readiness().state
        )
        // 沒有輸入框但有登入表單 → 需登入（不是「版面改了」）。
        assertEquals(
            AiReadiness.LOGIN_REQUIRED,
            parseProbeReport(probe(input = "textarea=0", pwd = 2))!!.readiness().state
        )
        // 被驗證頁擋住時，即使有密碼欄也要先講「被擋」。
        assertEquals(
            AiReadiness.CAPTCHA,
            parseProbeReport(probe(input = "textarea=0", pwd = 1, captcha = 3))!!.readiness().state
        )
        // 什麼都沒有 → 未就緒。
        assertEquals(
            AiReadiness.NOT_READY,
            parseProbeReport(probe(input = "textarea=0"))!!.readiness().state
        )
    }

    @Test
    fun `狀態標籤給人看的是短字`() {
        assertEquals("就緒", parseProbeReport(sample)!!.readiness().label)
        assertEquals(
            "需登入",
            parseProbeReport(probe(input = "textarea=0", pwd = 1))!!.readiness().label
        )
    }
}
