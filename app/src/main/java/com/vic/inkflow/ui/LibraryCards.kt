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
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Description
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


import com.vic.inkflow.ui.theme.BrandAmber
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FolderGroupedDocumentsView(
    folderTree: List<FolderNode>,
    uncategorizedDocs: List<DocumentEntity>,
    availableFolders: List<FolderEntity>,
    docViewModel: DocumentViewModel,
    context: android.content.Context,
    onOpenDocument: (String) -> Unit,
    onDelete: (String) -> Unit,
    onFavoriteToggle: (String, Boolean) -> Unit,
    onRename: (String, String) -> Unit,
    onMoveToFolder: (String, String?) -> Unit,
    onCreateFolder: (String, String?) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onMoveFolder: (String, Boolean) -> Unit,
    onMoveFolderToParent: (String, String?) -> Unit,
    hazeState: dev.chrisbanes.haze.HazeState? = null,
    dialogHaze: dev.chrisbanes.haze.HazeState,
    isDarkTheme: Boolean = false
) {
val collapsedSections = remember { mutableStateMapOf<String, Boolean>() }
    fun isCollapsed(sectionId: String): Boolean = collapsedSections[sectionId] == true

    // 單層：不再展開 children，level 恆為 0
    val flatFolders = remember(folderTree) {
        folderTree.map { it to 0 }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 6.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (uncategorizedDocs.isNotEmpty()) {
            item(key = "uncategorized") {
                var isDropTargetActive by remember { mutableStateOf(false) }
                Surface(
                    modifier = Modifier.dragAndDropTarget(
                        shouldStartDragAndDrop = { event ->
                            extractDraggedDocumentUri(event) != null ||
                                event.toAndroidDragEvent().clipDescription
                                    ?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true
                        },
                        target = object : DragAndDropTarget {
                            override fun onEntered(event: DragAndDropEvent) { isDropTargetActive = true }
                            override fun onExited(event: DragAndDropEvent) { isDropTargetActive = false }
                            override fun onEnded(event: DragAndDropEvent) { isDropTargetActive = false }
                            override fun onDrop(event: DragAndDropEvent): Boolean {
                                val docUri = extractDraggedDocumentUri(event) ?: return false
                                onMoveToFolder(docUri, null)
                                isDropTargetActive = false
                                return true
                            }
                        }
                    ).then(if (hazeState != null) Modifier.glassPanel(hazeState, isDarkTheme, ShapeMd) else Modifier.fauxGlassPanel(isDarkTheme, ShapeMd)),
                    shape = ShapeMd,
                    color = if (isDropTargetActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    border = if (isDropTargetActive) BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                    ) else null
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                IconButton(onClick = {
                                    val key = "uncategorized"
                                    collapsedSections[key] = !isCollapsed(key)
                                }) {
                                    Icon(
                                        imageVector = Icons.Outlined.ChevronRight,
                                        contentDescription = "展開或收合",
                                        modifier = Modifier.graphicsLayer { rotationZ = if (isCollapsed("uncategorized")) 0f else 90f }
                                    )
                                }
                                Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text("未分類", style = MaterialTheme.typography.titleMedium)
                            }
                            Text(
                                text = "${uncategorizedDocs.size} 份",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        AnimatedVisibility(visible = !isCollapsed("uncategorized")) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                uncategorizedDocs.forEach { doc ->
                                    val coverBitmap by docViewModel.getDocumentThumbnail(context, doc.uri).collectAsState()
                                    DocumentListRow(
                                        document = doc,
                                        coverBitmap = coverBitmap,
                                        availableFolders = availableFolders,
                                        onClick = { onOpenDocument(doc.uri) },
                                        onDelete = { onDelete(doc.uri) },
                                        onFavoriteToggle = { isFav -> onFavoriteToggle(doc.uri, isFav) },
                                        onRename = { newName -> onRename(doc.uri, newName) },
                                        onMoveToFolder = { folderId -> onMoveToFolder(doc.uri, folderId) },
                                        onCreateFolder = { folderName -> onCreateFolder(folderName, null) },
                                        // 實底：省即時模糊（對話框照吃第二路真模糊）
                                        hazeState = null,
                                        dialogHaze = dialogHaze,
                                        isDarkTheme = isDarkTheme
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        items(flatFolders.size, key = { flatFolders[it].first.folder.id }) { sectionIndex ->
            val (node, level) = flatFolders[sectionIndex]
            val folder = node.folder
            val docs = node.documents
            var showFolderMenu by remember(folder.id) { mutableStateOf(false) }
            var showRenameFolderDialog by remember(folder.id) { mutableStateOf(false) }
            var showDeleteFolderDialog by remember(folder.id) { mutableStateOf(false) }
            var showNewChildFolderDialog by remember(folder.id) { mutableStateOf(false) }
            var showMoveFolderDialog by remember(folder.id) { mutableStateOf(false) }
            var newChildFolderName by remember(folder.id) { mutableStateOf("") }
            var renameFolderInput by remember(folder.id, folder.name) { mutableStateOf(folder.name) }
            var folderDragAccumulator by remember(folder.id) { mutableFloatStateOf(0f) }
            var isFolderDropTargetActive by remember(folder.id) { mutableStateOf(false) }

            val descendantFolderIds = remember { emptySet<String>() }
            val movableParentCandidates = remember { emptyList<FolderEntity>() }

                        AnimatedDialog(visible = showNewChildFolderDialog) {
                GlassDialogCustom(
                    onDismissRequest = { showNewChildFolderDialog = false },
                    dialogHaze = dialogHaze,
                    isDark = isDarkTheme,
                    title = { Text("建立子資料夾") },
                    text = {
                        androidx.compose.material3.OutlinedTextField(
                            value = newChildFolderName,
                            onValueChange = { newChildFolderName = it },
                            singleLine = true,
                            label = { Text("資料夾名稱") },
                            colors = glassFieldColors(isDarkTheme),
                            shape = ShapeLg,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    buttons = {
                        GlassTextButton(
                            text = "取消",
                            onClick = { showNewChildFolderDialog = false },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        GlassTextButton(
                            text = "建立",
                            onClick = {
                                onCreateFolder(newChildFolderName, folder.id)
                                showNewChildFolderDialog = false
                            },
                            enabled = newChildFolderName.trim().isNotEmpty()
                        )
                    }
                )
            }

            AnimatedDialog(visible = showRenameFolderDialog) {
                GlassDialogCustom(
                    onDismissRequest = { showRenameFolderDialog = false },
                    dialogHaze = dialogHaze,
                    isDark = isDarkTheme,
                    title = { Text("重新命名資料夾") },
                    text = {
                        androidx.compose.material3.OutlinedTextField(
                            value = renameFolderInput,
                            onValueChange = { renameFolderInput = it },
                            singleLine = true,
                            label = { Text("資料夾名稱") },
                            colors = glassFieldColors(isDarkTheme),
                            shape = ShapeLg,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    buttons = {
                        GlassTextButton(
                            text = "取消",
                            onClick = { showRenameFolderDialog = false },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        GlassTextButton(
                            text = "確認",
                            onClick = {
                                onRenameFolder(folder.id, renameFolderInput)
                                showRenameFolderDialog = false
                            },
                            enabled = renameFolderInput.trim().isNotEmpty()
                        )
                    }
                )
            }

            AnimatedDialog(visible = showDeleteFolderDialog) {
                GlassDialog(
                    onDismissRequest = { showDeleteFolderDialog = false },
                    dialogHaze = dialogHaze,
                    isDark = isDarkTheme,
                    title = { Text("刪除資料夾") },
                    text = { Text("確定要刪除「${folder.name}」嗎？子資料夾會一併刪除，內含文件會保留並移到未分類。") },
                    confirmText = "刪除",
                    onConfirm = {
                        showDeleteFolderDialog = false
                        onDeleteFolder(folder.id)
                    },
                    confirmColor = MaterialTheme.colorScheme.error
                )
            }

            AnimatedDialog(visible = showMoveFolderDialog) {
                GlassDialogCustom(
                    onDismissRequest = { showMoveFolderDialog = false },
                    dialogHaze = dialogHaze,
                    isDark = isDarkTheme,
                    title = { Text("移動資料夾") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            GlassTextButton(
                                text = "移到最上層",
                                onClick = {
                                    onMoveFolderToParent(folder.id, null)
                                    showMoveFolderDialog = false
                                },
                                enabled = folder.parentFolderId != null,
                                alignStart = true
                            )

                            if (movableParentCandidates.isEmpty()) {
                                Text(
                                    text = "沒有可移動的目標資料夾",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            } else {
                                movableParentCandidates.forEach { candidate ->
                                    GlassTextButton(
                                        text = "移到「${candidate.name}」",
                                        onClick = {
                                            onMoveFolderToParent(folder.id, candidate.id)
                                            showMoveFolderDialog = false
                                        },
                                        alignStart = true
                                    )
                                }
                            }
                        }
                    },
                    buttons = {
                        GlassTextButton(
                            text = "關閉",
                            onClick = { showMoveFolderDialog = false },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }

            Surface(
                modifier = Modifier
                    .dragAndDropTarget(
                        shouldStartDragAndDrop = { event ->
                            extractDraggedDocumentUri(event) != null ||
                                event.toAndroidDragEvent().clipDescription
                                    ?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true
                        },
                        target = object : DragAndDropTarget {
                            override fun onEntered(event: DragAndDropEvent) { isFolderDropTargetActive = true }
                            override fun onExited(event: DragAndDropEvent) { isFolderDropTargetActive = false }
                            override fun onEnded(event: DragAndDropEvent) { isFolderDropTargetActive = false }
                            override fun onDrop(event: DragAndDropEvent): Boolean {
                                val docUri = extractDraggedDocumentUri(event) ?: return false
                                onMoveToFolder(docUri, folder.id)
                                isFolderDropTargetActive = false
                                return true
                            }
                        }
                    ).then(if (hazeState != null) Modifier.glassPanel(hazeState, isDarkTheme, ShapeMd) else Modifier.fauxGlassPanel(isDarkTheme, ShapeMd)),
                shape = ShapeMd,
                color = if (isFolderDropTargetActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = if (isFolderDropTargetActive) BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                ) else null
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(onClick = {
                                collapsedSections[folder.id] = !isCollapsed(folder.id)
                            }) {
                                Icon(
                                    imageVector = Icons.Outlined.ChevronRight,
                                    contentDescription = "展開或收合",
                                    modifier = Modifier.graphicsLayer { rotationZ = if (isCollapsed(folder.id)) 0f else 90f }
                                )
                            }
                            Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(folder.name, style = MaterialTheme.typography.titleMedium)
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = "拖曳排序",
                                modifier = Modifier
                                    .size(20.dp)
                                    .pointerInput(folder.id, sectionIndex) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                folderDragAccumulator = 0f
                                            },
                                            onDragCancel = {
                                                folderDragAccumulator = 0f
                                            },
                                            onDragEnd = {
                                                folderDragAccumulator = 0f
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                folderDragAccumulator += dragAmount.y
                                                val threshold = 36f
                                                if (folderDragAccumulator > threshold) {
                                                    onMoveFolder(folder.id, false)
                                                    folderDragAccumulator = 0f
                                                } else if (folderDragAccumulator < -threshold) {
                                                    onMoveFolder(folder.id, true)
                                                    folderDragAccumulator = 0f
                                                }
                                            }
                                        )
                                    }
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${docs.size} 份",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Box {
                                IconButton(onClick = { showFolderMenu = true }) {
                                    Icon(Icons.Outlined.MoreVert, contentDescription = "資料夾操作")
                                }
                                androidx.compose.material3.DropdownMenu(
                                    expanded = showFolderMenu,
                                    onDismissRequest = { showFolderMenu = false },
                                    containerColor = Color.Transparent,
                                    shadowElevation = 0.dp,
                                    shape = ShapeMd,
                                    modifier = Modifier.fauxGlassPanel(isDarkTheme, ShapeMd)
                                ) {
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text("重新命名") },
                                        leadingIcon = { Icon(Icons.Outlined.Create, contentDescription = null) },
                                        onClick = {
                                            showFolderMenu = false
                                            renameFolderInput = folder.name
                                            showRenameFolderDialog = true
                                        }
                                    )
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text("刪除資料夾") },
                                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                                        onClick = {
                                            showFolderMenu = false
                                            showDeleteFolderDialog = true
                                        }
                                    )
                                }
                            }
                        }
                    }

                    AnimatedVisibility(visible = !isCollapsed(folder.id)) {
                        if (docs.isEmpty()) {
                            Text(
                                text = "這個資料夾目前是空的，尚未加入文件。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp)
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                docs.forEach { doc ->
                                    val coverBitmap by docViewModel.getDocumentThumbnail(context, doc.uri).collectAsState()
                                    DocumentListRow(
                                        document = doc,
                                        coverBitmap = coverBitmap,
                                        availableFolders = availableFolders,
                                        onClick = { onOpenDocument(doc.uri) },
                                        onDelete = { onDelete(doc.uri) },
                                        onFavoriteToggle = { isFav -> onFavoriteToggle(doc.uri, isFav) },
                                        onRename = { newName -> onRename(doc.uri, newName) },
                                        onMoveToFolder = { folderId -> onMoveToFolder(doc.uri, folderId) },
                                        onCreateFolder = { folderName -> onCreateFolder(folderName, null) },
                                        // 實底：省即時模糊（對話框照吃第二路真模糊）
                                        hazeState = null,
                                        dialogHaze = dialogHaze,
                                        isDarkTheme = isDarkTheme
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DocumentCard(
    document: DocumentEntity,
    coverBitmap: Bitmap?,
    availableFolders: List<FolderEntity>,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit = {},
    onFavoriteToggle: (Boolean) -> Unit = {},
    onMoveToFolder: (String?) -> Unit = {},
    onCreateFolder: (String) -> Unit = {},
    hazeState: dev.chrisbanes.haze.HazeState? = null,
    dialogHaze: dev.chrisbanes.haze.HazeState,
    isDarkTheme: Boolean = false
) {
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var renameInput by remember(document.displayName) { mutableStateOf(document.displayName) }
    var folderInput by remember { mutableStateOf("") }
    // 整張卡同一塊玻璃：封面/收藏鈕都只用半透明，不再有實心色塊切開材質
    val cardCoverVeil = Color.White.copy(alpha = if (isDarkTheme) 0.10f else 0.35f)
    val favVeil = Color.White.copy(alpha = if (isDarkTheme) 0.18f else 0.55f)

    AnimatedDialog(visible = showRenameDialog) {
        GlassDialogCustom(
            onDismissRequest = { showRenameDialog = false },
            dialogHaze = dialogHaze,
            isDark = isDarkTheme,
            title = { Text("重新命名") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    singleLine = true,
                    label = { Text("筆記名稱") },
                    colors = glassFieldColors(isDarkTheme),
                    shape = ShapeLg,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            buttons = {
                GlassTextButton(
                    text = "取消",
                    onClick = { showRenameDialog = false },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                GlassTextButton(
                    text = "確認",
                    onClick = {
                        onRename(renameInput)
                        showRenameDialog = false
                    },
                    enabled = renameInput.isNotBlank()
                )
            }
        )
    }

    AnimatedDialog(visible = showDeleteDialog) {
        GlassDialog(
            onDismissRequest = { showDeleteDialog = false },
            dialogHaze = dialogHaze,
            isDark = isDarkTheme,
            title = { Text("刪除筆記本") },
            text = {
                Text("確定要刪除「${document.displayName}」嗎？此操作會一併移除筆跡與標註，且無法復原。")
            },
            confirmText = "刪除",
            onConfirm = {
                showDeleteDialog = false
                onDelete()
            },
            confirmColor = MaterialTheme.colorScheme.error
        )
    }

    AnimatedDialog(visible = showMoveDialog) {
        GlassDialogCustom(
            onDismissRequest = { showMoveDialog = false },
            dialogHaze = dialogHaze,
            isDark = isDarkTheme,
            title = { Text("移到資料夾") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassTextButton(
                        text = "設為未分類",
                        onClick = {
                            onMoveToFolder(null)
                            showMoveDialog = false
                        },
                        alignStart = true
                    )
                    if (availableFolders.isEmpty()) {
                        Text(
                            text = "尚未建立資料夾",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        availableFolders.forEach { folder ->
                            GlassTextButton(
                                text = folder.name,
                                onClick = {
                                    onMoveToFolder(folder.id)
                                    showMoveDialog = false
                                },
                                alignStart = true
                            )
                        }
                    }
                }
            },
            buttons = {
                GlassTextButton(
                    text = "關閉",
                    onClick = { showMoveDialog = false },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                GlassTextButton(
                    text = "新增資料夾",
                    onClick = {
                        showMoveDialog = false
                        showCreateFolderDialog = true
                    }
                )
            }
        )
    }

    AnimatedDialog(visible = showCreateFolderDialog) {
        GlassDialogCustom(
            onDismissRequest = { showCreateFolderDialog = false },
            dialogHaze = dialogHaze,
            isDark = isDarkTheme,
            title = { Text("建立資料夾") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = folderInput,
                    onValueChange = { folderInput = it },
                    singleLine = true,
                    label = { Text("資料夾名稱") },
                    colors = glassFieldColors(isDarkTheme),
                    shape = ShapeLg,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            buttons = {
                GlassTextButton(
                    text = "取消",
                    onClick = { showCreateFolderDialog = false },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                GlassTextButton(
                    text = "建立",
                    onClick = {
                        val name = folderInput.trim()
                        if (name.isNotEmpty()) {
                            onCreateFolder(name)
                            folderInput = ""
                            showCreateFolderDialog = false
                        }
                    },
                    enabled = folderInput.trim().isNotEmpty()
                )
            }
        )
    }

    val cardInteractionSource = remember { MutableInteractionSource() }
    val isCardPressed by cardInteractionSource.collectIsPressedAsState()
    val cardScale by animateFloatAsState(
        targetValue = if (isCardPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "CardScale"
    )
    var showMenu by remember { mutableStateOf(false) }
    val dateStr = remember(document.lastOpenedAt) {
        java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.getDefault())
            .format(java.util.Date(document.lastOpenedAt))
    }

    val cardModifier = Modifier
        .fillMaxWidth()
        .aspectRatio(0.85f)
        .graphicsLayer { scaleX = cardScale; scaleY = cardScale }
        .documentDragSource(document.uri)
        .then(if (hazeState != null) Modifier.glassPanel(hazeState, isDarkTheme, ShapeLg) else Modifier.fauxGlassPanel(isDarkTheme, ShapeLg))
    androidx.compose.material3.Card(
        onClick = onClick,
        modifier = cardModifier,
        shape = ShapeLg,
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color.Transparent),
        border = null,
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 0.dp),
        interactionSource = cardInteractionSource
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Inset cover so thin glass rim stays visible around
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.6f)
                    .clip(ShapeMd)
                    .background(cardCoverVeil),
                contentAlignment = Alignment.Center
            ) {
                Crossfade(
                    targetState = coverBitmap,
                    animationSpec = tween(Motion.DURATION_NORMAL),
                    label = "CardCoverFade"
                ) { bitmap ->
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.FileUpload,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
                
                // Favorite Button
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                ) {
                    androidx.compose.material3.IconButton(
                        onClick = { onFavoriteToggle(!document.isFavorite) },
                        modifier = Modifier.size(32.dp).background(
                            favVeil,
                            CircleShape
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Star,
                            contentDescription = "Favorite",
                            tint = if (document.isFavorite) BrandAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            // Info 40%
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.4f)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = document.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Box {
                        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "More", modifier = Modifier.size(16.dp))
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            containerColor = Color.Transparent,
                            shadowElevation = 0.dp,
                            shape = ShapeMd,
                            modifier = Modifier.fauxGlassPanel(isDarkTheme, ShapeMd)
                        ) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("移到資料夾") },
                                leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    showMoveDialog = true
                                }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("新增資料夾") },
                                leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    showCreateFolderDialog = true
                                }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("重新命名") },
                                leadingIcon = { Icon(Icons.Outlined.Create, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    renameInput = document.displayName
                                    showRenameDialog = true
                                }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("刪除") },
                                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    showDeleteDialog = true
                                }
                            )
                        }
                    }
                }
                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DocumentListRow(
    document: DocumentEntity,
    coverBitmap: Bitmap?,
    availableFolders: List<FolderEntity>,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit = {},
    onFavoriteToggle: (Boolean) -> Unit = {},
    onMoveToFolder: (String?) -> Unit = {},
    onCreateFolder: (String) -> Unit = {},
    hazeState: dev.chrisbanes.haze.HazeState? = null,
    dialogHaze: dev.chrisbanes.haze.HazeState,
    isDarkTheme: Boolean = false
) {
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var renameInput by remember(document.displayName) { mutableStateOf(document.displayName) }
    var folderInput by remember { mutableStateOf("") }

    val dateStr = remember(document.lastOpenedAt) {
        java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(document.lastOpenedAt))
    }

    androidx.compose.material3.Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
            .documentDragSource(document.uri)
            .then(if (hazeState != null) Modifier.glassPanel(hazeState, isDarkTheme, ShapeMd) else Modifier.fauxGlassPanel(isDarkTheme, ShapeMd)),
        shape = ShapeMd,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = null,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f / 1.414f)
                    .clip(ShapeSm)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                if (coverBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = coverBitmap.asImageBitmap(),
                        contentDescription = "Cover",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Description,
                        contentDescription = "PDF",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.align(Alignment.Center).size(32.dp)
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = document.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = { onFavoriteToggle(!document.isFavorite) }) {
                Icon(
                    Icons.Outlined.Star,
                    contentDescription = "Favorite",
                    tint = if (document.isFavorite) BrandAmber else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
            
            var showMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "More")
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    containerColor = Color.Transparent,
                    shadowElevation = 0.dp,
                    shape = ShapeMd,
                    modifier = Modifier.fauxGlassPanel(isDarkTheme, ShapeMd)
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("移到資料夾") },
                        leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            showMoveDialog = true
                        }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("新增資料夾") },
                        leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            showCreateFolderDialog = true
                        }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("重新命名") },
                        leadingIcon = { Icon(Icons.Outlined.Create, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            renameInput = document.displayName
                            showRenameDialog = true
                        }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("刪除") },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            showDeleteDialog = true
                        }
                    )
                }
            }
        }
    }

    AnimatedDialog(visible = showMoveDialog) {
        GlassDialogCustom(
            onDismissRequest = { showMoveDialog = false },
            dialogHaze = dialogHaze,
            isDark = isDarkTheme,
            title = { Text("移到資料夾") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassTextButton(
                        text = "設為未分類",
                        onClick = {
                            onMoveToFolder(null)
                            showMoveDialog = false
                        },
                        alignStart = true
                    )
                    if (availableFolders.isEmpty()) {
                        Text(
                            text = "尚未建立資料夾",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        availableFolders.forEach { folder ->
                            GlassTextButton(
                                text = folder.name,
                                onClick = {
                                    onMoveToFolder(folder.id)
                                    showMoveDialog = false
                                },
                                alignStart = true
                            )
                        }
                    }
                }
            },
            buttons = {
                GlassTextButton(
                    text = "關閉",
                    onClick = { showMoveDialog = false },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                GlassTextButton(
                    text = "新增資料夾",
                    onClick = {
                        showMoveDialog = false
                        showCreateFolderDialog = true
                    }
                )
            }
        )
    }

    AnimatedDialog(visible = showCreateFolderDialog) {
        GlassDialogCustom(
            onDismissRequest = { showCreateFolderDialog = false },
            dialogHaze = dialogHaze,
            isDark = isDarkTheme,
            title = { Text("建立資料夾") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = folderInput,
                    onValueChange = { folderInput = it },
                    singleLine = true,
                    label = { Text("資料夾名稱") },
                    colors = glassFieldColors(isDarkTheme),
                    shape = ShapeLg,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            buttons = {
                GlassTextButton(
                    text = "取消",
                    onClick = { showCreateFolderDialog = false },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                GlassTextButton(
                    text = "建立",
                    onClick = {
                        val name = folderInput.trim()
                        if (name.isNotEmpty()) {
                            onCreateFolder(name)
                            folderInput = ""
                            showCreateFolderDialog = false
                        }
                    },
                    enabled = folderInput.trim().isNotEmpty()
                )
            }
        )
    }

    AnimatedDialog(visible = showRenameDialog) {
        GlassDialogCustom(
            onDismissRequest = { showRenameDialog = false },
            dialogHaze = dialogHaze,
            isDark = isDarkTheme,
            title = { Text("重新命名") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    singleLine = true,
                    colors = glassFieldColors(isDarkTheme),
                    shape = ShapeLg,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            buttons = {
                GlassTextButton(
                    text = "取消",
                    onClick = { showRenameDialog = false },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                GlassTextButton(
                    text = "確認",
                    onClick = {
                        if (renameInput.isNotBlank()) onRename(renameInput.trim())
                        showRenameDialog = false
                    }
                )
            }
        )
    }

    AnimatedDialog(visible = showDeleteDialog) {
        GlassDialog(
            onDismissRequest = { showDeleteDialog = false },
            dialogHaze = dialogHaze,
            isDark = isDarkTheme,
            title = { Text("刪除文件") },
            text = { Text("確定要刪除「${document.displayName}」嗎？此操作無法還原。") },
            confirmText = "刪除",
            onConfirm = {
                onDelete()
                showDeleteDialog = false
            },
            confirmColor = MaterialTheme.colorScheme.error
        )
    }
}
