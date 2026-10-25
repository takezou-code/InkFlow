package com.vic.inkflow.agent

import com.vic.inkflow.data.LessonNote
import com.vic.inkflow.json.Json
import com.vic.inkflow.json.JsonValue
import com.vic.inkflow.json.asObject
import com.vic.inkflow.json.asString
import com.vic.inkflow.schema.SchemaNode
import com.vic.inkflow.schema.SchemaProp
import com.vic.inkflow.schema.toJsonSchemaDocument
import com.vic.inkflow.schema.validate

/** What the user asked the agent to do. */
data class AgentGoal(
    /** The instruction, in the user's words. */
    val instruction: String,
    /** Optional hint about what is being worked on (a page, a selection). */
    val pageHint: String = "",
    /** The document being edited, when the agent works on a whole document. */
    val documentUri: String = ""
)

/**
 * The result of a run.
 *
 * @property note the produced note (note mode), or `null` when the run failed or ran in edit mode.
 * @property editPlan the produced edit plan (edit mode), or `null` when the run failed or ran in note mode.
 * @property steps everything that happened, in order.
 * @property rawAnswer the last raw text the model returned — kept so a failure
 *   can be shown to the user instead of a bare "it did not work".
 * @property failure why there is no output, in words the user can act on.
 */
data class AgentOutcome(
    val note: LessonNote? = null,
    val editPlan: EditPlan? = null,
    val steps: List<AgentStep>,
    val rawAnswer: String? = null,
    val failure: String? = null
)

/**
 * The agent loop: goal in, note or edit plan out.
 *
 * The shape of it is dictated by what a *local* model can be relied on to do.
 * A cloud model can be handed a tool-calling protocol and will usually follow
 * it; a 7B model on a laptop will produce malformed tool calls, ignore a
 * schema, or answer in prose when asked for JSON. Two consequences run through
 * this class:
 *
 *  1. **Every answer is schema-constrained and validated.** The engine is
 *     asked to constrain generation, and the answer is checked anyway
 *     ([askStructured]). A rejected answer is retried once with the reason
 *     attached, because "your JSON had no `kind` field" is a message a model
 *     can act on.
 *  2. **Gather and compose are separate requests.** The model first looks
 *     around with tools, then writes the output in one constrained answer. A
 *     single long agentic request is where small models fall apart; two
 *     short ones is where they do fine.
 *
 * When [composeSchema] / [composeDecode] are supplied, the compose phase
 * produces an [EditPlan] instead of a [LessonNote]. Otherwise it produces a
 * [LessonNote] as before (backward compatible).
 *
 * Everything here is pure Kotlin with no platform dependency, so the tablet,
 * the desktop and (later) iOS run the same loop.
 */
class AgentLoop(
    private val provider: AgentProvider,
    private val tools: List<AgentTool>,
    /** How many tool-calling steps the gather phase may take. */
    private val maxSteps: Int = 6,
    /** How many times a malformed answer is retried before giving up. */
    private val maxSchemaRetries: Int = 1,
    /** When non-null, the compose phase uses this schema instead of [LessonNote.schema]. */
    private val composeSchema: SchemaNode? = null,
    /** Decodes the compose answer into a value placed in [AgentOutcome.editPlan]. */
    private val composeDecode: ((JsonValue) -> Any?)? = null
) {

    /**
     * Runs the loop.
     *
     * @param onStep called for every step, as it happens — the UI renders the
     *   run as it goes rather than after it ends.
     */
    suspend fun run(goal: AgentGoal, onStep: (AgentStep) -> Unit = {}): AgentOutcome {
        val steps = mutableListOf<AgentStep>()
        val observations = mutableListOf<String>()
        val images = mutableListOf<LlmImage>()
        var rawAnswer: String? = null

        fun emit(step: AgentStep) {
            steps += step
            onStep(step)
        }

        // ── Phase 1: gather ────────────────────────────────────────────────
        var usedSteps = 0
        var gathered = false
        var providerFailure: String? = null
        while (usedSteps < maxSteps && !gathered) {
            usedSteps++
            val request = LlmRequest(
                systemPrompt = gatherSystemPrompt(),
                userPrompt = gatherUserPrompt(goal, observations),
                images = images.toList(),
                responseSchema = LlmSchema("agent_step", stepSchema.toJsonSchemaDocument())
            )
            val answer = askStructured(request, stepSchema)
            if (answer.raw != null) rawAnswer = answer.raw
            if (answer.error != null) {
                emit(AgentStep.Warning(answer.error))
                providerFailure = answer.error
                break
            }
            val json = answer.value ?: break

            json.asObject?.get("thought")?.asString
                ?.takeIf { it.isNotBlank() }
                ?.let { emit(AgentStep.Thought(it)) }

            val action = json.asObject?.get("action")?.asString
            if (action != "call_tool") {
                // "gathered", or something the schema let through.
                gathered = true
                continue
            }

            val toolName = json.asObject?.get("tool")?.asString.orEmpty()
            val input = json.asObject?.get("input") ?: JsonValue.Obj(emptyMap())
            val tool = tools.firstOrNull { it.name == toolName }
            if (tool == null) {
                val message = "unknown tool \"$toolName\""
                emit(AgentStep.ToolOutcome(toolName, ok = false, summary = message))
                observations += message
                continue
            }

            emit(AgentStep.ToolCall(toolName, input))

            val problems = tool.inputSchema.validate(input)
            if (problems.isNotEmpty()) {
                val message = "invalid input for $toolName: ${problems.joinToString("; ")}"
                emit(AgentStep.ToolOutcome(toolName, ok = false, summary = message))
                observations += message
                continue
            }

            val result = tool.run(input)
            emit(AgentStep.ToolOutcome(toolName, result.ok, result.summary))
            observations += "${toolName}: ${result.summary}"
            result.data?.let { observations += "${toolName} data: ${Json.write(it)}" }
            images += result.images
        }

        // A provider that failed in the gather phase will fail in the compose
        // phase too — the server is not running, or the model cannot do what
        // was asked. Asking again would only spend another request and bury
        // the real reason under a second, vaguer error.
        if (providerFailure != null) {
            return AgentOutcome(null, null, steps, rawAnswer, providerFailure)
        }

        // ── Phase 2: compose ──────────────────────────────────────────────
        val editMode = composeSchema != null && composeDecode != null
        val composeSchemaNode = if (editMode) composeSchema!! else LessonNote.schema
        val composeSystemPromptStr = if (editMode) composeEditSystemPrompt() else composeSystemPrompt()
        val composeUserPromptStr = if (editMode) composeEditUserPrompt(goal, observations) else composeUserPrompt(goal, observations)
        val decodeFn: (JsonValue) -> Any? = if (editMode) {
            { value: JsonValue -> composeDecode!!(value) }
        } else {
            { value: JsonValue -> LessonNote.decode(value) }
        }

        val composeRequest = LlmRequest(
            systemPrompt = composeSystemPromptStr,
            userPrompt = composeUserPromptStr,
            images = images.toList(),
            responseSchema = LlmSchema(if (editMode) "edit_plan" else "lesson_note", composeSchemaNode.toJsonSchemaDocument())
        )
        val answer = askStructured(composeRequest, composeSchemaNode)
        if (answer.raw != null) rawAnswer = answer.raw
        if (answer.error != null) {
            emit(AgentStep.Warning(answer.error))
            return AgentOutcome(null, null, steps, rawAnswer, answer.error)
        }

        val parsed = answer.value ?: return AgentOutcome(null, null, steps, rawAnswer, "the model returned no JSON value")
        val composed = decodeFn(parsed)
        if (composed == null) {
            val failure = if (editMode) "the model did not return a usable edit plan" else "the model did not return a usable note"
            emit(AgentStep.Warning(failure))
            return AgentOutcome(null, null, steps, rawAnswer, failure)
        }

        if (editMode) {
            return AgentOutcome(null, composed as EditPlan, steps, rawAnswer, null)
        } else {
            val note = composed as LessonNote
            emit(AgentStep.NoteProduced(note))
            return AgentOutcome(note, null, steps, rawAnswer, null)
        }
    }

    /**
     * Asks the model for an answer that fits [schema], retrying once with the
     * reason attached when it does not.
     */
    private suspend fun askStructured(
        request: LlmRequest,
        schema: SchemaNode
    ): StructuredAnswer {
        var attempt = 0
        var current = request
        var problem = "the answer was not valid JSON"

        while (true) {
            val response = provider.complete(current)
            val error = response.error
            if (error != null) {
                return StructuredAnswer(value = null, error = error.message, raw = null)
            }

            val text = response.text.trim()
            val parsed = Json.parse(text)
            if (parsed == null) {
                problem = "the answer was not valid JSON"
            } else {
                val problems = schema.validate(parsed)
                if (problems.isEmpty()) {
                    return StructuredAnswer(value = parsed, error = null, raw = text)
                }
                problem = problems.joinToString("; ")
            }

            attempt++
            if (attempt > maxSchemaRetries) {
                return StructuredAnswer(
                    value = null,
                    error = "the model did not answer in the expected shape ($problem)",
                    raw = text
                )
            }
            current = request.copy(
                userPrompt = request.userPrompt +
                    "\n\nYour previous answer was rejected: $problem.\n" +
                    "Answer again with one JSON object and nothing else."
            )
        }
    }

    private fun gatherSystemPrompt(): String {
        val toolList = if (tools.isEmpty()) {
            "- (no tools are available)"
        } else {
            tools.joinToString("\n") { tool ->
                val schema = tool.inputSchema.toJsonSchemaDocument()
                "- ${tool.name}: ${tool.description}\n  input schema: $schema"
            }
        }
        return buildString {
            appendLine("You are a study-note assistant working inside a note-taking app.")
            appendLine("This is the GATHER phase: look at the material with your tools and decide what to look at next.")
            appendLine()
            appendLine("Available tools:")
            appendLine(toolList)
            appendLine()
            appendLine("Answer with exactly one JSON object, no prose, no markdown:")
            appendLine("""{"thought": "<why>", "action": "call_tool" | "gathered", "tool": "<tool name>", "input": {<arguments>}}""")
            appendLine()
            appendLine("Rules:")
            appendLine("- Call at most one tool per answer.")
            appendLine("- Use \"action\": \"gathered\" once you have seen enough material to write the note.")
            appendLine("- If a tool returns an error, read the message and try something else.")
        }
    }

    private fun gatherUserPrompt(goal: AgentGoal, observations: List<String>): String = buildString {
        appendLine("Task: ${goal.instruction}")
        if (goal.pageHint.isNotBlank()) appendLine("Material: ${goal.pageHint}")
        if (observations.isEmpty()) {
            appendLine("You have not looked at anything yet.")
        } else {
            appendLine()
            appendLine("What you have seen so far:")
            observations.forEach { appendLine("- $it") }
        }
    }

    private fun composeSystemPrompt(): String = buildString {
        appendLine("You are a study-note assistant. This is the COMPOSE phase: write the note.")
        appendLine()
        appendLine("Answer with exactly one JSON object, no prose, no markdown:")
        appendLine("""{"title": "<short title>", "blocks": [ ...blocks... ]}""")
        appendLine()
        appendLine("Every block has the same shape:")
        appendLine("""{"kind": "heading" | "paragraph" | "bullet" | "example" | "flashcard" | "quiz", "text": "<main text>", "detail": "<optional second half>", "options": ["<option>"], "answerIndex": 0}""")
        appendLine()
        appendLine("Rules:")
        appendLine("- \"detail\" is the example's answer or the flashcard's back.")
        appendLine("- \"options\" and \"answerIndex\" are for a quiz block only; answerIndex is 0-based.")
        appendLine("- Write in the language of the material. Keep each block short enough to read on a page.")
        appendLine("- Use headings to organise, bullets to summarise, one example to demonstrate, flashcards to drill, and a quiz to test.")
    }

    private fun composeUserPrompt(goal: AgentGoal, observations: List<String>): String = buildString {
        appendLine("Task: ${goal.instruction}")
        if (goal.pageHint.isNotBlank()) appendLine("Material: ${goal.pageHint}")
        appendLine()
        if (observations.isEmpty()) {
            appendLine("You were not able to look at any material. Write the note from the task alone, and do not invent specifics.")
        } else {
            appendLine("Material you gathered:")
            observations.forEach { appendLine("- $it") }
        }
    }

    /** System prompt for edit mode: the agent proposes document-level edits. */
    private fun composeEditSystemPrompt(): String = buildString {
        appendLine("You are a document editor working inside a note-taking app.")
        appendLine("This is the COMPOSE phase: propose edits to the document based on what you have gathered.")
        appendLine()
        appendLine("Answer with exactly one JSON object, no prose, no markdown:")
        appendLine("""{"ops": [ ...operations... ]}""")
        appendLine()
        appendLine("Every operation has the same shape:")
        appendLine("""{"op": "upsert_text" | "delete_text" | "upsert_stroke" | "delete_stroke", ...fields...}""")
        appendLine()
        appendLine("For upsert_text: op, pageIndex (0-based), text, modelX, modelY, fontSize (default 16), " +
            "colorArgb (default black), boxWidth, alignment (0=left,1=centre,2=right), id (optional, to replace).")
        appendLine("For delete_text: op, id (required).")
        appendLine("For upsert_stroke / delete_stroke: not yet implemented — do not emit.")
        appendLine()
        appendLine("Rules:")
        appendLine("- Each op targets one object by id (delete) or creates/replaces one object (upsert).")
        appendLine("- baseVersion / baseNonce are optional optimistic-locking tokens; omit if unknown.")
        appendLine("- Write in the language of the material.")
        appendLine("- Only emit ops you are confident about; better fewer correct ops than many wrong ones.")
    }

    /** User prompt for edit mode: describes the task and gathered material. */
    private fun composeEditUserPrompt(goal: AgentGoal, observations: List<String>): String = buildString {
        appendLine("Task: ${goal.instruction}")
        if (goal.documentUri.isNotBlank()) appendLine("Document: ${goal.documentUri}")
        if (goal.pageHint.isNotBlank()) appendLine("Material: ${goal.pageHint}")
        appendLine()
        if (observations.isEmpty()) {
            appendLine("You have not looked at anything yet. Propose edits from the task alone, and do not invent specifics.")
        } else {
            appendLine("Material you gathered:")
            observations.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("Propose the edit operations as JSON.")
    }

    private data class StructuredAnswer(
        val value: JsonValue?,
        val error: String?,
        val raw: String?
    )

    private companion object {
        /**
         * The shape of a gather-phase answer.
         *
         * `input` is free-form here: the tool's own schema is checked against
         * the input before the tool runs, and the tool's schema is in the
         * prompt. Making the step schema list every tool's input shape would
         * need `oneOf`, which the local engines translate badly.
         */
        val stepSchema: SchemaNode = SchemaNode.Obj(
            listOf(
                SchemaProp(
                    name = "thought",
                    schema = SchemaNode.Str(),
                    description = "Why this is the next step."
                ),
                SchemaProp(
                    name = "action",
                    schema = SchemaNode.Str(listOf("call_tool", "gathered")),
                    description = "Call a tool, or stop gathering."
                ),
                SchemaProp(
                    name = "tool",
                    schema = SchemaNode.Str(),
                    description = "The tool to call, when action is call_tool.",
                    required = false
                ),
                SchemaProp(
                    name = "input",
                    schema = SchemaNode.Any,
                    description = "The tool's arguments, when action is call_tool.",
                    required = false
                )
            )
        )
    }
}
