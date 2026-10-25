package com.vic.inkflow.agent

import com.vic.inkflow.json.JsonValue
import com.vic.inkflow.json.asArray
import com.vic.inkflow.json.asNumber
import com.vic.inkflow.json.asObject
import com.vic.inkflow.json.asString
import com.vic.inkflow.schema.SchemaNode
import com.vic.inkflow.schema.SchemaProp

/**
 * The agent's deliverable: a list of document-level edit operations.
 *
 * Every op maps one-to-one to a [ProposalOp] in the sync protocol
 * (`shared/.../sync/SyncDtos.kt:240`), so the desktop can apply the plan
 * locally and push it to the tablet through the same proposal queue — no
 * second translation layer. The platform layer converts each [EditOp] to a
 * [ProposalOp] (Gson serialisation happens there, not in `:shared`).
 *
 * ## Why not LessonNote
 *
 * `LessonNote` (`shared/.../data/LessonNote.kt`) is a single-page study note.
 * An editing agent works on the whole document: it needs to create, modify and
 * delete text annotations and strokes across pages. `EditPlan` carries those
 * ops, not a rendered note.
 *
 * ## Schema
 *
 * The model-facing JSON is a flat list of ops. Each op carries `op` plus the
 * fields relevant to that op type; missing fields are validated at decode
 * time. We deliberately do not use `oneOf` (local engines handle it badly),
 * so the schema is permissive and the decoder is strict.
 */
data class EditPlan(val ops: List<EditOp>) {

    /** The plan as JSON, for preview/diff display. */
    fun toJson(): JsonValue = JsonValue.Obj(
        mapOf(
            "ops" to JsonValue.Arr(ops.map { op -> op.toJson() })
        )
    )

    companion object {

        private val opObjectSchema: SchemaNode = SchemaNode.Obj(
            listOf(
                SchemaProp(
                    name = "op",
                    schema = SchemaNode.Str(
                        listOf("upsert_text", "delete_text", "upsert_stroke", "delete_stroke")
                    ),
                    description = "What this operation does."
                ),
                SchemaProp(
                    name = "id",
                    schema = SchemaNode.Str(),
                    description = "The object id (required for delete_*; optional for upsert_* when creating).",
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
                ),
                SchemaProp(
                    name = "pageIndex",
                    schema = SchemaNode.Num(integer = true),
                    description = "The page index (0-based) for text/stroke upserts.",
                    required = false
                ),
                SchemaProp(
                    name = "text",
                    schema = SchemaNode.Str(),
                    description = "The text content for upsert_text.",
                    required = false
                ),
                SchemaProp(
                    name = "modelX",
                    schema = SchemaNode.Num(),
                    description = "The X position in model space for upsert_text / upsert_stroke.",
                    required = false
                ),
                SchemaProp(
                    name = "modelY",
                    schema = SchemaNode.Num(),
                    description = "The Y position (first baseline) in model space for upsert_text.",
                    required = false
                ),
                SchemaProp(
                    name = "fontSize",
                    schema = SchemaNode.Num(),
                    description = "The font size in model points for upsert_text.",
                    required = false
                ),
                SchemaProp(
                    name = "colorArgb",
                    schema = SchemaNode.Num(integer = true),
                    description = "The ARGB colour for upsert_text.",
                    required = false
                ),
                SchemaProp(
                    name = "boxWidth",
                    schema = SchemaNode.Num(),
                    description = "The text-box wrap width in model units for upsert_text.",
                    required = false
                ),
                SchemaProp(
                    name = "alignment",
                    schema = SchemaNode.Num(integer = true),
                    description = "Text alignment: 0 = left, 1 = centre, 2 = right.",
                    required = false
                )
            )
        )

        private val opArraySchema: SchemaNode = SchemaNode.Arr(opObjectSchema)

        /** The JSON Schema the model's answer is constrained to. */
        val schema: SchemaNode = SchemaNode.Obj(
            listOf(
                SchemaProp(
                    name = "ops",
                    schema = opArraySchema,
                    description = "The edit operations, in the order they should be applied."
                )
            )
        )

        /** Decodes an EditPlan from the model's JSON answer. */
        fun decode(json: JsonValue): EditPlan? {
            val opsArray = json.asObject?.get("ops")?.asArray ?: return null
            val ops = mutableListOf<EditOp>()
            for (raw in opsArray) {
                val op = decodeOp(raw) ?: return null
                ops += op
            }
            return EditPlan(ops)
        }

        private fun decodeOp(raw: JsonValue): EditOp? {
            val obj = raw.asObject ?: return null
            val opType = obj.get("op")?.asString ?: return null

            val id = obj.get("id")?.asString
            val baseVersion = obj.get("baseVersion")?.asNumber?.toInt()
            val baseNonce = obj.get("baseNonce")?.asNumber?.toInt()

            return when (opType) {
                "upsert_text" -> {
                    val pageIndex = obj.get("pageIndex")?.asNumber?.toInt() ?: return null
                    val text = obj.get("text")?.asString?.takeIf { it.isNotBlank() } ?: return null
                    val modelX = obj.get("modelX")?.asNumber?.toFloat() ?: 0f
                    val modelY = obj.get("modelY")?.asNumber?.toFloat() ?: 0f
                    val fontSize = obj.get("fontSize")?.asNumber?.toFloat() ?: 16f
                    val colorArgb = obj.get("colorArgb")?.asNumber?.toInt() ?: 0xFF000000.toInt()
                    val boxWidth = obj.get("boxWidth")?.asNumber?.toFloat()
                    val alignment = obj.get("alignment")?.asNumber?.toInt() ?: 0

                    EditOp.UpsertText(
                        id = id,
                        pageIndex = pageIndex,
                        text = text,
                        modelX = modelX,
                        modelY = modelY,
                        fontSize = fontSize,
                        colorArgb = colorArgb,
                        boxWidth = boxWidth,
                        alignment = alignment,
                        baseVersion = baseVersion,
                        baseNonce = baseNonce
                    )
                }
                "delete_text" -> {
                    val targetId = id ?: return null
                    EditOp.DeleteText(
                        id = targetId,
                        baseVersion = baseVersion,
                        baseNonce = baseNonce
                    )
                }
                "upsert_stroke", "delete_stroke" -> null // signposted for future; not yet implemented
                else -> null
            }
        }
    }
}

/** One edit operation the agent can propose. */
sealed interface EditOp {

    /** Serialises this op to the model-facing JSON shape. */
    fun toJson(): JsonValue

    /** Create or replace a text annotation on a page. */
    data class UpsertText(
        val id: String?,               // null = create new (platform mints id)
        val pageIndex: Int,
        val text: String,
        val modelX: Float,
        val modelY: Float,
        val fontSize: Float = 16f,
        val colorArgb: Int = 0xFF000000.toInt(),
        val boxWidth: Float? = null,
        val alignment: Int = 0,
        val baseVersion: Int? = null,
        val baseNonce: Int? = null
    ) : EditOp {
        override fun toJson(): JsonValue = JsonValue.Obj(
            buildMap<String, JsonValue> {
                put("op", JsonValue.Str("upsert_text"))
                id?.let { put("id", JsonValue.Str(it)) }
                put("pageIndex", JsonValue.Num(pageIndex.toDouble()))
                put("text", JsonValue.Str(text))
                put("modelX", JsonValue.Num(modelX.toDouble()))
                put("modelY", JsonValue.Num(modelY.toDouble()))
                put("fontSize", JsonValue.Num(fontSize.toDouble()))
                put("colorArgb", JsonValue.Num(colorArgb.toDouble()))
                boxWidth?.let { put("boxWidth", JsonValue.Num(it.toDouble())) }
                if (alignment != 0) put("alignment", JsonValue.Num(alignment.toDouble()))
            }
        )
    }

    /** Delete a text annotation by id. */
    data class DeleteText(
        val id: String,
        val baseVersion: Int? = null,
        val baseNonce: Int? = null
    ) : EditOp {
        override fun toJson(): JsonValue = JsonValue.Obj(
            buildMap<String, JsonValue> {
                put("op", JsonValue.Str("delete_text"))
                put("id", JsonValue.Str(id))
            }
        )
    }

    /** Create or replace a stroke. Not yet implemented — signposted. */
    data class UpsertStroke(
        val id: String?,
        val pageIndex: Int,
        val points: List<com.vic.inkflow.data.Point>,
        val color: Int,
        val strokeWidth: Float,
        val baseVersion: Int? = null,
        val baseNonce: Int? = null
    ) : EditOp {
        override fun toJson(): JsonValue = JsonValue.Obj(
            buildMap<String, JsonValue> {
                put("op", JsonValue.Str("upsert_stroke"))
                id?.let { put("id", JsonValue.Str(it)) }
                put("pageIndex", JsonValue.Num(pageIndex.toDouble()))
            }
        )
    }

    /** Delete a stroke by id. Not yet implemented — signposted. */
    data class DeleteStroke(
        val id: String,
        val baseVersion: Int? = null,
        val baseNonce: Int? = null
    ) : EditOp {
        override fun toJson(): JsonValue = JsonValue.Obj(
            buildMap<String, JsonValue> {
                put("op", JsonValue.Str("delete_stroke"))
                put("id", JsonValue.Str(id))
            }
        )
    }
}
