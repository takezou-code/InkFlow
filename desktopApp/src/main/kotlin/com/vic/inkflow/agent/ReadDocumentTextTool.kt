package com.vic.inkflow.agent

import com.vic.inkflow.json.JsonValue
import com.vic.inkflow.schema.SchemaNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File

/**
 * Reads the printed text of every page of a document, in page order.
 *
 * Unlike [ReadPageTextTool] (single page), this is how the agent "looks over
 * the whole thing" before proposing edits. The full text of every page goes
 * into the observation, truncated per page so a long document does not overflow
 * the model's context.
 *
 * Neither reads nor writes the document — it only observes. Writing happens
 * through [UpsertTextTool] / [DeleteTextTool].
 */
class ReadDocumentTextTool(private val documentUri: String) : AgentTool {

    override val name: String = "readDocumentText"

    override val description: String =
        "Reads the printed text of every page of the document, in page order. " +
            "Use it to see what the whole document says before proposing edits."

    override val inputSchema: SchemaNode = SchemaNode.Obj(emptyList())

    override suspend fun run(input: JsonValue): ToolResult = withContext(Dispatchers.IO) {
        val file = localFileOf(documentUri)
        if (!file.exists()) {
            return@withContext ToolResult(false, "the document file is missing")
        }
        try {
            Loader.loadPDF(file).use { document ->
                val pageCount = document.numberOfPages
                val stripper = PDFTextStripper()
                val pages = buildList {
                    for (page in 1..pageCount) {
                        stripper.startPage = page
                        stripper.endPage = page
                        val text = stripper.getText(document).trim()
                        val shown = if (text.length > MAX_TEXT_CHARS_PER_PAGE) {
                            text.take(MAX_TEXT_CHARS_PER_PAGE) + "\n… (truncated)"
                        } else if (text.isEmpty()) {
                            "（此頁無可擷取文字，可能是掃描頁）"
                        } else {
                            text
                        }
                        add("第 $page 頁／共 $pageCount 頁：\n$shown")
                    }
                }
                val joined = pages.joinToString("\n\n---\n\n")
                ToolResult(true, "讀取 $pageCount 頁：\n$joined")
            }
        } catch (e: Exception) {
            ToolResult(false, "文件無法讀取：${e.message}")
        }
    }

    private companion object {
        /** How much of each page's text goes into the model. */
        const val MAX_TEXT_CHARS_PER_PAGE = 3000
    }
}
