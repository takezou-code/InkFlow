package com.vic.inkflow.agent

import androidx.compose.ui.graphics.toAwtImage
import com.vic.inkflow.json.JsonValue
import com.vic.inkflow.json.asNumber
import com.vic.inkflow.json.field
import com.vic.inkflow.render.PdfEngine
import com.vic.inkflow.schema.SchemaNode
import com.vic.inkflow.schema.SchemaProp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import javax.imageio.ImageIO

/**
 * The desktop's agent tools — the agent's hands on the document.
 *
 * Two tools, and both are about *looking*:
 *
 *  - [ReadPageTextTool] reads what is printed on the page. Cheap, exact, and
 *    the right tool for a textbook with a text layer.
 *  - [RenderPageTool] renders the page as an image so a **vision** model can
 *    see it — a diagram, a worked example drawn by hand, a page whose text
 *    layer is empty because it is a scan.
 *
 * Neither tool writes. Writing is the landing step, and it happens under the
 * user's hand: the note is shown first and lands only when they accept it.
 */

/** The local file a document URI points at. */
internal fun localFileOf(uri: String): File {
    val path = if (uri.startsWith("file://")) {
        runCatching { URI(uri).path }.getOrNull() ?: uri.removePrefix("file://")
    } else {
        uri
    }
    return File(path)
}

/**
 * Reads the printed text of one page.
 *
 * The text goes into the observation, which is what the model reads, so it is
 * truncated: a whole textbook page is more context than a local model can
 * hold, and the tail of a page is usually the least useful part of it.
 */
class ReadPageTextTool(private val documentUri: String) : AgentTool {

    override val name: String = "readPageText"

    override val description: String =
        "Reads the printed text of one page of the document. Use it to see what the page says."

    override val inputSchema: SchemaNode = SchemaNode.Obj(
        listOf(
            SchemaProp(
                name = "page",
                schema = SchemaNode.Num(integer = true),
                description = "The page number, counting from 1."
            )
        )
    )

    override suspend fun run(input: JsonValue): ToolResult = withContext(Dispatchers.IO) {
        val page = input.field("page")?.asNumber?.toInt() ?: 1
        val file = localFileOf(documentUri)
        if (!file.exists()) {
            return@withContext ToolResult(false, "the document file is missing")
        }
        try {
            Loader.loadPDF(file).use { document ->
                if (page < 1 || page > document.numberOfPages) {
                    return@withContext ToolResult(
                        false,
                        "page $page does not exist (the document has ${document.numberOfPages} pages)"
                    )
                }
                val stripper = PDFTextStripper()
                stripper.startPage = page
                stripper.endPage = page
                val text = stripper.getText(document).trim()
                if (text.isEmpty()) {
                    ToolResult(
                        false,
                        "page $page has no extractable text - it is probably a scan; " +
                            "render it as an image instead"
                    )
                } else {
                    val shown = if (text.length > MAX_TEXT_CHARS) {
                        text.take(MAX_TEXT_CHARS) + "\n… (truncated)"
                    } else {
                        text
                    }
                    ToolResult(true, "page $page reads:\n$shown")
                }
            }
        } catch (e: Exception) {
            ToolResult(false, "the page could not be read: ${e.message}")
        }
    }

    private companion object {
        /** How much of a page's text goes into the model. */
        const val MAX_TEXT_CHARS = 4000
    }
}

/**
 * Renders one page as an image, so a vision model can look at it.
 *
 * The image is handed to the model as an attachment on the following request
 * — the loop carries the images the tools produced, which is how the note can
 * be written from something the model actually saw.
 */
class RenderPageTool(
    private val documentUri: String,
    private val engine: PdfEngine
) : AgentTool {

    override val name: String = "renderPage"

    override val description: String =
        "Renders one page of the document as an image. Use it for pages with diagrams, " +
            "handwriting, or no extractable text."

    override val inputSchema: SchemaNode = SchemaNode.Obj(
        listOf(
            SchemaProp(
                name = "page",
                schema = SchemaNode.Num(integer = true),
                description = "The page number, counting from 1."
            )
        )
    )

    override suspend fun run(input: JsonValue): ToolResult = withContext(Dispatchers.IO) {
        val page = input.field("page")?.asNumber?.toInt() ?: 1
        val bitmap = engine.renderPage(documentUri, page - 1, RENDER_WIDTH_PX)
            ?: return@withContext ToolResult(false, "page $page could not be rendered")
        val bytes = try {
            val out = ByteArrayOutputStream()
            ImageIO.write(bitmap.toAwtImage(), "png", out)
            out.toByteArray()
        } catch (e: Exception) {
            return@withContext ToolResult(false, "page $page could not be encoded: ${e.message}")
        }
        ToolResult(
            ok = true,
            summary = "rendered page $page as an image",
            images = listOf(LlmImage(bytes, "image/png"))
        )
    }

    private companion object {
        /** Wide enough for a model to read the page, small enough to send. */
        const val RENDER_WIDTH_PX = 1200
    }
}
