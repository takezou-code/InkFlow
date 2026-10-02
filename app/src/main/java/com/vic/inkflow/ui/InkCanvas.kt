package com.vic.inkflow.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ImageBitmapConfig
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
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.vic.inkflow.data.ImageAnnotationEntity
import com.vic.inkflow.data.PointEntity
import com.vic.inkflow.data.StrokeEntity
import com.vic.inkflow.data.StrokeWithPoints
import com.vic.inkflow.data.TextAnnotationEntity
import com.vic.inkflow.ui.theme.BrandIndigo
import com.vic.inkflow.util.PalmRejectionFilter
import com.vic.inkflow.util.TouchEventLogger
import com.vic.inkflow.util.EnvelopeUtils
import com.vic.inkflow.util.smoothCenterline
import com.vic.inkflow.util.StrokePoint
import com.vic.inkflow.util.DocLayout
import com.vic.inkflow.util.DocTransform
import androidx.compose.ui.graphics.FilterQuality
import com.vic.inkflow.util.StrokeTransformUtils
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
    fun matches(ann: ImageAnnotationEntity): Boolean =
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
) {
    fun matches(ann: TextAnnotationEntity): Boolean =
        ann.id == id &&
            near(ann.modelX, modelX) && near(ann.modelY, modelY) &&
            near(ann.fontSize, fontSize)
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
 * 浮動比較。DB 走 Float 落盤（REAL），model 座標算完再存會有微小誤差，
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
    strokes: List<StrokeWithPoints> = emptyList(),
    /** 本頁文字註解（同上）。 */
    texts: List<TextAnnotationEntity> = emptyList(),
    /** 本頁圖片註解（同上）。 */
    images: List<ImageAnnotationEntity> = emptyList(),
    /** 頁間隙 px（Workspace 的 Arrangement.spacedBy，需與列表一致）。 */
    pageGapPx: Float = 0f,
    /** 邊緣自動捲：手指拖出紙上下界時回傳期望捲動量 px（正=往後頁）；回傳實際捲動量。 */
    onEdgeAutoScroll: (Float) -> Float = { _ -> 0f },
    /**
     * 插圖請求令牌：工具列按圖片鈕時 +1，畫布看到變化就開系統圖片庫。
     *
     * 舊設計是「圖片工具下點紙面空白就開圖庫」，而「空白」只是「四個命中測試都沒抓到」
     * 的 fall-through，且在 DOWN 當下就執行、沒有 tap/drag 區分 → 想滑頁也會彈圖庫，
     * 而且 :846-851 的無條件 consume 讓頁面也不會滑。改成令牌觸發後：
     * 紙面只負責「選取 / 移動 / 縮放 / 旋轉」，插圖是明確的工具列動作。
     *
     * 用 Int 令牌（遞增）而非 Boolean：同一頁可能重複請求，布爾翻回去就不會再觸發。
     */
    imagePickRequest: Int = 0
) {
    // 全活頁：筆/字/圖吃傳入的本頁資料（Workspace 熱流），不再訂閱作用頁流；
    // 工具/顏色/設定等全域態照舊。
    val committedStrokes = strokes
    val textAnnotations = texts
    val imageAnnotations = images
    val selectedStrokePreview by viewModel.selectedStrokePreview.collectAsState()
    val selectedStrokePreviewBounds by viewModel.selectedStrokePreviewBounds.collectAsState()
    val commitPreview by viewModel.commitPreview.collectAsState()
    val activeTool by viewModel.selectedTool.collectAsState()
    val selectedColor by viewModel.selectedColor.collectAsState()
    val strokeWidth by viewModel.strokeWidth.collectAsState()
    val selectedShapeSubType by viewModel.selectedShapeSubType.collectAsState()
    val selectedLassoSubType by viewModel.selectedLassoSubType.collectAsState()
    val selectionFramePolygon by viewModel.selectionFramePolygon.collectAsState()
    val lassoMoveOffset by viewModel.lassoMoveOffset.collectAsState()
    val selectedStrokeScale by viewModel.selectedStrokeScale.collectAsState()
    val selectedStrokeResizeAnchor by viewModel.selectedStrokeResizeAnchor.collectAsState()
    val inputMode by viewModel.inputMode.collectAsState()
    val quickSwipeEraserEnabled by viewModel.quickSwipeEraserEnabled.collectAsState()
    val strokeSpeedSensitivity by viewModel.strokeSpeedSensitivity.collectAsState()
    val widthResponsiveness by viewModel.widthResponsiveness.collectAsState()
    val touchCalEnabled by viewModel.touchCalEnabled.collectAsState()
    val touchCalDxDp by viewModel.touchCalDxDp.collectAsState()
    val touchCalDyDp by viewModel.touchCalDyDp.collectAsState()
    val selectedImageAnnotationIds by viewModel.selectedImageAnnotationIds.collectAsState()
    // 套索選中的字（與墨圖同等待遇；TEXT 工具私選 selectedTextAnnotationId 是另一套，不管）。
    val vmSelectedTextIds by viewModel.selectedTextAnnotationIds.collectAsState()
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
    // 拖曳 overlay 接手時，紙內選取預覽讓位（同像素只畫一次，螢光筆疊色會變深）
    val dragPreviewActive by viewModel.dragPreviewActive.collectAsState()
    // 套索虛線動畫按需組成：無選取時不跑 choreographer，省常駐喚醒；靜模式凍結
    val needLassoAnim = activeTool == Tool.LASSO && selectedStrokePreview.isNotEmpty() && !LocalQuietMode.current
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
    val committedIds = remember(committedStrokes) { committedStrokes.map { it.stroke.id }.toSet() }
    LaunchedEffect(bridgingIds, committedStrokes) {
        val ids = bridgingIds ?: return@LaunchedEffect
        // 一個都還沒到：等 DB 落定（或 2s 兜底防寫失敗殘影），再清不遲
        if (ids.none { it in committedIds }) delay(2000)
        activePath.reset()
        activeEnvelopePath.reset()
        currentPathPoints.clear()
        activePathVersion++
        viewModel.publishInFlight(null)
        bridgingIds = null
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
                pendingTextSettle = null
            }
        }
    }

// ── 選取把手尺寸（dp → 畫布 px，直接 1:1）──────────────────────────────
// 命中半徑 24dp → 螢幕上 48dp 觸控框（Material 最低標準）。舊碼寫死 24 物理px，
// 在 density 2.75 的平板上只剩 17.5dp，必須瞄準才抓得到。
// 不做 docZoom 補償：紙放大是 layout 變大、不是像素縮放（畫布無 graphicsLayer），
// 1 畫布 px 恆＝1 螢幕 px，所以任何縮放下把手都該是同一個螢幕大小。
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
    val selectedStrokePreviewBoundsRef = rememberUpdatedState(selectedStrokePreviewBounds)
    val selectionFramePolygonRef = rememberUpdatedState(selectionFramePolygon)
    val lassoMoveOffsetRef = rememberUpdatedState(lassoMoveOffset)
    val selectedStrokeScaleRef = rememberUpdatedState(selectedStrokeScale)
    val selectedStrokeResizeAnchorRef = rememberUpdatedState(selectedStrokeResizeAnchor)
    val isSelectionTransforming = lassoMoveOffset != Offset.Zero || abs(selectedStrokeScale - 1f) > 0.001f
    // 全活頁：每紙只畫歸屬本紙的選取（跨紙選取各紙畫各的；之前要求整包同頁，跨頁直接整片不畫＝框不出來）。
    val ownSelectedStrokes = remember(selectedStrokePreview, pageIndex) {
        selectedStrokePreview.filter { it.stroke.pageIndex == pageIndex }
    }
    // 本紙子集的 bounds（框＋錨點用；混合頁全域 bounds 在此無意義）。
    // 墨圖字一起算：純圖/純字選取也要出框（之前只有墨，圖字選了沒框）。
    val ownSelectionBounds = remember(
        ownSelectedStrokes, selectedImageAnnotationIds, vmSelectedTextIds,
        imageAnnotations, textAnnotations
    ) {
        val rects = mutableListOf<androidx.compose.ui.geometry.Rect>()
        ownSelectedStrokes.forEach { swp ->
            val s = swp.stroke
            rects += androidx.compose.ui.geometry.Rect(
                s.boundsLeft, s.boundsTop, s.boundsRight, s.boundsBottom
            )
        }
        imageAnnotations.forEach { ann ->
            if (ann.id in selectedImageAnnotationIds) {
                rects += androidx.compose.ui.geometry.Rect(
                    ann.modelX, ann.modelY,
                    ann.modelX + ann.modelWidth, ann.modelY + ann.modelHeight
                )
            }
        }
        textAnnotations.forEach { ann ->
            if (ann.id in vmSelectedTextIds) {
                val eb = textEstimatedBounds(ann)
                rects += androidx.compose.ui.geometry.Rect(eb.left, eb.top, eb.right, eb.bottom)
            }
        }
        if (rects.isEmpty()) null
        else androidx.compose.ui.geometry.Rect(
            rects.minOf { it.left }, rects.minOf { it.top },
            rects.maxOf { it.right }, rects.maxOf { it.bottom }
        )
    }
    val selectionTransformAnchor = selectedStrokeResizeAnchor
        ?: ownSelectionBounds?.center
        ?: selectedStrokePreviewBounds?.center
        ?: Offset.Zero
    val selectedPathData = remember(ownSelectedStrokes) {
        ownSelectedStrokes.map { swp ->
            SelectedStrokeRenderData(
                strokeWithPoints = swp,
                path = if (swp.stroke.shapeType == null) swp.points.toComposePath() else null
            )
        }
    }

    // 筆跡幾何快取：普通 Map，只在 drawWithCache 建構區讀寫。
    // 不用 State 容器 → 提交只觸發一次重建（之前 StateMap 寫入造成第二次失效）。
    val committedPathCache = remember { mutableMapOf<String, CachedStrokePath>() }

    // Image bitmap cache: uri-string → decoded Bitmap (null = load failed / placeholder)
    val loadedImages = remember { mutableStateMapOf<String, android.graphics.Bitmap?>() }
    // 顯示用 ImageBitmap：載入時轉一次，繪製每幀不再 asImageBitmap() 包裝
    val loadedImageBitmaps = remember { mutableStateMapOf<String, ImageBitmap?>() }
    // 文字共用 Paint + 分行快取：繪製時只改字號顏色，不再 new
    val textPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }
    val textLines = remember(textAnnotations) {
        textAnnotations.associate { it.id to it.text.split("\n") }
    }
    // 螢光筆作用中預覽共用 Paint：每幀只換色，不再 new
    val hlPreviewPaint = remember {
        Paint().apply {
            style = PaintingStyle.Fill
            blendMode = BlendMode.Multiply
        }
    }
    // 靜態虛線只建一次（形狀預覽用；選取框已統一走液態玻璃共用繪製）
    val dashPreview = remember { PathEffect.dashPathEffect(floatArrayOf(10f, 10f)) }

    // Image picker launcher — opened by the toolbar (imagePickRequest token), not by tapping the page.
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 50)
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            data class ImportedImage(val localUriString: String, val imagePixelWidth: Int, val imagePixelHeight: Int)

            val imported = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    val localUri = com.vic.inkflow.util.PdfManager.copyImageToAppDir(context, uri)
                        ?: return@mapNotNull null
                    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(localUri)?.use { stream ->
                        android.graphics.BitmapFactory.decodeStream(stream, null, opts)
                    }
                    ImportedImage(
                        localUriString = localUri.toString(),
                        imagePixelWidth = opts.outWidth.coerceAtLeast(0),
                        imagePixelHeight = opts.outHeight.coerceAtLeast(0)
                    )
                }
            }

            if (imported.isEmpty()) return@launch

            suspend fun waitForInsertedPage(previousPageCount: Int): Boolean {
                return withTimeoutOrNull(10_000) {
                    while (pdfViewModel.pageCount.value < previousPageCount + 1) {
                        delay(50)
                    }
                    true
                } == true
            }

            var lastInsertedImageId: String? = null
            // 插圖來源＝工具列，沒有「點哪插哪」的錨點 → 落位由 EditorViewModel 置中
            // （見 centeredImageOrigin）。多選：第 1 張放本頁，後續各開新頁（既有行為保留）。
            if (imported.size == 1) {
                val item = imported.first()
                lastInsertedImageId = viewModel.placeImageAnnotationOnPage(
                    uri = item.localUriString,
                    targetPageIndex = pageIndex,
                    imagePixelWidth = item.imagePixelWidth,
                    imagePixelHeight = item.imagePixelHeight
                )
            } else {
                val currentPageIndex = pageIndex
                var insertAfterIndex = currentPageIndex
                imported.forEachIndexed { index, item ->
                    if (index == 0) {
                        lastInsertedImageId = viewModel.placeImageAnnotationOnPage(
                            uri = item.localUriString,
                            targetPageIndex = currentPageIndex,
                            imagePixelWidth = item.imagePixelWidth,
                            imagePixelHeight = item.imagePixelHeight
                        )
                    } else {
                        // P0：多頁插入會移位頁號，先清復原棧（單張不動頁，不用清）。
                        viewModel.clearUndoStacks()
                        val previousPageCount = pdfViewModel.pageCount.value
                        pdfViewModel.insertBlankPage(
                            context = context,
                            documentUri = documentUri,
                            afterIndex = insertAfterIndex,
                            pageWidthPt = viewModel.modelWidth,
                            pageHeightPt = viewModel.modelHeight
                        )
                        val inserted = waitForInsertedPage(previousPageCount)
                        if (!inserted) return@forEachIndexed

                        val targetPageIndex = insertAfterIndex + 1
                        lastInsertedImageId = viewModel.placeImageAnnotationOnPage(
                            uri = item.localUriString,
                            targetPageIndex = targetPageIndex,
                            imagePixelWidth = item.imagePixelWidth,
                            imagePixelHeight = item.imagePixelHeight
                        )
                        insertAfterIndex = targetPageIndex
                    }
                }
            }

            lastInsertedImageId?.let { insertedId ->
                selectedImageAnnotationId = insertedId
                imageMovePreview = Offset.Zero
                imageResizeScale = 1f
                imageResizeAnchor = null
                imageRotatePreview = 0f
            }
        }
        // Stay in IMAGE tool so the user can immediately adjust the placed image
    }

    // 工具列按圖片鈕 → 開系統圖片庫（只在作用頁開，避免每頁 InkCanvas 都跳一次）。
    LaunchedEffect(imagePickRequest) {
        if (imagePickRequest > 0 && pageIndex == viewModel.selectionPage()) {
            imagePickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
    }

    // 切工具時收尾選取狀態。插圖不再由畫布接觸觸發（舊註解寫「Open gallery whenever
    // IMAGE tool is activated」但這裡從來沒有開圖庫，是從初始 commit 就存在的過期誤導）。
    LaunchedEffect(activeTool) {
        if (activeTool != Tool.TEXT) {
            if (inlineTextNewPos != null || inlineTextEditId != null) commitInlineText()
            selectedTextAnnotationId = null
            textMoveDelta = Offset.Zero
            textFontSizeDelta = 0f
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

    // Async bitmap loader — runs whenever annotation list changes
    LaunchedEffect(imageAnnotations) {
        imageAnnotations.forEach { ann ->
            if (ann.uri !in loadedImages) {
                loadedImages[ann.uri] = null
                scope.launch(Dispatchers.IO) {
                    val bmp = try {
                        decodeBoundedBitmap(context, Uri.parse(ann.uri), maxSidePx = 2048)
                    } catch (_: Exception) { null }
                    val frame = bmp?.asImageBitmap()
                    withContext(Dispatchers.Main) {
                        loadedImages[ann.uri] = bmp
                        loadedImageBitmaps[ann.uri] = frame
                    }
                }
            }
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
    val drawModifier = Modifier.fillMaxSize()
        .onSizeChanged { size ->
            canvasPixelSize = Size(size.width.toFloat(), size.height.toFloat())
            viewModel.setCanvasSize(size.width.toFloat(), size.height.toFloat())
        }
        // Capture raw contact metrics before Compose converts MotionEvent to PointerInputChange.
        // Returning false forwards the event unchanged to the pointerInput block below.
        .pointerInteropFilter { motionEvent ->
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
                        val currentTextRect = textAnnotationHitRect(selAnn, sx, sy).translate(textMoveDelta)
                        val handleRect = textResizeHandleRect(currentTextRect, handleHitRadiusPx)

                        // Resize handle hit
                        if (handleRect.contains(startOffset)) {
                            down.consume()
                            pressedHandleCenter = handleRect.center
                            var accModelDelta = 0f
                            var drag = awaitDragOrCancellation(down.id)
                            while (drag != null && drag.pressed) {
                                if (pinchActive) {
                                    viewModel.commitTextAnnotationResize(selAnn.id, selAnn.modelX, selAnn.modelY, selAnn.fontSize + accModelDelta)
                                    pendingTextSettle = PendingTextSettle(
                                        id = selAnn.id,
                                        modelX = selAnn.modelX,
                                        modelY = selAnn.modelY,
                                        fontSize = selAnn.fontSize + accModelDelta
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
                                fontSize = selAnn.fontSize + accModelDelta
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
                                // 跨頁：總位移把文字推出本頁 → 整顆換頁；否則舊提交
                                val cs = canvasPixelSizeState.value
                                val scaleX = DocTransform.invScaleX(cs.width, viewModel.modelWidth)
                                val scaleY = DocTransform.invScaleY(cs.height, viewModel.modelHeight)
                                val newModelX = selAnn.modelX + totalDelta.x * scaleX
                                val newModelY = selAnn.modelY + totalDelta.y * scaleY
                                val wrapped = wrapCrossPageY(
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
                                    fontSize = selAnn.fontSize
                                )
                                activePathVersion++
                                return@awaitEachGesture
                            }
                            textMoveDelta = Offset.Zero
                            activePathVersion++
                            return@awaitEachGesture
                        }
                    }

                    // Tap on any annotation to select it
                    val hitAnn = annotations.firstOrNull { ann ->
                        textAnnotationHitRect(ann, sx, sy).contains(startOffset)
                    }
                    if (hitAnn != null) {
                        selectedTextAnnotationId = hitAnn.id
                        textMoveDelta = Offset.Zero
                        textFontSizeDelta = 0f
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
                    // 插圖改由工具列觸發（imagePickRequest）。舊版在 DOWN 當下就 launch
                    // 系統 Activity，而且「空白」只是四個命中測試都沒抓到的 fall-through、
                    // 沒有 tap/drag 區分 → 想滑頁也會彈圖庫。
                    // 這裡刻意不 consume：圖片工具不畫東西，空白處該讓事件冒泡去捲頁。
                    return@awaitEachGesture
                }

                if (activeTool == Tool.LASSO) {
                    val selectionBounds = selectedStrokePreviewBoundsRef.value
                    val hasLassoSelection = selectedStrokePreview.isNotEmpty() ||
                        selectedImageAnnotationIds.isNotEmpty() ||
                        selectionFramePolygonRef.value.isNotEmpty()
                    val cs = canvasPixelSizeState.value
                    val sx = DocTransform.scaleX(cs.width, viewModel.modelWidth)
                    val sy = DocTransform.scaleY(cs.height, viewModel.modelHeight)
                    val anchorModel = selectedStrokeResizeAnchorRef.value
                        ?: selectionBounds?.center
                        ?: Offset.Zero
                    val selectionRect = modelTransformedPolygonBoundsToCanvasRect(
                        polygon = selectionFramePolygonRef.value,
                        translation = lassoMoveOffsetRef.value,
                        scale = selectedStrokeScaleRef.value,
                        anchor = anchorModel,
                        sx = sx,
                        sy = sy
                    )
                    if (selectionRect != null && !selectionRect.isEmpty) {
                        val hitIdx = handleHit(startOffset, strokeSelectionHandleCenters(selectionRect))
                        val hitHandle = if (hitIdx >= 0)
                            StrokeSelectionHandle.entries.getOrNull(hitIdx) else null
                        if (hitHandle != null) {
                            down.consume()
                            pressedHandleCenter = strokeSelectionHandleCenter(selectionRect, hitHandle)
                            val handle = hitHandle
                            val anchorCanvas = strokeSelectionResizeAnchor(selectionRect, handle)
                            val initialHandle = strokeSelectionHandleCenter(selectionRect, handle)
                            val baseDistance = hypot(
                                (initialHandle.x - anchorCanvas.x).toDouble(),
                                (initialHandle.y - anchorCanvas.y).toDouble()
                            ).toFloat().coerceAtLeast(1f)
                            viewModel.beginSelectedStrokeResize(anchorCanvas)
                            var drag = awaitDragOrCancellation(down.id)
                            while (drag != null && drag.pressed) {
                                // 縮放介入：提交當前進度後退出（model-space 變換與縮放無關，可安全提交）
                                if (pinchActive) {
                                    viewModel.commitResizedStrokes()
                                    pressedHandleCenter = null
                                    return@awaitEachGesture
                                }
                                val currentDistance = hypot(
                                    (drag.position.x - anchorCanvas.x).toDouble(),
                                    (drag.position.y - anchorCanvas.y).toDouble()
                                ).toFloat()
                                viewModel.previewSelectedStrokeScale(currentDistance / baseDistance)
                                drag.consume()
                                drag = awaitDragOrCancellation(drag.id)
                            }
                            viewModel.commitResizedStrokes()
                            pressedHandleCenter = null
                            return@awaitEachGesture
                        }

                        if (selectionRect.contains(startOffset)) {
                            down.consume()
                            viewModel.setPageLock(true)
                            viewModel.setDragPreviewActive(true)
                            // 跟手絕對式：指位相對起點，每幀重算不積差；
                            // 自動捲量不加（見文字分支同註解），增量餵 VM 保持 telescoping 精確。
                            var prevAbs = Offset.Zero
                            var drag = awaitDragOrCancellation(down.id)
                            while (drag != null && drag.pressed) {
                                // 連貫畫布：拖出紙界自動捲（只捲視野，不進位移）
                                val csH = canvasPixelSizeState.value.height
                                val autoDy = edgeAutoScrollDy(drag.position.y, csH)
                                if (autoDy != 0f) {
                                    onEdgeAutoScrollRef.value(autoDy)
                                }
                                val abs = drag.position - startOffset
                                val step = abs - prevAbs
                                prevAbs = abs
                                if (pinchActive) {
                                    viewModel.commitMovedStrokes()
                                    viewModel.setPageLock(false)
                                    viewModel.setDragPreviewActive(false)
                                    return@awaitEachGesture
                                }
                                if (step != Offset.Zero) viewModel.moveSelectedStrokes(step)
                                drag.consume()
                                drag = awaitDragOrCancellation(drag.id)
                            }
                            val maxPage = (pageCountRef.value - 1).coerceAtLeast(0)
                            viewModel.commitMovedStrokes(maxPage)
                            viewModel.setPageLock(false)
                            viewModel.setDragPreviewActive(false)
                            // 跨頁落地不清選（舊 workaround 已退役）：高亮和泡泡跟到新頁，
                            // 選不到東西時點空白照常清（見下）。單頁本來就不清，現在一致。
                            return@awaitEachGesture
                        }

                        down.consume()
                        viewModel.clearSelection()
                        return@awaitEachGesture
                    }

                    if (hasLassoSelection) {
                        down.consume()
                        viewModel.clearSelection()
                        return@awaitEachGesture
                    }
                }

                // Shape and Rectangular Lasso tool: drag to define bounding box
                if (activeTool == Tool.SHAPE || (activeTool == Tool.LASSO && selectedLassoSubType == LassoSubType.RECT)) {
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
                                viewModel.deleteStrokesIntersecting(points, page = pageIndexRef.value)
                            } else {
                                viewModel.saveShape(
                                    startOffset, end, selectedColor, strokeWidth,
                                    targetPage = pageIndexRef.value
                                )
                            }
                        } else if (activeTool == Tool.LASSO) {
                            // 矩形套索：按頁切子矩形（框跨頁不斷在起始頁；2 點碎段問題根治）。
                            val canvasW = canvasPixelSizeState.value.width
                            val canvasH = canvasPixelSizeState.value.height
                            val quads = DocLayout.rectsByPage(
                                left = minOf(startOffset.x, end.x),
                                top = minOf(startOffset.y, end.y),
                                right = maxOf(startOffset.x, end.x),
                                bottom = maxOf(startOffset.y, end.y),
                                canvasW = canvasW,
                                canvasH = canvasH,
                                gapPx = pageGapPxRef.value,
                                srcPage = pageIndexRef.value,
                                pageCount = pageCountRef.value
                            ).mapValues { (_, quad) -> listOf(quad) }
                            viewModel.selectStrokesInLassoAcross(
                                polygonsByPage = quads,
                                srcPage = pageIndexRef.value,
                                canvasW = canvasW,
                                canvasH = canvasH
                            )
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
                val quickSwipeTrace = mutableListOf(startOffset)
                val supportsQuickSwipeEraser = quickSwipeEraserEnabled &&
                    (activeTool == Tool.PEN || activeTool == Tool.HIGHLIGHTER)
                var quickSwipeTriggered = false
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
                // F3：上一筆交接中途又落筆——沿用舊行為清活路徑（提交段已在資料流，不閃），交接作廢。
                // 注意 reset 後必須補 moveTo，否則後續 quadraticTo 以原點起筆＝左上角怪線。
                bridgingIds = null
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
                            viewModel.setPageLock(false)
                            return@awaitEachGesture
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
                                val velocity = dist / dt.toFloat()
                                val sensitivity = strokeSpeedSensitivity.coerceAtLeast(0.1f)
                            
                            val w = if (activeTool == Tool.HIGHLIGHTER) {
                                baseWidth // For highlighter, do NOT apply variable thickness
                            } else {
                                    // 範圍 0.25x–1.7x（最粗最細對比更像壓感筆），跟隨由設定頁
                                    // 「粗細跟手速度」控制（0=鈍 0.95 → 1=靈 0.50，預設 0.80）。
                                    val maxW = baseWidth * 1.7f
                                    val minW = baseWidth * 0.25f
                                    val smoothOld =
                                        0.95f - 0.45f * widthResponsiveness.coerceIn(0f, 1f)
                                    // Sensitivity > 1.0 makes thinning happen sooner; < 1.0 makes it slower.
                                    val velocityThreshold = 0.7f / sensitivity
                                    val vMapped = (velocity / velocityThreshold).coerceIn(0f, 1f)
                                val velocityTarget = minW + (maxW - minW) * (1f - vMapped)
                                // 觸控筆直讀壓力：只用鎖定後的模式。整手勢壓力幾乎不變
                                // （<0.05，即副廠無壓感筆）→ 整筆退回速度路徑，不中途跳變。
                                // 鎖定前一律速度，避免開頭在兩種模式間橫跳。
                                val usePressure = isStylusPen &&
                                    pressureModeLatched && usePressureMode
                                val targetW = if (usePressure) {
                                    minW + (maxW - minW) * pressure.coerceIn(0f, 1f)
                                } else {
                                    velocityTarget
                                }
                                // 加重前一點的權重，讓粗細過渡更平滑，消除竹節突變
                                prevPt.width * smoothOld + targetW * (1f - smoothOld)
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
                                activePath.reset()
                                activeEnvelopePath.reset()
                                // reset 後補 moveTo，否則後續筆段以原點起筆（同左上角怪線）。
                                activePath.moveTo(drag.position.x, drag.position.y)
                            }
                        }
                        
                        if (!quickSwipeTriggered && (activeTool == Tool.PEN || activeTool == Tool.HIGHLIGHTER)) {
                            // 預覽包絡先過中心線平滑（入庫點列不動，匯出零影響）。
                            // F2：寬先 ×docZoom——提交入庫即此語義，預覽與成品同管線（zoom=1 時與舊行為一致）。
                            val zoomPrev = viewModel.docZoom.value.coerceAtLeast(0.1f)
                            val previewPts = smoothCenterline(
                                currentPathPoints.map { it.copy(width = it.width * zoomPrev) }
                            )
                            val newPath = EnvelopeUtils.generateEnvelopePath(previewPts)
                            activeEnvelopePath.reset()
                            activeEnvelopePath.addPath(newPath)
                        }

                        // F1：跨頁共享預覽（src 紙除外，走直接路徑）。點數不變不重發。
                        if ((activeTool == Tool.PEN || activeTool == Tool.HIGHLIGHTER ||
                                activeTool == Tool.LASSO || activeTool == Tool.ERASER) &&
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
                                    tool = activeTool,
                                    segments = segs.toMap(),
                                    colorArgb = selectedColor.toArgb()
                                )
                            )
                        }

                        activePathVersion++
                        drag.consume()

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
                                    // 跨頁擦除：擦過紙界時鄰頁一起擦（各頁分段，undo 按頁記）。
                                    // R1：命中進手勢累積器，結尾合併一格。
                                    viewModel.deleteStrokesIntersectingAcrossPages(
                                        eraserPointsCanvas = points,
                                        srcPage = pageIndexRef.value,
                                        canvasH = canvasPixelSizeState.value.height,
                                        gapPx = pageGapPxRef.value,
                                        pageCount = pageCountRef.value,
                                        markEraseHit = activeTool == Tool.ERASER,
                                        accumulateToGesture = activeTool == Tool.ERASER
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
                    viewModel.publishInFlight(null)
                    if (activeTool == Tool.ERASER) viewModel.clearEraseHitPending()
                    // R1：累積作廢，不留復原格
                    if (activeTool == Tool.ERASER) viewModel.discardEraseGesture()
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
                    viewModel.publishInFlight(null)
                    if (activeTool == Tool.ERASER) viewModel.clearEraseHitPending()
                    // R1：累積作廢，不留復原格
                    if (activeTool == Tool.ERASER) viewModel.discardEraseGesture()
                    viewModel.setPageLock(false)
                    return@awaitEachGesture
                }

                when (activeTool) {
                    Tool.PEN, Tool.HIGHLIGHTER -> {
                        if (quickSwipeTriggered) {
                            val points = if (currentPathPoints.size == 1) {
                                val pt = currentPathPoints.first()
                                listOf(Offset(pt.x, pt.y), Offset(pt.x + 0.01f, pt.y))
                            } else {
                                currentPathPoints.map { Offset(it.x, it.y) }
                            }
                            TouchEventLogger.logOutcome(
                                sessionId       = sessionId,
                                outcome         = "QUICK_SWIPE_ERASE",
                                pointCount      = points.size,
                                maxPressure     = maxPressureDuring,
                                maxSizeWidthPx  = maxSizeWidthDuring,
                                maxSizeHeightPx = maxSizeHeightDuring
                            )
                            viewModel.deleteStrokesIntersectingAcrossPages(
                                eraserPointsCanvas = points,
                                srcPage = pageIndexRef.value,
                                canvasH = canvasPixelSizeState.value.height,
                                gapPx = pageGapPxRef.value,
                                pageCount = pageCountRef.value,
                                markEraseHit = false
                            )
                        } else {
                            // A single-point tap produces no drag points; duplicate it so
                            // saveStroke receives ≥2 points and StrokeCap.Round renders a dot.
                            val rawPts = if (currentPathPoints.size == 1)
                                listOf(currentPathPoints[0], currentPathPoints[0])
                            else
                                currentPathPoints.toList()
                            // 提筆零形變：提交與最後一幀預覽完全相同的平滑點列
                            //（預覽包絡吃 smoothCenterline，入庫若用原始點列，鬆筆瞬間形狀跳變）。
                            val pts = smoothCenterline(rawPts)
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
                            segs.forEach { (pg, segPts) ->
                                if (segPts.size >= 2) {
                                    viewModel.saveStroke(
                                        segPts, selectedColor, activeTool, strokeWidth,
                                        targetPage = pg
                                    )?.let { id -> if (pg == pageIndexRef.value) bridged += id }
                                    lastPage = pg
                                }
                            }
                            bridgingIds = bridged.takeIf { it.isNotEmpty() }
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
                        val byPage = LinkedHashMap<Int, MutableList<List<Offset>>>()
                        DocLayout.splitByPage(
                            items = lassoPts,
                            yOf = { it.y },
                            canvasH = canvasH,
                            gapPx = pageGapPxRef.value,
                            srcPage = pageIndexRef.value,
                            pageCount = pageCountRef.value,
                            local = { p, ly -> Offset(p.x, ly) }
                        ).forEach { (pg, seg) ->
                            val clipped = clipPolygonToRect(seg, 0f, 0f, canvasW, canvasH)
                            if (clipped.size >= 3) byPage.getOrPut(pg) { mutableListOf() }.add(clipped)
                        }
                        viewModel.selectStrokesInLassoAcross(
                            polygonsByPage = byPage,
                            srcPage = pageIndexRef.value,
                            canvasW = canvasW,
                            canvasH = canvasH
                        )
                    }
                    Tool.ERASER -> {
                        // Fire one final erasure at end of the gesture so that:
                        //  (a) pure taps (no drag) can erase a stroke under the finger, and
                        //  (b) the tail end of a drag that ran asynchronously is not missed.
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
                            switchToPenAfterEraseHit = true,
                            accumulateToGesture = true
                        )
                    }
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
                // R1：橡皮擦手勢關門（合併推一格；畫筆是空操作）
                if (activeTool == Tool.ERASER) viewModel.endEraseGesture()
                // 兜底：自由筆各提交路徑在此統一清鎖（PEN/HL 已在分支內清過，重複無害）
                viewModel.setPageLock(false)
            }
        }
        .drawWithCache {
            val sx = DocTransform.scaleX(size.width, viewModel.modelWidth)
            val sy = DocTransform.scaleY(size.height, viewModel.modelHeight)

            val strokes = committedStrokes
            val preview = commitPreview
            val previewIds = preview?.map { it.stroke.id }?.toHashSet() ?: emptySet()

            val bmpWidth = size.width.toInt().coerceAtLeast(1)
            val bmpHeight = size.height.toInt().coerceAtLeast(1)
            // 筆跡快取封頂：縮放時畫布可達上萬 px，原尺寸建圖會超過
            // RecordingCanvas 上限直接閃退（321MB 事件），且每幀重建巨圖就是卡頓主因；
            // 改固定上限，繪製時再放大回全尺寸（GPU 做，免費）
            // 筆跡快取上限 3072（transient 約 27MB 內，largeHeap 平板可承受）。
            val cacheCap = 3072f
            val cacheScale = (cacheCap / maxOf(bmpWidth, bmpHeight)).coerceAtMost(1f)
            val cacheW = (bmpWidth * cacheScale).toInt().coerceAtLeast(1)
            val cacheH = (bmpHeight * cacheScale).toInt().coerceAtLeast(1)
            // P1 手勢凍結：pinchActive 時沿用上次的快取圖（不配置不重畫），
            // 讀 pinchActive 會訂閱——放開切換時失效重建，剛好是銳利化的時機。
            // 注意：手勢中若有新墨提交（理論上不會，門衛會棄筆），放開後自然重建。
            val frozen = pinchActive && pinchReuse.bmp != null
            val cachedImage = if (frozen) {
                pinchReuse.bmp!!
            } else {
                val reuse = pinchReuse.bmp
                if (reuse != null && reuse.width == cacheW && reuse.height == cacheH) {
                    // 同尺寸重用：清掉舊墨再重畫，提筆不再配置數十 MB（卡頓主因）
                    androidx.compose.ui.graphics.Canvas(reuse).drawRect(
                        0f, 0f, cacheW.toFloat(), cacheH.toFloat(),
                        Paint().apply { blendMode = BlendMode.Clear }
                    )
                    reuse
                } else {
                    ImageBitmap(cacheW, cacheH, ImageBitmapConfig.Argb8888).also {
                        pinchReuse.bmp = it
                    }
                }
            }
            val cacheCanvas = androidx.compose.ui.graphics.Canvas(cachedImage)

            // 凍結中連畫都跳過：整塊重建是捏合卡頓的最大頭
            if (!frozen) {
                cacheCanvas.save()
                cacheCanvas.scale(sx * cacheScale, sy * cacheScale)
                // 淘汰已刪筆跡（普通 Map 操作，不寫 State、不二次失效）
                val keepIds = HashSet<String>(strokes.size + 16)
                strokes.forEach { if (it.stroke.shapeType == null) keepIds.add(it.stroke.id) }
                committedPathCache.keys.removeAll { it !in keepIds }
                strokes.forEach { swp ->
                    if (swp.stroke.id in previewIds) return@forEach
                    if (swp.stroke.shapeType != null) {
                        drawShapeOnCanvas(cacheCanvas, swp.stroke, swp.points)
                    } else {
                        // 增量建幾何：命中直接重用，只建新增/變更的筆
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
                        drawPathOnCanvas(cacheCanvas, path,
                            Color(swp.stroke.color), swp.stroke.strokeWidth, swp.stroke.isHighlighter)
                    }
                }
                cacheCanvas.restore()
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
                    var lineY = step
                    while (lineY < viewModel.modelHeight) {
                        var lineX = step
                        while (lineX < viewModel.modelWidth) {
                            drawCircle(
                                color  = lineColor,
                                radius = 1.5f,
                                center = Offset(lineX * sx, lineY * sy)
                            )
                            lineX += step
                        }
                        lineY += step
                    }
                }
            }

            // Image annotations — drawn BELOW strokes (above PDF layer which is a separate Composable)
            currentImageAnns.forEach { ann ->
                val bmp = loadedImageBitmaps[ann.uri]
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
                            translation = lassoMoveOffset,
                            scale = selectedStrokeScale,
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
                    }
                }
            }

            // Committed strokes from DB.
            // Play the cached strokes layer! 快取是封頂小圖，放大回全畫布
            drawImage(
                image = cachedImage,
                dstSize = IntSize(
                    size.width.toInt().coerceAtLeast(1),
                    size.height.toInt().coerceAtLeast(1)
                ),
                // 放大用 High 品質，歷史墨不糊。
                filterQuality = FilterQuality.High
            )

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
            if (selectedPathData.isNotEmpty() && !dragPreviewActive) {
                drawIntoCanvas { cvs ->
                    cvs.save()
                    cvs.scale(sx, sy)
                    if (isSelectionTransforming) {
                        applySelectionTransform(
                            canvas = cvs,
                            translation = lassoMoveOffset,
                            scale = selectedStrokeScale,
                            anchor = selectionTransformAnchor
                        )
                    }
                    selectedPathData.forEach { renderData ->
                        val swp = renderData.strokeWithPoints
                        val stroke = swp.stroke
                        if (stroke.shapeType != null) {
                            drawShapeOnCanvas(cvs, stroke, swp.points, tintColor = BrandIndigo)
                        } else {
                            val path = renderData.path ?: return@forEach
                            drawPathOnCanvas(cvs, path, BrandIndigo, stroke.strokeWidth, stroke.isHighlighter)
                        }
                    }
                    cvs.restore()
                }
                // 框：本紙子集 bounds 轉四角（與移動/縮放同一變換）；無子集不畫。
                val ownCorners = ownSelectionBounds?.let { b ->
                    listOf(
                        Offset(b.left, b.top), Offset(b.right, b.top),
                        Offset(b.right, b.bottom), Offset(b.left, b.bottom)
                    )
                }.orEmpty()
                val selectionRect = if (ownCorners.size < 4) null
                else modelTransformedPolygonBoundsToCanvasRect(
                    polygon = ownCorners,
                    translation = lassoMoveOffset,
                    scale = selectedStrokeScale,
                    anchor = selectionTransformAnchor,
                    sx = sx,
                    sy = sy
                )
                if (activeTool == Tool.LASSO && selectionRect != null && !selectionRect.isEmpty) {
                    drawLassoSelectionFrame(
                        selectionRect = selectionRect,
                        // 把手只在墨/圖子集非空時出現：字不支援套索縮放，有把手沒功能更騙人。
                        showHandles = ownSelectedStrokes.isNotEmpty() || selectedImageAnnotationIds.isNotEmpty(),
                        dashPhase = if (isSelectionTransforming) 0f else lassoDashPhase,
                        animateDash = !isSelectionTransforming,
                        visualRadiusPx = handleVisualRadiusPx,
                        haloRadiusPx = handleHaloRadiusPx,
                        pressedHandleCenter = pressedHandleCenter,
                        strokeScale = if (pressedHandleCenter != null)
                            com.vic.inkflow.ui.theme.Handles.PressedStrokeScale else 1f
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
                            color = BrandIndigo.copy(alpha = 0.6f),
                            start = rotTopLocal,
                            end = rotHandleLocal,
                            strokeWidth = 2f
                        )
                        drawLiquidGlassSelectionFrame(
                            rect = imgSelRect,
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
                    textAnnotations.forEach { ann ->
                        val isSelected = ann.id == selectedTextAnnotationId
                        // 同圖片：落定後 DB 已含新值，不再疊預覽（否則閃一下）。見 PendingTextSettle。
                        val tSettle = pendingTextSettleRef.value
                        val txtSettled = tSettle != null &&
                            tSettle.id == ann.id && tSettle.matches(ann)
                        val dx = if (isSelected && !txtSettled) textMoveDelta.x else 0f
                        val dy = if (isSelected && !txtSettled) textMoveDelta.y else 0f
                        val fsDelta = if (isSelected && !txtSettled) textFontSizeDelta else 0f
                        textPaint.textSize = if (isSelected)
                            (ann.fontSize + fsDelta).coerceAtLeast(4f) * sy
                        else ann.fontSize * sy
                        textPaint.color = ann.colorArgb
                        // Multiline: modelY is the first-line baseline (matches PDF export).
                        val lineHeight = with(textPaint.fontMetrics) { -ascent + descent + leading }
                        textLines[ann.id].orEmpty().forEachIndexed { i, line ->
                            composeCanvas.nativeCanvas.drawText(
                                line, ann.modelX * sx + dx, ann.modelY * sy + dy + i * lineHeight, textPaint
                            )
                        }
                    }
                }

                // 套索選中字：與 TEXT 工具打字態同一框（同函數、同把手；框由 ownSelectionBounds 統一出）。
                if (activeTool == Tool.LASSO) {
                    textAnnotations.forEach { ann ->
                        if (ann.id in vmSelectedTextIds) {
                            val r = textAnnotationHitRect(ann, sx, sy).translate(
                                lassoMoveOffset.x * sx, lassoMoveOffset.y * sy
                            )
                            drawLiquidGlassSelectionFrame(
                                rect = r,
                                handleCenters = listOf(Offset(r.right, r.bottom)),
                                visualRadiusPx = handleVisualRadiusPx,
                                haloRadiusPx = handleHaloRadiusPx
                            )
                        }
                    }
                }

                // Selection handles for the currently selected text annotation
                val selTextId  = selectedTextAnnotationId
                val selTextAnn = if (selTextId != null && activeTool == Tool.TEXT)
                    textAnnotations.firstOrNull { it.id == selTextId } else null
                if (selTextAnn != null) {
                    // 選取框跟著落定（否則框先跳、內容後跳 → 框脫節＋二次閃）。
                    val tS = pendingTextSettleRef.value
                    val txtSelSettled = tS != null && tS.id == selTextAnn.id && tS.matches(selTextAnn)
                    val fsDelta   = if (txtSelSettled) 0f else textFontSizeDelta
                    val mvDelta   = if (txtSelSettled) Offset.Zero else textMoveDelta
                    val textRect  = textAnnotationHitRect(selTextAnn, sx, sy, fsDelta).translate(mvDelta)
                    val textHandle = Offset(textRect.right, textRect.bottom)
                    drawLiquidGlassSelectionFrame(
                        rect = textRect,
                        handles = listOf(
                            HandleVisual(
                                center = textHandle,
                                pressed = if (pressedHandleCenter == textHandle) 1f else 0f
                            )
                        ),
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
                    val previewStyle = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
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
                        style = Stroke(width = 2f, pathEffect = dashPreview)
                    )
                }

                // Active freehand / eraser / lasso path
                when (activeTool) {
                    Tool.PEN -> drawPath(activeEnvelopePath, selectedColor) // Uses default Fill style
                    Tool.HIGHLIGHTER -> drawIntoCanvas { cvs ->
                        hlPreviewPaint.color = selectedColor.copy(alpha = 0.4f)
                        cvs.drawPath(activeEnvelopePath, hlPreviewPaint)
                    }
                    Tool.LASSO  -> drawPath(activePath, Color.DarkGray, style = Stroke(width = 2f, pathEffect = dashPreview))
                    Tool.ERASER -> {
                        // 橡皮擦視覺：半透明暖紅寬帶（實際擦除寬度）+ 實線中心 + 頭部游標環。
                        // 之前是 2px 灰線，擦到哪裡完全看不出來。
                        // 擦除半徑與命中判定一致：model 10f → 換算成 canvas px。
                        val modelW = viewModel.modelWidth
                        val eraserRpx = if (modelW > 0f) 10f * (size.width / modelW) else 24f
                        val eraserColor = Color(0xFFFF5A5A)
                        drawPath(
                            activePath, eraserColor.copy(alpha = 0.28f),
                            style = Stroke(width = eraserRpx * 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                        drawPath(
                            activePath, eraserColor,
                            style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                        // 頭部游標環：顯示實際擦除範圍，手指/筆尖擋住時也看得到邊緣
                        currentPathPoints.lastOrNull()?.let { head ->
                            val c = Offset(head.x, head.y)
                            drawCircle(
                                color = Color.White.copy(alpha = 0.30f),
                                radius = eraserRpx, center = c
                            )
                            drawCircle(
                                color = eraserColor, radius = eraserRpx, center = c,
                                style = Stroke(width = 2.5f)
                            )
                            drawCircle(color = eraserColor, radius = 3f, center = c)
                        }
                    }
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
                                Color(sharedPreview?.colorArgb ?: 0xFF000000.toInt()).copy(alpha = 0.4f)
                            cvs.drawPath(EnvelopeUtils.generateEnvelopePath(sharedSegs), hlPreviewPaint)
                        }
                        Tool.ERASER -> {
                            val epath = Path()
                            sharedSegs.forEachIndexed { i, sp ->
                                if (i == 0) epath.moveTo(sp.x, sp.y) else epath.lineTo(sp.x, sp.y)
                            }
                            val sharedErRpx = if (viewModel.modelWidth > 0f) {
                                10f * (size.width / viewModel.modelWidth)
                            } else 24f
                            drawPath(
                                epath, Color(0xFFFF5A5A).copy(alpha = 0.28f),
                                style = Stroke(width = sharedErRpx * 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                            // 中線：與本紙一致，跨頁不斷（之前鄰頁只有寬帶沒有線）。
                            drawPath(
                                epath, Color(0xFFFF5A5A),
                                style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                        Tool.LASSO -> {
                            val lpath = Path()
                            sharedSegs.forEachIndexed { i, sp ->
                                if (i == 0) lpath.moveTo(sp.x, sp.y) else lpath.lineTo(sp.x, sp.y)
                            }
                            drawPath(lpath, Color.DarkGray, style = Stroke(width = 2f, pathEffect = dashPreview))
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

    Box(modifier = modifier) {
        Canvas(modifier = drawModifier) { }

        // Inline text editor — spawns at the tap point / annotation, commits on Done or outside tap
        if (inlineOpen && activeTool == Tool.TEXT && canvasPixelSize != Size.Zero) {
            val cs = canvasPixelSize
            val osx = if (cs.width > 0f) cs.width / viewModel.modelWidth else 1f
            val osy = if (cs.height > 0f) cs.height / viewModel.modelHeight else 1f
            val editAnn = inlineTextEditId?.let { id -> textAnnotations.firstOrNull { it.id == id } }
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
                cursorColorArgb = inlineTextColor.toArgb()
            )
        }
    }
}

// ── 命中測試與幾何見 InkCanvasGeometry.kt ────────────────────────────────────

// ── 選取框型別與快取見 InkCanvasGeometry.kt ──────────────────────────────────

// ── 液態玻璃選取框見 InkCanvasGeometry.kt ────────────────────────────────────

// ── 選取框手柄與座標換算見 InkCanvasGeometry.kt，形狀/筆跡繪製見 InkCanvasRendering.kt ─

