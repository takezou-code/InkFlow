package com.vic.inkflow.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import com.vic.inkflow.data.repository.InkFlowRepositories
import com.vic.inkflow.ui.theme.Motion
import com.vic.inkflow.ui.theme.ShapeLg
import kotlinx.coroutines.delay
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
    // 拖曳排序已整組拆除（2026-10-13）：手感越改越糟，最後根本拖不動。
    // 位置/頁號混用＋remember 閉包陳舊＋自捲座標系，三個問題疊在一起，
    // 補丁只會越補越亂。先回到無排序可用態，research 重做後再寫回去。
    // 這裡刻意只留 visibleIndices 直排，不留任何預覽順序中間態。
    val orderForGrid = visibleIndices

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
                    GridIconButton(
                        onClick = {
                            isSelectionMode = false
                            selectedPages = emptySet()
                        },
                        icon = Icons.Outlined.Close,
                        contentDescription = "取消多選"
                    )
                    Text(
                        text = "已選取 ${selectedPages.size} 頁",
                        style = MaterialTheme.typography.titleSmall,
                        color = glassContentColor(isDarkTheme),
                        modifier = Modifier.weight(1f)
                    )
                    GlassTextButton(
                        text = if (selectedPages.size == pageCount) "取消全選" else "全選",
                        onClick = {
                            if (selectedPages.size == pageCount) selectedPages = emptySet()
                            else selectedPages = (0 until pageCount).toSet()
                        }
                    )
                    GridIconButton(
                        onClick = {
                            if (selectedPages.isNotEmpty() && !isPageOperationInProgress) {
                                deleteConfirmIndices = selectedPages.toList()
                            }
                        },
                        enabled = selectedPages.isNotEmpty() && !isPageOperationInProgress,
                        icon = Icons.Outlined.DeleteOutline,
                        contentDescription = "刪除選擇",
                        tint = MaterialTheme.colorScheme.error
                    )
                } else {
                    GridIconButton(
                        onClick = onBack,
                        icon = Icons.Outlined.ArrowBack,
                        contentDescription = "回預覽條"
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        // 頂欄文字走玻璃內容色，不是 M3 原味 onSurface。
                        // 舊碼用 MaterialTheme.colorScheme，在深色模式下直接變成
                        // 深底＋黑字，整行看不見。
                        Text(
                            text = "所有頁面",
                            style = MaterialTheme.typography.titleSmall,
                            color = glassContentColor(isDarkTheme)
                        )
                        Text(
                            text = "${visibleIndices.size} 頁",
                            style = MaterialTheme.typography.labelSmall,
                            color = glassContentColor(isDarkTheme)
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
                            .glassClickable(
                                onClick = { showOnlyBookmarked = !showOnlyBookmarked },
                                shape = CircleShape
                            )
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
                    GridIconButton(
                        onClick = { isSelectionMode = true },
                        icon = Icons.Outlined.Checklist,
                        contentDescription = "多選頁面"
                    )
                    // 收合鈕：第 3 階必須有一條直接回 RAIL 的路。
                    // 舊碼只有返回鈕（回第 2 階），而 drag strip 在 GRID 不畫 → 單向門。
                    GridIconButton(
                        onClick = onCollapse,
                        icon = Icons.Outlined.ChevronLeft,
                        contentDescription = "收合側欄"
                    )
                }
            }
        }

        // 欄數：144.dp ＋ 間距 12.dp 在本機橫向（440dpi，可用寬約 1131.6dp）算出
        // ⌊(1131.6 + 12) / (144 + 12)⌋ = 7 欄，單張約 151.4dp。
        // 用 Adaptive 而非 Fixed(7)：視窗變窄時自動退成 6、5，不會把紙壓扁。
        // 全網格統一的頁面比例（A4 寬/高），並夾在合理範圍防止異常值把卡片壓扁。
    val gridAspectRatio = remember(pageCount) {
        pdfViewModel.getPageAspectRatio(0).coerceIn(0.6f, 0.85f)
    }

    // 錯落入場：每張卡錯開一點時間淡入＋收斂。
    // 只在「這次進場的第一個 layout」跑一次，並用上限截斷，
    // 否則頁數一多後面的卡片會等到使用者以為壞掉。
    // 全程只動 graphicsLayer（合成器層），不觸發 measure／重排。
    val staggerBudget = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(staggerBudget) {
        staggerBudget.snapTo(0f)
        staggerBudget.animateTo(1f, tween(Motion.DURATION_SLOW))
    }

    // 拖選自動捲：手指停在上下邊緣照樣捲（寫在 onDrag 裡就停了——手指不動不回調，
    // 官方博客點名的常見 bug）。速度由手勢按距邊距離給，這裡只負責轉。
    val dragAutoScrollSpeed = remember { mutableStateOf(0f) }
    LaunchedEffect(dragAutoScrollSpeed.value) {
        if (dragAutoScrollSpeed.value != 0f) {
            while (true) {
                // 同排序：封頂 20px/步，手指碰邊不飛屏。
                gridState.scrollBy(dragAutoScrollSpeed.value.coerceIn(-20f, 20f))
                delay(10)
            }
        }
    }
    // pointerInput 只 key gridState（不重啟）：集合經 ref 讀新鮮值，否則拖到一半
    // 讀到舊快照，範圍算出來是錯的。
    val dragOrderRef = rememberUpdatedState(orderForGrid)
    val selectedPagesRef = rememberUpdatedState(selectedPages)
    val setSelectedRef = rememberUpdatedState({ s: Set<Int> -> selectedPages = s })
    val haptics = LocalHapticFeedback.current
    val autoScrollThresholdPx = with(LocalDensity.current) { 40.dp.toPx() }

    LazyVerticalGrid(
            columns = GridCells.Adaptive(144.dp),
            state = gridState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 64.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                // 選擇模式拖選（Google 相簿／iOS 相簿同款）：點住一頁直接拖，
                // 拖過的全進集合，回拖縮範圍，從已選起拖是反選。快滑不受影響
                //（長按超時前鬆手／移動＝捲動，detectDragGesturesAfterLongPress 仲裁）。
                .then(
                    if (isSelectionMode) {
                        Modifier.pageGridDragSelect(
                            gridState = gridState,
                            dragOrder = dragOrderRef,
                            selectedPages = selectedPagesRef,
                            onSelectionChange = setSelectedRef,
                            haptics = haptics,
                            autoScrollSpeed = dragAutoScrollSpeed,
                            autoScrollThresholdPx = autoScrollThresholdPx
                        )
                    } else Modifier
                )
        ) {
            items(count = orderForGrid.size, key = { orderForGrid[it] }) { it ->
                val index = orderForGrid[it]
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
                val currentListIndex = it
                val isCurrent = !isSelectionMode && index == currentPageIndex
                // 選中放大必須套在「整張卡」（玻璃框＋白紙）上。
                // 舊碼只放大內層白紙 → 白紙溢出框外，看起來像破版。
                // 彈簧參數沿用 PageThumbnail 原本那組，手感才一致。
                val cardScale by animateFloatAsState(
                    targetValue = if (isCurrent) 1.06f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "GridCardScale"
                )
                Box(
                    modifier = Modifier
                        .animateItem()
                        // 錯落入場：前 24 張依 index 延遲，之後的同時淡入（不排隊）。
                        // 入場結束後才把選中放大乘上去，否則放大會被入場的 0.92 一起縮掉。
                        .graphicsLayer {
                            val cap = 24f
                            val delay = (currentListIndex.toFloat().coerceAtMost(cap)) / cap
                            val t = ((staggerBudget.value - delay * 0.45f) / 0.55f).coerceIn(0f, 1f)
                            val enter = 0.92f + 0.08f * t
                            val s = enter * cardScale
                            alpha = t
                            scaleX = s
                            scaleY = s
                        }
                        .clickable {
                            if (isSelectionMode) {
                                if (index in selectedPages) selectedPages -= index
                                else selectedPages += index
                            } else {
                                coroutineScope.launch {
                                    // 先把列表中心對齊再離開網格，否則舊碼會跳回舊頁。
                                    onPageSelected(index)
                                    onBack()
                                }
                            }
                        }
                ) {
                    // 玻璃卡框 + 內嵌 8dp 白紙，跟文件庫卡片同語言。
                    //
                    // 這裡用 fauxGlassPanel（假玻璃）而不是 glassPanel：
                    // 7 欄之下可見卡片從約 8–12 張變成約 24 張，每張都開真
                    // backdrop blur 就是 24 層同時採樣+模糊，幀率會垮。
                    // ui.md 的決策表本來就寫了 fauxGlassPanel 是給「大量重複卡片」用的。
                    // 視覺差異只在 rim/sheen 的真實折射，卡片框看不出來。
                    // 真模糊留給上面那條頂欄。
                    Box(
                        modifier = Modifier
                            .fauxGlassPanel(isDarkTheme, ShapeLg)
                            .padding(6.dp)
                    ) {
                        Box {
                            PageThumbnail(
                                pageIndex = index,
                                bitmap = thumb,
                                strokes = strokes,
                                imageAnnotations = images,
                                textAnnotations = texts,
                                isSelected = index in selectedPages ||
                                    (!isSelectionMode && index == currentPageIndex),
                                isBookmarked = index in bookmarkedPages,
                                onBookmarkToggle = { newState ->
                                    pdfViewModel.toggleBookmark(documentUri, index, newState)
                                },
                                boxModifier = Modifier
                                    .fillMaxWidth()
                                    // 全網格統一用同一個頁面比例。
                                    //
                                    // 舊碼用 pdfViewModel.getPageAspectRatio(index) 各頁自己的比例，
                                    // 兩個後果（實測畫面確認）：
                                    //  1. 某頁拿到壞比例（例如寬得離譜）→ 卡片高度塌成一條。
                                    //  2. 比例不一致 → 灰邊厚薄不均、整個網格排列參差，很難看。
                                    // 頁面管理要的是「每頁一樣大、排整齊」，所以用統一的 A4 比例，
                                    // 實際頁面差異由白紙內部的 bitmap 自己留白處理。
                                    .aspectRatio(gridAspectRatio),
                                modelWidth = modelWidth,
                                modelHeight = modelHeight,
                                // 放大由整張玻璃卡負責（見上方 graphicsLayer），
                                // 這裡不能再乘一次，否則變 1.08²。
                                applySelectionScale = false
                            )
                            // 選擇態只剩頁碼徽實心化（PageThumbnail 內部）：勾選圓已刪，
                            // 整張卡點哪都一樣是 toggle，不再留第二個點位。
                        }
                    }
                }
            }
        }
    }
}

/**
 * 網格頂欄的圖示按鈕。
 *
 * 不用 M3 `IconButton`（ui.md 禁原味上屏）。`glassClickable` 已經包好
 * 掃光＋縮放＋無漣漪，所以這裡只負責排版與圖示。
 */
@Composable
private fun GridIconButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    tint: Color? = null
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .glassClickable(onClick = onClick, shape = CircleShape, enabled = enabled),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint ?: glassContentColor(
                MaterialTheme.colorScheme.background.luminance() < 0.5f
            )
        )
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

/**
 * 選擇模式拖選（Google 相簿／iOS 相簿手感，照抄）：
 * 點住一頁直接拖，拖過的全進集合；回拖縮範圍；從已選起拖＝反選；
 * 手指到上下邊緣自動捲（越近越快）；起點給一次長按震動。
 *
 * 位置用「列表位」算範圍、再經 dragOrder 映射到頁號——書籤過濾時位不連號，
 * 直接對頁號取範圍會把藏起來的頁也選進去。
 */
private fun Modifier.pageGridDragSelect(
    gridState: LazyGridState,
    dragOrder: State<List<Int>>,
    selectedPages: State<Set<Int>>,
    onSelectionChange: State<(Set<Int>) -> Unit>,
    haptics: HapticFeedback,
    autoScrollSpeed: androidx.compose.runtime.MutableState<Float>,
    autoScrollThresholdPx: Float
): Modifier = pointerInput(gridState, dragOrder, selectedPages, onSelectionChange, haptics) {
    fun positionAt(hit: Offset): Int? =
        gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
            IntRect(info.offset, info.size).contains(
                androidx.compose.ui.unit.IntOffset(hit.x.toInt(), hit.y.toInt())
            )
        }?.index

    var anchorPos: Int? = null
    var currentPos: Int? = null
    var subtract = false
    // 本筆手勢已經動過的頁：縮範圍時要先退掉，不然只增不減。
    var applied: Set<Int> = emptySet()

    fun applyRange(toPos: Int) {
        val anchor = anchorPos ?: return
        val order = dragOrder.value
        val range = if (anchor <= toPos) anchor..toPos else toPos..anchor
        val pages = range.mapNotNull { order.getOrNull(it) }.toSet()
        var next = selectedPages.value - applied
        next = if (subtract) next - pages else next + pages
        applied = pages
        currentPos = toPos
        onSelectionChange.value(next)
    }

    detectDragGesturesAfterLongPress(
        onDragStart = { offset ->
            val pos = positionAt(offset) ?: return@detectDragGesturesAfterLongPress
            val page = dragOrder.value.getOrNull(pos) ?: return@detectDragGesturesAfterLongPress
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            anchorPos = pos
            subtract = page in selectedPages.value
            applyRange(pos)
        },
        onDragCancel = {
            anchorPos = null
            autoScrollSpeed.value = 0f
        },
        onDragEnd = {
            anchorPos = null
            autoScrollSpeed.value = 0f
        },
        onDrag = { change, _ ->
            if (anchorPos == null) return@detectDragGesturesAfterLongPress
            val distFromBottom = gridState.layoutInfo.viewportSize.height - change.position.y
            val distFromTop = change.position.y
            autoScrollSpeed.value = when {
                distFromBottom < autoScrollThresholdPx -> autoScrollThresholdPx - distFromBottom
                distFromTop < autoScrollThresholdPx -> distFromTop - autoScrollThresholdPx
                else -> 0f
            }
            positionAt(change.position)?.let { pos ->
                if (pos != currentPos) applyRange(pos)
            }
        }
    )
}