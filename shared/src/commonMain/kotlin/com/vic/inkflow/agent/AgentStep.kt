package com.vic.inkflow.agent

import com.vic.inkflow.data.LessonNote
import com.vic.inkflow.json.JsonValue

/**
 * One thing that happened during a run.
 *
 * The step list is what the user watches: a coding agent shows its steps
 * because the artefact it produces is only trustworthy if the path to it is
 * visible. This is the same idea, and the same reason the Windows panel will
 * render these rather than a spinner.
 */
sealed interface AgentStep {

    /** The model's stated reason for the next action. */
    data class Thought(val text: String) : AgentStep

    /** The model asked for a tool to be run. */
    data class ToolCall(val tool: String, val input: JsonValue) : AgentStep

    /** What the tool did. */
    data class ToolOutcome(val tool: String, val ok: Boolean, val summary: String) : AgentStep

    /** The note was produced. */
    data class NoteProduced(val note: LessonNote) : AgentStep

    /** Something went wrong, but the run continued or ended with a reason. */
    data class Warning(val message: String) : AgentStep
}
