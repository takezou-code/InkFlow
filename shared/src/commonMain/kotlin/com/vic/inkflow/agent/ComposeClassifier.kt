package com.vic.inkflow.agent

import com.vic.inkflow.json.Json
import com.vic.inkflow.json.asString
import com.vic.inkflow.json.asObject
import com.vic.inkflow.schema.SchemaNode
import com.vic.inkflow.schema.SchemaProp
import com.vic.inkflow.schema.toJsonSchemaDocument

/**
 * Which kind of artefact the agent should produce for this instruction.
 *
 * The panel deliberately shows no mode switch: the user describes the intent,
 * the model routes. `note` = the user wants a new artefact made from the
 * material (summary, teaching note, flashcards, quiz). `edit` = the user wants
 * the document itself changed (rewritten, deleted, reordered, cleaned up).
 *
 * When routing fails — no server, unparseable answer, anything — we fall back
 * to [ComposeKind.NOTE]: a note the user can discard is a harmless failure,
 * and forcing a router failure to *block* the run would be worse.
 */
enum class ComposeKind {
    NOTE,
    EDIT
}

/**
 * Routes an instruction to a compose phase.
 *
 * One schema-constrained request. It deliberately does NOT look at the page —
 * the intent lives in the verb, not the content — so a short prompt is enough.
 */
suspend fun classifyComposeKind(provider: AgentProvider, instruction: String): ComposeKind {
    val request = LlmRequest(
        systemPrompt = buildString {
            appendLine("You route requests inside a note-taking app's AI agent.")
            appendLine("The app can only do two things:")
            appendLine("1. make a study artefact from the material (a note, a summary, flashcards, a quiz) — answer \"note\";")
            appendLine("2. change the document's own annotations (rewrite, delete, merge, reorder, fix, clean up) — answer \"edit\".")
            appendLine()
            appendLine("Answer with exactly one JSON object, nothing else: {\"kind\": \"note\" | \"edit\"}")
        },
        userPrompt = "Request: $instruction",
        images = emptyList(),
        responseSchema = LlmSchema(
            "compose_kind",
            SchemaNode.Obj(
                listOf(
                    SchemaProp(
                        name = "kind",
                        schema = SchemaNode.Str(listOf("note", "edit")),
                        description = "The artefact the user wants."
                    )
                )
            ).toJsonSchemaDocument()
        )
    )

    val response = provider.complete(request)
    val text = response.text.trim()
    val parsed = Json.parse(text) ?: return ComposeKind.NOTE
    val kind = parsed.asObject?.get("kind")?.asString
    return when (kind) {
        "edit" -> ComposeKind.EDIT
        else -> ComposeKind.NOTE
    }
}
