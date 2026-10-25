package com.vic.inkflow.agent

import com.vic.inkflow.data.DatabaseManager
import com.vic.inkflow.json.JsonValue
import com.vic.inkflow.json.asNumber
import com.vic.inkflow.json.asString
import com.vic.inkflow.json.field
import com.vic.inkflow.schema.SchemaNode
import com.vic.inkflow.schema.SchemaProp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Creates or replaces a text annotation on a page.
 *
 * Validates that the target page exists in the document. Does not write to the
 * database — the actual insert/update happens in [AgentPanelViewModel.applyEditPlan]
 * after the user confirms the diff. The tool only confirms the operation is feasible.
 */
class UpsertTextTool(
    private val documentUri: String,
    private val db: DatabaseManager
) : AgentTool {

    override val name: String = "upsertText"

    override val description: String =
        "Creates or replaces a text annotation on a page. Supply pageIndex, text, " +
            "modelX, modelY; omit id to create a new annotation."

    override val inputSchema: SchemaNode = SchemaNode.Obj(
        listOf(
            SchemaProp(
                name = "id",
                schema = SchemaNode.Str(),
                description = "The annotation id when replacing an existing one; omitted to create new.",
                required = false
            ),
            SchemaProp(
                name = "pageIndex",
                schema = SchemaNode.Num(integer = true),
                description = "The page index (0-based)."
            ),
            SchemaProp(
                name = "text",
                schema = SchemaNode.Str(),
                description = "The annotation text."
            ),
            SchemaProp(
                name = "modelX",
                schema = SchemaNode.Num(),
                description = "The X position in model space."
            ),
            SchemaProp(
                name = "modelY",
                schema = SchemaNode.Num(),
                description = "The Y position (first baseline) in model space."
            ),
            SchemaProp(
                name = "fontSize",
                schema = SchemaNode.Num(),
                description = "The font size in model points (default 16).",
                required = false
            ),
            SchemaProp(
                name = "colorArgb",
                schema = SchemaNode.Num(integer = true),
                description = "The ARGB colour (default black).",
                required = false
            ),
            SchemaProp(
                name = "boxWidth",
                schema = SchemaNode.Num(),
                description = "The text-box wrap width in model units.",
                required = false
            ),
            SchemaProp(
                name = "alignment",
                schema = SchemaNode.Num(integer = true),
                description = "Text alignment: 0 = left, 1 = centre, 2 = right.",
                required = false
            ),
            SchemaProp(
                name = "baseVersion",
                schema = SchemaNode.Num(integer = true),
                description = "The document version this edit was made against (optimistic locking).",
                required = false
            ),
            SchemaProp(
                name = "baseNonce",
                schema = SchemaNode.Num(integer = true),
                description = "The nonce counterpart of baseVersion.",
                required = false
            )
        )
    )

    override suspend fun run(input: JsonValue): ToolResult = withContext(Dispatchers.IO) {
        val pageIndex = input.field("pageIndex")?.asNumber?.toInt() ?: -1
        if (pageIndex < 0) {
            return@withContext ToolResult(false, "upsertText 需要 pageIndex")
        }
        if (!pageExists(pageIndex)) {
            return@withContext ToolResult(false, "第 $pageIndex 頁不存在")
        }
        val text = input.field("text")?.asString ?: return@withContext ToolResult(false, "upsertText 需要 text")
        val modelX = input.field("modelX")?.asNumber?.toDouble() ?: 0.0
        val modelY = input.field("modelY")?.asNumber?.toDouble() ?: 0.0

        val id = input.field("id")?.asString
        if (id != null && !textAnnotationExists(id)) {
            return@withContext ToolResult(false, "註記 id $id 不存在，無法替換")
        }

        val fields = mutableMapOf<String, JsonValue>()
        fields["op"] = JsonValue.Str("upsert_text")
        id?.let { fields["id"] = JsonValue.Str(it) }
        fields["pageIndex"] = JsonValue.Num(pageIndex.toDouble())
        fields["text"] = JsonValue.Str(text)
        fields["modelX"] = JsonValue.Num(modelX)
        fields["modelY"] = JsonValue.Num(modelY)
        input.field("fontSize")?.let { fields["fontSize"] = it }
        input.field("colorArgb")?.let { fields["colorArgb"] = it }
        input.field("boxWidth")?.let { fields["boxWidth"] = it }
        input.field("alignment")?.let { fields["alignment"] = it }
        input.field("baseVersion")?.let { fields["baseVersion"] = it }
        input.field("baseNonce")?.let { fields["baseNonce"] = it }

        val action = if (id == null) "新增" else "替換 $id"
        ToolResult(
            ok = true,
            summary = "upsertText：第 ${pageIndex + 1} 頁 $action",
            data = JsonValue.Obj(fields)
        )
    }

    private fun pageExists(pageIndex: Int): Boolean {
        val file = localFileOf(documentUri)
        if (!file.exists()) return false
        return runCatching {
            org.apache.pdfbox.Loader.loadPDF(file).use { doc ->
                pageIndex in 0 until doc.numberOfPages
            }
        }.getOrElse { false }
    }

    private fun textAnnotationExists(id: String): Boolean {
        return runCatching {
            db.getTextAnnotationIdsForDocument(documentUri).contains(id)
        }.getOrElse { false }
    }
}

/**
 * Deletes a text annotation by id.
 *
 * Validates that the annotation exists. Does not write to the database — the
 * actual tombstone/delete happens in [AgentPanelViewModel.applyEditPlan]
 * after the user confirms the diff.
 */
class DeleteTextTool(
    private val documentUri: String,
    private val db: DatabaseManager
) : AgentTool {

    override val name: String = "deleteText"

    override val description: String =
        "Deletes a text annotation by id. Supply the annotation id."

    override val inputSchema: SchemaNode = SchemaNode.Obj(
        listOf(
            SchemaProp(
                name = "id",
                schema = SchemaNode.Str(),
                description = "The annotation id to delete."
            ),
            SchemaProp(
                name = "baseVersion",
                schema = SchemaNode.Num(integer = true),
                description = "The document version this edit was made against (optimistic locking).",
                required = false
            ),
            SchemaProp(
                name = "baseNonce",
                schema = SchemaNode.Num(integer = true),
                description = "The nonce counterpart of baseVersion.",
                required = false
            )
        )
    )

    override suspend fun run(input: JsonValue): ToolResult = withContext(Dispatchers.IO) {
        val id = input.field("id")?.asString ?: return@withContext ToolResult(false, "deleteText 需要 id")
        if (!textAnnotationExists(id)) {
            return@withContext ToolResult(false, "註記 id $id 不存在，無法刪除")
        }

        val fields = mutableMapOf<String, JsonValue>()
        fields["op"] = JsonValue.Str("delete_text")
        fields["id"] = JsonValue.Str(id)
        input.field("baseVersion")?.let { fields["baseVersion"] = it }
        input.field("baseNonce")?.let { fields["baseNonce"] = it }

        ToolResult(
            ok = true,
            summary = "deleteText：刪除 $id",
            data = JsonValue.Obj(fields)
        )
    }

    private fun textAnnotationExists(id: String): Boolean {
        return runCatching {
            db.getTextAnnotationIdsForDocument(documentUri).contains(id)
        }.getOrElse { false }
    }
}
