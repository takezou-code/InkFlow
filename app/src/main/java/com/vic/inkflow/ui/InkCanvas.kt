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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.vic.inkflow.util.StrokePoint
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

private const val ENABLE_PALM_DEBUG_LOGS = false
private val QUICK_SWIPE_MIN_TRAVEL_DISTANCE = 96.dp
private const val QUICK_SWIPE_MAX_DURATION_MS = 320L
private const val QUICK_SWIPE_MIN_PATH_VELOCITY_DP_PER_MS = 0.42f
private val QUICK_SWIPE_DIRECTION_DELTA = 10.dp
private const val QUICK_SWIPE_MIN_DIRECTION_REVERSALS = 1
private const val ERASER_LIVE_WINDOW_POINTS = 20
private const val ERASER_LIVE_DISPATCH_INTERVAL_MS = 24L

private fun palmDebugLog(message: String) {
    if (ENABLE_PALM_DEBUG_LOGS) {
        Log.d("PALM_LOG", message)
    }
}

private fun countDirectionReversals(points: List<Offset>, minStepPx: Float): Int {
    if (points.size < 3) return 0

    var minX = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    points.forEach { p ->
        if (p.x < minX) minX = p.x
        if (p.x > maxX) maxX = p.x
        if (p.y < minY) minY = p.y
        if (p.y > maxY) maxY = p.y
    }

    val useX = (maxX - minX) >= (maxY - minY)
    var previousSign = 0
    var reversals = 0
    for (i in 1 until points.size) {
        val delta = if (useX) points[i].x - points[i - 1].x else points[i].y - points[i - 1].y
        if (abs(delta) < minStepPx) continue
        val sign = if (delta > 0f) 1 else -1
        if (previousSign != 0 && sign != previousSign) {
            reversals++
        }
        previousSign = sign
    }
    return reversals
}

private fun shouldTriggerQuickSwipeEraser(
    points: List<Offset>,
    elapsedMs: Long,
    density: Density
): Boolean {
    if (points.size < 4) return false
    if (elapsedMs <= 0L || elapsedMs > QUICK_SWIPE_MAX_DURATION_MS) return false

    var pathLengthPx = 0f
    for (i in 1 until points.size) {
        pathLengthPx += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
    }

    val minTravelPx = with(density) { QUICK_SWIPE_MIN_TRAVEL_DISTANCE.toPx() }
    if (pathLengthPx < minTravelPx) return false

    val minVelocityPxPerMs = with(density) { QUICK_SWIPE_MIN_PATH_VELOCITY_DP_PER_MS.dp.toPx() }
    val pathVelocityPxPerMs = pathLengthPx / elapsedMs.toFloat()
    if (pathVelocityPxPerMs < minVelocityPxPerMs) return false

    val minDirectionStepPx = with(density) { QUICK_SWIPE_DIRECTION_DELTA.toPx() }
    val directionReversals = countDirectionReversals(points, minDirectionStepPx)
    return directionReversals >= QUICK_SWIPE_MIN_DIRECTION_REVERSALS
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
fun InkCanvas(
    modifier: Modifier,
    viewModel: EditorViewModel,
    pdfViewModel: PdfViewModel,
    documentUri: String
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
    val palmThresholdDp by viewModel.palmThresholdDp.collectAsState()
    val strokeSpeedSensitivity by viewModel.strokeSpeedSensitivity.collectAsState()
    val fingerTouchThresholdDp by viewModel.fingerTouchThresholdDp.collectAsState()
    val textAnnotations by viewModel.currentTextAnnotations.collectAsState()
    val imageAnnotations by viewModel.currentImageAnnotations.collectAsState()
    val selectedImageAnnotationIds by viewModel.selectedImageAnnotationIds.collectAsState()
    val paperStyle by viewModel.paperStyle.collectAsState()
    // 雙指縮放進行中：各畫筆迴圈見此即棄筆（由 Workspace 仲裁器寫入）
    val pinchActive by viewModel.pinchActive.collectAsState()
    val lassoFrameTransition = rememberInfiniteTransition(label = "lasso-frame")
    val lassoDashPhase by lassoFrameTransition.animateFloat(
        initialValue = 0f,
        targetValue = 20f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "lasso-dash-phase"
    )

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

    // Stamp placement dialog
    var pendingTextPosition by remember { mutableStateOf<Offset?>(null) }
    var pendingIsStamp      by remember { mutableStateOf(false) }

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

    // Text annotation interactive selection / move / resize state
    var selectedTextAnnotationId by remember { mutableStateOf<String?>(null) }
    var textMoveDelta by remember { mutableStateOf(Offset.Zero) }
    var textFontSizeDelta by remember { mutableFloatStateOf(0f) }

    // Latest snapshot of text annotations for use inside pointer-input coroutines
    val textAnnotationsRef = rememberUpdatedState(textAnnotations)
    val selectedTextIdRef  = rememberUpdatedState(selectedTextAnnotationId)

    // Image annotation interactive selection / move / resize state
    var selectedImageAnnotationId by remember { mutableStateOf<String?>(null) }
    var imageMovePreview   by remember { mutableStateOf(Offset.Zero) }
    var imageResizePreview by remember { mutableStateOf(Offset.Zero) }

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

    // Cache freehand paths for committed strokes to avoid rebuilding geometry on every cache refresh.
    val committedPathCache = remember { mutableStateMapOf<String, CachedStrokePath>() }
    LaunchedEffect(committedStrokes) {
        val freehandIds = committedStrokes.asSequence()
            .filter { it.stroke.shapeType == null }
            .map { it.stroke.id }
            .toHashSet()
        committedPathCache.keys
            .filter { it !in freehandIds }
            .forEach { committedPathCache.remove(it) }

        committedStrokes.forEach { swp ->
            if (swp.stroke.shapeType != null) return@forEach
            val cached = committedPathCache[swp.stroke.id]
            if (!swp.matches(cached)) {
                committedPathCache[swp.stroke.id] = CachedStrokePath(
                    path = swp.points.toComposePath(),
                    pointCount = swp.points.size,
                    boundsLeft = swp.stroke.boundsLeft,
                    boundsTop = swp.stroke.boundsTop,
                    boundsRight = swp.stroke.boundsRight,
                    boundsBottom = swp.stroke.boundsBottom
                )
            }
        }
    }

    // Image bitmap cache: uri-string → decoded Bitmap (null = load failed / placeholder)
    val loadedImages = remember { mutableStateMapOf<String, android.graphics.Bitmap?>() }

    // Image picker launcher — gallery opens on empty-canvas tap (IMAGE tool)
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
            if (imported.size == 1) {
                val item = imported.first()
                lastInsertedImageId = viewModel.placeImageAnnotationOnPage(
                    uri = item.localUriString,
                    targetPageIndex = viewModel.pageIndex.value,
                    imagePixelWidth = item.imagePixelWidth,
                    imagePixelHeight = item.imagePixelHeight
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
                            imagePixelHeight = item.imagePixelHeight
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
                imageResizePreview = Offset.Zero
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
            imageResizePreview = Offset.Zero
        }
    }

    // Async bitmap loader — runs whenever annotation list changes
    LaunchedEffect(imageAnnotations) {
        imageAnnotations.forEach { ann ->
            if (ann.uri !in loadedImages) {
                loadedImages[ann.uri] = null
                scope.launch(Dispatchers.IO) {
                    val bmp = try {
                        context.contentResolver.openInputStream(Uri.parse(ann.uri))?.use { stream ->
                            BitmapFactory.decodeStream(stream)
                        }
                    } catch (_: Exception) { null }
                    withContext(Dispatchers.Main) { loadedImages[ann.uri] = bmp }
                }
            }
        }
    }

    // ---- Dialogs ----

    val pendingPos = pendingTextPosition

    if (pendingPos != null && pendingIsStamp) {
        val stamps = listOf("✓", "✗", "⭐", "❤", "★", "!", "?", "→")
        AlertDialog(
            onDismissRequest = { pendingTextPosition = null; pendingIsStamp = false },
            title = { Text("選擇印章") },
            text = {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    stamps.forEach { stamp ->
                        TextButton(onClick = {
                            viewModel.addTextAnnotation(
                                text   = stamp,
                                canvasX = pendingPos.x,
                                canvasY = pendingPos.y,
                                fontSize = 48f,
                                color  = selectedColor,
                                isStamp = true
                            )
                            pendingTextPosition = null
                            pendingIsStamp = false
                        }) { Text(stamp, fontSize = 24.sp) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pendingTextPosition = null; pendingIsStamp = false }) {
                    Text("取消")
                }
            }
        )
    }

    // Raw MotionEvent metrics captured via pointerInteropFilter (before Compose pipeline).
    // pointerInteropFilter fires on ACTION_DOWN and stores contact info for the pointerInput block.
    var lastTouchMajorPx by remember { mutableFloatStateOf(0f) }
    var lastTouchMinorPx by remember { mutableFloatStateOf(0f) }
    var lastToolMajorPx  by remember { mutableFloatStateOf(0f) }
    var lastNativeToolType by remember { mutableIntStateOf(0) }
    var lastPointerCount by remember { mutableIntStateOf(1) }
    var stylusButtonPressed by remember { mutableStateOf(false) }
    // MotionEvent pointer ID → getTouchMajor(). Updated for every pointer down event.
    // Lets awaitEachGesture identify the stylus among simultaneous palm+stylus contacts.
    val pointerTouchMajors = remember { mutableStateMapOf<Int, Float>() }

    // ---- Modifier chain ----

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
                // PALM_REJECTION + multi-touch: locate the stylus among all contacts
                //   (smallest touchMajor ≤ STYLUS_TOUCH_MAJOR_THRESHOLD).
                // All other modes: block multi-touch entirely (existing behaviour).
                val down = if (firstEvent.changes.size > 1) {
                    if (inputMode != InputMode.PALM_REJECTION) return@awaitEachGesture
                    val candidate = firstEvent.changes.minByOrNull { c ->
                        pointerTouchMajors[c.id.value.toInt()] ?: Float.MAX_VALUE
                    } ?: return@awaitEachGesture
                    val cMajor = pointerTouchMajors[candidate.id.value.toInt()] ?: Float.MAX_VALUE
                    when {
                        // All contacts are palms
                        PalmRejectionFilter.shouldReject(cMajor, 1f, 1, density, palmThresholdDp.dp) ->
                            return@awaitEachGesture
                        // All contacts are fingers — pass through for pan
                        PalmRejectionFilter.isFinger(cMajor, density, fingerTouchThresholdDp.dp) ->
                            return@awaitEachGesture
                        // Stylus found — use it for drawing; ignore palm/finger sibling pointers
                        else -> candidate
                    }
                } else {
                    val singleMajor =
                        pointerTouchMajors[firstContactDown.id.value.toInt()] ?: lastTouchMajorPx
                    if (inputMode == InputMode.PALM_REJECTION &&
                        PalmRejectionFilter.shouldReject(singleMajor, 1f, 1, density, palmThresholdDp.dp)
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
                                if (!PalmRejectionFilter.shouldReject(major, 1f, 1, density, palmThresholdDp.dp) &&
                                    !PalmRejectionFilter.isFinger(major, density, fingerTouchThresholdDp.dp)
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
                    posY             = down.position.y
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
                        density = density,
                        maxTouchMajorDp = palmThresholdDp.dp
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
                    if (PalmRejectionFilter.isFinger(touchMajorPx, density, fingerTouchThresholdDp.dp)) {
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

                val startOffset = down.position

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
                            var totalDelta = Offset.Zero
                            var drag = awaitDragOrCancellation(down.id)
                            while (drag != null && drag.pressed) {
                                if (pinchActive) {
                                    viewModel.commitTextAnnotationMove(selAnn.id, totalDelta.x, totalDelta.y)
                                    textMoveDelta = Offset.Zero
                                    activePathVersion++
                                    return@awaitEachGesture
                                }
                                val delta = drag.positionChange()
                                totalDelta += delta
                                textMoveDelta += delta
                                activePathVersion++
                                drag.consume()
                                drag = awaitDragOrCancellation(drag.id)
                            }
                            if (totalDelta == Offset.Zero) {
                                inlineTextEditId = selAnn.id
                                inlineTextValue  = selAnn.text
                                inlineTextColor  = Color(selAnn.colorArgb)
                            } else {
                                viewModel.commitTextAnnotationMove(selAnn.id, totalDelta.x, totalDelta.y)
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

                // Stamp: single tap to place
                if (activeTool == Tool.STAMP) {
                    down.consume()
                    pendingIsStamp = true
                    pendingTextPosition = startOffset
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
                        // Effective image rect in canvas space (committed position + in-flight move)
                        val imgRect = imageAnnotationRect(selAnn, sx, sy).let { r ->
                            Rect(r.left + imageMovePreview.x, r.top + imageMovePreview.y,
                                 r.right + imageMovePreview.x, r.bottom + imageMovePreview.y)
                        }
                        // Effective bottom-right corner (includes any in-flight resize)
                        val effectiveBR = Offset(
                            imgRect.right  + imageResizePreview.x,
                            imgRect.bottom + imageResizePreview.y
                        )
                        val hrImg = imageResizeHandleRect(effectiveBR)

                        // — Resize: tap on the bottom-right handle —
                        if (hrImg.contains(startOffset)) {
                            down.consume()
                            var drag = awaitDragOrCancellation(down.id)
                            while (drag != null && drag.pressed) {
                                if (pinchActive) {
                                    val newModelW = ((selAnn.modelWidth * sx + imageResizePreview.x).coerceAtLeast(30f)) / sx
                                    val newModelH = ((selAnn.modelHeight * sy + imageResizePreview.y).coerceAtLeast(30f)) / sy
                                    viewModel.commitImageAnnotationResize(selAnn.id, selAnn.modelX, selAnn.modelY, newModelW, newModelH)
                                    imageResizePreview = Offset.Zero
                                    activePathVersion++
                                    return@awaitEachGesture
                                }
                                // Direct mapping: handle follows the finger
                                imageResizePreview += drag.positionChange()
                                activePathVersion++
                                drag.consume()
                                drag = awaitDragOrCancellation(drag.id)
                            }
                            // Commit: convert effective canvas size back to model space
                            val newModelW = ((selAnn.modelWidth  * sx + imageResizePreview.x).coerceAtLeast(30f)) / sx
                            val newModelH = ((selAnn.modelHeight * sy + imageResizePreview.y).coerceAtLeast(30f)) / sy
                            viewModel.commitImageAnnotationResize(selAnn.id, selAnn.modelX, selAnn.modelY, newModelW, newModelH)
                            imageResizePreview = Offset.Zero
                            activePathVersion++
                            return@awaitEachGesture
                        }

                        // — Move: tap inside the image body —
                        if (imgRect.contains(startOffset)) {
                            down.consume()
                            var totalDelta = Offset.Zero
                            var drag = awaitDragOrCancellation(down.id)
                            while (drag != null && drag.pressed) {
                                if (pinchActive) {
                                    viewModel.commitImageAnnotationMove(selAnn.id, totalDelta.x, totalDelta.y)
                                    imageMovePreview = Offset.Zero
                                    activePathVersion++
                                    return@awaitEachGesture
                                }
                                val delta = drag.positionChange()
                                totalDelta += delta
                                imageMovePreview += delta
                                activePathVersion++
                                drag.consume()
                                drag = awaitDragOrCancellation(drag.id)
                            }
                            viewModel.commitImageAnnotationMove(selAnn.id, totalDelta.x, totalDelta.y)
                            imageMovePreview = Offset.Zero
                            activePathVersion++
                            return@awaitEachGesture
                        }
                    }

                    // Tap on another image to select it
                    val hitAnn = annotations.firstOrNull { ann ->
                        imageAnnotationRect(ann, sx, sy).contains(startOffset)
                    }
                    if (hitAnn != null) {
                        selectedImageAnnotationId = hitAnn.id
                        imageMovePreview = Offset.Zero
                        imageResizePreview = Offset.Zero
                        down.consume()
                        activePathVersion++
                        return@awaitEachGesture
                    }

                    // Tap on empty canvas: open gallery picker
                    down.consume()
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
                            var drag = awaitDragOrCancellation(down.id)
                            while (drag != null && drag.pressed) {
                                if (pinchActive) {
                                    viewModel.commitMovedStrokes()
                                    return@awaitEachGesture
                                }
                                val delta = drag.positionChange()
                                if (delta != Offset.Zero) viewModel.moveSelectedStrokes(delta)
                                drag.consume()
                                drag = awaitDragOrCancellation(drag.id)
                            }
                            viewModel.commitMovedStrokes()
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

                currentPathPoints.clear()
                currentPathPoints.add(StrokePoint(startOffset.x, startOffset.y, currentW))
                activePathVersion++
                down.consume()

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
                            return@awaitEachGesture
                        }
                        // Track peak pressure for outcome log
                        maxPressureDuring = maxOf(maxPressureDuring, drag.pressure)

                        // Pressure spike mid-stroke (same threshold reasoning as above)
                        if (drag.type == PointerType.Touch && drag.pressure > 1.5f) {
                            isRejected = true
                            break
                        }

                        val calcWidth = { pos: Offset, time: Long ->
                            val prevPt = currentPathPoints.last()
                            val dist = kotlin.math.hypot(pos.x - prevPt.x, pos.y - prevPt.y)
                            val dt = (time - lastPointTime).coerceAtLeast(1L)
                                val velocity = dist / dt.toFloat()
                                val sensitivity = strokeSpeedSensitivity.coerceAtLeast(0.1f)
                            
                            val w = if (activeTool == Tool.HIGHLIGHTER) {
                                baseWidth // For highlighter, do NOT apply variable thickness
                            } else {
                                    val maxW = baseWidth * 1.3f   // 最粗：稍微放大即可，不需要誇張
                                    val minW = baseWidth * 0.4f   // 最細
                                    // Sensitivity > 1.0 makes thinning happen sooner; < 1.0 makes it slower.
                                    val velocityThreshold = 0.7f / sensitivity
                                    val vMapped = (velocity / velocityThreshold).coerceIn(0f, 1f)
                                val targetW = minW + (maxW - minW) * (1f - vMapped)
                                // 加重前一點的權重 (0.85f)，讓粗細過渡更平滑，消除竹節突變
                                prevPt.width * 0.85f + targetW * 0.15f
                            }
                            
                            lastPointTime = time
                            w
                        }

                        drag.historical.forEach { historical ->
                            val hp   = historical.position
                            val prev = currentPathPoints.last()
                            activePath.quadraticTo(prev.x, prev.y, (prev.x + hp.x) / 2f, (prev.y + hp.y) / 2f)
                            val w = calcWidth(hp, historical.uptimeMillis)
                            currentPathPoints.add(StrokePoint(hp.x, hp.y, w))
                            quickSwipeTrace.add(hp)
                        }
                        val newPoint  = drag.position
                        val prevPoint = currentPathPoints.last()
                        activePath.quadraticTo(prevPoint.x, prevPoint.y, (prevPoint.x + newPoint.x) / 2f, (prevPoint.y + newPoint.y) / 2f)
                        val w = calcWidth(newPoint, drag.uptimeMillis)
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
                            val newPath = EnvelopeUtils.generateEnvelopePath(currentPathPoints)
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
                                    viewModel.deleteStrokesIntersecting(points)
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
                    return@awaitEachGesture
                }

                // 手指全程沒動、縮放卻在中途發生過：同樣丟棄，避免提交錯位座標
                if (viewModel.docZoom.value != zoomAtStrokeStart) {
                    activePath.reset()
                    activeEnvelopePath.reset()
                    currentPathPoints.clear()
                    activePathVersion++
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
                            viewModel.deleteStrokesIntersecting(points)
                        } else {
                            // A single-point tap produces no drag points; duplicate it so
                            // saveStroke receives ≥2 points and StrokeCap.Round renders a dot.
                            val pts = if (currentPathPoints.size == 1)
                                listOf(currentPathPoints[0], currentPathPoints[0])
                            else
                                currentPathPoints.toList()
                            TouchEventLogger.logOutcome(
                                sessionId       = sessionId,
                                outcome         = "ACCEPTED",
                                pointCount      = pts.size,
                                maxPressure     = maxPressureDuring,
                                maxSizeWidthPx  = maxSizeWidthDuring,
                                maxSizeHeightPx = maxSizeHeightDuring
                            )
                            viewModel.saveStroke(pts, selectedColor, activeTool, strokeWidth)
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
            val cachedImage = ImageBitmap(bmpWidth, bmpHeight, ImageBitmapConfig.Argb8888)
            val cacheCanvas = androidx.compose.ui.graphics.Canvas(cachedImage)
            
            cacheCanvas.save()
            cacheCanvas.scale(sx, sy)
            strokes.forEach { swp ->
                if (swp.stroke.id in previewIds) return@forEach
                if (swp.stroke.shapeType != null) {
                    drawShapeOnCanvas(cacheCanvas, swp.stroke, swp.points)
                } else {
                    val path = committedPathCache[swp.stroke.id]?.path ?: swp.points.toComposePath()
                    drawPathOnCanvas(cacheCanvas, path,
                        Color(swp.stroke.color), swp.stroke.strokeWidth, swp.stroke.isHighlighter)
                }
            }
            cacheCanvas.restore()

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
                val bmp = loadedImages[ann.uri]
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
                            val dw = if (isSelectedInImageTool) imageResizePreview.x else 0f
                            val dh = if (isSelectedInImageTool) imageResizePreview.y else 0f
                            Rect(
                                left = ann.modelX * sx + dx,
                                top = ann.modelY * sy + dy,
                                right = ann.modelX * sx + dx + (ann.modelWidth * sx + dw).coerceAtLeast(2f),
                                bottom = ann.modelY * sy + dy + (ann.modelHeight * sy + dh).coerceAtLeast(2f)
                            )
                        }
                    }
                    drawImage(
                        image     = bmp.asImageBitmap(),
                        dstOffset = IntOffset(canvasRect.left.toInt(), canvasRect.top.toInt()),
                        dstSize   = IntSize(canvasRect.width.toInt().coerceAtLeast(2), canvasRect.height.toInt().coerceAtLeast(2))
                    )
                }
            }

            // Committed strokes from DB.
            // Play the cached strokes layer!
            drawImage(cachedImage)

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
            if (selectedPathData.isNotEmpty()) {
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
                    val imgSelRect = Rect(
                        r.left  + imageMovePreview.x,
                        r.top   + imageMovePreview.y,
                        r.right  + imageMovePreview.x + imageResizePreview.x,
                        r.bottom + imageMovePreview.y + imageResizePreview.y
                    )
                    // Dashed border
                    drawRect(
                        color   = BrandIndigo,
                        topLeft = Offset(imgSelRect.left, imgSelRect.top),
                        size    = Size(imgSelRect.width, imgSelRect.height),
                        style   = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)))
                    )
                    // Resize handle at bottom-right
                    val hr = imageResizeHandleRect(Offset(imgSelRect.right, imgSelRect.bottom))
                    drawRect(
                        color   = BrandIndigo,
                        topLeft = Offset(hr.left, hr.top),
                        size    = Size(hr.width, hr.height)
                    )
                    // Diagonal arrow inside handle
                    drawLine(
                        color       = Color.White,
                        start       = Offset(hr.left + 5f, hr.bottom - 5f),
                        end         = Offset(hr.right - 5f, hr.top + 5f),
                        strokeWidth = 2f,
                        cap         = StrokeCap.Round
                    )
                }

                // Text annotations — apply move/resize delta for the selected annotation
                drawIntoCanvas { composeCanvas ->
                    textAnnotations.forEach { ann ->
                        val isSelected = ann.id == selectedTextAnnotationId
                        val dx = if (isSelected) textMoveDelta.x else 0f
                        val dy = if (isSelected) textMoveDelta.y else 0f
                        val effectiveFontSize = if (isSelected)
                            (ann.fontSize + textFontSizeDelta).coerceAtLeast(4f) * sy
                        else ann.fontSize * sy
                        val paint = android.graphics.Paint().apply {
                            textSize    = effectiveFontSize
                            color       = ann.colorArgb
                            isAntiAlias = true
                            typeface    = android.graphics.Typeface.DEFAULT_BOLD
                        }
                        // Multiline: modelY is the first-line baseline (matches PDF export).
                        val lineHeight = with(paint.fontMetrics) { -ascent + descent + leading }
                        ann.text.split("\n").forEachIndexed { i, line ->
                            composeCanvas.nativeCanvas.drawText(
                                line, ann.modelX * sx + dx, ann.modelY * sy + dy + i * lineHeight, paint
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
                    // Dashed selection border
                    drawRect(
                        color    = BrandIndigo,
                        topLeft  = Offset(textRect.left, textRect.top),
                        size     = Size(textRect.width, textRect.height),
                        style    = Stroke(
                            width      = 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 5f))
                        )
                    )
                    // Solid resize handle at bottom-right corner
                    val hr = textResizeHandleRect(textRect)
                    drawRect(
                        color   = BrandIndigo,
                        topLeft = Offset(hr.left, hr.top),
                        size    = Size(hr.width, hr.height)
                    )
                    // Diagonal resize arrow indicator inside handle
                    drawLine(
                        color       = Color.White,
                        start       = Offset(hr.left + 4f, hr.bottom - 4f),
                        end         = Offset(hr.right - 4f, hr.top + 4f),
                        strokeWidth = 1.5f,
                        cap         = StrokeCap.Round
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
                        style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
                    )
                }

                // Active freehand / eraser / lasso path
                when (activeTool) {
                    Tool.PEN -> drawPath(activeEnvelopePath, selectedColor) // Uses default Fill style
                    Tool.HIGHLIGHTER -> drawIntoCanvas { cvs ->
                        cvs.drawPath(activeEnvelopePath, Paint().apply {
                            color = selectedColor.copy(alpha = 0.4f)
                            style = PaintingStyle.Fill
                            blendMode = BlendMode.Multiply
                        })
                    }
                    Tool.LASSO  -> drawPath(activePath, Color.DarkGray, style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))))
                    Tool.ERASER -> drawPath(activePath, Color.Gray,     style = Stroke(width = 2f))
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

/**
 * Returns the bounding rect of [ann] in canvas-pixel space.
 * [fontSizeDelta] is an in-flight resize delta in model units (applied during drag).
 */
private fun textAnnotationHitRect(
    ann: TextAnnotationEntity, sx: Float, sy: Float, fontSizeDelta: Float = 0f
): Rect {
    val canvasX         = ann.modelX * sx
    val canvasY         = ann.modelY * sy
    val effectiveFontPx = (ann.fontSize + fontSizeDelta).coerceAtLeast(4f) * sy
    // Multiline bounds: modelY is the first-line baseline; each extra line adds one line-height.
    val lines           = ann.text.split("\n")
    val maxLineLen      = lines.maxOfOrNull { it.length } ?: 0
    // Approximate text width; drawText baseline is at (canvasX, canvasY)
    val textWidth       = maxLineLen * effectiveFontPx * 0.65f + 8f
    val textHeight      = effectiveFontPx + (lines.size - 1) * effectiveFontPx * 1.2f
    return Rect(canvasX - 4f, canvasY - effectiveFontPx - 4f, canvasX + textWidth, canvasY - effectiveFontPx + textHeight + 4f)
}

/** Returns the 22×22 px resize handle rect anchored to the bottom-right of [textRect]. */
private fun textResizeHandleRect(textRect: Rect): Rect {
    val h = 22f
    return Rect(textRect.right - h / 2f, textRect.bottom - h / 2f, textRect.right + h / 2f, textRect.bottom + h / 2f)
}

// ---- Image annotation hit-testing helpers ----

/** Returns the canvas-pixel bounding rect for [ann] (committed DB values, no in-flight delta). */
private fun imageAnnotationRect(ann: ImageAnnotationEntity, sx: Float, sy: Float): Rect =
    Rect(ann.modelX * sx, ann.modelY * sy,
         (ann.modelX + ann.modelWidth) * sx, (ann.modelY + ann.modelHeight) * sy)

/** Returns the 28×28 px resize handle rect centered on [bottomRight]. */
private fun imageResizeHandleRect(bottomRight: Offset): Rect {
    val h = 28f
    return Rect(bottomRight.x - h / 2f, bottomRight.y - h / 2f,
                bottomRight.x + h / 2f, bottomRight.y + h / 2f)
}

private enum class StrokeSelectionHandle {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT
}

private data class SelectedStrokeRenderData(
    val strokeWithPoints: StrokeWithPoints,
    val path: Path?
)

private data class CachedStrokePath(
    val path: Path,
    val pointCount: Int,
    val boundsLeft: Float,
    val boundsTop: Float,
    val boundsRight: Float,
    val boundsBottom: Float
)

private fun StrokeWithPoints.matches(cached: CachedStrokePath?): Boolean {
    if (cached == null) return false
    return cached.pointCount == points.size &&
        cached.boundsLeft == stroke.boundsLeft &&
        cached.boundsTop == stroke.boundsTop &&
        cached.boundsRight == stroke.boundsRight &&
        cached.boundsBottom == stroke.boundsBottom
}

private const val LASSO_FRAME_CORNER_RADIUS_PX = 14f
private const val LASSO_FRAME_OUTER_STROKE_PX = 6f
private const val LASSO_FRAME_INNER_STROKE_PX = 2.2f
private const val LASSO_HANDLE_VISUAL_RADIUS_PX = 8f
private const val LASSO_HANDLE_HIT_RADIUS_PX = 14f
private const val LASSO_HANDLE_HALO_RADIUS_PX = 12f
private val LASSO_DASH_PATTERN = floatArrayOf(12f, 8f)
private val LASSO_FRAME_COLOR = BrandIndigo

private fun applySelectionTransform(
    canvas: androidx.compose.ui.graphics.Canvas,
    translation: Offset,
    scale: Float,
    anchor: Offset
) {
    val clampedScale = StrokeTransformUtils.clampUniformScale(scale)
    canvas.translate(anchor.x, anchor.y)
    canvas.scale(clampedScale, clampedScale)
    canvas.translate(-anchor.x, -anchor.y)
    canvas.translate(translation.x, translation.y)
}

private fun DrawScope.drawLassoSelectionFrame(
    selectionRect: Rect,
    showHandles: Boolean,
    dashPhase: Float,
    animateDash: Boolean
) {
    val topLeft = Offset(selectionRect.left, selectionRect.top)
    val size = Size(selectionRect.width, selectionRect.height)
    val corner = CornerRadius(LASSO_FRAME_CORNER_RADIUS_PX, LASSO_FRAME_CORNER_RADIUS_PX)
    val innerStroke = if (animateDash) {
        Stroke(
            width = LASSO_FRAME_INNER_STROKE_PX,
            pathEffect = PathEffect.dashPathEffect(LASSO_DASH_PATTERN, dashPhase)
        )
    } else {
        Stroke(width = LASSO_FRAME_INNER_STROKE_PX)
    }

    // Selection fill helps users identify the selected region quickly.
    drawRoundRect(
        color = LASSO_FRAME_COLOR.copy(alpha = 0.09f),
        topLeft = topLeft,
        size = size,
        cornerRadius = corner
    )
    drawRoundRect(
        color = LASSO_FRAME_COLOR.copy(alpha = 0.22f),
        topLeft = topLeft,
        size = size,
        cornerRadius = corner,
        style = Stroke(width = LASSO_FRAME_OUTER_STROKE_PX)
    )
    drawRoundRect(
        color = LASSO_FRAME_COLOR,
        topLeft = topLeft,
        size = size,
        cornerRadius = corner,
        style = innerStroke
    )

    if (!showHandles) return
    strokeSelectionHandleRects(selectionRect).forEach { (_, handleRect) ->
        val center = handleRect.center
        drawCircle(
            color = LASSO_FRAME_COLOR.copy(alpha = 0.22f),
            radius = LASSO_HANDLE_HALO_RADIUS_PX,
            center = center
        )
        drawCircle(
            color = Color.White,
            radius = LASSO_HANDLE_VISUAL_RADIUS_PX,
            center = center
        )
        drawCircle(
            color = LASSO_FRAME_COLOR,
            radius = LASSO_HANDLE_VISUAL_RADIUS_PX,
            center = center,
            style = Stroke(width = 2f)
        )
    }
}

private fun strokeSelectionHandleCenter(selectionRect: Rect, handle: StrokeSelectionHandle): Offset = when (handle) {
    StrokeSelectionHandle.TOP_LEFT -> Offset(selectionRect.left, selectionRect.top)
    StrokeSelectionHandle.TOP_RIGHT -> Offset(selectionRect.right, selectionRect.top)
    StrokeSelectionHandle.BOTTOM_LEFT -> Offset(selectionRect.left, selectionRect.bottom)
    StrokeSelectionHandle.BOTTOM_RIGHT -> Offset(selectionRect.right, selectionRect.bottom)
}

private fun strokeSelectionResizeAnchor(selectionRect: Rect, handle: StrokeSelectionHandle): Offset = when (handle) {
    StrokeSelectionHandle.TOP_LEFT -> Offset(selectionRect.right, selectionRect.bottom)
    StrokeSelectionHandle.TOP_RIGHT -> Offset(selectionRect.left, selectionRect.bottom)
    StrokeSelectionHandle.BOTTOM_LEFT -> Offset(selectionRect.right, selectionRect.top)
    StrokeSelectionHandle.BOTTOM_RIGHT -> Offset(selectionRect.left, selectionRect.top)
}

private fun strokeSelectionHandleRects(selectionRect: Rect): List<Pair<StrokeSelectionHandle, Rect>> = listOf(
    StrokeSelectionHandle.TOP_LEFT,
    StrokeSelectionHandle.TOP_RIGHT,
    StrokeSelectionHandle.BOTTOM_LEFT,
    StrokeSelectionHandle.BOTTOM_RIGHT
).map { handle ->
    handle to strokeSelectionHandleHitRect(strokeSelectionHandleCenter(selectionRect, handle))
}

private fun strokeSelectionHandleHitRect(center: Offset): Rect = Rect(
    left = center.x - LASSO_HANDLE_HIT_RADIUS_PX,
    top = center.y - LASSO_HANDLE_HIT_RADIUS_PX,
    right = center.x + LASSO_HANDLE_HIT_RADIUS_PX,
    bottom = center.y + LASSO_HANDLE_HIT_RADIUS_PX
)

private fun modelTransformedPolygonBoundsToCanvasRect(
    polygon: List<Offset>,
    translation: Offset,
    scale: Float,
    anchor: Offset,
    sx: Float,
    sy: Float
): Rect? {
    if (polygon.isEmpty()) return null
    val clampedScale = StrokeTransformUtils.clampUniformScale(scale)
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    polygon.forEach { point ->
        val anchoredX = point.x - anchor.x
        val anchoredY = point.y - anchor.y
        val transformedX = anchor.x + anchoredX * clampedScale + translation.x
        val transformedY = anchor.y + anchoredY * clampedScale + translation.y
        if (transformedX < minX) minX = transformedX
        if (transformedY < minY) minY = transformedY
        if (transformedX > maxX) maxX = transformedX
        if (transformedY > maxY) maxY = transformedY
    }
    if (maxX <= minX || maxY <= minY) return null
    return Rect(minX * sx, minY * sy, maxX * sx, maxY * sy)
}

private fun modelTransformedImageRectToCanvasRect(
    image: ImageAnnotationEntity,
    translation: Offset,
    scale: Float,
    anchor: Offset,
    sx: Float,
    sy: Float
): Rect {
    val clampedScale = StrokeTransformUtils.clampUniformScale(scale)
    val transformedLeft = anchor.x + (image.modelX - anchor.x) * clampedScale + translation.x
    val transformedTop = anchor.y + (image.modelY - anchor.y) * clampedScale + translation.y
    val transformedWidth = (image.modelWidth * clampedScale).coerceAtLeast(1f)
    val transformedHeight = (image.modelHeight * clampedScale).coerceAtLeast(1f)
    return Rect(
        left = transformedLeft * sx,
        top = transformedTop * sy,
        right = (transformedLeft + transformedWidth) * sx,
        bottom = (transformedTop + transformedHeight) * sy
    )
}

// ---- Shape rendering helpers ----

private fun drawShapeOnCanvas(
    canvas: androidx.compose.ui.graphics.Canvas,
    stroke: StrokeEntity,
    points: List<PointEntity>,
    tintColor: Color? = null
) {
    val color = tintColor ?: Color(stroke.color)
    val paint = Paint().apply {
        this.color   = color
        style        = PaintingStyle.Stroke
        strokeWidth  = stroke.strokeWidth
        strokeCap    = StrokeCap.Round
        strokeJoin   = StrokeJoin.Round
    }
    val r = Rect(stroke.boundsLeft, stroke.boundsTop, stroke.boundsRight, stroke.boundsBottom)
    when (stroke.shapeType) {
        "RECT"   -> canvas.drawRect(r, paint)
        "CIRCLE" -> canvas.drawOval(r, paint)
        "LINE"   -> if (points.size >= 2) {
            canvas.drawLine(Offset(points.first().x, points.first().y), Offset(points.last().x, points.last().y), paint)
        }
        "ARROW"  -> if (points.size >= 2) {
            val p0 = Offset(points.first().x, points.first().y)
            val p1 = Offset(points.last().x,  points.last().y)
            canvas.drawLine(p0, p1, paint)
            drawArrowHeadOnCanvas(canvas, p0, p1, paint, stroke.strokeWidth)
        }
    }
}

private fun drawArrowHeadOnCanvas(
    canvas: androidx.compose.ui.graphics.Canvas,
    start: Offset, end: Offset, paint: Paint, sw: Float
) {
    val headSize   = sw * 5f + 10f
    val angle      = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
    val leftAngle  = angle + Math.PI * 0.75
    val rightAngle = angle - Math.PI * 0.75
    val lp = Offset(end.x + (headSize * cos(leftAngle)).toFloat(),  end.y + (headSize * sin(leftAngle)).toFloat())
    val rp = Offset(end.x + (headSize * cos(rightAngle)).toFloat(), end.y + (headSize * sin(rightAngle)).toFloat())
    canvas.drawLine(end, lp, paint)
    canvas.drawLine(end, rp, paint)
}

private fun DrawScope.drawArrowHeadInScope(start: Offset, end: Offset, color: Color, sw: Float) {
    val headSize   = sw * 5f + 10f
    val angle      = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
    val leftAngle  = angle + Math.PI * 0.75
    val rightAngle = angle - Math.PI * 0.75
    val lp = Offset(end.x + (headSize * cos(leftAngle)).toFloat(),  end.y + (headSize * sin(leftAngle)).toFloat())
    val rp = Offset(end.x + (headSize * cos(rightAngle)).toFloat(), end.y + (headSize * sin(rightAngle)).toFloat())
    drawLine(color, end, lp, strokeWidth = sw, cap = StrokeCap.Round)
    drawLine(color, end, rp, strokeWidth = sw, cap = StrokeCap.Round)
}

// ---- Freehand path helpers ----

private fun List<PointEntity>.toComposePath(): Path {
    val strokePoints = this.map { StrokePoint(it.x, it.y, it.width) }
    return EnvelopeUtils.generateEnvelopePath(strokePoints)
}

private fun drawPathOnCanvas(
    canvas: androidx.compose.ui.graphics.Canvas,
    path: Path, color: Color, strokeWidth: Float, isHighlighter: Boolean = false
) {
    canvas.drawPath(path, Paint().apply {
        this.color       = if (isHighlighter) color.copy(alpha = 0.4f) else color
        this.style       = PaintingStyle.Fill
        if (isHighlighter) this.blendMode = BlendMode.Multiply
    })
}

