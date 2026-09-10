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
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.BackHand
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
fun TabletEditorTopBar(
    documentTitle: String,
    onBack: () -> Unit,
    viewModel: EditorViewModel,
    showStrokeWidthSlider: Boolean = false,
    onToggleStrokeWidthSlider: () -> Unit = {},
    onHideStrokeWidthSlider: () -> Unit = {},
    onExport: () -> Unit = {},
    onDocumentSettings: () -> Unit = {},
    onToggleAiPanel: () -> Unit = {},
    hazeState: dev.chrisbanes.haze.HazeState,
    isDarkTheme: Boolean
) {
    val activeTool by viewModel.selectedTool.collectAsState()
    val selectedColor by viewModel.selectedColor.collectAsState()
    val recentColors by viewModel.recentColors.collectAsState()
    val selectedShapeSubType by viewModel.selectedShapeSubType.collectAsState()
    val selectedLassoSubType by viewModel.selectedLassoSubType.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()
    val inputMode by viewModel.inputMode.collectAsState()
    val strokeWidth by viewModel.strokeWidth.collectAsState()
    val toolColors = listOf(Color(0xFF111827), Color(0xFFFACC15), Color(0xFFF87171), Color(0xFF4ADE80))
    val shownRecentColors = recentColors.filterNot { it in toolColors }.take(8)
    var showColorPicker by remember { mutableStateOf(false) }
    val isDarkSurface = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val shellColor = MaterialTheme.colorScheme.primary.copy(alpha = if (isDarkSurface) 0.10f else 0.08f) // ????,???
    val clusterColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (isDarkSurface) 0.78f else 0.94f)
    val colorSelectorBg = MaterialTheme.colorScheme.primary.copy(alpha = if (isDarkSurface) 0.16f else 0.12f) // ??????,???????
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f)
    val toolButtonSize = 32.dp
    val utilityButtonSize = 34.dp

    val drawingTools = listOf(Tool.PEN, Tool.HIGHLIGHTER, Tool.ERASER, Tool.LASSO)
    val drawingActiveIdx = drawingTools.indexOf(activeTool).let { if (it < 0) -1 else it }
    val drawingHighlightOffset by animateDpAsState(
        targetValue = if (drawingActiveIdx >= 0) toolButtonSize * drawingActiveIdx else 0.dp,
        animationSpec = tween(durationMillis = 200),
        label = "DrawingToolHighlight"
    )

    val annotationTools = listOf(Tool.SHAPE, Tool.TEXT, Tool.IMAGE, Tool.STAMP)
    val annotationActiveIdx = annotationTools.indexOf(activeTool).let { if (it < 0) -1 else it }
    val annotationHighlightOffset by animateDpAsState(
        targetValue = if (annotationActiveIdx >= 0) toolButtonSize * annotationActiveIdx else 0.dp,
        animationSpec = tween(durationMillis = 200),
        label = "AnnotationToolHighlight"
    )

    val undoScale by animateFloatAsState(
        targetValue = if (canUndo) 1f else 0.84f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "UndoScale"
    )
    val redoScale by animateFloatAsState(
        targetValue = if (canRedo) 1f else 0.84f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "RedoScale"
    )
    if (showColorPicker) {
        ColorPickerDialog(
            onColorSelected = { viewModel.onColorSelected(it) },
            onDismiss = { showColorPicker = false }
        )
    }

    // 外層全透明：玻璃改到每顆群組藥丸上，整條才不會糊成一塊灰板
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .glassPanel(hazeState, isDarkTheme, ShapeLg),
                shape = ShapeLg,
                color = Color.Transparent
            ) {
                Row(
                    modifier = Modifier.fillMaxHeight().padding(start = 6.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(utilityButtonSize)) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back to Library")
                    }
                    Column(modifier = Modifier.widthIn(max = 156.dp)) {
                        Text(
                            text = documentTitle,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "InkFlow Studio",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .glassPanel(hazeState, isDarkTheme, ShapeLg),
                shape = ShapeLg,
                color = Color.Transparent
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            
                            .padding(horizontal = 3.dp, vertical = 3.dp)
                    ) {
                        if (drawingActiveIdx >= 0) {
                            Box(
                                modifier = Modifier
                                    .offset(x = drawingHighlightOffset)
                                    .size(toolButtonSize)
                                    .background(
                                        Brush.linearGradient(
                                            listOf(
                                                MaterialTheme.colorScheme.primaryContainer,
                                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.82f)
                                            )
                                        ),
                                        ShapeMd
                                    )
                            )
                        }
                        Row {
                            EditorIconButton(
                                onClick = {
                                    if (activeTool == Tool.PEN) onToggleStrokeWidthSlider()
                                    else {
                                        viewModel.onToolSelected(Tool.PEN)
                                        onHideStrokeWidthSlider()
                                    }
                                },
                                isActive = activeTool == Tool.PEN,
                                icon = Icons.Outlined.Create,
                                contentDescription = "Pen Tool"
                            )
                            EditorIconButton(
                                onClick = {
                                    if (activeTool == Tool.HIGHLIGHTER) onToggleStrokeWidthSlider()
                                    else {
                                        viewModel.onToolSelected(Tool.HIGHLIGHTER)
                                        onHideStrokeWidthSlider()
                                    }
                                },
                                isActive = activeTool == Tool.HIGHLIGHTER,
                                icon = Icons.Outlined.Brush,
                                contentDescription = "Highlighter Tool"
                            )
                            EditorIconButton(
                                onClick = {
                                    viewModel.onToolSelected(Tool.ERASER)
                                    onHideStrokeWidthSlider()
                                },
                                isActive = activeTool == Tool.ERASER,
                                icon = Icons.Outlined.DeleteOutline,
                                contentDescription = "Eraser Tool"
                            )
                            EditorIconButton(
                                onClick = {
                                    viewModel.onToolSelected(Tool.LASSO)
                                    onHideStrokeWidthSlider()
                                },
                                isActive = activeTool == Tool.LASSO,
                                icon = Icons.Outlined.Gesture,
                                contentDescription = "Lasso Select Tool"
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = activeTool == Tool.LASSO,
                        enter = fadeIn(tween(200)) + androidx.compose.animation.expandHorizontally(tween(250, easing = androidx.compose.animation.core.FastOutSlowInEasing)),
                        exit = fadeOut(tween(180)) + androidx.compose.animation.shrinkHorizontally(tween(200))
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(start = 8.dp)
                                
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LassoSubType.entries.forEach { subType ->
                                val isSelected = selectedLassoSubType == subType
                                TextButton(
                                    onClick = { viewModel.onLassoSubTypeSelected(subType) },
                                    modifier = Modifier.height(32.dp),
                                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                ) {
                                    Text(
                                        text = when (subType) {
                                            LassoSubType.FREEFORM -> "????"
                                            LassoSubType.RECT -> "????"
                                        },
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }

                    VerticalDivider(Modifier.height(24.dp).padding(horizontal = 8.dp))

                    Box(
                        modifier = Modifier
                            
                            .padding(horizontal = 3.dp, vertical = 3.dp)
                    ) {
                        if (annotationActiveIdx >= 0) {
                            Box(
                                modifier = Modifier
                                    .offset(x = annotationHighlightOffset)
                                    .size(toolButtonSize)
                                    .background(
                                        Brush.linearGradient(
                                            listOf(
                                                MaterialTheme.colorScheme.primaryContainer,
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.78f)
                                            )
                                        ),
                                        ShapeMd
                                    )
                            )
                        }
                        Row {
                            EditorIconButton(
                                onClick = {
                                    viewModel.onToolSelected(Tool.SHAPE)
                                    onHideStrokeWidthSlider()
                                },
                                isActive = activeTool == Tool.SHAPE,
                                icon = Icons.Outlined.CropSquare,
                                contentDescription = "Shape Tool"
                            )
                            EditorIconButton(
                                onClick = {
                                    viewModel.onToolSelected(Tool.TEXT)
                                    onHideStrokeWidthSlider()
                                },
                                isActive = activeTool == Tool.TEXT,
                                icon = Icons.Outlined.Title,
                                contentDescription = "Text Tool"
                            )
                            EditorIconButton(
                                onClick = {
                                    viewModel.onToolSelected(Tool.IMAGE)
                                    onHideStrokeWidthSlider()
                                },
                                isActive = activeTool == Tool.IMAGE,
                                icon = Icons.Outlined.Image,
                                contentDescription = "Image Tool"
                            )
                            EditorIconButton(
                                onClick = {
                                    viewModel.onToolSelected(Tool.STAMP)
                                    onHideStrokeWidthSlider()
                                },
                                isActive = activeTool == Tool.STAMP,
                                icon = Icons.Outlined.Star,
                                contentDescription = "Stamp Tool"
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = activeTool == Tool.SHAPE,
                        enter = fadeIn(tween(200)) + androidx.compose.animation.expandHorizontally(tween(250, easing = androidx.compose.animation.core.FastOutSlowInEasing)),
                        exit = fadeOut(tween(180)) + androidx.compose.animation.shrinkHorizontally(tween(200))
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(start = 8.dp)
                                
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ShapeSubType.entries.forEach { subType ->
                                val isSelected = selectedShapeSubType == subType
                                TextButton(
                                    onClick = { viewModel.onShapeSubTypeSelected(subType) },
                                    modifier = Modifier.height(32.dp),
                                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                ) {
                                    Text(
                                        text = when (subType) {
                                            ShapeSubType.RECT -> "??"
                                            ShapeSubType.CIRCLE -> "??"
                                            ShapeSubType.LINE -> "??"
                                            ShapeSubType.ARROW -> "??"
                                        },
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }

                    VerticalDivider(Modifier.height(24.dp).padding(horizontal = 8.dp))

                    Row(
                        modifier = Modifier
                            .background(colorSelectorBg, CircleShape)
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        shownRecentColors.forEach { color ->
                            ColorChip(color = color, isSelected = selectedColor == color) {
                                viewModel.onColorSelected(color)
                            }
                        }
                        if (shownRecentColors.isNotEmpty()) {
                            Spacer(Modifier.width(4.dp))
                        }
                        toolColors.forEach { color ->
                            ColorChip(color = color, isSelected = selectedColor == color) {
                                viewModel.onColorSelected(color)
                            }
                        }
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .padding(3.dp)
                                .clip(CircleShape)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), CircleShape)
                                .background(
                                    Brush.sweepGradient(
                                        listOf(
                                            Color.Red,
                                            Color.Yellow,
                                            Color.Green,
                                            Color.Cyan,
                                            Color.Blue,
                                            Color.Magenta,
                                            Color.Red
                                        )
                                    )
                                )
                                .clickable { showColorPicker = true }
                        )
                    }

                    AnimatedVisibility(
                        visible = showStrokeWidthSlider && (activeTool == Tool.PEN || activeTool == Tool.HIGHLIGHTER),
                        enter = fadeIn(tween(180)) + androidx.compose.animation.expandHorizontally(tween(220)),
                        exit = fadeOut(tween(150)) + androidx.compose.animation.shrinkHorizontally(tween(180))
                    ) {
                        Surface(
                            modifier = Modifier.padding(start = 8.dp),
                            shape = ShapeMd,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ) {
                            Text(
                                text = "?? ${strokeWidth.toInt()} px",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .glassPanel(hazeState, isDarkTheme, ShapeLg),
                shape = ShapeLg,
                color = Color.Transparent
            ) {
                Row(
                    modifier = Modifier.fillMaxHeight().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewModel.undo() },
                        enabled = canUndo,
                        modifier = Modifier
                            .size(utilityButtonSize)
                            .graphicsLayer { scaleX = undoScale; scaleY = undoScale }
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "Undo")
                    }
                    IconButton(
                        onClick = { viewModel.redo() },
                        enabled = canRedo,
                        modifier = Modifier
                            .size(utilityButtonSize)
                            .graphicsLayer { scaleX = redoScale; scaleY = redoScale }
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = "Redo")
                    }
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        IconButton(onClick = onExport, modifier = Modifier.size(utilityButtonSize)) {
                            Icon(
                                Icons.Outlined.FileUpload,
                                contentDescription = "Export PDF",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    IconButton(onClick = onToggleAiPanel, modifier = Modifier.size(utilityButtonSize)) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_gemini),
                            contentDescription = "Toggle AI Panel",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = onDocumentSettings, modifier = Modifier.size(utilityButtonSize)) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Article,
                            contentDescription = "Document Settings",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { viewModel.cycleInputMode() }, modifier = Modifier.size(utilityButtonSize)) {
                        Icon(
                            imageVector = when (inputMode) {
                                InputMode.FREE -> Icons.Outlined.TouchApp
                                InputMode.PALM_REJECTION -> Icons.Outlined.BackHand
                                InputMode.STYLUS_ONLY -> Icons.Outlined.Create
                            },
                            contentDescription = when (inputMode) {
                                InputMode.FREE -> "手指模式（單指書寫）"
                                InputMode.PALM_REJECTION -> "觸控筆模式"
                                InputMode.STYLUS_ONLY -> "觸控筆模式（手指卷動）"
                            },
                            tint = when (inputMode) {
                                InputMode.FREE -> MaterialTheme.colorScheme.onSurface
                                InputMode.PALM_REJECTION -> MaterialTheme.colorScheme.tertiary
                                InputMode.STYLUS_ONLY -> MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                }
            }
        }
    }
}
