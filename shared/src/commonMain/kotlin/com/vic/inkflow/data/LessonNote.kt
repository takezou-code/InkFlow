package com.vic.inkflow.data

import com.vic.inkflow.json.JsonValue
import com.vic.inkflow.json.asArray
import com.vic.inkflow.json.asNumber
import com.vic.inkflow.json.asObject
import com.vic.inkflow.json.asString
import com.vic.inkflow.schema.SchemaNode
import com.vic.inkflow.schema.SchemaProp

/**
 * The deliverable of an AI run: a study note built from a page of a document.
 *
 * Why a dedicated type instead of "whatever the model said"
 * -------------------------------------------------------
 * The point of the feature is that the AI produces an *artefact*, the way a
 * coding agent produces an app or a run produces a result — not a chat
 * transcript. An artefact has to be:
 *
 *  - **checkable**: a model that answers "here are three key points" in prose
 *    cannot be verified, cannot be turned into ink, and cannot be undone.
 *  - **editable**: the user has to be able to fix it, because a study note
 *    that is 80% right is worth more than one that is 100% right and locked
 *    away in a chat bubble.
 *  - **portable**: it has to become document content (text annotations on a
 *    page) so it can be synced to the tablet, exported, and thumbnailed like
 *    everything else the user wrote.
 *
 * So the agent answers with JSON constrained to [schema], the JSON is decoded
 * into this type, and the *document layer* turns the blocks into annotations.
 * The blocks are deliberately simple text: the tablet's existing pipeline
 * (`AiTextImport`) already knows how to cut text into pages and measure it,
 * and that pipeline is where the layout decisions belong.
 *
 * The model-facing shape is uniform — every block carries `kind`, `text`,
 * and optionally `detail`, `options`, `answerIndex` — because a JSON Schema
 * over heterogeneous objects is a schema language the local engines handle
 * badly, and because it keeps one encoder and one decoder instead of six.
 */
data class LessonNote(
    val title: String,
    val blocks: List<NoteBlock>
) {

    /** The note as the model sees it. */
    fun toJson(): JsonValue = JsonValue.Obj(
        mapOf(
            "title" to JsonValue.Str(title),
            "blocks" to JsonValue.Arr(blocks.map { it.toJson() })
        )
    )

        companion object {

            /**
             * The shape of one block.
             *
             * Declared before [schema] because companion properties are
             * initialised in declaration order, and [schema] reads this one.
             */
            private val blockSchema: SchemaNode = SchemaNode.Obj(
                listOf(
                    SchemaProp(
                        name = "kind",
                        schema = SchemaNode.Str(
                            listOf("heading", "paragraph", "bullet", "example", "flashcard", "quiz")
                        ),
                        description = "What this block is."
                    ),
                    SchemaProp(
                        name = "text",
                        schema = SchemaNode.Str(),
                        description = "The block's main text: the heading, the paragraph, the bullet, " +
                            "the example's question, the flashcard's front, the quiz's question."
                    ),
                    SchemaProp(
                        name = "detail",
                        schema = SchemaNode.Str(),
                        description = "The block's second half: an example's answer or a flashcard's back.",
                        required = false
                    ),
                    SchemaProp(
                        name = "options",
                        schema = SchemaNode.Arr(SchemaNode.Str()),
                        description = "Answer options, for a quiz block only.",
                        required = false
                    ),
                    SchemaProp(
                        name = "answerIndex",
                        schema = SchemaNode.Num(integer = true),
                        description = "Which option is correct, for a quiz block only (0-based).",
                        required = false
                    )
                )
            )

            /** The JSON Schema the model's answer is constrained to. */
            val schema: SchemaNode = SchemaNode.Obj(
                listOf(
                    SchemaProp(
                        name = "title",
                        schema = SchemaNode.Str(),
                        description = "A short title for the note (the topic, not a sentence)."
                    ),
                    SchemaProp(
                        name = "blocks",
                        schema = SchemaNode.Arr(blockSchema),
                        description = "The blocks of the note, in reading order."
                    )
                )
            )

            /**
             * Decodes a note from the model's answer.
             *
             * Returns `null` when the answer is not a usable note — the caller
             * then asks the model again with the reason attached, which is
             * more likely to fix the answer than refusing to show anything.
             */
            fun decode(json: JsonValue): LessonNote? {
                val title = json.asObject?.get("title")?.asString?.trim().orEmpty()
                val rawBlocks = json.asObject?.get("blocks")?.asArray ?: return null
                val blocks = ArrayList<NoteBlock>(rawBlocks.size)
                for (raw in rawBlocks) {
                    val block = decodeBlock(raw) ?: return null
                    blocks += block
                }
                if (blocks.isEmpty()) return null
                return LessonNote(title.ifEmpty { "Untitled note" }, blocks)
            }

            private fun decodeBlock(raw: JsonValue): NoteBlock? {
                val kind = raw.asObject?.get("kind")?.asString ?: return null
                val text = raw.asObject?.get("text")?.asString?.trim().orEmpty()
                val detail = raw.asObject?.get("detail")?.asString?.trim().orEmpty()
                val options = raw.asObject?.get("options")?.asArray
                    ?.mapNotNull { it.asString }
                    .orEmpty()
                val answerIndex = raw.asObject?.get("answerIndex")?.asNumber?.toInt()

                return when (kind) {
                    "heading" -> if (text.isEmpty()) null else NoteBlock.Heading(text)
                    "paragraph" -> if (text.isEmpty()) null else NoteBlock.Paragraph(text)
                    "bullet" -> if (text.isEmpty()) null else NoteBlock.Bullet(text)
                    "example" -> {
                        if (text.isEmpty() || detail.isEmpty()) null
                        else NoteBlock.Example(question = text, answer = detail)
                    }
                    "flashcard" -> {
                        if (text.isEmpty() || detail.isEmpty()) null
                        else NoteBlock.Flashcard(front = text, back = detail)
                    }
                    "quiz" -> {
                        val index = answerIndex ?: return null
                        if (text.isEmpty() || options.size < 2) null
                        else if (index !in options.indices) null
                        else NoteBlock.Quiz(question = text, options = options, answerIndex = index)
                    }
                    else -> null
                }
            }
        }
}

/**
 * One piece of a note.
 *
 * The kinds are the vocabulary of a study note: a heading to organise, prose
 * to explain, bullets to summarise, a worked example, a flashcard to drill,
 * and a quiz to self-test with.
 */
sealed interface NoteBlock {

    /** The block's `kind` in the model-facing JSON. */
    val kind: String

    /** The block's main text. */
    val text: String

    /** A section title. */
    data class Heading(override val text: String) : NoteBlock {
        override val kind: String get() = "heading"
    }

    /** A paragraph of explanation. */
    data class Paragraph(override val text: String) : NoteBlock {
        override val kind: String get() = "paragraph"
    }

    /** A single point in a list. */
    data class Bullet(override val text: String) : NoteBlock {
        override val kind: String get() = "bullet"
    }

    /** A worked example: the question and its answer. */
    data class Example(
        val question: String,
        val answer: String
    ) : NoteBlock {
        override val kind: String get() = "example"
        override val text: String get() = question
    }

    /** A card to drill: the front and the back. */
    data class Flashcard(
        val front: String,
        val back: String
    ) : NoteBlock {
        override val kind: String get() = "flashcard"
        override val text: String get() = front
    }

    /** A self-test question with its options. */
    data class Quiz(
        val question: String,
        val options: List<String>,
        val answerIndex: Int
    ) : NoteBlock {
        override val kind: String get() = "quiz"
        override val text: String get() = question
    }

    /** The block as the model sees it. */
    fun toJson(): JsonValue {
        val fields = LinkedHashMap<String, JsonValue>()
        fields["kind"] = JsonValue.Str(kind)
        fields["text"] = JsonValue.Str(text)
        when (this) {
            is Heading, is Paragraph, is Bullet -> Unit
            is Example -> fields["detail"] = JsonValue.Str(answer)
            is Flashcard -> fields["detail"] = JsonValue.Str(back)
            is Quiz -> {
                fields["options"] = JsonValue.Arr(options.map { JsonValue.Str(it) })
                fields["answerIndex"] = JsonValue.Num(answerIndex.toDouble())
            }
        }
        return JsonValue.Obj(fields)
    }
}
