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
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
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
import android.util.Log

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
import com.vic.inkflow.data.repository.InkFlowRepositories
import com.vic.inkflow.data.DocumentEntity
import com.vic.inkflow.data.FolderEntity
import com.vic.inkflow.data.ImageAnnotationEntity
import com.vic.inkflow.data.StrokeWithPoints
import com.vic.inkflow.data.TextAnnotationEntity
import com.vic.inkflow.ui.theme.InkFlowTheme


import com.vic.inkflow.ui.theme.Slate50
import com.vic.inkflow.ui.theme.Slate100
import com.vic.inkflow.ui.theme.Slate900
import com.vic.inkflow.ui.theme.WorkspaceDeskDark
import com.vic.inkflow.ui.theme.WorkspaceDeskLight
import com.vic.inkflow.util.PdfManager
import com.vic.inkflow.util.DocTransform
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
    sidebarStage: SidebarStage,
    onModeChange: (SidebarStage) -> Unit,
    pdfViewModel: PdfViewModel,
    pageCount: Int,
    currentPageIndex: Int,
    repos: InkFlowRepositories,
    documentUri: String,
    modelWidth: Float,
    modelHeight: Float,
    onPageSelected: (Int) -> Unit,
    onAddPage: (afterIndex: Int) -> Unit,
    onDeletePages: (List<Int>) -> Unit,
    // R2：頁面結構操作（拖拽移頁）超出復原範圍——呼叫方清棧，杜絕舊命令錯位。
    onStructureChanged: () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier,
    hazeState: dev.chrisbanes.haze.HazeState,
    isDarkTheme: Boolean,
    // 跟隨門衛：主列表推側欄時為 true，這時側欄推主列表必須讓路，不准回推
    isFollowingSidebar: Boolean = false
) {
    var deleteConfirmIndices by remember { mutableStateOf<List<Int>>(emptyList()) }
    val bookmarkedPages by pdfViewModel.getBookmarkedPages(documentUri).collectAsState(initial = emptyList())
    val thumbnailVersion by pdfViewModel.thumbnailVersion.collectAsState()
    val isPageOperationInProgress by pdfViewModel.isPageOperationInProgress.collectAsState()
    if (deleteConfirmIndices.isNotEmpty()) {
        val indices = deleteConfirmIndices
        GlassDialog(
            onDismissRequest = { deleteConfirmIndices = emptyList() },
            isDark = isDarkTheme,
            title = { Text("刪除頁面") },
            text = { Text(if (indices.size == 1) "確定要刪除第 ${indices[0] + 1} 頁？此操作無法復原。" else "確定要刪除這 ${indices.size} 頁？此操作無法復原。") },
            confirmText = "刪除",
            onConfirm = {
                deleteConfirmIndices = emptyList()
                onDeletePages(indices)
            },
            confirmEnabled = !isPageOperationInProgress,
            confirmColor = MaterialTheme.colorScheme.error
        )
    }

    // 整塊底全透明，跟工具列一樣只留零件玻璃
    Surface(
        modifier = modifier,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
            // Normal / Collapsed — thumbnail list + pinned add-page footer
            Column(Modifier.fillMaxSize()) {
                val coroutineScope = rememberCoroutineScope()

                androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val halfHeight = maxHeight / 2
                    // contentPadding ＝ 讓「第一頁」也能捲到 viewport 中央所需的空間。
                    // 必須用「實際 item 高度」算，不能猜：
                    //   展開＝PageThumbnail 寬 88dp ÷ 頁面比例 ＋ 上下 padding 16dp
                    //   收合＝PageIcon 48dp ＋ 上下 padding 16dp
                    // 舊碼在展開模式硬寫 70dp，但實際只有約 39dp（A4）→ 差 31dp，
                    // 首頁／末頁因此無法置中（看起來就是「頁碼跑到最下面」）。
                    // 置中本身已改由 [scrollToCenter]/[animateScrollToCenter] 用實際 layout 量，
                    // 這個 padding 現在只負責讓首末頁「有空間」捲到中央。
                    val pageAspect = pdfViewModel.getPageAspectRatio(0).coerceAtLeast(0.1f)
                    val thumbHalf = (88.dp / pageAspect + 16.dp) / 2f
                    val itemHalfHeight = if (sidebarStage == SidebarStage.PANEL) thumbHalf else 32.dp
                    val verticalPadding = (halfHeight - itemHalfHeight).coerceAtLeast(0.dp)

                    // 定錨玻璃丸：畫在列表下層，數字浮在玻璃上才看得清。
                    // 丸子釘死 viewport 中央不跟頁碼跑（snap 置中保證當前頁永遠停在這）；
                    // 頁碼撞進來的那一下：壓扁再彈簧回彈（帶過衝 wobble）+ 漣漪擴散，像東西砸進泡泡。
                    // 丸子常駐不藏：捲動時頁碼從它後面滑過去正是要看的效果，藏了反而像泡泡在閃。
                    if (sidebarStage != SidebarStage.PANEL) {
                        val density = LocalDensity.current
                        // 定值中央：跟 snap 置中同一點，不追蹤不脫鉤
                        val fixedY = with(density) { maxHeight.toPx() / 2f - 24.dp.toPx() }
                        val sqX = remember { Animatable(1f) }
                        val sqY = remember { Animatable(1f) }
                        val ripple = remember { Animatable(0f) }
                        var splashedIndex by remember { mutableStateOf(-1) }
                        // 經過偵測：誰的中心滑過丸子中心線（viewport 中央），就是誰撞進來。
                        // 直接讀 layoutInfo，不等頁碼提交、不等停穩——1 滑到 5 就抖五次。
                        val passingIndex by remember {
                            androidx.compose.runtime.derivedStateOf {
                                val info = listState.layoutInfo
                                if (info.visibleItemsInfo.isEmpty()) return@derivedStateOf null
                                val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
                                val nearest = info.visibleItemsInfo.minByOrNull { item ->
                                    kotlin.math.abs((item.offset + item.size / 2) - center)
                                } ?: return@derivedStateOf null
                                val dist = kotlin.math.abs((nearest.offset + nearest.size / 2) - center)
                                if (dist < nearest.size) nearest.index else null
                            }
                        }
                        androidx.compose.runtime.LaunchedEffect(passingIndex) {
                            val idx = passingIndex ?: return@LaunchedEffect
                            if (idx == splashedIndex) return@LaunchedEffect
                            splashedIndex = idx
                            // 雙相壓扁：先花 160ms 看得見地壓下去，再彈簧回彈帶過衝。
                            // 之前 snapTo 是瞬間到位，眼睛只剩彈回那段，看起來像沒壓過。
                            // 連續快速滑過時直接重啟（取消上一次），每一下都跟手。
                            launch {
                                sqX.animateTo(
                                    1.18f,
                                    tween(160, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                )
                                sqX.animateTo(
                                    1f,
                                    spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                            }
                            launch {
                                sqY.animateTo(
                                    0.82f,
                                    tween(160, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                )
                                sqY.animateTo(
                                    1f,
                                    spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                            }
                            launch {
                                // 漣漪不重啟：上一圈還沒散就讓它散完，不然環會疊成常駐外框卡住。
                                if (ripple.isRunning) return@launch
                                ripple.snapTo(0f)
                                ripple.animateTo(1f, tween(650))
                            }
                        }
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            // 漣漪環（下層）：從中心出發往外擴（0.3→1.7 倍），淡出
                            Box(
                                modifier = Modifier
                                    .offset { IntOffset(0, fixedY.roundToInt()) }
                                    .size(48.dp)
                                    .graphicsLayer {
                                        val r = ripple.value
                                        val s = 0.3f + r * 1.4f
                                        scaleX = s
                                        scaleY = s
                                        alpha = (1f - r) * 0.6f
                                    }
                                    .border(
                                        1.5.dp,
                                        Color.White.copy(alpha = 0.55f),
                                        CircleShape
                                    )
                            )
                            // 丸子本體（上層）：玻璃 + 碰撞壓扁回彈
                            Box(
                                modifier = Modifier
                                    .offset { IntOffset(0, fixedY.roundToInt()) }
                                    .size(48.dp)
                                    .graphicsLayer {
                                        scaleX = sqX.value
                                        scaleY = sqY.value
                                    }
                                    .glassPanel(hazeState, isDarkTheme, CircleShape)
                            )
                        }
                    }

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

                    // 2. 只有頁碼模式才隨滑動翻頁：預覽/全頁模式滑動只用來看，點了才翻。
                    // 只跟真手勢：interactionSource 只有手指拖才有 DragInteraction，
                    // 程式捲動（跟隨/點擊/吸附）沒有——從源頭斷迴圈。門衛旗當第二道。
                    var sidebarUserScrolling by remember { mutableStateOf(false) }
                    var lastSidebarDragEndMs by remember { mutableStateOf(0L) }
                    androidx.compose.runtime.LaunchedEffect(listState) {
                        listState.interactionSource.interactions.collect { interaction ->
                            when (interaction) {
                                is androidx.compose.foundation.interaction.DragInteraction.Start ->
                                    sidebarUserScrolling = true
                                is androidx.compose.foundation.interaction.DragInteraction.Stop,
                                is androidx.compose.foundation.interaction.DragInteraction.Cancel -> {
                                    sidebarUserScrolling = false
                                    lastSidebarDragEndMs = android.os.SystemClock.uptimeMillis()
                                }
                                else -> Unit
                            }
                        }
                    }
                    androidx.compose.runtime.LaunchedEffect(centerItemIndex, sidebarStage, isFollowingSidebar) {
                        if (sidebarStage != SidebarStage.RAIL) return@LaunchedEffect
                        if (isFollowingSidebar) return@LaunchedEffect
                        // 手指放開後 800ms 內的慣性也算數（不然甩過去不停頁）
                        val recentDrag = android.os.SystemClock.uptimeMillis() - lastSidebarDragEndMs < 800
                        if (!sidebarUserScrolling && !recentDrag) return@LaunchedEffect
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
                            repos.strokes.getStrokesForPage(documentUri, index)
                        }
                        val strokes by strokesFlow.collectAsState(initial = emptyList())
                        val imagesFlow = androidx.compose.runtime.remember(index) {
                            repos.images.getForPage(documentUri, index)
                        }
                        val images by imagesFlow.collectAsState(initial = emptyList())
                        val textsFlow = androidx.compose.runtime.remember(index) {
                            repos.texts.getForPage(documentUri, index)
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
                            if (sidebarStage == SidebarStage.PANEL) {
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
                // 收合態的玻璃脊：一條貫通的玻璃把展開鈕／頁碼丸／新增頁面收成同一件東西。
                //
                // 舊碼是三顆互不相連的玻璃藥丸浮在紙上，看起來像三個各自為政的按鈕。
                // 現在上下各留一段「脊」，中間的頁碼列表壓在脊上，
                // 空白處也能點＝不用精準命中小小的展開 chevron 就能展開。
                if (sidebarStage == SidebarStage.RAIL) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 上段：展開鈕
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .glassPanel(hazeState, isDarkTheme, ShapeMd),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .glassClickable(
                                        onClick = { onModeChange(SidebarStage.PANEL) },
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Outlined.ChevronRight,
                                    contentDescription = "展開側欄",
                                    modifier = Modifier.size(20.dp),
                                    tint = glassContentColor(isDarkTheme)
                                )
                            }
                        }
                        // 頁面操作進行中時顯示細長進度條，給予使用者視覺回饋
                        if (isPageOperationInProgress) {
                            androidx.compose.material3.LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(2.dp)
                            )
                        }
                        // 下段：新增頁面
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .glassPanel(hazeState, isDarkTheme, ShapeMd),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .glassClickable(
                                        onClick = { onAddPage(currentPageIndex) },
                                        shape = CircleShape,
                                        enabled = !isPageOperationInProgress
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Outlined.Add,
                                    contentDescription = "新增頁面",
                                    modifier = Modifier.size(22.dp),
                                    tint = glassContentColor(isDarkTheme)
                                )
                            }
                        }
                    }
                } else {
                    if (isPageOperationInProgress) {
                        androidx.compose.material3.LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(2.dp)
                        )
                    }
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        // 方角版新增頁面：玻璃底 + glassClickable。
                        // GlassTextButton 是固定圓角的，PANEL 這裡要 ShapeMd 方角才整齊。
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 5.dp)
                                .glassPanel(hazeState, isDarkTheme, ShapeMd)
                                .glassClickable(
                                    onClick = { onAddPage(currentPageIndex) },
                                    shape = ShapeMd,
                                    enabled = !isPageOperationInProgress
                                )
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.Add,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = glassContentColor(isDarkTheme)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("新增頁面", style = MaterialTheme.typography.labelMedium)
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
        // 選中框走共用外框（與工具列同一套珍珠色票）。縮圖本身是白紙，不能蓋實心底，
        // 所以只用框。1.08f 放大保留當第二重訊號。
        Box(
            modifier = boxModifier
                .graphicsLayer { scaleX = thumbScale; scaleY = thumbScale }
                .clip(ShapeMd)
                .background(paperColor, shape = ShapeMd)
                .then(
                    if (isSelected) Modifier.glassSelectionFrame(ShapeMd) else Modifier
                )
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
                val sx = DocTransform.scaleX(size.width, modelW)
                val sy = DocTransform.scaleY(size.height, modelH)
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
                            // Multiline：與 InkCanvas 同畫法，否則 \n 疊成一行
                            val lineHeight = with(paint.fontMetrics) { -ascent + descent + leading }
                            ann.text.split("\n").forEachIndexed { i, line ->
                                composeCanvas.nativeCanvas.drawText(
                                    line, ann.modelX * sx, ann.modelY * sy + i * lineHeight, paint
                                )
                            }
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
            // 頁碼徽：常駐左上，選中上色，未選中半透明罩（不再有實心底）
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else Color.White.copy(alpha = 0.35f),
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
    // 頁碼藥丸本體只留數字（透明），高亮由外面整顆玻璃丸滑過來。
    // 一顆常駐模糊勝過每格開關：不掉幀、不閃。
    Box(
        modifier = Modifier.size(48.dp),
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

/**
 * 側欄置中捲動（唯一實作，瞬時）。
 *
 * 不要用 animateScrollToItem + contentPadding 假設：那是靠猜 item 高度，
 * 猜錯就置中失敗（首末頁會偏）。這裡直接由實際 layout 量出差異再位移，一定準。
 *
 * 中心點要用 (viewportStart + viewportEnd) / 2，不是 (end - start) / 2。
 * LazyListLayoutInfo 的 viewportStartOffset = -beforeContentPadding，
 * 而我們為了讓首／末頁能捲到中央，contentPadding 給了幾乎半個視窗高。
 * 用「視窗高度 / 2」當目標會整體偏移一整個 contentPadding（實測可達半個視窗），
 * 結果就是整條頁碼看起來沒置中、正好對不到中央玻璃丸。
 * (start + end) / 2 才是可見區中心，也跟 Compose 自己的 [SnapPosition.Center] 一致。
 */
internal suspend fun LazyListState.scrollToCenter(index: Int) {
    // 陳舊頁碼（刪頁/空文件）直接捲會閃退，先擋
    if (index < 0 || index >= layoutInfo.totalItemsCount) return
    runCatching { scrollToItem(index) }.getOrNull() ?: return
    val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val delta = (itemInfo.offset + itemInfo.size / 2f -
        (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f)
    if (kotlin.math.abs(delta) < 0.5f) return
    scroll { scrollBy(delta) }
}

/** 側欄置中捲動（動畫版）。與 [scrollToCenter] 同一套演算法，只是換成動畫。 */
internal suspend fun LazyListState.animateScrollToCenter(index: Int) {
    // 使用者正在滑動時不要強制中斷
    if (isScrollInProgress) return
    if (index < 0 || index >= layoutInfo.totalItemsCount) return
    runCatching { scrollToItem(index) }.getOrNull() ?: return
    val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val delta = (itemInfo.offset + itemInfo.size / 2f -
        (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f)
    if (kotlin.math.abs(delta) < 0.5f) return
    runCatching { animateScrollBy(delta) }
}
