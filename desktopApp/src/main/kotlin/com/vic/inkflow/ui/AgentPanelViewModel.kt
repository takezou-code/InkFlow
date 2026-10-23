package com.vic.inkflow.ui

import com.vic.inkflow.DesktopSettings
import com.vic.inkflow.agent.AgentGoal
import com.vic.inkflow.agent.AgentLoop
import com.vic.inkflow.agent.AgentStep
import com.vic.inkflow.agent.LocalOpenAiProvider
import com.vic.inkflow.agent.NoteLanding
import com.vic.inkflow.agent.NoteLandingHandle
import com.vic.inkflow.data.LessonNote
import com.vic.inkflow.agent.ReadPageTextTool
import com.vic.inkflow.agent.RenderPageTool
import com.vic.inkflow.util.DesktopPdfEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Screen state holder for the AI panel.
 *
 * ## Why this exists
 *
 * `AgentPanel` used to own every piece of agent state (instruction, steps,
 * note, failure, landed, busy, models, tools) inside the composable. That is
 * the same "hoist everything to the root" pattern the library and settings
 * screens had; the agent state is the most complex of the three because the
 * generate flow runs a coroutine that mutates six fields, so lifting it into
 * a `StateFlow`-backed holder makes the state transitions explicit and testable.
 */
class AgentPanelViewModel(
    private val documentUri: String,
    private val pageIndex: Int,
    private val landing: NoteLandingHandle,
    private val scope: CoroutineScope
) {

    data class UiState(
        val instruction: String = DEFAULT_INSTRUCTION,
        val steps: List<AgentStep> = emptyList(),
        val note: LessonNote? = null,
        val failure: String? = null,
        val landed: String? = null,
        val busy: Boolean = false,
        val models: List<String> = emptyList()
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var tools = rebuildTools(documentUri)

    fun setInstruction(value: String) {
        _state.value = _state.value.copy(instruction = value)
    }

    fun toggleSendImages() {
        DesktopSettings.modelSendImages = !DesktopSettings.modelSendImages
        tools = rebuildTools(documentUri)
    }

    fun checkModels() {
        scope.launch {
            val found = LocalOpenAiProvider(DesktopSettings.localModelConfig()).listModels()
            val ids = found.map { it.id }
            _state.value = _state.value.copy(models = ids)
            if (DesktopSettings.modelName.isBlank() && found.isNotEmpty()) {
                DesktopSettings.modelName = found.first().id
            }
        }
    }

    fun generate() {
        val s = _state.value
        if (s.busy) return
        _state.value = s.copy(busy = true, failure = null, landed = null, note = null, steps = emptyList())
        scope.launch {
            val provider = LocalOpenAiProvider(DesktopSettings.localModelConfig())
            val loop = AgentLoop(provider, tools)
            val instruction = _state.value.instruction.ifBlank { DEFAULT_INSTRUCTION }
            val outcome = loop.run(
                AgentGoal(
                    instruction = instruction,
                    pageHint = "第 ${pageIndex + 1} 頁"
                )
            ) { step -> _state.value = _state.value.copy(steps = _state.value.steps + step) }
            _state.value = _state.value.copy(
                note = outcome.note,
                failure = outcome.failure,
                busy = false
            )
        }
    }

    fun landNote(): String {
        val produced = _state.value.note ?: return "筆記沒有內容可寫"
        return when (val result = landing.land(produced)) {
            is NoteLanding.Placed ->
                if (result.annotations > 0) "已寫進第 ${pageIndex + 1} 頁（${result.annotations} 段）"
                else "筆記沒有內容可寫"
            is NoteLanding.Overflow ->
                "已寫進第 ${pageIndex + 1} 頁（${result.annotations} 段），" +
                    "另有 ${result.dropped} 段這一頁放不下"
            NoteLanding.NoRoom -> "這一頁沒有空間（下方已被既有內容寫滿）"
            NoteLanding.NoPage -> "找不到可寫入的頁面"
            NoteLanding.SaveFailed -> "寫入失敗"
        }
    }

    fun setLanded(message: String) {
        _state.value = _state.value.copy(landed = message)
    }

    private fun rebuildTools(documentUri: String): List<com.vic.inkflow.agent.AgentTool> {
        val base = mutableListOf<com.vic.inkflow.agent.AgentTool>(ReadPageTextTool(documentUri))
        if (DesktopSettings.modelSendImages) {
            base += RenderPageTool(documentUri, DesktopPdfEngine())
        }
        return base
    }

    companion object {
    }
}

internal const val DEFAULT_INSTRUCTION = "把這一頁整理成教材筆記：重點、範例、字卡與自測題"
