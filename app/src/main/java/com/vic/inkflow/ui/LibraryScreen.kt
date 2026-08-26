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
fun DocumentLibraryScreen(
    navController: NavController,
    db: AppDatabase,
    isDarkTheme: Boolean = false,
    onToggleDarkTheme: () -> Unit = {}
) {
    val context = LocalContext.current
    val docViewModel: DocumentViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = DocumentViewModelFactory(db.documentDao(), db.folderDao(), db.strokeDao(), db)
    )
    val documents by docViewModel.documents.collectAsState()
    val folders by docViewModel.folders.collectAsState()
    val folderOperationMessage by docViewModel.folderOperationMessage.collectAsState()
    val scope = rememberCoroutineScope()
    var showFabMenu by remember { mutableStateOf(false) }
    var showNewDocSizeDialog by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var createFolderInput by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(folderOperationMessage) {
        val message = folderOperationMessage ?: return@LaunchedEffect
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        docViewModel.consumeFolderOperationMessage()
    }

    if (showCreateFolderDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("建立新資料夾") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = createFolderInput,
                    onValueChange = { createFolderInput = it },
                    singleLine = true,
                    label = { Text("資料夾名稱") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = createFolderInput.trim()
                        if (name.isNotEmpty()) {
                            docViewModel.createFolder(name)
                            createFolderInput = ""
                            showCreateFolderDialog = false
                        }
                    },
                    enabled = createFolderInput.trim().isNotEmpty()
                ) { Text("建立") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) { Text("取消") }
            }
        )
    }

    if (showNewDocSizeDialog) {
        NewDocPaperSizeDialog(
            onDismiss = { showNewDocSizeDialog = false },
            onCreate = { widthPt, heightPt ->
                showNewDocSizeDialog = false
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val newUri = PdfManager.createBlankPdf(context, widthPt, heightPt)
                    if (newUri != null) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            docViewModel.recordOpened(newUri.toString(), "未命名筆記")
                            val encodedUri = URLEncoder.encode(newUri.toString(), StandardCharsets.UTF_8.toString())
                            navController.navigate("editor/$encodedUri")
                        }
                    } else {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "無法建立空白筆記", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        )
    }

    val pdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                val name = context.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: uri.lastPathSegment ?: "Untitled"
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val copiedUri = PdfManager.copyPdfToAppDir(context, uri)
                    if (copiedUri != null) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            docViewModel.recordOpened(copiedUri.toString(), name)
                            val encodedUri = URLEncoder.encode(copiedUri.toString(), StandardCharsets.UTF_8.toString())
                            navController.navigate("editor/$encodedUri")
                        }
                    } else {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "無法複製 PDF，請重試", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    )

    // Vivid brand gradient for the FAB / logo (never animated — static)
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val brandGradient = remember(primaryColor, secondaryColor) {
        androidx.compose.ui.graphics.Brush.linearGradient(
            listOf(primaryColor, secondaryColor)
        )
    }

    var selectedNavIndex by remember { mutableStateOf(0) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val normalizedQuery = searchQuery.trim()
    val filteredDocs = remember(documents, normalizedQuery, selectedNavIndex) {
        documents.filter { doc ->
            val matchesQuery = normalizedQuery.isEmpty() || doc.displayName.contains(normalizedQuery, ignoreCase = true)
            val matchesTab = when (selectedNavIndex) {
                1 -> true
                2 -> doc.isFavorite
                else -> true // Home
            }
            matchesQuery && matchesTab
        }
    }
    val folderTree = remember(folders, documents, normalizedQuery) {
        val folderScopedDocs = documents
            .asSequence()
            .filter { it.folderId != null }
            .groupBy { it.folderId }

        val childrenMap = folders.groupBy { it.parentFolderId }

        fun buildNode(folder: FolderEntity): FolderNode? {
            val childNodes = childrenMap[folder.id].orEmpty().mapNotNull { buildNode(it) }
            val docs = folderScopedDocs[folder.id].orEmpty()
            
            if (normalizedQuery.isNotEmpty()) {
                val matchedDocs = docs.filter { it.displayName.contains(normalizedQuery, ignoreCase = true) }
                val folderMatches = folder.name.contains(normalizedQuery, ignoreCase = true)
                if (matchedDocs.isNotEmpty() || folderMatches || childNodes.isNotEmpty()) {
                    return FolderNode(folder, matchedDocs, childNodes)
                } else {
                    return null
                }
            }
            return FolderNode(folder, docs, childNodes)
        }
        
        childrenMap[null].orEmpty().mapNotNull { buildNode(it) }
    }
    val uncategorizedDocs = remember(folders, documents, normalizedQuery) {
        val knownFolderIds = folders.map { it.id }.toHashSet()
        documents.filter { doc ->
            val matchesQuery = normalizedQuery.isEmpty() || doc.displayName.contains(normalizedQuery, ignoreCase = true)
            val noFolder = doc.folderId == null || doc.folderId !in knownFolderIds
            matchesQuery && noFolder
        }
    }
    val visibleDocumentCount = if (selectedNavIndex == 1) {
        fun countDocs(nodes: List<FolderNode>): Int = nodes.sumOf { it.documents.size + countDocs(it.children) }
        countDocs(folderTree) + uncategorizedDocs.size
    } else {
        filteredDocs.size
    }
    var isGridView by rememberSaveable { mutableStateOf(true) }

    // Outer Box does NOT read any animated State, so it never recomposes at 60 fps.
    // The animated gradient is drawn by the isolated AnimatedGradientBackground child.
    val libraryHazeState = rememberHazeState()
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        AnimatedGradientBackground(isDarkTheme, Modifier.fillMaxSize().then(
            androidx.compose.ui.Modifier.hazeSource(libraryHazeState)
        ))

    Row(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // Navigation Rail — transparent so the full-screen gradient shows through
        androidx.compose.material3.NavigationRail(
            modifier = Modifier.fillMaxHeight(),
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground
        ) {
            Spacer(Modifier.height(8.dp))
            // Brand logo — vivid flowing gradient circle
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(brandGradient, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("I", style = MaterialTheme.typography.titleMedium.copy(
                    color = androidx.compose.ui.graphics.Color.White,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold
                ))
            }
            Spacer(Modifier.height(16.dp))
            // Nav items
            NavigationRailItem(
                selected = selectedNavIndex == 0,
                onClick = { selectedNavIndex = 0 },
                icon = { Icon(Icons.Default.Home, contentDescription = "首頁") },
                label = { Text("首頁") }
            )
            NavigationRailItem(
                selected = selectedNavIndex == 1,
                onClick = { selectedNavIndex = 1 },
                icon = { Icon(Icons.Default.Folder, contentDescription = "資料夾") },
                label = { Text("資料夾") }
            )
            NavigationRailItem(
                selected = selectedNavIndex == 2,
                onClick = { selectedNavIndex = 2 },
                icon = { Icon(Icons.Default.Star, contentDescription = "收藏") },
                label = { Text("收藏") }
            )
            Spacer(Modifier.weight(1f))
            // Dark / Light mode toggle
            IconButton(onClick = onToggleDarkTheme) {
                Icon(
                    imageVector = if (isDarkTheme) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                    contentDescription = "切換主題"
                )
            }
            // Settings
            IconButton(onClick = { navController.navigate("settings") }) {
                Icon(Icons.Default.Settings, contentDescription = "設定")
            }
            Spacer(Modifier.height(8.dp))
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            topBar = {
                LibraryHeroPanel(
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    totalDocuments = documents.size,
                    visibleDocuments = visibleDocumentCount,
                    isDarkTheme = isDarkTheme,
                    isGridView = isGridView,
                    onToggleGridView = { isGridView = !isGridView },
                    selectedNavIndex = selectedNavIndex,
                    onCreateFolder = { showCreateFolderDialog = true },
                    hazeState = libraryHazeState
                )
            },
            floatingActionButton = {
                DocumentLibraryFab(
                    brandGradient = brandGradient,
                    showFabMenu = showFabMenu,
                    onToggleMenu = { showFabMenu = !showFabMenu },
                    onDismissMenu = { showFabMenu = false },
                    isDarkTheme = isDarkTheme,
                    hazeState = libraryHazeState,
                    onOpenPdf = {
                        showFabMenu = false
                        pdfLauncher.launch(arrayOf("application/pdf"))
                    },
                    onCreateBlank = {
                        showFabMenu = false
                        showNewDocSizeDialog = true
                    },
                    onCreateFolder = {
                        showFabMenu = false
                        showCreateFolderDialog = true
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .padding(horizontal = 18.dp)
                    .hazeSource(libraryHazeState)
            ) {
                AnimatedVisibility(
                    visible = normalizedQuery.isNotEmpty(),
                    enter = fadeIn(tween(220)) + expandVertically(tween(240)),
                    exit = fadeOut(tween(180)) + shrinkVertically(tween(200))
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 12.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                        shape = ShapeMd,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "搜尋結果",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "\"$normalizedQuery\" 對應 ${visibleDocumentCount} 份筆記",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { searchQuery = "" }) {
                                Text("清除搜尋")
                            }
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                if (selectedNavIndex == 1) {
                    if (folders.isEmpty() && uncategorizedDocs.isEmpty()) {
                        LibraryEmptyState(
                            modifier = Modifier.fillMaxSize(),
                            brandGradient = brandGradient,
                            isSearchActive = normalizedQuery.isNotEmpty(),
                            searchQuery = normalizedQuery,
                            onClearSearch = { searchQuery = "" },
                            onOpenPdf = { pdfLauncher.launch(arrayOf("application/pdf")) },
                            onCreateBlank = { showNewDocSizeDialog = true }
                        )
                    } else {
                        FolderGroupedDocumentsView(
                            folderTree = folderTree,
                            uncategorizedDocs = uncategorizedDocs,
                            availableFolders = folders,
                            docViewModel = docViewModel,
                            context = context,
                            onOpenDocument = { uri ->
                                val encodedUri = URLEncoder.encode(uri, StandardCharsets.UTF_8.toString())
                                navController.navigate("editor/$encodedUri")
                            },
                            onDelete = { uri -> docViewModel.delete(context, uri) },
                            onFavoriteToggle = { uri, isFav -> docViewModel.toggleFavorite(uri, isFav) },
                            onRename = { uri, newName -> docViewModel.rename(uri, newName) },
                            onMoveToFolder = { uri, folderId -> docViewModel.moveDocumentToFolder(uri, folderId) },
                            onCreateFolder = { folderName, parentId -> docViewModel.createFolder(folderName, parentId) },
                            onRenameFolder = { folderId, newName -> docViewModel.renameFolder(folderId, newName) },
                            onDeleteFolder = { folderId -> docViewModel.deleteFolder(folderId) },
                            onMoveFolder = { folderId, moveUp -> docViewModel.moveFolder(folderId, moveUp) },
                            onMoveFolderToParent = { folderId, targetParentId ->
                                docViewModel.moveFolderToParent(folderId, targetParentId)
                            }
                        )
                    }
                } else if (filteredDocs.isEmpty()) {
                    LibraryEmptyState(
                        modifier = Modifier.fillMaxSize(),
                        brandGradient = brandGradient,
                        isSearchActive = normalizedQuery.isNotEmpty(),
                        searchQuery = normalizedQuery,
                        onClearSearch = { searchQuery = "" },
                        onOpenPdf = { pdfLauncher.launch(arrayOf("application/pdf")) },
                        onCreateBlank = { showNewDocSizeDialog = true }
                    )
                } else {
                    val animatedCardUris = remember { mutableStateMapOf<String, Boolean>() }
                    if (isGridView) {
                        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                            columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(minSize = 180.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 6.dp, bottom = 120.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(filteredDocs.size, key = { filteredDocs[it].uri }) { index ->
                                val doc = filteredDocs[index]
                                val coverBitmap by docViewModel.getDocumentThumbnail(context, doc.uri).collectAsState()
                                val hasAnimated = animatedCardUris[doc.uri] == true
                                var visible by remember(doc.uri) { mutableStateOf(hasAnimated) }
                                LaunchedEffect(doc.uri) {
                                    if (!hasAnimated) {
                                        delay(index.coerceAtMost(8) * 60L)
                                        visible = true
                                        animatedCardUris[doc.uri] = true
                                    }
                                }
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = visible,
                                    enter = slideInVertically(
                                        initialOffsetY = { it / 2 },
                                        animationSpec = tween(300)
                                    ) + fadeIn(animationSpec = tween(300))
                                ) {
                                    DocumentCard(
                                        document = doc,
                                        coverBitmap = coverBitmap,
                                        availableFolders = folders,
                                        onClick = {
                                            val encodedUri = URLEncoder.encode(doc.uri, StandardCharsets.UTF_8.toString())
                                            navController.navigate("editor/$encodedUri")
                                        },
                                        onDelete = { docViewModel.delete(context, doc.uri) },
                                        onFavoriteToggle = { isFav -> docViewModel.toggleFavorite(doc.uri, isFav) },
                                        onRename = { newName -> docViewModel.rename(doc.uri, newName) },
                                        onMoveToFolder = { folderId -> docViewModel.moveDocumentToFolder(doc.uri, folderId) },
                                        onCreateFolder = { folderName -> docViewModel.createFolder(folderName) }
                                    )
                                }
                            }
                        }
                    } else {
                        androidx.compose.foundation.lazy.LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 6.dp, bottom = 120.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(filteredDocs.size, key = { filteredDocs[it].uri }) { index ->
                                val doc = filteredDocs[index]
                                val coverBitmap by docViewModel.getDocumentThumbnail(context, doc.uri).collectAsState()
                                val hasAnimated = animatedCardUris[doc.uri] == true
                                var visible by remember(doc.uri) { mutableStateOf(hasAnimated) }
                                LaunchedEffect(doc.uri) {
                                    if (!hasAnimated) {
                                        delay(index.coerceAtMost(8) * 60L)
                                        visible = true
                                        animatedCardUris[doc.uri] = true
                                    }
                                }
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = visible,
                                    enter = slideInVertically(
                                        initialOffsetY = { it / 2 },
                                        animationSpec = tween(300)
                                    ) + fadeIn(animationSpec = tween(300))
                                ) {
                                    DocumentListRow(
                                        document = doc,
                                        coverBitmap = coverBitmap,
                                        availableFolders = folders,
                                        onClick = {
                                            val encodedUri = URLEncoder.encode(doc.uri, StandardCharsets.UTF_8.toString())
                                            navController.navigate("editor/$encodedUri")
                                        },
                                        onDelete = { docViewModel.delete(context, doc.uri) },
                                        onFavoriteToggle = { isFav -> docViewModel.toggleFavorite(doc.uri, isFav) },
                                        onRename = { newName -> docViewModel.rename(doc.uri, newName) },
                                        onMoveToFolder = { folderId -> docViewModel.moveDocumentToFolder(doc.uri, folderId) },
                                        onCreateFolder = { folderName -> docViewModel.createFolder(folderName) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }   // close inner Row
    }   // close outer Box (background + gradient overlay)
}


data class FolderNode(
    val folder: FolderEntity,
    val documents: List<DocumentEntity>,
    val children: List<FolderNode>
)
