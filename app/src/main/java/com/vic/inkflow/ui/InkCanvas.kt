package com.vic.inkflow.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitDragOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import com.vic.inkflow.ui.theme.ShapeMd
import com.vic.inkflow.ui.theme.GlassSelectionInk
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.ExperimentalComposeUiApi
import com.vic.inkflow.data.ImageAnnotation
import com.vic.inkflow.data.Point
import com.vic.inkflow.data.Stroke
import com.vic.inkflow.data.StrokeWithSamples
import com.vic.inkflow.data.TextAnnotation
import com.vic.inkflow.util.InkWidthModel
import com.vic.inkflow.util.PalmRejectionFilter
import com.vic.inkflow.util.TouchEventLogger
import com.vic.inkflow.util.EnvelopeUtils
import com.vic.inkflow.util.StrokePoint
import com.vic.inkflow.util.DocLayout
import com.vic.inkflow.util.DocTransform
import androidx.compose.ui.graphics.FilterQuality
import com.vic.inkflow.util.StrokeTransformUtils
import com.vic.inkflow.ui.selection.LassoGeometry
import com.vic.inkflow.ui.selection.SelectionGestureHost
import com.vic.inkflow.ui.selection.drawSelectionFrameOnPage
import com.vic.inkflow.ui.selection.handleSelectionDown
import com.vic.inkflow.ui.selection.selectionFrameRectOnPage
import java.util.UUID
import android.view.MotionEvent
import androidx.compose.ui.input.pointer.pointerInteropFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// ── 橡皮擦預覽配色(紙張橡皮:石灰階,不用警報紅)──────────────────────────────
/** veil 的暖石色(alpha 在使用處指定,營造「擦掉、留白」的負空間感) */
private val ERASER_VEIL = Color(0xFF7A7267)
/** 頭環暗線+中心點(近黑暖灰),配合白暈,深淺紙面都看得到 */
private val ERASER_RING = Color(0xFF3F3A33)
private const val ERASER_VEIL_OUTER_ALPHA = 0.16f
private const val ERASER_VEIL_INNER_ALPHA = 0.34f
private const val ERASER_INNER_WIDTH_RATIO = 1.15f
private const val DUST_DUR_MS = 550L
private const val MAX_DUST = 180

/**
 * 向量層最多同時畫幾筆「位圖帳上還沒收錄」的筆。
 *
 * 正常情況只有 1–2 筆（剛提筆、等 swap）。上限只是防禦：若背景烘焙連續失敗，
 * 位圖會一直停在舊版本，不設限的話向量層會膨脹成每幀掃全頁、重畫所有筆。
 */
private const val INK_OVERLAY_MAX = 32

/** 一顆橡皮屑(canvas px) */
private data class DustParticle(
    val x: Float, val y: Float,
    val vx: Float, val vy: Float,
    val radius: Float, val t0Nanos: Long
)

// ── 輸入啟發式（快滑擦除/手掌日誌）見 InkCanvasInput.kt ──────────────────────

// ── 連貫畫布 helpers 見 CrossPageCanvas.kt ───────────────────────────────────

/**
 * 圖片提交後的「落定等待」：記住這次 commit 應該讓 DB 變成什麼樣子，
 * 等熱流回報的實體真的等於預期值，才讓畫面改用 DB 值。
 *
 * 為什麼需要：commit 是 `launch(Dispatchers.IO)` 非同步寫 DB，而繪製層拿到的 DB 值
 * 更新與預覽位移歸零**不同步**。若靠 `LaunchedEffect` 去歸零預覽，DB 更新那一幀繪製層
 * 可能還讀到殘留的預覽 → 畫出「新位置 ＋ 舊位移」瞬間偏移再修正＝閃一下。
 *
 * 正確做法是**繪製當下即時判斷**（見繪製層的 `settledImg` / `settledTxt`）：
 * DB 已落定就直接用 DB 值（不疊預覽），還沒落定就疊預覽位移。零時序依賴。
 */
internal data class PendingImageSettle(
    val id: String,
    val modelX: Float,
    val modelY: Float,
    val modelWidth: Float,
    val modelHeight: Float,
    val rotation: Float,
) {
    fun matches(ann: ImageAnnotation): Boolean =
        ann.id == id &&
            near(ann.modelX, modelX) && near(ann.modelY, modelY) &&
            near(ann.modelWidth, modelWidth) && near(ann.modelHeight, modelHeight) &&
            near(ann.rotation, rotation)
}

/** 文字提交後的落定等待，理由同 [PendingImageSettle]。 */
internal data class PendingTextSettle(
    val id: String,
    val modelX: Float,
    val modelY: Float,
    val fontSize: Float,
    /** 框寬（model pt）；null = 舊資料（無框）。 */
    val boxWidth: Float? = null,
    /**
     * 只看 id、不比座標。
     *
     * 整條文字流一起搬時，實際落點會被夾在紙面內（`commitTextAnnotationMove`），
     * 跟這裡預測的 `modelX/modelY` 不一定相同；用座標比對會「永遠落不定」，
     * 預覽位移就永遠掛在畫面上。大小/框寬仍然要比，才不會提前清掉重排中的預覽。
     */
    val anyPosition: Boolean = false,
) {
    fun matches(ann: TextAnnotation): Boolean =
        ann.id == id &&
            (anyPosition || (near(ann.modelX, modelX) && near(ann.modelY, modelY))) &&
            near(ann.fontSize, fontSize) &&
            near(ann.boxWidth ?: 0f, boxWidth ?: 0f)
}

/**
 * 預覽位移在繪製當下該不該疊上去。
 *
 * @param pending 這次 commit 記下的預期值（null = 沒在等落定，例如拖曳中或已清掉）
 * @param current DB／熱流此刻回報的實體
 * @return true = 還沒落定，要疊預覽位移；false = 已落定，繪製直接用 DB 值
 */
internal fun <T> shouldApplyPreview(
    pending: Any?,
    current: T?,
    matches: (Any?, T) -> Boolean,
): Boolean {
    if (pending == null || current == null) return true
    return !matches(pending, current)
}

/**
 * 提筆時的「交接快照」：這一筆墨的向量直接烙在提筆當下。
 *
 * 為什麼需要：提筆後頁流（Room → 合併為 `pageDataFlow`）需要一幀才能把這筆吐給畫布，
 * 而活路徑已經讓位／已清、快取位圖還沒烙——那一幀這筆墨**沒有任何層在畫**。
 * 實機 log 證明每次提筆都會精確產生一行 `overlay=0`（見 docs/specs/pitfalls.md）。
 * 頁流排空幾幀都無所謂——有快照就一定有東西畫。
 */
private data class LiftedStrokeSnapshot(
    /** 屬於「本紙」的提交段 id；snapshot 只畫這些。 */
    val ids: Set<String>,
    /** 向量（canvas 像素座標），與活路徑同一份點列生成。 */
    val path: Path,
    val color: Color,
    val isHighlighter: Boolean,
    /** 烙下來的時刻（uptimeMillis）；超過 TTL 自動丟棄。 */
    val atMs: Long,
) {
    /**
     * 這一筆是否「真的在頁流裡出現過」。
     *
     * 刪除判定不能只看「現在不在 strokes 裡」：頁流在交接完成後仍可能排空一幀
     * （多條 DB flow 競速，實機 log `overlay=0`），那一幀正是快照要補的。
     * 只有「曾經出現過、之後又不見」才是真的被刪除／復原，才准立刻丟快照。
     */
    var seenInFlow: Boolean = false
}

/** 浮動比較。DB 走 Float 落盤（REAL），model 座標算完再存會有微小誤差，
 * 用整數容差比對避免「差 0.0001 永遠不落定」→ 預覽卡住不歸零。
 */
private fun near(a: Float, b: Float): Boolean = abs(a - b) < 0.01f

@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
fun InkCanvas(
    modifier: Modifier,
    viewModel: EditorViewModel,
    pdfViewModel: PdfViewModel,
    documentUri: String,
    /** 本頁在文件中的索引（跨頁分段/換頁歸屬用）。 */
    pageIndex: Int = 0,
    /** 本頁筆跡（Workspace 熱流傳入）：畫布只認自己的紙，不讀作用頁流。 */
    strokes: List<StrokeWithSamples> = emptyList(),
    /** 本頁還在樂觀層（DB 未回報）的筆跡 id：這幾筆走向量層，不進位圖快取。 */
    pendingIds: Set<String> = emptySet(),
    /** 本頁文字註解（同上）。 */
    texts: List<TextAnnotation> = emptyList(),
    /** 本頁圖片註解（同上）。 */
    images: List<ImageAnnotation> = emptyList(),
    /** 頁間隙 px（Workspace 的 Arrangement.spacedBy，需與列表一致）。 */
    pageGapPx: Float = 0f,
    /** 邊緣自動捲：手指拖出紙上下界時回傳期望捲動量 px（正=往後頁）；回傳實際捲動量。 */
    onEdgeAutoScroll: (Float) -> Float = { _ -> 0f }
) {
    // 全活頁：筆/字/圖吃傳入的本頁資料（Workspace 熱流），不再訂閱作用頁流；
    // 工具/顏色/設定等全域態照舊。
    val committedStrokes = strokes
    val textAnnotations = texts
    val imageAnnotations = images
    // 圈選只有一份狀態：選取內容、框、拖曳草稿、commit preview 全在這個物件裡。
    val selectionState by viewModel.selectionState.collectAsState()
    val commitPreview = selectionState.commitPreview
    val activeTool by viewModel.selectedTool.collectAsState()
    val selectedColor by viewModel.selectedColor.collectAsState()
    // 重點色：跟全域主題同一變數（primary），畫布裡不再寫死靛紫
    val accentColor = MaterialTheme.colorScheme.primary
    val strokeWidth by viewModel.strokeWidth.collectAsState()
    val eraserRadiusModel by viewModel.eraserRadius.collectAsState()
    // ── 橡皮擦特效狀態(橡皮屑灰石粒 + 快滑擦除視覺接管)──────────────────
    val dustFx = remember { mutableStateListOf<DustParticle>() }
    // 快滑擦除觸發後＝這個手勢就是橡皮擦:視覺、dust、鄰頁共享預覽全部走橡皮擦管線
    var eraseVisualActive by remember { mutableStateOf(false) }
    // draw-phase 訂閱的時間戳:只有特效活著時有人寫它,畫布才逐幀重繪
    var fxTickNanos by remember { mutableLongStateOf(System.nanoTime()) }
    val fxActive = dustFx.isNotEmpty()
    val selectedShapeSubType by viewModel.selectedShapeSubType.collectAsState()
    val selectedLassoSubType by viewModel.selectedLassoSubType.collectAsState()
    val inputMode by viewModel.inputMode.collectAsState()
    val quickSwipeEraserEnabled by viewModel.quickSwipeEraserEnabled.collectAsState()
    val strokeSpeedSensitivity by viewModel.strokeSpeedSensitivity.collectAsState()
    val widthResponsiveness by viewModel.widthResponsiveness.collectAsState()
    val touchCalEnabled by viewModel.touchCalEnabled.collectAsState()
    val touchCalDxDp by viewModel.touchCalDxDp.collectAsState()
    val touchCalDyDp by viewModel.touchCalDyDp.collectAsState()
    // 套索選中的圖／字（與墨同等待遇；TEXT／IMAGE 工具私選 id 是另一套，不管）。
    // key 用 items（拖曳時不變）：只有選取內容真的換了才重算 id 集合。
    val selectedImageAnnotationIds = remember(selectionState.items) { selectionState.items.imageIds }
    val vmSelectedTextIds = remember(selectionState.items) { selectionState.items.textIds }
    val paperStyle by viewModel.paperStyle.collectAsState()
    // 雙指縮放進行中：各畫筆迴圈見此即棄筆（由 Workspace 仲裁器寫入）
    val pinchActive by viewModel.pinchActive.collectAsState()
    // F1：鄰頁共享預覽（src 紙走直接路徑，這裡只讀分給本頁的段）。
    val sharedPreview by viewModel.inFlightPreview.collectAsState()
    val sharedSegs = remember(sharedPreview, pageIndex) {
        sharedPreview?.segments?.get(pageIndex).orEmpty()
    }
    // 連貫畫布：跨頁參數 refs（進長駐協程，不進 pointerInput key，手勢不被重啟打斷）
    val pageCount by pdfViewModel.pageCount.collectAsState()
    val pageIndexRef = rememberUpdatedState(pageIndex)
    val pageGapPxRef = rememberUpdatedState(pageGapPx)
    val pageCountRef = rememberUpdatedState(pageCount)
    val onEdgeAutoScrollRef = rememberUpdatedState(onEdgeAutoScroll)
    // 拖曳 overlay 接手時，紙內選取預覽讓位（同像素只畫一次，蟳光筆疊色會變深）。
    // 只讓「歸屬紙」讓位：跨頁選取的其他紙仍要畫自己的那一段，否則拖曳中整段消失。
    val dragPreviewActive by viewModel.dragPreviewActive.collectAsState()
    val ownDragOverlayActive = dragPreviewActive && selectionState.page == pageIndex
    // 套索虛線動畫按需組成：無選取時不跑 choreographer，省常駐喚醒；靜模式凍結
    val needLassoAnim = activeTool == Tool.LASSO && selectionState.items.strokes.isNotEmpty() && !LocalQuietMode.current
    val lassoDashPhase = if (needLassoAnim) {
        val lassoFrameTransition = rememberInfiniteTransition(label = "lasso-frame")
        lassoFrameTransition.animateFloat(
            initialValue = 0f,
            targetValue = 20f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "lasso-dash-phase"
        ).value
    } else 0f

    val density = LocalDensity.current
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    // Active path for freehand/lasso/eraser
    val activePath = remember { Path() }
    val activeEnvelopePath = remember { Path() }
    var activePathVersion by remember { mutableIntStateOf(0) }
    val currentPathPoints = remember { mutableStateListOf<StrokePoint>() }

    // Shape live-preview anchors (canvas pixel space)
    var activeShapeStart by remember { mutableStateOf<Offset?>(null) }
    var activeShapeEnd   by remember { mutableStateOf<Offset?>(null) }
    // F3：提筆交接——本紙提交段 id 等資料流出現才清活路徑（2s 兜底），消滅提筆閃一下。
    var bridgingIds by remember { mutableStateOf<Set<String>?>(null) }
    // 手勢世代號：交接的收尾只准作用在「產生這次交接的那個手勢」上。
    // 少了這個守衛，下一筆已經落筆、交接 effect 才跑完，會把**新筆**的活路徑 reset 掉
    //（筆頭那段消失再從後面接上＝機率性閃爍，見 docs/specs/pitfalls.md）。
    var strokeGestureSeq by remember { mutableIntStateOf(0) }
    var bridgingSeq by remember { mutableIntStateOf(-1) }
    // 交接快照：提筆當幀現場烙一張向量。頁流（Room→合併流）需要一幀才能把墨
    // 吐給畫布——那一幀向量層是空的、活路徑已清＝「提筆閃一下」的實際成因。
    var liftedSnapshot by remember { mutableStateOf<LiftedStrokeSnapshot?>(null) }
    // 提筆快照：最後一次提交的筆跡 id。它**不烙進封頂位圖快取**，直接以向量畫在快取圖之上。
    // 為什麼需要：已落庫的墨是點陣化的（cacheCap=3072，比 2880px 寬的平板還小 → 縮小再放大），
    // 提筆那一刻從「活的向量」換成「糊的位圖」＝肉眼看到的閃一下／微微位移。
    // 只保最後一筆：下一筆提交時它自然被增量髒區烙進位圖，成本＝一筆的 bounds。
    var hotStrokeId by remember { mutableStateOf<String?>(null) }
    /**
     * 目前這張墨跡位圖**真的裝了哪些筆**（不含高亮、預覽、樂觀層、熱筆）。
     *
     * 為什麼需要這本帳（2026-11-15，實機 53ms 閃爍）：熱筆刻意不烙進位圖（螢光筆 alpha
     * 疊兩層會偏暗、位圖封頂會糊一級），所以「熱筆換人」時舊熱筆只能等下一次烘焙 swap
     * 才回到位圖。舊寫法在熱筆換人的那一幀就把它從向量層放掉 → 位圖裡沒有、向量層也沒有，
     * 中間 30–60ms 那筆是空的＝使用者看到的「上一個筆畫閃一下」。
     *
     * 不變式：**不在 [bakedIds] 裡的筆一律用向量層畫**。它只在位圖真的換上時更新，
     * 所以「舊熱筆退出向量層」與「含它的位圖換上」是同一瞬間，不存在空窗。
     */
    var bakedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // LASSO ???????????????????????????????????????????????
    // ??????????????????(entering / leaving)?????? bounds??? dirty???
    // ????????????????????????????????????
    var lastSelectionExclude by remember { mutableStateOf<Set<String>>(emptySet()) }
    val committedIds = remember(committedStrokes) { committedStrokes.map { it.stroke.id }.toSet() }
    LaunchedEffect(bridgingIds, committedStrokes) {
        val ids = bridgingIds ?: return@LaunchedEffect
        val seq = bridgingSeq
        // 一個都還沒到：等 DB 落定（或 2s 兜底防寫失敗殘影），再清不遲
        if (ids.none { it in committedIds }) delay(2000)
        // 交接期間使用者已經又落筆（或這已經不是同一批交接）：這條活路徑屬於新手勢，不准碰。
        // 這裡不能用「bridgingIds == null 就代表交接結束」判斷——新手勢在 DOWN 就先把它設 null，
        // 而 effect 可能已經跑過 suspension，接著才執行到 reset。
        if (seq != strokeGestureSeq || bridgingIds !== ids) {
            android.util.Log.w(
                "InkFlowDiag",
                "HANDOFF skip seq=$seq now=$strokeGestureSeq ids=${ids.size}"
            )
            return@LaunchedEffect
        }
        activePath.reset()
        activeEnvelopePath.reset()
        currentPathPoints.clear()
        activePathVersion++
        viewModel.publishInFlight(null)
        bridgingIds = null
        bridgingSeq = -1
    }

    // Inline text editor (replaces the 新增文字 dialog): NEW at a canvas pos, or EDIT an existing id
    var inlineTextNewPos by remember { mutableStateOf<Offset?>(null) }
    var inlineTextEditId by remember { mutableStateOf<String?>(null) }
    var inlineTextValue  by remember { mutableStateOf("") }
    var inlineTextColor  by remember { mutableStateOf(selectedColor) }
    val keyboardController   = LocalSoftwareKeyboardController.current
    val inlineFocusRequester = remember { FocusRequester() }
    // Default text size ≈ 20sp in canvas pixels (old dialog passed a fixed 14px, too small to read)
    val defaultTextFontPx = with(density) { 20.sp.toPx() }
    // Refs for the pointer-input coroutine (which outlives recompositions)
    val inlineTextValueRef  = rememberUpdatedState(inlineTextValue)
    val inlineTextNewPosRef = rememberUpdatedState(inlineTextNewPos)
    val inlineTextEditIdRef = rememberUpdatedState(inlineTextEditId)
    val inlineTextColorRef  = rememberUpdatedState(inlineTextColor)
    val selectedColorRef    = rememberUpdatedState(selectedColor)
    val keyboardRef         = rememberUpdatedState(keyboardController)

    /** Commits (or cancels when blank) the inline editor. Safe to call from composition. */
    fun commitInlineText() {
        val v      = inlineTextValue
        val newPos = inlineTextNewPos
        val editId = inlineTextEditId
        inlineTextNewPos = null
        inlineTextEditId = null
        inlineTextValue  = ""
        keyboardController?.hide()
        if (v.isBlank()) return
        if (editId != null) viewModel.commitTextAnnotationContent(editId, v)
        else if (newPos != null) viewModel.addTextAnnotation(
            v, newPos.x, newPos.y, defaultTextFontPx, inlineTextColor,
            targetPage = pageIndex
        )
    }

    // Canvas pixel size (updated via onSizeChanged, used for hit-testing in pointer input)
    var canvasPixelSize by remember { mutableStateOf(Size.Zero) }
    val canvasPixelSizeState = rememberUpdatedState(canvasPixelSize)

    // 手指落筆校正（副廠電容筆專用）：refs 進長駐協程，不進 pointerInput key，
    // 設定頁調整不中斷正在畫的手勢
    val touchCalEnabledRef = rememberUpdatedState(touchCalEnabled)
    val touchCalDxRef = rememberUpdatedState(touchCalDxDp)
    val touchCalDyRef = rememberUpdatedState(touchCalDyDp)

    // Text annotation interactive selection / move / resize state
    var selectedTextAnnotationId by remember { mutableStateOf<String?>(null) }
    var textMoveDelta by remember { mutableStateOf(Offset.Zero) }
    var textFontSizeDelta by remember { mutableFloatStateOf(0f) }
    // 拖曳「寬度把手」時的框寬預覽（model pt）；落定後歸零（見 PendingTextSettle）。
    var textBoxWidthDelta by remember { mutableFloatStateOf(0f) }

    // Latest snapshot of text annotations for use inside pointer-input coroutines
    val textAnnotationsRef = rememberUpdatedState(textAnnotations)
    val selectedTextIdRef  = rememberUpdatedState(selectedTextAnnotationId)

    // Image annotation interactive selection / move / resize state
    // Resize is uniform (aspect locked): a scale about the opposite-corner anchor.
    var selectedImageAnnotationId by remember { mutableStateOf<String?>(null) }
    var imageMovePreview   by remember { mutableStateOf(Offset.Zero) }
    var imageResizeScale   by remember { mutableFloatStateOf(1f) }
    var imageResizeAnchor  by remember { mutableStateOf<Offset?>(null) }
    // M5: in-flight rotation delta in degrees (0 = none); committed value lives in the entity.
    var imageRotatePreview by remember { mutableFloatStateOf(0f) }

    // ── 提交後的「落定等待」（防放手瞬間閃爍）────────────────────────────────
    // 四種提交（圖片移動/縮放/旋轉、文字移動/縮放）全是 launch(Dispatchers.IO) 寫 DB，
    // 而預覽位移的歸零是同步的 → 提交後到 DB 回報前那幾幀，繪製會用「舊值 + 0」，
    // 圖片先閃回原位、DB 更新後再跳到新位置（實機回報：閃一下）。
    //
    // 這裡記住「什麼值算落定」，等 DB 真的回報對上了才清預覽（見下方 LaunchedEffect）。
    var pendingImageSettle by remember { mutableStateOf<PendingImageSettle?>(null) }
    var pendingTextSettle by remember { mutableStateOf<PendingTextSettle?>(null) }
    // 繪製層（drawWithCache.onDrawBehind）需要讀最新值。它讀的是 CapturedValue，
    // 直接改 mutableStateOf 的屬性在某些組態下會讀到快照（AGENTS 快照訂閱地雷），
    // 所以這裡用 rememberUpdatedState 保持「永遠指向最新 StateFlow 值」的參考。
    val pendingImageSettleRef = rememberUpdatedState(pendingImageSettle)
    val pendingTextSettleRef = rememberUpdatedState(pendingTextSettle)

    // imageAnnotations / textAnnotations 是 Workspace 傳入的熱流值，DB 一更新就換新 List
    // → 這個 effect 會重跑，屆時比對預期值即可判斷是否落定。
    //
    // ⚠️ 這裡**只清狀態、不負責決定繪製對不對**——繪製層自己即時比對（見繪製層的 `settled`）。
    // 若靠這個 effect 去歸零預覽，DB 更新那一幀 effect 可能還沒跑到，繪製會疊上殘留預覽
    // → 偏移一下才修正＝閃爍。所以「清」只是收尾，真正的閃爍防護在繪製層。
    LaunchedEffect(pendingImageSettle, pendingTextSettle, imageAnnotations, textAnnotations) {
        val img = pendingImageSettle
        if (img != null) {
            val now = imageAnnotations.firstOrNull { it.id == img.id }
            if (now != null && img.matches(now)) {
                imageMovePreview = Offset.Zero
                imageResizeScale = 1f
                imageResizeAnchor = null
                imageRotatePreview = 0f
                pendingImageSettle = null
            }
        }
        val txt = pendingTextSettle
        if (txt != null) {
            val now = textAnnotations.firstOrNull { it.id == txt.id }
            if (now != null && txt.matches(now)) {
                textMoveDelta = Offset.Zero
                textFontSizeDelta = 0f
                textBoxWidthDelta = 0f
                pendingTextSettle = null
            }
        }
    }

// ── 選取把手尺寸（dp → 畫布 px，直接 1:1）──────────────────────────────
// 命中半徑 24dp → 蟳幕上 48dp 觸控框（Material 最低標準）。舊碼寫死 24 物理px，
// 在 density 2.75 的平板上只剩 17.5dp，必須瞄準才抓得到。
// 不做 docZoom 補償：紙放大是 layout 變大、不是像素縮放（畫布無 graphicsLayer），
// 1 畫布 px 恆＝1 蟳幕 px，所以任何縮放下把手都該是同一個蟳幕大小。
    val handleHitRadiusPx = with(density) {
        com.vic.inkflow.ui.theme.Handles.TouchRadius.toPx()
    }
    val handleVisualRadiusPx = with(density) {
        com.vic.inkflow.ui.theme.Handles.VisualRadius.toPx()
    }
    val handleHaloRadiusPx = with(density) {
        com.vic.inkflow.ui.theme.Handles.HaloRadius.toPx()
    }
    val rotHandleGapPx = with(density) {
        com.vic.inkflow.ui.theme.Handles.RotGap.toPx()
    }

    // 按住哪顆把手（畫布座標）→ 該顆放大回饋 + 框線加粗。null = 沒按住。
    var pressedHandleCenter by remember { mutableStateOf<Offset?>(null) }
    // 命中框放到 48dp 後小圖的四角會重疊、旋轉把手會蓋到角，必須用「最近優先」
    // 而不是舊碼的固定順序（旋轉 → indexOfFirst），否則重疊時會穩定抓錯把手。
    fun handleHit(point: Offset, centers: List<Offset>, rotIndex: Int = -1): Int =
        nearestHandleIndex(point, centers, handleHitRadiusPx, preferLastTies = rotIndex < 0)

    // Latest snapshot of image annotations for use inside pointer-input coroutines
    val imageAnnotationsRef    = rememberUpdatedState(imageAnnotations)
    val selectedImageIdRef     = rememberUpdatedState(selectedImageAnnotationId)
    val selectionStateRef = rememberUpdatedState(selectionState)
    val isSelectionTransforming = selectionState.isTransforming
    // 全活頁：每紙只畫歸屬本紙的選取（跨紙選取各紙畫各的）。
    // key 用 items 而不是整個 state：拖曳時每幀只改 transform，路徑包絡不該跟著重建。
    val ownPageSelection = remember(selectionState.items, pageIndex) {
        selectionState.items.forPage(pageIndex)
    }
    val ownSelectedStrokes = ownPageSelection.strokes
    // drawWithCache factory 的選取訂閱源：selection 任何變動（建立／commit／取消）→ 快照實例改變。
    // 注意：選取狀態不進 inkStamp（純取消沒有 DB 寫入也沒有頁流重發），factory 不感知就永遠不重跑，
    // 排除集合過渡的擦除／補回不會執行 → 墨跡不動／憑空消失（使用者實報「等下一筆才位移」）。
    val selectionStampRef = rememberUpdatedState(ownSelectedStrokes)
    // 本紙框與變換：渲染與手勢共用同一份換算（見 SelectionOverlay）。
    val selectionTransformAnchor = remember(selectionState, pageIndex) {
        selectionState.anchorFor(pageIndex)
    }
    val selectedPathData = remember(ownSelectedStrokes) {
        ownSelectedStrokes.map { swp ->
            SelectedStrokeRenderData(
                strokeWithPoints = swp,
                path = if (swp.stroke.shapeType == null) swp.points.toComposePath() else null
            )
        }
    }
    // 圈選中的圖片：繪製一律吃選取快照（items），不讀 DB。
    // 提交後 transform 先歸零、DB 才寫回來，那幾幀若讀 DB 就是「先縮回去、再跳回新尺寸」
    // ＝使用者實測的縮放閃爍（2026-11-16）。快照在提交當下就已經是新位置／新尺寸。
    val selectedImageSnapshots = remember(ownPageSelection.images) {
        ownPageSelection.images.associateBy { it.id }
    }

    // 筆跡幾何快取：普通 Map，只在 drawWithCache 建構區讀寫。
    // 不用 State 容器 → 提交只觸發一次重建（之前 StateMap 寫入造成第二次失效）。
    val committedPathCache = remember { mutableMapOf<String, CachedStrokePath>() }
    // 向量層（樂觀筆／熱筆）的包絡路徑快取：刻意與 committedPathCache 分開——
    // 這一層靠 overlayIds 進出淘汰，不參與髒區判定；混用會讓「退場的那一筆」
    // 因為路徑快取命中而不再補畫進位圖，整筆憑空消失。
    val overlayPathCache = remember { mutableMapOf<String, CachedStrokePath>() }
    // 形狀上一幀 bounds：形狀走快照覆蓋（同 id 新 bounds），髒區需要舊 bounds。
    // 普通 Map，同上只在建構區讀寫。
    val lastShapeBounds = remember { mutableMapOf<String, Rect>() }

    // P0-B：畫布不再讀全域 version——draw 直接訂閱 ImageAnnotationCache.states 的 per-URI 標記
    // 文字共用 Paint + 分行快取：繪製時只改字號顏色，不再 new
    val textPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }
    val textLines = remember(textAnnotations) {
        textAnnotations.associate { ann ->
            val bw = ann.boxWidth?.takeIf { it > 0f }
            ann.id to if (bw != null) wrapTextLines(ann.text, ann.fontSize, bw) else ann.text.split("\n")
        }
    }
    // 蟳光筆作用中預覽共用 Paint：每幀只換色，不再 new
    val hlPreviewPaint = remember {
        Paint().apply {
            style = PaintingStyle.Fill
            blendMode = BlendMode.Multiply
        }
    }
    // 靜態虛線只建一次（形狀預覽用；選取框已統一走液態玻璃共用繪製）
    val dashPreview = remember { PathEffect.dashPathEffect(floatArrayOf(10f, 10f)) }
    // 點格紙背景瓦片：tile + shader 只隨「步距 px」重建，不進 Compose 狀態（否則每幀 1.7 萬次 drawCircle）。
    val dotTileCache = remember {
        object {
            var bmp: android.graphics.Bitmap? = null
            var paint: android.graphics.Paint? = null
            var key: String = ""
        }
    }

    // 插圖選取：EditorScreen 插完圖後丟 id 過來，由「持有該 id 的那一頁」接走並選中。
    // 取走即請 ViewModel 清空請求 → 不會因為頁面被 dispose／重組而重播。
    // imageAnnotations 一起當 key：DB 寫入是異步的，請求可能先到、註解後到。
    val pendingImageSelectId by viewModel.pendingImageSelectId.collectAsState()
    LaunchedEffect(pendingImageSelectId, imageAnnotations) {
        val wantedId = pendingImageSelectId ?: return@LaunchedEffect
        if (imageAnnotations.none { it.id == wantedId }) return@LaunchedEffect
        selectedImageAnnotationId = wantedId
        imageMovePreview = Offset.Zero
        imageResizeScale = 1f
        imageResizeAnchor = null
        imageRotatePreview = 0f
        viewModel.consumeImageSelectRequest()
    }

    // 切工具時收尾選取狀態。插圖不再由畫布接觸觸發（舊註解寫「Open gallery whenever
    // IMAGE tool is activated」但這裡從來沒有開圖庫，是從初始 commit 就存在的過期誤導）。
    LaunchedEffect(activeTool) {
        if (activeTool != Tool.TEXT) {
            if (inlineTextNewPos != null || inlineTextEditId != null) commitInlineText()
            selectedTextAnnotationId = null
            textMoveDelta = Offset.Zero
            textFontSizeDelta = 0f
            textBoxWidthDelta = 0f
            pendingTextSettle = null
        }
        if (activeTool != Tool.IMAGE) {
            selectedImageAnnotationId = null
            imageMovePreview = Offset.Zero
            imageResizeScale = 1f
            imageResizeAnchor = null
            imageRotatePreview = 0f
            pendingImageSettle = null
        }
    }

    // P0-B：draw 缺圖即派發 decode（bucket 依顯示長邊），collector 逐筆在 IO 解碼
    val decodeRequests = remember { kotlinx.coroutines.channels.Channel<Pair<String, Int>>(kotlinx.coroutines.channels.Channel.UNLIMITED) }
    LaunchedEffect(Unit) {
        for ((uri, px) in decodeRequests) {
            com.vic.inkflow.util.ImageAnnotationCache.getOrDecode(context, uri, px)
        }
    }

    // Raw MotionEvent metrics captured via pointerInteropFilter (before Compose pipeline).
    // pointerInteropFilter fires on ACTION_DOWN and stores contact info for the pointerInput block.
    var lastTouchMajorPx by remember { mutableFloatStateOf(0f) }
    var lastTouchMinorPx by remember { mutableFloatStateOf(0f) }
    var lastToolMajorPx  by remember { mutableFloatStateOf(0f) }
    var lastNativeToolType by remember { mutableIntStateOf(0) }
    var lastPointerCount by remember { mutableIntStateOf(1) }
    var stylusButtonPressed by remember { mutableStateOf(false) }
    // 原始筆軸（tilt/orientation/pressure），餵 TouchEventLogger 的手掌辨識診斷。
    var lastTiltDeg by remember { mutableFloatStateOf(-1f) }
    var lastOrientationDeg by remember { mutableFloatStateOf(-1f) }
    var lastAxisPressure by remember { mutableFloatStateOf(-1f) }
    // MotionEvent pointer ID → getTouchMajor(). Updated for every pointer down event.
    // Lets awaitEachGesture identify the stylus among simultaneous palm+stylus contacts.
    val pointerTouchMajors = remember { mutableStateMapOf<Int, Float>() }

    // ---- Modifier chain ----

    // 手勢凍結槽：draw 階段讀寫普通物件（非 State），不觸發重組
    val pinchReuse = remember { object { var bmp: ImageBitmap? = null } }
    // 背景重烘管線（P0-A）：drawWithCache 發現「原生快取圖不在／比例不對」時不阻塞主執行緒，
    // 改送一筆 conflate 的重烘請求到 Channel；LaunchedEffect 收集後在 Default 執行緒
    // rasterize 整頁筆跡、swap 進 pinchReuse，並 bump inkBakeVersion 觸發 draw 換新圖。
    // — inkBakeVersion 必須在 drawWithCache 內讀到：讀了才訂閱、換圖那幀才會重畫。
    val inkBakeVersion = remember { mutableIntStateOf(0) }
    val inkBakeInFlight = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val inkBakeRequests = remember { kotlinx.coroutines.channels.Channel<InkBakeRequest>(capacity = kotlinx.coroutines.channels.Channel.CONFLATED) }
    // 頁流重發（fingerprint 含 stroke.version）→ bump stamp：讓 drawWithCache factory 重跑、
    // 由下方 dirty-rect 增量擦除重畫接手（位移不進 inkScaleKey，bake 不會自動被喚醒，這是唯一入口）。
    val inkStamp = remember { mutableIntStateOf(0) }
    LaunchedEffect(strokes) {
        inkStamp.intValue += 1
    }
    // P0-A 背景重烘收集器：conflated → 只保留最新請求；Default 執行緒 rasterize 後
    // swap 進 pinchReuse 並 bump inkBakeVersion。全局限 2 個並行（多頁同時 zoom 不互踩）。
    // 上次建墨跡快取用的比例（同上，普通物件不觸發重組）。比例一變就必須全量重建：
    // 增量只補髒區，髒區外的像素會留著舊比例＝紙面與墨跡對不上（縮放才自愈的那個病）。
    // order＝位圖內筆跡的有序簽名，只用來抓「同一個集合、換了順序」（見重建區說明）。
    // 墨跡快取的 rebuild 簿記：背景 swap／冷啟動烘時寫入（inkScaleKey，只認 sx/sy）；不一致才重烘。
    val lastInkScale = remember { object { var contentKey: Long = 0L } }
    LaunchedEffect(pageIndex) {
        for (req in inkBakeRequests) {
            val bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                if (!tryAcquireBakeSlot()) return@withContext null
                try {
                    rasterizeInkPageBitmap(req)
                } catch (e: Exception) {
                    android.util.Log.w("InkFlowDiag", "INKBG fail p=$pageIndex: ${e.message}")
                    null
                } finally {
                    releaseBakeSlot()
                }
            } ?: run {
                inkBakeInFlight.set(false)
                continue
            }
            // 以「這次派發的快照」算 order——stroke 在烘焙期間落筆的情況由 order 檢測收斂（再烘一次）
            pinchReuse.bmp = bitmap.asImageBitmap()
            lastInkScale.contentKey = req.contentKey
            // 帳與圖同一瞬間更新：向量層只畫「不在帳上」的筆，所以舊熱筆會在這一刻
            // 從向量層退場，而含它的位圖正好同時換上（見 bakedIds 的說明）。
            bakedIds = req.includedIds
            inkBakeVersion.intValue += 1
            inkBakeInFlight.set(false)
            android.util.Log.w(
                "InkFlowDiag",
                "INKBG swap p=$pageIndex ${req.targetWidth}x${req.targetHeight} baked=${req.includedIds.size}"
            )
        }
    }
    val drawModifier = Modifier.fillMaxSize()
        .onSizeChanged { size ->
            canvasPixelSize = Size(size.width.toFloat(), size.height.toFloat())
            viewModel.setCanvasSize(size.width.toFloat(), size.height.toFloat())
        }
        // Capture raw contact metrics before Compose converts MotionEvent to PointerInputChange.
        // Returning false forwards the event unchanged to the pointerInput block below.
        .pointerInteropFilter { motionEvent ->
            // 筆跡跟手：系統預設把觸控事件批次成「一幀一包」才派發，高刷新率平板上
            // 筆跡會落後筆尖，提筆那一幀再一次補齊（肉眼是抖一下／閃一下）。
            // 官方做法：ACTION_DOWN 時對這個 View 要求無批次派發（只影響本 View 的輸入）。
            val maskedAction = motionEvent.actionMasked
            if (maskedAction == MotionEvent.ACTION_DOWN ||
                maskedAction == MotionEvent.ACTION_POINTER_DOWN
            ) {
                view.requestUnbufferedDispatch(motionEvent)
            }
            val hasStylusPointer = (0 until motionEvent.pointerCount).any { i ->
                motionEvent.getToolType(i) == MotionEvent.TOOL_TYPE_STYLUS
            }
            val stylusButtonsMask = MotionEvent.BUTTON_STYLUS_PRIMARY or
                MotionEvent.BUTTON_STYLUS_SECONDARY or
                MotionEvent.BUTTON_SECONDARY
            val stylusButtonNow = hasStylusPointer &&
                (motionEvent.buttonState and stylusButtonsMask) != 0

            if (!stylusButtonPressed && stylusButtonNow) {
                stylusButtonPressed = true
                viewModel.onStylusButtonPressed()
            } else if (stylusButtonPressed && !stylusButtonNow) {
                stylusButtonPressed = false
                viewModel.onStylusButtonReleased()
            }

            val actionName = when (motionEvent.actionMasked) {
                MotionEvent.ACTION_DOWN         -> "DOWN"
                MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN"
                MotionEvent.ACTION_MOVE         -> "MOVE"
                MotionEvent.ACTION_UP           -> "UP"
                MotionEvent.ACTION_POINTER_UP   -> "POINTER_UP"
                MotionEvent.ACTION_CANCEL       -> "CANCEL"
                MotionEvent.ACTION_HOVER_ENTER  -> "HOVER_ENTER"
                MotionEvent.ACTION_HOVER_MOVE   -> "HOVER_MOVE"
                MotionEvent.ACTION_HOVER_EXIT   -> "HOVER_EXIT"
                MotionEvent.ACTION_BUTTON_PRESS -> "BUTTON_PRESS"
                MotionEvent.ACTION_BUTTON_RELEASE -> "BUTTON_RELEASE"
                else -> "UNKNOWN(${motionEvent.actionMasked})"
            }
            val tool0 = when (motionEvent.getToolType(0)) {
                MotionEvent.TOOL_TYPE_FINGER -> "FINGER"
                MotionEvent.TOOL_TYPE_STYLUS -> "STYLUS"
                else -> "OTHER(${motionEvent.getToolType(0)})"
            }
            palmDebugLog("RAW $actionName cnt=${motionEvent.pointerCount} " +
                "tool0=$tool0 major0=${"%,.2f".format(motionEvent.getTouchMajor(0))} " +
                "id0=${motionEvent.getPointerId(0)} btn=${motionEvent.buttonState}")
            when (motionEvent.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    // Tell the parent view hierarchy not to intercept our touch stream.
                    // This prevents MIUI's multi-touch gesture recogniser from stealing
                    // the stylus ACTION_POINTER_DOWN when a palm is already on screen.
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    // Store touchMajor for every active pointer so that multi-touch
                    // resolution (palm on screen + stylus writing) can identify the stylus.
                    repeat(motionEvent.pointerCount) { i ->
                        pointerTouchMajors[motionEvent.getPointerId(i)] =
                            motionEvent.getTouchMajor(i)
                    }
                    lastTouchMajorPx   = motionEvent.getTouchMajor(0)
                    lastTouchMinorPx   = motionEvent.getTouchMinor(0)
                    lastToolMajorPx    = motionEvent.getToolMajor(0)
                    lastNativeToolType = motionEvent.getToolType(0)
                    lastPointerCount   = motionEvent.pointerCount
                    // P0-0 PROBE: stylus axes (tilt 0=直立; orientation 方向; pressure 原生壓感)
                    val axisIdx = if (motionEvent.actionMasked == MotionEvent.ACTION_POINTER_DOWN) motionEvent.actionIndex else 0
                    lastTiltDeg        = motionEvent.getAxisValue(MotionEvent.AXIS_TILT, axisIdx)
                    lastOrientationDeg = motionEvent.getAxisValue(MotionEvent.AXIS_ORIENTATION, axisIdx)
                    lastAxisPressure   = motionEvent.getAxisValue(MotionEvent.AXIS_PRESSURE, axisIdx)
                }
                MotionEvent.ACTION_BUTTON_PRESS -> {
                    // Fallback path for devices that do emit explicit button events.
                    if (hasStylusPointer) {
                        viewModel.onStylusButtonPressed()
                        stylusButtonPressed = true
                    }
                }
                MotionEvent.ACTION_BUTTON_RELEASE -> {
                    // Fallback path for devices that do emit explicit button events.
                    if (hasStylusPointer) {
                        viewModel.onStylusButtonReleased()
                        stylusButtonPressed = false
                    }
                }
                MotionEvent.ACTION_POINTER_UP ->
                    pointerTouchMajors.remove(
                        motionEvent.getPointerId(motionEvent.actionIndex)
                    )
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pointerTouchMajors.clear()
                    if (stylusButtonPressed) {
                        stylusButtonPressed = false
                        viewModel.onStylusButtonReleased()
                    }
                }
            }
            false
        }
        .pointerInput(activeTool, inputMode, quickSwipeEraserEnabled) {
            awaitEachGesture {
                palmDebugLog("GESTURE start - awaiting first down")
                val firstContactDown = awaitFirstDown(requireUnconsumed = false)
                palmDebugLog("GESTURE firstDown id=${firstContactDown.id.value} " +
                    "type=${firstContactDown.type} pressed=${firstContactDown.pressed} " +
                    "major=${pointerTouchMajors[firstContactDown.id.value.toInt()]}")
                val firstEvent = awaitPointerEvent()
                palmDebugLog("GESTURE firstEvent changes=${firstEvent.changes.size}: " +
                    firstEvent.changes.joinToString { c ->
                        "id=${c.id.value} pressed=${c.pressed} prev=${c.previousPressed}"
                    })
                var downEvent = firstEvent

                // Resolve which pointer to track for drawing.
                // STYLUS_ONLY + multi-touch（手掌著屏＋筆落下）：原廠判斷優先——
                // 直接認 PointerType.Stylus（OEM tool type 直透），手掌 Touch 不擋筆；
                // 全是 Touch（雙指縮放/平移）才放行。全程不用 touchMajor 猜。
                // PALM_REJECTION + multi-touch: locate the stylus among all contacts
                //   (smallest touchMajor ≤ STYLUS_TOUCH_MAJOR_THRESHOLD).
                // FREE: block multi-touch entirely (existing behaviour).
                val down = if (firstEvent.changes.size > 1) {
                    if (inputMode == InputMode.STYLUS_ONLY) {
                        firstEvent.changes.firstOrNull { it.type == PointerType.Stylus }
                            ?: return@awaitEachGesture
                    } else {
                        if (inputMode != InputMode.PALM_REJECTION) return@awaitEachGesture
                    val candidate = firstEvent.changes.minByOrNull { c ->
                        pointerTouchMajors[c.id.value.toInt()] ?: Float.MAX_VALUE
                    } ?: return@awaitEachGesture
                    val cMajor = pointerTouchMajors[candidate.id.value.toInt()] ?: Float.MAX_VALUE
                    when {
                        // All contacts are palms
                        PalmRejectionFilter.shouldReject(cMajor, 1f, 1, density) ->
                            return@awaitEachGesture
                        // All contacts are fingers — pass through for pan
                        PalmRejectionFilter.isFinger(cMajor, density) ->
                            return@awaitEachGesture
                        // Stylus found — use it for drawing; ignore palm/finger sibling pointers
                        else -> candidate
                    }
                    }
                } else {
                    val singleMajor =
                        pointerTouchMajors[firstContactDown.id.value.toInt()] ?: lastTouchMajorPx
                    if (inputMode == InputMode.PALM_REJECTION &&
                        PalmRejectionFilter.shouldReject(singleMajor, 1f, 1, density)
                    ) {
                        // Palm is the first (and only) contact. In PALM_REJECTION mode, do NOT
                        // return immediately — that would let awaitAllPointersUp() swallow the
                        // subsequent stylus ACTION_POINTER_DOWN. Instead, hold here and wait for
                        // a stylus pointer to join.
                        var stylusDown: PointerInputChange? = null
                        outer@ while (true) {
                            val evt = awaitPointerEvent()
                            val genuineLift = evt.changes.any { it.previousPressed && !it.pressed }
                            palmDebugLog("GESTURE while-loop changes=${evt.changes.size} " +
                                "allUp=${evt.changes.all { !it.pressed }} genuineLift=$genuineLift: " +
                                evt.changes.joinToString { c ->
                                    "id=${c.id.value} pressed=${c.pressed} prev=${c.previousPressed} " +
                                    "major=${pointerTouchMajors[c.id.value.toInt()]}"
                                })
                            // Only exit if a pointer genuinely lifted (avoids premature break on hover events).
                            if (evt.changes.all { !it.pressed } && genuineLift) break
                            for (change in evt.changes) {
                                if (!change.pressed || change.previousPressed) continue  // only newly-pressed pointers
                                val major =
                                    pointerTouchMajors[change.id.value.toInt()] ?: Float.MAX_VALUE
                                palmDebugLog("GESTURE while-loop candidate id=${change.id.value} major=$major")
                                if (!PalmRejectionFilter.shouldReject(major, 1f, 1, density) &&
                                    !PalmRejectionFilter.isFinger(major, density)
                                ) {
                                    downEvent = evt
                                    stylusDown = change
                                    break@outer
                                }
                            }
                        }
                        palmDebugLog("GESTURE while-loop exited stylusDown=$stylusDown")
                        stylusDown ?: return@awaitEachGesture
                    } else {
                        firstContactDown
                    }
                }

                // --- Snapshot raw metrics captured by pointerInteropFilter ---
                // Also detect if the DOWN+UP sequence completed before awaitPointerEvent() ran
                // (touch lifetime < one frame). When true, skip awaitDragOrCancellation and treat
                // the gesture as an instantaneous tap rather than a cancelled gesture.
                val alreadyReleased = downEvent.changes.any { it.id == down.id && !it.pressed }
                val touchMajorPx   = pointerTouchMajors[down.id.value.toInt()] ?: lastTouchMajorPx
                val touchMinorPx   = lastTouchMinorPx
                val toolMajorPx    = lastToolMajorPx
                val nativeToolType = lastNativeToolType
                val nativePointers = lastPointerCount
                val sessionId = TouchEventLogger.newSession()

                TouchEventLogger.logDown(
                    sessionId        = sessionId,
                    mode             = inputMode.name,
                    composeToolType  = down.type.toString(),
                    pressure         = down.pressure,
                    sizeWidthPx      = touchMajorPx,
                    sizeHeightPx     = touchMinorPx,
                    touchMajorPx     = touchMajorPx,
                    touchMinorPx     = touchMinorPx,
                    toolMajorPx      = toolMajorPx,
                    nativeToolType   = nativeToolType,
                    pointerCount     = nativePointers,
                    posX             = down.position.x,
                    posY             = down.position.y,
                    tiltDeg          = lastTiltDeg,
                    orientationDeg   = lastOrientationDeg,
                    axisPressure     = lastAxisPressure
                )

                // --- STYLUS_ONLY: any Touch-type contact → pass through for pan (Box handles) ---
                if (inputMode == InputMode.STYLUS_ONLY && down.type == PointerType.Touch) {
                    return@awaitEachGesture
                }

                // --- Universal palm rejection: applies to ALL modes ---
                // A palm-sized contact is silently dropped (no draw, no pan) in every mode.
                if (down.type == PointerType.Touch &&
                    PalmRejectionFilter.shouldReject(
                        touchMajorPx = touchMajorPx,
                        pressure = down.pressure,
                        concurrentPointers = downEvent.changes.size,
                        density = density
                    )
                ) {
                    TouchEventLogger.logOutcome(
                        sessionId       = sessionId,
                        outcome         = "REJECTED_PALM",
                        pointCount      = 0,
                        maxPressure     = down.pressure,
                        maxSizeWidthPx  = touchMajorPx,
                        maxSizeHeightPx = touchMinorPx
                    )
                    return@awaitEachGesture
                }

                // --- PALM_REJECTION only: finger zone → pass through for single-finger pan ---
                // FREE mode: finger (non-palm) falls through and draws — no zone filtering needed.
                if (inputMode == InputMode.PALM_REJECTION && down.type == PointerType.Touch) {
                    if (PalmRejectionFilter.isFinger(touchMajorPx, density)) {
                        // Finger: do NOT consume — bubbles up to Workspace Box for pan.
                        // Exception: Eraser works with finger contacts; let it fall through.
                        if (activeTool != Tool.ERASER) {
                            TouchEventLogger.logOutcome(
                                sessionId       = sessionId,
                                outcome         = "FINGER_PAN",
                                pointCount      = 0,
                                maxPressure     = down.pressure,
                                maxSizeWidthPx  = touchMajorPx,
                                maxSizeHeightPx = touchMinorPx
                            )
                            return@awaitEachGesture
                        }
                    }
                    // Stylus (small touchMajor): fall through to draw
                }

                // --- Inline text editor open: this new contact commits it (tap-outside-to-commit).
                // A second tap is then needed to start another annotation — matching GoodNotes behaviour.
                if (inlineTextNewPosRef.value != null || inlineTextEditIdRef.value != null) {
                    val v      = inlineTextValueRef.value
                    val editId = inlineTextEditIdRef.value
                    val newPos = inlineTextNewPosRef.value
                    inlineTextNewPos = null
                    inlineTextEditId = null
                    inlineTextValue  = ""
                    keyboardRef.value?.hide()
                    if (v.isNotBlank()) {
                        if (editId != null) viewModel.commitTextAnnotationContent(editId, v)
                        else if (newPos != null) {
                            viewModel.addTextAnnotation(
                                v, newPos.x, newPos.y,
                                with(density) { 20.sp.toPx() }, inlineTextColorRef.value,
                                targetPage = pageIndexRef.value
                            )
                        }
                    }
                    down.consume()
                    return@awaitEachGesture
                }

                // Claim accepted in-canvas gestures immediately so fast stylus motion cannot
                // leak through to the workspace pan handler before we enter the tool branch.
                //
                // 例外＝IMAGE 工具：它是唯一不在紙上畫東西的工具（只做選取/移動/縮放/旋轉），
                // 紙面空白處沒有東西要認領。這裡不預先 consume，空白處那一指就不會被標記
                // 為「墨水的」，樞紐（hubShouldPanOnPaper 看 isConsumed）就會接手捲頁；
                // 而單指落在圖片本體／把手上時，那些分支各自 down.consume()，
                // 樞紐看到旗標會讓路 → 只移動圖片、不捲頁。兩邊不需要知道對方的工具。
                if (activeTool != Tool.IMAGE) {
                    downEvent.changes
                        .filter { it.pressed }
                        .forEach { it.consume() }
                    down.consume()
                }
                // 頁鎖兜底：上個手勢若異常退出（未走提交/丟棄），在此清除，不污染新手勢
                viewModel.setPageLock(false)
                viewModel.setDragPreviewActive(false)

                // 手指落筆校正：僅手指模式(FREE)＋Touch 接觸＋開關開；觸控筆模式零偏移。
                // 整個手勢同一個偏移（手勢中途改設定不影響本筆，避免線條斷折）。
                // startOffset 之後所有分支（筆畫/橡皮擦/圖形/套索/文字圖片錨點/命中判定）
                // 全從校正後座標衍生，視覺與命中自動一致。
                val calDxPx: Float
                val calDyPx: Float
                if (inputMode == InputMode.FREE && touchCalEnabledRef.value && down.type == PointerType.Touch) {
                    calDxPx = with(density) { touchCalDxRef.value.dp.toPx() }
                    calDyPx = with(density) { touchCalDyRef.value.dp.toPx() }
                } else {
                    calDxPx = 0f
                    calDyPx = 0f
                }
                fun calPos(p: Offset): Offset =
                    if (calDxPx == 0f && calDyPx == 0f) p else Offset(p.x + calDxPx, p.y + calDyPx)

                val startOffset = calPos(down.position)

                // Text tool: selection, move, resize, or new text placement
                if (activeTool == Tool.TEXT) {
                    val cs = canvasPixelSizeState.value
                    val sx = DocTransform.scaleX(cs.width, viewModel.modelWidth)
                    val sy = DocTransform.scaleY(cs.height, viewModel.modelHeight)
                    val annotations = textAnnotationsRef.value
                    val selId = selectedTextIdRef.value
                    val selAnn = if (selId != null) annotations.firstOrNull { it.id == selId } else null

                    if (selAnn != null) {
                        val currentTextRect = textAnnotationHitRect(
                            selAnn, sx, sy, boxWidthDelta = textBoxWidthDelta
                        ).translate(textMoveDelta)
                        // 兩顆把手：右下角＝字級、右緣中點＝框寬。用「最近優先」挑，
                        // 短框時兩顆的 48dp 命中圈必然重疊（同圖片把手的理由）。
                        val cornerHandle = Offset(currentTextRect.right, currentTextRect.bottom)
                        val widthHandle = if (selAnn.boxWidth != null)
                            Offset(currentTextRect.right, currentTextRect.center.y) else null
                        val handleCandidates =
                            if (widthHandle != null) listOf(cornerHandle, widthHandle) else listOf(cornerHandle)
                        val handleHit = nearestHandleIndex(startOffset, handleCandidates, handleHitRadiusPx)

                        // 寬度把手（框右緣中點）：拖曳改框寬 → 立即重排。只有文字框（boxWidth 有值）有。
                        if (handleHit == 1 && widthHandle != null) {
                            down.consume()
                            pressedHandleCenter = widthHandle
                            val startBoxW = selAnn.boxWidth!!
                            var accW = 0f
                            var drag = awaitDragOrCancellation(down.id)
                            while (drag != null && drag.pressed) {
                                if (pinchActive) {
                                    val w = (startBoxW + accW).coerceAtLeast(TEXT_BOX_MIN_WIDTH)
                                    viewModel.commitTextAnnotationWidth(selAnn.id, w)
                                    pendingTextSettle = PendingTextSettle(
                                        id = selAnn.id, modelX = selAnn.modelX, modelY = selAnn.modelY,
                                        fontSize = selAnn.fontSize, boxWidth = w
                                    )
                                    activePathVersion++
                                    pressedHandleCenter = null
                                    return@awaitEachGesture
                                }
                                accW += drag.positionChange().x * viewModel.modelWidth / cs.width.coerceAtLeast(1f)
                                textBoxWidthDelta = accW
                                activePathVersion++
                                drag.consume()
                                drag = awaitDragOrCancellation(drag.id)
                            }
                            val newW = (startBoxW + accW).coerceAtLeast(TEXT_BOX_MIN_WIDTH)
                            viewModel.commitTextAnnotationWidth(selAnn.id, newW)
                            // 不歸零 textBoxWidthDelta：等熱流回報新框寬才清（見 PendingTextSettle）
                            pendingTextSettle = PendingTextSettle(
                                id = selAnn.id, modelX = selAnn.modelX, modelY = selAnn.modelY,
                                fontSize = selAnn.fontSize, boxWidth = newW
                            )
                            activePathVersion++
                            pressedHandleCenter = null
                            return@awaitEachGesture
                        }

                        // Resize handle hit（右下角＝字級）
                        if (handleHit == 0) {
                            down.consume()
                            pressedHandleCenter = cornerHandle
                            var accModelDelta = 0f
                            var drag = awaitDragOrCancellation(down.id)
                            while (drag != null && drag.pressed) {
                                if (pinchActive) {
                                    viewModel.commitTextAnnotationResize(selAnn.id, selAnn.modelX, selAnn.modelY, selAnn.fontSize + accModelDelta)
                                    pendingTextSettle = PendingTextSettle(
                                        id = selAnn.id,
                                        modelX = selAnn.modelX,
                                        modelY = selAnn.modelY,
                                        fontSize = selAnn.fontSize + accModelDelta,
                                        boxWidth = selAnn.boxWidth
                                    )
                                    activePathVersion++
                                    pressedHandleCenter = null
                                    return@awaitEachGesture
                                }
                                // Standard resize UX: dragging toward bottom-right = bigger
                                val change = drag.positionChange()
                                val diagonal = (change.x + change.y) / 2f
                                accModelDelta += diagonal * viewModel.modelWidth / cs.width.coerceAtLeast(1f)
                                textFontSizeDelta = accModelDelta
                                activePathVersion++
                                drag.consume()
                                drag = awaitDragOrCancellation(drag.id)
                            }
                            viewModel.commitTextAnnotationResize(selAnn.id, selAnn.modelX, selAnn.modelY, selAnn.fontSize + accModelDelta)
                            // 不歸零 textFontSizeDelta：等熱流回報新字級才清（見 PendingTextSettle）
                            pendingTextSettle = PendingTextSettle(
                                id = selAnn.id,
                                modelX = selAnn.modelX,
                                modelY = selAnn.modelY,
                                fontSize = selAnn.fontSize + accModelDelta,
                                boxWidth = selAnn.boxWidth
                            )
                            activePathVersion++
                            pressedHandleCenter = null
                            return@awaitEachGesture
                        }

                        // Move: drag inside the selected text box; plain tap re-edits contents inline
                        if (currentTextRect.contains(startOffset)) {
                            down.consume()
                            viewModel.setPageLock(true)
                            var totalDelta = Offset.Zero
                            // 跟手絕對式：指位相對起點，每幀重算不積差。
                            // 注意自動捲量「不可」加進來：觸控座標是紙座標，紙被捲動時
                            // 座標跟著 shift（Compose 機制），再加 autoY 就是雙倍——
                            // 跨頁漂移的真正根因。自動捲只負責把路讓出來（捲視野）。
                            var drag = awaitDragOrCancellation(down.id)
                            while (drag != null && drag.pressed) {
                                // 連貫畫布：拖出紙界自動捲（只捲視野，不進位移）
                                val csH = canvasPixelSizeState.value.height
                                val autoDy = edgeAutoScrollDy(drag.position.y, csH)
                                if (autoDy != 0f) {
                                    onEdgeAutoScrollRef.value(autoDy)
                                }
                                totalDelta = drag.position - startOffset
                                textMoveDelta = totalDelta
                                if (pinchActive) {
                                    viewModel.commitTextAnnotationMove(selAnn.id, totalDelta.x, totalDelta.y)
                                    textMoveDelta = Offset.Zero
                                    activePathVersion++
                                    viewModel.setPageLock(false)
                                    return@awaitEachGesture
                                }
                                activePathVersion++
                                drag.consume()
                                drag = awaitDragOrCancellation(drag.id)
                            }
                            if (totalDelta == Offset.Zero) {
                                inlineTextEditId = selAnn.id
                                inlineTextValue  = selAnn.text
                                inlineTextColor  = Color(selAnn.colorArgb)
                                viewModel.setPageLock(false)
                            } else {
                                // 跨頁：總位移把文字推出本頁 → 整顆換頁；否則舊提交。
                                // **文字流成員不換頁**：換頁等於離開那條流的排序鏈（重排只能在
                                // 同一頁內量），所以流的成員一律留在本頁、由 VM 夾在紙面內。
                                val isFlowMember = selAnn.flowId != null
                                val cs = canvasPixelSizeState.value
                                val scaleX = DocTransform.invScaleX(cs.width, viewModel.modelWidth)
                                val scaleY = DocTransform.invScaleY(cs.height, viewModel.modelHeight)
                                val newModelX = selAnn.modelX + totalDelta.x * scaleX
                                val newModelY = selAnn.modelY + totalDelta.y * scaleY
                                val wrapped = if (isFlowMember) null else wrapCrossPageY(
                                    newModelY, viewModel.modelHeight,
                                    pageIndexRef.value, pageCountRef.value
                                )
                                if (wrapped != null) {
                                    viewModel.commitTextAnnotationMoveToPage(
                                        selAnn.id, wrapped.first, newModelX, wrapped.second
                                    )
                                    viewModel.setPageLock(false)
                                    // M1 全活頁：不激活不跳轉；脫選（M2 改框跟內容走）
                                    selectedTextAnnotationId = null
                                    textMoveDelta = Offset.Zero
                                    activePathVersion++
                                    return@awaitEachGesture
                                }
                                viewModel.commitTextAnnotationMove(selAnn.id, totalDelta.x, totalDelta.y)
                                viewModel.setPageLock(false)
                                // 不歸零 textMoveDelta：等熱流回報新位置才清（見 PendingTextSettle），
                                // 否則 DB 回報前的幾幀會用舊位置＋0 繪製 → 文字閃一下。
                                pendingTextSettle = PendingTextSettle(
                                    id = selAnn.id,
                                    modelX = selAnn.modelX + totalDelta.x * scaleX,
                                    modelY = selAnn.modelY + totalDelta.y * scaleY,
                                    fontSize = selAnn.fontSize,
                                    boxWidth = selAnn.boxWidth,
                                    // 整條流搬動會被夾在紙面內 → 預測座標不一定成立，只認 id。
                                    anyPosition = isFlowMember
                                )
                                activePathVersion++
                                return@awaitEachGesture
                            }
                            textMoveDelta = Offset.Zero
                            activePathVersion++
                            return@awaitEachGesture
                        }
                    }

                    // Tap on any annotation to select it（用「內容」框，見 textAnnotationContentRect）
                    val hitAnn = annotations.firstOrNull { ann ->
                        textAnnotationContentRect(ann, sx, sy).contains(startOffset)
                    }
                    if (hitAnn != null) {
                        selectedTextAnnotationId = hitAnn.id
                        textMoveDelta = Offset.Zero
                        textFontSizeDelta = 0f
                        textBoxWidthDelta = 0f
                        down.consume()
                        activePathVersion++
                        return@awaitEachGesture
                    }

                    // Tap on empty space: deselect and open the inline editor at the tap point
                    selectedTextAnnotationId = null
                    textMoveDelta = Offset.Zero
                    inlineTextEditId = null
                    inlineTextNewPos = startOffset
                    inlineTextValue  = ""
                    inlineTextColor  = selectedColorRef.value
                    down.consume()
                    return@awaitEachGesture
                }

                // Image tool: selection, move, resize, or tap-to-pick
                if (activeTool == Tool.IMAGE) {
                    val cs = canvasPixelSizeState.value
                    val sx = DocTransform.scaleX(cs.width, viewModel.modelWidth)
                    val sy = DocTransform.scaleY(cs.height, viewModel.modelHeight)
                    val annotations = imageAnnotationsRef.value
                    val selId = selectedImageIdRef.value
                    val selAnn = if (selId != null) annotations.firstOrNull { it.id == selId } else null

                    if (selAnn != null) {
                        // Local (unrotated) rect in canvas space: committed + in-flight move/resize.
                        // Rotation pivot is the committed(+move) center, fixed for the whole gesture.
                        val baseRect = imageAnnotationRect(selAnn, sx, sy).translate(imageMovePreview)
                        val pivot = baseRect.center
                        val localRect = if (imageResizeAnchor != null && abs(imageResizeScale - 1f) > 0.0001f)
                            scaleRectAbout(baseRect, imageResizeAnchor!!, imageResizeScale)
                        else baseRect
                        val theta = selAnn.rotation + imageRotatePreview
                        val localCorners = listOf(
                            localRect.topLeft, localRect.topRight,
                            localRect.bottomLeft, localRect.bottomRight
                        )
                        val screenCorners = localCorners.map { rotatePoint(it, pivot, theta) }
                        // Rotation handle floats above the (rotated) top edge
                        val rotHandleLocal = Offset(localRect.center.x, localRect.top - rotHandleGapPx)
                        val rotHandle = rotatePoint(rotHandleLocal, pivot, theta)

                        // 命中：四角 + 旋轉把手一起比距離，取最近的（rotIndex=4）。
                        // 舊碼是「旋轉固定優先 → 四角 indexOfFirst」，48dp 命中框在
                        // 小圖上重疊時會穩定抓錯——改成最近優先後才敢放大命中區。
                        val allHandles = screenCorners + rotHandle
                        val ROT_IDX = 4
                        val hitIdx = handleHit(startOffset, allHandles, rotIndex = ROT_IDX)

                        // — Rotate: nearest handle is the rotation handle —
                        if (hitIdx == ROT_IDX) {
                            down.consume()
                            pressedHandleCenter = rotHandleLocal
                            val grabAngle = atan2(startOffset.y - pivot.y, startOffset.x - pivot.x) *
                                (180f / Math.PI.toFloat())
                            imageRotatePreview = 0f
                            fun commitRotated() {
                                // 算出這次 commit 會讓 DB 變成什麼，等熱流回報對上了才清預覽
                                // （否則放手瞬間圖片會閃回原位再跳回來，見 PendingImageSettle）。
                                val targetRot = ((selAnn.rotation + imageRotatePreview) % 360f + 360f) % 360f
                                viewModel.commitImageAnnotationRotation(
                                    selAnn.id, selAnn.rotation + imageRotatePreview
                                )
                                pendingImageSettle = PendingImageSettle(
                                    id = selAnn.id,
                                    modelX = selAnn.modelX, modelY = selAnn.modelY,
                                    modelWidth = selAnn.modelWidth, modelHeight = selAnn.modelHeight,
                                    rotation = targetRot
                                )
                                activePathVersion++
                            }
                            var drag = awaitDragOrCancellation(down.id)
                            while (drag != null && drag.pressed) {
                                if (pinchActive) {
                                    commitRotated()
                                    pressedHandleCenter = null
                                    return@awaitEachGesture
                                }
                                val a = atan2(drag.position.y - pivot.y, drag.position.x - pivot.x) *
                                    (180f / Math.PI.toFloat())
                                var d = a - grabAngle
                                while (d > 180f) d -= 360f
                                while (d < -180f) d += 360f
                                imageRotatePreview = d
                                activePathVersion++
                                drag.consume()
                                drag = awaitDragOrCancellation(drag.id)
                            }
                            commitRotated()
                            pressedHandleCenter = null
                            return@awaitEachGesture
                        }

                        // — Resize: nearest handle is a corner (aspect locked, rotation-aware) —
                        if (hitIdx >= 0) {
                            down.consume()
                            pressedHandleCenter = allHandles[hitIdx]
                            // All resize math happens in local (unrotated) space about the fixed pivot.
                            val anchorLocal = localCorners[
                                when (hitIdx) { 0 -> 3; 1 -> 2; 2 -> 1; else -> 0 }
                            ]
                            val startLocal = rotatePoint(startOffset, pivot, -theta)
                            val grabVec = startLocal - anchorLocal
                            val grabLenSq = (grabVec.x * grabVec.x + grabVec.y * grabVec.y).coerceAtLeast(1f)
                            imageResizeAnchor = anchorLocal
                            imageResizeScale = 1f
                            fun commitScaled() {
                                val sc = StrokeTransformUtils.clampUniformScale(imageResizeScale)
                                val r = scaleRectAbout(imageAnnotationRect(selAnn, sx, sy), anchorLocal, sc)
                                val nx = r.left / sx
                                val ny = r.top / sy
                                val nw = r.width / sx
                                val nh = r.height / sy
                                viewModel.commitImageAnnotationResize(selAnn.id, nx, ny, nw, nh)
                                // 等熱流回報對上了才清預覽（見 PendingImageSettle）
                                pendingImageSettle = PendingImageSettle(
                                    id = selAnn.id,
                                    modelX = nx, modelY = ny,
                                    modelWidth = nw, modelHeight = nh,
                                    rotation = selAnn.rotation
                                )
                                activePathVersion++
                            }
                            var drag = awaitDragOrCancellation(down.id)
                            while (drag != null && drag.pressed) {
                                if (pinchActive) {
                                    commitScaled()
                                    pressedHandleCenter = null
                                    return@awaitEachGesture
                                }
                                // Project finger travel onto the grab vector → uniform scale
                                val fingerLocal = rotatePoint(drag.position, pivot, -theta)
                                val v = fingerLocal - anchorLocal
                                imageResizeScale = (v.x * grabVec.x + v.y * grabVec.y) / grabLenSq
                                activePathVersion++
                                drag.consume()
                                drag = awaitDragOrCancellation(drag.id)
                            }
                            commitScaled()
                            pressedHandleCenter = null
                            return@awaitEachGesture
                        }

                        // — Move: tap inside the image body —
                        if (rotatedRectContains(localRect, theta, startOffset)) {
                            down.consume()
                            viewModel.setPageLock(true)
                            var totalDelta = Offset.Zero
                            // 跟手絕對式：指位相對起點；自動捲量不加（見文字分支同註解）。
                            var drag = awaitDragOrCancellation(down.id)
                            while (drag != null && drag.pressed) {
                                // 連貫畫布：拖出紙界自動捲（只捲視野，不進位移）
                                val csH = canvasPixelSizeState.value.height
                                val autoDy = edgeAutoScrollDy(drag.position.y, csH)
                                if (autoDy != 0f) {
                                    onEdgeAutoScrollRef.value(autoDy)
                                }
                                totalDelta = drag.position - startOffset
                                imageMovePreview = totalDelta
                                // F4：發布拖曳態給 overlay 跨頁畫（model 位移，與提交同公式）。
                                run {
                                    val csw = canvasPixelSizeState.value.width.coerceAtLeast(1f)
                                    val csh = canvasPixelSizeState.value.height.coerceAtLeast(1f)
                                    viewModel.publishImageDragPreview(
                                        EditorViewModel.ImageDragPreview(
                                            image = selAnn,
                                            dxModel = totalDelta.x * viewModel.modelWidth / csw,
                                            dyModel = totalDelta.y * viewModel.modelHeight / csh
                                        )
                                    )
                                }
                                if (pinchActive) {
                                    viewModel.commitImageAnnotationMove(selAnn.id, totalDelta.x, totalDelta.y)
                                    imageMovePreview = Offset.Zero
                                    activePathVersion++
                                    viewModel.publishImageDragPreview(null)
                                    viewModel.setPageLock(false)
                                    return@awaitEachGesture
                                }
                                activePathVersion++
                                drag.consume()
                                drag = awaitDragOrCancellation(drag.id)
                            }
                            // 跨頁：推出本頁 → 整顆換頁（旋轉角保留）；否則舊提交
                            val cs = canvasPixelSizeState.value
                            val scaleX = DocTransform.invScaleX(cs.width, viewModel.modelWidth)
                            val scaleY = DocTransform.invScaleY(cs.height, viewModel.modelHeight)
                            val wrapped = wrapCrossPageY(
                                selAnn.modelY + totalDelta.y * scaleY, viewModel.modelHeight,
                                pageIndexRef.value, pageCountRef.value
                            )
                            if (wrapped != null && totalDelta != Offset.Zero) {
                                viewModel.commitImageAnnotationMoveToPage(
                                    selAnn.id, wrapped.first,
                                    selAnn.modelX + totalDelta.x * scaleX, wrapped.second
                                )
                                viewModel.setPageLock(false)
                                // M1 全活頁：不激活不跳轉；脫選（M2 改框跟內容走）
                                selectedImageAnnotationId = null
                                imageMovePreview = Offset.Zero
                                activePathVersion++
                                viewModel.publishImageDragPreview(null)
                                return@awaitEachGesture
                            }
                            viewModel.commitImageAnnotationMove(selAnn.id, totalDelta.x, totalDelta.y)
                            viewModel.setPageLock(false)
                            // 不歸零預覽：等熱流回報新位置才清（見 PendingImageSettle）。
                            // 立即歸零會讓 DB 回報前的幾幀用「舊位置+0」繪製 → 圖片閃回原位再跳回來。
                            pendingImageSettle = PendingImageSettle(
                                id = selAnn.id,
                                modelX = selAnn.modelX + totalDelta.x * scaleX,
                                modelY = selAnn.modelY + totalDelta.y * scaleY,
                                modelWidth = selAnn.modelWidth,
                                modelHeight = selAnn.modelHeight,
                                rotation = selAnn.rotation
                            )
                            activePathVersion++
                            viewModel.publishImageDragPreview(null)
                            return@awaitEachGesture
                        }
                    }

                    // Tap on another image to select it (rotation-aware hit)
                    val hitAnn = annotations.firstOrNull { ann ->
                        rotatedRectContains(imageAnnotationRect(ann, sx, sy), ann.rotation, startOffset)
                    }
                    if (hitAnn != null) {
                        selectedImageAnnotationId = hitAnn.id
                        imageMovePreview = Offset.Zero
                        imageResizeScale = 1f
                        imageResizeAnchor = null
                        imageRotatePreview = 0f
                        down.consume()
                        activePathVersion++
                        return@awaitEachGesture
                    }

                    // Tap on empty canvas: do nothing, let the event bubble up to the workspace pan handler.
                    // 插圖改由工具列觸發（launcher 在 EditorScreen）。舊版在 DOWN 當下就 launch
                    // 系統 Activity，而且「空白」只是四個命中測試都沒抓到的 fall-through、
                    // 沒有 tap/drag 區分 → 想滑頁也會彈圖庫。
                    // 這裡刻意不 consume：圖片工具不畫東西，空白處該讓事件冒泡去捲頁。
                    return@awaitEachGesture
                }

                if (activeTool == Tool.LASSO) {
                    val rectLasso = selectedLassoSubType == LassoSubType.RECT
                    val host = SelectionGestureHost(
                        state = { selectionStateRef.value },
                        canvasSize = {
                            val cs = canvasPixelSizeState.value
                            IntSize(cs.width.toInt(), cs.height.toInt())
                        },
                        page = { pageIndexRef.value },
                        pageCount = { pageCountRef.value },
                        modelWidth = { viewModel.modelWidth },
                        modelHeight = { viewModel.modelHeight },
                        pinchActive = { viewModel.pinchActive.value },
                        onEdgeAutoScroll = { dy -> onEdgeAutoScrollRef.value(dy) },
                        setPageLock = { viewModel.setPageLock(it) },
                        setDragPreviewActive = { viewModel.setDragPreviewActive(it) },
                        setPressedHandle = { pressedHandleCenter = it },
                        handleHitRadiusPx = handleHitRadiusPx,
                        beginMove = { viewModel.beginSelectionMove() },
                        moveTo = { viewModel.moveSelectionTo(it) },
                        commitMove = { maxPage -> viewModel.commitSelectionMove(maxPage) },
                        beginResize = { viewModel.beginSelectionResize(it) },
                        resizeTo = { viewModel.scaleSelectionTo(it) },
                        commitResize = { viewModel.commitSelectionResize() },
                        commitRect = { start, end ->
                            val cw = canvasPixelSizeState.value.width
                            val ch = canvasPixelSizeState.value.height
                            LassoGeometry.fromRect(
                                startCanvas = start,
                                endCanvas = end,
                                srcPage = pageIndexRef.value,
                                pageCount = pageCountRef.value,
                                gapPx = pageGapPxRef.value,
                                canvasW = cw,
                                canvasH = ch,
                                modelW = viewModel.modelWidth,
                                modelH = viewModel.modelHeight
                            )?.let { viewModel.selectLassoRegion(it) }
                        },
                        clear = { viewModel.clearSelection() }
                    )
                    val consumed = handleSelectionDown(
                        down = down,
                        startOffset = startOffset,
                        host = host,
                        rectangular = rectLasso,
                        onRectPreview = { s, e ->
                            activeShapeStart = s
                            activeShapeEnd = e
                            activePathVersion++
                        }
                    )
                    if (consumed) return@awaitEachGesture
                }

                // Shape tool: drag to define bounding box (rectangular lasso 已由上面接手)
                if (activeTool == Tool.SHAPE) {
                    activeShapeStart = startOffset
                    activeShapeEnd   = startOffset
                    activePathVersion++
                    val shapeGestureStartTime = down.uptimeMillis
                    var shapeLastEventTime = down.uptimeMillis
                    val shapeGestureTrace = mutableListOf(startOffset)
                    down.consume()
                    var drag = awaitDragOrCancellation(down.id)
                    while (drag != null && drag.pressed) {
                        if (pinchActive) {
                            activeShapeStart = null
                            activeShapeEnd = null
                            activePathVersion++
                            return@awaitEachGesture
                        }
                        activeShapeEnd = drag.position
                        shapeGestureTrace.add(drag.position)
                        shapeLastEventTime = drag.uptimeMillis
                        activePathVersion++
                        drag.consume()
                        drag = awaitDragOrCancellation(drag.id)
                    }
                    val end = activeShapeEnd
                    if (end != null) {
                        if (activeTool == Tool.SHAPE) {
                            val quickSwipeTriggered = quickSwipeEraserEnabled &&
                                shouldTriggerQuickSwipeEraser(
                                    points = shapeGestureTrace,
                                    elapsedMs = shapeLastEventTime - shapeGestureStartTime,
                                    density = density
                                )
                            if (quickSwipeTriggered) {
                                val points = if (startOffset == end) {
                                    listOf(startOffset, Offset(startOffset.x + 0.01f, startOffset.y))
                                } else {
                                    listOf(startOffset, end)
                                }
                                // 形狀工具的快滑擦除是「手勢結束才刪一次」＝本來就一格 undo。
                                // markEraseHit 必須明示 false：預設 true 會把切筆旗標留給下一次
                                // 真正的橡皮擦手勢，讓它沒擦到東西也自動切回畫筆。
                                viewModel.deleteStrokesIntersecting(
                                    points,
                                    page = pageIndexRef.value,
                                    markEraseHit = false
                                )
                            } else {
                                viewModel.saveShape(
                                    startOffset, end, selectedColor, strokeWidth,
                                    targetPage = pageIndexRef.value
                                )
                            }
                        }
                    }
                    activeShapeStart = null
                    activeShapeEnd   = null
                    activePathVersion++
                    return@awaitEachGesture
                }

                // Freehand / Lasso / Eraser
                activePath.reset()
                activePath.moveTo(startOffset.x, startOffset.y)
                activeEnvelopePath.reset()
                
                var lastPointTime = down.uptimeMillis
                val gestureStartTime = down.uptimeMillis
                val zoomAtStrokeStart = viewModel.docZoom.value
                // 本筆累積點列當下所屬的畫布框架。中途若 relayout 改變畫布尺寸，舊點屬舊框架、
                // 提筆卻用新 canvasW 正規化 → 整筆等比偏移（預覽仍在筆尖，所以看不出來）。
                var frameOfPoints = canvasPixelSizeState.value
                val quickSwipeTrace = mutableListOf(startOffset)
                val supportsQuickSwipeEraser = quickSwipeEraserEnabled &&
                    (activeTool == Tool.PEN || activeTool == Tool.HIGHLIGHTER)
                var quickSwipeTriggered = false
                // R1：本次手勢是否已開「擦除累積器」。橡皮擦在落筆時就開（見下方 begin）；
                // 快滑擦除是手勢中途才判出來的，在觸發那一刻才開。
                // 兩條路徑共用這一個旗標 → 不可能一條走累積、另一條又退回「每批一格」。
                var eraseGestureBegan = activeTool == Tool.ERASER
                var lastEraserDispatchTime = down.uptimeMillis
                // F1：共享預覽去重（點數不變不重發，避免每幀垃圾）。
                var lastPublishedSize = -1
                val baseWidth = if (activeTool == Tool.HIGHLIGHTER) strokeWidth * 3f else strokeWidth
                var currentW = baseWidth
                // 觸控筆直讀壓力：Stylus 落筆才進壓力路徑；手指維持速度路徑。
                // down.pressure / drag.pressure 由 Compose 直透 MotionEvent 壓感。
                val isStylusPen = down.type == PointerType.Stylus
                var minPressureSeen = down.pressure
                var maxPressureSeen = down.pressure
                // 壓力模式整筆鎖定一次：前 8 點只觀察，第 8 點起鎖定用壓力還是速度。
                // （之前是逐點閾值切換 → 寫到一半突然從細變粗的「綻開」。）
                var pressureModeLatched = false
                var usePressureMode = false
                var smoothPressure = down.pressure.coerceIn(0f, 1f)
                var lastSmoothPressure = smoothPressure
                // 單點 tap：壓力看似合理（0.05..0.95）才用落筆壓力定寬，否則維持 baseWidth。
                if (isStylusPen && activeTool == Tool.PEN &&
                    down.pressure in 0.05f..0.95f
                ) {
                    val pSeed = down.pressure.coerceIn(0f, 1f)
                    currentW = baseWidth * 0.25f + (baseWidth * 1.7f - baseWidth * 0.25f) * pSeed
                }

                currentPathPoints.clear()
                currentPathPoints.add(StrokePoint(startOffset.x, startOffset.y, currentW))
                activePathVersion++
                down.consume()
                // 連貫畫布：寫筆全程鎖頁，自動捲不觸發作用頁切換，鬆筆才結算跨頁
                viewModel.setPageLock(true)
                // R1：橡皮擦手勢開門（累積器；畫筆/quick-swipe 不開，各自成格）
                if (activeTool == Tool.ERASER) viewModel.beginEraseGesture()
                // 新手勢開始：清掉上一個手勢殘留的視覺接管（正常情況手勢結尾也會清）
                eraseVisualActive = false
                // F3：上一筆交接中途又落筆——沿用舊行為清活路徑（提交段已在資料流，不閃），交接作廢。
                // 注意 reset 後必須補 moveTo，否則後續 quadraticTo 以原點起筆＝左上角怪線。
                // 世代號先跳：交接 effect 若已經跑過 suspension，醒來只會看到「不是我的世代」而放手。
                strokeGestureSeq++
                bridgingIds = null
                bridgingSeq = -1
                activePath.reset()
                activePath.moveTo(startOffset.x, startOffset.y)
                activeEnvelopePath.reset()
                activePathVersion++

                // When the pointer already lifted before we could call awaitDragOrCancellation
                // (DOWN+UP in < one frame), skip the drag loop; the single DOWN point is enough
                // for saveStroke to render a dot.
                var drag: PointerInputChange? = null
                var isRejected = false
                // Peak metrics accumulated during drag for outcome logging.
                // Contact size is only available from nativeEvent at DOWN; track pressure during drag.
                var maxPressureDuring    = down.pressure
                val maxSizeWidthDuring   = touchMajorPx
                val maxSizeHeightDuring  = touchMinorPx

                // Ongoing soft palm-rejection (pressure spike during drag).
                // Threshold > 1.0 so it only fires on devices that report super-normalized
                // pressure (> 1.0 possible on some AOSP/OEM drivers).
                // MIUI caps at 1.0, so this check is benign there.
                if (down.type == PointerType.Touch && down.pressure > 1.5f) {
                    isRejected = true
                }

                if (!alreadyReleased && !isRejected) {
                    drag = awaitDragOrCancellation(down.id)

                        while (drag != null && drag.pressed && !isRejected) {
                        // 雙指介入：棄筆，不提交（縮放會改變座標映射）
                        if (pinchActive) {
                            activePath.reset()
                            activeEnvelopePath.reset()
                            currentPathPoints.clear()
                            activePathVersion++
                            // R1：擦除累積器必須結算——雙指是手勢中途才出現，前面已經
                            // 刪掉的東西（墓碑已提交）不能被靜默丟掉，否則那些筆再也回不來。
                            if (activeTool == Tool.ERASER) viewModel.clearEraseHitPending()
                            if (eraseGestureBegan) viewModel.discardEraseGesture()
                            viewModel.setPageLock(false)
                            return@awaitEachGesture
                        }
                        // 框架中途改變（relayout／視窗或紙面尺寸變）：已收的點屬舊畫布框架，
                        // 全部按尺寸比例換算到新框架再續畫。已收點與續畫點同框架，
                        // 提筆才不會「舊點 × 新係數」＝整筆偏移。
                        val frameNow = canvasPixelSizeState.value
                        val frameWas = frameOfPoints
                        if (frameWas.width > 0f && frameWas.height > 0f &&
                            (frameNow.width != frameWas.width || frameNow.height != frameWas.height)
                        ) {
                            val kx = frameNow.width / frameWas.width
                            val ky = frameNow.height / frameWas.height
                            for (i in currentPathPoints.indices) {
                                val p = currentPathPoints[i]
                                currentPathPoints[i] = p.copy(x = p.x * kx, y = p.y * ky)
                            }
                            for (i in quickSwipeTrace.indices) {
                                val q = quickSwipeTrace[i]
                                quickSwipeTrace[i] = Offset(q.x * kx, q.y * ky)
                            }
                            // 中心線重畫（LASSO/ERASER 的視覺）；PEN 的包絡在下一個點就會重算。
                            activePath.reset()
                            if (currentPathPoints.isNotEmpty()) {
                                activePath.moveTo(currentPathPoints[0].x, currentPathPoints[0].y)
                                for (i in 1 until currentPathPoints.size) {
                                    val a = currentPathPoints[i - 1]
                                    val b = currentPathPoints[i]
                                    activePath.quadraticTo(a.x, a.y, (a.x + b.x) / 2f, (a.y + b.y) / 2f)
                                }
                            }
                            activePathVersion++
                            frameOfPoints = frameNow
                            android.util.Log.w(
                                "InkFlowDiag",
                                "FRAMECHANGE page=${pageIndexRef.value} " +
                                    "was=${frameWas.width.toInt()}x${frameWas.height.toInt()} " +
                                    "now=${frameNow.width.toInt()}x${frameNow.height.toInt()} " +
                                    "kx=${kx} ky=${ky} pts=${currentPathPoints.size} tool=$activeTool"
                            )
                        }
                        // Track peak pressure for outcome log
                        maxPressureDuring = maxOf(maxPressureDuring, drag.pressure)
                        // 觸控筆壓力追蹤：historical 無 pressure 欄位，用同批 drag.pressure 近似。
                        minPressureSeen = minOf(minPressureSeen, drag.pressure)
                        maxPressureSeen = maxOf(maxPressureSeen, drag.pressure)
                        // 壓力本身先做 EMA 去抖（副廠筆壓感跳動大，直接映射會爆粗細）。
                        lastSmoothPressure = smoothPressure
                        smoothPressure = smoothPressure * 0.85f +
                            drag.pressure.coerceIn(0f, 1f) * 0.15f
                        // 前 8 點觀察，之後鎖定整筆模式，不再切換。
                        if (!pressureModeLatched && currentPathPoints.size >= 8) {
                            pressureModeLatched = true
                            usePressureMode = (maxPressureSeen - minPressureSeen) >= 0.05f
                        }

                        // Pressure spike mid-stroke (same threshold reasoning as above)
                        if (drag.type == PointerType.Touch && drag.pressure > 1.5f) {
                            isRejected = true
                            break
                        }

                        val calcWidth = { pos: Offset, time: Long, pressure: Float ->
                            val prevPt = currentPathPoints.last()
                            val dist = kotlin.math.hypot(pos.x - prevPt.x, pos.y - prevPt.y)
                            val dt = (time - lastPointTime).coerceAtLeast(1L)
                                val sensitivity = strokeSpeedSensitivity.coerceAtLeast(0.1f)

                            val w = if (activeTool == Tool.HIGHLIGHTER) {
                                baseWidth // For highlighter, do NOT apply variable thickness
                            } else {
                                // 範圍 0.25x–1.7x（最粗最細對比更像壓感筆），跟隨由設定頁
                                // 「粗細跟手速度」控制（0=鈍 0.95 → 1=靈 0.50，預設 0.80）。
                                val minW = baseWidth * 0.25f
                                val maxW = baseWidth * 1.7f
                                // 觸控筆直讀壓力：只用鎖定後的模式。整手勢壓力幾乎不變
                                // （<0.05，即副廠無壓感筆）→ 整筆退回速度路徑，不中途跳變。
                                // 鎖定前一律速度，避免開頭在兩種模式間橫跳。
                                val usePressure = isStylusPen &&
                                    pressureModeLatched && usePressureMode
                                if (usePressure) {
                                    // 壓感路徑**也要平滑**（與速度路徑同一個函式）——
                                    // 數位板的壓感在取樣之間會跳動，直接用目標值會讓粗細
                                    // 逐點跳動（就是被消掉的「竹節」）。
                                    val target = minW + (maxW - minW) * pressure.coerceIn(0f, 1f)
                                    InkWidthModel.smoothWidth(
                                        previousWidth = prevPt.width,
                                        targetWidth = target,
                                        responsiveness = widthResponsiveness
                                    )
                                } else {
                                    // 速度路徑：**兩端同一份規則**（`:shared` 的 InkWidthModel）——
                                    // 以前桌面接的是一份手抄的常數與平滑，改一邊另一邊就不一樣了。
                                    InkWidthModel.widthFor(
                                        distPx = dist,
                                        dtMs = dt,
                                        previousWidth = prevPt.width,
                                        baseWidth = baseWidth,
                                        isHighlighter = false,
                                        sensitivity = sensitivity,
                                        responsiveness = widthResponsiveness
                                    )
                                }
                            }

                            lastPointTime = time
                            w
                        }

                        drag.historical.forEach { historical ->
                            val hp   = calPos(historical.position)
                            val prev = currentPathPoints.last()
                            activePath.quadraticTo(prev.x, prev.y, (prev.x + hp.x) / 2f, (prev.y + hp.y) / 2f)
                            val w = calcWidth(hp, historical.uptimeMillis, lastSmoothPressure)
                            currentPathPoints.add(StrokePoint(hp.x, hp.y, w))
                            quickSwipeTrace.add(hp)
                        }
                        val newPoint  = calPos(drag.position)
                        val prevPoint = currentPathPoints.last()
                        activePath.quadraticTo(prevPoint.x, prevPoint.y, (prevPoint.x + newPoint.x) / 2f, (prevPoint.y + newPoint.y) / 2f)
                        val w = calcWidth(newPoint, drag.uptimeMillis, smoothPressure)
                        currentPathPoints.add(StrokePoint(newPoint.x, newPoint.y, w))
                        quickSwipeTrace.add(newPoint)

                        if (supportsQuickSwipeEraser && !quickSwipeTriggered) {
                            quickSwipeTriggered = shouldTriggerQuickSwipeEraser(
                                points = quickSwipeTrace,
                                elapsedMs = drag.uptimeMillis - gestureStartTime,
                                density = density
                            )
                            if (quickSwipeTriggered) {
                                eraseVisualActive = true
                                activePath.reset()
                                activeEnvelopePath.reset()
                                // reset 後補 moveTo，否則後續筆段以原點起筆（同左上角怪線）。
                                activePath.moveTo(newPoint.x, newPoint.y)
                                // R1：快滑擦除走跟橡皮擦同一條管線。此刻才開累積器，
                                // 整段快滑（live 批次＋final）才會合併成「一格」undo。
                                // 舊碼沒開 → 每個 24ms 批次各推一格 RemoveStrokes，
                                // 一次快滑＝十幾格，undo/redo 就壞了。
                                if (!eraseGestureBegan) {
                                    viewModel.beginEraseGesture()
                                    eraseGestureBegan = true
                                }
                            }
                        }
                        
                        if (!quickSwipeTriggered && (activeTool == Tool.PEN || activeTool == Tool.HIGHLIGHTER)) {
                            // 預覽與入庫吃「同一份原始點列」：平滑只在共用層
                            // EnvelopeUtils.presmooth 裡做一次。以前預覽先過 3 點平均，
                            // 那會把筆尖往回拉 1/3 段距（快筆 ~20px），而且提筆換成
                            // 未平滑的點列時整條線會跳一下。
                            // F2：寬先 ×docZoom——提交入庫即此語義，預覽與成品同管線（zoom=1 時與舊行為一致）。
                            val zoomPrev = viewModel.docZoom.value.coerceAtLeast(0.1f)
                            val previewPts = currentPathPoints.map { it.copy(width = it.width * zoomPrev) }
                            val newPath = EnvelopeUtils.generateEnvelopePath(previewPts)
                            activeEnvelopePath.reset()
                            activeEnvelopePath.addPath(newPath)
                        }

                        // F1：跨頁共享預覽（src 紙除外，走直接路徑）。點數不變不重發。
                        if ((activeTool == Tool.PEN || activeTool == Tool.HIGHLIGHTER ||
                                activeTool == Tool.LASSO || activeTool == Tool.ERASER ||
                                eraseVisualActive) &&
                            currentPathPoints.size != lastPublishedSize
                        ) {
                            lastPublishedSize = currentPathPoints.size
                            val zoomPub = viewModel.docZoom.value.coerceAtLeast(0.1f)
                            val pubPts = currentPathPoints.map { it.copy(width = it.width * zoomPub) }
                            val segs = DocLayout.splitByPage(
                                items = pubPts,
                                yOf = { it.y },
                                canvasH = canvasPixelSizeState.value.height,
                                gapPx = pageGapPxRef.value,
                                srcPage = pageIndexRef.value,
                                pageCount = pageCountRef.value,
                                local = { p, ly -> p.copy(y = ly) }
                            ).filter { it.first != pageIndexRef.value }
                            viewModel.publishInFlight(
                                if (segs.isEmpty()) null
                                else EditorViewModel.InFlightPreview(
                                    tool = if (eraseVisualActive) Tool.ERASER else activeTool,
                                    segments = segs.toMap(),
                                    colorArgb = selectedColor.toArgb()
                                )
                            )
                        }

                        activePathVersion++
                        drag.consume()

                        if (activeTool == Tool.ERASER || quickSwipeTriggered) {
                            // 橡皮屑(灰石粒):從橡皮擦圓環頭左右寬扇形噴出、重力往下落;每個 drag 事件 2 顆 + 25% 機率第三顆
                            val hp = calPos(drag.position)
                            repeat(if (kotlin.random.Random.nextFloat() < 0.25f) 3 else 2) {
                                if (dustFx.size >= MAX_DUST) dustFx.removeAt(0)
                                // 水平扇形 ±160px/s(左右噴),垂直只小上踢/小下墜,重力接管往下
                                val vx = (kotlin.random.Random.nextFloat() * 2f - 1f) * 160f
                                val vy = -25f + kotlin.random.Random.nextFloat() * 55f
                                dustFx.add(
                                    DustParticle(
                                        x = hp.x, y = hp.y,
                                        vx = vx,
                                        vy = vy,
                                        radius = 2f + kotlin.random.Random.nextFloat() * 2.8f,
                                        t0Nanos = System.nanoTime()
                                    )
                                )
                            }
                        }

                        if (activeTool == Tool.ERASER || quickSwipeTriggered) {
                            // Live erase: send only a recent window and throttle dispatches.
                            // This keeps interaction fluid for large documents while the final
                            // end-of-gesture pass still runs on the full path.
                            val shouldDispatch =
                                drag.uptimeMillis - lastEraserDispatchTime >= ERASER_LIVE_DISPATCH_INTERVAL_MS
                            if (shouldDispatch) {
                                val fromIndex = (currentPathPoints.size - ERASER_LIVE_WINDOW_POINTS).coerceAtLeast(0)
                                val points = currentPathPoints.subList(fromIndex, currentPathPoints.size)
                                    .map { Offset(it.x, it.y) }
                                if (points.size >= 2) {
                                    // 手勢中途只記命中不切筆（切筆留到手勢結尾，
                                    // 避免 pointerInput key 變化中途重啟打斷手勢）。
                                    // quick-swipe 本來就是筆：不記旗不切筆。
                                    // 跨頁擦除：擦過紙界時鄰頁一起擦（各頁分段，累積器按頁收）。
                                    // R1：命中進手勢累積器（橡皮擦與快滑同一條），結尾合併一格。
                                    viewModel.deleteStrokesIntersectingAcrossPages(
                                        eraserPointsCanvas = points,
                                        srcPage = pageIndexRef.value,
                                        canvasH = canvasPixelSizeState.value.height,
                                        gapPx = pageGapPxRef.value,
                                        pageCount = pageCountRef.value,
                                        markEraseHit = activeTool == Tool.ERASER,
                                        accumulateToGesture = eraseGestureBegan
                                    )
                                    lastEraserDispatchTime = drag.uptimeMillis
                                }
                            }
                        }
                        drag = awaitDragOrCancellation(drag.id)
                    }
                } // end !alreadyReleased

                // drag==null means the system cancelled the gesture (e.g. native palm detection).
                // alreadyReleased taps fall through to saving as a dot — NOT cancelled.
                val wasCancelled = drag == null && !alreadyReleased
                if (wasCancelled || isRejected) {
                    TouchEventLogger.logOutcome(
                        sessionId       = sessionId,
                        outcome         = if (wasCancelled) "CANCELLED_SYSTEM" else "REJECTED_PRESSURE",
                        pointCount      = currentPathPoints.size,
                        maxPressure     = maxPressureDuring,
                        maxSizeWidthPx  = maxSizeWidthDuring,
                        maxSizeHeightPx = maxSizeHeightDuring
                    )
                    // Gracefully discard the in-flight path
                    activePath.reset()
                    activeEnvelopePath.reset()
                    currentPathPoints.clear()
                    activePathVersion++
                    bridgingIds = null
                    bridgingSeq = -1
                    viewModel.publishInFlight(null)
                    if (activeTool == Tool.ERASER) viewModel.clearEraseHitPending()
                    // R1：手勢作廢＝結算累積器（已提交的墓碑仍要能 undo，見 discardEraseGesture），
                    // 快滑擦除同樣走這裡（它的門在觸發那一刻開）。
                    if (eraseGestureBegan) viewModel.discardEraseGesture()
                    eraseVisualActive = false
                    viewModel.setPageLock(false)
                    return@awaitEachGesture
                }

                // 手指全程沒動、縮放卻在中途發生過：同樣丟棄，避免提交錯位座標
                if (viewModel.docZoom.value != zoomAtStrokeStart) {
                    activePath.reset()
                    activeEnvelopePath.reset()
                    currentPathPoints.clear()
                    activePathVersion++
                    bridgingIds = null
                    bridgingSeq = -1
                    viewModel.publishInFlight(null)
                    if (activeTool == Tool.ERASER) viewModel.clearEraseHitPending()
                    // R1：手勢作廢＝結算累積器（已提交的墓碑仍要能 undo，見 discardEraseGesture），
                    // 快滑擦除同樣走這裡（它的門在觸發那一刻開）。
                    if (eraseGestureBegan) viewModel.discardEraseGesture()
                    eraseVisualActive = false
                    viewModel.setPageLock(false)
                    return@awaitEachGesture
                }

                // 統一的「擦除手勢收尾」：一般橡皮擦與快滑擦除走完全相同的管線。
                // R1：live 批次＋final 都進手勢累積器（快滑在觸發那一刻才開門），整段合併成一格 undo。
                // quick-swipe 本來就是筆：不記旗不切筆；橡皮擦手勢結尾可觸發自動切回畫筆。
                fun finishErase() {
                    val points = if (currentPathPoints.size == 1) {
                        val pt = currentPathPoints.first()
                        listOf(Offset(pt.x, pt.y), Offset(pt.x + 0.01f, pt.y))
                    } else {
                        currentPathPoints.map { Offset(it.x, it.y) }
                    }
                    viewModel.deleteStrokesIntersectingAcrossPages(
                        eraserPointsCanvas = points,
                        srcPage = pageIndexRef.value,
                        canvasH = canvasPixelSizeState.value.height,
                        gapPx = pageGapPxRef.value,
                        pageCount = pageCountRef.value,
                        markEraseHit = activeTool == Tool.ERASER,
                        switchToPenAfterEraseHit = activeTool == Tool.ERASER,
                        accumulateToGesture = eraseGestureBegan
                    )
                }
                when (activeTool) {
                    Tool.PEN, Tool.HIGHLIGHTER -> {
                        if (quickSwipeTriggered) {
                            TouchEventLogger.logOutcome(
                                sessionId       = sessionId,
                                outcome         = "QUICK_SWIPE_ERASE",
                                pointCount      = currentPathPoints.size,
                                maxPressure     = maxPressureDuring,
                                maxSizeWidthPx  = maxSizeWidthDuring,
                                maxSizeHeightPx = maxSizeHeightDuring
                            )
                            finishErase()
                        } else {
                            // A single-point tap produces no drag points; duplicate it so
                            // saveStroke receives ≥2 points and StrokeCap.Round renders a dot.
                            val rawPts = if (currentPathPoints.size == 1)
                                listOf(currentPathPoints[0], currentPathPoints[0])
                            else
                                currentPathPoints.toList()
                            // 提筆零形變：這一份就是最後一幀預覽用的點列，中間不再做任何
                            // 預覽專屬平滑（平滑在 EnvelopeUtils 內、兩邊共用），所以鬆筆
                            // 不會換形狀，筆尖也不會回彈。
                            val pts = rawPts
                            TouchEventLogger.logOutcome(
                                sessionId       = sessionId,
                                outcome         = "ACCEPTED",
                                pointCount      = pts.size,
                                maxPressure     = maxPressureDuring,
                                maxSizeWidthPx  = maxSizeWidthDuring,
                                maxSizeHeightPx = maxSizeHeightDuring
                            )
                            // 連貫畫布：按紙界（含頁間隙）切段，分頁存檔，一筆可橫跨多頁
                            val srcPage = pageIndexRef.value
                            val segs = splitStrokeByPage(
                                pts = pts,
                                canvasH = canvasPixelSizeState.value.height,
                                gapPx = pageGapPxRef.value,
                                srcPage = srcPage,
                                pageCount = pageCountRef.value
                            )
                            var lastPage = srcPage
                            // F3：記下本紙段 id 交接（提筆不清活路徑，等資料流出現）；
                            // 鄰頁段由各紙提交流接著畫。
                            val bridged = mutableSetOf<String>()
                            // 提筆正規化吃「收點當時」的框架，不是現在的 canvas：
                            // 手勢途中若 relayout，兩者不同 → 整筆按比例偏移。
                            val frameW = frameOfPoints.width
                            val frameH = frameOfPoints.height
                            segs.forEach { (pg, segPts) ->
                                if (segPts.size >= 2) {
                                    viewModel.saveStroke(
                                        segPts, selectedColor, activeTool, strokeWidth,
                                        targetPage = pg,
                                        frameW = frameW,
                                        frameH = frameH
                                    )?.let { id -> if (pg == pageIndexRef.value) bridged += id }
                                    lastPage = pg
                                }
                            }
                            bridgingIds = bridged.takeIf { it.isNotEmpty() }
                            bridgingSeq = if (bridgingIds != null) strokeGestureSeq else -1
                            // 交接法醫線：這一筆的存活完全靠「live→overlay→bitmap」三段接力，
                            // 出現閃爍時先看這行與後續 HANDOFF/CACHE 的 seq 對不對得上。
                            android.util.Log.w(
                                "InkFlowDiag",
                                "LIFT seq=$strokeGestureSeq ids=${bridgingIds?.joinToString(",") { it.take(8) } ?: "-"}" +
                                    " n=${pts.size} page=$srcPage"
                            )
                            // 最後一筆停在向量層（不烙位圖）：落庫後的第一幀就是清晰版。
                            // 順手烙一張交接快照：頁流在接下來幾幀裡可能排空一幀（剛落庫的
                            // 合併流還沒吐出這筆），那一幀靠這張補——墨不會消失。
                            bridged.lastOrNull()?.let { newHot ->
                                hotStrokeId = newHot
                                // 快照的寬度必須跟「最後一幀預覽」逐字相同：預覽畫的是
                                // currentPathPoints × docZoom（見上方 F2）。少了這一步，
                                // 提筆瞬間墨會突然換成另一個粗細＝使用者看到的「提筆閃」。
                                val snapPts = currentPathPoints.map { it.copy(width = it.width * zoomAtStrokeStart) }
                                liftedSnapshot = LiftedStrokeSnapshot(
                                    ids = bridged.toSet(),
                                    path = EnvelopeUtils.generateEnvelopePath(snapPts),
                                    color = selectedColor,
                                    isHighlighter = activeTool == Tool.HIGHLIGHTER,
                                    atMs = android.os.SystemClock.uptimeMillis()
                                )
                            } ?: run { liftedSnapshot = null }
                            eraseVisualActive = false
                            viewModel.setPageLock(false)
                            // M1 全活頁：目標紙本來就活著，無需激活（提筆零跳轉）
                        }
                    }
                    Tool.LASSO -> {
                        activePath.close()
                        // 全走跨頁聯集：切分保留同頁多段（閉環過界兩次不斷段），每段裁到本頁閉合，
                        // 同頁閉環裁剪後原樣＝舊單頁行為，不再分叉。
                        val lassoPts = currentPathPoints.map { Offset(it.x, it.y) }
                        val canvasW = canvasPixelSizeState.value.width
                        val canvasH = canvasPixelSizeState.value.height
                        LassoGeometry.fromFreeform(
                            pointsCanvas = lassoPts,
                            srcPage = pageIndexRef.value,
                            pageCount = pageCountRef.value,
                            gapPx = pageGapPxRef.value,
                            canvasW = canvasW,
                            canvasH = canvasH,
                            modelW = viewModel.modelWidth,
                            modelH = viewModel.modelHeight
                        )?.let { viewModel.selectLassoRegion(it) }
                    }
                    Tool.ERASER -> finishErase()
                    else -> { }
                }
                if (activeTool == Tool.PEN || activeTool == Tool.HIGHLIGHTER) {
                    // F3：活路徑等交接（上附 LaunchedEffect），這裡不清；
                    // 共享預覽也不在這裡關——鄰頁段還在路上，現在關鄰頁閃。
                    // 關閉由交接完成/2s兜底統一做。存了零段（全是單點碎段）才立刻清。
                    if (bridgingIds == null) {
                        activePath.reset()
                        activeEnvelopePath.reset()
                        currentPathPoints.clear()
                        activePathVersion++
                        viewModel.publishInFlight(null)
                    }
                } else {
                    activePath.reset()
                    activeEnvelopePath.reset()
                    activePathVersion++
                    currentPathPoints.clear()
                    viewModel.publishInFlight(null)
                }
                // R1：擦除手勢關門（合併推一格）。橡皮擦與「已觸發的快滑擦除」都開過門，
                // 所以這裡看旗標而不是看工具——看工具的話快滑那條永遠關不了門。
                if (eraseGestureBegan) viewModel.endEraseGesture()
                eraseVisualActive = false
                // 兜底：自由筆各提交路徑在此統一清鎖（PEN/HL 已在分支內清過，重複無害）
                viewModel.setPageLock(false)
            }
        }
        .drawWithCache {
            // P0-A：inkBakeVersion 換圖通知——swap 後 inkBakeVersion++ → 這個 draw block 重跑畫新位圖
            val bakedSwap = inkBakeVersion.intValue
            // 頁流重發訂閱（位移不進 inkScaleKey）：stamp++ → factory 重跑 → dirty-rect 增量接手
            val inkDataRev = inkStamp.intValue
            // 選取訂閱：select/commit/deselect → factory 重跑 → 排除集合過渡擦除／補回會執行
            val selRev = selectionStampRef.value
            // 過期快照兜底丟棄（2s 後一定不需要了；過期後至多多畫一個無害的 expired 檢查）。
            liftedSnapshot?.let { snap ->
                if (android.os.SystemClock.uptimeMillis() - snap.atMs > 2000L) {
                    liftedSnapshot = null
                }
            }
            // 紙尺寸改從 collectAsState 的 paperStyle 讀（Compose State）：讀了才建立訂閱，
            // 紙尺寸變更才會讓這張墨跡快取失效。原本讀 viewModel.modelWidth 是普通 getter
            // （_paperStyle.value），不訂閱 → 改紙尺寸時背景重畫、墨跡留在舊比例。
            val paper = paperStyle
            val modelW = paper.widthPt
            val modelH = paper.heightPt
            val sx = DocTransform.scaleX(size.width, modelW)
            val sy = DocTransform.scaleY(size.height, modelH)

            val strokes = committedStrokes
            // 快照兜底：這筆已經被刪除／復原 → 立即丟棄殘影。
            // 判定必須是「曾經出現過、現在不見了」：交接完成後頁流偶爾仍會排空一幀
            // （實機 log overlay=0），單純「不在 strokes 裡」會把快照誤刪＝閃爍回來。
            liftedSnapshot?.let { snap ->
                if (bridgingIds == null) {
                    val stillThere =
                        strokes.any { it.stroke.id in snap.ids } ||
                            pendingIds.any { it in snap.ids }
                    if (stillThere) {
                        snap.seenInFlow = true
                    } else if (snap.seenInFlow) {
                        liftedSnapshot = null
                    }
                }
            }
            val preview = commitPreview
            val previewIds = preview?.map { it.stroke.id }?.toHashSet() ?: emptySet()
            // ── 這一輪「一定不能烙進位圖」的集合 ──
            // 提交預覽、樂觀層（DB 未回報）、現任熱筆。熱筆留向量層是刻意的：螢光筆 alpha
            // 疊兩層會偏暗，位圖封頂也會糊一級。
            // 注意：**舊熱筆刻意不在這裡**——它們要靠這次烘焙回到位圖，向量層才會放掉它們
            //（見 bakedIds 的不變式）。把它們排除掉就等於把閃爍寫進設計。
            val hotId = hotStrokeId?.takeIf { it !in previewIds }
            // LASSO 選取的筆劃也要從 bake 排除（否則拖曳時原筆跡凍結在位圖上、
            // 只有 overlay 的 accent 副本跟手 → 「感覺沒在動、放開才跳」）。
            // 排除集合變化時在下方合併 dirty bounds，強制局部重烤才能真正抹掉舊像素。
            val selectionExcludeIds: Set<String> =
                if (ownSelectedStrokes.isEmpty()) emptySet() else ownSelectedStrokes.map { it.stroke.id }.toSet()
            val bakeExcludeIds: Set<String> =
                if (previewIds.isEmpty() && pendingIds.isEmpty() && hotId == null && selectionExcludeIds.isEmpty()) {
                    emptySet()
                } else {
                    HashSet<String>(previewIds.size + pendingIds.size + selectionExcludeIds.size + 1).apply {
                        addAll(previewIds)
                        addAll(pendingIds)
                        addAll(selectionExcludeIds)
                        if (hotId != null) add(hotId)
                    }
                }

            val bmpWidth = size.width.toInt().coerceAtLeast(1)
            val bmpHeight = size.height.toInt().coerceAtLeast(1)
            // 筆跡快取上限：見 [inkCacheScale]。這裡原本是固定 3072——
            // 平板整頁 3100×4400 會被縮到 0.70 再放大 1.43 倍，提筆那一刻
            // （最後一筆從向量層烙進位圖）就是使用者看到的「糊一下」。
            // 現在 1:1 優先，只有縮放到爆掉（畫布超過預算）才降採樣。
            val cacheScale = inkCacheScale(bmpWidth, bmpHeight)
            val cacheW = (bmpWidth * cacheScale).toInt().coerceAtLeast(1)
            val cacheH = (bmpHeight * cacheScale).toInt().coerceAtLeast(1)
            // P1 手勢凍結：pinchActive 時沿用上次的快取圖（不配置不重畫），
            // 讀 pinchActive 會訂閱——放開切換時失效重建，剛好是銳利化的時機。
            // 注意：手勢中若有新墨提交（理論上不會，門衛會棄筆），放開後自然重建。
            val reuseBmp = pinchReuse.bmp
            val frozen = pinchActive && reuseBmp != null
            // P0-A：尺寸變了（縮放後）沿用舊位圖拉伸繪製，不在主執行緒配置新位圖或全頁重烘；
            // 一律派發到 inkBakeRequests 背景重烘，swap 後由 inkBakeVersion 通知換圖。
            var cachedImage = reuseBmp
            // 診斷用（CACHE 那行 log 在烘焙之後才印，需要跨出 if 區塊）
            var fullRebuild = false
            var bakedFresh = true
            var dirty: Rect? = null
            var boundsBroken = false

            // 凍結中連畫都跳過：整塊重建是捏合卡頓的最大頭
            if (!frozen) {
                // 位圖內筆跡的「有序簽名」：只收真的會烙進位圖的那些（排除預覽／向量層）。
                // 純換序（同步換 rowid、桌機端覆寫）不產生任何髒區，舊寫法靠
                // 「dirty==null → 全頁重畫」偶然救到；現在那條路被跳過，得自己補。
                // 只在「同一個集合、不同順序」時才全量：一般的加筆／刪筆交給髒區。
                // ── 髒矩形增量重建 ──
                // 全量（清空整張＋重畫全筆）是擦除卡頓的最大頭（實測 draw 17.6ms/幀）。
                // 只清髒區、只重畫相交的筆：重繪成本跟著變化量走。髒區過大／新圖／
                // 尺寸變 → 走全量（舊行為原樣保留當 fallback）。
                val scaleKey = inkScaleKey(sx, sy)
                bakedFresh = lastInkScale.contentKey == scaleKey && cachedImage != null
                val pageRect = Rect(0f, 0f, modelW, modelH)
                fun strokeRect(swp: StrokeWithSamples) = Rect(
                    swp.stroke.boundsLeft, swp.stroke.boundsTop,
                    swp.stroke.boundsRight, swp.stroke.boundsBottom
                )
                // 刪筆的髒：淘汰前先把舊 bounds 留下來（淘汰後就問不到了）
                val keepIds = HashSet<String>(strokes.size + 16)
                strokes.forEach {
                    if (it.stroke.shapeType == null) keepIds.add(it.stroke.id)
                    if (!strokeRect(it).hasFiniteBounds()) boundsBroken = true
                }
                committedPathCache.forEach { (id, cached) ->
                    if (id !in keepIds) {
                        val old = Rect(cached.boundsLeft, cached.boundsTop, cached.boundsRight, cached.boundsBottom)
                        // 被刪的列若帶非有限舊 bounds：髒區表達不出來，硬走增量會留鬼影，直接全量。
                        if (!old.hasFiniteBounds()) boundsBroken = true
                        dirty = unionRect(dirty, old)
                    }
                }
                // 淘汰已刪筆跡（普通 Map 操作，不寫 State、不二次失效）
                committedPathCache.keys.removeAll { it !in keepIds }
                // 加筆＋改筆的髒（改筆要舊 bounds ∪ 新 bounds，否則舊位置殘影）
                strokes.forEach { swp ->
                    if (swp.stroke.shapeType != null) return@forEach
                    if (swp.stroke.id in previewIds) return@forEach
                    // 向量層（樂觀筆／熱筆）不進位圖，也就不產生髒區——它每幀重畫。
                    if (swp.stroke.id in bakeExcludeIds) return@forEach
                    val cached = committedPathCache[swp.stroke.id]
                    if (cached == null || !swp.matches(cached)) {
                        if (cached != null) {
                            val old = Rect(cached.boundsLeft, cached.boundsTop, cached.boundsRight, cached.boundsBottom)
                            if (!old.hasFiniteBounds()) boundsBroken = true
                            dirty = unionRect(dirty, old)
                        }
                        dirty = unionRect(dirty, strokeRect(swp))
                    }
                }
                // 形狀的髒：同 id 新 bounds＝移動／縮放過，舊 bounds 也要清
                val shapeIds = HashSet<String>()
                strokes.forEach { swp ->
                    if (swp.stroke.shapeType == null) return@forEach
                    if (swp.stroke.id in previewIds) return@forEach
                    shapeIds.add(swp.stroke.id)
                    val now = strokeRect(swp)
                    val old = lastShapeBounds[swp.stroke.id]
                    if (old == null || old != now) {
                        if (old != null) dirty = unionRect(dirty, old)
                        dirty = unionRect(dirty, now)
                    }
                }
                lastShapeBounds.keys.removeAll {
                    val gone = it !in shapeIds
                    // 被刪形狀帶非有限舊 bounds：同上，表達不出髒區，直接全量。
                    if (gone && lastShapeBounds[it]?.hasFiniteBounds() == false) boundsBroken = true
                    gone
                }
                strokes.forEach { swp ->
                    if (swp.stroke.shapeType != null && swp.stroke.id !in previewIds) {
                        lastShapeBounds[swp.stroke.id] = strokeRect(swp)
                    }
                }

                // LASSO 選取 bake exclusion 過渡：進入/離開排除集合的筆劃 bounds
                // 合併進 dirty，讓重烤路徑真正擦掉舊像素（否則 cachedImage 會殘留原位墨跡）。
                if (selectionExcludeIds != lastSelectionExclude) {
                    val entering = selectionExcludeIds - lastSelectionExclude
                    val leaving = lastSelectionExclude - selectionExcludeIds
                    strokes.forEach { swp ->
                        if (swp.stroke.id in entering || swp.stroke.id in leaving) {
                            dirty = unionRect(dirty, strokeRect(swp))
                        }
                    }
                    // M2.1：leaving 發生時 DB 回寫若還在途，dirty repaint 會用舊座標把墨畫回位圖。
                    // 打 sentinel 強制下一幀 full rebuild/背景重烤，覆蓋該舊像素，消除「舊墨殘留」。
                    if (leaving.isNotEmpty() && commitPreview != null) {
                        lastInkScale.contentKey = BAKE_CONTENT_KEY_STALE
                    }
                    lastSelectionExclude = selectionExcludeIds
                }

                // 非有限 bounds 熔斷（在下面 keepIds/shape 兩輪遍歷里順手收集，
                // 不另開一輪）：退化輸入的包絡數學可能產出 NaN/Inf；minOf/maxOf
                // 會靜默吞掉 NaN，髒區算小、相交全 false，該畫的全被跳過
                // （無崩潰無 log 的純隱形）。看到一個就走全量：無條件重畫一定對。
                //
                // dirty == null 不再是「未知，全部重畫」：加了向量層（樂觀筆／熱筆）之後，
                // 每次提筆都會有一幀「只有向量層變、位圖完全沒變」，那時 dirty 就是 null。
                // 舊語義會在那裡全頁重畫（實測 17.6ms/幀）＝修閃爍反而多一次卡頓。
                // 真正的「一定要全量」是：比例變、bounds 壞掉、這張圖是剛配置的。
                // R2：不再有「內容指紋」全頁重烤——dirty-rect 增量接手增刪改位移；
                // 整頁重烤只剩一種情形：解析度 key 不符／cachedImage==null。
                // ⚠️ 不可讓 fullRebuild 依賴 boundsBroken 或 damageCoverage：
                //    pitfalls.md（INKBG 無限重烘 OOM，2026-11-14）明文記載，
                //    含不 finite bounds 的殘缺頁面每幀成立 → 60fps 背景重烘 → 512MB 爆。
                fullRebuild = !bakedFresh
                val canBackgroundBake = fullRebuild && cachedImage != null
                // 這張位圖「會裝進去」哪些筆：非高亮、且不在排除清單。
                // swap 時原封不動寫進 bakedIds——向量層靠它決定哪幾筆可以退場。
                val includedIds: Set<String> =
                    if (strokes.isEmpty()) emptySet() else HashSet<String>(strokes.size).apply {
                        for (swp in strokes) {
                            if (swp.stroke.isHighlighter) continue
                            if (swp.stroke.id in bakeExcludeIds) continue
                            add(swp.stroke.id)
                        }
                    }
                if (canBackgroundBake && inkBakeInFlight.compareAndSet(false, true)) {
                    inkBakeRequests.trySend(
                        InkBakeRequest(
                            pageIndex = pageIndex,
                            targetWidth = cacheW,
                            targetHeight = cacheH,
                            scaleX = sx * cacheScale,
                            scaleY = sy * cacheScale,
                            strokes = ArrayList(strokes),
                            excludeIds = bakeExcludeIds,
                            includedIds = includedIds,
                            contentKey = scaleKey
                        )
                    )
                }
                if (cachedImage == null) {
                    // P0-A 冷啟動：沒有 stale 可沿用，主執行緒配置並全烘，保底首幀有圖
                    cachedImage = ImageBitmap(cacheW, cacheH, ImageBitmapConfig.Argb8888).also { pinchReuse.bmp = it }
                    val c0 = androidx.compose.ui.graphics.Canvas(cachedImage!!)
                    c0.scale(sx * cacheScale, sy * cacheScale)
                    strokes.forEach { swp ->
                        if (swp.stroke.isHighlighter) return@forEach
                        if (swp.stroke.id in bakeExcludeIds) return@forEach
                        if (swp.stroke.shapeType != null) {
                            drawShapeOnCanvas(c0, swp.stroke, swp.points)
                        } else {
                            val cached = committedPathCache[swp.stroke.id]
                            val path = if (cached != null && swp.matches(cached)) {
                                cached.path
                            } else {
                                swp.points.toComposePath().also {
                                    committedPathCache[swp.stroke.id] = CachedStrokePath(
                                        path = it,
                                        pointCount = swp.points.size,
                                        boundsLeft = swp.stroke.boundsLeft,
                                        boundsTop = swp.stroke.boundsTop,
                                        boundsRight = swp.stroke.boundsRight,
                                        boundsBottom = swp.stroke.boundsBottom
                                    )
                                }
                            }
                            drawPathOnCanvas(c0, path,
                                Color(swp.stroke.color), swp.stroke.strokeWidth, swp.stroke.isHighlighter)
                        }
                    }
                    lastInkScale.contentKey = scaleKey
                    // 帳與圖同一瞬間補上：否則這一輪算向量層時會把整頁的筆再畫一遍
                    // （pen 疊兩層邊緣會略深，而且白做一次全頁路徑）。
                    bakedIds = includedIds
                } else if (!fullRebuild && dirty != null) {
                    val zone = dirty.inflate(INK_DIRTY_INFLATE_PX).intersect(pageRect)
                    // c0 內已 scale(sx*cacheScale)，clip/clear 都在 model 座標
                    val c0 = androidx.compose.ui.graphics.Canvas(cachedImage!!)
                    c0.save()
                    c0.scale(sx * cacheScale, sy * cacheScale)
                    c0.clipRect(zone.left, zone.top, zone.right, zone.bottom)
                    c0.drawRect(
                        zone.left, zone.top, zone.right, zone.bottom,
                        Paint().apply { blendMode = BlendMode.Clear }
                    )
                    // 髒區內重畫過的筆現在確定在位圖裡——補進帳，向量層才不會重疊畫一次。
                    val repaintedIds = HashSet<String>(8)
                    strokes.forEach { swp ->
                        if (swp.stroke.isHighlighter) return@forEach
                        if (swp.stroke.id in bakeExcludeIds) return@forEach
                        if (!strokeRect(swp).inflate(INK_DIRTY_INFLATE_PX).overlaps(zone)) return@forEach
                        repaintedIds.add(swp.stroke.id)
                        if (swp.stroke.shapeType != null) {
                            drawShapeOnCanvas(c0, swp.stroke, swp.points)
                        } else {
                            val cached = committedPathCache[swp.stroke.id]
                            val path = if (cached != null && swp.matches(cached)) {
                                cached.path
                            } else {
                                swp.points.toComposePath().also {
                                    committedPathCache[swp.stroke.id] = CachedStrokePath(
                                        path = it,
                                        pointCount = swp.points.size,
                                        boundsLeft = swp.stroke.boundsLeft,
                                        boundsTop = swp.stroke.boundsTop,
                                        boundsRight = swp.stroke.boundsRight,
                                        boundsBottom = swp.stroke.boundsBottom
                                    )
                                }
                            }
                            drawPathOnCanvas(c0, path,
                                Color(swp.stroke.color), swp.stroke.strokeWidth, swp.stroke.isHighlighter)
                        }
                    }
                    c0.restore()
                    if (repaintedIds.isNotEmpty()) bakedIds = bakedIds + repaintedIds
                }
            }

            // ── 向量層：位圖帳上還沒有的筆 ──
            // 不變式：不在 [bakedIds] 裡的筆一律用向量畫。bakedIds 只在位圖真的換上時更新，
            // 所以「舊熱筆退出向量層」與「含它的位圖換上」是同一瞬間——中間不會有一幀
            // 兩邊都沒有（那就是使用者回報的「上一個筆畫閃一下」，實測 53ms 空窗）。
            // 必須在烘焙之後算：冷啟動那一輪烘完會立刻把帳補上。
            // 不做「size 比對就跳過」的捷徑：bakedIds 可能含舊 id（被刪的筆），
            // size 相同不代表「所有現存筆都在帳上」——那個誤判會重演閃爍。
            val overlayIds = HashSet<String>()
            for (i in strokes.indices.reversed()) {
                val st = strokes[i].stroke
                if (st.shapeType != null || st.isHighlighter) continue
                if (st.id in bakedIds) continue
                overlayIds.add(st.id)
                if (overlayIds.size >= INK_OVERLAY_MAX) break
            }
            // 向量層要畫的清單在快取階段就算好：onDrawBehind 每幀都跑，不要在裡面掃全頁。
            val overlayStrokes = if (overlayIds.isEmpty()) {
                emptyList()
            } else {
                strokes.filter { it.stroke.id in overlayIds && it.stroke.shapeType == null }
            }
            // 交接中「這筆已經被畫到了」的判定基準是頁流，不是 overlay——熒光筆不進 overlay，
            // 問 overlay 會讓活筆（Multiply alpha 0.4）與 hlCommitted 疊兩層，
            // 提筆後比拖曳預覽深一截。頁流有就一定能被某一層畫到（高亮＝hlCommitted、
            // pen＝位圖或向量覆蓋），活筆就該讓位。
            val bridgedDrawn = bridgingIds?.let { ids -> strokes.any { it.stroke.id in ids } } == true
            android.util.Log.w(
                "InkFlowDiag",
                "CACHE p=$pageIndex canvas=${bmpWidth}x${bmpHeight} " +
                    "scale=${"%.3f".format(cacheScale)} cache=${cacheW}x${cacheH} " +
                    "overlay=${overlayStrokes.size} baked=${bakedIds.size} " +
                    "hot=${hotStrokeId?.take(8) ?: "-"} " +
                    "bridge=${bridgingIds?.size ?: 0} drawn=$bridgedDrawn " +
                    "dirty=${dirty != null} full=$fullRebuild fresh=$bakedFresh broken=$boundsBroken " +
                    "seq=$strokeGestureSeq bseq=$bridgingSeq rev=$inkDataRev sel=${selRev.size}"
            )

            // 真正「空窗」的判定：交接還在等、向量層沒有它、活路徑也已經空了 →
            // 這一幀這筆墨三層都沒畫（畫面真的會缺一筆）。正常交接不會落在這裡：
            // 資料流到之前活路徑還在，資料流到了 overlay 就有它。
            if (bridgingIds != null && !bridgedDrawn && overlayStrokes.isEmpty() && activePath.isEmpty
                && liftedSnapshot == null
            ) {
                android.util.Log.w(
                    "InkFlowDiag",
                    "GAP p=$pageIndex seq=$strokeGestureSeq bseq=$bridgingSeq" +
                        " bridge=${bridgingIds?.joinToString(",") { it.take(8) }}" +
                        " snap=null"
                )
            }

            onDrawBehind {
                val currentPaperStyle = paperStyle
                val currentImageAnns = imageAnnotations

            // Background lines — drawn as the very bottom layer below all annotations and strokes.
            if (currentPaperStyle.background != PageBackground.BLANK) {
                val lineColor = androidx.compose.ui.graphics.Color(0x33000000)
                val step = when (currentPaperStyle.background) {
                    PageBackground.NARROW_RULED -> 18f
                    PageBackground.WIDE_RULED   -> 42f
                    else                        -> 28f
                }
                val drawHLines = currentPaperStyle.background == PageBackground.RULED ||
                    currentPaperStyle.background == PageBackground.NARROW_RULED ||
                    currentPaperStyle.background == PageBackground.WIDE_RULED ||
                    currentPaperStyle.background == PageBackground.GRID
                if (drawHLines) {
                    var lineY = step
                    while (lineY < viewModel.modelHeight) {
                        drawLine(
                            color       = lineColor,
                            start       = Offset(0f, lineY * sy),
                            end         = Offset(size.width, lineY * sy),
                            strokeWidth = 1f
                        )
                        lineY += step
                    }
                }
                if (currentPaperStyle.background == PageBackground.GRID) {
                    var lineX = step
                    while (lineX < viewModel.modelWidth) {
                        drawLine(
                            color       = lineColor,
                            start       = Offset(lineX * sx, 0f),
                            end         = Offset(lineX * sx, size.height),
                            strokeWidth = 1f
                        )
                        lineX += step
                    }
                }
                if (currentPaperStyle.background == PageBackground.DOT_GRID) {
                    // 點格紙：1.7 萬個 drawCircle/幀 換成一個 BitmapShader tile + 一次 fillRect。
                    // tile 依「步距 px」快取，紙張/縮放比例變了才重建。
                    val stepPxX = step * sx
                    val stepPxY = step * sy
                    val key = "${stepPxX}x${stepPxY}"
                    if (dotTileCache.key != key) {
                        val tw = stepPxX.toInt().coerceAtLeast(2)
                        val th = stepPxY.toInt().coerceAtLeast(2)
                        val tile = android.graphics.Bitmap.createBitmap(
                            tw, th, android.graphics.Bitmap.Config.ARGB_8888
                        )
                        val tc = android.graphics.Canvas(tile)
                        val tp = android.graphics.Paint().apply {
                            color = 0x33000000
                            isAntiAlias = true
                        }
                        tc.drawCircle(0f, 0f, 1.5f, tp)
                        dotTileCache.bmp = tile
                        dotTileCache.paint = android.graphics.Paint().apply {
                            shader = android.graphics.BitmapShader(
                                tile, android.graphics.Shader.TileMode.REPEAT,
                                android.graphics.Shader.TileMode.REPEAT
                            )
                        }
                        dotTileCache.key = key
                    }
                    val fill = dotTileCache.paint
                    if (fill != null) {
                        // 從 (step, step) 開始鋪，點的位置與舊迴圈一致。
                        drawIntoCanvas { cvs ->
                            cvs.save()
                            cvs.translate(stepPxX, stepPxY)
                            cvs.nativeCanvas.drawRect(
                                0f, 0f,
                                (size.width - stepPxX).coerceAtLeast(0f),
                                (size.height - stepPxY).coerceAtLeast(0f),
                                fill
                            )
                            cvs.restore()
                        }
                    }
                }
            }

            // Image annotations — drawn BELOW strokes (above PDF layer which is a separate Composable)
            currentImageAnns.forEach { dbAnn ->
                // 跨頁拖曳 overlay 期間，選取中的圖片由 PageWorkspace 那個 clip=false 的層統一畫：
                // 紙內這層會被紙界裁掉（拖往別頁時整張圖消失），也避免同像素／同選取框畫兩次。
                if (ownDragOverlayActive && dbAnn.id in selectedImageAnnotationIds) return@forEach
                // 圈選中的圖片改讀選取快照（見 selectedImageSnapshots 的說明：避免縮放閃爍）
                val ann = selectedImageSnapshots[dbAnn.id] ?: dbAnn
                // P0-B：依顯示長邊（model 尺寸 × 當下縮放，忽略旋轉微差，bucket 是 2 倍步距）選桶；缺圖 → 派發 decode
                val displayLongPx = maxOf((ann.modelWidth * sx).toInt(), (ann.modelHeight * sy).toInt()).coerceAtLeast(1)
                val requestPx = com.vic.inkflow.util.ImageAnnotationCache.bucketPx(displayLongPx)
                val bmp = com.vic.inkflow.util.ImageAnnotationCache.getFor(ann.uri, requestPx)
                if (bmp == null && !com.vic.inkflow.util.ImageAnnotationCache.isPendingOrFailed(ann.uri)) {
                    decodeRequests.trySend(ann.uri to requestPx)
                }
                if (bmp != null) {
                    val isSelectedInImageTool = ann.id == selectedImageAnnotationId && activeTool == Tool.IMAGE
                    val isSelectedInSelectionTool = ann.id in selectedImageAnnotationIds && activeTool == Tool.LASSO
                    // 落定判斷放在「繪製當下」而不是靠 LaunchedEffect 歸零預覽：
                    // commit 是 launch(IO) 非同步寫 DB，DB 更新觸發重繪的那一幀，
                    // LaunchedEffect 可能還沒跑到 → 繪製會疊上殘留預覽 = 新位置＋舊位移，
                    // 下一幀才修正 → 閃一下（實機回報：閃更快但仍看得出）。
                    // 這裡即時比對：已落定就直接吃 DB 值，零時序依賴。
                    val settleTarget = pendingImageSettleRef.value
                    val settled = settleTarget != null &&
                        settleTarget.id == ann.id && settleTarget.matches(ann)
                    val canvasRect = when {
                        isSelectedInSelectionTool -> modelTransformedImageRectToCanvasRect(
                            image = ann,
                            translation = selectionState.transform.translation,
                            scale = selectionState.transform.scale,
                            anchor = selectionTransformAnchor,
                            sx = sx,
                            sy = sy
                        )
                        else -> {
                            val dx = if (isSelectedInImageTool && !settled) imageMovePreview.x else 0f
                            val dy = if (isSelectedInImageTool && !settled) imageMovePreview.y else 0f
                            val base = Rect(
                                left = ann.modelX * sx + dx,
                                top = ann.modelY * sy + dy,
                                right = ann.modelX * sx + dx + ann.modelWidth * sx,
                                bottom = ann.modelY * sy + dy + ann.modelHeight * sy
                            )
                            val eff = if (isSelectedInImageTool && !settled)
                                effectiveImageRect(base, Offset.Zero, imageResizeAnchor, imageResizeScale)
                            else base
                            Rect(
                                left = eff.left,
                                top = eff.top,
                                right = eff.left + eff.width.coerceAtLeast(2f),
                                bottom = eff.top + eff.height.coerceAtLeast(2f)
                            )
                        }
                    }
                    // M5: rotation pivot is the unscaled base center (= gesture pivot); the
                    // in-flight rotate delta only applies to the IMAGE-tool-selected image,
                    // 且只在尚未落定時疊加（同上，settled 後 DB 已含新角度）。
                    val imgTheta = ann.rotation +
                        (if (isSelectedInImageTool && !settled) imageRotatePreview else 0f)
                    val imgPivot = if (isSelectedInSelectionTool) canvasRect.center else run {
                        val bx = ann.modelX * sx + (if (isSelectedInImageTool && !settled) imageMovePreview.x else 0f)
                        val by = ann.modelY * sy + (if (isSelectedInImageTool && !settled) imageMovePreview.y else 0f)
                        Offset(bx + ann.modelWidth * sx / 2f, by + ann.modelHeight * sy / 2f)
                    }
                    rotate(imgTheta, imgPivot) {
                        drawImage(
                            image     = bmp,
                            dstOffset = IntOffset(canvasRect.left.toInt(), canvasRect.top.toInt()),
                            dstSize   = IntSize(canvasRect.width.toInt().coerceAtLeast(2), canvasRect.height.toInt().coerceAtLeast(2))
                        )
                        // LASSO 選取的圖片：畫 accent 選取框（無把手，純標識「這張有被選」），
                        // 否則只選圖片時使用者完全看不出哪些圖片屬於選取範圍。
                        if (isSelectedInSelectionTool) {
                            drawLiquidGlassSelectionFrame(
                                rect = canvasRect,
                                accent = accentColor,
                                handles = emptyList(),
                                handleCenters = emptyList()
                            )
                        }
                    }
                }
            }

            // Committed strokes from DB.
            // Play the cached strokes layer! 快取是封頂小圖，放大回全畫布
            drawImage(
                image = cachedImage!!,
                dstSize = IntSize(
                    size.width.toInt().coerceAtLeast(1),
                    size.height.toInt().coerceAtLeast(1)
                ),
                // 放大用 High 品質，歷史墨不糊。
                filterQuality = FilterQuality.High
            )

            // 高亮筆向量層：快取烙不進透明位圖——Multiply 需要底，透明底烤出來的是
            // 半透明灰球（文字被洗灰、黃色不見）；與活筆／快照／PDF／桌面同源
            // （:shared strokePaint＝Multiply）。每幀向量直畫，髒區邏輯不受影響
            // （pen 底層不變），刪筆／復原照常走 strokes 的增刪。
            run {
                val hlCommitted = strokes.filter {
                    it.stroke.isHighlighter && it.stroke.shapeType == null
                }
                if (hlCommitted.isNotEmpty()) {
                    drawIntoCanvas { cvs ->
                        cvs.save()
                        cvs.scale(sx, sy)
                        hlCommitted.forEach { swp ->
                            val cached = committedPathCache[swp.stroke.id]
                            val path = if (cached != null && swp.matches(cached)) {
                                cached.path
                            } else {
                                swp.points.toComposePath().also {
                                    committedPathCache[swp.stroke.id] = CachedStrokePath(
                                        path = it,
                                        pointCount = swp.points.size,
                                        boundsLeft = swp.stroke.boundsLeft,
                                        boundsTop = swp.stroke.boundsTop,
                                        boundsRight = swp.stroke.boundsRight,
                                        boundsBottom = swp.stroke.boundsBottom
                                    )
                                }
                            }
                            drawPathOnCanvas(
                                cvs, path, Color(swp.stroke.color),
                                swp.stroke.strokeWidth, swp.stroke.isHighlighter
                            )
                        }
                        cvs.restore()
                    }
                }
            }

            // ── 向量層：樂觀筆（DB 未回報）＋ 最後一筆熱筆 ──
            // 畫在快取圖之上、提交預覽與選取框之下。用同一支 drawPathOnCanvas，
            // 幾何與混合模式跟烙進位圖那條逐字一致（蟳光筆的 Multiply 對同一張底）。
            if (overlayStrokes.isNotEmpty()) {
                overlayPathCache.keys.retainAll(overlayIds)
                drawIntoCanvas { cvs ->
                    cvs.save()
                    cvs.scale(sx, sy)
                    overlayStrokes.forEach { swp ->
                        val cached = overlayPathCache[swp.stroke.id]
                        val path = if (cached != null && swp.matches(cached)) {
                            cached.path
                        } else {
                            swp.points.toComposePath().also {
                                overlayPathCache[swp.stroke.id] = CachedStrokePath(
                                    path = it,
                                    pointCount = swp.points.size,
                                    boundsLeft = swp.stroke.boundsLeft,
                                    boundsTop = swp.stroke.boundsTop,
                                    boundsRight = swp.stroke.boundsRight,
                                    boundsBottom = swp.stroke.boundsBottom
                                )
                            }
                        }
                        drawPathOnCanvas(
                            cvs, path, Color(swp.stroke.color),
                            swp.stroke.strokeWidth, swp.stroke.isHighlighter
                        )
                    }
                    cvs.restore()
                }
            } else if (overlayPathCache.isNotEmpty()) {
                overlayPathCache.clear()
            }

            // 交接快照：頁流排空一幀時向量層沒有這筆墨——補畫本地快照。
            // 向量層畫得到（merged 頁流已到）就讓路；絕不疊兩層（高亮筆 Alpha×2 會偏暗）。
            liftedSnapshot?.let { snap ->
                if (snap.ids.contains(hotStrokeId)) {
                    // 判定基準是「頁流有沒有吐出這筆」，不是 overlay——熒光筆根本不進 overlay，
                    // 問 overlay 會讓快照整段疊在 hlCommitted 上 Multiply 兩次（飽和×2）：
                    // 提筆後的熒光筆比拖曳預覽明顯深一截，就是使用者回報的「預覽和實際上不一樣」。
                    // 頁流有（含樂觀層）→ 它一定能被某一層畫到：高亮走 hlCommitted、
                    // pen 走位圖或向量覆蓋；頁流沒有才輪到這張快照補上。
                    // 覆蓋來源只有三種：頁流有這筆（pen 走位圖／向量覆蓋、高亮走 hlCommitted）；
                    // 交接還在等（bridgingIds 有這批）→ 活筆路徑本身就在畫它（!bridgedDrawn 時）；
                    // 都沒有（2s 逾時或新手勢已接走活筆）才輪到這張快照補上。
                    val covered = snap.ids.any { it in committedIds } ||
                        (bridgingIds?.any { it in snap.ids } == true)
                    if (!covered) {
                        if (snap.isHighlighter) {
                            drawIntoCanvas { cvs ->
                                hlPreviewPaint.color = snap.color.copy(alpha = HIGHLIGHTER_ALPHA)
                                cvs.drawPath(snap.path, hlPreviewPaint)
                            }
                        } else {
                            drawPath(snap.path, snap.color)
                        }
                    }
                }
            }

            val preview = commitPreview
            // 全活頁：只畫歸屬本紙的提交預覽（跨紙移動的另一段由歸屬紙畫）
            val ownPreview = preview?.filter { it.stroke.pageIndex == pageIndex }
            // While a commitPreview is active (DB write in flight after a move), draw the preview layer separately.
            if (!ownPreview.isNullOrEmpty()) {
                drawIntoCanvas { cvs ->
                    cvs.save()
                    cvs.scale(sx, sy)
                    // Preview layer: moved strokes at their committed (new) position.
                    ownPreview.forEach { swp ->
                        if (swp.stroke.shapeType != null) {
                            drawShapeOnCanvas(cvs, swp.stroke, swp.points)
                        } else {
                            drawPathOnCanvas(cvs, swp.points.toComposePath(),
                                Color(swp.stroke.color), swp.stroke.strokeWidth, swp.stroke.isHighlighter)
                        }
                    }
                    cvs.restore()
                }
            }

            // Selected strokes (highlighted with current preview transform applied).
            // dragPreviewActive 時由 Workspace overlay 繪製（可溢出紙界、置頂），此處讓位。
            // 全活頁：每紙畫歸屬本紙的子集（ownSelectedStrokes），跨紙選取各紙出框。
            if (selectedPathData.isNotEmpty() && !ownDragOverlayActive) {
                drawIntoCanvas { cvs ->
                    cvs.save()
                    cvs.scale(sx, sy)
                    if (isSelectionTransforming) {
                        applySelectionTransform(
                            canvas = cvs,
                            translation = selectionState.transform.translation,
                            scale = selectionState.transform.scale,
                            anchor = selectionTransformAnchor
                        )
                    }
                    selectedPathData.forEach { renderData ->
                        val swp = renderData.strokeWithPoints
                        val stroke = swp.stroke
                        if (stroke.shapeType != null) {
                            drawShapeOnCanvas(cvs, stroke, swp.points, tintColor = accentColor)
                        } else {
                            val path = renderData.path ?: return@forEach
                            drawPathOnCanvas(cvs, path, accentColor, stroke.strokeWidth, stroke.isHighlighter)
                        }
                    }
                    cvs.restore()
                }
            }
            // 聯合選取框：只要「本頁有框」就畫（墨／圖／字都算，純文字選取也要看得見）。
            // 框與手勢共用 selectionFrameRectOnPage／drawSelectionFrameOnPage，不再各自算一份。
            if (!ownDragOverlayActive && activeTool == Tool.LASSO) {
                val frameRect = selectionFrameRectOnPage(selectionState, pageIndex, sx, sy)
                if (frameRect != null) {
                    drawSelectionFrameOnPage(
                        state = selectionState,
                        page = pageIndex,
                        sx = sx,
                        sy = sy,
                        accent = accentColor,
                        // 把手只在墨/圖子集非空時出現：字不支援套索縮放，有把手沒功能更騙人。
                        showHandles = ownPageSelection.strokes.isNotEmpty() ||
                            ownPageSelection.images.isNotEmpty(),
                        dashPhase = if (isSelectionTransforming) 0f else lassoDashPhase,
                        animateDash = !isSelectionTransforming,
                        visualRadiusPx = handleVisualRadiusPx,
                        haloRadiusPx = handleHaloRadiusPx,
                        pressedHandleCenter = pressedHandleCenter
                    )
                }
            }

                // Selection handles for the currently selected image
                val selImgId  = selectedImageAnnotationId
                val selImgAnn = if (selImgId != null && activeTool == Tool.IMAGE)
                    imageAnnotations.firstOrNull { it.id == selImgId } else null
                if (selImgAnn != null) {
                    val r = imageAnnotationRect(selImgAnn, sx, sy)
                    // 選取框要跟著內容一起落定（否則框先跳一步、內容後跳 → 框脫節＋二次閃）。
                    val settleT = pendingImageSettleRef.value
                    val imgSelSettled = settleT != null &&
                        settleT.id == selImgAnn.id && settleT.matches(selImgAnn)
                    val selMove = if (imgSelSettled) Offset.Zero else imageMovePreview
                    val selScale = if (imgSelSettled) 1f else imageResizeScale
                    val selAnchor = if (imgSelSettled) null else imageResizeAnchor
                    val selRot = if (imgSelSettled) 0f else imageRotatePreview
                    val imgSelRect = effectiveImageRect(r, selMove, selAnchor, selScale)
                    // M5: frame + handles live in local space, rotated about the same pivot
                    // the gesture uses (committed+move center).
                    val imgTheta = selImgAnn.rotation + selRot
                    val imgPivot = r.translate(selMove).center
                    val rotTopLocal = Offset(imgSelRect.center.x, imgSelRect.top)
                    val rotHandleLocal = rotTopLocal + Offset(0f, -rotHandleGapPx)
                    val imgCorners = listOf(
                        imgSelRect.topLeft, imgSelRect.topRight,
                        imgSelRect.bottomLeft, imgSelRect.bottomRight
                    )
                    // pressedHandleCenter 存的是「local 未旋轉」座標（手勢那側算的），
                    // 這裡也在 rotate{} 內用 local 座標比，兩邊同一空間才對得上。
                    val anyHandlePressed = pressedHandleCenter != null
                    rotate(imgTheta, imgPivot) {
                        drawLine(
                            color = accentColor.copy(alpha = 0.6f),
                            start = rotTopLocal,
                            end = rotHandleLocal,
                            strokeWidth = 2f
                        )
                        drawLiquidGlassSelectionFrame(
                            rect = imgSelRect,
                            accent = accentColor,
                            handles = imgCorners.map { c ->
                                HandleVisual(
                                    center = c,
                                    pressed = if (c == pressedHandleCenter) 1f else 0f
                                )
                            } + HandleVisual(
                                // 旋轉把手畫成缺口環＋箭頭，跟四角的實心點區分
                                // （形狀區分、不動色票，保持單一 indigo 語言）。
                                center = rotHandleLocal,
                                pressed = if (pressedHandleCenter == rotHandleLocal) 1f else 0f,
                                isRotation = true
                            ),
                            visualRadiusPx = handleVisualRadiusPx,
                            haloRadiusPx = handleHaloRadiusPx,
                            strokeScale = if (anyHandlePressed)
                                com.vic.inkflow.ui.theme.Handles.PressedStrokeScale else 1f
                        )
                    }
                }

                // Text annotations — apply move/resize delta for the selected annotation
                drawIntoCanvas { composeCanvas ->
                    // 文字流：拖一個成員時整條一起跟手（VM 也是整條一起寫）。
                    // 少了這段，預覽只有那一格在動、放手後鄰居才跳定位 → 看起來像「拉壞了」。
                    val selFlowAnn = textAnnotations.firstOrNull { it.id == selectedTextAnnotationId }
                    val selFlowId = selFlowAnn?.flowId
                    textAnnotations.forEach { ann ->
                        // 打字中的那一段不畫，位置由輸入框獨佔（否則新舊兩份文字疊在一起）。
                        // 以前是靠輸入框的白底卡片蓋住它——所以輸入框才長得跟成品完全不同。
                        if (ann.id == inlineTextEditId) return@forEach
                        val isSelected = ann.id == selectedTextAnnotationId
                        val inSelFlow = selFlowId != null && ann.flowId == selFlowId &&
                            ann.pageIndex == selFlowAnn?.pageIndex
                        // 同圖片：落定後 DB 已含新值，不再疊預覽（否則閃一下）。見 PendingTextSettle。
                        val tSettle = pendingTextSettleRef.value
                        val txtSettled = tSettle != null &&
                            tSettle.id == ann.id && tSettle.matches(ann)
                        // 只有「移動」會帶動整條流；字級/框寬是單格的預覽。
                        val moves = (isSelected || inSelFlow) && !txtSettled
                        // LASSO 選取的字也要跟手（平移＋等比縮放；model → px），
                        // 否則圈選拖曳時原文字釘在原位、只有 overlay 的幽靈副本在動。
                        val lassoSel = ann.id in vmSelectedTextIds
                        val selT = selectionState.transform
                        val lassoScale = if (lassoSel) selT.scale else 1f
                        val lassoAnchor = selectionTransformAnchor
                        val lassoDx = if (lassoSel)
                            (lassoAnchor.x + (ann.modelX - lassoAnchor.x) * lassoScale +
                                selT.translation.x - ann.modelX) * sx else 0f
                        val lassoDy = if (lassoSel)
                            (lassoAnchor.y + (ann.modelY - lassoAnchor.y) * lassoScale +
                                selT.translation.y - ann.modelY) * sy else 0f
                        val dx = (if (moves) textMoveDelta.x else 0f) + lassoDx
                        val dy = (if (moves) textMoveDelta.y else 0f) + lassoDy
                        val fsDelta = if (isSelected && !txtSettled) textFontSizeDelta else 0f
                        val bwDelta = if (isSelected && !txtSettled) textBoxWidthDelta else 0f
                        val effFont = ((ann.fontSize + fsDelta) * lassoScale).coerceAtLeast(4f)
                        textPaint.textSize = effFont * sy
                        textPaint.color = ann.colorArgb
                        // 文字框：框寬（＋拖曳預覽）決定折行與對齊；舊資料（boxWidth null）維持逐 `\n` 行。
                        val boxW = ann.boxWidth?.takeIf { it > 0f }?.plus(bwDelta)?.times(lassoScale)
                        val lines = if (boxW != null &&
                            (boxW != ann.boxWidth || fsDelta != 0f || lassoScale != 1f)
                        )
                            wrapTextLines(ann.text, effFont, boxW)
                        else textLines[ann.id].orEmpty()
                        // Multiline: modelY is the first-line baseline (matches PDF export).
                        val lineHeight = with(textPaint.fontMetrics) { -ascent + descent + leading }
                        lines.forEachIndexed { i, line ->
                            val alignOffPx = if (boxW != null)
                                textAlignOffsetModel(measureTextWidth(line, effFont), boxW, ann.alignment) * sx
                            else 0f
                            composeCanvas.nativeCanvas.drawText(
                                line, ann.modelX * sx + dx + alignOffPx, ann.modelY * sy + dy + i * lineHeight, textPaint
                            )
                        }
                    }
                }

                // 套索選中字不再逐字畫框：聯合選取框（上方 selectionFrameRectOnPage）已含文字包絡，
                // 逐字框只會多出不能按的假把手。

                // Selection handles for the currently selected text annotation.
                // 打字中不畫選取框與把手：框是照「舊內容」的行數算的，而舊內容已經被藏起來，
                // 留著只會浮出一個與眼前文字對不上的框；輸入時的身分提示由輸入框自己的淡框負責。
                val selTextId  = selectedTextAnnotationId
                val selTextAnn =
                    if (selTextId != null && selTextId != inlineTextEditId && activeTool == Tool.TEXT)
                        textAnnotations.firstOrNull { it.id == selTextId } else null
                if (selTextAnn != null) {
                    // 選取框跟著落定（否則框先跳、內容後跳 → 框脫節＋二次閃）。
                    val tS = pendingTextSettleRef.value
                    val txtSelSettled = tS != null && tS.id == selTextAnn.id && tS.matches(selTextAnn)
                    val fsDelta   = if (txtSelSettled) 0f else textFontSizeDelta
                    val bwDelta   = if (txtSelSettled) 0f else textBoxWidthDelta
                    val mvDelta   = if (txtSelSettled) Offset.Zero else textMoveDelta
                    val textRect  = textAnnotationHitRect(selTextAnn, sx, sy, fsDelta, bwDelta).translate(mvDelta)
                    val textHandle = Offset(textRect.right, textRect.bottom)
                    val handleVisuals = ArrayList<HandleVisual>(2)
                    handleVisuals.add(
                        HandleVisual(
                            center = textHandle,
                            pressed = if (pressedHandleCenter == textHandle) 1f else 0f
                        )
                    )
                    // 框右緣中點的寬度把手（僅文字框）：橫向膠囊＝形狀本身就說「橫向拉」。
                    if (selTextAnn.boxWidth != null) {
                        val wHandle = Offset(textRect.right, textRect.center.y)
                        handleVisuals.add(
                            HandleVisual(
                                center = wHandle,
                                pressed = if (pressedHandleCenter == wHandle) 1f else 0f,
                                isCapsule = true
                            )
                        )
                    }
                    drawLiquidGlassSelectionFrame(
                        rect = textRect,
                        accent = accentColor,
                        handles = handleVisuals,
                        visualRadiusPx = handleVisualRadiusPx,
                        haloRadiusPx = handleHaloRadiusPx,
                        strokeScale = if (pressedHandleCenter != null)
                            com.vic.inkflow.ui.theme.Handles.PressedStrokeScale else 1f
                    )
                }

                // Shape and Rectangular Lasso live-preview (canvas-pixel space — no model→canvas scaling needed here)
                val _v         = activePathVersion   // subscribe to version changes
                val shapeStart = activeShapeStart
                val shapeEnd   = activeShapeEnd
                // For Shape tool
                if (activeTool == Tool.SHAPE && shapeStart != null && shapeEnd != null) {
                    val left   = minOf(shapeStart.x, shapeEnd.x)
                    val top    = minOf(shapeStart.y, shapeEnd.y)
                    val right  = maxOf(shapeStart.x, shapeEnd.x)
                    val bottom = maxOf(shapeStart.y, shapeEnd.y)
                    val previewStyle = DrawStroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    when (selectedShapeSubType) {
                        ShapeSubType.RECT ->
                            drawRect(selectedColor, topLeft = Offset(left, top), size = Size(right - left, bottom - top), style = previewStyle)
                        ShapeSubType.CIRCLE ->
                            drawOval(selectedColor, topLeft = Offset(left, top), size = Size(right - left, bottom - top), style = previewStyle)
                        ShapeSubType.LINE -> drawLine(selectedColor, shapeStart, shapeEnd, strokeWidth = strokeWidth, cap = StrokeCap.Round)
                        ShapeSubType.ARROW -> {
                            drawLine(selectedColor, shapeStart, shapeEnd, strokeWidth = strokeWidth, cap = StrokeCap.Round)
                            drawArrowHeadInScope(shapeStart, shapeEnd, selectedColor, strokeWidth)
                        }
                    }
                }
                
                // For Rectangular Lasso tool
                if (activeTool == Tool.LASSO && selectedLassoSubType == LassoSubType.RECT && shapeStart != null && shapeEnd != null) {
                    val left   = minOf(shapeStart.x, shapeEnd.x)
                    val top    = minOf(shapeStart.y, shapeEnd.y)
                    val right  = maxOf(shapeStart.x, shapeEnd.x)
                    val bottom = maxOf(shapeStart.y, shapeEnd.y)
                    drawRect(
                        color = Color.DarkGray,
                        topLeft = Offset(left, top),
                        size = Size(right - left, bottom - top),
                        style = DrawStroke(width = 2f, pathEffect = dashPreview)
                    )
                }

                // ── 橡皮擦特效:橡皮屑灰石粒(畫在墨水之上、手勢預覽之下)──
                if (dustFx.isNotEmpty()) {
                    val tick = fxTickNanos
                    dustFx.forEach { p ->
                        val t = (tick - p.t0Nanos).toFloat() / 1_000_000f / DUST_DUR_MS
                        if (t >= 0f && t < 1f) {
                            val secs = t * (DUST_DUR_MS / 1000f)
                            val x = p.x + p.vx * secs
                            val y = p.y + p.vy * secs + 900f * t * t / 2f
                            drawCircle(
                                Color(0xFF8A8276),
                                radius = p.radius * (1f - 0.6f * t),
                                center = Offset(x, y),
                                alpha = 0.85f * (1f - t)
                            )
                        }
                    }
                }

                // Active freehand / eraser / lasso path
                // 交接中的活路徑：若向量層已接手同一筆就不再疊一層
                //（高亮筆 alpha×2 會偏暗，提筆瞬間反而更明顯）。
                if (!bridgedDrawn) when {
                    activeTool == Tool.ERASER || eraseVisualActive -> {
                        // 紙張橡皮:暖石灰雙層 veil(外淡內濃,半透明負空間,無實線)
                        // + 頭部游標環(白暈+暗環+中心點,深淺紙面都看得到)。
                        // 擦除半徑與命中判定一致:model → canvas px。
                        val modelW = viewModel.modelWidth
                        val eraserRpx = if (modelW > 0f) eraserRadiusModel * (size.width / modelW) else 24f
                        drawPath(
                            activePath, ERASER_VEIL.copy(alpha = ERASER_VEIL_OUTER_ALPHA),
                            style = DrawStroke(width = eraserRpx * 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                        drawPath(
                            activePath, ERASER_VEIL.copy(alpha = ERASER_VEIL_INNER_ALPHA),
                            style = DrawStroke(width = eraserRpx * ERASER_INNER_WIDTH_RATIO, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                        // 頭部游標環:白暈(0.85)讓深色紙面也浮得出來,暗環給淺色紙面對比
                        currentPathPoints.lastOrNull()?.let { head ->
                            val c = Offset(head.x, head.y)
                            drawCircle(
                                color = Color.White.copy(alpha = 0.85f),
                                radius = eraserRpx, center = c,
                                style = DrawStroke(width = 4.5f)
                            )
                            drawCircle(
                                color = ERASER_RING, radius = eraserRpx, center = c,
                                style = DrawStroke(width = 2f)
                            )
                            drawCircle(color = ERASER_RING, radius = 3f, center = c)
                        }
                    }
                    activeTool == Tool.PEN -> drawPath(activeEnvelopePath, selectedColor) // Uses default Fill style
                    activeTool == Tool.HIGHLIGHTER -> drawIntoCanvas { cvs ->
                        hlPreviewPaint.color = selectedColor.copy(alpha = HIGHLIGHTER_ALPHA)
                        cvs.drawPath(activeEnvelopePath, hlPreviewPaint)
                    }
                    activeTool == Tool.LASSO  -> drawPath(activePath, Color.DarkGray, style = DrawStroke(width = 2f, pathEffect = dashPreview))
                    else -> { }
                }
                // F1：鄰頁共享預覽（點段已是本頁頁內座標，直接畫；寬已 ×docZoom，與提交同管線）。
                if (sharedSegs.size >= 2) {
                    when (sharedPreview?.tool) {
                        Tool.PEN -> drawPath(
                            EnvelopeUtils.generateEnvelopePath(sharedSegs),
                            Color(sharedPreview?.colorArgb ?: 0xFF000000.toInt())
                        )
                        Tool.HIGHLIGHTER -> drawIntoCanvas { cvs ->
                            hlPreviewPaint.color =
                                Color(sharedPreview?.colorArgb ?: 0xFF000000.toInt()).copy(alpha = HIGHLIGHTER_ALPHA)
                            cvs.drawPath(EnvelopeUtils.generateEnvelopePath(sharedSegs), hlPreviewPaint)
                        }
                        Tool.ERASER -> {
                            val epath = Path()
                            sharedSegs.forEachIndexed { i, sp ->
                                if (i == 0) epath.moveTo(sp.x, sp.y) else epath.lineTo(sp.x, sp.y)
                            }
                            val sharedErRpx = if (viewModel.modelWidth > 0f) {
                                eraserRadiusModel * (size.width / viewModel.modelWidth)
                            } else 24f
                            drawPath(
                                epath, ERASER_VEIL.copy(alpha = ERASER_VEIL_OUTER_ALPHA),
                                style = DrawStroke(width = sharedErRpx * 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                            drawPath(
                                epath, ERASER_VEIL.copy(alpha = ERASER_VEIL_INNER_ALPHA),
                                style = DrawStroke(width = sharedErRpx * ERASER_INNER_WIDTH_RATIO, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                        Tool.LASSO -> {
                            val lpath = Path()
                            sharedSegs.forEachIndexed { i, sp ->
                                if (i == 0) lpath.moveTo(sp.x, sp.y) else lpath.lineTo(sp.x, sp.y)
                            }
                            drawPath(lpath, Color.DarkGray, style = DrawStroke(width = 2f, pathEffect = dashPreview))
                        }
                        else -> Unit
                    }
                }
            }
        }

    val inlineOpen = inlineTextNewPos != null || inlineTextEditId != null
    LaunchedEffect(inlineOpen) {
        if (inlineOpen) {
            inlineFocusRequester.requestFocus()
            delay(100)
            keyboardRef.value?.show()
        }
    }

    // 特效活著就每幀 tick + 順手清過期(只寫 draw-phase 訂閱的 state,不觸發整個樹重組)
    LaunchedEffect(fxActive) {
        if (!fxActive) return@LaunchedEffect
        while (dustFx.isNotEmpty()) {
            withFrameNanos { tick ->
                fxTickNanos = tick
                dustFx.removeAll { tick - it.t0Nanos > DUST_DUR_MS * 1_000_000L + 30_000_000L }
            }
        }
    }

    Box(modifier = modifier) {
        Canvas(modifier = drawModifier) { }

        // Inline text editor — spawns at the tap point / annotation, commits on Done or outside tap
        if (inlineOpen && activeTool == Tool.TEXT && canvasPixelSize != Size.Zero) {
            val cs = canvasPixelSize
            val osx = if (cs.width > 0f) cs.width / viewModel.modelWidth else 1f
            val osy = if (cs.height > 0f) cs.height / viewModel.modelHeight else 1f
            val editAnn = inlineTextEditId?.let { id -> textAnnotations.firstOrNull { it.id == id } }
            // 新建時的預覽框寬＝落點到右邊距（與 commitInlineText 的預設框寬一致）
            val newBoxW = inlineTextNewPos?.let { p ->
                (cs.width - p.x - TEXT_BOX_DEFAULT_MARGIN * osx).coerceAtLeast(TEXT_BOX_MIN_WIDTH * osx)
            }
            InlineTextEditor(
                value = inlineTextValue,
                onValueChange = { inlineTextValue = it },
                onDone = { commitInlineText() },
                focusRequester = inlineFocusRequester,
                editing = editAnn,
                newAnchorPx = inlineTextNewPos,
                canvasWidthPx = cs.width,
                scaleX = osx,
                scaleY = osy,
                defaultFontPx = defaultTextFontPx,
                cursorColorArgb = inlineTextColor.toArgb(),
                boxWidthPx = editAnn?.boxWidth?.let { it * osx } ?: newBoxW,
                alignment = editAnn?.alignment ?: TEXT_ALIGN_LEFT,
                // 與畫布同一顆字體度量：行高 = -ascent + descent + leading。
                // 這個元件是共用的，度量由平台給（桌面／iOS 給自己的量法），
                // 否則共用元件只能猜，猜出來的會差幾 px。
                fontMetrics = { px ->
                    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        textSize = px
                    }
                    val fm = p.fontMetrics
                    com.vic.inkflow.ui.TextFontMetrics(
                        lineHeightPx = -fm.ascent + fm.descent + fm.leading,
                        ascentPx = -fm.ascent
                    )
                }
            )
        }

        // 對齊浮動小工具列：選中文字框、且不在打字時出現（左/中/右）。
        // 只對「有框」的文字出現：沒框的（圖章、v31 之前沒回填到的舊資料）渲染端
        // 對它們完全忽略 alignment（InkCanvas 折行渲染 `if (boxW != null)` else 0f），
        // 若不設此閘，工具列會浮出來但點左/中/右毫無反應＝死按鈕。
        // AI 匯入文字從 v31 起一律有框，所以它也有這排工具列。
        val floatSelId = selectedTextAnnotationId
        val floatSelAnn = if (activeTool == Tool.TEXT && !inlineOpen && floatSelId != null)
            textAnnotations.firstOrNull { it.id == floatSelId && it.boxWidth != null } else null
        if (floatSelAnn != null && canvasPixelSize != Size.Zero) {
            val cs = canvasPixelSize
            val fsx = if (cs.width > 0f) cs.width / viewModel.modelWidth else 1f
            val fsy = if (cs.height > 0f) cs.height / viewModel.modelHeight else 1f
            val tb = textAnnotationHitRect(floatSelAnn, fsx, fsy)
            val barH = with(density) { 40.dp.toPx() }
            val barWPx = with(density) { 172.dp.toPx() }
            val gapPx = with(density) { 8.dp.toPx() }
            val barX = tb.left.coerceIn(0f, (cs.width - barWPx).coerceAtLeast(0f))
            val barY = (tb.top - barH - gapPx).coerceAtLeast(0f)
            val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
            Row(
                modifier = Modifier
                    .offset { IntOffset(barX.toInt(), barY.toInt()) }
                    .fauxGlassPanel(isDark, ShapeMd)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(
                    "左" to TEXT_ALIGN_LEFT,
                    "中" to TEXT_ALIGN_CENTER,
                    "右" to TEXT_ALIGN_RIGHT
                ).forEach { (label, value) ->
                    val selected = floatSelAnn.alignment == value
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) GlassSelectionInk else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .clip(ShapeMd)
                            .clickable { viewModel.commitTextAnnotationAlignment(floatSelAnn.id, value) }
                            .then(if (selected) Modifier.glassSelectionPill(ShapeMd) else Modifier)
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

// ── 命中測試與幾何見 InkCanvasGeometry.kt ────────────────────────────────────

// ── 選取框型別與快取見 InkCanvasGeometry.kt ──────────────────────────────────

// ── 液態玻璃選取框見 InkCanvasGeometry.kt ────────────────────────────────────

// ── 選取框手柄與座標換算見 InkCanvasGeometry.kt，形狀/筆跡繪製見 InkCanvasRendering.kt ─

