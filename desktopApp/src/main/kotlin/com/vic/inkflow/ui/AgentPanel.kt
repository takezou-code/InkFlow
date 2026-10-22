package com.vic.inkflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.OutlinedTextField
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
import com.vic.inkflow.agent.NoteLandingHandle
import com.vic.inkflow.agent.ReadPageTextTool
import com.vic.inkflow.agent.RenderPageTool
import com.vic.inkflow.data.LessonNote
import com.vic.inkflow.data.NoteBlock
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
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (collapsed) {
        Box(modifier = modifier.width(56.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .glassClickable(onClick = onToggleCollapsed, shape = RoundedCornerShape(16.dp))
                    .padding(10.dp)
            ) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = "展開 AI 教材筆記",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        return
    }

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

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(360.dp)
            .clip(RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
            .glassSidePanel(InkThemeState.darkMode)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "AI 教材筆記",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassTextButton(
                        text = if (DesktopSettings.modelSendImages) "送圖：開" else "送圖：關",
                        onClick = {
                            // Sending an image to a model that cannot see one is
                            // a guaranteed error, so this is a switch the user
                            // can reach from the panel itself.
                            DesktopSettings.modelSendImages = !DesktopSettings.modelSendImages
                            tools = rebuildTools(documentUri)
                        }
                    )
                    Spacer(Modifier.width(4.dp))
                    GlassTextButton(
                        text = if (models.isEmpty()) "檢查模型" else "${models.size} 個模型",
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
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .glassClickable(onClick = onToggleCollapsed, shape = RoundedCornerShape(12.dp))
                            .padding(8.dp)
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = "收合",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = "本機模型：${DesktopSettings.modelName.ifBlank { "未設定" }} · ${DesktopSettings.modelBaseUrl}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = instruction,
                onValueChange = { instruction = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("把這一頁整理成教材筆記") },
                shape = RoundedCornerShape(12.dp),
                maxLines = 3,
                colors = glassFieldColors(InkThemeState.darkMode)
            )

            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassTextButton(
                    text = if (busy) "進行中…" else "產生教材筆記",
                    onClick = {
                        if (busy) return@GlassTextButton
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
                    enabled = !busy
                )
            }

            Spacer(Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
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
                            Spacer(Modifier.width(6.dp))
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                note?.let { produced ->
                    item { NotePreview(produced) }
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
                            Spacer(Modifier.width(6.dp))
                            Text(message, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            note?.let { produced ->
                GlassTextButton(
                    text = "寫進文件",
                    onClick = {
                        val placed = landing.land(produced)
                        landed = if (placed > 0) "已寫進第 ${pageIndex + 1} 頁（$placed 段）" else "寫入失敗"
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
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
