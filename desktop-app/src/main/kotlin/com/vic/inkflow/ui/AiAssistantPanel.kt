package com.vic.inkflow.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.vic.inkflow.ui.glassDressing
import com.vic.inkflow.ui.glassSidePanel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** AI panel UI states (spec §4C). */
enum class AiState { Idle, Processing, Result, Error }

data class AiAction(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val prompt: String
)

data class AiMessage(
    val fromUser: Boolean,
    val text: String,
    val state: AiState = AiState.Result
)

/**
 * AI Assistant sidebar (spec §4C): expandable panel on the right of the reader.
 * Framework actions wired to a stub backend — replace [runAiAction] with a real
 * API call (e.g. OpenAI-compatible endpoint) when available.
 */
@Composable
fun AiAssistantPanel(
    documentUri: String,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (collapsed) {
        // Collapsed rail: just an expand button.
        Box(modifier = modifier.width(56.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Spacer(Modifier.height(12.dp))
            FilledIconToggleButton(checked = true, onCheckedChange = { onToggleCollapsed() }) {
                Icon(Icons.Default.AutoAwesome, contentDescription = "展開 AI 助手")
            }
        }
        return
    }

    val actions = remember {
        listOf(
            AiAction("summary", "智能摘要", Icons.Default.Summarize, "請為目前文件生成章節摘要"),
            AiAction("ocr", "手寫轉文字", Icons.Default.Description, "請將同步過來的筆跡辨識為可搜尋文字"),
            AiAction("knowledge", "知識卡片", Icons.Default.Calculate, "請將筆記內容整理成 Q&A 知識卡片"),
            AiAction("search", "關鍵字搜尋", Icons.Default.Search, "在識別文字中搜尋…")
        )
    }

    var messages by remember(documentUri) { mutableStateOf<List<AiMessage>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Was a solid `surfaceVariant` with tonalElevation = 3.dp. It floats over the
    // page, so it gets the shared glass treatment — leading rim, because on a
    // full-height strip that is the only edge that is ever visible.
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(348.dp)
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
                    "AI 閱讀助手",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                IconButton(onClick = onToggleCollapsed) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = "收合")
                }
            }

            Spacer(Modifier.height(8.dp))

            // Action buttons (Tonal buttons per spec)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                actions.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { a ->
                            FilledTonalButton(
                                onClick = {
                                    if (!busy) {
                                        messages = messages + AiMessage(true, a.label) + AiMessage(false, "", AiState.Processing)
                                        busy = true
                                        scope.launch {
                                            // ── Stub backend: simulate latency, then placeholder result.
                                            runAiAction(a, documentUri)
                                            delay(900)
                                            val last = messages.size - 1
                                            messages = messages.toMutableList().also { m ->
                                                m[last] = AiMessage(
                                                    false,
                                                    "【${a.label}】功能框架就緒，等待接入真實 AI API。\n\nPrompt: ${a.prompt}",
                                                    AiState.Result
                                                )
                                            }
                                            busy = false
                                        }
                                    }
                                },
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                            ) {
                                Icon(a.icon, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(a.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            }
                        }
                    }
                }
            }

            Divider(Modifier.padding(vertical = 10.dp))

            // Conversation area
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "選擇上方功能，或輸入問題開始對話。\n（截圖提問：在左側框選區域後按「Send」附图）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                items(messages) { msg ->
                    AiBubble(msg)
                }
            }

            Spacer(Modifier.height(8.dp))

            // Input row
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("問 AI…") },
                    shape = RoundedCornerShape(12.dp),
                    maxLines = 3
                )
                Spacer(Modifier.width(6.dp))
                FilledIconButton(
                    onClick = {
                        if (input.isNotBlank() && !busy) {
                            val q = input
                            input = ""
                            messages = messages + AiMessage(true, q) + AiMessage(false, "", AiState.Processing)
                            busy = true
                            scope.launch {
                                delay(900)
                                val last = messages.size - 1
                                messages = messages.toMutableList().also { m ->
                                    m[last] = AiMessage(false, "（API 待接入）已收到：$q", AiState.Result)
                                }
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && input.isNotBlank()
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "送出")
                }
            }
        }
    }
}

/**
 * A conversation bubble.
 *
 * Asymmetric on purpose: the user's own words get a filled, saturated pill so the
 * transcript is scannable at a glance, while the assistant's reply is glass —
 * translucent, so it sits *behind* the page it is talking about rather than
 * competing with it for attention.
 */
@Composable
private fun AiBubble(msg: AiMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.fromUser) Arrangement.End else Arrangement.Start
    ) {
        if (msg.fromUser) {
            Surface(
                shape = RoundedCornerShape(topStart = 14.dp, topEnd = 4.dp, bottomEnd = 14.dp, bottomStart = 14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.widthIn(max = 300.dp)
            ) {
                BubbleContent(msg)
            }
        } else {
            Box(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 14.dp))
                    .glassDressing(
                        isDark = InkThemeState.darkMode,
                        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 14.dp),
                        // No rim: at this size a hairline reads as a rendering
                        // artefact rather than a lit edge.
                        specular = false
                    )
            ) {
                BubbleContent(msg)
            }
        }
    }
}

@Composable
private fun BubbleContent(msg: AiMessage) {
    when (msg.state) {
        AiState.Processing -> Row(
            Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("AI 分析中…", style = MaterialTheme.typography.bodySmall)
        }
        AiState.Error -> Text(
            msg.text.ifBlank { "發生錯誤，請重試" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(12.dp)
        )
        else -> Text(
            msg.text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp)
        )
    }
}

/** Placeholder for the future real AI backend invocation. */
private suspend fun runAiAction(action: AiAction, documentUri: String) {
    /* no-op: replaced when a real API client is added */
}
