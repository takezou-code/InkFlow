package com.vic.inkflow.ui

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.vic.inkflow.DesktopSettings
import com.vic.inkflow.agent.AgentGoal
import com.vic.inkflow.agent.AgentLoop
import com.vic.inkflow.agent.AgentStep
import com.vic.inkflow.agent.DeleteTextTool
import com.vic.inkflow.agent.EditOp
import com.vic.inkflow.agent.EditPlan
import com.vic.inkflow.agent.LocalOpenAiProvider
import com.vic.inkflow.agent.ModelHealth
import com.vic.inkflow.agent.NoteLanding
import com.vic.inkflow.agent.NoteLandingHandle
import com.vic.inkflow.agent.ReadDocumentTextTool
import com.vic.inkflow.agent.ReadPageTextTool
import com.vic.inkflow.agent.RenderPageTool
import com.vic.inkflow.agent.UpsertTextTool
import com.vic.inkflow.data.DatabaseManager
import com.vic.inkflow.data.LessonNote
import com.vic.inkflow.sync.ProposalQueue
import com.vic.inkflow.util.DesktopPdfEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AgentPanelViewModel(
    private val documentUri: String,
    private val pageIndex: Int,
    private val landing: NoteLandingHandle,
    private val scope: CoroutineScope,
    private val db: DatabaseManager,
    private val proposalQueue: ProposalQueue
) {

    data class UiState(
        val instruction: String = DEFAULT_INSTRUCTION,
        val steps: List<AgentStep> = emptyList(),
        val note: LessonNote? = null,
        val editPlan: EditPlan? = null,
        val failure: String? = null,
        val landed: String? = null,
        val busy: Boolean = false,
        val models: List<String> = emptyList(),
        val connection: ModelHealth? = null
    )

    sealed interface AgentEvent {
        data class SetInstruction(val value: String) : AgentEvent
        data object ToggleSendImages : AgentEvent
        data object CheckModels : AgentEvent
        data object Generate : AgentEvent
        data object LandNote : AgentEvent
        data object ApplyEditPlan : AgentEvent
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var tools = rebuildTools(documentUri)
    private var provider: LocalOpenAiProvider? = null

    /** Reuses a single provider instance so the HTTP client is not recreated per request. */
    private fun currentProvider(): LocalOpenAiProvider {
        return provider ?: LocalOpenAiProvider(DesktopSettings.localModelConfig()).also { provider = it }
    }

    fun onEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.SetInstruction -> _state.value = _state.value.copy(instruction = event.value)
            is AgentEvent.ToggleSendImages -> {
                DesktopSettings.modelSendImages = !DesktopSettings.modelSendImages
                tools = rebuildTools(documentUri)
            }
            is AgentEvent.CheckModels -> checkModels()
            is AgentEvent.Generate -> generate()
            is AgentEvent.LandNote -> {
                val msg = landNote()
                _state.value = _state.value.copy(landed = msg)
            }
            is AgentEvent.ApplyEditPlan -> {
                val msg = applyEditPlan()
                _state.value = _state.value.copy(landed = msg)
            }
        }
    }

    private fun checkModels() {
        scope.launch {
            val prov = currentProvider()
            val health = prov.healthCheck()
            _state.value = _state.value.copy(connection = health)
            if (health is ModelHealth.Reachable) {
                val found = prov.listModels()
                val ids = found.map { it.id }
                _state.value = _state.value.copy(models = ids)
                if (DesktopSettings.modelName.isBlank() && found.isNotEmpty()) {
                    DesktopSettings.modelName = found.first().id
                }
            } else {
                _state.value = _state.value.copy(models = emptyList())
            }
        }
    }

    private fun generate() {
        val s = _state.value
        if (s.busy) return
        _state.value = s.copy(busy = true, failure = null, landed = null, note = null, editPlan = null, steps = emptyList())
        scope.launch {
            val prov = currentProvider()
            // Note mode: the agent composes a LessonNote (study note) from the
            // material it gathered. EditPlan mode (document-level edits) is
            // available on AgentLoop but this panel's job is to make notes.
            val loop = AgentLoop(
                provider = prov,
                tools = tools
            )
            val instruction = _state.value.instruction.ifBlank { DEFAULT_INSTRUCTION }
            val outcome = loop.run(
                AgentGoal(
                    instruction = instruction,
                    pageHint = "第 ${pageIndex + 1} 頁",
                    documentUri = documentUri
                )
            ) { step -> _state.value = _state.value.copy(steps = _state.value.steps + step) }
            _state.value = _state.value.copy(
                note = outcome.note,
                editPlan = outcome.editPlan,
                failure = outcome.failure,
                busy = false
            )
        }
    }

    private fun applyEditPlan(): String {
        val plan = _state.value.editPlan ?: return "沒有可套用的編輯計畫"
        if (plan.ops.isEmpty()) return "編輯計畫為空"

        val gson = Gson()
        var applied = 0

        for (op in plan.ops) {
            // 1. Apply to local DB immediately so the user sees the change.
            when (op) {
                is EditOp.UpsertText -> {
                    val entity = upsertEntity(op)
                    db.saveTextAnnotation(entity)
                }
                is EditOp.DeleteText -> {
                    db.deleteTextAnnotation(op.id)
                }
                is EditOp.UpsertStroke, is EditOp.DeleteStroke -> {
                    // Signposted for future; no DB support yet.
                }
            }

            // 2. Convert EditOp to ProposalOp for the sync queue.
            val proposalOp = when (op) {
                is EditOp.UpsertText -> {
                    val entity = upsertEntity(op)
                    val textJson = JsonObject()
                    textJson.add("id", JsonPrimitive(entity.id))
                    textJson.add("documentUri", JsonPrimitive(entity.documentUri))
                    textJson.add("pageIndex", JsonPrimitive(entity.pageIndex))
                    textJson.add("docY", com.google.gson.JsonNull())
                    textJson.add("text", JsonPrimitive(entity.text))
                    textJson.add("modelX", JsonPrimitive(entity.modelX))
                    textJson.add("modelY", JsonPrimitive(entity.modelY))
                    textJson.add("fontSize", JsonPrimitive(entity.fontSize))
                    textJson.add("colorArgb", JsonPrimitive(entity.colorArgb))
                    textJson.add("isStamp", JsonPrimitive(entity.isStamp))
                    if (entity.boxWidth != null) {
                        textJson.add("boxWidth", JsonPrimitive(entity.boxWidth))
                    } else {
                        textJson.add("boxWidth", com.google.gson.JsonNull())
                    }
                    textJson.add("alignment", JsonPrimitive(entity.alignment))
                    textJson.add("version", JsonPrimitive(entity.version))
                    textJson.add("versionNonce", JsonPrimitive(entity.versionNonce))
                    textJson.add("deletedAt", com.google.gson.JsonNull())

                    com.vic.inkflow.sync.ProposalOp(
                        op = com.vic.inkflow.sync.ProposalOp.UPSERT_TEXT,
                        text = textJson,
                        id = entity.id,
                        baseVersion = op.baseVersion,
                        baseNonce = op.baseNonce
                    )
                }
                is EditOp.DeleteText -> {
                    com.vic.inkflow.sync.ProposalOp(
                        op = com.vic.inkflow.sync.ProposalOp.DELETE_TEXT,
                        id = op.id,
                        baseVersion = op.baseVersion,
                        baseNonce = op.baseNonce
                    )
                }
                is EditOp.UpsertStroke, is EditOp.DeleteStroke -> null // not yet implemented
            }

            if (proposalOp != null) {
                val result = proposalQueue.record(documentUri, proposalOp)
                when (result) {
                    ProposalQueue.RecordResult.RECORDED -> applied++
                    ProposalQueue.RecordResult.SKIPPED_NO_BASE -> applied++
                    ProposalQueue.RecordResult.STALE_DROPPED -> applied++
                }
            }
        }

        // Clear the plan after applying so the UI reflects the new state.
        _state.value = _state.value.copy(editPlan = null)

        return "已套用 ${applied} 筆編輯（會在下次同步推送到平板）"
    }

    private fun landNote(): String {
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

    private fun rebuildTools(documentUri: String): List<com.vic.inkflow.agent.AgentTool> {
        val base = mutableListOf<com.vic.inkflow.agent.AgentTool>(
            ReadPageTextTool(documentUri),
            ReadDocumentTextTool(documentUri),
            UpsertTextTool(documentUri, db),
            DeleteTextTool(documentUri, db)
        )
        if (DesktopSettings.modelSendImages) {
            base += RenderPageTool(documentUri, DesktopPdfEngine())
        }
        return base
    }

    /** Builds one TextAnnotationEntity from an upsert op, minting an id when needed. */
    private fun upsertEntity(op: EditOp.UpsertText): com.vic.inkflow.data.TextAnnotationEntity {
        val id = op.id ?: java.util.UUID.randomUUID().toString()
        return com.vic.inkflow.data.TextAnnotationEntity(
            id = id,
            documentUri = documentUri,
            pageIndex = op.pageIndex,
            text = op.text,
            modelX = op.modelX,
            modelY = op.modelY,
            fontSize = op.fontSize,
            colorArgb = op.colorArgb,
            boxWidth = op.boxWidth,
            alignment = op.alignment
        )
    }

    companion object {
        private const val DEFAULT_INSTRUCTION = "把這一份文件整理成教材：重點、範例、字卡與自測題"
    }
}
