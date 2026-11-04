package com.vic.inkflow.data

/**
 * AI 匯入的「下層」模型：切塊、分頁、落頁前的位置。
 *
 * 為什麼在 `:shared`
 * ------------------
 * 這套是平板做 AI 匯入時長出來的三層裡的最後一層（L0 意圖 → L1 契約 → L2 driver
 * → **L3 切塊/排版/落頁**）。桌面的 AI agent 產物（教材筆記）注定要走同一條路：
 * 交付物是「頁面上的文字」，不是對話。以前桌面自己寫了一份單頁排版
 * （`desktopApp/agent/NoteLayout.kt`），結果是兩個 app 對同一段文字有不同的行距與
 * 起排位置，而且桌面不知道頁上已經有墨 → 筆記直接疊在既有內容上。
 *
 * 所以這一層只描述「文字怎麼變成頁上的位置」，不含任何平台型別：
 *  - 檔案用**路徑字串**，不是 `java.io.File`（`RenderedMath`/`Placed.I`）；
 *  - 字型量測由呼叫端注入（Android 是 `Paint`，桌面是 `TextMetrics` 的係數模型）；
 *  - 紙白比例吃 `IntArray`，不是 `Bitmap`。
 *
 * 禁 `android.` / `java.` import（`:shared/commonMain` 鐵律，`ink doctor` 會擋）。
 */

/**
 * M2：從 AI 回覆抓回的文字分塊（一塊 = 一次插入單位，M3 排版引擎消費）。
 *
 * @property fontSize 這一塊的字級（model pt）。平板全部 16；桌面的教材筆記用 22 當
 *   章節標題，所以字級跟著塊走，分頁時逐塊量測。預設值讓既有呼叫端不必改。
 */
data class AiTextChunk(
    val id: String,
    val title: String,
    val body: String,
    val charCount: Int = body.length,
    val fontSize: Float = DEFAULT_AI_FONT_SIZE,
    /**
     * 這一塊是**同一段落被切開後的續塊**。
     *
     * 為什麼需要：一段超過 [AI_CHUNK_MAX_CHARS] 的正文會切成多塊、各成一個 annotation。
     * 排版時若每塊都補段落間距，紙上就會出現「一段變兩段」的假段落。
     * 續塊之間只留行距，段落間距只加在真正的新段落後面。
     */
    val continuesPrevious: Boolean = false
)

/** AI 匯入的預設字級（model pt）：與分頁、寫入端共用的同一個數字。 */
const val DEFAULT_AI_FONT_SIZE = 16f

/** 一個文字塊最多幾個字（超過就按句／硬切）。 */
const val AI_CHUNK_MAX_CHARS = 300

/**
 * AI 排版的水平邊距（model pt）：文字框左緣＝此值，內容寬＝`modelW − 2×此值`。
 *
 * 分頁（[paginateAiBlocks]）與寫入端（`EditorViewModel.insertImportedText`）
 * **必須是同一個數字**，否則 AI 文字的框寬與它的排版寬不一致，畫面會多折一次。
 */
const val AI_MARGIN_H = 48f

/**
 * AI 文字的文字框寬（model pt）＝排版內容寬。**唯一算法**：
 * [paginateAiBlocks] 用它排版，寫入端用它寫 `boxWidth`。
 *
 * 兩邊若各算各的（例如只改了一邊的邊距），AI 文字存的行就會比框寬、畫面多折一次
 * ——`AiPaginationTest` 把這個等式釘住了。
 */
fun aiTextContentWidth(modelW: Float): Float =
    (modelW - AI_MARGIN_H * 2f).coerceAtLeast(com.vic.inkflow.ui.TEXT_BOX_MIN_WIDTH)

/**
 * M3：排版輸出（一塊 = 一個 TextAnnotation）。
 *
 * @property modelY **首行 baseline**，`text_annotations` 表用的就是這個約定。
 *   存成「上緣」會讓每一行掉一個字級的高度。
 * @property boxWidth 折行寬（model pt）。AI 文字與手打文字走同一條路：存的是折行
 *   結果＋框寬，之後折行由渲染器負責；折行冪等，所以畫面完全一致。
 */
data class PlacedText(
    val text: String,
    val modelX: Float,
    val modelY: Float,
    val fontSize: Float,
    val boxWidth: Float
)

/** 數學圖渲染時的參考寬（px）。平板是 KaTeX `MathSnapshot.RENDER_W_PX`；共用層只拿它換算比例。 */
const val MATH_RENDER_REFERENCE_W_PX = 1500f

/** 數學渲染結果（已存檔 PNG）。 */
data class RenderedMath(val path: String, val pxW: Int, val pxH: Int)

/** 排版輸出：文字或數學圖。 */
sealed interface Placed {
    data class T(val t: PlacedText) : Placed
    data class I(
        val path: String,
        val modelX: Float,
        val modelY: Float,
        val modelW: Float,
        val modelH: Float,
        val blockId: String
    ) : Placed
}

/** 引入內容塊：純文字或數學（待渲染），保原文順序。 */
sealed interface AiBlock {
    val id: String
}

data class AiTextBlock(val chunk: AiTextChunk) : AiBlock {
    override val id: String get() = chunk.id
}

data class AiMathBlock(
    override val id: String,
    val html: String,
    val display: Boolean,
    val fallback: String
) : AiBlock
