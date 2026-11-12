package com.vic.inkflow.ui

import com.vic.inkflow.ui.theme.ShapeSm

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
import com.vic.inkflow.util.ScrollJumpWatch
import com.vic.inkflow.util.TraceRecorder
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.clipToBounds
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 一次 flick 的慣性距離上限＝幾頁。
 *
 * 舊制是「一個視窗高」：縮放 0.4 時一頁 1826px、視窗 2136px，預算剛好一頁，
 * 甩一下走到頁尾就歸零、紙直接停住——「滑一頁之後慣性消失」就是它。
 * 三頁＝用力 flick 約兩頁多，再用力也只到三頁（仍足以擋「意外飛過十頁」）。
 */
private const val FLING_BUDGET_PAGES = 3f

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
    var viewportWpx by remember { mutableIntStateOf(0) }

    val density = LocalDensity.current
    // 單指慣性（空白單指放手 glide）：唯一合法的動量源。原生捲動已關，
    // 這條是唯一的 fling：接管即殺（新手勢/雙指鎖/筆），禁多重排隊。雙指放手即停（不變）。
    val gestureScope = rememberCoroutineScope()
    var flingJob by remember { mutableStateOf<Job?>(null) }
    // 慣性法醫：fling 被誰掐掉。只在 cancel=true 時寫進 FLING-end 的 by=。
    // 空白＝不是我們自己的三個取消點（Compose 捲動互斥鎖被搶 / composition 被拆）。
    var flingAbort by remember { mutableStateOf("") }
    // 跳頁稽核：主列表「內容座標」單次取樣跳掉大半頁 → 落 JUMP（正常滑動遠小於門檻）。
    // 搭配 ScrollWriters 的寫入者日誌，用時間戳就能判定「誰把它跳過去的」。
    val jumpWatch = remember { ScrollJumpWatch(jumpFraction = 0.5f) }
    // 樞紐鎖定種類（SINGLE_PAN 也要記）：跳頁發生時要知道當下是手勢中還是放手後。
    var hubLockedShown by remember { mutableStateOf<GestureStateMachine.Kind?>(null) }
    // 頁間隙 px：必須與下方 LazyColumn 的 Arrangement.spacedBy(18.dp) 一致（跨頁分段用）
    val pageGapPx = with(density) { 18.dp.toPx() }
    val bubbleGapPx = with(density) { 12.dp.toPx() }
    val bubbleSidePaddingPx = with(density) { 12.dp.toPx() }
    val bubbleTopSafePx = with(density) { 12.dp.toPx() }

    // 橫移狀態（空白區單指＋雙指全域寫入，放手停留、跨頁保持）：
    // **全文件只有這一個水平數值**。縮放只改變夾鉗範圍，不換寫入目標。
    //
    // 舊碼在 docZoom>1 時把位移寫進 hScrollState，兩個數值互不換算：
    // 100% 時可推 ±2325px，101% 時只剩 32px（突變 75 倍），而 100% 留下的
    // panOffsetX 在放大後仍被 .offset 套用 → 「推到一邊再放大就卡住偏一邊」、
    // 「放大後拖到邊界會彈」。單一數值＋連續夾鉗（panRangePx）根治這兩者。
    //
    // 用 fun 即時重算（讀 State delegate 即時值），不 remember，避免快照訂閱地雷。
    val panOffsetX by viewModel.panOffsetX.collectAsState()
    val sidePadPx = with(density) { 40.dp.toPx() }
    fun clampPanAt(z: Float, raw: Float): Float {
        val range = panRangePx(viewportWpx.toFloat(), z, sidePadPx)
        return raw.coerceIn(range.start, range.endInclusive)
    }
    fun clampPan(raw: Float): Float = clampPanAt(viewModel.docZoom.value, raw)
    fun isBlankX(x: Float): Boolean {
        // 算式與理由都在 paperEdgesInViewport（純函式，有回歸測試釘住）。
        val (paperLeft, paperRight) = paperEdgesInViewport(
            viewportWpx = viewportWpx.toFloat(),
            docZoom = viewModel.docZoom.value,
            sidePadPx = sidePadPx,
            panX = clampPan(viewModel.panOffsetX.value)
        )
        // 紙的實際位置＝置中＋當前橫移：推到底後空出來的地方整條都是活區，點哪都能拉回。
        return x < paperLeft || x > paperRight
    }
    val clampedPanX = clampPan(panOffsetX)
    // zoom/旋轉導致邊界縮小：只在 viewport/zoom 變化時收斂一次（直給，不用 spring 追移動目標）。
    // 不以 clampedPanX 為 key——手勢寫入的值出場已鉗制，放手後不再有外力寫回。
    LaunchedEffect(viewportWpx, docZoom, sidePadPx) {
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

    // 原生 fling 行為（spline 衰減）：只驅動 dispatch，不經原生手勢，無打架。
    // 外面再包一層距離上限（見 BoundedFlingBehavior），單位是**頁**，不是視窗。
    //
    // 舊制上限＝一個視窗高（2136px）。縮放 0.4 時一頁 1826px，於是預算剛好一頁：
    // 甩一下走到頁尾就歸零、紙直接停住——使用者回報的「滑一頁之後慣性消失」就是它。
    // 慣性的意義是「小力推、滑得遠」，上限只該擋「一次意外飛過十頁」，
    // 不該擋正常的一兩頁。三頁＝用力 flick 約兩頁多，再用力也只到三頁。
    val baseFlingBehavior = ScrollableDefaults.flingBehavior()
    val flingBehavior = remember(baseFlingBehavior) {
        BoundedFlingBehavior(baseFlingBehavior) {
            val info = mainListState.layoutInfo
            val pageFull = info.visibleItemsInfo.firstOrNull()?.size?.toFloat()
                ?: paperHAtZoom(viewModel.docZoom.value)
            pageFull * FLING_BUDGET_PAGES
        }
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
            // 單指平移的唯一分流點（hubShouldPanOnPaper）。樞紐與 InkCanvas 各自跑
            // pointerInput、彼此沒有握手，唯一能判「墨水是否已接手」的是 DOWN 的 isConsumed
            // ——InkCanvas 是子節點、Main pass 比樞紐早到，它 consume 過就代表這一指是它的。
            // 少了這一段就會雙寫：拖圖片時樞紐同時 dispatchRawDelta，頁面跟著捲。
            //
            // requireUnconsumed = false 仍要保留：DOWN 已被墨水 consume 時樞紐也必須看到那一指，
            // 否則第二指加入時樞紐湊不到雙指，pinch 競賽會少一個人。
            //
            // pointerInput(Unit) 只跑一次：必須讀 viewModel.inputMode.value 拿新鮮值，
            // 不吃捕獲的 by-delegate 快照（同 applyZoom 裡 docZoom 那條地雷）。
val allowSinglePan = startedBlank || fingerPanOnPaperAllowed(
                viewModel.inputMode.value,
                viewModel.selectedTool.value
            )
            val fedIds = mutableSetOf<Int>()
            var gestureActive = false // 雙指鎖定接管中（墨水讀 pinchActive 棄筆）
            var lockedKind: GestureStateMachine.Kind? = null
            var lastZoomFx = 0f
            var lastZoomFy = 0f
            var haveZoomFocus = false
            // 單指速度追蹤（放手 glide 用）：只追空白單指，紙上單指是墨水的不產慣性。
            val sTracker = VelocityTracker()
            var sPoints = 0
            var sT0 = 0L
            var endedKind: GestureStateMachine.Kind? = null

            fun startKind(kind: GestureStateMachine.Kind) {
                // 再按住即殺 glide（新起點不疊加不亂飛）。
                if (flingJob?.isActive == true) flingAbort = "gesture-start:${kind.name}"
                flingJob?.cancel()
                flingJob = null
                hubLockedShown = kind
                // SINGLE_PAN 不立 pinchActive 旗（空白單指舊制，紙上單指同制：筆一落下就 yield，
                // 兩者不可能重疊，無需棄筆旗）。但 lockedKind 一定要記，
                // 否則紙上單指鎖了也不 consume，事件還是漏回 Void。
                if (kind == GestureStateMachine.Kind.SINGLE_PAN) {
                    lockedKind = kind
                    haveZoomFocus = false
                    return
                }
                if (!gestureActive) {
                    viewModel.setPinchActive(true)
                    gestureActive = true
                    lockedKind = kind
                    pdfViewModel.setRendersPaused(true)
                } else if (lockedKind != kind) {
                    lockedKind = kind
                }
                haveZoomFocus = false
            }
            fun endAll() {
                if (gestureActive) {
                    viewModel.setPinchActive(false)
                    gestureActive = false
                    pdfViewModel.flushPendingRenders()
                }
                lockedKind = null
                hubLockedShown = null
                haveZoomFocus = false
            }
            // 水平位移唯一寫者（單指/兩指共用這個出口）。
            // 只有一個數值、一條連續夾鉗：100% 與放大後是同一條曲線，
            // 不再有「兩個狀態要交接」的斷點（見 panRangePx）。
            //
            // 符號：panOffsetX 是內容位移（正=紙往右），手指往右 dx>0 → +dx，跟手。
            fun applyHorizontalDelta(dx: Float) {
                if (dx == 0f || !dx.isFinite()) return
                viewModel.setPanOffsetX(clampPan(viewModel.panOffsetX.value + dx))
            }
            // 捏合施加：zoom 幀 anchor-set 直接定狀態；factor==1 幀跟隨平滑 focus 漂移
            //（舊碼捏合重心漂移照吃，行為保留，輸入換成平滑後的）。
            fun applyZoom(fx: Float, fy: Float, factor: Float) {
                if (!factor.isFinite() || factor.isNaN() || factor <= 0f) return
                if (factor == 1f) {
                    if (haveZoomFocus) {
                        val dx = fx - lastZoomFx
                        val dy = fy - lastZoomFy
                        // 走同一個出口：捏合重心漂移就是水平位移，跟單指同一條路。
                        if (dx != 0f) applyHorizontalDelta(dx)
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
                // 錨定在雙指中心 fx 下：**紙面座標**在縮放前後必須是同一個點。
                // 紙寬 = 視窗 × z − 40dp（左右各 20dp 墊），紙在視窗內置中＋panOffsetX。
                // 直接把「左緣也要跟著縮放」的等式解開（panAfterZoom），比用 ratio 近似
                // 精確：那 40dp 墊不隨縮放長大，用 ratio 會在邊緣差幾個 mm。
                val oldPanX = viewModel.panOffsetX.value
                val newPanX = clampPanAt(
                    new,
                    panAfterZoom(
                        panX = oldPanX,
                        docZoomOld = old,
                        docZoomNew = new,
                        focusX = fx,
                        viewportWpx = viewportWpx.toFloat(),
                        sidePadPx = sidePadPx
                    )
                )
                viewModel.setDocZoom(new)
                if (newPanX != oldPanX) viewModel.setPanOffsetX(newPanX)
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
                    // 捏合的垂直錨定是唯一允許「一步定態」的程式化寫入；**跳一頁等級**的位移要留痕，
                    // 否則「捏合時忽然跳一頁」只會看到結果、看不到是誰寫的。逐幀微調不記（太吵）。
                    val pageSized = kotlin.math.abs(scrollNew - scrollOld) >= itemFullOld * 0.5f
                    if (idxNew != mainListState.firstVisibleItemIndex || pageSized) {
                        ScrollWriters.log(
                            "PINCH-anchor",
                            "z=${old}->${new} idx=$idxNew off=$offNew" +
                                " cur=(${mainListState.firstVisibleItemIndex},${mainListState.firstVisibleItemScrollOffset})" +
                                " scrollOld=${scrollOld.toInt()} scrollNew=${scrollNew.toInt()} fy=${fy.toInt()}"
                        )
                    }
                    mainListState.requestScrollToItem(idxNew, offNew)
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
                        if (o.dx != 0f) applyHorizontalDelta(o.dx)
                        if (o.dy != 0f) {
                            mainListState.dispatchRawDelta(-o.dy)
                        }
                    }
                    is GestureStateMachine.Output.ZoomBy -> applyZoom(o.focusX, o.focusY, o.factor)
                    is GestureStateMachine.Output.GestureEnd -> {
                        endedKind = o.kind
                        endAll()
                        tracer.flush("END-${o.kind}").forEach { android.util.Log.d("InkFlowTrace", it) }
                    }
                    GestureStateMachine.Output.YieldToStylus,
                    GestureStateMachine.Output.AbortGesture -> {
                        fedIds.clear() // 下幀還壓著的手指當新起點重吃（舊 reset 語義）
                        endAll()
                        tracer.flush("$o").forEach { android.util.Log.d("InkFlowTrace", it) }
                    }
                    null -> {}
                }
            }
            try {
                run {
                    // 新起點殺舊 glide：甩完立刻再按住，不疊加不亂飛。
                    if (flingJob?.isActive == true) flingAbort = "new-down"
                    flingJob?.cancel()
                    flingJob = null
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
                                isPalmPointer(down.id.value), allowSinglePan, down.uptimeMillis
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
                                    isPalmPointer(c.id.value), allowSinglePan, now
                                )
                            )
                        }
                    }
                    if (pressed.isEmpty()) {
                        // 單指放手 glide：只許 SINGLE_PAN（空白單指），雙指/筆/取消一律定住。
                        // 品質門（點數≥3、跨度≥50ms、>3 倍 minFling）：垃圾速度點不著火。
                        if (endedKind == GestureStateMachine.Kind.SINGLE_PAN) {
                            val vy = runCatching { sTracker.calculateVelocity().y }.getOrDefault(0f)
                            val span = if (sPoints > 0) now - sT0 else 0L
                            val minFling = viewConfiguration.minimumFlingVelocity * 3f
                            if (vy.isFinite() && sPoints >= 3 && span >= 50L &&
                                kotlin.math.abs(vy) > minFling
                            ) {
                                if (flingJob?.isActive == true) flingAbort = "refling"
                                flingJob?.cancel()
                                // 慣性法醫線：起點位置＋初速＋取樣品質；結束時再補一行實際走了多少。
                                // 「忽然跳一頁」若是這條造成的，兩行一對就看得出來（dz ≫ 一頁就是它）。
                                val startIdx = mainListState.firstVisibleItemIndex
                                val startOff = mainListState.firstVisibleItemScrollOffset
                                // 頁高優先用「實際量到」的 item 高＋縫：跟 ScrollJumpWatch 同一把尺，
                                // 兩邊的 dz 才對得上（公式估的和量測的差幾個 % 就會讓 pages 失準）。
                                val measured = mainListState.layoutInfo.visibleItemsInfo
                                    .firstOrNull()?.size?.toFloat() ?: 0f
                                val full = (if (measured > 0f) measured else paperHAtZoom(viewModel.docZoom.value)) +
                                    pageGapPx
                                val viewportPx = mainListState.layoutInfo.let {
                                    (it.viewportEndOffset - it.viewportStartOffset).toFloat()
                                }
                                ScrollWriters.log(
                                    "FLING-start",
                                    "vy=${vy.toInt()} clamp=${(-vy).coerceIn(-8000f, 8000f).toInt()}" +
                                        " budget=${(full * FLING_BUDGET_PAGES).toInt()}" +
                                        " pts=$sPoints span=$span idx=$startIdx off=$startOff" +
                                        " full=${full.toInt()} viewport=${viewportPx.toInt()}" +
                                        " z=${viewModel.docZoom.value}"
                                )
                                flingAbort = ""
                                flingJob = gestureScope.launch {
                                    val t0 = android.os.SystemClock.uptimeMillis()
                                    var completed = false
                                    var abortReason = ""
                                    try {
                                        mainListState.scroll {
                                            with(flingBehavior) {
                                                performFling((-vy).coerceIn(-8000f, 8000f))
                                            }
                                        }
                                        completed = true
                                    } catch (ce: CancellationException) {
                                        // 慣性不是「走完預算」而是「被掐掉」時，這裡留下兇手。
                                        // 空字串＝不是我們三個取消點之一（多半是 Compose 的
                                        // scroll 互斥鎖被別的寫者搶走，或 composition 被拆）。
                                        abortReason = flingAbort.ifEmpty { "external" }
                                        throw ce
                                    } finally {
                                        val endIdx = mainListState.firstVisibleItemIndex
                                        val endOff = mainListState.firstVisibleItemScrollOffset
                                        val dz = (endIdx - startIdx) * full + (endOff - startOff)
                                        ScrollWriters.log(
                                            "FLING-end",
                                            "dz=${dz.toInt()} pages=${"%.2f".format(dz / full)}" +
                                                " ms=${android.os.SystemClock.uptimeMillis() - t0}" +
                                                " idx=$endIdx off=$endOff cancel=${!completed}" +
                                                (if (abortReason.isNotEmpty()) " by=$abortReason" else "")
                                        )
                                    }
                                }
                            }
                        }
                        endAll()
                        break
                    }
                    val snap = pressed
                        .filter { it.id.value.toInt() in fedIds }
                        .associate { it.id.value.toInt() to Pt(it.position.x, it.position.y) }
                    val o1 = machine.tick(now)
                    apply(o1)
                    val o2 = machine.pointerMove(snap, now)
                    // ── 讓路判斷放在「施加位移之前」，不是 DOWN 當下 ──
                    // 實測 log（InkFlowProbe）：樞紐的 DOWN 比 InkCanvas 的分支判定早 17ms，
                    // DOWN 當下讀 down.isConsumed 永遠是 false → 樞紐以為沒人接手而照捲，
                    // 於是「拖圖片時頁面跟著捲」。
                    // PointerInputChange 是同一個實例（同一 Main pass 派同一個 PointerEvent），
                    // consume() 直接改物件欄位，所以這裡（墨水已跑完）再讀才會看到 true。
                    val inkTookIt = hubShouldYieldToInk(
                        hubGotDelta = o2 is GestureStateMachine.Output.ScrollBy,
                        inkConsumed = down.isConsumed
                    )
                    if (inkTookIt) {
                        // 墨水接手這一指：整段放棄（不施加這一幀位移、不吃速度取樣）。
                        apply(machine.cancelAll())
                        endAll()
                        return@awaitEachGesture
                    }
                    apply(o2)
                    // 單指速度取樣（glide 用）：單指＋准平移才記，雙指幀不污染速度。
                    // 用算數手指數（觸控且非手掌），不用 pressed.size——
                    // 手掌貼著時 size==2 會誤判成多指，慣性就整個不動。
                    if (allowSinglePan) {
                        val only = pressed.filter { isCountedFinger(it) }
                        if (only.size == 1) {
                            val c = only[0]
                            if (c.id.value.toInt() in fedIds) {
                                sTracker.addPosition(c.uptimeMillis, c.position)
                                if (sPoints == 0) sT0 = c.uptimeMillis
                                sPoints++
                            }
                        }
                    }
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
                    if (startedBlank || lockedKind != null || gestureActive) {
                        pressed.forEach { it.consume() }
                    } else {
                        // 起點紙上未定（手指模式，紙是墨水的）：只攔新指，首指放行不斷流。
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
    // 氣泡錨定 region 而非可編輯物：空白區圈選也有框，AI/提取照樣出；複製/刪除才看 hasEditable。
    val showSelectionBubble = activeTool == Tool.LASSO && hasRegionSnapshot && !isExtracting

    // pdfViewModel and LaunchedEffect(uri) are owned by TabletEditorScreen
    val pageCount by pdfViewModel.pageCount.collectAsState()

    // 卷動跟著走：作用頁 = 可見面積最大的那頁（不是 firstVisible）。
    // 之前用 firstVisibleItemIndex，第 2 頁要「完整出現、把第 1 頁完全頂掉」才會激活，
    // 半露出的頁只能看不能寫。改最大可見面積後，露出一半以上就能直接寫。
    // （側欄置中由 EditorScreen 跟著做）
    LaunchedEffect(mainListState, pageCount) {
        // 跳頁稽核基準重設：這個 effect 重啟＝頁數變了/換文件，舊座標不可比。
        jumpWatch.reset()
        // P0 快滑偵測狀態（effect 作用域內常駐，跨排放累積；只寫 pdfViewModel 快旗，不碰捲動寫者）。
        var lastFastT = 0L
        var lastFastPos = -1L
        var lastFastFirst = 0
        var lastFastLast = -1
        var lastFastBest = -1
        var settleJob: Job? = null
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
            MainListSnap(
                best = best, first = first, last = last,
                // 位置一律連 offset 一起取：只看 index 會漏掉「同一頁內瞬移」。
                offset = mainListState.firstVisibleItemScrollOffset,
                // 頁高用實測 item 高度＋縫；量不到就 0（稽核自己會忽略）。
                itemFull = info.visibleItemsInfo.firstOrNull()?.size?.let {
                    (it + pageGapPx).toInt()
                } ?: 0
            )
        }.collect { snap ->
            val idx = snap.best
            val first = snap.first
            val last = snap.last
            // P0：首可見項位置估速度。>6000px/s（約 3 頁/秒）或單幀跳 ≥3 頁即立快旗；
            // 高速期間只投縮圖（LOD 掠過），高清排隊憑票作廢。
            val now = android.os.SystemClock.uptimeMillis()
            // 跳頁稽核：一次取樣內內容座標跳掉半頁以上 → JUMP（正常滑動差一個量級）。
            if (first != Int.MAX_VALUE) {
                jumpWatch.sample(now, first, snap.offset, snap.itemFull.toFloat())?.let { line ->
                    android.util.Log.w(
                        "InkFlowScroll",
                        "$line hub=${hubLockedShown ?: "-"} pinch=${viewModel.pinchActive.value}" +
                            " z=${viewModel.docZoom.value} best=$idx first=$first last=$last pages=$pageCount"
                    )
                }
            }
            val pos = first.toLong() * 10_000_000L + mainListState.firstVisibleItemScrollOffset.toLong()
            if (lastFastT > 0 && lastFastPos >= 0) {
                val dt = now - lastFastT
                if (dt > 0) {
                    val v = kotlin.math.abs(pos - lastFastPos) * 1000f / dt
                    if (!pdfViewModel.isScrollingFast.value &&
                        first != Int.MAX_VALUE && lastFastFirst != Int.MAX_VALUE &&
                        (v > 6000f || kotlin.math.abs(first - lastFastFirst) >= 3)
                    ) {
                        pdfViewModel.setScrollingFast(true)
                    }
                }
            }
            lastFastT = now
            lastFastPos = pos
            lastFastFirst = first
            lastFastLast = last
            lastFastBest = idx
            settleJob?.cancel()
            // M2 慢滑跟手清：只有快滑才凍結高清（縮圖掠過）；慢滑已組成的頁
            // 即時渲（waiter 只看快旗），手指推到哪清到哪。落定再定序補齊 5 頁。
            if (pdfViewModel.isScrollingFast.value) pdfViewModel.holdRenders()
            // 可視範圍預取：未露臉的鄰頁先查好，快取當初始值，第一幀就有墨
            if (first != Int.MAX_VALUE && last != Int.MIN_VALUE) {
                // v6 分級渲染：只有可見頁吃滿倍率，窗外頁維持低清（見 setRenderPlan）。
                // 這裡就是「可見」的定義來源——不餵的話倍率範圍停在最後一次縮放的舊值，
                // 捲到新頁時那頁會拿到窗外倍率而糊掉。
                pdfViewModel.setVisiblePageRange(first..last)
                viewModel.prefetchPages(first, last)
                // 鄰頁只預熱縮圖（便宜，240px 封頂），高清等落定按序補。
                for (p in first - 1..last + 1) pdfViewModel.prefetchThumbnail(p)
            }
            // 頁鎖期間（跨頁手勢中）：忽略自動捲帶來的頁面切換，避免中途換頁斷筆；
            // 全活頁下各頁本來就活著，手勢結束也無需激活跳轉。
            if (!viewModel.isPageLocked() && idx in 0 until pageCount) {
                onScrollPage(idx)
            }
            // 落定：0.5s 無新排放才開渲。只渲 5 頁：當前頁±2，
            // 順序本頁→下一頁→上一頁→下兩頁→上兩頁（3 → 3,4,2,5,1）。
            settleJob = launch {
                delay(500)
                val c = lastFastBest
                val f = lastFastFirst
                val l = lastFastLast
                pdfViewModel.setScrollingFast(false)
                pdfViewModel.releaseRenders()
                if (c in 0 until pageCount && f != Int.MAX_VALUE && l >= 0 && f <= l) {
                    pdfViewModel.ensureHighQualityAround(c, radius = 2)
                    pdfViewModel.flushPendingRenders(f..l)
                    // 有界：只留「當前±2 ∪ 可見窗」，其餘無訂閱高清釋放（滑回來自動補渲）。
                    val keepFirst = minOf(f, c - 2)
                    val keepLast = maxOf(l, c + 2)
                    pdfViewModel.trimBitmapFlowsToWindow(keepFirst..keepLast, margin = 0)
                }
            }
        }
    }

    // 渲染刻度跟著**紙寬**（視窗寬 × docZoom），不是跟著視窗寬。
    //
    // 舊版傳 `viewportWpx / w`，而縮放後紙比視窗寬（listWdp = viewportWpx × max(docZoom,1)），
    // 於是倍率永遠停在視窗那一格：放大時墨是向量重畫所以銳利，PDF 卻只能被拉大變糊，
    // 同一頁上兩層清晰度打架。現在傳紙寬，讓倍率真的追上縮放。
    //
    // `docZoom` 必須進 key：少了它，縮放根本不會觸發重算。放進去之後它同時就是
    // 防抖器——捏合每一幀都重新 key 這個 effect，300ms 的 delay 從頭計時，
    // 所以只在**落定後**升級重渲，捏合途中不換圖。
    //
    // Fix2b: 防抖 300ms — AI 面板開合/拖曳時寬度連變，只在落定後重渲。
    val renderEpoch by pdfViewModel.renderEpoch.collectAsState()
    val firstSize by pdfViewModel.firstPageSize.collectAsState()
    LaunchedEffect(viewportWpx, docZoom, firstSize) {
        val w = firstSize?.first ?: 595f
        if (viewportWpx > 0 && w > 0f) {
            delay(300)
            pdfViewModel.setRenderPlan(
                paperWidthPx = viewportWpx.toFloat() * maxOf(docZoom, 1f),
                pageWidthPt = w
            )
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
                // 裁切原本由 horizontalScroll 提供；現在水平位移只剩一個 .offset，
                // 這層負責把放大後超出視窗的紙裁掉（純繪製，不吃觸控）。
                .clipToBounds(),
            contentAlignment = Alignment.Center
        ) {
            LazyColumn(
                state = mainListState,
                modifier = Modifier
                    // requiredWidth：放大後列表比視窗寬，必須忽略父層 maxWidth 才會真的變寬
                    // （一般 width() 會被夾回視窗寬，整條縮放版式就失效）。
                    .requiredWidth(listWdp.coerceAtLeast(1.dp))
                    .fillMaxHeight()
                    // 橫移：整列水平位移（唯一水平數值，offset 直給，無大圖層、無 spring）
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
            // P0 快滑 LOD：縮圖常駐訂閱，高清未到先頂著顯示，落定自動換上（同幀 Crossfade 接住）。
            val thumbFlow = remember(index) { pdfViewModel.getPageThumbnail(index) }
            val pageThumb by thumbFlow.collectAsState()
            val shownBitmap = pageBitmap ?: pageThumb
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
            if (shownBitmap == null) {
                Box(
                    modifier = Modifier.fillMaxSize()
                        .background(Color.White)
                )
            }
            // PDF static layer (bottom) — crossfade between page bitmaps
            Crossfade(
                targetState = shownBitmap,
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
            // 背景紙紋（ruled / grid / dot）統一由 InkCanvas 的 onDrawBehind 繪製——
            // 這裡原本有一份一模一樣的 Canvas，雙重繪製（點格紙一幀 ×2 ≈ 3.4 萬次 drawCircle）。
            // 已刪除，紙紋只剩一處。
            // M1 全活頁：InkCanvas 只吃本頁熱流，不讀作用頁流；
            // 新鮮度門隨單活頁一起退役（每頁數據自洽，無跨頁鬼影可言）。
            InkCanvas(
                modifier = Modifier.fillMaxSize(),
                viewModel = viewModel,
                pdfViewModel = pdfViewModel,
                documentUri = documentUri,
                pageIndex = index,
                strokes = pageStrokes,
                pendingIds = pageData.pendingIds,
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
    // 重點色：跟全域主題同一變數（primary），不在這裡寫死靛紫
    val accentColor = MaterialTheme.colorScheme.primary
    val selectedIds by viewModel.selectedImageAnnotationIds.collectAsState()
    // 選中圖：與墨同待遇畫在紙上層（跨頁拖曳全程可見；之前 overlay 只畫墨，圖被留在紙內）。
    val selImages = remember(selectedIds) { viewModel.selectedImagesNow() }
    val selTextIds by viewModel.selectedTextAnnotationIds.collectAsState()
    val selTexts = remember(selTextIds) { viewModel.selectedTextsNow() }
    val dragImg by viewModel.imageDragPreview.collectAsState()
    if (preview.isEmpty() && selImages.isEmpty() && selTexts.isEmpty() && dragImg == null) return
    val context = LocalContext.current
    // 把手尺寸（dp 基準，1 畫布 px 恆＝1 螢幕 px，不做 docZoom 補償）。跟 InkCanvas 那層一致。
    // 必須在 Composable 層算：LocalDensity 不能在 drawWithCache 的繪製層取。
    val overlayDensity = LocalDensity.current
    val overlayHandleVisualPx = with(overlayDensity) {
        com.vic.inkflow.ui.theme.Handles.VisualRadius.toPx()
    }
    val overlayHandleHaloPx = with(overlayDensity) {
        com.vic.inkflow.ui.theme.Handles.HaloRadius.toPx()
    }
    // 圖片走共享預算制快取（1024 封頂夠拖曳預覽用；與 InkCanvas/側欄同一份快取，不重複解碼）。
    val imageCacheVersion = com.vic.inkflow.util.ImageAnnotationCache.version
    androidx.compose.runtime.LaunchedEffect(selImages, dragImg?.image?.uri, imageCacheVersion) {
        (selImages.map { it.uri } + listOfNotNull(dragImg?.image?.uri)).distinct().forEach { uri ->
            com.vic.inkflow.util.ImageAnnotationCache.getOrDecode(context, uri, 1024)
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
            val bmp = com.vic.inkflow.util.ImageAnnotationCache.getFor(ann.uri, 1024) ?: return@forEach
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
            val bmp = com.vic.inkflow.util.ImageAnnotationCache.getFor(d.image.uri, 1024)
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
                    drawShapeOnCanvas(cvs, swp.stroke, swp.points, tintColor = accentColor)
                } else if (path != null) {
                    drawPathOnCanvas(
                        cvs, path, accentColor,
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
            val boxW = ann.boxWidth?.takeIf { it > 0f }
            val lines = if (boxW != null) wrapTextLines(ann.text, ann.fontSize, boxW)
                        else ann.text.split("\n")
            val lineHeight = with(textPaint.fontMetrics) { -ascent + descent + leading }
            val dx = moveOffset.x * sx
            val dy = moveOffset.y * sy
            drawIntoCanvas { cvs ->
                lines.forEachIndexed { i, line ->
                    val off = if (boxW != null)
                        textAlignOffsetModel(measureTextWidth(line, ann.fontSize), boxW, ann.alignment) * sx else 0f
                    cvs.nativeCanvas.drawText(
                        line,
                        ann.modelX * sx + dx + off,
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
                accent = accentColor,
                showHandles = true,
                dashPhase = 0f,
                animateDash = false,
                visualRadiusPx = overlayHandleVisualPx,
                haloRadiusPx = overlayHandleHaloPx
            )
        }
    }
}

/**
 * 主列表版面快照（`snapshotFlow` 用）：只看「位置」與「頁高」兩件事，
 * 剛好是判定跳頁需要的全部（`ScrollJumpWatch`）。
 */
private data class MainListSnap(
    val best: Int,
    val first: Int,
    val last: Int,
    val offset: Int,
    val itemFull: Int,
)

/**
 * 主列表垂直位置的**寫入者日誌**（tag `InkFlowScroll`，release 可見）。
 *
 * 為什麼要有它：`dispatchRawDelta`／`requestScrollToItem`／`animateScrollToItem`／放手慣性
 * 都能移動紙，出了「忽然跳一頁」時光看畫面分不出是誰。統一在這裡落一行帶時間戳的意圖，
 * 跟 `ScrollJumpWatch` 的 `JUMP` 行對時間即可歸因（時間戳同一時鐘：uptimeMillis）。
 */
internal object ScrollWriters {
    fun log(writer: String, detail: String = "") {
        android.util.Log.w("InkFlowScroll", "$writer t=${android.os.SystemClock.uptimeMillis()} $detail")
    }
}

/**
 * 紙面單指平移准入（adapter 閘門，狀態機只聽這裡）。
 *
 * 觸控筆模式（STYLUS_ONLY／PALM_REJECTION）下 InkCanvas 對任何 Touch 聯絡
 * 直接 return 不 consume（InkCanvas.kt STYLUS_ONLY 分支），紙上單指就沒人接手了。
 * 這裡開門把它交回樞紐，避免事件沉進 Void（LazyColumn userScrollEnabled=false、
 * 無 nestedScroll、水平方向只剩樞紐自己寫 panOffsetX → 沒有下游消費者）。
 *
 * 手指模式（FREE）原則上門關著——紙上單指是墨水的。
 * **唯一例外＝IMAGE 工具**：它是唯一不在紙上畫東西的工具（只做選取/移動/縮放/旋轉），
 * 紙面空白處沒有東西要畫，讓給捲動沒有衝突。搭配 InkCanvas 端 IMAGE 工具不預先
 * consume（見該檔 activeTool != Tool.IMAGE 的條件），單指在空白紙上就能滑頁；
 * 單指落在圖片本體／把手上時那些分支各自 consume，樞紐靠 isConsumed 讓路。
 *
 * 這個例外不可外溢到畫線/橡皮/套索/文字/形狀，否則手指在紙上畫線會變成捲頁
 * （FingerPanAdmissionTest 有逐工具釘住）。
 *
 * 不看 docZoom：放大後行為必須跟 100% 一樣（放大中 isBlankX 恆 false＝整個視窗都算紙上，
 * 若這裡再關門就是「放大後單指全死」）。水平位移不分縮放層級，一律走 applyHorizontalDelta，
 * 樞紐是唯一寫者，不會跟原生搶。
 */
internal fun fingerPanOnPaperAllowed(inputMode: InputMode, activeTool: Tool): Boolean =
    inputMode != InputMode.FREE || activeTool == Tool.IMAGE

/**
 * 樞紐在這一幀要不要放棄這一指的單指平移（讓路給墨水）。
 *
 * **兩個寫者必須一主一主**——樞紐與 InkCanvas 各自獨立跑 `pointerInput`，彼此沒有握手。
 * 若同一根手指兩邊都平移，畫面會出現「拖圖片時頁面跟著捲」的雙寫。
 * 唯一仲裁依據是 [PointerInputChange.isConsumed]：InkCanvas 是子節點、Main pass 同派
 * 同一個 `PointerEvent`，`awaitFirstDown` 拿到的是**同一個 `PointerInputChange` 實例**，
 * 墨水 `consume()` 直接改該物件欄位 → 樞紐讀得到。
 *
 * ⚠️ **必須延後到施加位移前呼叫**：兩個 coroutine 的執行先後沒有保證。實測 log 顯示樞紐的
 * DOWN 比 InkCanvas 的分支判定早 17ms，在 DOWN 當下讀 `isConsumed` 必定是 false。
 * 到 `pointerMove` 這裡時墨水已跑完，讀到的才是真值。
 *
 * @param hubGotDelta 樞紐這一幀算出了位移（有東西要施加，仲裁才有意義）
 * @param inkConsumed 這一指的 DOWN 目前是否已被 InkCanvas consume
 */
internal fun hubShouldYieldToInk(hubGotDelta: Boolean, inkConsumed: Boolean): Boolean =
    hubGotDelta && inkConsumed

/**
 * 紙在視窗座標下的左右邊界（純函數）。
 *
 * 版式（見 Workspace 的 requiredWidth → offset 修飾符鏈）：
 *   列表寬 L  = 視窗 × max(z, 1)
 *   置中偏移 = (視窗 − L) / 2             （contentAlignment = Center）
 *   內容左緣 = 置中偏移 + panOffsetX      （單一水平數值，見 panRangePx）
 *   紙寬     = L × min(z, 1) − 40dp = 視窗 × z − 40dp（Surface 在 20dp 內墊之內）
 *
 * 所以紙邊界＝「置中」＋「panOffsetX」，只有一項。舊版還要多算 hScroll，
 * 漏一項就會「放大後判定不出紙外、單指推不動」——那一坑修了兩輪。
 *
 * 紙寬刻意與 [panRangePx]／[panAfterZoom] 用同一個式子，避免「夾鉗算一種寬、
 * 命中算另一種寬」的舊病復發。
 */
internal fun paperEdgesInViewport(
    viewportWpx: Float,
    docZoom: Float,
    sidePadPx: Float,
    panX: Float
): Pair<Float, Float> {
    if (!(viewportWpx > 0f)) return 0f to 0f
    val z = if (docZoom.isFinite() && docZoom > 0f) docZoom else 1f
    val paperW = (viewportWpx * z - sidePadPx).coerceAtLeast(0f)
    val left = (viewportWpx - paperW) / 2f + panX
    return left to (left + paperW)
}

// ── 水平位移：單一數值 + 連續夾鉗 ──────────────────────────────────────────
//
// 舊版把水平位移分成兩個狀態：100% 用 panOffsetX（±2325px），放大用 hScrollState
// （0..視窗×(z−1)）。兩者不換算，於是：
//   • z=1.00 → ±2325px，z=1.01 → 32px（75 倍突變，100~110% 橫向幾乎是死的）
//   • 100% 推到一邊（panOffsetX=+2325）再放大，殘留值仍被套用 → 紙卡在一邊拉不回
// 現在只有一個數值，夾鉗是 z 的連續函數，跨 100% 沒有斷點。

/**
 * panOffsetX 的合法範圍（對稱）。
 *
 * @param sidePadPx 紙左右各留 20dp 的墊（40dp 合計），與 Surface 的 padding 同源。
 *
 * 兩個成分：
 *  - [traverse]：紙比視窗寬時「從左緣捲到右緣」需要的位移 =（紙寬 − 視窗）/2。
 *    z ≤ 1 時紙比視窗窄，此項為 0。
 *  - [slack]：允許把紙推出邊界多少（保證至少 1/4 紙還在畫面上）。沿用 100% 的舊手感：
 *    0.75 ×（視窗 × min(z,1) − 40dp）。z ≥ 1 時是常數，所以 z→1⁺ 不收縮。
 *
 * 兩者相加即「捲動全程 + 兩端的超出額度」，且 z=1 前後連續：
 * z = 0.999 與 1.001 的範圍都約等於 ±slack。
 */
internal fun panRangePx(
    viewportWpx: Float,
    docZoom: Float,
    sidePadPx: Float
): ClosedFloatingPointRange<Float> {
    if (!(viewportWpx > 0f) || !docZoom.isFinite() || docZoom <= 0f) return 0f..0f
    val paperW = (viewportWpx * docZoom - sidePadPx).coerceAtLeast(0f)
    val traverse = ((paperW - viewportWpx) / 2f).coerceAtLeast(0f)
    val narrowW = (viewportWpx * minOf(docZoom, 1f) - sidePadPx).coerceAtLeast(0f)
    val slack = narrowW * 0.75f
    val max = traverse + slack
    return -max..max
}

/**
 * 縮放時的水平錨定：回傳能讓 [focusX] 底下的紙面點留在原位的 panOffsetX。
 *
 * 紙面座標 m ∝（螢幕 x − 紙左緣）/ 紙寬。令縮放前後 m 相等：
 *   左緣_new = focusX − (focusX − 左緣_old) × 紙寬_new / 紙寬_old
 * 再換回 panOffsetX。用紙寬比（而不是 z 比）才精確——40dp 的墊不隨縮放長大。
 */
internal fun panAfterZoom(
    panX: Float,
    docZoomOld: Float,
    docZoomNew: Float,
    focusX: Float,
    viewportWpx: Float,
    sidePadPx: Float
): Float {
    if (!(viewportWpx > 0f)) return panX
    val paperOld = (viewportWpx * docZoomOld - sidePadPx).coerceAtLeast(1f)
    val paperNew = (viewportWpx * docZoomNew - sidePadPx).coerceAtLeast(1f)
    val leftOld = (viewportWpx - paperOld) / 2f + panX
    val leftNew = focusX - (focusX - leftOld) * (paperNew / paperOld)
    return leftNew - (viewportWpx - paperNew) / 2f
}
