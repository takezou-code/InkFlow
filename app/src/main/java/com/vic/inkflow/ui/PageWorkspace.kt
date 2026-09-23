package com.vic.inkflow.ui

import com.vic.inkflow.ui.theme.ShapeSm

import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.provider.OpenableColumns
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.runtime.withFrameNanos
import com.vic.inkflow.util.DocLayout
import com.vic.inkflow.util.DocTransform
import com.vic.inkflow.util.GestureStateMachine
import com.vic.inkflow.util.PalmRejectionFilter
import com.vic.inkflow.util.Pt
import com.vic.inkflow.util.TraceRecorder
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.vic.inkflow.data.AppDatabase
import com.vic.inkflow.ui.theme.BrandIndigo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    db: AppDatabase,
    mainListState: LazyListState = rememberLazyListState(),
    onRequestPage: (Int) -> Unit = {},
    onScrollPage: (Int) -> Unit = {}
) {
    val isDarkSurface = isDarkTheme || MaterialTheme.colorScheme.background.luminance() < 0.5f
    // 桌面全透明：底由 EditorScreen 根 Aurora 提供，紙直接浮在光斑上
    val paperColor = MaterialTheme.colorScheme.surface
    val activeTool by viewModel.selectedTool.collectAsState()
    val lassoPolygon by viewModel.lassoPolygon.collectAsState()
    val lastLassoPolygon by viewModel.lastLassoPolygon.collectAsState()
    val selectedStrokes by viewModel.selectedStrokes.collectAsState()
    val selectedImageAnnotationIds by viewModel.selectedImageAnnotationIds.collectAsState()
    val selectedTextAnnotationIds by viewModel.selectedTextAnnotationIds.collectAsState()
    val hasEditableSelection = selectedStrokes.isNotEmpty() || selectedImageAnnotationIds.isNotEmpty() ||
        selectedTextAnnotationIds.isNotEmpty()
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
    // 手勢慣性已刪除（M2 規格：手指離開即停，fling 兩條全退役）。
    // 頁間隙 px：必須與下方 LazyColumn 的 Arrangement.spacedBy(18.dp) 一致（跨頁分段用）
    val pageGapPx = with(density) { 18.dp.toPx() }
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

    // 全文件統一直徑：所有頁同一個 aspect（model 為準），上下頁不可能大小不一；
    // 墨水映射也因此是 1:1 精確（不再被混尺寸拉伸）。
    // （位置：必須在手勢修飾之前宣告，捏合垂直錨定要用到它。）
    val modelW0 = viewModel.modelWidth
    val modelH0 = viewModel.modelHeight
    val uniformAspect = remember(modelW0, modelH0, pageAspectRatio) {
        val a = if (modelW0 > 0f && modelH0 > 0f) modelW0 / modelH0 else pageAspectRatio
        // 非有限值會讓 aspectRatio 罷工甚至閃退，直接兜底 A4 直式
        if (a.isFinite() && a in 0.2f..5f) a else 1f / 1.414f
    }

    // 捏合垂直錨定用：zoom=z 時單頁紙高 px（與下方 Surface 版式同公式：
    // 列寬 viewport*max(z,1) → Surface 取 min(z,1) → 扣 20dp 雙邊墊 → 除 aspect）。
    fun paperHAtZoom(z: Float): Float {
        val listW = viewportWpx * maxOf(z, 1f)
        val sidePad = with(density) { 40.dp.toPx() }
        return ((listW * z.coerceAtMost(1f) - sidePad) / uniformAspect).coerceAtLeast(0f)
    }

    // 工作區層手掌辨識（touchMajor）：手掌和捏合錨定指在位移上長得一樣，
    // 唯一分得出來的是接觸面積。Compose 手勢層拿不到面積，所以跟 InkCanvas 一樣
    // 用 pointerInteropFilter 偷看原生 MotionEvent（墨水那套獨立運作，互不干擾）。
    // 用途：雙指重心/間距＋空白作廢計數只算非手掌點——手掌貼著時手指照樣全速拖，
    // 真雙指（兩個小點）和捏合錨定指行為不變。
    // 安全閥：touchMajor <= 0（裝置不回報）一律當手指——fail-open，寧放過不誤殺。
    // 注意不用 PalmRejectionFilter.shouldReject 整顆：它含「多指即拒」條款（寫字用的），平移只取面積判定。
    // 面積走 EMA 平滑（抄 AOSP ScaleGestureDetector 作業：touchMajor 在部分驅動上會抖）：
    // 初見即記，MOVE 逐幀 0.6/0.4 收斂——落指拍那一下、抖一下都被吸掉，一直貼著的真手掌才出局。
    // 普通 Map（無 composition 讀者，MOVE 高頻寫免快照開銷；讀寫全在 UI 線）。
    val palmTouchMajors = remember { mutableMapOf<Int, Float>() }
    // 系統手掌取消（官方 pattern，面積啟發式的第二軌）：
    // Android 13+ 對誤觸發 ACTION_POINTER_UP + FLAG_CANCELED（單指 ACTION_CANCEL 全版本），
    // 收到就把該 pointer 踢出計數（isCountedFinger 順查）。同 id 再 DOWN 即移出（防復用誤殺），
    // 全放開清空（防膨脹）。墨水側 undo 是 follow-up，這裡只管手勢計數。
    val cancelledIds = remember { mutableStateSetOf<Int>() }
    fun isPalmPointer(composeId: Long): Boolean {
        val major = palmTouchMajors[composeId.toInt()] ?: return false
        return PalmRejectionFilter.isPalmByArea(major, density)
    }

    /**
     * P0：兩層手勢共用同一個「算數的手指」定義（觸控＋非手掌；筆不算）。
     * 之前雙指層看不見筆、空白層把筆當手指——筆＋手指組合兩頭落空。
     */
    fun isCountedFinger(change: PointerInputChange): Boolean =
        change.type == PointerType.Touch && !isPalmPointer(change.id.value) &&
            change.id.value.toInt() !in cancelledIds

    // M2 單一手勢迴圈（手指模式）：空白單指＋雙指 PAN/PINCH 全收進 GestureStateMachine，
    // 舊的雙層修飾＋補丁堆（arbitrator/carry/grace/fling/shadow/sight/panSpike）退役，blankPan 併入此迴圈。
    // 消費策略（TOUCH_CONTRACT 不變）：未定＋起點空白→全攔（blank 歸我，原生餓死）；
    // 未定＋起點紙上→只攔新指（墨水/原生繼續）；鎖定/接管後→全攔。放手零外力（無慣性）。
    // PINCH 施加：機器給的 focus 已指數平滑＋snap；zoom 幀只做 anchor-set（直接定狀態），
    // 不再另加一份 centroid-drift dispatch（舊碼兩份同施＝飄的嫌疑犯之一）。
    val twoFingerModifier = Modifier.pointerInput(Unit) {
        val machine = GestureStateMachine(touchSlopPx = viewConfiguration.touchSlop)
        // 嚴謹量測：每幀記「手指在哪／紙在哪」，手勢結束一次倒出來（tag InkFlowTrace），
        // 離線算接合延遲、跟手保真度、抖動、放手後位移。不再憑感覺。
        val tracer = TraceRecorder()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val startedBlank = isBlankX(down.position.x)
            val fedIds = mutableSetOf<Int>()
            var gestureActive = false // 雙指鎖定接管中（墨水讀 pinchActive 棄筆）
            var lockedKind: GestureStateMachine.Kind? = null
            var lastZoomFx = 0f
            var lastZoomFy = 0f
            var haveZoomFocus = false

            fun startKind(kind: GestureStateMachine.Kind) {
                if (kind == GestureStateMachine.Kind.SINGLE_PAN) return // 空白單指不立旗（舊制）
                if (!gestureActive) {
                    android.util.Log.d("InkFlowGesture", "m2Lock=$kind blank=$startedBlank")
                    viewModel.setPinchActive(true)
                    gestureActive = true
                    lockedKind = kind
                    pdfViewModel.setRendersPaused(true)
                } else if (lockedKind != kind) {
                    android.util.Log.d("InkFlowGesture", "m2Upgrade=$lockedKind->$kind")
                    lockedKind = kind
                }
                haveZoomFocus = false
            }
            fun endAll() {
                if (gestureActive) {
                    viewModel.setPinchActive(false)
                    gestureActive = false
                    lockedKind = null
                    pdfViewModel.flushPendingRenders()
                }
                haveZoomFocus = false
            }
            // 捏合施加：zoom 幀 anchor-set 直接定狀態；factor==1 幀跟隨平滑 focus 漂移
            //（舊碼捏合重心漂移照吃，行為保留，輸入換成平滑後的）。
            fun applyZoom(fx: Float, fy: Float, factor: Float) {
                if (!factor.isFinite() || factor.isNaN() || factor <= 0f) return
                if (factor == 1f) {
                    if (haveZoomFocus) {
                        val dx = fx - lastZoomFx
                        val dy = fy - lastZoomFy
                        if (dx != 0f) viewModel.setPanOffsetX(clampPan(viewModel.panOffsetX.value + dx))
                        if (dy != 0f) mainListState.dispatchRawDelta(-dy)
                    }
                    lastZoomFx = fx
                    lastZoomFy = fy
                    haveZoomFocus = true
                    return
                }
                // pointerInput(Unit) 只跑一次：閉包裡的 docZoom delegate 是陳舊快照，
                // 必須讀 viewModel.docZoom.value（新鮮值），否則每幀都拿初始值乘，
                // zoom 永遠不疊加＝捏合看起來死了（AGENTS 快照訂閱地雷同族）。
                val old = viewModel.docZoom.value
                val new = (old * factor).coerceIn(0.4f, 4f)
                if (!new.isFinite() || new.isNaN() || new == old) return
                val ratio = new / old
                // 錨定在雙指中心 fx 下：內容點 = scrollX + fx - panX
                //（整列被 panOffsetX 平移過，必須扣掉；不扣＝平移後再捏就偏）。
                val panX = clampPan(viewModel.panOffsetX.value)
                val currentScrollX = hScrollState.value
                val maxScrollX = maxOf(0f, viewportWpx * (new - 1f))
                val targetScrollX = ((currentScrollX + fx - panX) * ratio - fx + panX)
                    .coerceIn(0f, maxScrollX)
                viewModel.setDocZoom(new)
                val deltaX = targetScrollX - currentScrollX
                if (kotlin.math.abs(deltaX) > 0.5f) {
                    hScrollState.dispatchRawDelta(deltaX)
                }
                // 垂直錨定 fy：uniform 紙高跟 zoom 線性走、縫/頂墊固定，直接定狀態一步到位
                //（dispatchRawDelta 會跟框架原生保持疊加、每幀 1.8 倍飛走——禁，見 AGENTS）。
                val paperHOld = paperHAtZoom(old)
                val paperHNew = paperHAtZoom(new)
                if (paperHOld > 0f && paperHNew > 0f) {
                    val topPadPx = with(density) { 18.dp.toPx() }
                    val itemFullOld = paperHOld + pageGapPx
                    val scrollOld = topPadPx +
                        mainListState.firstVisibleItemIndex * itemFullOld +
                        mainListState.firstVisibleItemScrollOffset
                    val relOld = (scrollOld + fy - topPadPx).coerceAtLeast(0f)
                    val idx = (relOld / itemFullOld).toInt().coerceAtLeast(0)
                    val fracOld = relOld - idx * itemFullOld
                    val fracNew = if (fracOld <= paperHOld) {
                        fracOld * paperHNew / paperHOld
                    } else {
                        // 落在縫裡：紙部縮放、縫部不動
                        paperHNew + (fracOld - paperHOld)
                    }
                    val scrollNew = topPadPx + idx * (paperHNew + pageGapPx) + fracNew
                    // S_new = P_new − fy：在新版式下分解成 (index, offset) 直接定狀態。
                    val relNew = (scrollNew - fy - topPadPx).coerceAtLeast(0f)
                    val itemFullNew = paperHNew + pageGapPx
                    val totalItems = mainListState.layoutInfo.totalItemsCount
                    val idxNew = (relNew / itemFullNew).toInt().coerceAtLeast(0)
                        .coerceAtMost((totalItems - 1).coerceAtLeast(0))
                    val offNew = (relNew - idxNew * itemFullNew).coerceAtLeast(0f).toInt()
                    android.util.Log.d("InkFlowPinch", "anchor2 old=$old new=$new cy=$fy idx=$idxNew off=$offNew first=${mainListState.firstVisibleItemIndex}+${mainListState.firstVisibleItemScrollOffset}")
                    mainListState.requestScrollToItem(idxNew, offNew)
                    android.util.Log.d("InkFlowPinch", "anchor2-post first=${mainListState.firstVisibleItemIndex}+${mainListState.firstVisibleItemScrollOffset}")
                }
                lastZoomFx = fx
                lastZoomFy = fy
                haveZoomFocus = true
            }
            fun summarize(o: GestureStateMachine.Output?): String = when (o) {
                null -> "-"
                is GestureStateMachine.Output.ScrollBy ->
                    "S(${o.dx.toInt()},${o.dy.toInt()})"
                is GestureStateMachine.Output.ZoomBy ->
                    "Z(${o.factor},${o.focusX.toInt()},${o.focusY.toInt()})"
                is GestureStateMachine.Output.GestureEnd -> "E(${o.kind})"
                is GestureStateMachine.Output.GestureStart -> "B(${o.kind})"
                GestureStateMachine.Output.YieldToStylus -> "Y"
                GestureStateMachine.Output.AbortGesture -> "A"
            }
            fun apply(o: GestureStateMachine.Output?) {
                machine.drainStart()?.let { startKind(it.kind) }
                when (o) {
                    // Start 永遠走 pendingStart（drainStart），這裡不會出現；分支只為窮舉。
                    is GestureStateMachine.Output.GestureStart -> {}
                    is GestureStateMachine.Output.ScrollBy -> {
                        if (o.dx != 0f) {
                            viewModel.setPanOffsetX(clampPan(viewModel.panOffsetX.value + o.dx))
                        }
                        if (o.dy != 0f) {
                            mainListState.dispatchRawDelta(-o.dy)
                        }
                    }
                    is GestureStateMachine.Output.ZoomBy -> applyZoom(o.focusX, o.focusY, o.factor)
                    is GestureStateMachine.Output.GestureEnd -> {
                        android.util.Log.d("InkFlowGesture", "m2End=${o.kind}")
                        endAll()
                        tracer.flush("END-${o.kind}").forEach { android.util.Log.d("InkFlowTrace", it) }
                    }
                    GestureStateMachine.Output.YieldToStylus,
                    GestureStateMachine.Output.AbortGesture -> {
                        android.util.Log.d("InkFlowGesture", "m2$o")
                        fedIds.clear() // 下幀還壓著的手指當新起點重吃（舊 reset 語義）
                        endAll()
                        tracer.flush("$o").forEach { android.util.Log.d("InkFlowTrace", it) }
                    }
                    null -> {}
                }
            }
            try {
                run {
                    val id = down.id.value.toInt()
                    if (down.type == PointerType.Stylus) {
                        apply(machine.stylusDown())
                        return@awaitEachGesture
                    }
                    if (id !in cancelledIds) {
                        fedIds.add(id)
                        apply(
                            machine.pointerDown(
                                id, down.position.x, down.position.y,
                                isPalmPointer(down.id.value), startedBlank, down.uptimeMillis
                            )
                        )
                    }
                }
                while (true) {
                    val event = awaitPointerEvent()
                    val now = event.changes.maxOf { it.uptimeMillis }
                    // 筆出現＝拿筆要寫了：不等寬限，直接重開（墨水一刻不等，凍結契約）。
                    if (event.changes.any { it.pressed && it.type == PointerType.Stylus }) {
                        apply(machine.stylusDown())
                        endAll()
                        return@awaitEachGesture
                    }
                    val pressed = event.changes.filter { it.pressed }
                    for (c in event.changes) {
                        val id = c.id.value.toInt()
                        if (!c.pressed && id in fedIds) {
                            fedIds.remove(id)
                            apply(machine.pointerUp(id, id in cancelledIds, now))
                        }
                    }
                    for (c in pressed) {
                        val id = c.id.value.toInt()
                        if (id !in fedIds && id !in cancelledIds && c.type == PointerType.Touch) {
                            fedIds.add(id)
                            apply(
                                machine.pointerDown(
                                    id, c.position.x, c.position.y,
                                    isPalmPointer(c.id.value), startedBlank, now
                                )
                            )
                        }
                    }
                    if (pressed.isEmpty()) {
                        endAll()
                        break
                    }
                    val snap = pressed
                        .filter { it.id.value.toInt() in fedIds }
                        .associate { it.id.value.toInt() to Pt(it.position.x, it.position.y) }
                    val o1 = machine.tick(now)
                    apply(o1)
                    val o2 = machine.pointerMove(snap, now)
                    apply(o2)
                    // 施加後立刻讀列表位置（同步已驗證）：手指 vs 紙，同一幀。
                    tracer.frame(
                        now = now,
                        fingers = pressed
                            .filter { it.type == PointerType.Touch && !isPalmPointer(it.id.value) }
                            .map { Pt(it.position.x, it.position.y) },
                        state = machine.state.name,
                        out = "${summarize(o1)}|${summarize(o2)}",
                        listIndex = mainListState.firstVisibleItemIndex,
                        listOffset = mainListState.firstVisibleItemScrollOffset,
                        panX = viewModel.panOffsetX.value,
                        zoom = viewModel.docZoom.value
                    )
                    if (startedBlank || gestureActive) {
                        pressed.forEach { it.consume() }
                    } else {
                        // 起點紙上未定：只攔新指，首指放行（墨水/原生繼續吃，不斷流）。
                        pressed.filter { it.id != down.id }.forEach { it.consume() }
                    }
                }
            } catch (e: CancellationException) {
                apply(machine.cancelAll())
                endAll()
            }
            endAll()
        }
    }
    val showSelectionBubble = activeTool == Tool.LASSO && hasSelection && !isExtracting

    // pdfViewModel and LaunchedEffect(uri) are owned by TabletEditorScreen
    val pageCount by pdfViewModel.pageCount.collectAsState()

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
            // 頁鎖期間（跨頁手勢中）：忽略自動捲帶來的頁面切換，避免中途換頁斷筆；
            // 全活頁下各頁本來就活著，手勢結束也無需激活跳轉。
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
                            cancelledIds.remove(pid)
                            // 筆永不判掌：記 0，isPalmPointer 直接放行
                            palmTouchMajors[pid] =
                                if (motionEvent.getToolType(i) == MotionEvent.TOOL_TYPE_STYLUS) 0f
                                else motionEvent.getTouchMajor(i)
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        repeat(motionEvent.pointerCount) { i ->
                            val pid = motionEvent.getPointerId(i)
                            if (motionEvent.getToolType(i) == MotionEvent.TOOL_TYPE_STYLUS) {
                                palmTouchMajors[pid] = 0f
                            } else {
                                val major = motionEvent.getTouchMajor(i)
                                val prev = palmTouchMajors[pid]
                                palmTouchMajors[pid] =
                                    if (prev == null) major else prev * 0.6f + major * 0.4f
                            }
                        }
                    }
                    MotionEvent.ACTION_POINTER_UP -> {
                        val pid = motionEvent.getPointerId(motionEvent.actionIndex)
                        palmTouchMajors.remove(pid)
                        // 系統判定誤觸（API 33+）：踢出計數（見 cancelledIds）。
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                            (motionEvent.flags and MotionEvent.FLAG_CANCELED) != 0
                        ) {
                            cancelledIds.add(pid)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        palmTouchMajors.clear()
                        cancelledIds.clear()
                    }
                }
                false
            }
            .then(twoFingerModifier)
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
                verticalArrangement = Arrangement.spacedBy(18.dp),
                // M2：關系統橡皮筋（頂/底邊緣拉伸回彈跟手勢代碼無關，之前十幾輪修錯地方；
                // 紙縫和邊界就是視覺邊界，不需要第二套）。只關主列表，側欄/對話框不動。
                overscrollEffect = null,
                // 單一寫者：原生拖曳/slop/fling 全關。之前原生列表自己捲、我們又從外面推，
                // 放手後原生還會用它偷看到的速度自己射 fling（殺不掉、看不見＝影子 glide）。
                // 關掉後手勢迴圈是唯一寫者；程式化寫入（dispatch/request）不受影響。
                userScrollEnabled = false
            ) {
        items(pageCount, key = { it }) { index ->
            val aspect = uniformAspect
            // 套索跨頁拖曳中：overlay 接手選取預覽（本 item 需配合置頂）
            val dragActive by viewModel.dragPreviewActive.collectAsState()
            // F4 圖片直接拖：本頁窗口有重疊才顯示 overlay（各頁按自己窗口畫一段）。
            val imageDrag by viewModel.imageDragPreview.collectAsState()
            val dragImgOverlap = remember(imageDrag, index) {
                val d = imageDrag ?: return@remember false
                DocLayout.draggedLocalVertical(
                    anchorPage = d.image.pageIndex,
                    offsetInAnchorPage = d.image.modelY + d.dyModel,
                    extent = d.image.modelHeight,
                    pageIndex = index,
                    pageH = viewModel.modelHeight.coerceAtLeast(1f)
                ) != null
            }
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
                    activeRegionPolygon, itemWidthPx, bubbleWidthPx, bubbleHeightPx, aspect
                ) {
                    bubbleTargetOffset(
                        regionPolygon = activeRegionPolygon,
                        itemWidthPx = itemWidthPx,
                        bubbleWidthPx = bubbleWidthPx,
                        bubbleHeightPx = bubbleHeightPx,
                        aspect = aspect,
                        gapPx = bubbleGapPx,
                        sidePaddingPx = bubbleSidePaddingPx,
                        topSafePx = bubbleTopSafePx,
                        modelW = viewModel.modelWidth,
                        modelH = viewModel.modelHeight
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 拖曳 overlay 置頂：溢出紙界的框/墨/圖不被後頁蓋住；
                        // 全活頁下只頂選取歸屬紙＋圖片拖曳重疊紙（其餘紙的 overlay 不畫，見下）
                        .zIndex(if ((dragActive && viewModel.selectionPage() == index) || dragImgOverlap) 1f else 0f),
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
                    val sx = DocTransform.scaleX(size.width, modelW)
                    val sy = DocTransform.scaleY(size.height, modelH)
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
                        } // 頁內容 Box
                    } // 紙 Surface
                    // 跨頁拖曳 overlay：框+墨畫在紙上層、可溢出紙界（clip=false），
                    // InkCanvas 側同時讓位（同像素只畫一次）；放開提交後清旗接回。
                    // 全活頁下只在選取歸屬紙畫（他紙同像素會重影，螢光筆疊色）。
                    if ((dragActive && itemWidthPx > 0 && viewModel.selectionPage() == index) ||
                        (dragImgOverlap && itemWidthPx > 0)
                    ) {
                        DragPreviewOverlay(
                            viewModel = viewModel,
                            paperWpx = itemWidthPx.toFloat(),
                            aspect = aspect,
                            pageIndex = index,
                            modifier = Modifier
                                .width(with(density) { itemWidthPx.toDp() })
                                .aspectRatio(aspect)
                                .graphicsLayer { clip = false }
                        )
                    }
                    // 套索氣泡：只在選取歸屬紙顯示（全活頁下選取可落任意紙；本體見 LassoBubble.kt）
                    LassoBubble(
                        visible = showSelectionBubble && viewModel.selectionPage() == index,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset { itemTargetOffset },
                        onSizeChanged = {
                            bubbleWidthPx = it.width
                            bubbleHeightPx = it.height
                        },
                        hasSelection = hasSelection,
                        hasEditableSelection = hasEditableSelection,
                        hasRegionSnapshot = hasRegionSnapshot,
                        isExtracting = isExtracting,
                        onExtractingChange = { isExtracting = it },
                        viewModel = viewModel,
                        pdfViewModel = pdfViewModel,
                        documentUri = documentUri,
                        onAiFileReady = onAiFileReady,
                        isDark = isDarkSurface
                    )
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
    pageIndex: Int,
    modifier: Modifier = Modifier
) {
    val preview by viewModel.selectedStrokePreview.collectAsState()
    val selectedIds by viewModel.selectedImageAnnotationIds.collectAsState()
    // 選中圖：與墨同待遇畫在紙上層（跨頁拖曳全程可見；之前 overlay 只畫墨，圖被留在紙內）。
    val selImages = remember(selectedIds) { viewModel.selectedImagesNow() }
    val selTextIds by viewModel.selectedTextAnnotationIds.collectAsState()
    val selTexts = remember(selTextIds) { viewModel.selectedTextsNow() }
    val dragImg by viewModel.imageDragPreview.collectAsState()
    if (preview.isEmpty() && selImages.isEmpty() && selTexts.isEmpty() && dragImg == null) return
    val context = LocalContext.current
    // 圖片解碼快取（1024 封頂，拖曳預覽夠用；與 InkCanvas 各管各的，不共享）。
    val loadedImages = remember { mutableStateMapOf<String, ImageBitmap?>() }
    androidx.compose.runtime.LaunchedEffect(selImages, dragImg?.image?.uri) {
        (selImages.map { it.uri } + listOfNotNull(dragImg?.image?.uri)).distinct().forEach { uri ->
            if (uri !in loadedImages) {
                loadedImages[uri] = null
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val bmp = try {
                        decodeBoundedBitmap(context, Uri.parse(uri), maxSidePx = 1024)
                            ?.asImageBitmap()
                    } catch (_: Exception) { null }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        loadedImages[uri] = bmp
                    }
                }
            }
        }
    }
    val moveOffset by viewModel.lassoMoveOffset.collectAsState()
    val scale by viewModel.selectedStrokeScale.collectAsState()
    val anchorState by viewModel.selectedStrokeResizeAnchor.collectAsState()
    val bounds by viewModel.selectedStrokePreviewBounds.collectAsState()
    val framePolygon by viewModel.selectionFramePolygon.collectAsState()
    val modelW = viewModel.modelWidth
    val modelH = viewModel.modelHeight
    if (modelW <= 0f || modelH <= 0f || paperWpx <= 0f) return
    val sx = DocTransform.scaleX(paperWpx, modelW)
    val paperHpx = paperWpx / aspect
    val sy = if (paperHpx > 0f) DocTransform.scaleY(paperHpx, modelH) else sx
    val anchor = anchorState ?: bounds?.center ?: androidx.compose.ui.geometry.Offset.Zero
    // 路徑只隨選取內容重建，拖曳位移只走 draw（不重建包絡，不卡）
    val pathData = remember(preview) {
        preview.map { swp ->
            swp to if (swp.stroke.shapeType == null) swp.points.toComposePath() else null
        }
    }
    // 字共用 Paint（拖曳 transient，逐字改字號顏色，與 InkCanvas 同模式）。
    val textPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }
    androidx.compose.foundation.Canvas(modifier = modifier) {
        // 選中圖先畫（墊底）；只畫歸屬本紙的（跨紙選取各紙 overlay 畫各的，跟墨同規矩）。
        selImages.forEach { ann ->
            if (ann.pageIndex != pageIndex) return@forEach
            val bmp = loadedImages[ann.uri] ?: return@forEach
            val r = modelTransformedImageRectToCanvasRect(
                image = ann,
                translation = moveOffset,
                scale = scale,
                anchor = anchor,
                sx = sx,
                sy = sy
            )
            rotate(ann.rotation, r.center) {
                drawImage(
                    image = bmp,
                    dstOffset = IntOffset(r.left.toInt(), r.top.toInt()),
                    dstSize = IntSize(
                        r.width.toInt().coerceAtLeast(2),
                        r.height.toInt().coerceAtLeast(2)
                    )
                )
            }
        }
        // F4 圖片直接拖：本頁窗口重疊段畫在紙上層（src 紙原本照畫，同像素複畫無感）。
        // 旋轉中心用整圖中心（與 InkCanvas 一致），映射到本頁座標。
        val dragLocal = dragImg?.let { d ->
            DocLayout.draggedLocalVertical(
                anchorPage = d.image.pageIndex,
                offsetInAnchorPage = d.image.modelY + d.dyModel,
                extent = d.image.modelHeight,
                pageIndex = pageIndex,
                pageH = modelH.coerceAtLeast(1f)
            )?.let { (lt, lb) -> Triple(d, lt, lb) }
        }
        if (dragLocal != null) {
            val (d, lt, lb) = dragLocal
            val bmp = loadedImages[d.image.uri]
            if (bmp != null) {
                val rx = (d.image.modelX + d.dxModel) * sx
                val fullCx = (d.image.modelX + d.dxModel + d.image.modelWidth / 2f) * sx
                val fullCy = (d.image.pageIndex * modelH + d.image.modelY + d.dyModel +
                    d.image.modelHeight / 2f - pageIndex * modelH) * sy
                rotate(d.image.rotation, androidx.compose.ui.geometry.Offset(fullCx, fullCy)) {
                    drawImage(
                        image = bmp,
                        dstOffset = IntOffset(rx.toInt(), (lt * sy).toInt()),
                        dstSize = IntSize(
                            (d.image.modelWidth * sx).toInt().coerceAtLeast(2),
                            ((lb - lt) * sy).toInt().coerceAtLeast(2)
                        )
                    )
                }
            }
        }
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
        // 選中字：跟墨同層序（字在墨上，與 InkCanvas 一致），跟隨移動變換，只畫歸屬本紙的。
        selTexts.forEach { ann ->
            if (ann.pageIndex != pageIndex) return@forEach
            textPaint.textSize = ann.fontSize * sy
            textPaint.color = ann.colorArgb
            val lines = ann.text.split("\n")
            val lineHeight = with(textPaint.fontMetrics) { -ascent + descent + leading }
            val dx = moveOffset.x * sx
            val dy = moveOffset.y * sy
            drawIntoCanvas { cvs ->
                lines.forEachIndexed { i, line ->
                    cvs.nativeCanvas.drawText(
                        line,
                        ann.modelX * sx + dx,
                        ann.modelY * sy + dy + i * lineHeight,
                        textPaint
                    )
                }
            }
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
