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
import com.vic.inkflow.util.EnvelopeUtils
import com.vic.inkflow.util.StrokePoint

import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.view.MotionEvent
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
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.runtime.withFrameNanos
import com.vic.inkflow.util.PalmRejectionFilter
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
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.graphics.drawscope.scale
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
import androidx.compose.ui.zIndex
import com.vic.inkflow.ui.theme.BrandIndigo
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

/** AI 快捷指令 prompt（繁體中文）：圈選氣泡第二排使用，按下後隨圈選圖一起送進 Gemini 自動送出。 */
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

@Composable
internal fun Workspace(
    pageIndex: Int,
    pdfViewModel: PdfViewModel,
    viewModel: EditorViewModel,
    modifier: Modifier = Modifier,
    pageAspectRatio: Float = 1f / 1.414f,
    documentUri: String,
    onAiFileReady: (android.net.Uri, String?) -> Unit,
    hazeState: dev.chrisbanes.haze.HazeState = rememberHazeState(),
    isDarkTheme: Boolean = false,
    prismalBackdrop: com.styropyr0.prismal.PrismalBackdrop? = null,
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

    // 圈選送 AI 共用管線：擷取套索區 → 分享檔 → FileProvider Uri → 交給 AI 面板（附可選快捷 prompt）。
    fun sendRegionToAi(prompt: String?, pageIdx: Int) {
        if (isExtracting || !hasSelection || !hasRegionSnapshot) return
        isExtracting = true
        scope.launch {
            try {
                val sourceBitmap = kotlinx.coroutines.withTimeoutOrNull(1200) {
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
                isExtracting = false
            }
        }
    }

    var bubbleWidthPx by remember { mutableIntStateOf(0) }
    var bubbleHeightPx by remember { mutableIntStateOf(0) }

    // 文件級縮放：整份文件同一個 docZoom（Chrome 式），ViewModel 持有、跨頁保持；
    // pinchActive 供 InkCanvas 棄筆判定
    val docZoom by viewModel.docZoom.collectAsState()
    val hScrollState = rememberScrollState()
    var viewportWpx by remember { mutableIntStateOf(0) }

    val density = LocalDensity.current
    // 頁間隙 px：必須與下方 LazyColumn 的 Arrangement.spacedBy(18.dp) 一致（跨頁分段用）
    val pageGapPx = with(density) { 18.dp.toPx() }
    // 列表上下內距 px：與下方 LazyColumn 的 contentPadding(vertical = 18.dp) 一致（縮放垂直錨定用）
    val listPadTopPx = with(density) { 18.dp.toPx() }
    val bubbleGapPx = with(density) { 12.dp.toPx() }
    val bubbleSidePaddingPx = with(density) { 12.dp.toPx() }
    val bubbleTopSafePx = with(density) { 12.dp.toPx() }

    // 橫移狀態（空白區單指＋雙指全域寫入，放手停留、跨頁保持）：
    // 鉗制保證至少 1/4 紙留在區內（KEEP=1/4）。
    // 用 fun 即時重算（讀 State delegate 即時值），不 remember，避免快照訂閱地雷。
    val panOffsetX by viewModel.panOffsetX.collectAsState()
    fun paperWpxForPan(): Float {
        val zoom = viewModel.docZoom.value.coerceAtMost(1f)
        val side = with(density) { 40.dp.toPx() }
        return ((viewportWpx * zoom) - side).coerceAtLeast(0f)
    }
    fun clampPan(raw: Float): Float {
        val max = paperWpxForPan() * 0.25f
        return raw.coerceIn(-max, max)
    }
    fun isBlankX(x: Float): Boolean {
        if (viewModel.docZoom.value > 1f) return false
        // 紙的實際位置＝置中＋當前橫移：推到底後空出來的地方整條都是活區，點哪都能拉回。
        // （之前沒加 pan，空出來的 [L, L+pan] 被誤判成紙，推到底只剩 20dp 縫能起手＝卡死。）
        val paperLeft = (viewportWpx - paperWpxForPan()) / 2f + clampPan(viewModel.panOffsetX.value)
        return x < paperLeft || x > paperLeft + paperWpxForPan()
    }
    val clampedPanX = clampPan(panOffsetX)
    // zoom/旋轉導致邊界縮小：只在 viewport/zoom 變化時收斂一次（直給，不用 spring 追移動目標）。
    // 不以 clampedPanX 為 key——手勢寫入的值出場已鉗制，放手後不再有外力寫回。
    LaunchedEffect(viewportWpx, docZoom) {
        val cur = viewModel.panOffsetX.value
        val fixed = clampPan(cur)
        if (fixed != cur) viewModel.setPanOffsetX(fixed)
    }

    // 工作區層手掌辨識（touchMajor）：手掌和捏合錨定指在位移上長得一樣，
    // 唯一分得出來的是接觸面積。Compose 手勢層拿不到面積，所以跟 InkCanvas 一樣
    // 用 pointerInteropFilter 偷看原生 MotionEvent（墨水那套獨立運作，互不干擾）。
    // 用途：雙指重心/間距＋空白作廢計數只算非手掌點——手掌貼著時手指照樣全速拖，
    // 真雙指（兩個小點）和捏合錨定指行為不變。
    // 安全閥：touchMajor <= 0（裝置不回報）一律當手指——fail-open，寧放過不誤殺。
    // 注意不用 PalmRejectionFilter.shouldReject 整顆：它含「多指即拒」條款（寫字用的），平移只取面積判定。
    val palmTouchMajors = remember { mutableStateMapOf<Int, Float>() }
    fun isPalmPointer(composeId: Long): Boolean {
        val major = palmTouchMajors[composeId.toInt()] ?: return false
        if (major <= 0f) return false
        return if (major >= 10f) {
            major > with(density) { 45.dp.toPx() } // 像素檔（同 PalmRejectionFilter 閾值）
        } else {
            major > PalmRejectionFilter.RAW_UNIT_PALM_THRESHOLD // 小米歸一化檔 1.8
        }
    }

    val regionBoundsModel = remember(activeRegionPolygon) { polygonBounds(activeRegionPolygon) }

    // 雙指全域手勢（紙上＋空白＋跨頁，單一仲裁器，同時是唯一的縮放入口）：
    // PAN → dx 寫 panOffsetX、dy 同步 dispatchRawDelta；PINCH → 寫 shared docZoom＋水平錨定。
    // 掛外層 Box：兩指分落上下頁時 centroid/span 仍是同一座標系，跨頁捏合/平移都能動
    // （之前 pinch 掛每頁 Box 內，跨頁雙指每頁只見 1 指 → 跨頁捏合永遠觸發不了）。
    // 同步施加、無協程排隊：放手即停（之前每幀 scope.launch scrollBy，放手後還在消化佇列＝彈走主因）。
    // 順序：twoFinger 先裝、blankPan 後裝——雙指接管時 consume 會取消單指拖，避免雙重施加。
    val twoFingerModifier = Modifier.pointerInput(Unit) {
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
                    lastIds = emptySet()
                    if (wasPinching) {
                        viewModel.setPinchActive(false)
                        wasPinching = false
                    }
                    break
                }
                // 只算觸控手指：觸控筆書寫時不參與；手掌（大接觸面積）剔除——
                // 手掌靜止＋手指拖時，不過濾的話重心只走一半速度、間距亂變還會誤判 PINCH，
                // 就是半速＋亂縮放的來源。真雙指兩個都是小點，不過濾不受影響。
                val touch = pressedAll.filter { it.type == PointerType.Touch && !isPalmPointer(it.id.value) }
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
                        is TwoFingerDecision.Pan -> {
                            event.changes.forEach { it.consume() }
                            if (decision.dx != 0f) {
                                viewModel.setPanOffsetX(clampPan(viewModel.panOffsetX.value + decision.dx))
                            }
                            if (decision.dy != 0f) {
                                mainListState.dispatchRawDelta(-decision.dy)
                            }
                        }
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
                                    // 錨定在雙指中心 (cx, cy) 下——內容點跟著手指，不漂移。
                                    // 水平：內容點 = scrollX + cx - panX（整列被 panOffsetX 平移過，必須扣掉）。
                                    val panX = clampPan(viewModel.panOffsetX.value)
                                    val currentScrollX = hScrollState.value
                                    val maxScrollX = maxOf(0f, viewportWpx * (new - 1f))
                                    val targetScrollX = ((currentScrollX + cx - panX) * ratio - cx + panX)
                                        .coerceIn(0f, maxScrollX)
                                    // 垂直：各頁等高 H，內容點 P = S + cy 縮放後保持 → S' = S + P * (ratio - 1)。
                                    val firstItem = mainListState.layoutInfo.visibleItemsInfo.firstOrNull()
                                    val anchorDy = if (firstItem != null && firstItem.size > 0) {
                                        val scrollS = listPadTopPx +
                                            firstItem.index * (firstItem.size + pageGapPx) +
                                            mainListState.firstVisibleItemScrollOffset
                                        (scrollS + cy) * (ratio - 1f)
                                    } else 0f
                                    viewModel.setDocZoom(new)
                                    val deltaX = targetScrollX - currentScrollX
                                    if (kotlin.math.abs(deltaX) > 0.5f) {
                                        hScrollState.dispatchRawDelta(deltaX)
                                    }
                                    if (anchorDy.isFinite() && kotlin.math.abs(anchorDy) > 0.5f) {
                                        mainListState.dispatchRawDelta(anchorDy)
                                    }
                                }
                            }
                        }
                        else -> Unit // 未定：不 consume
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
    // 空白區單指二維拖曳：起點在空白才接管（紙上單指一律放過，繪圖/直捲不受影響），
    // 之後 dx→panOffsetX、dy→主列表同步位移——斜上斜下自然支援，與雙指 Pan 同一語義。
    // 零 slop 接管：第一個 move 像素即 consume，內層 LazyColumn 的 slop 永遠湊不滿、
    // 整段手勢只有這裡在施加——之前用 detectDragGestures 跟原生捲動跑 slop 競賽，
    // 原生先過的那次整段垂直被獨佔（consume 不分軸，dx 全死），就是「有時只能走直線」。
    // 手寫 awaitPointerEvent 迴圈：被別人 consume 的幀只跳過不死（雙指接管時自動讓路，不雙重施加）。
    val blankPanModifier = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (isPalmPointer(down.id.value)) return@awaitEachGesture // 手掌先落：不接管
            if (!isBlankX(down.position.x)) return@awaitEachGesture
            while (true) {
                val event = awaitPointerEvent()
                // 第二根「手指」出現：整段手勢作廢，交給雙指修飾——手掌不算，
                // 否則手掌貼著時所有空白拖曳一動就死（另一種卡住）。
                if (event.changes.count { it.pressed && !isPalmPointer(it.id.value) } > 1) return@awaitEachGesture
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break // up/cancel：tap 原樣放過，不消耗
                val delta = change.positionChange()
                if (delta != Offset.Zero && !change.isConsumed) {
                    change.consume()
                    if (delta.x != 0f) {
                        viewModel.setPanOffsetX(clampPan(viewModel.panOffsetX.value + delta.x))
                    }
                    if (delta.y != 0f) {
                        mainListState.dispatchRawDelta(-delta.y)
                    }
                }
            }
        }
    }
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

    // 卷動跟著走：作用頁 = 可見面積最大的那頁（不是 firstVisible）。
    // 之前用 firstVisibleItemIndex，第 2 頁要「完整出現、把第 1 頁完全頂掉」才會激活，
    // 半露出的頁只能看不能寫。改最大可見面積後，露出一半以上就能直接寫。
    // （側欄置中由 EditorScreen 跟著做）
    LaunchedEffect(mainListState, pageCount) {
        snapshotFlow {
            val info = mainListState.layoutInfo
            val start = info.viewportStartOffset
            val end = info.viewportEndOffset
            var best = -1
            var bestVisible = -1
            var first = Int.MAX_VALUE
            var last = Int.MIN_VALUE
            for (item in info.visibleItemsInfo) {
                if (item.index < first) first = item.index
                if (item.index > last) last = item.index
                val visStart = maxOf(item.offset, start)
                val visEnd = minOf(item.offset + item.size, end)
                val visible = (visEnd - visStart).coerceAtLeast(0)
                if (visible > bestVisible) {
                    bestVisible = visible
                    best = item.index
                }
            }
            Triple(best, first, last)
        }.collect { (idx, first, last) ->
            // 可視範圍預取：未露臉的鄰頁先查好，快取當初始值，第一幀就有墨
            if (first != Int.MAX_VALUE && last != Int.MIN_VALUE) {
                viewModel.prefetchPages(first, last)
                // 點陣預熱：可視 ±1 底先渲好，滑入直接顯示（API 自帶邊界守衛＋渲染排隊）
                for (p in first - 1..last + 1) pdfViewModel.prefetchPage(p)
            }
            // 頁鎖期間（跨頁手勢中）：忽略自動捲帶來的作用頁切換，
            // 手勢結束由 InkCanvas.onCrossPageEnd 激活目標頁，避免 InkCanvas 中途被替換斷筆
            if (!viewModel.isPageLocked() && idx in 0 until pageCount) onScrollPage(idx)
        }
    }

    // 渲染刻度跟著可視寬：可視越寬渲染倍率越高（2x–3.5x），旋轉/轉向自動重渲
    // Fix2b: 防抖 300ms — AI 面板開合/拖曳時寬度連變，只在落定後重渲，避免 evict 風暴
    val renderEpoch by pdfViewModel.renderEpoch.collectAsState()
    val firstSize by pdfViewModel.firstPageSize.collectAsState()
    LaunchedEffect(viewportWpx, firstSize) {
        val w = firstSize?.first ?: 595f
        if (viewportWpx > 0 && w > 0f) {
            android.util.Log.d("InkFlowDbg", "viewport settled? wpx=$viewportWpx firstW=$w (debouncing)")
            delay(300)
            android.util.Log.d("InkFlowDbg", "viewport applied wpx=$viewportWpx")
            pdfViewModel.setDisplayRenderScale(viewportWpx.toFloat() / w)
        }
    }

    // 直向連續卷動：一頁接一頁；外層橫向卷軸承載文件級縮放（整份同縮，Chrome 式）
    Box(
        modifier = modifier
            .background(Color.Transparent)
            .onSizeChanged { viewportWpx = it.width }
            // 原生接觸面積採集（手掌辨識用）：只看不攔（回傳 false），事件原樣交給手勢層。
            // 與 InkCanvas 內的採集器各管各的 map，互不干擾。
            .pointerInteropFilter { motionEvent ->
                when (motionEvent.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                        repeat(motionEvent.pointerCount) { i ->
                            val pid = motionEvent.getPointerId(i)
                            // 筆永不判掌：記 0，isPalmPointer 直接放行
                            palmTouchMajors[pid] =
                                if (motionEvent.getToolType(i) == MotionEvent.TOOL_TYPE_STYLUS) 0f
                                else motionEvent.getTouchMajor(i)
                        }
                    }
                    MotionEvent.ACTION_POINTER_UP ->
                        palmTouchMajors.remove(motionEvent.getPointerId(motionEvent.actionIndex))
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        palmTouchMajors.clear()
                }
                false
            }
            .then(twoFingerModifier)
            .then(blankPanModifier)
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
                    .fillMaxHeight()
                    // 橫移：整列水平位移（offset 直給，無大圖層、無 spring）
                    .offset { IntOffset(clampedPanX.roundToInt(), 0) },
                contentPadding = PaddingValues(vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
        items(pageCount, key = { it }) { index ->
            val aspect = uniformAspect
            // 套索跨頁拖曳中：overlay 接手選取預覽（本 item 需配合置頂）
            val dragActive by viewModel.dragPreviewActive.collectAsState()
            // 數據上提：bitmap + 三路註記流放在分支外面，作用頁/靜態頁身份互換時
            // remember 不重建、Flow 不重訂、實例不變 —— 翻頁不再有空窗白閃。
            // （之前翻頁閃光的主因：分支內各自 remember，切換必重載 + Crossfade 重播）
            val bitmapFlow = remember(index, renderEpoch) { pdfViewModel.getPageBitmap(index) }
            val pageBitmap by bitmapFlow.collectAsState()
            // 常駐熱流不斷線：捲動中反覆組成只換訂閱不重查；初始值吃預取快取，第一幀就有墨
            val pageData by remember(index) { viewModel.pageDataFlow(index) }
                .collectAsState(initial = viewModel.cachedNeighbor(index) ?: EditorViewModel.NeighborPageData())
            val pageStrokes = pageData.strokes
            val pageImages = pageData.images
            val pageTexts = pageData.texts
            // M1 全活頁：每張紙都掛 InkCanvas，以下即本體（原作用頁分支）；
            // 作用頁概念僅剩側欄指示器＋落筆記憶。靜態分支已删除。
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
                        // 拖曳 overlay 置頂：溢出紙界的框/墨不被後頁蓋住；
                        // 全活頁下只頂選取歸屬紙（其餘紙的 overlay 不畫，見下）
                        .zIndex(if (dragActive && viewModel.selectionPage() == index) 1f else 0f),
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
            // 白紙墊底：點陣圖還沒來/渲染失敗時顯示白紙，不露深色主題底（永久黑頁主因）
            if (pageBitmap == null) {
                Box(
                    modifier = Modifier.fillMaxSize()
                        .background(Color.White)
                )
            }
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
            // M1 全活頁：InkCanvas 只吃本頁熱流，不讀作用頁流；
            // 新鮮度門隨單活頁一起退役（每頁數據自洽，無跨頁鬼影可言）。
            InkCanvas(
                modifier = Modifier.fillMaxSize(),
                viewModel = viewModel,
                pdfViewModel = pdfViewModel,
                documentUri = documentUri,
                pageIndex = index,
                strokes = pageStrokes,
                texts = pageTexts,
                images = pageImages,
                pageGapPx = pageGapPx,
                onEdgeAutoScroll = { dy ->
                    // 同步施加：寫筆中邊緣捲不斷流，提筆即停（不經協程排隊）
                    mainListState.dispatchRawDelta(dy)
                }
            )
                        } // 作用頁內容 Box
                    } // 紙 Surface
                    // 跨頁拖曳 overlay：框+墨畫在紙上層、可溢出紙界（clip=false），
                    // InkCanvas 側同時讓位（同像素只畫一次）；放開提交後清旗接回。
                    // 全活頁下只在選取歸屬紙畫（他紙同像素會重影，螢光筆疊色）。
                    if (dragActive && itemWidthPx > 0 && viewModel.selectionPage() == index) {
                        DragPreviewOverlay(
                            viewModel = viewModel,
                            paperWpx = itemWidthPx.toFloat(),
                            aspect = aspect,
                            modifier = Modifier
                                .width(with(density) { itemWidthPx.toDp() })
                                .aspectRatio(aspect)
                                .graphicsLayer { clip = false }
                        )
                    }
                    // 套索氣泡：只在選取歸屬紙顯示（全活頁下選取可落任意紙）
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showSelectionBubble && viewModel.selectionPage() == index,
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
                    .onSizeChanged {
                        bubbleWidthPx = it.width
                        bubbleHeightPx = it.height
                    }
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
                            isExtracting = true
                            scope.launch {
                                try {
                                    // 提取源 = 選取歸屬紙（全活頁下未必是作用頁）
                                    val sourcePageIndex = viewModel.selectionPage()
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
                    )
                    SelectionBubbleAction(
                        icon = Icons.Filled.AutoAwesome,
                        label = "AI 解析",
                        enabled = !isExtracting && hasSelection && hasRegionSnapshot,
                        onClick = { sendRegionToAi(null, pageIndex) }
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
                                onClick = { sendRegionToAi(AiQuickPrompt.EXPLAIN, viewModel.selectionPage()) }
                            )
                            SelectionBubbleAction(
                                icon = Icons.Filled.Summarize,
                                label = "總結",
                                enabled = !isExtracting,
                                onClick = { sendRegionToAi(AiQuickPrompt.SUMMARIZE, viewModel.selectionPage()) }
                            )
                            SelectionBubbleAction(
                                icon = Icons.Filled.Translate,
                                label = "翻譯",
                                enabled = !isExtracting,
                                onClick = { sendRegionToAi(AiQuickPrompt.TRANSLATE, viewModel.selectionPage()) }
                            )
                        }
                    }
                }
            }
                    }
                    } // 全活頁 item Box（靜態分支已删除，每紙常駐 InkCanvas）
            }
        }
    }
}
}

/**
 * 套索跨頁拖曳 overlay：與 InkCanvas 紙內選取預覽同像素、同畫法，
 * 但畫在紙上層且可溢出紙界（clip=false）+ item 置頂，
 * 拖出紙界的框/墨全程可見，不被後頁蓋住。
 * 顯示期間 InkCanvas 側讓位（見 dragPreviewActive），同像素只畫一次。
 */
@Composable
private fun DragPreviewOverlay(
    viewModel: EditorViewModel,
    paperWpx: Float,
    aspect: Float,
    modifier: Modifier = Modifier
) {
    val preview by viewModel.selectedStrokePreview.collectAsState()
    if (preview.isEmpty()) return
    val moveOffset by viewModel.lassoMoveOffset.collectAsState()
    val scale by viewModel.selectedStrokeScale.collectAsState()
    val anchorState by viewModel.selectedStrokeResizeAnchor.collectAsState()
    val bounds by viewModel.selectedStrokePreviewBounds.collectAsState()
    val framePolygon by viewModel.selectionFramePolygon.collectAsState()
    val modelW = viewModel.modelWidth
    val modelH = viewModel.modelHeight
    if (modelW <= 0f || modelH <= 0f || paperWpx <= 0f) return
    val sx = paperWpx / modelW
    val paperHpx = paperWpx / aspect
    val sy = if (paperHpx > 0f) paperHpx / modelH else sx
    val anchor = anchorState ?: bounds?.center ?: androidx.compose.ui.geometry.Offset.Zero
    // 路徑只隨選取內容重建，拖曳位移只走 draw（不重建包絡，不卡）
    val pathData = remember(preview) {
        preview.map { swp ->
            swp to if (swp.stroke.shapeType == null) swp.points.toComposePath() else null
        }
    }
    androidx.compose.foundation.Canvas(modifier = modifier) {
        drawIntoCanvas { cvs ->
            cvs.save()
            cvs.scale(sx, sy)
            applySelectionTransform(cvs, moveOffset, scale, anchor)
            pathData.forEach { (swp, path) ->
                if (swp.stroke.shapeType != null) {
                    drawShapeOnCanvas(cvs, swp.stroke, swp.points, tintColor = BrandIndigo)
                } else if (path != null) {
                    drawPathOnCanvas(
                        cvs, path, BrandIndigo,
                        swp.stroke.strokeWidth, swp.stroke.isHighlighter
                    )
                }
            }
            cvs.restore()
        }
        val rect = modelTransformedPolygonBoundsToCanvasRect(
            polygon = framePolygon,
            translation = moveOffset,
            scale = scale,
            anchor = anchor,
            sx = sx,
            sy = sy
        )
        if (rect != null && !rect.isEmpty) {
            drawLassoSelectionFrame(
                selectionRect = rect,
                showHandles = true,
                dashPhase = 0f,
                animateDash = false
            )
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
                    // 與 InkCanvas 即時層同畫法（包絡填充，逐點寬），消除換頁/切換門線感跳變。
                    val env = EnvelopeUtils.generateEnvelopePath(
                        pts.map { StrokePoint(it.x, it.y, it.width) })
                    scale(sx, sy) { drawPath(env, strokeColor.copy(alpha = alpha)) }
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
                    // Multiline：與 InkCanvas 同畫法（modelY=首行 baseline 逐行下移），否則 \n 疊成一行
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
