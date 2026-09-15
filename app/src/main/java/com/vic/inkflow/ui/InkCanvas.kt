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

@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
fun InkCanvas(
    modifier: Modifier,
    viewModel: EditorViewModel,
    pdfViewModel: PdfViewModel,
    documentUri: String,
    /** 本頁在文件中的索引（跨頁分段/換頁歸屬用）。 */
    pageIndex: Int = 0,
    /** 頁間隙 px（Workspace 的 Arrangement.spacedBy，需與列表一致）。 */
    pageGapPx: Float = 0f,
    /** 邊緣自動捲：手指拖出紙上下界時回傳期望捲動量 px（正=往後頁）。 */
    onEdgeAutoScroll: (Float) -> Unit = {},
    /** 跨頁提交完成：回傳應激活的目標頁（與本頁不同才回調）。 */
    onCrossPageEnd: (Int) -> Unit = {}
) {
    val committedStrokes by viewModel.currentStrokes.collectAsState()
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
    val textAnnotations by viewModel.currentTextAnnotations.collectAsState()
    val imageAnnotations by viewModel.currentImageAnnotations.collectAsState()
    val selectedImageAnnotationIds by viewModel.selectedImageAnnotationIds.collectAsState()
    val paperStyle by viewModel.paperStyle.collectAsState()
    // 雙指縮放進行中：各畫筆迴圈見此即棄筆（由 Workspace 仲裁器寫入）
    val pinchActive by viewModel.pinchActive.collectAsState()
    // 連貫畫布：跨頁參數 refs（進長駐協程，不進 pointerInput key，手勢不被重啟打斷）
    val pageCount by pdfViewModel.pageCount.collectAsState()
    val pageIndexRef = rememberUpdatedState(pageIndex)
    val pageGapPxRef = rememberUpdatedState(pageGapPx)
    val pageCountRef = rememberUpdatedState(pageCount)
    val onEdgeAutoScrollRef = rememberUpdatedState(onEdgeAutoScroll)
    val onCrossPageEndRef = rememberUpdatedState(onCrossPageEnd)
    // 拖曳 overlay 接手時，紙內選取預覽讓位（同像素只畫一次，螢光筆疊色會變深）
    val dragPreviewActive by viewModel.dragPreviewActive.collectAsState()
    // 套索虛線動畫按需組成：無選取時不跑 choreographer，省常駐喚醒
    val needLassoAnim = activeTool == Tool.LASSO && selectedStrokePreview.isNotEmpty()
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
        else if (newPos != null) viewModel.addTextAnnotation(v, newPos.x, newPos.y, defaultTextFontPx, inlineTextColor)
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
    // M4: tap-to-place anchor in model space (set on empty-tap, consumed by the import callback).
    var pendingImageAnchorModel by remember { mutableStateOf<Offset?>(null) }
    var selectedImageAnnotationId by remember { mutableStateOf<String?>(null) }
    var imageMovePreview   by remember { mutableStateOf(Offset.Zero) }
    var imageResizeScale   by remember { mutableFloatStateOf(1f) }
    var imageResizeAnchor  by remember { mutableStateOf<Offset?>(null) }
    // M5: in-flight rotation delta in degrees (0 = none); committed value lives in the entity.
    var imageRotatePreview by remember { mutableFloatStateOf(0f) }

    // Latest snapshot of image annotations for use inside pointer-input coroutines
    val imageAnnotationsRef    = rememberUpdatedState(imageAnnotations)
    val selectedImageIdRef     = rememberUpdatedState(selectedImageAnnotationId)
    val selectedStrokePreviewBoundsRef = rememberUpdatedState(selectedStrokePreviewBounds)
    val selectionFramePolygonRef = rememberUpdatedState(selectionFramePolygon)
    val lassoMoveOffsetRef = rememberUpdatedState(lassoMoveOffset)
    val selectedStrokeScaleRef = rememberUpdatedState(selectedStrokeScale)
    val selectedStrokeResizeAnchorRef = rememberUpdatedState(selectedStrokeResizeAnchor)
    val isSelectionTransforming = lassoMoveOffset != Offset.Zero || abs(selectedStrokeScale - 1f) > 0.001f
    val selectionTransformAnchor = selectedStrokeResizeAnchor
        ?: selectedStrokePreviewBounds?.center
        ?: Offset.Zero
    val selectedPathData = remember(selectedStrokePreview) {
        selectedStrokePreview.map { swp ->
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

    // Image picker launcher — gallery opens on empty-canvas tap (IMAGE tool)
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 50)
    ) { uris ->
        if (uris.isEmpty()) {
            pendingImageAnchorModel = null
            return@rememberLauncherForActivityResult
        }
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
            // M4: first image lands centered on the tap anchor (if any); the rest keep legacy placement.
            val tapAnchor = pendingImageAnchorModel
            pendingImageAnchorModel = null
            if (imported.size == 1) {
                val item = imported.first()
                lastInsertedImageId = viewModel.placeImageAnnotationOnPage(
                    uri = item.localUriString,
                    targetPageIndex = viewModel.pageIndex.value,
                    imagePixelWidth = item.imagePixelWidth,
                    imagePixelHeight = item.imagePixelHeight,
                    anchorModel = tapAnchor
                )
            } else {
                val currentPageIndex = viewModel.pageIndex.value
                var insertAfterIndex = currentPageIndex
                imported.forEachIndexed { index, item ->
                    if (index == 0) {
                        lastInsertedImageId = viewModel.placeImageAnnotationOnPage(
                            uri = item.localUriString,
                            targetPageIndex = currentPageIndex,
                            imagePixelWidth = item.imagePixelWidth,
                            imagePixelHeight = item.imagePixelHeight,
                            anchorModel = tapAnchor
                        )
                    } else {
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

    // Open gallery whenever IMAGE tool is activated; clear selections on tool change
    LaunchedEffect(activeTool) {
        if (activeTool != Tool.TEXT) {
            if (inlineTextNewPos != null || inlineTextEditId != null) commitInlineText()
            selectedTextAnnotationId = null
            textMoveDelta = Offset.Zero
            textFontSizeDelta = 0f
        }
        if (activeTool != Tool.IMAGE) {
            selectedImageAnnotationId = null
            imageMovePreview = Offset.Zero
            imageResizeScale = 1f
            imageResizeAnchor = null
            imageRotatePreview = 0f
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
    // P0-0 PROBE: raw stylus axes captured at DOWN (tilt/orientation/pressure)
    var lastTiltDeg by remember { mutableFloatStateOf(-1f) }
    var lastOrientationDeg by remember { mutableFloatStateOf(-1f) }
    var lastAxisPressure by remember { mutableFloatStateOf(-1f) }
    // MotionEvent pointer ID → getTouchMajor(). Updated for every pointer down event.
    // Lets awaitEachGesture identify the stylus among simultaneous palm+stylus contacts.
    val pointerTouchMajors = remember { mutableStateMapOf<Int, Float>() }

    // ---- Modifier chain ----

    // P0-0 PROBE-ONLY hover logger: remove after probe. Observes, never consumes.
    androidx.compose.runtime.DisposableEffect(view) {
        var hoverMoves = 0
        val listener = android.view.View.OnHoverListener { _, event ->
            val isHover = event.actionMasked == MotionEvent.ACTION_HOVER_ENTER ||
                event.actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
                event.actionMasked == MotionEvent.ACTION_HOVER_EXIT
            if (isHover && event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                hoverMoves++
                if (event.actionMasked != MotionEvent.ACTION_HOVER_MOVE || hoverMoves % 30 == 0) {
                    val actionName = when (event.actionMasked) {
                        MotionEvent.ACTION_HOVER_ENTER -> "ENTER"
                        MotionEvent.ACTION_HOVER_MOVE -> "MOVE"
                        else -> "EXIT"
                    }
                    com.vic.inkflow.util.TouchEventLogger.logProbe(
                        "HOVER $actionName pos=(${event.x.toInt()},${event.y.toInt()})" +
                            " pressure=${"%.3f".format(event.pressure)}" +
                            " tilt=${"%.1f".format(event.getAxisValue(MotionEvent.AXIS_TILT))}"
                    )
                }
            }
            false
        }
        view.setOnHoverListener(listener)
        onDispose { view.setOnHoverListener(null) }
    }

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
                                with(density) { 20.sp.toPx() }, inlineTextColorRef.value
                            )
                        }
                    }
                    down.consume()
                    return@awaitEachGesture
                }

                // Claim accepted in-canvas gestures immediately so fast stylus motion cannot
                // leak through to the workspace pan handler before we enter the tool branch.
                downEvent.changes
                    .filter { it.pressed }
                    .forEach { it.consume() }
                down.consume()
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
                    val sx = if (cs.width > 0f) cs.width / viewModel.modelWidth else 1f
                    val sy = if (cs.height > 0f) cs.height / viewModel.modelHeight else 1f
                    val annotations = textAnnotationsRef.value
                    val selId = selectedTextIdRef.value
                    val selAnn = if (selId != null) annotations.firstOrNull { it.id == selId } else null

                    if (selAnn != null) {
                        val currentTextRect = textAnnotationHitRect(selAnn, sx, sy).translate(textMoveDelta)
                        val handleRect = textResizeHandleRect(currentTextRect)

                        // Resize handle hit
                        if (handleRect.contains(startOffset)) {
                            down.consume()
                            var accModelDelta = 0f
                            var drag = awaitDragOrCancellation(down.id)
                            while (drag != null && drag.pressed) {
                                if (pinchActive) {
                                    viewModel.commitTextAnnotationResize(selAnn.id, selAnn.modelX, selAnn.modelY, selAnn.fontSize + accModelDelta)
                                    textFontSizeDelta = 0f
                                    activePathVersion++
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
                            textFontSizeDelta = 0f
                            activePathVersion++
                            return@awaitEachGesture
                        }

                        // Move: drag inside the selected text box; plain tap re-edits contents inline
                        if (currentTextRect.contains(startOffset)) {
                            down.consume()
                            viewModel.setPageLock(true)
                            var totalDelta = Offset.Zero
                            var drag = awaitDragOrCancellation(down.id)
                            while (drag != null && drag.pressed) {
                                if (pinchActive) {
                                    viewModel.commitTextAnnotationMove(selAnn.id, totalDelta.x, totalDelta.y)
                                    textMoveDelta = Offset.Zero
                                    activePathVersion++
                                    viewModel.setPageLock(false)
                                    return@awaitEachGesture
                                }
                                val delta = drag.positionChange()
                                totalDelta += delta
                                textMoveDelta += delta
                                activePathVersion++
                                drag.consume()
                                // 連貫畫布：拖出紙界自動捲（Workspace 程式化捲動，不搶原生手勢）
                                val csH = canvasPixelSizeState.value.height
                                val autoDy = edgeAutoScrollDy(drag.position.y, csH)
                                if (autoDy != 0f) onEdgeAutoScrollRef.value(autoDy)
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
                                val scaleX = viewModel.modelWidth / cs.width.coerceAtLeast(1f)
                                val scaleY = viewModel.modelHeight / cs.height.coerceAtLeast(1f)
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
                                    textMoveDelta = Offset.Zero
                                    activePathVersion++
                                    onCrossPageEndRef.value(wrapped.first)
                                    return@awaitEachGesture
                                }
                                viewModel.commitTextAnnotationMove(selAnn.id, totalDelta.x, totalDelta.y)
                                viewModel.setPageLock(false)
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
                    val sx = if (cs.width > 0f) cs.width / viewModel.modelWidth else 1f
                    val sy = if (cs.height > 0f) cs.height / viewModel.modelHeight else 1f
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
                        val rotHandleLocal = Offset(localRect.center.x, localRect.top - IMAGE_ROT_HANDLE_GAP_PX)
                        val rotHandle = rotatePoint(rotHandleLocal, pivot, theta)

                        // — Rotate: tap on the top handle first (may overlap a corner on tiny images) —
                        if (imageResizeHandleRect(rotHandle).contains(startOffset)) {
                            down.consume()
                            val grabAngle = atan2(startOffset.y - pivot.y, startOffset.x - pivot.x) *
                                (180f / Math.PI.toFloat())
                            imageRotatePreview = 0f
                            fun commitRotated() {
                                viewModel.commitImageAnnotationRotation(
                                    selAnn.id, selAnn.rotation + imageRotatePreview
                                )
                                imageRotatePreview = 0f
                                activePathVersion++
                            }
                            var drag = awaitDragOrCancellation(down.id)
                            while (drag != null && drag.pressed) {
                                if (pinchActive) {
                                    commitRotated()
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
                            return@awaitEachGesture
                        }

                        // — Resize: tap on any corner handle (aspect locked, rotation-aware) —
                        val hitIdx = screenCorners.indexOfFirst {
                            imageResizeHandleRect(it).contains(startOffset)
                        }
                        if (hitIdx >= 0) {
                            down.consume()
                            // All resize math happens in local (unrotated) space about the fixed pivot.
                            val oppIdx = when (hitIdx) { 0 -> 3; 1 -> 2; 2 -> 1; else -> 0 }
                            val anchorLocal = localCorners[oppIdx]
                            val startLocal = rotatePoint(startOffset, pivot, -theta)
                            val grabVec = startLocal - anchorLocal
                            val grabLenSq = (grabVec.x * grabVec.x + grabVec.y * grabVec.y).coerceAtLeast(1f)
                            imageResizeAnchor = anchorLocal
                            imageResizeScale = 1f
                            fun commitScaled() {
                                val sc = StrokeTransformUtils.clampUniformScale(imageResizeScale)
                                val r = scaleRectAbout(imageAnnotationRect(selAnn, sx, sy), anchorLocal, sc)
                                viewModel.commitImageAnnotationResize(
                                    selAnn.id, r.left / sx, r.top / sy, r.width / sx, r.height / sy
                                )
                                imageResizeScale = 1f
                                imageResizeAnchor = null
                                activePathVersion++
                            }
                            var drag = awaitDragOrCancellation(down.id)
                            while (drag != null && drag.pressed) {
                                if (pinchActive) {
                                    commitScaled()
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
                            return@awaitEachGesture
                        }

                        // — Move: tap inside the image body —
                        if (rotatedRectContains(localRect, theta, startOffset)) {
                            down.consume()
                            viewModel.setPageLock(true)
                            var totalDelta = Offset.Zero
                            var drag = awaitDragOrCancellation(down.id)
                            while (drag != null && drag.pressed) {
                                if (pinchActive) {
                                    viewModel.commitImageAnnotationMove(selAnn.id, totalDelta.x, totalDelta.y)
                                    imageMovePreview = Offset.Zero
                                    activePathVersion++
                                    viewModel.setPageLock(false)
                                    return@awaitEachGesture
                                }
                                val delta = drag.positionChange()
                                totalDelta += delta
                                imageMovePreview += delta
                                activePathVersion++
                                drag.consume()
                                // 連貫畫布：拖出紙界自動捲
                                val csH = canvasPixelSizeState.value.height
                                val autoDy = edgeAutoScrollDy(drag.position.y, csH)
                                if (autoDy != 0f) onEdgeAutoScrollRef.value(autoDy)
                                drag = awaitDragOrCancellation(drag.id)
                            }
                            // 跨頁：推出本頁 → 整顆換頁（旋轉角保留）；否則舊提交
                            val cs = canvasPixelSizeState.value
                            val scaleX = viewModel.modelWidth / cs.width.coerceAtLeast(1f)
                            val scaleY = viewModel.modelHeight / cs.height.coerceAtLeast(1f)
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
                                imageMovePreview = Offset.Zero
                                activePathVersion++
                                onCrossPageEndRef.value(wrapped.first)
                                return@awaitEachGesture
                            }
                            viewModel.commitImageAnnotationMove(selAnn.id, totalDelta.x, totalDelta.y)
                            viewModel.setPageLock(false)
                            imageMovePreview = Offset.Zero
                            activePathVersion++
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

                    // Tap on empty canvas: remember the tap point (model space) so the
                    // imported image lands centered on it, then open gallery picker
                    down.consume()
                    pendingImageAnchorModel = Offset(
                        startOffset.x / sx.coerceAtLeast(1f),
                        startOffset.y / sy.coerceAtLeast(1f)
                    )
                    imagePickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    return@awaitEachGesture
                }

                if (activeTool == Tool.LASSO) {
                    val selectionBounds = selectedStrokePreviewBoundsRef.value
                    val hasLassoSelection = selectedStrokePreview.isNotEmpty() ||
                        selectedImageAnnotationIds.isNotEmpty() ||
                        selectionFramePolygonRef.value.isNotEmpty()
                    val cs = canvasPixelSizeState.value
                    val sx = if (cs.width > 0f) cs.width / viewModel.modelWidth else 1f
                    val sy = if (cs.height > 0f) cs.height / viewModel.modelHeight else 1f
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
                        val hitHandle = strokeSelectionHandleRects(selectionRect)
                            .firstOrNull { (_, handleRect) -> handleRect.contains(startOffset) }
                        if (hitHandle != null) {
                            down.consume()
                            val handle = hitHandle.first
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
                            return@awaitEachGesture
                        }

                        if (selectionRect.contains(startOffset)) {
                            down.consume()
                            viewModel.setPageLock(true)
                            viewModel.setDragPreviewActive(true)
                            var drag = awaitDragOrCancellation(down.id)
                            while (drag != null && drag.pressed) {
                                if (pinchActive) {
                                    viewModel.commitMovedStrokes()
                                    viewModel.setPageLock(false)
                                    viewModel.setDragPreviewActive(false)
                                    return@awaitEachGesture
                                }
                                val delta = drag.positionChange()
                                if (delta != Offset.Zero) viewModel.moveSelectedStrokes(delta)
                                drag.consume()
                                // 連貫畫布：拖出紙界自動捲
                                val csH = canvasPixelSizeState.value.height
                                val autoDy = edgeAutoScrollDy(drag.position.y, csH)
                                if (autoDy != 0f) onEdgeAutoScrollRef.value(autoDy)
                                drag = awaitDragOrCancellation(drag.id)
                            }
                            val maxPage = (pageCountRef.value - 1).coerceAtLeast(0)
                            val crossTarget = viewModel.commitMovedStrokes(maxPage)
                            viewModel.setPageLock(false)
                            viewModel.setDragPreviewActive(false)
                            if (crossTarget != null) {
                                onCrossPageEndRef.value(crossTarget)
                            }
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
                                viewModel.deleteStrokesIntersecting(points)
                            } else {
                                viewModel.saveShape(startOffset, end, selectedColor, strokeWidth)
                            }
                        } else if (activeTool == Tool.LASSO) {
                            val pts = listOf(
                                Offset(startOffset.x, startOffset.y),
                                Offset(end.x, startOffset.y),
                                Offset(end.x, end.y),
                                Offset(startOffset.x, end.y)
                            )
                            viewModel.selectStrokesInLasso(pts)
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
                            }
                        }
                        
                        if (!quickSwipeTriggered && (activeTool == Tool.PEN || activeTool == Tool.HIGHLIGHTER)) {
                            // 預覽包絡先過中心線平滑（入庫點列不動，匯出零影響）。
                            val previewPts = smoothCenterline(currentPathPoints)
                            val newPath = EnvelopeUtils.generateEnvelopePath(previewPts)
                            activeEnvelopePath.reset()
                            activeEnvelopePath.addPath(newPath)
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
                                    viewModel.deleteStrokesIntersecting(
                                        points,
                                        markEraseHit = activeTool == Tool.ERASER
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
                    if (activeTool == Tool.ERASER) viewModel.clearEraseHitPending()
                    viewModel.setPageLock(false)
                    return@awaitEachGesture
                }

                // 手指全程沒動、縮放卻在中途發生過：同樣丟棄，避免提交錯位座標
                if (viewModel.docZoom.value != zoomAtStrokeStart) {
                    activePath.reset()
                    activeEnvelopePath.reset()
                    currentPathPoints.clear()
                    activePathVersion++
                    if (activeTool == Tool.ERASER) viewModel.clearEraseHitPending()
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
                            viewModel.deleteStrokesIntersecting(points, markEraseHit = false)
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
                            segs.forEach { (pg, segPts) ->
                                if (segPts.size >= 2) {
                                    viewModel.saveStroke(
                                        segPts, selectedColor, activeTool, strokeWidth,
                                        targetPage = pg
                                    )
                                    lastPage = pg
                                }
                            }
                            viewModel.setPageLock(false)
                            if (lastPage != srcPage) onCrossPageEndRef.value(lastPage)
                        }
                    }
                    Tool.LASSO -> {
                        activePath.close()
                        viewModel.selectStrokesInLasso(currentPathPoints.map { Offset(it.x, it.y) })
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
                        viewModel.deleteStrokesIntersecting(
                            eraserPointsCanvas = points,
                            switchToPenAfterEraseHit = true
                        )
                    }
                    else -> { }
                }
                activePath.reset()
                activeEnvelopePath.reset()
                activePathVersion++
                currentPathPoints.clear()
                // 兜底：自由筆各提交路徑在此統一清鎖（PEN/HL 已在分支內清過，重複無害）
                viewModel.setPageLock(false)
            }
        }
        .drawWithCache {
            val sx = size.width  / viewModel.modelWidth
            val sy = size.height / viewModel.modelHeight

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
                            val dx = if (isSelectedInImageTool) imageMovePreview.x else 0f
                            val dy = if (isSelectedInImageTool) imageMovePreview.y else 0f
                            val base = Rect(
                                left = ann.modelX * sx + dx,
                                top = ann.modelY * sy + dy,
                                right = ann.modelX * sx + dx + ann.modelWidth * sx,
                                bottom = ann.modelY * sy + dy + ann.modelHeight * sy
                            )
                            val eff = if (isSelectedInImageTool)
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
                    // in-flight rotate delta only applies to the IMAGE-tool-selected image.
                    val imgTheta = ann.rotation +
                        (if (isSelectedInImageTool) imageRotatePreview else 0f)
                    val imgPivot = if (isSelectedInSelectionTool) canvasRect.center else run {
                        val bx = ann.modelX * sx + (if (isSelectedInImageTool) imageMovePreview.x else 0f)
                        val by = ann.modelY * sy + (if (isSelectedInImageTool) imageMovePreview.y else 0f)
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
            // While a commitPreview is active (DB write in flight after a move), draw the preview layer separately.
            if (preview != null) {
                drawIntoCanvas { cvs ->
                    cvs.save()
                    cvs.scale(sx, sy)
                    // Preview layer: moved strokes at their committed (new) position.
                    preview.forEach { swp ->
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
                val previewBounds = selectedStrokePreviewBounds
                val selectionRect = modelTransformedPolygonBoundsToCanvasRect(
                    polygon = selectionFramePolygon,
                    translation = lassoMoveOffset,
                    scale = selectedStrokeScale,
                    anchor = selectionTransformAnchor,
                    sx = sx,
                    sy = sy
                )
                if (activeTool == Tool.LASSO && selectionRect != null && !selectionRect.isEmpty) {
                    drawLassoSelectionFrame(
                        selectionRect = selectionRect,
                        showHandles = selectedStrokePreview.isNotEmpty() || selectedImageAnnotationIds.isNotEmpty(),
                        dashPhase = if (isSelectionTransforming) 0f else lassoDashPhase,
                        animateDash = !isSelectionTransforming
                    )
                }
            }

                // Selection handles for the currently selected image
                val selImgId  = selectedImageAnnotationId
                val selImgAnn = if (selImgId != null && activeTool == Tool.IMAGE)
                    imageAnnotations.firstOrNull { it.id == selImgId } else null
                if (selImgAnn != null) {
                    val r = imageAnnotationRect(selImgAnn, sx, sy)
                    val imgSelRect = effectiveImageRect(
                        r, imageMovePreview, imageResizeAnchor, imageResizeScale
                    )
                    // M5: frame + handles live in local space, rotated about the same pivot
                    // the gesture uses (committed+move center).
                    val imgTheta = selImgAnn.rotation + imageRotatePreview
                    val imgPivot = r.translate(imageMovePreview).center
                    val rotTopLocal = Offset(imgSelRect.center.x, imgSelRect.top)
                    val rotHandleLocal = rotTopLocal + Offset(0f, -IMAGE_ROT_HANDLE_GAP_PX)
                    rotate(imgTheta, imgPivot) {
                        drawLine(
                            color = BrandIndigo.copy(alpha = 0.6f),
                            start = rotTopLocal,
                            end = rotHandleLocal,
                            strokeWidth = 2f
                        )
                        drawLiquidGlassSelectionFrame(
                            rect = imgSelRect,
                            handleCenters = listOf(
                                imgSelRect.topLeft, imgSelRect.topRight,
                                imgSelRect.bottomLeft, imgSelRect.bottomRight,
                                rotHandleLocal
                            )
                        )
                    }
                }

                // Text annotations — apply move/resize delta for the selected annotation
                drawIntoCanvas { composeCanvas ->
                    textAnnotations.forEach { ann ->
                        val isSelected = ann.id == selectedTextAnnotationId
                        val dx = if (isSelected) textMoveDelta.x else 0f
                        val dy = if (isSelected) textMoveDelta.y else 0f
                        textPaint.textSize = if (isSelected)
                            (ann.fontSize + textFontSizeDelta).coerceAtLeast(4f) * sy
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

                // Selection handles for the currently selected text annotation
                val selTextId  = selectedTextAnnotationId
                val selTextAnn = if (selTextId != null && activeTool == Tool.TEXT)
                    textAnnotations.firstOrNull { it.id == selTextId } else null
                if (selTextAnn != null) {
                    val fsDelta   = textFontSizeDelta
                    val textRect  = textAnnotationHitRect(selTextAnn, sx, sy, fsDelta).translate(textMoveDelta)
                    drawLiquidGlassSelectionFrame(
                        rect = textRect,
                        handleCenters = listOf(Offset(textRect.right, textRect.bottom))
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
            val cs  = canvasPixelSize
            val osx = if (cs.width > 0f) cs.width / viewModel.modelWidth else 1f
            val osy = if (cs.height > 0f) cs.height / viewModel.modelHeight else 1f
            val editAnn = inlineTextEditId?.let { id -> textAnnotations.firstOrNull { it.id == id } }
            val anchorPx = when {
                editAnn != null       -> Offset(editAnn.modelX * osx, editAnn.modelY * osy)
                inlineTextNewPos != null -> inlineTextNewPos!!
                else                  -> Offset.Zero
            }
            val fontPx  = editAnn?.fontSize?.times(osy) ?: defaultTextFontPx
            val boxX    = anchorPx.x.coerceIn(0f, (cs.width - 160f).coerceAtLeast(0f))
            // anchorPx is the first-line baseline; the box top sits one line above it
            val boxY    = (anchorPx.y - fontPx - 8f).coerceAtLeast(0f)
            val maxBoxW = with(density) { (cs.width - boxX - 8f).coerceAtLeast(120f).toDp() }
            BasicTextField(
                value = inlineTextValue,
                onValueChange = { inlineTextValue = it },
                modifier = Modifier
                    .offset { IntOffset(boxX.toInt(), boxY.toInt()) }
                    .widthIn(min = 140.dp, max = maxBoxW)
                    .background(Color.White, RoundedCornerShape(6.dp))
                    .border(1.5.dp, BrandIndigo, RoundedCornerShape(6.dp))
                    .padding(6.dp)
                    .focusRequester(inlineFocusRequester),
                textStyle = TextStyle(
                    fontSize   = with(density) { fontPx.toSp() },
                    fontWeight = FontWeight.Bold,
                    color      = Color(editAnn?.colorArgb ?: inlineTextColor.toArgb())
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commitInlineText() }),
                singleLine = false,
                maxLines   = 8,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(BrandIndigo)
            )
        }
    }
}

// ── 命中測試與幾何見 InkCanvasGeometry.kt ────────────────────────────────────

// ── 選取框型別與快取見 InkCanvasGeometry.kt ──────────────────────────────────

// ── 液態玻璃選取框見 InkCanvasGeometry.kt ────────────────────────────────────

// ── 選取框手柄與座標換算見 InkCanvasGeometry.kt，形狀/筆跡繪製見 InkCanvasRendering.kt ─

