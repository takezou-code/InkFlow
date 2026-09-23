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
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.runtime.withFrameNanos
import com.vic.inkflow.util.DocLayout
import com.vic.inkflow.util.DocTransform
import com.vic.inkflow.util.MAX_FRAME_DELTA_PX
import com.vic.inkflow.util.PalmRejectionFilter
import com.vic.inkflow.util.TwoFingerArbitrator
import com.vic.inkflow.util.TwoFingerDecision
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
import androidx.compose.foundation.gestures.ScrollableDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.input.pointer.util.VelocityTracker
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
import kotlinx.coroutines.Job
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
    // 放手慣性：單一可取消 fling（scroll 互斥內跑，接管即殺，禁多重排隊——
    // 之前每幀 scope.launch scrollBy 的放手彈走就是反面教材）。
    val gestureScope = rememberCoroutineScope()
    var flingJob by remember { mutableStateOf<Job?>(null) }
    // 原生 fling 行為（跟單指捲動同一套 spline 衰減）：版本無關，不碰已退役的 decay API。
    val flingBehavior = ScrollableDefaults.flingBehavior()
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

    // 雙指全域手勢（紙上＋空白＋跨頁，單一仲裁器，同時是唯一的縮放入口）：
    // PAN → dx 寫 panOffsetX、dy 同步 dispatchRawDelta；PINCH → 寫 shared docZoom＋水平錨定。
    // 掛外層 Box：兩指分落上下頁時 centroid/span 仍是同一座標系，跨頁捏合/平移都能動
    // （之前 pinch 掛每頁 Box 內，跨頁雙指每頁只見 1 指 → 跨頁捏合永遠觸發不了）。
    // 同步施加、無協程排隊：放手即停（之前每幀 scope.launch scrollBy，放手後還在消化佇列＝彈走主因）。
    // 順序：twoFinger 先裝、blankPan 後裝——雙指接管時 consume 會取消單指拖，避免雙重施加。
    val twoFingerModifier = Modifier.pointerInput(Unit) {
        val arbitrator = TwoFingerArbitrator(touchSlopPx = viewConfiguration.touchSlop)
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var lastIds: Set<PointerId> = emptySet()
            var gestureActive = false // 雙指接管中（PAN 或 PINCH 鎖定後）：墨水走 pinchActive 棄筆
            var lastTwoFingerUptime = 0L // 上次見到雙指的事件時間（瞬斷寬限用）
            var wasSolo = false // 上一幀只剩一指：回來先重錨，單指段位移不算進雙指（會跳）
            var carryX = 0f // 封頂欠款：單幀超 96 的部分下幀先還（±300 上限），不斷流不丟失
            var carryY = 0f
            var sightUptime = 0L // 本輪起點時間（卡死 log 用）
            var sightLogged = false
            var lastShadowLog = 0L // 影子 log 節流（2 秒一次）
            while (true) {
                val event = awaitPointerEvent()
                val pressedAll = event.changes.filter { it.pressed }
                if (pressedAll.isEmpty()) {
                    arbitrator.reset()
                    lastIds = emptySet()
                    if (gestureActive) {
                        // 雙指無慣性：抓住紙，放手當幀定住（單手的滑走 blankPan 那條）。
                        viewModel.setPinchActive(false)
                        gestureActive = false
                        pdfViewModel.flushPendingRenders()
                    }
                    break
                }
                // 只算觸控手指：觸控筆書寫時不參與；手掌（大接觸面積）剔除——
                // 手掌靜止＋手指拖時，不過濾的話重心只走一半速度、間距亂變還會誤判 PINCH，
                // 就是半速＋亂縮放的來源。真雙指兩個都是小點，不過濾不受影響。
                // （計數定義見 isCountedFinger，與空白層共用，禁各寫各的。）
                val touch = pressedAll.filter { isCountedFinger(it) }
                if (touch.size >= 2) {
                    lastTwoFingerUptime = event.changes.maxOf { it.uptimeMillis }
                    val ids = touch.map { it.id }.toSet()
                    val cx = touch.sumOf { it.position.x.toDouble() }.toFloat() / touch.size
                    val cy = touch.sumOf { it.position.y.toDouble() }.toFloat() / touch.size
                    val span = (touch[0].position - touch[1].position).getDistance()
                    if (wasSolo) {
                        // 空窗回來：完整重錨（單指段丟掉）＋欠款清零。
                        arbitrator.rebaseline(cx, cy, span)
                        carryX = 0f
                        carryY = 0f
                        lastIds = ids
                        wasSolo = false
                        sightUptime = lastTwoFingerUptime
                        sightLogged = false
                    } else if (ids != lastIds) {
                        if (lastIds.isEmpty()) {
                            // 首見：完整錨定（起點必須定，否則從 (0,0) 起算必誤鎖）。
                            arbitrator.rebaseline(cx, cy, span)
                            sightUptime = lastTwoFingerUptime
                            sightLogged = false
                        } else {
                            // 加指/換指：只重錨 span 系（PAN 賽局不斷、不跳），欠款清零。
                            arbitrator.rebaselineSpan(span)
                            carryX = 0f
                            carryY = 0f
                        }
                        lastIds = ids
                    }
                    val decision = arbitrator.onFrame(cx, cy, span)
                    // 未定只攔新指：首指（down.id）放行不斷流（blankPan/原生繼續吃），
                    // 新指全攔（內層 InkCanvas 起不了筆）；鎖定後照舊全攔。
                    // 誤傷代價：雙指點按（本來就沒東西可點），可接受。
                    if (decision is TwoFingerDecision.Undecided) {
                        event.changes.filter { it.id != down.id }.forEach { it.consume() }
                    } else {
                        event.changes.forEach { it.consume() }
                    }
                    // 鎖定（PAN 或 PINCH）即立旗：墨水走同一 pinchActive 棄筆＋凍結，
                    // 兩指 PAN 時寫筆邊緣捲不再跟主列表打架（垂直跳頁主因）。禁另起旗子。
                    when (decision) {
                        is TwoFingerDecision.Pan -> {
                            if (!gestureActive) {
                                // 診斷（臨時）：手掌誤算成第二指會在寫字時觸發此行，看數據定案後刪。
                                // excluded＝被踢名單＋各自面積（面積案取證用）。
                                android.util.Log.d("InkFlowGesture", "twoFingerLock=PAN pressed=${pressedAll.size} palms=${palmTouchMajors.toMap()} excluded=${pressedAll.filterNot { isCountedFinger(it) }.map { it.id.value to palmTouchMajors[it.id.value.toInt()] }}")
                                viewModel.setPinchActive(true)
                                gestureActive = true
                                // 接管即殺：未跑完的慣性／舊動畫不許跟新手勢打架。
                                flingJob?.cancel()
                                flingJob = null
                                pdfViewModel.setRendersPaused(true)
                            }
                            // 診斷（臨時）：單幀超大 dy＝重心跳變（閃頁嫌疑），抓 ids/cx/cy。
                            if (kotlin.math.abs(decision.dy) > 150f) {
                                android.util.Log.d("InkFlowGesture", "panSpike dy=${decision.dy} ids=$ids cx=$cx cy=$cy")
                            }
                            // 封頂欠款下期還：仲裁器已封頂 96，超的部分記帳下幀先還
                            // （±300 上限），卡頓後 2~3 幀追上落點，不閃也不永久落後。
                            val wantX = decision.dx + carryX
                            val wantY = decision.dy + carryY
                            val useX = wantX.coerceIn(-MAX_FRAME_DELTA_PX, MAX_FRAME_DELTA_PX)
                            val useY = wantY.coerceIn(-MAX_FRAME_DELTA_PX, MAX_FRAME_DELTA_PX)
                            carryX = (wantX - useX).coerceIn(-300f, 300f)
                            carryY = (wantY - useY).coerceIn(-300f, 300f)
                            if (useX != 0f) {
                                viewModel.setPanOffsetX(clampPan(viewModel.panOffsetX.value + useX))
                            }
                            if (useY != 0f) {
                                mainListState.dispatchRawDelta(-useY)
                            }
                        }
                        is TwoFingerDecision.Pinch -> {
                            if (!gestureActive) {
                                // 診斷（臨時）：同上。
                                android.util.Log.d("InkFlowGesture", "twoFingerLock=PINCH pressed=${pressedAll.size} palms=${palmTouchMajors.toMap()} excluded=${pressedAll.filterNot { isCountedFinger(it) }.map { it.id.value to palmTouchMajors[it.id.value.toInt()] }}")
                                viewModel.setPinchActive(true)
                                gestureActive = true
                                // 接管即殺：同上。
                                flingJob?.cancel()
                                flingJob = null
                                pdfViewModel.setRendersPaused(true)
                            }
                            // 捏合重心漂移照吃：邊捏邊移時內容跟手，否則錨點丟失＝偏心感。
                            if (decision.dx != 0f) {
                                viewModel.setPanOffsetX(clampPan(viewModel.panOffsetX.value + decision.dx))
                            }
                            if (decision.dy != 0f) {
                                mainListState.dispatchRawDelta(-decision.dy)
                            }
                            if (decision.zoomFactor.isFinite() && !decision.zoomFactor.isNaN() && decision.zoomFactor > 0f && decision.zoomFactor != 1f) {
                                val old = docZoom
                                val new = (old * decision.zoomFactor).coerceIn(0.4f, 4f)
                                if (new.isFinite() && !new.isNaN() && new != old) {
                                    val ratio = new / old
                                    // 錨定在雙指中心 cx 下：內容點 = scrollX + cx - panX
                                    //（整列被 panOffsetX 平移過，必須扣掉；不扣＝平移後再捏就偏）。
                                    // 垂直見下方 cy 錨定（直接定狀態一步到位）。
                                    val panX = clampPan(viewModel.panOffsetX.value)
                                    val currentScrollX = hScrollState.value
                                    val maxScrollX = maxOf(0f, viewportWpx * (new - 1f))
                                    val targetScrollX = ((currentScrollX + cx - panX) * ratio - cx + panX)
                                        .coerceIn(0f, maxScrollX)
                                    viewModel.setDocZoom(new)
                                    val deltaX = targetScrollX - currentScrollX
                                    if (kotlin.math.abs(deltaX) > 0.5f) {
                                        hScrollState.dispatchRawDelta(deltaX)
                                    }
                                    // 垂直錨定 cy：uniform 紙高跟 zoom 線性走、縫/頂墊固定。
                                    // 直接定狀態（scrollToItem 同步）：dispatchRawDelta 會跟框架的
                                    // (index,offset) 原生保持雙重施加、每幀約 1.8 倍指數飛走（實測）。
                                    // 這裡把 cy 下的內容點換算成新版式的 (index,offset)，一步寫死，
                                    // 重組保持的就是目標本身，無伺服、無疊加。
                                    val paperHOld = paperHAtZoom(old)
                                    val paperHNew = paperHAtZoom(new)
                                    if (paperHOld > 0f && paperHNew > 0f) {
                                        val topPadPx = with(density) { 18.dp.toPx() }
                                        val itemFullOld = paperHOld + pageGapPx
                                        val scrollOld = topPadPx +
                                            mainListState.firstVisibleItemIndex * itemFullOld +
                                            mainListState.firstVisibleItemScrollOffset
                                        val relOld = (scrollOld + cy - topPadPx).coerceAtLeast(0f)
                                        val idx = (relOld / itemFullOld).toInt().coerceAtLeast(0)
                                        val fracOld = relOld - idx * itemFullOld
                                        val fracNew = if (fracOld <= paperHOld) {
                                            fracOld * paperHNew / paperHOld
                                        } else {
                                            // 落在縫裡：紙部縮放、縫部不動
                                            paperHNew + (fracOld - paperHOld)
                                        }
                                        val scrollNew = topPadPx + idx * (paperHNew + pageGapPx) + fracNew
                                        // S_new = P_new − cy：在新版式下分解成 (index, offset) 直接定狀態。
                                        val relNew = (scrollNew - cy - topPadPx).coerceAtLeast(0f)
                                        val itemFullNew = paperHNew + pageGapPx
                                        val totalItems = mainListState.layoutInfo.totalItemsCount
                                        val idxNew = (relNew / itemFullNew).toInt().coerceAtLeast(0)
                                            .coerceAtMost((totalItems - 1).coerceAtLeast(0))
                                        val offNew = (relNew - idxNew * itemFullNew).coerceAtLeast(0f).toInt()
                                        // 診斷（臨時）：驗證收斂 + request 是否同步（調用後立刻讀，相等＝同步）。
                                        android.util.Log.d("InkFlowPinch", "anchor2 old=$old new=$new cy=$cy idx=$idxNew off=$offNew first=${mainListState.firstVisibleItemIndex}+${mainListState.firstVisibleItemScrollOffset}")
                                        mainListState.requestScrollToItem(idxNew, offNew)
                                        android.util.Log.d("InkFlowPinch", "anchor2-post first=${mainListState.firstVisibleItemIndex}+${mainListState.firstVisibleItemScrollOffset}")
                                    }
                                }
                            }
                        }
                        else -> {
                            // 未定：已在上方預 consume，只等 slop 判決。
                            // 卡死取證：起點 500ms 後還未定，打一次（此時手指請繼續畫圈）。
                            if (!sightLogged && sightUptime > 0L &&
                                lastTwoFingerUptime - sightUptime > 500L
                            ) {
                                sightLogged = true
                                android.util.Log.d(
                                    "InkFlowGesture",
                                    "stuckRace ids=$ids majors=${touch.map { it.id.value to palmTouchMajors[it.id.value.toInt()] }} cancelled=${touch.map { it.id.value }.filter { it.toInt() in cancelledIds }}"
                                )
                            }
                        }
                    }
                } else {
                    // 瞬斷寬限：剩一指≠結束（抬指調整常見），400ms 內回來不斷流；
                    // 回來先重錨（wasSolo），單指段位移不算進雙指。超時或全放開才真正結束。
                    // 筆出現＝拿筆要寫了：不等寬限，直接重開（墨水一刻不等）。
                    val now = event.changes.maxOf { it.uptimeMillis }
                    wasSolo = true
                    if (event.changes.any { it.pressed && it.type == PointerType.Stylus }) {
                        arbitrator.reset()
                        lastIds = emptySet()
                        if (gestureActive) {
                            viewModel.setPinchActive(false)
                            gestureActive = false
                            pdfViewModel.flushPendingRenders()
                        }
                        break
                    }
                    // 影子取證：明明兩指壓著卻湊不齊 2 隻（被踢掉），2 秒打一次。
                    if (pressedAll.size >= 2 && now - lastShadowLog > 2000L) {
                        lastShadowLog = now
                        android.util.Log.d(
                            "InkFlowGesture",
                            "shadowed pressed=${pressedAll.map { it.id.value }} majors=${pressedAll.map { it.id.value to palmTouchMajors[it.id.value.toInt()] }}"
                        )
                    }
                    if (now - lastTwoFingerUptime > 400L) {
                        arbitrator.reset()
                        lastIds = emptySet()
                        if (gestureActive) {
                            viewModel.setPinchActive(false)
                            gestureActive = false
                            pdfViewModel.flushPendingRenders()
                        }
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
            // 起手殺舊 glide：甩完立刻再按住拖，不疊加不亂飛（跟雙指接管同規）。
            flingJob?.cancel()
            flingJob = null
            val bTracker = VelocityTracker()
            var bPoints = 0
            var bT0 = 0L
            var bCarry = Offset.Zero // 封頂欠款：同雙指，下幀先還
            var sawPinch = false // 這輩子見過雙指接管：放手不射（影子跟隨的 glide 是驚嚇不是慣性）
            while (true) {
                val event = awaitPointerEvent()
                // 第二根「手指」出現：整段手勢作廢，交給雙指修飾——手掌不算；
                // 筆出現也讓路（畫布不能在筆下漂移）。手掌貼著時不作廢，否則一動就死。
                val counted = event.changes.count { it.pressed && isCountedFinger(it) }
                val stylusDown = event.changes.any { it.pressed && it.type == PointerType.Stylus }
                if (counted > 1 || stylusDown) return@awaitEachGesture
                if (viewModel.pinchActive.value) sawPinch = true
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    // 放手慣性（單手）：品質門同舊制（點數≥3、跨度≥50ms），垃圾速度點不著火。
                    val vy = runCatching { bTracker.calculateVelocity().y }.getOrDefault(0f)
                    val span = if (bPoints > 0) change.uptimeMillis - bT0 else 0L
                    android.util.Log.d("InkFlowGesture", "flingCheckBlank vy=$vy points=$bPoints span=$span")
                    val minFling = viewConfiguration.minimumFlingVelocity * 3f
                    if (!sawPinch && vy.isFinite() && bPoints >= 3 && span >= 50L && kotlin.math.abs(vy) > minFling) {
                        flingJob?.cancel()
                        flingJob = gestureScope.launch {
                            mainListState.scroll {
                                with(flingBehavior) {
                                    performFling((-vy).coerceIn(-8000f, 8000f))
                                }
                            }
                        }
                    }
                    break // up/cancel：tap 原樣放過（上面沒 consume 的話）
                }
                bTracker.addPosition(change.uptimeMillis, change.position)
                if (bPoints == 0) bT0 = change.uptimeMillis
                bPoints++
                val delta = change.positionChange()
                // 單幀封頂＋欠款下期還：與雙指同理（見 MAX_FRAME_DELTA_PX），不斷流不丟失。
                val want = Offset(
                    (delta + bCarry).x.coerceIn(-MAX_FRAME_DELTA_PX, MAX_FRAME_DELTA_PX),
                    (delta + bCarry).y.coerceIn(-MAX_FRAME_DELTA_PX, MAX_FRAME_DELTA_PX)
                )
                bCarry = Offset(
                    (delta.x + bCarry.x - want.x).coerceIn(-300f, 300f),
                    (delta.y + bCarry.y - want.y).coerceIn(-300f, 300f)
                )
                if (want != Offset.Zero && !change.isConsumed) {
                    change.consume()
                    if (want.x != 0f) {
                        viewModel.setPanOffsetX(clampPan(viewModel.panOffsetX.value + want.x))
                    }
                    if (want.y != 0f) {
                        mainListState.dispatchRawDelta(-want.y)
                    }
                }
            }
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
