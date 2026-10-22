package com.vic.inkflow.agent

import com.vic.inkflow.json.JsonValue
import com.vic.inkflow.schema.SchemaNode

/**
 * A capability the agent can use while working.
 *
 * The tools are the agent's hands: they are the only things that touch the
 * document, and the only things that can fail in a way the user needs to see.
 *
 * A tool owns its own input shape. The core checks the input against
 * [inputSchema] before running it, so a model that invents a field gets a
 * precise error instead of a crash inside the tool.
 */
interface AgentTool {

    /** Stable identifier, used by the model to call this tool. */
    val name: String

    /** One line explaining what the tool does. This is documentation for the model. */
    val description: String

    /** The shape of the input this tool accepts. */
    val inputSchema: SchemaNode

    /** Runs the tool. Must not throw for an ordinary failure; return a failed [ToolResult]. */
    suspend fun run(input: JsonValue): ToolResult
}

/**
 * What a tool did.
 *
 * @property ok whether it worked.
 * @property summary one line, shown to the user in the step list and fed
 *   back to the model as the observation.
 * @property data structured output, when there is any.
 * @property images images the tool produced (a rendered page, a crop). They
 *   are handed to the model on the following request, which is how a local
 *   vision model gets to see the material.
 */
data class ToolResult(
    val ok: Boolean,
    val summary: String,
    val data: JsonValue? = null,
    val images: List<LlmImage> = emptyList()
)
