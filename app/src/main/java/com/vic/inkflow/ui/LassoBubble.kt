package com.vic.inkflow.ui

// ── 套索氣泡：選取後的浮動工具列（提取/AI 解析/複製/刪除＋AI 快捷列） ──────
// 自 PageWorkspace 抽出（純搬家，行為零變化）：定位數學 + 送 AI 管線 + 按鈕列。
// Workspace 只留狀態（選取快照/isExtracting/泡泡尺寸）與一行呼叫。

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

internal object AiQuickPrompt {
    const val EXPLAIN = "請用繁體中文詳細解釋這張圖片中的內容，包含重點與關鍵概念。"
    const val SUMMARIZE = "請用繁體中文總結這張圖片內容的重點，條列不超過5點。"
    const val TRANSLATE = "請將這張圖片中的文字翻譯成繁體中文，只輸出譯文。"
}

/** 套索氣泡的圖示動作鈕：全自繪（Box + clickable），不用 TextButton。
 *  M3 TextButton 自帶 chrome 會在圖示列畫出一條銳利白帶（Bisect A/B 定案），故棄用。 */
@Composable
private fun SelectionBubbleAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    tint: Color = Color(0xFF1E293B),
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(20.dp),
                tint = if (enabled) tint else tint.copy(alpha = 0.38f)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
        }
    }
}

private fun polygonBounds(points: List<Offset>): Rect? {
    if (points.isEmpty()) return null
    val minX = points.minOf { it.x }
    val minY = points.minOf { it.y }
    val maxX = points.maxOf { it.x }
    val maxY = points.maxOf { it.y }
    if (maxX <= minX || maxY <= minY) return null
    return Rect(minX, minY, maxX, maxY)
}

/**
 * 氣泡定位（pure）：以套索區為錨，優先貼在區上方、放不下改貼下方，鉗在紙內。
 * 全 px 進出，無 density 依賴。
 */
internal fun bubbleTargetOffset(
    regionPolygon: List<Offset>,
    itemWidthPx: Int,
    bubbleWidthPx: Int,
    bubbleHeightPx: Int,
    aspect: Float,
    gapPx: Float,
    sidePaddingPx: Float,
    topSafePx: Float,
    modelW: Float,
    modelH: Float
): IntOffset {
    val regionBounds = polygonBounds(regionPolygon)
    if (regionBounds == null || itemWidthPx <= 0 || modelW <= 0f || modelH <= 0f) {
        return IntOffset(0, 0)
    }
    val wF = itemWidthPx.toFloat()
    val hF = wF / aspect
    val sx = wF / modelW
    val sy = hF / modelH
    val left = regionBounds.left * sx
    val top = regionBounds.top * sy
    val right = regionBounds.right * sx
    val bottom = regionBounds.bottom * sy
    val cx = (left + right) / 2f
    val bw = bubbleWidthPx.toFloat()
    val bh = bubbleHeightPx.toFloat()
    val aboveY = top - bh - gapPx
    val y = if (aboveY < topSafePx) bottom + gapPx else aboveY
    val maxX = maxOf(sidePaddingPx, wF - bw - sidePaddingPx)
    val maxY = maxOf(topSafePx, hF - bh - topSafePx)
    return IntOffset(
        ((cx - bw / 2f).coerceIn(sidePaddingPx, maxX)).roundToInt(),
        y.coerceIn(topSafePx, maxY).roundToInt()
    )
}

// 圈選送 AI 共用管線：擷取套索區 → 分享檔 → FileProvider Uri → 交給 AI 面板（附可選快捷 prompt）。
internal fun sendRegionToAi(
    scope: CoroutineScope,
    pdfViewModel: PdfViewModel,
    viewModel: EditorViewModel,
    context: Context,
    onAiFileReady: (android.net.Uri, String?) -> Unit,
    isExtracting: Boolean,
    onExtractingChange: (Boolean) -> Unit,
    hasSelection: Boolean,
    hasRegionSnapshot: Boolean,
    prompt: String?,
    pageIdx: Int
) {
    if (isExtracting || !hasSelection || !hasRegionSnapshot) return
    onExtractingChange(true)
    scope.launch {
        try {
            val sourceBitmap = withTimeoutOrNull(1200) {
                pdfViewModel.getPageBitmap(pageIdx).filterNotNull().first()
            } ?: pdfViewModel.getPageBitmap(pageIdx).value

            val file = viewModel.extractRegionToShareFile(
                context = context,
                sourcePageIndex = pageIdx,
                pdfPageBitmap = sourceBitmap
            )
            if (file != null) {
                val fileUri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                onAiFileReady(fileUri, prompt)
            }
        } finally {
            onExtractingChange(false)
        }
    }
}

@Composable
internal fun LassoBubble(
    visible: Boolean,
    modifier: Modifier = Modifier,
    onSizeChanged: (IntSize) -> Unit,
    hasSelection: Boolean,
    hasEditableSelection: Boolean,
    hasRegionSnapshot: Boolean,
    isExtracting: Boolean,
    onExtractingChange: (Boolean) -> Unit,
    viewModel: EditorViewModel,
    pdfViewModel: PdfViewModel,
    documentUri: String,
    onAiFileReady: (android.net.Uri, String?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(animationSpec = tween(180)) +
            slideInVertically(
                animationSpec = tween(220, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                initialOffsetY = { it / 2 }
            ),
        exit = fadeOut(animationSpec = tween(140)) +
            slideOutVertically(
                animationSpec = tween(160, easing = androidx.compose.animation.core.FastOutLinearInEasing),
                targetOffsetY = { it / 3 }
            )
    ) {
        // 最終版：Box + 自繪毛玻璃 + 自繪按鈕，零 M3 Surface/TextButton（白帶兇手，已定案棄用）。
        // 陰影用 graphicsLayer 打（不經過 Surface，避免 tonal/shadow 附帶圖層）。
        Box(
            modifier = Modifier
                .graphicsLayer {
                    shadowElevation = with(density) { 8.dp.toPx() }
                    shape = RoundedCornerShape(22.dp)
                    clip = false
                }
                .bubbleGlass(false, RoundedCornerShape(22.dp))
                .onSizeChanged { onSizeChanged(it) }
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SelectionBubbleAction(
                        icon = Icons.Filled.LibraryAdd,
                        label = "提取",
                        enabled = !isExtracting && hasSelection && hasRegionSnapshot,
                        onClick = {
                            if (isExtracting || !hasSelection || !hasRegionSnapshot) return@SelectionBubbleAction
                            onExtractingChange(true)
                            scope.launch {
                                try {
                                    // 提取源 = 選取歸屬紙（全活頁下未必是作用頁）
                                    val sourcePageIndex = viewModel.selectionPage()
                                    val sourceBitmap = withTimeoutOrNull(1200) {
                                        pdfViewModel.getPageBitmap(sourcePageIndex)
                                            .filterNotNull()
                                            .first()
                                    } ?: pdfViewModel.getPageBitmap(sourcePageIndex).value

                                    val newPageIndex = sourcePageIndex + 1
                                    // R2：插頁是結構操作，先清棧（後面頁號全移位，舊復原格會錯位）。
                                    viewModel.clearUndoStacks()
                                    pdfViewModel.insertBlankPage(
                                        context, documentUri, sourcePageIndex,
                                        pageWidthPt = viewModel.modelWidth,
                                        pageHeightPt = viewModel.modelHeight
                                    )

                                    val ok = viewModel.extractRegionToNewPage(
                                        context = context,
                                        sourcePageIndex = sourcePageIndex,
                                        targetPageIndex = newPageIndex,
                                        pdfPageBitmap = sourceBitmap
                                    )
                                    if (!ok) {
                                        // P0：渲染失敗時收掉剛建的空白頁，不留孤兒頁；給提示。
                                        pdfViewModel.deletePages(documentUri, listOf(newPageIndex))
                                        android.widget.Toast.makeText(
                                            context, "提取失敗：畫面尚未就緒，請稍後再試",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                } finally {
                                    onExtractingChange(false)
                                }
                            }
                        }
                    )
                    SelectionBubbleAction(
                        icon = Icons.Filled.AutoAwesome,
                        label = "AI 解析",
                        enabled = !isExtracting && hasSelection && hasRegionSnapshot,
                        // P0：用選取歸屬紙（同提取/快捷列），之前誤用作用頁，跨頁選取會送錯圖。
                        onClick = {
                            sendRegionToAi(
                                scope, pdfViewModel, viewModel, context, onAiFileReady,
                                isExtracting, onExtractingChange, hasSelection, hasRegionSnapshot,
                                null, viewModel.selectionPage()
                            )
                        }
                    )
                    SelectionBubbleAction(
                        icon = Icons.Filled.ContentCopy,
                        label = "複製",
                        enabled = hasEditableSelection,
                        onClick = {
                            if (!hasEditableSelection) return@SelectionBubbleAction
                            viewModel.copySelectionInPlace()
                        }
                    )
                    SelectionBubbleAction(
                        icon = Icons.Filled.DeleteOutline,
                        label = "刪除",
                        enabled = hasEditableSelection,
                        tint = if (hasEditableSelection) MaterialTheme.colorScheme.error else Color(0xFF1E293B),
                        onClick = {
                            if (!hasEditableSelection) return@SelectionBubbleAction
                            viewModel.deleteSelection()
                        }
                    )
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable { viewModel.clearSelection() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "取消選取",
                            modifier = Modifier.size(16.dp),
                            tint = Color(0xFF1E293B)
                        )
                    }
                } // 氣泡第一排
                // 快捷指令第二排：有圈選區才出現，按下後送圖 + prompt 自動送出
                if (hasRegionSnapshot && hasSelection) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SelectionBubbleAction(
                            icon = Icons.Filled.QuestionAnswer,
                            label = "解釋",
                            enabled = !isExtracting,
                            onClick = {
                                sendRegionToAi(
                                    scope, pdfViewModel, viewModel, context, onAiFileReady,
                                    isExtracting, onExtractingChange, hasSelection, hasRegionSnapshot,
                                    AiQuickPrompt.EXPLAIN, viewModel.selectionPage()
                                )
                            }
                        )
                        SelectionBubbleAction(
                            icon = Icons.Filled.Summarize,
                            label = "總結",
                            enabled = !isExtracting,
                            onClick = {
                                sendRegionToAi(
                                    scope, pdfViewModel, viewModel, context, onAiFileReady,
                                    isExtracting, onExtractingChange, hasSelection, hasRegionSnapshot,
                                    AiQuickPrompt.SUMMARIZE, viewModel.selectionPage()
                                )
                            }
                        )
                        SelectionBubbleAction(
                            icon = Icons.Filled.Translate,
                            label = "翻譯",
                            enabled = !isExtracting,
                            onClick = {
                                sendRegionToAi(
                                    scope, pdfViewModel, viewModel, context, onAiFileReady,
                                    isExtracting, onExtractingChange, hasSelection, hasRegionSnapshot,
                                    AiQuickPrompt.TRANSLATE, viewModel.selectionPage()
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}
