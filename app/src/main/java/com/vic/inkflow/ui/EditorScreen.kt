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
import android.os.SystemClock
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
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.runtime.derivedStateOf
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
import com.styropyr0.prismal.sources.prismalGlassLayer
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

// --- 3. Editor Screen ---
enum class SidebarMode { COLLAPSED, NORMAL, FULLSCREEN }

@Composable
fun TabletEditorScreen(navController: NavController, uri: Uri, db: AppDatabase) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("inkflow_settings", 0) }
    val settingsRepository = remember(db, prefs) {
        EditorSettingsRepository(
            db = db,
            documentPreferenceDao = db.documentPreferenceDao(),
            prefs = prefs
        )
    }
    val viewModel: EditorViewModel = viewModel(
        factory = EditorViewModelFactory(db, uri.toString(), settingsRepository)
    )
    val pdfViewModel: PdfViewModel = viewModel()
    val strokes by viewModel.currentStrokes.collectAsState()
    val scope = rememberCoroutineScope()

    val docViewModel: DocumentViewModel = viewModel(
        factory = DocumentViewModelFactory(db.documentDao(), db.folderDao(), db.strokeDao(), db)
    )
    var sidebarMode by rememberSaveable { mutableStateOf(SidebarMode.COLLAPSED) }
    val activeTool by viewModel.selectedTool.collectAsState()
    var currentPageIndex by rememberSaveable { mutableIntStateOf(0) }
    var showStrokeWidthSlider by rememberSaveable { mutableStateOf(false) }
    // Guards against overwriting the DB value before we've read it on first open
    var initialPageRestored by rememberSaveable { mutableStateOf(false) }

    var showAiPanel by rememberSaveable { mutableStateOf(false) }
    var aiPanelWeight by rememberSaveable { mutableFloatStateOf(0.4f) }
    var aiFileUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val sidebarListState = rememberLazyListState()
    val mainListState = rememberLazyListState()
    val pinchActive by viewModel.pinchActive.collectAsState()
    // 點選意圖（側欄/靜態頁）：換作用頁 + 主列表滑過去
    val onRequestPage: (Int) -> Unit = { index ->
        currentPageIndex = index
        viewModel.setActivePage(index)
        scope.launch { runCatching { mainListState.animateScrollToItem(index) } }
    }
    // 卷動跟隨：主列表滑到哪頁就換作用頁（不捲主列表，避免打架；側欄由下方 effect 置中）
    val onScrollPage: (Int) -> Unit = { index ->
        if (index != currentPageIndex) {
            currentPageIndex = index
            viewModel.setActivePage(index)
        }
    }
    // 編輯器共用玻璃狀態：根 Aurora 當 source，TopBar/側欄/氣泡當 effect。
    // 真折射用同一個 Aurora 當 backdrop（haze 照用，小元件不受影響）。
    // Fix1 REVERTED: 紙層當 haze/prismal source 會凍結（氣泡 effect 與紙 source 同樹→重採樣迴圈；
    // 開 AI 面板改寬時巨型圖層重抓直接全黑）。氣泡暫回 Aurora 源（黑洞但穩定），另想辦法。
    val editorHaze = rememberHazeState()
    val editorPrismalBackdrop = com.styropyr0.prismal.sources.rememberPrismalGlassLayer()
    val isEditorDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    // 離開編輯器時刷新書庫封面（否則畫完墨水回主頁封面永遠是舊的）
    androidx.compose.runtime.DisposableEffect(uri) {
        onDispose {
            docViewModel.updateThumbnail(context, uri.toString())
        }
    }

    // Restore the last-viewed page from DB on first open; rememberSaveable keeps it
    // true across config changes so we don't reset the page on rotation.
    androidx.compose.runtime.LaunchedEffect(uri) {
        if (!initialPageRestored) {
            val stored = docViewModel.getLastPageIndex(uri.toString())
            if (stored > 0) {
                currentPageIndex = stored
                viewModel.setActivePage(stored)
                sidebarListState.scrollToCenter(stored)
            }
            initialPageRestored = true
            // 陳舊頁碼（刪頁後）+ 空文件都不可直接捲，會閃退
            runCatching {
                val count = pdfViewModel.pageCount.value
                if (count > 0) {
                    val safe = currentPageIndex.coerceIn(0, count - 1)
                    currentPageIndex = safe
                    viewModel.setActivePage(safe)
                    mainListState.scrollToItem(safe)
                }
            }
        }
    }

    // Persist current page and sync sidebar scroll whenever the user navigates or changes sidebar mode
    // 跟隨門衛：主列表推側欄時舉旗，側欄推主列表的那條看到旗就讓路，
    // 否則兩邊互推、底端來回彈（4↔5跳不停）。State 寫一天幾次，重組成本忽略。
    var sidebarFollowActive by remember { mutableStateOf(false) }
    // 側欄帶頭時間戳：側欄點選/轉頁蓋章，1 秒內跟隨不回拉，讓 fling 飛完。
    var lastSidebarDriveMs by remember { mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(currentPageIndex, sidebarMode) {
        // 旗子從 effect 一進來就舉（涵蓋防抖等待期），結束/取消才放下——
        // 之前只包著 animate，等待期有洞，迴圈從洞裡鑽。
        sidebarFollowActive = true
        try {
            if (initialPageRestored) {
                // 側欄帶的頭不回拉：1 秒內是側欄點選/轉頁造成的換頁，
                // 回拉會勒死側欄的 fling（只能一頁一頁切）。主列表自己動的不影響。
                // 但 DB 照寫，不然甩完直接退出、重開頁碼是舊的。
                if (SystemClock.uptimeMillis() - lastSidebarDriveMs < 1000) {
                    docViewModel.updateLastPage(uri.toString(), currentPageIndex)
                    return@LaunchedEffect
                }
                // 防抖：滑動中頁碼連跳時，每次重進 effect 會重設計時，
                // 只有停穩 150ms 才跟側欄 + 寫 DB。
                kotlinx.coroutines.delay(150)
                docViewModel.updateLastPage(uri.toString(), currentPageIndex)
                sidebarListState.animateScrollToCenter(currentPageIndex)
            }
        } finally {
            sidebarFollowActive = false
        }
    }

    // Resolve the human-readable file name from the documents DB record.
    // ContentResolver does not work with file:// URIs (copied PDFs use UUID filenames),
    // so we look up the displayName that was saved at import time.
    val documents by docViewModel.documents.collectAsState()
    val documentTitle = remember(uri, documents) {
        documents.find { it.uri == uri.toString() }?.displayName
            ?: uri.lastPathSegment ?: "Untitled"
    }

    // Open PDF once; PdfViewModel survives config changes
    androidx.compose.runtime.LaunchedEffect(uri) {
        docViewModel.markDocumentOpened(uri.toString())
        pdfViewModel.openPdf(uri)
    }
    val pageCount by pdfViewModel.pageCount.collectAsState()
    val isPageOperationInProgress by pdfViewModel.isPageOperationInProgress.collectAsState()
    val pageOperationMessage by pdfViewModel.pageOperationMessage.collectAsState()

    androidx.compose.runtime.LaunchedEffect(pageOperationMessage) {
        val message = pageOperationMessage ?: return@LaunchedEffect
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        pdfViewModel.consumePageOperationMessage()
    }

    val insertPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { selectedUri ->
        if (selectedUri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                selectedUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        pdfViewModel.insertPdfPages(
            context = context,
            documentUri = uri.toString(),
            sourceUri = selectedUri,
            afterIndex = currentPageIndex
        )
    }

    // When the PDF first loads, initialize the EditorViewModel's model space to match the first page.
    val firstPageSize by pdfViewModel.firstPageSize.collectAsState()
    androidx.compose.runtime.LaunchedEffect(firstPageSize) {
        firstPageSize?.let { (w, h) -> viewModel.initializePaperSize(w, h) }
    }

    // Document settings dialog state
    var showDocumentSettingsDialog by remember { mutableStateOf(false) }
    var showExportConfirmDialog by remember { mutableStateOf(false) }
    var isExportingPdf by remember { mutableStateOf(false) }
    val paperStyle by viewModel.paperStyle.collectAsState()
    AnimatedDialog(visible = showDocumentSettingsDialog) {
        DocumentSettingsDialog(
            documentTitle = documentTitle,
            pageCount = pageCount,
            currentPageIndex = currentPageIndex,
            currentStyle = paperStyle,
            isPageOperationInProgress = isPageOperationInProgress,
            onDismiss = { showDocumentSettingsDialog = false },
            onConfirmStyle = { viewModel.setPaperStyle(it) },
            onInsertPdf = {
                showDocumentSettingsDialog = false
                insertPdfLauncher.launch(arrayOf("application/pdf"))
            }
        )
    }

    AnimatedDialog(visible = showExportConfirmDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                if (!isExportingPdf) showExportConfirmDialog = false
            },
            modifier = Modifier.fauxGlassPanel(isEditorDark, ShapeLg),
            containerColor = Color.Transparent,
            shape = ShapeLg,
            title = { Text("確認輸出 PDF") },
            text = {
                Text(
                    if (isExportingPdf) "正在輸出，請稍候..."
                    else "將輸出目前文件的所有頁面與註記到 Downloads，是否繼續？"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (isExportingPdf) return@TextButton
                        isExportingPdf = true
                        scope.launch {
                            try {
                                val allStrokes = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    db.strokeDao().getAllStrokesForDocument(uri.toString())
                                }
                                val allTextAnnotations = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    db.textAnnotationDao().getAllForDocument(uri.toString())
                                }
                                val allImageAnnotations = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    db.imageAnnotationDao().getAllForDocument(uri.toString())
                                }
                                com.vic.inkflow.util.PdfExporter.export(
                                    originalPdfUri = uri,
                                    strokes = allStrokes,
                                    textAnnotations = allTextAnnotations,
                                    imageAnnotations = allImageAnnotations,
                                    context = context,
                                    fileName = "InkFlow_${System.currentTimeMillis()}.pdf",
                                    modelW = viewModel.modelWidth,
                                    modelH = viewModel.modelHeight
                                )
                            } finally {
                                isExportingPdf = false
                                showExportConfirmDialog = false
                            }
                        }
                    },
                    enabled = !isExportingPdf
                ) {
                    Text(if (isExportingPdf) "輸出中" else "確認匯出")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showExportConfirmDialog = false },
                    enabled = !isExportingPdf
                ) {
                    Text("取消")
                }
            }
        )
    }

    // Per-page aspect ratio: follows each page's real dimensions once scanned;
    // falls back to the document model space (which annotations are normalised against).
    val pageSizeVersion by pdfViewModel.pageSizeVersion.collectAsState()
    val pageAspectRatio = remember(paperStyle.aspectRatio, currentPageIndex, pageSizeVersion) {
        pdfViewModel.getPageAspectRatio(currentPageIndex, paperStyle.aspectRatio)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AuroraBackground(
            isDarkTheme = isEditorDark,
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(editorHaze)
                .prismalGlassLayer(editorPrismalBackdrop),
            orbCount = 5
        )
        Column(modifier = Modifier.fillMaxSize()) {
            TabletEditorTopBar(
                documentTitle = documentTitle,
                onBack = { navController.popBackStack() },
                viewModel = viewModel,
                showStrokeWidthSlider = showStrokeWidthSlider,
                onToggleStrokeWidthSlider = { showStrokeWidthSlider = !showStrokeWidthSlider },
                onHideStrokeWidthSlider = { showStrokeWidthSlider = false },
                onExport = {
                    showExportConfirmDialog = true
                },
                onDocumentSettings = { showDocumentSettingsDialog = true },
                onToggleAiPanel = { showAiPanel = !showAiPanel },
                hazeState = editorHaze,
                isDarkTheme = isEditorDark,
                prismalBackdrop = editorPrismalBackdrop
            )

            AnimatedVisibility(
                visible = showStrokeWidthSlider && (activeTool == Tool.PEN || activeTool == Tool.HIGHLIGHTER),
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                StrokeWidthSlider(
                    viewModel = viewModel,
                    hazeState = editorHaze,
                    isDarkTheme = isEditorDark,
                    prismalBackdrop = editorPrismalBackdrop
                )
            }

        // Auto-navigate to the newly inserted page
        val lastInsertedPage by pdfViewModel.lastInsertedPageIndex.collectAsState()
        androidx.compose.runtime.LaunchedEffect(lastInsertedPage) {
            val idx = lastInsertedPage ?: return@LaunchedEffect
            if (initialPageRestored) {
                currentPageIndex = idx
                viewModel.setActivePage(idx)
                sidebarListState.animateScrollToCenter(idx)
                runCatching { mainListState.animateScrollToItem(idx) }
                pdfViewModel.consumeInsertedPageEvent()
            }
        }

        // Adjust currentPageIndex after single/multi page delete.
        val lastDeletedPages by pdfViewModel.lastDeletedPageIndices.collectAsState()
        androidx.compose.runtime.LaunchedEffect(lastDeletedPages, pageCount) {
            if (lastDeletedPages.isEmpty()) return@LaunchedEffect
            val clamped = PdfViewModel.remapCurrentPageAfterDeletes(
                currentPageIndex = currentPageIndex,
                deletedIndices = lastDeletedPages,
                pageCountAfter = pageCount
            )
            currentPageIndex = clamped
            viewModel.setActivePage(clamped)
            sidebarListState.animateScrollToCenter(clamped)
            runCatching { mainListState.scrollToItem(clamped) }
            pdfViewModel.consumeDeletedPageEvent()
        }

        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val totalWidth = maxWidth
            val density = androidx.compose.ui.platform.LocalDensity.current
            
            val collapsedWidth = 68.dp
            val normalWidth = 160.dp
            
            val targetWidth = when (sidebarMode) {
                SidebarMode.COLLAPSED -> collapsedWidth
                SidebarMode.NORMAL -> normalWidth
                SidebarMode.FULLSCREEN -> totalWidth
            }

            val animatableWidth = remember {
                androidx.compose.animation.core.Animatable(
                    when (sidebarMode) {
                        SidebarMode.COLLAPSED -> 68f
                        SidebarMode.NORMAL -> 160f
                        SidebarMode.FULLSCREEN -> 160f
                    }
                )
            }
            val coroutineScope = rememberCoroutineScope()

            LaunchedEffect(targetWidth, totalWidth) {
                animatableWidth.animateTo(
                    targetValue = targetWidth.value,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
                )
            }

            val currentWidthDp = animatableWidth.value.dp

            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(currentWidthDp).fillMaxHeight()) {
                    Sidebar(
                sidebarMode = sidebarMode,
                onModeChange = { sidebarMode = it },
                pdfViewModel = pdfViewModel,
                pageCount = pageCount,
                currentPageIndex = currentPageIndex,
                db = db,
                documentUri = uri.toString(),
                modelWidth = viewModel.modelWidth,
                modelHeight = viewModel.modelHeight,
                onPageSelected = { index ->
                    lastSidebarDriveMs = SystemClock.uptimeMillis()
                    onRequestPage(index)
                },
                onAddPage = { afterIndex ->
                    scope.launch {
                        pdfViewModel.insertBlankPage(
                            context,
                            uri.toString(),
                            afterIndex,
                            pageWidthPt = paperStyle.widthPt,
                            pageHeightPt = paperStyle.heightPt
                        )
                    }
                },
                onDeletePages = { indices ->
                    pdfViewModel.deletePages(uri.toString(), indices)
                },
                listState = sidebarListState,
                modifier = Modifier.fillMaxSize(),
                hazeState = editorHaze,
                isDarkTheme = isEditorDark,
                isFollowingSidebar = sidebarFollowActive,
                isMainScrolling = mainListState.isScrollInProgress,
                prismalBackdrop = editorPrismalBackdrop
            )

        }

        // Drag Strip：獨立 24dp 細觸控條，NORMAL 顯示玻璃丸；
        // 點循環切換；橫拖調寬（1:1 跟手），直拖捲主列表；主軸先過 slop 先鎖定
        // 全屏態由網格內返回鈕退出，這裡不佔位
        if (sidebarMode != SidebarMode.FULLSCREEN) {
            Box(
                modifier = Modifier
                    .width(24.dp)
                    .fillMaxHeight()
                    .pointerInput(totalWidth) {
                        val anchors = listOf(
                            SidebarMode.COLLAPSED to collapsedWidth.value,
                            SidebarMode.NORMAL to normalWidth.value,
                            SidebarMode.FULLSCREEN to totalWidth.value
                        )
                        val order = listOf(SidebarMode.COLLAPSED, SidebarMode.NORMAL, SidebarMode.FULLSCREEN)
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            // null = 未定；true = 橫向調寬；false = 直向捲動（先過 slop 先鎖定）
                            var horizontalLock: Boolean? = null
                            var prevX = down.position.x
                            var prevY = down.position.y
                            var accX = 0f
                            var accY = 0f
                            var velX = 0f
                            var lastT = down.uptimeMillis
                            val slop = viewConfiguration.touchSlop
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    if (horizontalLock == null) {
                                        // 純點：循環切換，怎麼點都有反應
                                        sidebarMode = order[(order.indexOf(sidebarMode) + 1) % order.size]
                                    } else if (horizontalLock == true) {
                                        val vx = velX
                                        val currentW = animatableWidth.value
                                        val sorted = anchors.sortedBy { it.second }
                                        sidebarMode = when {
                                            vx > 600f -> sorted.firstOrNull { it.second > currentW + 1f }?.first
                                                ?: SidebarMode.FULLSCREEN
                                            vx < -600f -> sorted.lastOrNull { it.second < currentW - 1f }?.first
                                                ?: SidebarMode.COLLAPSED
                                            else -> anchors.minByOrNull { (_, w) ->
                                                kotlin.math.abs(currentW - w)
                                            }?.first ?: SidebarMode.COLLAPSED
                                        }
                                    }
                                    break
                                }
                                val dx = change.position.x - prevX
                                val dy = change.position.y - prevY
                                prevX = change.position.x
                                prevY = change.position.y
                                if (horizontalLock == null) {
                                    accX += dx
                                    accY += dy
                                    horizontalLock = when {
                                        kotlin.math.abs(accX) > slop && kotlin.math.abs(accX) >= kotlin.math.abs(accY) -> true
                                        kotlin.math.abs(accY) > slop -> false
                                        else -> null
                                    }
                                }
                                if (horizontalLock == true) {
                                    val now = change.uptimeMillis
                                    val dt = (now - lastT).coerceAtLeast(1L)
                                    lastT = now
                                    velX = velX * 0.75f + (dx / dt * 1000f) * 0.25f
                                    change.consume()
                                    val deltaDp = dx / density.density
                                    val newWidth = (animatableWidth.value + deltaDp).coerceIn(collapsedWidth.value, totalWidth.value)
                                    coroutineScope.launch { animatableWidth.snapTo(newWidth) }
                                } else if (horizontalLock == false) {
                                    // 直向：把主列表跟著手指捲（內容跟手）
                                    change.consume()
                                    val dyPx = -dy
                                    if (dyPx != 0f) {
                                        coroutineScope.launch { mainListState.scrollBy(dyPx) }
                                    }
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (sidebarMode == SidebarMode.NORMAL) {
                    Box(
                        Modifier
                            .width(6.dp)
                            .height(56.dp)
                            .glassPanel(editorHaze, isEditorDark, CircleShape)
                    )
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxSize()) {
                // Left Panel: AI Parser View — fade only. Size animation remeasures the whole
                // Row every frame → glass capture reallocs every frame → sustained black glass.
                // Instant layout (1 remeasure) + fade = single invisible frame; bitmaps persist via Fix2c.
                androidx.compose.animation.AnimatedVisibility(
                    visible = showAiPanel,
                    modifier = Modifier.weight(aiPanelWeight).fillMaxHeight(),
                    enter = fadeIn(tween(220)),
                    exit = fadeOut(tween(180))
                ) {
                    AiWebPanel(
                        fileUri = aiFileUri,
                        onClose = {
                            showAiPanel = false
                            aiFileUri = null
                        }
                    )
                }
                if (showAiPanel) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(10.dp)
                            .background(Color.Transparent)
                            .pointerInput(Unit) {
                                detectHorizontalDragGestures { change, dragAmount ->
                                    change.consume()
                                    val screenWidthPx = context.resources.displayMetrics.widthPixels.toFloat()
                                    val deltaWeight = dragAmount / screenWidthPx
                                    aiPanelWeight = (aiPanelWeight + deltaWeight).coerceIn(0.2f, 0.8f)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.VerticalDivider(
                            modifier = Modifier.fillMaxHeight(),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(32.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                        )
                    }
                }

                // Main Workspace
                val workspaceWeight = if (showAiPanel) (1f - aiPanelWeight) else 1f
                Box(Modifier.weight(workspaceWeight).fillMaxHeight()) {
                    Workspace(
                        pageIndex = currentPageIndex,
                        pdfViewModel = pdfViewModel,
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize(),
                        pageAspectRatio = pageAspectRatio,
                        documentUri = uri.toString(),
                        onAiFileReady = { fileUri ->
                            aiFileUri = fileUri
                            showAiPanel = true
                        },
                        hazeState = editorHaze,
                        isDarkTheme = isEditorDark,
                        db = db,
                        mainListState = mainListState,
                        onRequestPage = onRequestPage,
                        onScrollPage = onScrollPage,
                        prismalBackdrop = editorPrismalBackdrop
                    )
                } // 5 Box(Workspace)
            } // 6 inner Row
        } // 7 Box(weight 1f)
        } // 8 outer Row
        } // 9 BoxWithConstraints
        } // 10 Column
    } // 11 root Box(Aurora)
} // 12 TabletEditorScreen
