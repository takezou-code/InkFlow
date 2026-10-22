package com.vic.inkflow.agent

import com.vic.inkflow.json.JsonValue
import com.vic.inkflow.schema.SchemaNode
import com.vic.inkflow.schema.SchemaProp
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentLoopTest {

    /** Answers a fixed script, one per request, and remembers what it was asked. */
    private class ScriptedProvider(private val answers: List<LlmResponse>) : AgentProvider {
        val requests = mutableListOf<LlmRequest>()
        override suspend fun complete(request: LlmRequest): LlmResponse {
            requests += request
            return answers.getOrElse(requests.size - 1) { LlmResponse(text = "") }
        }
    }

    /** A tool that records its calls and returns a canned result. */
    private class RecordingTool(
        override val name: String = "renderPage",
        override val description: String = "Renders one page of the document as an image.",
        private val result: ToolResult = ToolResult(ok = true, summary = "rendered page 1"),
        override val inputSchema: SchemaNode = SchemaNode.Obj(
            listOf(SchemaProp("page", SchemaNode.Num(integer = true)))
        )
    ) : AgentTool {
        val calls = mutableListOf<JsonValue>()
        override suspend fun run(input: JsonValue): ToolResult {
            calls += input
            return result
        }
    }

    private fun stepCall(tool: String, input: String): LlmResponse = LlmResponse(
        text = """{"thought":"look at the page","action":"call_tool","tool":"$tool","input":$input}"""
    )

    private fun stepGathered(): LlmResponse = LlmResponse(
        text = """{"thought":"enough","action":"gathered"}"""
    )

    private fun noteAnswer(): LlmResponse = LlmResponse(
        text = """
            {"title":"Derivatives","blocks":[
              {"kind":"heading","text":"Chapter 3"},
              {"kind":"bullet","text":"A derivative is a rate of change"},
              {"kind":"example","text":"d/dx x^2","detail":"2x"},
              {"kind":"quiz","text":"What is d/dx x^2?","options":["2x","x^2"],"answerIndex":0}
            ]}
        """.trimIndent()
    )

    private fun goal() = AgentGoal(instruction = "Turn this page into a study note")

    @Test
    fun gathersThenComposesANote() = runTest {
        val provider = ScriptedProvider(
            listOf(stepCall("renderPage", """{"page":1}"""), stepGathered(), noteAnswer())
        )
        val tool = RecordingTool()
        val loop = AgentLoop(provider, listOf(tool))

        val outcome = loop.run(goal())

        assertNotNull(outcome.note)
        assertEquals("Derivatives", outcome.note.title)
        assertEquals(4, outcome.note.blocks.size)
        assertEquals(1, tool.calls.size)
        assertNull(outcome.failure)

        // Every request carried a schema: that is the whole reason the loop
        // can check what the model said.
        assertTrue(provider.requests.all { it.responseSchema != null })
        // The tool's output reached the composing request.
        assertTrue(provider.requests.last().userPrompt.contains("rendered page 1"))
    }

    @Test
    fun stepsAreReportedAsTheyHappen() = runTest {
        val provider = ScriptedProvider(
            listOf(stepCall("renderPage", """{"page":1}"""), stepGathered(), noteAnswer())
        )
        val seen = mutableListOf<AgentStep>()
        AgentLoop(provider, listOf(RecordingTool())).run(goal()) { seen += it }

        assertTrue(seen.any { it is AgentStep.Thought })
        assertTrue(seen.any { it is AgentStep.ToolCall })
        assertTrue(seen.any { it is AgentStep.ToolOutcome })
        assertTrue(seen.any { it is AgentStep.NoteProduced })
    }

    @Test
    fun aMalformedAnswerIsRetriedWithTheReason() = runTest {
        val provider = ScriptedProvider(
            listOf(LlmResponse(text = "Sure! Here is the note:"), stepGathered(), noteAnswer())
        )
        val outcome = AgentLoop(provider, emptyList()).run(goal())

        assertNotNull(outcome.note)
        assertEquals(3, provider.requests.size)
        assertTrue(
            provider.requests[1].userPrompt.contains("rejected"),
            "the retry should say what was wrong: ${provider.requests[1].userPrompt}"
        )
    }

    @Test
    fun anAnswerThatBreaksTheSchemaIsRetried() = runTest {
        // "action" is an enum; "dance" is not in it, and the engine's
        // constraint did not stop the model from trying.
        val bad = LlmResponse(text = """{"thought":"hmm","action":"dance"}""")
        val provider = ScriptedProvider(listOf(bad, stepGathered(), noteAnswer()))
        val outcome = AgentLoop(provider, emptyList()).run(goal())

        assertNotNull(outcome.note)
        assertTrue(
            provider.requests[1].userPrompt.contains("gathered"),
            "the reason should name the problem"
        )
    }

    @Test
    fun aProviderErrorEndsTheRunWithAMessage() = runTest {
        val provider = ScriptedProvider(
            listOf(
                LlmResponse(
                    text = "",
                    error = LlmError(LlmErrorKind.NotRunning, "the model server is not running")
                )
            )
        )
        val outcome = AgentLoop(provider, emptyList()).run(goal())

        assertNull(outcome.note)
        assertNotNull(outcome.failure)
        assertTrue(outcome.failure.contains("not running"), outcome.failure)
        assertTrue(outcome.steps.any { it is AgentStep.Warning })
    }

    @Test
    fun anUnknownToolIsReportedAndTheRunContinues() = runTest {
        val provider = ScriptedProvider(listOf(stepCall("teleport", "{}"), stepGathered(), noteAnswer()))
        val outcome = AgentLoop(provider, listOf(RecordingTool())).run(goal())

        assertNotNull(outcome.note)
        val failure = outcome.steps.filterIsInstance<AgentStep.ToolOutcome>().first()
        assertFalse(failure.ok)
        assertTrue(failure.summary.contains("teleport"), failure.summary)
    }

    @Test
    fun invalidToolInputDoesNotRunTheTool() = runTest {
        // The tool wants a whole number for "page"; the model sent a string.
        val provider = ScriptedProvider(
            listOf(stepCall("renderPage", """{"page":"one"}"""), stepGathered(), noteAnswer())
        )
        val tool = RecordingTool()
        val outcome = AgentLoop(provider, listOf(tool)).run(goal())

        assertEquals(0, tool.calls.size)
        assertNotNull(outcome.note)
        val failure = outcome.steps.filterIsInstance<AgentStep.ToolOutcome>().first()
        assertFalse(failure.ok)
        assertTrue(failure.summary.contains("invalid input"), failure.summary)
    }

    @Test
    fun aNoteThatCannotBeUsedEndsTheRun() = runTest {
        // The block kind is not one the app knows: the note is not usable.
        val badNote = LlmResponse(
            text = """{"title":"t","blocks":[{"kind":"song","text":"x"}]}"""
        )
        val provider = ScriptedProvider(listOf(stepGathered(), badNote))
        val outcome = AgentLoop(provider, emptyList()).run(goal())

        assertNull(outcome.note)
        assertNotNull(outcome.failure)
        assertNotNull(outcome.rawAnswer)
    }

    @Test
    fun theGatherPhaseIsBounded() = runTest {
        // The model never says "gathered"; the loop must still stop and try
        // to compose, rather than looping forever.
        val provider = ScriptedProvider(
            listOf(
                stepCall("renderPage", """{"page":1}"""),
                stepCall("renderPage", """{"page":2}"""),
                stepCall("renderPage", """{"page":3}"""),
                noteAnswer()
            )
        )
        val outcome = AgentLoop(provider, listOf(RecordingTool()), maxSteps = 3).run(goal())

        // 3 gather steps + 1 compose request.
        assertEquals(4, provider.requests.size)
        assertNotNull(outcome.note)
    }
}
