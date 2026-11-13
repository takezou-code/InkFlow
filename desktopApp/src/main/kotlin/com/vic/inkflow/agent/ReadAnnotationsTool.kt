package com.vic.inkflow.agent

import com.vic.inkflow.data.DatabaseManager
import com.vic.inkflow.json.JsonValue
import com.vic.inkflow.json.asNumber
import com.vic.inkflow.json.asBoolean
import com.vic.inkflow.json.field
import com.vic.inkflow.schema.SchemaNode
import com.vic.inkflow.schema.SchemaProp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lists the text annotations the document already holds — the user's notes
 * and the ones previous AI landings wrote.
 *
 * This is the "eyes" the editing agent needs before it can touch anything:
 * to replace or delete an existing box it needs that box's id, and to place
 * a new box sensibly it needs to know where the content already sits. Without
 * this tool the agent is editing blind — it can add text, but it cannot
 * restructure what is there.
 *
 * Observations only — writing happens through [UpsertTextTool] / [DeleteTextTool],
 * and the actual write happens after the user confirms the plan.
 */
class ReadAnnotationsTool(
    private val documentUri: String,
    private val db: DatabaseManager
) : AgentTool {

    override val name: String = "readAnnotations"

    override val description: String =
        "Lists the text annotations the document already holds — the ones the user " +
            "and previous AI runs wrote — with their id, page, content, position, " +
            "font size, wrap width and flowId. Use it before proposing edits to " +
            "existing notes: replace and delete need the id. Pass pageIndex to read " +
            "one page; omit it to read the whole document."

    override val inputSchema: SchemaNode = SchemaNode.Obj(
        listOf(
            SchemaProp(
                name = "pageIndex",
                schema = SchemaNode.Num(integer = true),
                description = "Only list this page (0-based). Omit to list the whole document.",
                required = false
            ),
            SchemaProp(
                name = "includeStamps",
                schema = SchemaNode.Bool,
                description = "Include emoji stamps in the listing (default false).",
                required = false
            )
        )
    )

    override suspend fun run(input: JsonValue): ToolResult = withContext(Dispatchers.IO) {
        val pageIndex = input.field("pageIndex")?.asNumber?.toInt()
        if (pageIndex != null && pageIndex < 0) {
            return@withContext ToolResult(false, "readAnnotations：pageIndex 不能是負數")
        }
        val includeStamps = input.field("includeStamps")?.asBoolean ?: false

        val all = try {
            if (pageIndex != null) db.getTextAnnotationsForPage(documentUri, pageIndex)
            else db.getAllTextAnnotationsForDocument(documentUri)
        } catch (e: Exception) {
            return@withContext ToolResult(false, "註記讀取失敗：${e.message}")
        }

        val rows = all.filter { includeStamps || !it.isStamp }
        if (rows.isEmpty()) {
            val scope = pageIndex?.let { "第 ${it + 1} 頁" } ?: "整份文件"
            return@withContext ToolResult(true, "$scope 沒有既有的文字註記")
        }

        // Cap what goes into the model's context: a full list of a heavily
        // annotated document can be tens of kilobytes of prose.
        val capLimit = if (pageIndex != null) MAX_PAGE_ROWS else MAX_DOC_ROWS
        val shown = rows.take(capLimit)
        var dropped = rows.size - shown.size

        val sb = StringBuilder()
        val scope = pageIndex?.let { "第 ${it + 1} 頁" } ?: "整份文件"
        sb.append("$scope 既有文字註記（${rows.size} 則，列出 ${shown.size} 則）：\n")
        var total = 0
        for (ann in shown) {
            val text = ann.text.trim().replace('\n', ' ')
            val shownText = if (text.length > MAX_TEXT_PER_ROW) text.take(MAX_TEXT_PER_ROW) + "…" else text
            val box = ann.boxWidth?.let { String.format("boxWidth=%.0f", it) } ?: "未折行"
            val flow = ann.flowId?.let { " flowId=$it" } ?: ""
            val line = "頁 ${ann.pageIndex + 1} 【${ann.id}】「$shownText」@(%.0f,%.0f) fontSize=%.0f $box alignment=${ann.alignment}$flow\n"
                .format(ann.modelX, ann.modelY, ann.fontSize)
            if (total + line.length > MAX_TOTAL_CHARS) { dropped += 1; break }
            total += line.length
            sb.append(line)
        }
        if (dropped > 0) {
            sb.append("… 另有 $dropped 則未列出（內容過長或筆數超過上限）；請用 pageIndex 縮小範圍再讀\n")
        }
        // Tell the model explicitly that ids exist and what they are for.
        sb.append("（replace／delete 需要 id；flowId 相同的註記是同一篇文章的連續區塊）")
        ToolResult(true, sb.toString())
    }

    private companion object {
        /** Rows listed when one page is asked for. */
        const val MAX_PAGE_ROWS = 200

        /** Rows listed when the whole document is asked for. */
        const val MAX_DOC_ROWS = 120

        /** Per-annotation content cap, so one huge stamp-paragraph cannot eat the budget. */
        const val MAX_TEXT_PER_ROW = 300

        /** Hard cap on total observation size. */
        const val MAX_TOTAL_CHARS = 24000
    }
}
