package com.vic.inkflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.vic.inkflow.data.repository.InkFlowRepositories
import com.vic.inkflow.util.reorderable
import com.vic.inkflow.util.reorderableItem
import com.vic.inkflow.ui.theme.ShapeLg
import kotlinx.coroutines.launch

/**
 * 第 3 階：頁面網格浮層。
 *
 * 為什麼獨立成檔、而不是側欄寬度的一種取值：
 * 舊碼把 GRID 當成 `targetWidth = totalWidth`，讓 128dp 一路彈到約 800dp。
 * 那是 6.7 倍距離的 spring，加上動畫期間側欄內容每幀重排，動畫必然崩潰。
 * 現在 GRID 是覆蓋在紙之上的獨立層，寬度軸完全不參與。
 *
 * 多選／只看書籤／拖曳排序／刪除確認這些狀態全部屬於網格自己，
 * 不與側欄的 RAIL／PANEL 共用——舊碼把它們混在同一個 `Sidebar()` 裡，
 * 導致切階段時狀態被重建。
 */
@Composable
internal fun SidebarPageGrid(
    pageCount: Int,
    currentPageIndex: Int,
    pdfViewModel: PdfViewModel,
    repos: InkFlowRepositories,
    documentUri: String,
    modelWidth: Float,
    modelHeight: Float,
    hazeState: dev.chrisbanes.haze.HazeState,
    isDarkTheme: Boolean,
    onBack: () -> Unit,
    onCollapse: () -> Unit,
    onPageSelected: (Int) -> Unit,
    onDeletePages: (List<Int>) -> Unit,
    onStructureChanged: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gridState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedPages by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var showOnlyBookmarked by remember { mutableStateOf(false) }
    var deleteConfirmIndices by remember { mutableStateOf<List<Int>>(emptyList()) }

    val bookmarkedPages by pdfViewModel.getBookmarkedPages(documentUri)
        .collectAsState(initial = emptyList())
    val thumbnailVersion by pdfViewModel.thumbnailVersion.collectAsState()
    val isPageOperationInProgress by pdfViewModel.isPageOperationInProgress.collectAsState()

    val visibleIndices = remember(pageCount, showOnlyBookmarked, bookmarkedPages) {
        if (showOnlyBookmarked) (0 until pageCount).filter { it in bookmarkedPages }
        else (0 until pageCount).toList()
    }
    var dragOrder by remember(visibleIndices, thumbnailVersion) { mutableStateOf(visibleIndices) }

    val reorderState = com.vic.inkflow.util.rememberReorderableLazyGridState(
        gridState = gridState,
        onMove = { from, to ->
            val newOrder = dragOrder.toMutableList()
            val item = newOrder.removeAt(from)
            newOrder.add(to, item)
            dragOrder = newOrder
        },
        onDragEnd = { from, to ->
            if (!showOnlyBookmarked && !isSelectionMode) {
                // R2：移頁是結構操作，清棧。
                onStructureChanged()
                pdfViewModel.movePage(documentUri, visibleIndices[from], visibleIndices[to])
            }
        }
    )

    // 欄數實測，不能寫死。
    // 舊碼寫 `currentPageIndex / 4`，欄數一改（現在是 7 欄）就會把當前頁捲到錯的列。
    // 同一列的 item 共用同一個 y offset，取最上面一列數幾個即為欄數。
    val columnCount by remember { derivedStateOf { gridState.measuredColumnCount() } }
    var didInitialScroll by remember { mutableStateOf(false) }
    LaunchedEffect(columnCount) {
        if (!didInitialScroll && columnCount > 0) {
            didInitialScroll = true
            val row = currentPageIndex / columnCount
            gridState.scrollToItem(index = (row * columnCount).coerceAtLeast(0))
        }
    }

    if (deleteConfirmIndices.isNotEmpty()) {
        val indices = deleteConfirmIndices
        GlassDialog(
            onDismissRequest = { deleteConfirmIndices = emptyList() },
            isDark = isDarkTheme,
            title = { Text("刪除頁面") },
            text = {
                Text(
                    if (indices.size == 1) "確定要刪除第 ${indices[0] + 1} 頁？此操作無法復原。"
                    else "確定要刪除這 ${indices.size} 頁？此操作無法復原。"
                )
            },
            confirmText = "刪除",
            onConfirm = {
                deleteConfirmIndices = emptyList()
                onDeletePages(indices)
                isSelectionMode = false
                selectedPages = emptySet()
            },
            confirmEnabled = !isPageOperationInProgress,
            confirmColor = MaterialTheme.colorScheme.error
        )
    }

    Column(modifier.fillMaxSize()) {
        // 玻璃頂欄：跟工具列藥丸同一語言
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .glassPanel(hazeState, isDarkTheme, ShapeLg)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSelectionMode) {
                    IconButton(onClick = {
                        isSelectionMode = false
                        selectedPages = emptySet()
                    }) {
                        Icon(Icons.Outlined.Close, contentDescription = "取消多選")
                    }
                    Text(
                        text = "已選取 ${selectedPages.size} 頁",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        if (selectedPages.size == pageCount) selectedPages = emptySet()
                        else selectedPages = (0 until pageCount).toSet()
                    }) {
                        Text(if (selectedPages.size == pageCount) "取消全選" else "全選")
                    }
                    IconButton(
                        onClick = {
                            if (selectedPages.isNotEmpty() && !isPageOperationInProgress) {
                                deleteConfirmIndices = selectedPages.toList()
                            }
                        },
                        enabled = selectedPages.isNotEmpty() && !isPageOperationInProgress
                    ) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            contentDescription = "刪除選擇",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "回預覽條")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "所有頁面", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "${visibleIndices.size} 頁",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // 書籤開關：玻璃小丸（取代 M3 FilterChip）
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .then(
                                if (showOnlyBookmarked) Modifier.glassPanel(hazeState, isDarkTheme, CircleShape)
                                else Modifier
                            )
                            .clickable { showOnlyBookmarked = !showOnlyBookmarked }
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (showOnlyBookmarked) Icons.Outlined.Star
                            else Icons.Outlined.BookmarkBorder,
                            contentDescription = "只看書籤",
                            tint = if (showOnlyBookmarked) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = { isSelectionMode = true }) {
                        Icon(Icons.Outlined.Checklist, contentDescription = "多選頁面")
                    }
                    // 收合鈕：第 3 階必須有一條直接回 RAIL 的路。
                    // 舊碼只有返回鈕（回第 2 階），而 drag strip 在 GRID 不畫 → 單向門。
                    IconButton(onClick = onCollapse) {
                        Icon(Icons.Outlined.ChevronLeft, contentDescription = "收合側欄")
                    }
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(240.dp),
            state = gridState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 64.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxSize()
                .reorderable(reorderState, enabled = !showOnlyBookmarked && !isSelectionMode && !isPageOperationInProgress)
        ) {
            items(count = dragOrder.size, key = { dragOrder[it] }) { it ->
                val index = dragOrder[it]
                val thumbFlow = remember(index, thumbnailVersion) {
                    pdfViewModel.getPageThumbnail(index)
                }
                val thumb by thumbFlow.collectAsState()
                val strokesFlow = remember(index) { repos.strokes.getStrokesForPage(documentUri, index) }
                val strokes by strokesFlow.collectAsState(initial = emptyList())
                val imagesFlow = remember(index) { repos.images.getForPage(documentUri, index) }
                val images by imagesFlow.collectAsState(initial = emptyList())
                val textsFlow = remember(index) { repos.texts.getForPage(documentUri, index) }
                val texts by textsFlow.collectAsState(initial = emptyList())
                val canDrag = !showOnlyBookmarked && !isSelectionMode && !isPageOperationInProgress
                val currentListIndex = it
                Box(
                    modifier = Modifier
                        .let { mod -> if (reorderState.draggingItemIndex == currentListIndex) mod else mod.animateItem() }
                        .reorderableItem(reorderState, currentListIndex)
                        .then(
                            if (canDrag) {
                                Modifier.clickable {
                                    coroutineScope.launch {
                                        // 先把列表中心對齊再離開網格，否則舊碼會跳回舊頁。
                                        onPageSelected(index)
                                        onBack()
                                    }
                                }
                            } else {
                                Modifier.clickable {
                                    if (isSelectionMode) {
                                        if (index in selectedPages) selectedPages -= index
                                        else selectedPages += index
                                    } else {
                                        onPageSelected(index)
                                        onBack()
                                    }
                                }
                            }
                        )
                ) {
                    // 玻璃卡框 + 內嵌 8dp 白紙，跟文件庫卡片同語言
                    Box(
                        modifier = Modifier
                            .glassPanel(hazeState, isDarkTheme, ShapeLg)
                            .padding(8.dp)
                    ) {
                        Box {
                            PageThumbnail(
                                pageIndex = index,
                                bitmap = thumb,
                                strokes = strokes,
                                imageAnnotations = images,
                                textAnnotations = texts,
                                isSelected = isSelectionMode && index in selectedPages ||
                                    (!isSelectionMode && index == currentPageIndex),
                                isBookmarked = index in bookmarkedPages,
                                onBookmarkToggle = { newState ->
                                    pdfViewModel.toggleBookmark(documentUri, index, newState)
                                },
                                boxModifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(pdfViewModel.getPageAspectRatio(index))
                                    .let { if (isSelectionMode) it.padding(8.dp) else it },
                                modelWidth = modelWidth,
                                modelHeight = modelHeight
                            )
                            if (isSelectionMode) {
                                // 自繪勾選圓（取代 M3 Checkbox）
                                val checked = index in selectedPages
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(12.dp)
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (checked) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                                            CircleShape
                                        )
                                        .clickable {
                                            if (checked) selectedPages -= index
                                            else selectedPages += index
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (checked) {
                                        Icon(
                                            Icons.Outlined.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 由實際 layout 量出欄數。
 *
 * 同一列的 item 共用同一個 y offset，所以取最上面一列有幾個 item 即為欄數。
 * layout 還沒量好時回傳 0，呼叫端據此跳過捲動。
 */
private fun LazyGridState.measuredColumnCount(): Int {
    val visible = layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) return 0
    val topOffset = visible.minOf { it.offset.y }
    return visible.count { it.offset.y == topOffset }
}