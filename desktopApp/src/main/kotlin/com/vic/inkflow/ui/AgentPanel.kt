package com.vic.inkflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vic.inkflow.DesktopSettings
import com.vic.inkflow.agent.AgentGoal
import com.vic.inkflow.agent.AgentLoop
import com.vic.inkflow.agent.AgentStep
import com.vic.inkflow.agent.LocalOpenAiProvider
import com.vic.inkflow.agent.NoteLanding
import com.vic.inkflow.agent.NoteLandingHandle
import com.vic.inkflow.agent.ReadPageTextTool
import com.vic.inkflow.agent.RenderPageTool
import com.vic.inkflow.data.LessonNote
import com.vic.inkflow.data.NoteBlock
import com.vic.inkflow.ui.kit.DeskPillButton
import com.vic.inkflow.ui.kit.DeskPrimaryButton
import com.vic.inkflow.ui.kit.DeskSidePanel
import com.vic.inkflow.ui.kit.DeskSpace
import com.vic.inkflow.ui.kit.DeskTextField
import com.vic.inkflow.util.DesktopPdfEngine
import kotlinx.coroutines.launch

/**
 * The AI panel: an agent that turns a page into a study note.
 *
 * ## What it shows, and why
 *
 * A coding agent shows its steps because the artefact it produces is only
 * trustworthy if the path to it is visible. This panel does the same: the
 * steps (what the model thought, which tool it called, what came back) sit
 * above the artefact, and the artefact lands in the document only when the
 * user says so.
 *
 * The panel is deliberately **not** a chat. A transcript invites the user to
 * keep typing; the promise here is that one instruction produces one note.
 *
 * ## The pieces
 *
 *  - the **steps** are the agent's own words, not a spinner: the model has
 *    said what it is about to do and why;
 *  - the **note** is shown before it lands, in full, so a bad note costs
 *    nothing;
 *  - **寫進文件** is the only way the note touches the document.
 */
@Composable
fun AgentPanel(
    documentUri: String,
    pageIndex: Int,
    landing: NoteLandingHandle,
    onToggleCollapsed: () -> Unit,
    modifier: Modifier = Modifier
) {
    var instruction by remember { mutableStateOf(DEFAULT_INSTRUCTION) }
    var steps by remember { mutableStateOf<List<AgentStep>>(emptyList()) }
    var note by remember { mutableStateOf<LessonNote?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var landed by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()

    // The tools follow the "send images" switch: the tool that renders a page
    // is only offered when images are wanted, because a model that cannot see
    // an image will only produce an error.
    var tools by remember { mutableStateOf(rebuildTools(documentUri)) }

    val produced = note
    val modelSet = DesktopSettings.modelName.isNotBlank()

    DeskSidePanel(
        title = "AI 教材筆記",
        titleIcon = Icons.Default.AutoAwesome,
        subtitle = if (modelSet) DesktopSettings.modelName else "未設定模型",
        onClose = onToggleCollapsed,
        modifier = modifier,
        // The only way the note touches the document. It lives in the panel's
        // footer so it stays reachable however long the step list grows.
        footer = if (produced != null) {
            {
                DeskPrimaryButton(
                    text = "寫進文件",
                    icon = Icons.Default.Check,
                    onClick = {
                        landed = when (val result = landing.land(produced)) {
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
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else null
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(DeskSpace.md)) {
            // Model status + the one control that needs a network round-trip.
            // A dot carries the state for anyone who cannot read the label colour.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DeskSpace.xs),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (modelSet) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                )
                Text(
                    text = if (modelSet) DesktopSettings.modelBaseUrl else "請到設定填入本機模型",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                DeskPillButton(
                    text = if (models.isEmpty()) "檢查模型" else "${models.size} 個",
                    icon = Icons.Default.Search,
                    onClick = {
                        scope.launch {
                            val found = LocalOpenAiProvider(DesktopSettings.localModelConfig()).listModels()
                            models = found.map { it.id }
                            if (DesktopSettings.modelName.isBlank() && found.isNotEmpty()) {
                                DesktopSettings.modelName = found.first().id
                            }
                        }
                    }
                )
            }

            Spacer(Modifier.height(DeskSpace.xs))

            // Send-images toggle as a chip, not a text button: it is a state, and
            // a state belongs in the chip language the rest of the app uses.
            GlassOptionChip(
                text = if (DesktopSettings.modelSendImages) "送圖：開" else "送圖：關",
                selected = DesktopSettings.modelSendImages,
                onClick = {
                    DesktopSettings.modelSendImages = !DesktopSettings.modelSendImages
                    tools = rebuildTools(documentUri)
                },
                isDark = InkThemeState.darkMode
            )

            Spacer(Modifier.height(DeskSpace.sm))

            DeskTextField(
                value = instruction,
                onValueChange = { instruction = it },
                placeholder = "把這一頁整理成教材筆記",
                singleLine = false,
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(DeskSpace.xs))

            DeskPrimaryButton(
                text = if (busy) "進行中…" else "產生教材筆記",
                icon = Icons.Default.AutoAwesome,
                onClick = {
                    if (busy) return@DeskPrimaryButton
                    busy = true
                    failure = null
                    landed = null
                    note = null
                    steps = emptyList()
                    scope.launch {
                        val provider = LocalOpenAiProvider(DesktopSettings.localModelConfig())
                        val loop = AgentLoop(provider, tools)
                        val outcome = loop.run(
                            AgentGoal(
                                instruction = instruction.ifBlank { DEFAULT_INSTRUCTION },
                                pageHint = "第 ${pageIndex + 1} 頁"
                            )
                        ) { step -> steps = steps + step }
                        note = outcome.note
                        failure = outcome.failure
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(DeskSpace.sm))

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(DeskSpace.xs)
            ) {
                if (steps.isEmpty() && failure == null && produced == null && !busy) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = DeskSpace.lg),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.MenuBook,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(Modifier.height(DeskSpace.xs))
                                Text(
                                    "尚無筆記",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "輸入指令後點「產生教材筆記」",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                items(steps) { step ->
                    StepRow(step)
                }
                failure?.let { message ->
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                produced?.let { preview ->
                    item { NotePreview(preview) }
                }
                landed?.let { message ->
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(16.dp)
                            )
                            Spacer(Modifier.width(DeskSpace.xs))
                            Text(message, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

/** One step of the run, in one line. */
@Composable
private fun StepRow(step: AgentStep) {
    val (icon, text, tint) = when (step) {
        is AgentStep.Thought -> Triple(Icons.Default.AutoAwesome, step.text, MaterialTheme.colorScheme.onSurfaceVariant)
        is AgentStep.ToolCall -> Triple(Icons.Default.Search, "呼叫 ${step.tool}", MaterialTheme.colorScheme.primary)
        is AgentStep.ToolOutcome -> Triple(
            if (step.ok) Icons.Default.Check else Icons.Default.WarningAmber,
            "${step.tool}：${step.summary}",
            if (step.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
        )
        is AgentStep.NoteProduced -> Triple(Icons.Default.MenuBook, "筆記完成", MaterialTheme.colorScheme.primary)
        is AgentStep.Warning -> Triple(Icons.Default.WarningAmber, step.message, MaterialTheme.colorScheme.error)
    }
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.width(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

/** The produced note, shown before it lands. */
@Composable
private fun NotePreview(note: LessonNote) {
    Column(Modifier.fillMaxWidth()) {
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

private fun previewTextOf(block: NoteBlock): String = when (block) {
    is NoteBlock.Heading -> "▍${block.text}"
    is NoteBlock.Paragraph -> block.text
    is NoteBlock.Bullet -> "· ${block.text}"
    is NoteBlock.Example -> "Q: ${block.question}\nA: ${block.answer}"
    is NoteBlock.Flashcard -> "Q: ${block.front}\nA: ${block.back}"
    is NoteBlock.Quiz -> "Q: ${block.question}\n" +
        block.options.mapIndexed { index, option -> "${index + 1}) $option" }.joinToString("\n") +
        "\nAnswer: ${block.options.getOrNull(block.answerIndex).orEmpty()}"
}

/** The default instruction. */
private const val DEFAULT_INSTRUCTION = "把這一頁整理成教材筆記：重點、範例、字卡與自測題"

/**
 * The tools the agent gets for this document.
 *
 * The page-rendering tool is only offered when the user wants images sent:
 * a text-only model asked to look at an image fails, and a failed run is
 * worse than a note written from the page's text.
 */
private fun rebuildTools(documentUri: String): List<com.vic.inkflow.agent.AgentTool> = buildList {
    add(ReadPageTextTool(documentUri))
    if (DesktopSettings.modelSendImages) {
        add(RenderPageTool(documentUri, DesktopPdfEngine()))
    }
}
