package com.vic.inkflow.agent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ComposeClassifierTest {

    private class ScriptedProvider(private val answer: LlmResponse) : AgentProvider {
        val requests = mutableListOf<LlmRequest>()
        override suspend fun complete(request: LlmRequest): LlmResponse {
            requests += request
            return answer
        }
    }

    @Test
    fun `a note request routes to note`() = runTest {
        val provider = ScriptedProvider(LlmResponse(text = """{"kind":"note"}"""))
        assertEquals(ComposeKind.NOTE, classifyComposeKind(provider, "把這頁的重點整理成三條筆記"))
    }

    @Test
    fun `an edit request routes to edit`() = runTest {
        val provider = ScriptedProvider(LlmResponse(text = """{"kind":"edit"}"""))
        assertEquals(ComposeKind.EDIT, classifyComposeKind(provider, "把這頁重複的段落刪掉"))
    }

    @Test
    fun `an unparseable answer falls back to note, not to failure`() = runTest {
        val provider = ScriptedProvider(LlmResponse(text = "I cannot decide"))
        assertEquals(ComposeKind.NOTE, classifyComposeKind(provider, "隨便"))
    }

    @Test
    fun `an empty answer falls back to note`() = runTest {
        val provider = ScriptedProvider(LlmResponse(text = ""))
        assertEquals(ComposeKind.NOTE, classifyComposeKind(provider, "隨便"))
    }

    @Test
    fun `a provider failure falls back to note`() = runTest {
        val provider = ScriptedProvider(LlmResponse(text = "", error = LlmError(LlmErrorKind.NotRunning, "server down")))
        assertEquals(ComposeKind.NOTE, classifyComposeKind(provider, "隨便"))
    }
}
