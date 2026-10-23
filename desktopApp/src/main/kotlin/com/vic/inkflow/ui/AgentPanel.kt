package com.vic.inkflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vic.inkflow.DesktopSettings
import com.vic.inkflow.agent.NoteLanding
import com.vic.inkflow.agent.NoteLandingHandle
import com.vic.inkflow.ui.kit.DeskPrimaryButton
import com.vic.inkflow.ui.kit.DeskSidePanel
import com.vic.inkflow.ui.kit.DeskSpace
import com.vic.inkflow.ui.kit.DeskTextField
import com.vic.inkflow.ui.kit.DeskPillButton
import com.vic.inkflow.ui.GlassOptionChip
import com.vic.inkflow.ui.GlassTextButton

/**
 * The AI panel: an agent that turns a page into a study note.
 */
@Composable
fun AgentPanel(
    documentUri: String,
    pageIndex: Int,
    landing: NoteLandingHandle,
    onToggleCollapsed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val vm = remember(documentUri, pageIndex) {
        AgentPanelViewModel(documentUri, pageIndex, landing, scope)
    }
    val state by vm.state.collectAsState()

    val modelSet = DesktopSettings.modelName.isNotBlank()

    DeskSidePanel(
        title = "AI 教材筆記",
        titleIcon = Icons.Default.AutoAwesome,
        subtitle = if (modelSet) DesktopSettings.modelName else "未設定模型",
        onClose = onToggleCollapsed,
        modifier = modifier,
        footer = state.note?.let {
            {
                DeskPrimaryButton(
                    text = "寫進文件",
                    icon = Icons.Default.Check,
                    onClick = { vm.setLanded(vm.landNote()) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(DeskSpace.md)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DeskSpace.xs),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (modelSet) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                )
                Text(
                    text = if (modelSet) DesktopSettings.modelBaseUrl else "請到設定填入本機模型",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                DeskPillButton(
                    text = if (state.models.isEmpty()) "檢查模型" else "${state.models.size} 個",
                    icon = Icons.Default.Search,
                    onClick = { vm.checkModels() }
                )
            }

            Spacer(Modifier.height(DeskSpace.xs))

            GlassOptionChip(
                text = if (DesktopSettings.modelSendImages) "送圖：開" else "送圖：關",
                selected = DesktopSettings.modelSendImages,
                onClick = { vm.toggleSendImages() },
                isDark = InkThemeState.darkMode
            )

            Spacer(Modifier.height(DeskSpace.sm))

            DeskTextField(
                value = state.instruction,
                onValueChange = { vm.setInstruction(it) },
                placeholder = "把這一頁整理成教材筆記",
                singleLine = false,
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(DeskSpace.xs))

            DeskPrimaryButton(
                text = if (state.busy) "進行中…" else "產生教材筆記",
                icon = Icons.Default.AutoAwesome,
                onClick = { vm.generate() },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(DeskSpace.sm))

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(DeskSpace.xs)
            ) {
                if (state.steps.isEmpty() && state.failure == null && state.note == null && !state.busy) {
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
                items(state.steps) { step ->
                    StepRow(step)
                }
                state.failure?.let { message ->
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
                state.note?.let { preview ->
                    item { NotePreview(preview) }
                }
                state.landed?.let { message ->
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
private fun StepRow(step: com.vic.inkflow.agent.AgentStep) {
    val (icon, text, tint) = when (step) {
        is com.vic.inkflow.agent.AgentStep.Thought -> Triple(Icons.Default.AutoAwesome, step.text, MaterialTheme.colorScheme.onSurfaceVariant)
        is com.vic.inkflow.agent.AgentStep.ToolCall -> Triple(Icons.Default.Search, "呼叫 ${step.tool}", MaterialTheme.colorScheme.primary)
        is com.vic.inkflow.agent.AgentStep.ToolOutcome -> Triple(
            if (step.ok) Icons.Default.Check else Icons.Default.WarningAmber,
            "${step.tool}：${step.summary}",
            if (step.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
        )
        is com.vic.inkflow.agent.AgentStep.NoteProduced -> Triple(Icons.Default.MenuBook, "筆記完成", MaterialTheme.colorScheme.primary)
        is com.vic.inkflow.agent.AgentStep.Warning -> Triple(Icons.Default.WarningAmber, step.message, MaterialTheme.colorScheme.error)
    }
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.width(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

/** The produced note, shown before it lands. */
@Composable
private fun NotePreview(note: com.vic.inkflow.data.LessonNote) {
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

private fun previewTextOf(block: com.vic.inkflow.data.NoteBlock): String = when (block) {
    is com.vic.inkflow.data.NoteBlock.Heading -> "▍${block.text}"
    is com.vic.inkflow.data.NoteBlock.Paragraph -> block.text
    is com.vic.inkflow.data.NoteBlock.Bullet -> "· ${block.text}"
    is com.vic.inkflow.data.NoteBlock.Example -> "Q: ${block.question}\nA: ${block.answer}"
    is com.vic.inkflow.data.NoteBlock.Flashcard -> "Q: ${block.front}\nA: ${block.back}"
    is com.vic.inkflow.data.NoteBlock.Quiz -> "Q: ${block.question}\n" +
        block.options.mapIndexed { index, option -> "${index + 1}) $option" }.joinToString("\n") +
        "\nAnswer: ${block.options.getOrNull(block.answerIndex).orEmpty()}"
}
