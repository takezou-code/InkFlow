package com.vic.inkflow.ui

import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import com.vic.inkflow.ui.theme.Motion
import com.vic.inkflow.ui.theme.ShapeSm
import com.vic.inkflow.ui.theme.ShapeMd
import com.vic.inkflow.ui.theme.ShapeLg
import com.vic.inkflow.ui.theme.ShapeXl
import com.vic.inkflow.util.reorderable
import com.vic.inkflow.util.reorderableItem

import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.BackHand
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vic.inkflow.R
import com.vic.inkflow.data.AppDatabase
import com.vic.inkflow.data.DocumentEntity
import com.vic.inkflow.data.FolderEntity
import com.vic.inkflow.data.ImageAnnotationEntity
import com.vic.inkflow.data.StrokeWithPoints
import com.vic.inkflow.data.TextAnnotationEntity
import com.vic.inkflow.ui.theme.InkFlowTheme


import com.vic.inkflow.ui.theme.Slate50
import com.vic.inkflow.ui.theme.Slate100
import com.vic.inkflow.ui.theme.Slate900
import com.vic.inkflow.ui.theme.ToolbarGlassDark
import com.vic.inkflow.ui.theme.ToolbarGlassLight
import com.vic.inkflow.ui.theme.WorkspaceDeskDark
import com.vic.inkflow.ui.theme.WorkspaceDeskLight
import com.vic.inkflow.util.PdfManager
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Sidebar(
    sidebarMode: SidebarMode,
    onModeChange: (SidebarMode) -> Unit,
    pdfViewModel: PdfViewModel,
    pageCount: Int,
    currentPageIndex: Int,
    db: AppDatabase,
    documentUri: String,
    modelWidth: Float,
    modelHeight: Float,
    onPageSelected: (Int) -> Unit,
    onAddPage: (afterIndex: Int) -> Unit,
    onDeletePages: (List<Int>) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier,
    hazeState: dev.chrisbanes.haze.HazeState,
    isDarkTheme: Boolean
) {
    var deleteConfirmIndices by remember { mutableStateOf<List<Int>>(emptyList()) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedPages by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var showOnlyBookmarked by remember { mutableStateOf(false) }
    val bookmarkedPages by pdfViewModel.getBookmarkedPages(documentUri).collectAsState(initial = emptyList())
    val visibleIndices = remember(pageCount, showOnlyBookmarked, bookmarkedPages, sidebarMode) {
        if (showOnlyBookmarked && sidebarMode == SidebarMode.FULLSCREEN) {
            (0 until pageCount).filter { it in bookmarkedPages }
        } else {
            (0 until pageCount).toList()
        }
    }
    val thumbnailVersion by pdfViewModel.thumbnailVersion.collectAsState()
    val isPageOperationInProgress by pdfViewModel.isPageOperationInProgress.collectAsState()
    if (deleteConfirmIndices.isNotEmpty()) {
        val indices = deleteConfirmIndices
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteConfirmIndices = emptyList() },
            title = { Text("刪除頁面") },
            text = { Text(if (indices.size == 1) "確定要刪除第 ${indices[0] + 1} 頁？此操作無法復原。" else "確定要刪除這 ${indices.size} 頁？此操作無法復原。") },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        deleteConfirmIndices = emptyList()
                        onDeletePages(indices)
                        isSelectionMode = false
                        selectedPages = emptySet()
                    },
                    enabled = !isPageOperationInProgress
                ) { Text("刪除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { deleteConfirmIndices = emptyList() }) {
                    Text("取消")
                }
            }
        )
    }

    // 整塊底全透明，跟工具列一樣只留零件玻璃
    Surface(
        modifier = modifier,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        if (sidebarMode == SidebarMode.FULLSCREEN) {
            // Fullscreen: 4-column page grid with back button
            val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
            val coroutineScope = rememberCoroutineScope()
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
                        pdfViewModel.movePage(documentUri, visibleIndices[from], visibleIndices[to])
                    }
                }
            )
            LaunchedEffect(Unit) {
                // Scroll so the current page is visible when the grid opens
                val row = currentPageIndex / 4
                gridState.scrollToItem(index = (row * 4).coerceAtLeast(0))
            }
            Column(Modifier.fillMaxSize()) {
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
                            androidx.compose.material3.TextButton(onClick = {
                                if (selectedPages.size == pageCount) selectedPages = emptySet() else selectedPages = (0 until pageCount).toSet()
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
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = "刪除選擇", tint = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            IconButton(onClick = { onModeChange(SidebarMode.NORMAL) }) {
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
                                    imageVector = if (showOnlyBookmarked) Icons.Outlined.Star else Icons.Outlined.BookmarkBorder,
                                    contentDescription = "只看書籤",
                                    tint = if (showOnlyBookmarked) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            IconButton(onClick = { isSelectionMode = true }) {
                                Icon(Icons.Outlined.Check, contentDescription = "多選頁面")
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
                    modifier = Modifier.fillMaxSize().reorderable(reorderState, enabled = !showOnlyBookmarked && !isSelectionMode && !isPageOperationInProgress)
                ) {
                    items(count = dragOrder.size, key = { dragOrder[it] }) { it ->
                        val index = dragOrder[it]
                        val thumbFlow = androidx.compose.runtime.remember(index, thumbnailVersion) {
                            pdfViewModel.getPageThumbnail(index)
                        }
                        val thumb by thumbFlow.collectAsState()
                        val strokesFlow = androidx.compose.runtime.remember(index) {
                            db.strokeDao().getStrokesForPage(documentUri, index)
                        }
                        val strokes by strokesFlow.collectAsState(initial = emptyList())
                        val imagesFlow = androidx.compose.runtime.remember(index) {
                            db.imageAnnotationDao().getForPage(documentUri, index)
                        }
                        val images by imagesFlow.collectAsState(initial = emptyList())
                        val textsFlow = androidx.compose.runtime.remember(index) {
                            db.textAnnotationDao().getForPage(documentUri, index)
                        }
                        val texts by textsFlow.collectAsState(initial = emptyList())
                        val canDrag = !showOnlyBookmarked && !isSelectionMode && !isPageOperationInProgress
                        val currentListIndex = it
                        Box(modifier = Modifier
                            .let { mod -> if (reorderState.draggingItemIndex == currentListIndex) mod else mod.animateItem() }
                            .reorderableItem(reorderState, currentListIndex)
                            .then(
                            if (canDrag) {
                                Modifier.clickable {
                                    coroutineScope.launch {
                                        // Keep list center in sync before leaving fullscreen,
                                        // otherwise stale center index may snap back.
                                        listState.scrollToCenter(index)
                                        onPageSelected(index)
                                        onModeChange(SidebarMode.NORMAL)
                                    }
                                }
                            } else {
                                Modifier.combinedClickable(
                                    onClick = {
                                        if (isSelectionMode) {
                                            if (index in selectedPages) selectedPages -= index else selectedPages += index
                                        } else {
                                            coroutineScope.launch {
                                                // Keep list center in sync before leaving fullscreen,
                                                // otherwise stale center index may snap back.
                                                listState.scrollToCenter(index)
                                                onPageSelected(index)
                                                onModeChange(SidebarMode.NORMAL)
                                            }
                                        }
                                    },
                                    onLongClick = { 
                                        if (!isSelectionMode && pageCount > 1 && !isPageOperationInProgress) {
                                            isSelectionMode = true
                                            selectedPages += index
                                        }
                                    }
                                )
                            }
                        )) {
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
                                    isSelected = isSelectionMode && index in selectedPages || (!isSelectionMode && index == currentPageIndex),
                                    isBookmarked = index in bookmarkedPages,
                                    onBookmarkToggle = { newState -> pdfViewModel.toggleBookmark(documentUri, index, newState) },
                                    boxModifier = Modifier.fillMaxWidth().aspectRatio(pdfViewModel.getPageAspectRatio(index))
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
                                                if (checked) selectedPages -= index else selectedPages += index
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
        } else {
            // Normal / Collapsed — thumbnail list + pinned add-page footer
            Column(Modifier.fillMaxSize()) {
                val coroutineScope = rememberCoroutineScope()

                androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val halfHeight = maxHeight / 2
                    // 當在收合模式 (30dp) 與展開模式 (70dp) 時，我們一律讓原點對齊到 item 高度的「中心」，以維持視覺的絕對置中。
                    val approximateItemHalfHeight = if (sidebarMode == SidebarMode.NORMAL) 70.dp else 40.dp
                    val verticalPadding = (halfHeight - approximateItemHalfHeight).coerceAtLeast(0.dp)

                    // 1. 即時計算中心項目
                    val centerItemIndex by androidx.compose.runtime.remember {
                        androidx.compose.runtime.derivedStateOf {
                            val layoutInfo = listState.layoutInfo
                            if (layoutInfo.visibleItemsInfo.isEmpty()) return@derivedStateOf null
                            val center = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
                            val centerItemInfo = layoutInfo.visibleItemsInfo.minByOrNull { item ->
                                kotlin.math.abs((item.offset + item.size / 2) - center)
                            }
                            centerItemInfo?.index
                        }
                    }

                    // 2. 只有頁碼模式才隨滑動翻頁：預覽/全頁模式滑動只用來看，點了才翻
                    androidx.compose.runtime.LaunchedEffect(centerItemIndex, sidebarMode) {
                        if (sidebarMode != SidebarMode.COLLAPSED) return@LaunchedEffect
                        centerItemIndex?.let { newIndex ->
                            if (newIndex != currentPageIndex) {
                                onPageSelected(newIndex)
                            }
                        }
                    }

                    // 3. 掛載原生的 SnapFlingBehavior
                    LazyColumn(
                        state = listState,
                        flingBehavior = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(
                            lazyListState = listState,
                            snapPosition = androidx.compose.foundation.gestures.snapping.SnapPosition.Center
                        ),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = verticalPadding),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        items(pageCount, key = { it }) { index ->
                        val thumbFlow = androidx.compose.runtime.remember(index, thumbnailVersion) {
                            pdfViewModel.getPageThumbnail(index)
                        }
                        val thumb by thumbFlow.collectAsState()
                        val strokesFlow = androidx.compose.runtime.remember(index) {
                            db.strokeDao().getStrokesForPage(documentUri, index)
                        }
                        val strokes by strokesFlow.collectAsState(initial = emptyList())
                        val imagesFlow = androidx.compose.runtime.remember(index) {
                            db.imageAnnotationDao().getForPage(documentUri, index)
                        }
                        val images by imagesFlow.collectAsState(initial = emptyList())
                        val textsFlow = androidx.compose.runtime.remember(index) {
                            db.textAnnotationDao().getForPage(documentUri, index)
                        }
                        val texts by textsFlow.collectAsState(initial = emptyList())
                        Box(
                            modifier = Modifier
                                .padding(vertical = 8.dp)
                                .combinedClickable(
                                    onClick = { coroutineScope.launch { listState.animateScrollToCenter(index) }; onPageSelected(index) },
                                    onLongClick = { if (pageCount > 1 && !isPageOperationInProgress) deleteConfirmIndices = listOf(index) }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (sidebarMode == SidebarMode.NORMAL) {
                                PageThumbnail(
                                    pageIndex = index,
                                    bitmap = thumb,
                                    strokes = strokes,
                                    imageAnnotations = images,
                                    textAnnotations = texts,
                                    isSelected = index == currentPageIndex,
                                    isBookmarked = index in bookmarkedPages,
                                    onBookmarkToggle = { newState -> pdfViewModel.toggleBookmark(documentUri, index, newState) },
                                    boxModifier = Modifier.width(88.dp).aspectRatio(pdfViewModel.getPageAspectRatio(index)),
                                    modelWidth = modelWidth,
                                    modelHeight = modelHeight
                                )
                            } else {
                                PageIcon(
                                    pageIndex = index,
                                    isSelected = index == currentPageIndex,
                                    hazeState = hazeState,
                                    isDarkTheme = isDarkTheme
                                )
                            }
                        }
                    }
                }
                }
                // 固定在底部的新增頁面按鈕（去分隔線，玻璃藥丸）
                // 頁面操作進行中時顯示細長進度條，給予使用者視覺回饋
                if (isPageOperationInProgress) {
                    androidx.compose.material3.LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(2.dp)
                    )
                }
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (sidebarMode == SidebarMode.NORMAL) {
                        androidx.compose.material3.TextButton(
                            onClick = { onAddPage(currentPageIndex) },
                            enabled = !isPageOperationInProgress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 5.dp)
                                .glassPanel(hazeState, isDarkTheme, ShapeMd)
                        ) {
                            Icon(
                                Icons.Outlined.Add,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("新增頁面", style = MaterialTheme.typography.labelMedium)
                        }
                    } else {
                        androidx.compose.material3.IconButton(
                            onClick = { onAddPage(currentPageIndex) },
                            enabled = !isPageOperationInProgress,
                            modifier = Modifier.padding(vertical = 5.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Add,
                                contentDescription = "新增頁面",
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PageThumbnail(
    pageIndex: Int,
    bitmap: android.graphics.Bitmap?,
    strokes: List<StrokeWithPoints>,
    imageAnnotations: List<ImageAnnotationEntity>,
    textAnnotations: List<TextAnnotationEntity>,
    isSelected: Boolean,
    isBookmarked: Boolean = false,
    onBookmarkToggle: ((Boolean) -> Unit)? = null,
    boxModifier: Modifier = Modifier.width(88.dp).aspectRatio(1f / 1.414f),
    modelWidth: Float = 595f,
    modelHeight: Float = 842f
) {
    val context = LocalContext.current
    val isDarkSurface = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // Paper base must be pure white to match rendered PDF bitmaps (eraseColor(WHITE)),
    // regardless of dark/light theme — prevents non-white patches while bitmaps load.
    val paperColor = Color.White
    // Cache decoded bitmaps keyed by URI string
    val loadedImages = remember { mutableStateMapOf<String, android.graphics.Bitmap?>() }
    LaunchedEffect(imageAnnotations) {
        imageAnnotations.forEach { ann ->
            if (ann.uri !in loadedImages) {
                loadedImages[ann.uri] = null
                withContext(Dispatchers.IO) {
                    try {
                        val bmp = context.contentResolver.openInputStream(Uri.parse(ann.uri))?.use { stream ->
                            BitmapFactory.decodeStream(stream)
                        }
                        loadedImages[ann.uri] = bmp
                    } catch (_: Exception) { }
                }
            }
        }
    }

    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 4.dp)) {
        val thumbScale by animateFloatAsState(
            targetValue = if (isSelected) 1.08f else 1f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
            label = "ThumbScale"
        )
        // Animated gradient border for selected page
        val outlineColor = MaterialTheme.colorScheme.outlineVariant
        val primaryColor = MaterialTheme.colorScheme.primary
        val secondaryColor = MaterialTheme.colorScheme.secondary
        val borderBrush = remember(isSelected, outlineColor, primaryColor, secondaryColor) {
            if (isSelected) Brush.linearGradient(listOf(primaryColor, secondaryColor))
            else Brush.linearGradient(listOf(outlineColor, outlineColor))
        }
        Box(
            modifier = boxModifier
                .graphicsLayer { scaleX = thumbScale; scaleY = thumbScale }
                .clip(ShapeMd)
                .background(paperColor, shape = ShapeMd)
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier
                        .size(20.dp)
                        .align(Alignment.Center),
                    strokeWidth = 2.dp
                )
            }
            // Unified ink + image + text overlay (direct hardware-accelerated draw without intermediate bitmap allocation)
            Spacer(modifier = Modifier.fillMaxSize().drawBehind {
                val modelW = modelWidth
                val modelH = modelHeight
                if (modelW <= 0f || modelH <= 0f) return@drawBehind
                val sx = size.width / modelW
                val sy = size.height / modelH
                // --- Strokes (freehand + shapes) ---
                strokes.forEach { swp ->
                    val stroke = swp.stroke
                    val strokeColor = Color(stroke.color)
                    val alpha = if (stroke.isHighlighter) 0.4f else 1f
                    val widthPx = stroke.strokeWidth * (if (stroke.isHighlighter) 3f else 1f) * sx
                    val paintStyle = Stroke(width = widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    if (stroke.shapeType != null) {
                        val r = Rect(
                            stroke.boundsLeft * sx, stroke.boundsTop * sy,
                            stroke.boundsRight * sx, stroke.boundsBottom * sy
                        )
                        when (stroke.shapeType) {
                            "RECT" -> drawRect(
                                color = strokeColor.copy(alpha = alpha),
                                topLeft = Offset(r.left, r.top),
                                size = Size(r.width, r.height),
                                style = paintStyle
                            )
                            "CIRCLE" -> drawOval(
                                color = strokeColor.copy(alpha = alpha),
                                topLeft = Offset(r.left, r.top),
                                size = Size(r.width, r.height),
                                style = paintStyle
                            )
                            "LINE" -> if (swp.points.size >= 2) {
                                drawLine(
                                    color = strokeColor.copy(alpha = alpha),
                                    start = Offset(swp.points.first().x * sx, swp.points.first().y * sy),
                                    end = Offset(swp.points.last().x * sx, swp.points.last().y * sy),
                                    strokeWidth = widthPx,
                                    cap = StrokeCap.Round
                                )
                            }
                            "ARROW" -> if (swp.points.size >= 2) {
                                val p0 = Offset(swp.points.first().x * sx, swp.points.first().y * sy)
                                val p1 = Offset(swp.points.last().x * sx, swp.points.last().y * sy)
                                drawLine(
                                    color = strokeColor.copy(alpha = alpha),
                                    start = p0, end = p1,
                                    strokeWidth = widthPx, cap = StrokeCap.Round
                                )
                                thumbnailDrawArrowHead(
                                    drawScope = this,
                                    color = strokeColor.copy(alpha = alpha),
                                    start = p0, end = p1, sw = widthPx
                                )
                            }
                        }
                    } else {
                        val pts = swp.points
                        if (pts.size >= 2) {
                            val path = androidx.compose.ui.graphics.Path()
                            path.moveTo(pts.first().x * sx, pts.first().y * sy)
                            for (i in 1 until pts.size) {
                                val p1 = pts[i - 1]; val p2 = pts[i]
                                path.quadraticTo(
                                    p1.x * sx, p1.y * sy,
                                    (p1.x + p2.x) / 2f * sx, (p1.y + p2.y) / 2f * sy
                                )
                            }
                            pts.lastOrNull()?.let { path.lineTo(it.x * sx, it.y * sy) }
                            drawPath(path, strokeColor.copy(alpha = alpha), style = paintStyle)
                        }
                    }
                }
                // --- Image annotations ---
                imageAnnotations.forEach { ann ->
                    val bmp = loadedImages[ann.uri]
                    if (bmp != null) {
                        drawImage(
                            image = bmp.asImageBitmap(),
                            dstOffset = IntOffset((ann.modelX * sx).toInt(), (ann.modelY * sy).toInt()),
                            dstSize = IntSize(
                                (ann.modelWidth * sx).toInt().coerceAtLeast(1),
                                (ann.modelHeight * sy).toInt().coerceAtLeast(1)
                            )
                        )
                    }
                }
                // --- Text annotations ---
                if (textAnnotations.isNotEmpty()) {
                    drawIntoCanvas { composeCanvas ->
                        textAnnotations.forEach { ann ->
                            val paint = android.graphics.Paint().apply {
                                textSize    = ann.fontSize * sy
                                color       = ann.colorArgb
                                isAntiAlias = true
                                typeface    = android.graphics.Typeface.DEFAULT_BOLD
                            }
                            composeCanvas.nativeCanvas.drawText(
                                ann.text, ann.modelX * sx, ann.modelY * sy, paint
                            )
                        }
                    }
                }
            })
            if (onBookmarkToggle != null) {
                androidx.compose.material3.IconButton(
                    onClick = { onBookmarkToggle(!isBookmarked) },
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Star,
                        contentDescription = "Toggle Bookmark",
                        tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            // 頁碼徽：常駐左上，選中上色，未選中半透明（去中央大框）
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                        CircleShape
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${pageIndex + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Draws an arrowhead at [end] pointing away from [start] in a thumbnail DrawScope. */
internal fun thumbnailDrawArrowHead(
    drawScope: androidx.compose.ui.graphics.drawscope.DrawScope,
    color: Color,
    start: Offset,
    end: Offset,
    sw: Float
) {
    val headSize   = sw * 5f + 6f
    val angle      = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
    val leftAngle  = angle + Math.PI * 0.75
    val rightAngle = angle - Math.PI * 0.75
    val lp = Offset(end.x + (headSize * cos(leftAngle)).toFloat(), end.y + (headSize * sin(leftAngle)).toFloat())
    val rp = Offset(end.x + (headSize * cos(rightAngle)).toFloat(), end.y + (headSize * sin(rightAngle)).toFloat())
    with(drawScope) {
        drawLine(color, end, lp, strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, end, rp, strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
internal fun PageIcon(
    pageIndex: Int,
    isSelected: Boolean,
    hazeState: dev.chrisbanes.haze.HazeState? = null,
    isDarkTheme: Boolean = false
) {
    // 玻璃頁碼藥丸：選中是實心玻璃丸，未選中全透明只留數字
    Box(
        modifier = Modifier
            .size(48.dp)
            .then(
                if (isSelected && hazeState != null) Modifier.glassPanel(hazeState, isDarkTheme, CircleShape)
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "${pageIndex + 1}",
            style = if (isSelected) MaterialTheme.typography.titleSmall
                else MaterialTheme.typography.labelMedium,
            color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        )
    }
}
