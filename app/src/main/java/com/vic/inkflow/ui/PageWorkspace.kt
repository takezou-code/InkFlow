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
    onAiFileReady: (android.net.Uri) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scale by rememberSaveable { mutableFloatStateOf(1f) }
    var offsetX by rememberSaveable { mutableFloatStateOf(0f) }
    var offsetY by rememberSaveable { mutableFloatStateOf(0f) }
    val isDarkSurface = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bubbleHazeState = rememberHazeState()
    val primaryColor = MaterialTheme.colorScheme.primary
    val deskBrush = remember(isDarkSurface) {
        Brush.linearGradient(
            colors = if (isDarkSurface) {
                listOf(WorkspaceDeskDark, WorkspaceDeskDark)
            } else {
                listOf(WorkspaceDeskLight, WorkspaceDeskLight)
            }
        )
    }
    val paperColor = MaterialTheme.colorScheme.surface
    val stageColor = if (isDarkSurface) {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.30f)
    } else {
        WorkspaceDeskLight.copy(alpha = 0.76f)
    }
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

    // Track container dimensions (pixels) to compute the fit-to-page minimum scale
    var containerWidth by remember { mutableIntStateOf(0) }
    var containerHeight by remember { mutableIntStateOf(0) }
    var paperWidthPx by remember { mutableIntStateOf(0) }
    var paperHeightPx by remember { mutableIntStateOf(0) }
    var bubbleWidthPx by remember { mutableIntStateOf(0) }
    var bubbleHeightPx by remember { mutableIntStateOf(0) }
    val minScale = remember(containerWidth, containerHeight, pageAspectRatio) {
        if (containerWidth <= 0 || containerHeight <= 0) return@remember 1f
        val cW = containerWidth.toFloat()
        val cH = containerHeight.toFloat()
        // Compute page dimensions (height-constrained for portrait, width-constrained for landscape)
        val pageH = cH * 0.9f
        val pageW = pageH * pageAspectRatio
        // Minimum scale = page occupies 85% of viewport (full page visible with comfortable margins)
        minOf(cW * 0.85f / pageW, cH * 0.85f / pageH)
    }

    // When container first appears (or resizes), clamp saved scale to the new minimum
    LaunchedEffect(minScale) {
        if (scale < minScale) scale = minScale
    }

    val density = LocalDensity.current
    val bubbleGapPx = with(density) { 12.dp.toPx() }
    val bubbleSidePaddingPx = with(density) { 12.dp.toPx() }
    val bubbleTopSafePx = with(density) { 12.dp.toPx() }
    val hasSelectionState = rememberUpdatedState(hasSelection)
    val activeToolState = rememberUpdatedState(activeTool)

    val regionBoundsModel = remember(activeRegionPolygon) { polygonBounds(activeRegionPolygon) }
    val showSelectionBubble = activeTool == Tool.LASSO && hasSelection && !isExtracting

    val bubbleTargetOffset = remember(
        regionBoundsModel,
        paperWidthPx,
        paperHeightPx,
        containerWidth,
        containerHeight,
        scale,
        offsetX,
        offsetY,
        bubbleWidthPx,
        bubbleHeightPx,
        bubbleGapPx,
        bubbleSidePaddingPx,
        bubbleTopSafePx
    ) {
        if (regionBoundsModel == null || paperWidthPx <= 0 || paperHeightPx <= 0 || containerWidth <= 0 || containerHeight <= 0) {
            IntOffset(0, 0)
        } else {
            val paperLeft = (containerWidth - paperWidthPx) / 2f
            val paperTop = (containerHeight - paperHeightPx) / 2f
            val paperScaleX = paperWidthPx / viewModel.modelWidth
            val paperScaleY = paperHeightPx / viewModel.modelHeight

            val boundsBase = Rect(
                left = paperLeft + regionBoundsModel.left * paperScaleX,
                top = paperTop + regionBoundsModel.top * paperScaleY,
                right = paperLeft + regionBoundsModel.right * paperScaleX,
                bottom = paperTop + regionBoundsModel.bottom * paperScaleY
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

            val transformedTopLeft = transformPoint(boundsBase.topLeft)
            val transformedBottomRight = transformPoint(boundsBase.bottomRight)
            val transformedBounds = Rect(
                left = minOf(transformedTopLeft.x, transformedBottomRight.x),
                top = minOf(transformedTopLeft.y, transformedBottomRight.y),
                right = maxOf(transformedTopLeft.x, transformedBottomRight.x),
                bottom = maxOf(transformedTopLeft.y, transformedBottomRight.y)
            )

            val anchorX = transformedBounds.center.x
            val bubbleW = bubbleWidthPx.toFloat()
            val bubbleH = bubbleHeightPx.toFloat()
            val aboveY = transformedBounds.top - bubbleH - bubbleGapPx
            val bubbleY = if (aboveY < bubbleTopSafePx) {
                transformedBounds.bottom + bubbleGapPx
            } else {
                aboveY
            }

            val maxX = maxOf(bubbleSidePaddingPx, containerWidth.toFloat() - bubbleW - bubbleSidePaddingPx)
            val maxY = maxOf(bubbleTopSafePx, containerHeight.toFloat() - bubbleH - bubbleTopSafePx)

            val clampedX = (anchorX - bubbleW / 2f).coerceIn(bubbleSidePaddingPx, maxX)
            val clampedY = bubbleY.coerceIn(bubbleTopSafePx, maxY)
            IntOffset(clampedX.roundToInt(), clampedY.roundToInt())
        }
    }
    // pdfViewModel and LaunchedEffect(uri) are owned by TabletEditorScreen
    val pageCount by pdfViewModel.pageCount.collectAsState()
    val pageBitmapFlow = androidx.compose.runtime.remember(pageIndex, pageCount) {
        if (pageCount > 0) pdfViewModel.getPageBitmap(pageIndex)
        else kotlinx.coroutines.flow.MutableStateFlow(null)
    }
    val pageBitmap by pageBitmapFlow.collectAsState()

    Box(
        modifier = modifier
            .clip(RectangleShape)
            .background(deskBrush)
            .onSizeChanged { size ->
                containerWidth = size.width
                containerHeight = size.height
            }
            .pointerInput(minScale) {
                // Pan / zoom handler for the Workspace background Box.
                //
                // Routing contract (agreed with InkCanvas.pointerInput):
                //   FREE          — InkCanvas does NOT consume Touch events → all single-finger
                //                   touch contacts bubble here for pan.  Stylus is consumed by
                //                   InkCanvas for drawing.
                //   PALM_REJECTION — InkCanvas does NOT consume finger-zone contacts → single
                //                   finger pans here.  Stylus consumed for drawing.  Palm dropped.
                //   STYLUS_ONLY   — InkCanvas does NOT consume any Touch (finger/palm) → all
                //                   touch contacts bubble here for single-finger pan.
                //
                // For 2+ simultaneous pointers this handler handles pinch-zoom + two-finger pan
                // regardless of mode (InkCanvas always passes multi-touch through in FREE /
                // STYLUS_ONLY, and passes finger-only multi-touch through in PALM_REJECTION).
                awaitEachGesture {
                    // requireUnconsumed = false: picks up events not consumed by children (InkCanvas).
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    if (firstDown.isConsumed) return@awaitEachGesture  // InkCanvas claimed it (stylus draw)

                    if (activeToolState.value == Tool.LASSO && hasSelectionState.value) {
                        val paperRect = transformedPaperRect(
                            containerWidth = containerWidth,
                            containerHeight = containerHeight,
                            paperWidthPx = paperWidthPx,
                            paperHeightPx = paperHeightPx,
                            scale = scale,
                            offsetX = offsetX,
                            offsetY = offsetY
                        )
                        if (paperRect == null || !paperRect.contains(firstDown.position)) {
                            viewModel.clearSelection()
                        }
                    }

                    val touchSlop = viewConfiguration.touchSlop
                    var accZoom = 1f
                    var accPan = androidx.compose.ui.geometry.Offset.Zero
                    var pastTouchSlop = false

                    while (true) {
                        val evt = awaitPointerEvent()
                        // If any change was consumed by a child (shouldn't happen after firstDown
                        // check, but be safe) stop handling.
                        if (evt.changes.any { it.isConsumed }) break
                        if (evt.changes.none { it.pressed }) break

                        val zoomChange = evt.calculateZoom()
                        val panChange  = evt.calculatePan()

                        if (!pastTouchSlop) {
                            accZoom *= zoomChange
                            accPan  += panChange
                            val centroidSize = evt.calculateCentroidSize(useCurrent = false)
                            val zoomMotion = kotlin.math.abs(1 - accZoom) * centroidSize
                            val panMotion  = accPan.getDistance()
                            if (zoomMotion > touchSlop || panMotion > touchSlop) {
                                pastTouchSlop = true
                                accZoom = 1f
                                accPan  = androidx.compose.ui.geometry.Offset.Zero
                            }
                        }

                        // If InkCanvas claimed the gesture on this frame, bail out before
                        // applying any offset change from the same motion sample.
                        if (evt.changes.any { it.isConsumed }) break

                        if (pastTouchSlop) {
                            val centroid = evt.calculateCentroid(useCurrent = false)
                            val oldScale = scale
                            // Single-finger pan: zoom = 1 so scale is unchanged.
                            // Multi-finger pinch: zoom != 1 so scale changes.
                            val newScale = (scale * zoomChange).coerceIn(minScale, 5f)
                            if (newScale <= minScale) {
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                val r = newScale / oldScale
                                offsetX = offsetX * r + (centroid.x - containerWidth / 2f) * (1 - r) + panChange.x
                                offsetY = offsetY * r + (centroid.y - containerHeight / 2f) * (1 - r) + panChange.y
                            }
                            scale = newScale
                            evt.changes.forEach { it.consume() }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 18.dp)
                .clip(ShapeXl)
                .background(stageColor)
        )
        Surface(
            modifier = Modifier
                .fillMaxSize(0.86f)
                .aspectRatio(pageAspectRatio, matchHeightConstraintsFirst = true)
                .hazeSource(bubbleHazeState)
                .onSizeChanged {
                    paperWidthPx = it.width
                    paperHeightPx = it.height
                }
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY
                ),
            shape = ShapeSm,
            shadowElevation = 18.dp,
            color = paperColor
        ) {
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
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = showSelectionBubble,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset { bubbleTargetOffset },
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
                    .glassPanel(bubbleHazeState, isDarkSurface, shape = CircleShape, specular = false)
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
                            Icons.Default.Close,
                            contentDescription = "取消選取",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
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
