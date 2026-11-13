package com.vic.inkflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vic.inkflow.DesktopSettings
import com.vic.inkflow.agent.EditPlan
import com.vic.inkflow.agent.ModelHealth
import com.vic.inkflow.agent.NoteLanding
import com.vic.inkflow.agent.NoteLandingHandle
import com.vic.inkflow.data.DatabaseManager
import com.vic.inkflow.data.LessonNote
import com.vic.inkflow.sync.ProposalQueue
import com.vic.inkflow.ui.kit.DeskPrimaryButton
import com.vic.inkflow.ui.kit.DeskSidePanel
import com.vic.inkflow.ui.kit.DeskSpace
import com.vic.inkflow.ui.kit.DeskTextField
import com.vic.inkflow.ui.kit.DeskPillButton

/**
 * The AI panel: an agent that edits the whole document.
 *
 * ## The shape of it
 *
 * Everything a modern assistant panel does, in this order:
 *
 *  - **status strip** on top: is the model reachable, how fast, does it get
 *    the page image. One line, so it never competes with the content.
 *  - **the run** in the middle: the steps of what the agent did, as a log that
 *    reads like a transcript rather than a stack trace.
 *  - **the composer at the bottom**, pinned: the input is where the hand is,
 *    and the transcript grows upwards. (It used to be at the top, with the
 *    result underneath - which reads like a form, not a conversation.)
 *  - **the landing action** at the very bottom, because it is the thing the
 *    user came for: put the agent's work on the page.
 */
@Composable
fun AgentPanel(
    documentUri: String,
    pageIndex: Int,
    landing: NoteLandingHandle,
    onToggleCollapsed: () -> Unit,
    databaseManager: DatabaseManager,
    proposalQueue: ProposalQueue,
    modifier: Modifier = Modifier,
    previewHandle: com.vic.inkflow.agent.PreviewHandle? = null
) {
    val scope = rememberCoroutineScope()
    val vm = remember(documentUri, pageIndex, databaseManager, proposalQueue, previewHandle) {
        AgentPanelViewModel(documentUri, pageIndex, landing, scope, databaseManager, proposalQueue, previewHandle)
    }
    val state by vm.state.collectAsState()

    val modelSet = DesktopSettings.modelName.isNotBlank()

    DeskSidePanel(
        title = "AI 助理",
        titleIcon = Icons.Default.AutoAwesome,
        subtitle = if (modelSet) DesktopSettings.modelName else "尚未設定模型",
        onClose = onToggleCollapsed,
        modifier = modifier,
        headerActions = {
            DeskPillButton(
                text = if (state.models.isEmpty()) "檢查" else "${state.models.size} 個",
                icon = Icons.Default.Search,
                onClick = { vm.onEvent(AgentPanelViewModel.AgentEvent.CheckModels) }
            )
        },
        footer = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // ── The composer: pinned to the bottom, like every assistant ──
                Row(verticalAlignment = Alignment.Bottom) {
                    DeskTextField(
                        value = state.instruction,
                        onValueChange = { vm.onEvent(AgentPanelViewModel.AgentEvent.SetInstruction(it)) },
                        placeholder = if (state.editMode) "要改什麼？例如「把這頁的重複段落刪掉」"
                                      else "要 AI 做什麼？例如「把這頁的重點整理成筆記」",
                        singleLine = false,
                        minLines = 2,
                        maxLines = 6,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(DeskSpace.xs))
                    // A round accent button instead of a full-width bar: the eye
                    // goes to the thing you press, and the composer stays the
                    // biggest thing in the footer.
                    val canSend = !state.busy && state.instruction.isNotBlank()
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                if (canSend) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .glassClickable(
                                onClick = { vm.onEvent(AgentPanelViewModel.AgentEvent.Generate) },
                                shape = CircleShape,
                                enabled = canSend
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (state.busy) {
                            androidx.compose.material3.CircularProgressIndicator(
                                Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = "產生草稿",
                                tint = if (canSend) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(DeskSpace.xs))

                val hasNote = state.note != null
                val hasPlan = state.editPlan != null && state.editPlan!!.ops.isNotEmpty()
                when {
                    hasPlan -> Row(verticalAlignment = Alignment.CenterVertically) {
                        DeskPrimaryButton(
                            text = "套用變更",
                            icon = Icons.Default.Check,
                            onClick = { vm.onEvent(AgentPanelViewModel.AgentEvent.ApplyEditPlan) },
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(DeskSpace.xs))
                        DeskPillButton(
                            text = "捨棄",
                            icon = Icons.Default.Close,
                            onClick = { vm.onEvent(AgentPanelViewModel.AgentEvent.RejectEditPlan) },
                            enabled = !state.busy
                        )
                    }
                    hasNote -> DeskPrimaryButton(
                        text = "寫入頁面",
                        icon = Icons.Default.Check,
                        onClick = { vm.onEvent(AgentPanelViewModel.AgentEvent.LandNote) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                    else -> Text(
                        text = "產生的筆記會先出現在這裡，確認後才寫進頁面。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(DeskSpace.md)) {
            // ── Status strip: one line, never competing with the content ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DeskSpace.xs),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(horizontal = DeskSpace.sm, vertical = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (modelSet) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                )
                Text(
                    text = if (modelSet) DesktopSettings.modelBaseUrl else "尚未設定模型網址",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                state.connection?.let { health ->
                    val (label, tint) = when (health) {
                        is ModelHealth.Reachable -> "${health.latencyMs} ms" to MaterialTheme.colorScheme.primary
                        is ModelHealth.Unreachable -> health.reason to MaterialTheme.colorScheme.error
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = if (DesktopSettings.modelSendImages) "含圖" else "純文字",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .glassClickable(
                            onClick = { vm.onEvent(AgentPanelViewModel.AgentEvent.ToggleSendImages) },
                            shape = RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            Spacer(Modifier.height(DeskSpace.sm))

            // ── Mode: produce a note, or edit the document ──────────────
            Row(horizontalArrangement = Arrangement.spacedBy(DeskSpace.xs)) {
                ModeChip(
                    label = "整理成筆記",
                    selected = !state.editMode,
                    onClick = { if (state.editMode) vm.onEvent(AgentPanelViewModel.AgentEvent.ToggleEditMode) }
                )
                ModeChip(
                    label = "編輯文件",
                    selected = state.editMode,
                    onClick = { if (!state.editMode) vm.onEvent(AgentPanelViewModel.AgentEvent.ToggleEditMode) }
                )
            }

            Spacer(Modifier.height(DeskSpace.sm))

            // ── The run: the transcript grows upwards from the composer ──
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(DeskSpace.xs)
            ) {
                if (state.steps.isEmpty() && state.failure == null && state.note == null && state.editPlan == null && !state.busy) {
                    item {
                        AgentEmptyState(
                            editMode = state.editMode,
                            onPick = { vm.onEvent(AgentPanelViewModel.AgentEvent.SetInstruction(it)) }
                        )
                    }
                }
                items(state.steps) { step ->
                    StepRow(step)
                }
                state.failure?.let { message ->
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.10f))
                                .padding(DeskSpace.sm)
                        ) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.width(16.dp)
                            )
                            Spacer(Modifier.width(DeskSpace.xs))
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                state.note?.let { preview ->
                    item { NotePreview(preview) }
                }
                state.editPlan?.let { plan ->
                    item { EditPlanPreview(plan, state.previewAck) }
                }
                state.landed?.let { message ->
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                                .padding(DeskSpace.sm)
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(16.dp)
                            )
                            Spacer(Modifier.width(DeskSpace.xs))
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * What the panel shows before anything has happened.
 *
 * Three example prompts, and clicking one fills the composer. An empty panel
 * that only says "type something" makes the user guess what the agent can do;
 * a prompt they can press is the fastest way to find out.
 */
@Composable
private fun AgentEmptyState(editMode: Boolean, onPick: (String) -> Unit) {
    val examples = if (editMode) {
        listOf(
            "把這頁重複的段落合併成一段",
            "把這頁的長段落重排成條列重點",
            "找出這頁的筆記錯字並改過來"
        )
    } else {
        listOf(
            "把這頁的重點整理成三條筆記",
            "把這個公式的每一步推導寫成說明",
            "替這頁出一題練習題與解答"
        )
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = DeskSpace.lg)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(AccentGradient),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.height(DeskSpace.sm))
        Text(
            text = if (editMode) "描述你想怎麼改這頁" else "描述你要 AI 做什麼",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = if (editMode) "AI 會讀頁面，先把修改提案顯示在頁面上，你按套用才會寫入。"
                   else "AI 會讀頁面、查模型，最後把成果先給你確認。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(DeskSpace.md))
        examples.forEach { prompt ->
            Text(
                text = prompt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                        RoundedCornerShape(10.dp)
                    )
                    .glassClickable(onClick = { onPick(prompt) }, shape = RoundedCornerShape(10.dp))
                    .padding(horizontal = DeskSpace.sm, vertical = 8.dp)
            )
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** One step of the run: an icon chip and the line it produced. */
@Composable
private fun StepRow(step: com.vic.inkflow.agent.AgentStep) {
    val (icon, text, tint, emphasised) = when (step) {
        is com.vic.inkflow.agent.AgentStep.Thought ->
            Quad(Icons.Default.AutoAwesome, step.text, MaterialTheme.colorScheme.onSurfaceVariant, false)
        is com.vic.inkflow.agent.AgentStep.ToolCall ->
            Quad(Icons.Default.Search, "呼叫 ${step.tool}", MaterialTheme.colorScheme.primary, true)
        is com.vic.inkflow.agent.AgentStep.ToolOutcome ->
            Quad(
                if (step.ok) Icons.Default.Check else Icons.Default.WarningAmber,
                "${step.tool}：${step.summary}",
                if (step.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                !step.ok
            )
        is com.vic.inkflow.agent.AgentStep.NoteProduced ->
            Quad(Icons.Default.MenuBook, "筆記已產生", MaterialTheme.colorScheme.primary, true)
        is com.vic.inkflow.agent.AgentStep.Warning ->
            Quad(Icons.Default.WarningAmber, step.message, MaterialTheme.colorScheme.error, true)
    }
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(
                    if (emphasised) tint.copy(alpha = 0.16f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(12.dp)
            )
        }
        Spacer(Modifier.width(DeskSpace.xs))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (emphasised) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}

/** Four values in one tuple: Kotlin has no data class in a `when` branch. */
private data class Quad(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val text: String,
    val tint: androidx.compose.ui.graphics.Color,
    val emphasised: Boolean
)

/** The produced note, shown before it lands (legacy single-page mode). */
@Composable
private fun NotePreview(note: LessonNote) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(DeskSpace.sm)
    ) {
        Text(
            note.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        note.blocks.forEach { block ->
            Text(
                text = previewTextOf(block),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** The edit plan preview: shows each op as a diff line. */
@Composable
private fun EditPlanPreview(plan: EditPlan, ack: com.vic.inkflow.agent.PreviewAck?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(DeskSpace.sm)
    ) {
        Text(
            text = "變更預覽（${plan.ops.size} 項）",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        if (ack != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (ack.isNoop) "頁面預覽：沒有變更（模型的提案等於現狀）"
                       else "頁面預覽：＋${ack.added} 新增 · ${ack.changed} 改寫 · −${ack.deleted} 刪除",
                style = MaterialTheme.typography.bodySmall,
                color = if (ack.isNoop) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary
            )
            if (ack.problems.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                ack.problems.forEach { problem ->
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .width(14.dp)
                                .padding(top = 1.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = problem,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        plan.ops.forEachIndexed { index, op ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = opSummary(op),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "第 ${index + 1} 項",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun opSummary(op: com.vic.inkflow.agent.EditOp): String {
    return when (op) {
        is com.vic.inkflow.agent.EditOp.UpsertText -> {
            "upsert_text 第 ${op.pageIndex + 1} 頁：${op.text.take(60)}${if (op.text.length > 60) "…" else ""}"
        }
        is com.vic.inkflow.agent.EditOp.DeleteText -> "delete_text：${op.id}"
        // 筆跡 op 在桌面端不會出現：桌面的 agent 工具只有讀頁面
        // （readPageText / renderPage）與寫文字（upsertText），沒有寫筆跡的工具。
        is com.vic.inkflow.agent.EditOp.UpsertStroke ->
            "upsert_stroke 第 ${op.pageIndex + 1} 頁（桌面版未提供寫筆跡的工具）"
        is com.vic.inkflow.agent.EditOp.DeleteStroke ->
            "delete_stroke：${op.id}（桌面版未提供寫筆跡的工具）"
    }
}

private fun previewTextOf(block: com.vic.inkflow.data.NoteBlock): String = when (block) {
    is com.vic.inkflow.data.NoteBlock.Heading -> "▍${block.text}"
    is com.vic.inkflow.data.NoteBlock.Paragraph -> block.text
    is com.vic.inkflow.data.NoteBlock.Bullet -> "• ${block.text}"
    is com.vic.inkflow.data.NoteBlock.Example -> "Q: ${block.question}\nA: ${block.answer}"
    is com.vic.inkflow.data.NoteBlock.Flashcard -> "Q: ${block.front}\nA: ${block.back}"
    is com.vic.inkflow.data.NoteBlock.Quiz -> "Q: ${block.question}\n" +
        block.options.mapIndexed { index, option -> "${index + 1}) $option" }.joinToString("\n") +
        "\nAnswer: ${block.options.getOrNull(block.answerIndex).orEmpty()}"
}

/** A small segmented choice for the panel's two modes. */
@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val fill = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val content = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(fill)
            .glassClickable(onClick = onClick, shape = RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = content)
    }
}
