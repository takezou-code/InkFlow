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
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.runtime.withFrameNanos
import com.vic.inkflow.util.TwoFingerArbitrator
import com.vic.inkflow.util.TwoFingerDecision
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
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.snapshotFlow
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

@Composable
internal fun Workspace(
    pageIndex: Int,
    pdfViewModel: PdfViewModel,
    viewModel: EditorViewModel,
    modifier: Modifier = Modifier,
    pageAspectRatio: Float = 1f / 1.414f,
    documentUri: String,
    onAiFileReady: (android.net.Uri) -> Unit,
    hazeState: dev.chrisbanes.haze.HazeState = rememberHazeState(),
    isDarkTheme: Boolean = false,
    db: AppDatabase,
    mainListState: LazyListState = rememberLazyListState(),
    onRequestPage: (Int) -> Unit = {},
    onScrollPage: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 直向連續卷動：一頁接一頁，不再做縮放平移（觸控直走卷動、觸控筆走繪圖）
    val isDarkSurface = isDarkTheme || MaterialTheme.colorScheme.background.luminance() < 0.5f
    // 桌面全透明：底由 EditorScreen 根 Aurora 提供，紙直接浮在光斑上
    val paperColor = MaterialTheme.colorScheme.surface
    val activeTool by viewModel.selectedTool.collectAsState()
    val lassoPolygon by viewModel.lassoPolygon.collectAsState()
    val lastLassoPolygon by viewModel.lastLassoPolygon.collectAsState()
    val selectedStrokes by viewModel.selectedStrokes.collectAsState()
    val selectedImageAnnotationIds by viewModel.selectedImageAnnotationIds.collectAsState()
    val hasEditableSelection = selectedStrokes.isNotEmpty() || selectedImageAnnotationIds.isNotEmpty()
    val hasPdfBaseSelection = lassoPolygon.isNotEmpty()
    val hasSelection = hasEditableSelection || hasPdfBaseSelection
    val activeRegionPolygon = if (lassoPolygon.isNotEmpty()) lassoPolygon else lastLassoPolygon
    val hasRegionSnapshot = activeRegionPolygon.isNotEmpty()
    var isExtracting by remember { mutableStateOf(false) }

    var bubbleWidthPx by remember { mutableIntStateOf(0) }
    var bubbleHeightPx by remember { mutableIntStateOf(0) }

    // 文件級縮放：整份文件同一個 docZoom（Chrome 式），ViewModel 持有、跨頁保持；
    // pinchActive 供 InkCanvas 棄筆判定
    val docZoom by viewModel.docZoom.collectAsState()
    val hScrollState = rememberScrollState()
    var viewportWpx by remember { mutableIntStateOf(0) }

    val density = LocalDensity.current
    val bubbleGapPx = with(density) { 12.dp.toPx() }
    val bubbleSidePaddingPx = with(density) { 12.dp.toPx() }
    val bubbleTopSafePx = with(density) { 12.dp.toPx() }

    // 雙指仲裁手勢：PAN 交給原生卷動，PINCH 寫 shared docZoom + 卷動錨定
    val pinchModifier = Modifier.pointerInput(Unit) {
        val arbitrator = TwoFingerArbitrator(touchSlopPx = viewConfiguration.touchSlop)
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var lastIds: Set<PointerId> = emptySet()
            var wasPinching = false
            while (true) {
                val event = awaitPointerEvent()
                val pressedAll = event.changes.filter { it.pressed }
                if (pressedAll.isEmpty()) {
                    arbitrator.reset()
                    if (wasPinching) {
                        viewModel.setPinchActive(false)
                        wasPinching = false
                    }
                    break
                }
                // 只算觸控手指：觸控筆書寫時不參與
                val touch = pressedAll.filter { it.type == PointerType.Touch }
                if (touch.size >= 2) {
                    val ids = touch.map { it.id }.toSet()
                    val cx = touch.sumOf { it.position.x.toDouble() }.toFloat() / touch.size
                    val cy = touch.sumOf { it.position.y.toDouble() }.toFloat() / touch.size
                    val span = (touch[0].position - touch[1].position).getDistance()
                    if (ids != lastIds) {
                        arbitrator.rebaseline(cx, cy, span)
                        lastIds = ids
                    }
                    when (val decision = arbitrator.onFrame(cx, cy, span)) {
                        is TwoFingerDecision.Pinch -> {
                            event.changes.forEach { it.consume() }
                            if (!wasPinching) {
                                viewModel.setPinchActive(true)
                                wasPinching = true
                            }
                            if (decision.zoomFactor.isFinite() && !decision.zoomFactor.isNaN() && decision.zoomFactor > 0f && decision.zoomFactor != 1f) {
                                val old = docZoom
                                val new = (old * decision.zoomFactor).coerceIn(0.4f, 4f)
                                if (new.isFinite() && !new.isNaN() && new != old) {
                                    val ratio = new / old
                                    val currentScrollX = hScrollState.value
                                    val maxScrollX = maxOf(0f, viewportWpx * (new - 1f))
                                    val targetScrollX = ((currentScrollX + cx) * ratio - cx).coerceIn(0f, maxScrollX)
                                    val deltaX = targetScrollX - currentScrollX
                                    viewModel.setDocZoom(new)
                                    if (kotlin.math.abs(deltaX) > 0.5f) {
                                        hScrollState.dispatchRawDelta(deltaX)
                                    }
                                }
                            }
                        }
                        else -> Unit // PAN / 未定：原生卷動接手，不 consume
                    }
                } else {
                    arbitrator.reset()
                    lastIds = emptySet()
                    if (wasPinching) {
                        viewModel.setPinchActive(false)
                        wasPinching = false
                    }
                }
            }
        }
    }

    val regionBoundsModel = remember(activeRegionPolygon) { polygonBounds(activeRegionPolygon) }
    val showSelectionBubble = activeTool == Tool.LASSO && hasSelection && !isExtracting

    // pdfViewModel and LaunchedEffect(uri) are owned by TabletEditorScreen
    val pageCount by pdfViewModel.pageCount.collectAsState()
    // 全文件統一直徑：所有頁同一個 aspect（model 為準），上下頁不可能大小不一；
    // 墨水映射也因此是 1:1 精確（不再被混尺寸拉伸）
    val modelW0 = viewModel.modelWidth
    val modelH0 = viewModel.modelHeight
    val uniformAspect = remember(modelW0, modelH0, pageAspectRatio) {
        val a = if (modelW0 > 0f && modelH0 > 0f) modelW0 / modelH0 else pageAspectRatio
        // 非有限值會讓 aspectRatio 罷工甚至閃退，直接兜底 A4 直式
        if (a.isFinite() && a in 0.2f..5f) a else 1f / 1.414f
    }

    // 卷動跟著走：主列表滑到哪頁，作用頁就換到哪頁（側欄由 EditorScreen 跟著置中）
    LaunchedEffect(mainListState, pageCount) {
        snapshotFlow { mainListState.firstVisibleItemIndex }
            .collect { idx ->
                if (idx in 0 until pageCount) onScrollPage(idx)
            }
    }

    // 渲染刻度跟著可視寬：可視越寬渲染倍率越高（2x–3.5x），旋轉/轉向自動重渲
    val renderEpoch by pdfViewModel.renderEpoch.collectAsState()
    val firstSize by pdfViewModel.firstPageSize.collectAsState()
    LaunchedEffect(viewportWpx, firstSize) {
        val w = firstSize?.first ?: 595f
        if (viewportWpx > 0 && w > 0f) {
            pdfViewModel.setDisplayRenderScale(viewportWpx.toFloat() / w)
        }
    }

    // 直向連續卷動：一頁接一頁；外層橫向卷軸承載文件級縮放（整份同縮，Chrome 式）
    Box(
        modifier = modifier
            .background(Color.Transparent)
            .onSizeChanged { viewportWpx = it.width }
    ) {
        // 列表恒满视口宽（无死角）；纸在 item 内按比例缩、居中
        val listWdp = with(density) { (viewportWpx.toFloat() * maxOf(docZoom, 1f)).toDp() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(hScrollState, enabled = docZoom > 1f),
            contentAlignment = Alignment.Center
        ) {
            LazyColumn(
                state = mainListState,
                modifier = Modifier
                    .width(listWdp.coerceAtLeast(1.dp))
                    .fillMaxHeight(),
                contentPadding = PaddingValues(vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
        items(pageCount, key = { it }) { index ->
            val aspect = uniformAspect
            if (index == pageIndex) {
                val bitmapFlow = remember(index, renderEpoch) { pdfViewModel.getPageBitmap(index) }
                val pageBitmap by bitmapFlow.collectAsState()
                var itemWidthPx by remember { mutableIntStateOf(0) }
                val itemTargetOffset = remember(
                    regionBoundsModel, itemWidthPx, bubbleWidthPx, bubbleHeightPx,
                    aspect, bubbleGapPx, bubbleSidePaddingPx, bubbleTopSafePx
                ) {
                    if (regionBoundsModel == null || itemWidthPx <= 0) {
                        IntOffset(0, 0)
                    } else {
                        val modelW = viewModel.modelWidth
                        val modelH = viewModel.modelHeight
                        if (modelW <= 0f || modelH <= 0f) {
                            IntOffset(0, 0)
                        } else {
                            val wF = itemWidthPx.toFloat()
                            val hF = wF / aspect
                            val sx = wF / modelW
                            val sy = hF / modelH
                            val left = regionBoundsModel.left * sx
                            val top = regionBoundsModel.top * sy
                            val right = regionBoundsModel.right * sx
                            val bottom = regionBoundsModel.bottom * sy
                            val cx = (left + right) / 2f
                            val bw = bubbleWidthPx.toFloat()
                            val bh = bubbleHeightPx.toFloat()
                            val aboveY = top - bh - bubbleGapPx
                            val y = if (aboveY < bubbleTopSafePx) bottom + bubbleGapPx else aboveY
                            val maxX = maxOf(bubbleSidePaddingPx, wF - bw - bubbleSidePaddingPx)
                            val maxY = maxOf(bubbleTopSafePx, hF - bh - bubbleTopSafePx)
                            IntOffset(
                                ((cx - bw / 2f).coerceIn(bubbleSidePaddingPx, maxX)).roundToInt(),
                                y.coerceIn(bubbleTopSafePx, maxY).roundToInt()
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(pinchModifier),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth(docZoom.coerceAtMost(1f))
                            .padding(horizontal = 20.dp)
                            .aspectRatio(aspect)
                            .onSizeChanged { itemWidthPx = it.width },
                        shape = ShapeSm,
                        shadowElevation = 18.dp,
                        color = paperColor
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
            // PDF static layer (bottom) — crossfade between page bitmaps
            Crossfade(
                targetState = pageBitmap,
                animationSpec = tween(200),
                label = "PageBitmapFade"
            ) { bitmap ->
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "PDF Page $pageIndex",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                    )
                }
            }
            // Background pattern layer (ruled / grid lines)
            val paperStyle by viewModel.paperStyle.collectAsState()
            if (paperStyle.background != PageBackground.BLANK) {
                val lineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    val modelW = viewModel.modelWidth
                    val modelH = viewModel.modelHeight
                    val sx = size.width / modelW
                    val sy = size.height / modelH
                    val step = when (paperStyle.background) {
                        PageBackground.NARROW_RULED -> 18f  // ~6.35mm
                        PageBackground.WIDE_RULED   -> 42f  // ~15mm
                        else                        -> 28f  // ~1cm
                    }
                    val drawHLines = paperStyle.background == PageBackground.RULED ||
                        paperStyle.background == PageBackground.NARROW_RULED ||
                        paperStyle.background == PageBackground.WIDE_RULED ||
                        paperStyle.background == PageBackground.GRID
                    if (drawHLines) {
                        var y = step
                        while (y < modelH) {
                            drawLine(
                                color = lineColor,
                                start = androidx.compose.ui.geometry.Offset(0f, y * sy),
                                end = androidx.compose.ui.geometry.Offset(size.width, y * sy),
                                strokeWidth = 1f
                            )
                            y += step
                        }
                    }
                    if (paperStyle.background == PageBackground.GRID) {
                        var x = step
                        while (x < modelW) {
                            drawLine(
                                color = lineColor,
                                start = androidx.compose.ui.geometry.Offset(x * sx, 0f),
                                end = androidx.compose.ui.geometry.Offset(x * sx, size.height),
                                strokeWidth = 1f
                            )
                            x += step
                        }
                    }
                    if (paperStyle.background == PageBackground.DOT_GRID) {
                        var y = step
                        while (y < modelH) {
                            var x = step
                            while (x < modelW) {
                                drawCircle(
                                    color = lineColor,
                                    radius = 1.5f,
                                    center = androidx.compose.ui.geometry.Offset(x * sx, y * sy)
                                )
                                x += step
                            }
                            y += step
                        }
                    }
                }
            }
            // Ink active layer (top)
            InkCanvas(
                modifier = Modifier.fillMaxSize(),
                viewModel = viewModel,
                pdfViewModel = pdfViewModel,
                documentUri = documentUri
            )
                        } // 作用頁內容 Box
                    } // 紙 Surface
                    // 套索氣泡：作用頁內定位
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showSelectionBubble && docZoom == 1f,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset { itemTargetOffset },
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
            Surface(
                modifier = Modifier
                    .glassPanel(hazeState, isDarkSurface, shape = CircleShape, specular = false)
                    .onSizeChanged {
                        bubbleWidthPx = it.width
                        bubbleHeightPx = it.height
                    },
                shape = CircleShape,
                color = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (hasEditableSelection) "已選取物件" else "已選取 PDF 區域",
                        style = MaterialTheme.typography.labelLarge
                    )
                    TextButton(
                        enabled = !isExtracting && hasSelection && hasRegionSnapshot,
                        onClick = {
                            if (isExtracting || !hasSelection || !hasRegionSnapshot) return@TextButton
                            isExtracting = true
                            scope.launch {
                                try {
                                    val sourcePageIndex = pageIndex
                                    val sourceBitmap = kotlinx.coroutines.withTimeoutOrNull(1200) {
                                        pdfViewModel.getPageBitmap(sourcePageIndex)
                                            .filterNotNull()
                                            .first()
                                    } ?: pdfViewModel.getPageBitmap(sourcePageIndex).value

                                    val newPageIndex = sourcePageIndex + 1
                                    pdfViewModel.insertBlankPage(
                                        context, documentUri, sourcePageIndex,
                                        pageWidthPt = viewModel.modelWidth,
                                        pageHeightPt = viewModel.modelHeight
                                    )

                                    viewModel.extractRegionToNewPage(
                                        context = context,
                                        sourcePageIndex = sourcePageIndex,
                                        targetPageIndex = newPageIndex,
                                        pdfPageBitmap = sourceBitmap
                                    )
                                } finally {
                                    isExtracting = false
                                }
                            }
                        }
                    ) {
                        Text("提取到新頁面")
                    }
                    TextButton(
                        enabled = !isExtracting && hasSelection && hasRegionSnapshot,
                        onClick = {
                            if (isExtracting || !hasSelection || !hasRegionSnapshot) return@TextButton
                            isExtracting = true
                            scope.launch {
                                try {
                                    val sourcePageIndex = pageIndex
                                    val sourceBitmap = kotlinx.coroutines.withTimeoutOrNull(1200) {
                                        pdfViewModel.getPageBitmap(sourcePageIndex).filterNotNull().first()
                                    } ?: pdfViewModel.getPageBitmap(sourcePageIndex).value

                                    val file = viewModel.extractRegionToShareFile(
                                        context = context,
                                        sourcePageIndex = sourcePageIndex,
                                        pdfPageBitmap = sourceBitmap
                                    )
                                    if (file != null) {
                                        val fileUri = androidx.core.content.FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            file
                                        )
                                        onAiFileReady(fileUri)
                                    }
                                } finally {
                                    isExtracting = false
                                }
                            }
                        }
                    ) {
                        Text("AI 解析")
                    }
                    TextButton(
                        enabled = hasEditableSelection,
                        onClick = {
                            if (!hasEditableSelection) return@TextButton
                            viewModel.copySelectionInPlace()
                        }
                    ) {
                        Text("複製")
                    }
                    TextButton(
                        enabled = hasEditableSelection,
                        onClick = {
                            if (!hasEditableSelection) return@TextButton
                            viewModel.deleteSelection()
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("刪除")
                    }
                    IconButton(
                        onClick = { viewModel.clearSelection() },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "取消選取",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
                    }
                    } // 作用頁 item Box
                } else {
                    // ===== 靜態頁：點了變作用頁，尺寸樣式與作用頁完全一致 =====
                    val staticBitmapFlow = remember(index, renderEpoch) { pdfViewModel.getPageBitmap(index) }
                    val staticBitmap by staticBitmapFlow.collectAsState()
                    val staticStrokes by remember(index, documentUri) {
                        db.strokeDao().getStrokesForPage(documentUri, index)
                    }.collectAsState(initial = emptyList())
                    val staticImages by remember(index, documentUri) {
                        db.imageAnnotationDao().getForPage(documentUri, index)
                    }.collectAsState(initial = emptyList())
                    val staticTexts by remember(index, documentUri) {
                        db.textAnnotationDao().getForPage(documentUri, index)
                    }.collectAsState(initial = emptyList())
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(pinchModifier),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth(docZoom.coerceAtMost(1f))
                                .padding(horizontal = 20.dp)
                                .aspectRatio(aspect)
                                .clip(ShapeSm)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onRequestPage(index) },
                            shape = ShapeSm,
                            shadowElevation = 18.dp,
                            color = paperColor
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                val currentBmp = staticBitmap
                                if (currentBmp != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = currentBmp.asImageBitmap(),
                                        contentDescription = "PDF Page $index",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                                    )
                                } else {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        androidx.compose.material3.CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                }
                                val paperStyle by viewModel.paperStyle.collectAsState()
                                if (paperStyle.background != PageBackground.BLANK) {
                                    val lineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                                        val modelW = viewModel.modelWidth
                                        val modelH = viewModel.modelHeight
                                        if (modelW > 0f && modelH > 0f) {
                                            val sx = size.width / modelW
                                            val sy = size.height / modelH
                                            val step = when (paperStyle.background) {
                                                PageBackground.NARROW_RULED -> 18f
                                                PageBackground.WIDE_RULED   -> 42f
                                                else                        -> 28f
                                            }
                                            val drawHLines = paperStyle.background == PageBackground.RULED ||
                                                paperStyle.background == PageBackground.NARROW_RULED ||
                                                paperStyle.background == PageBackground.WIDE_RULED ||
                                                paperStyle.background == PageBackground.GRID
                                            if (drawHLines) {
                                                var y = step
                                                while (y < modelH) {
                                                    drawLine(
                                                        color = lineColor,
                                                        start = androidx.compose.ui.geometry.Offset(0f, y * sy),
                                                        end = androidx.compose.ui.geometry.Offset(size.width, y * sy),
                                                        strokeWidth = 1f
                                                    )
                                                    y += step
                                                }
                                            }
                                            if (paperStyle.background == PageBackground.GRID) {
                                                var x = step
                                                while (x < modelW) {
                                                    drawLine(
                                                        color = lineColor,
                                                        start = androidx.compose.ui.geometry.Offset(x * sx, 0f),
                                                        end = androidx.compose.ui.geometry.Offset(x * sx, size.height),
                                                        strokeWidth = 1f
                                                    )
                                                    x += step
                                                }
                                            }
                                            if (paperStyle.background == PageBackground.DOT_GRID) {
                                                var y = step
                                                while (y < modelH) {
                                                    var x = step
                                                    while (x < modelW) {
                                                        drawCircle(
                                                            color = lineColor,
                                                            radius = 1.5f,
                                                            center = androidx.compose.ui.geometry.Offset(x * sx, y * sy)
                                                        )
                                                        x += step
                                                    }
                                                    y += step
                                                }
                                            }
                                        }
                                    }
                                }
                                StaticPageOverlay(
                                    modifier = Modifier.fillMaxSize(),
                                    strokes = staticStrokes,
                                    imageAnnotations = staticImages,
                                    textAnnotations = staticTexts,
                                    modelWidth = viewModel.modelWidth,
                                    modelHeight = viewModel.modelHeight
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

@Composable
private fun StaticPageOverlay(
    modifier: Modifier = Modifier,
    strokes: List<StrokeWithPoints>,
    imageAnnotations: List<ImageAnnotationEntity>,
    textAnnotations: List<TextAnnotationEntity>,
    modelWidth: Float,
    modelHeight: Float
) {
    val context = LocalContext.current
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

    Spacer(modifier = modifier.drawBehind {
        val modelW = modelWidth
        val modelH = modelHeight
        if (modelW <= 0f || modelH <= 0f) return@drawBehind
        val sx = size.width / modelW
        val sy = size.height / modelH
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
}

internal fun polygonBounds(points: List<Offset>): Rect? {
    if (points.isEmpty()) return null
    val minX = points.minOf { it.x }
    val minY = points.minOf { it.y }
    val maxX = points.maxOf { it.x }
    val maxY = points.maxOf { it.y }
    if (maxX <= minX || maxY <= minY) return null
    return Rect(minX, minY, maxX, maxY)
}

internal fun transformedPaperRect(
    containerWidth: Int,
    containerHeight: Int,
    paperWidthPx: Int,
    paperHeightPx: Int,
    scale: Float,
    offsetX: Float,
    offsetY: Float
): Rect? {
    if (containerWidth <= 0 || containerHeight <= 0 || paperWidthPx <= 0 || paperHeightPx <= 0) {
        return null
    }
    val paperLeft = (containerWidth - paperWidthPx) / 2f
    val paperTop = (containerHeight - paperHeightPx) / 2f
    val baseRect = Rect(
        left = paperLeft,
        top = paperTop,
        right = paperLeft + paperWidthPx,
        bottom = paperTop + paperHeightPx
    )
    val centerX = containerWidth / 2f
    val centerY = containerHeight / 2f
    fun transformPoint(point: Offset): Offset {
        val dx = point.x - centerX
        val dy = point.y - centerY
        return Offset(
            x = centerX + dx * scale + offsetX,
            y = centerY + dy * scale + offsetY
        )
    }
    val transformedTopLeft = transformPoint(baseRect.topLeft)
    val transformedBottomRight = transformPoint(baseRect.bottomRight)
    return Rect(
        left = minOf(transformedTopLeft.x, transformedBottomRight.x),
        top = minOf(transformedTopLeft.y, transformedBottomRight.y),
        right = maxOf(transformedTopLeft.x, transformedBottomRight.x),
        bottom = maxOf(transformedTopLeft.y, transformedBottomRight.y)
    )
}
