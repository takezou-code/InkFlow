package com.vic.inkflow.ui

import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import com.vic.inkflow.ui.theme.Motion
import com.vic.inkflow.ui.theme.ShapeSm
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
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
internal fun LibraryHeroPanel(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    isDarkTheme: Boolean,
    isGridView: Boolean,
    onToggleGridView: () -> Unit,
    selectedNavIndex: Int = 0,
    onCreateFolder: () -> Unit = {},
    hazeState: dev.chrisbanes.haze.HazeState
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Surface(
            modifier = Modifier.smartGlass(hazeState, isDarkTheme),
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 文青字標：兩字都襯線斜體輕字，錯峰進場 + InkFlow 流光
                var wordmarkVisible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { wordmarkVisible = true }
                val inkAlpha by animateFloatAsState(
                    targetValue = if (wordmarkVisible) 1f else 0f,
                    animationSpec = tween(550),
                    label = "WordInkAlpha"
                )
                val inkSlide by animateFloatAsState(
                    targetValue = if (wordmarkVisible) 0f else -26f,
                    animationSpec = tween(550, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                    label = "WordInkSlide"
                )
                val studioAlpha by animateFloatAsState(
                    targetValue = if (wordmarkVisible) 1f else 0f,
                    animationSpec = tween(550, delayMillis = 200),
                    label = "WordStudioAlpha"
                )
                val studioSlide by animateFloatAsState(
                    targetValue = if (wordmarkVisible) 0f else -18f,
                    animationSpec = tween(550, delayMillis = 200, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                    label = "WordStudioSlide"
                )
                val shimmerX by rememberInfiniteTransition(label = "WordShimmer")
                    .animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 2800, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "WordShimmerX"
                    )
                var inkWidth by remember { mutableFloatStateOf(0f) }
                val sweep = inkWidth.coerceAtLeast(1f) * (shimmerX * 1.8f - 0.4f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "InkFlow",
                        modifier = Modifier.graphicsLayer {
                            alpha = inkAlpha
                            translationX = inkSlide
                        },
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Light,
                            fontStyle = FontStyle.Italic,
                            fontFamily = FontFamily.Serif,
                            letterSpacing = (-0.5).sp,
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    Color.White.copy(alpha = 0.9f),
                                    MaterialTheme.colorScheme.primary
                                ),
                                start = Offset(sweep, 0f),
                                end = Offset(sweep + inkWidth.coerceAtLeast(1f) * 0.4f, 0f)
                            )
                        ),
                        onTextLayout = { inkWidth = it.size.width.toFloat() },
                        maxLines = 1
                    )
                    Text(
                        text = "  Studio",
                        modifier = Modifier.graphicsLayer {
                            alpha = studioAlpha
                            translationX = studioSlide
                        },
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Light,
                            fontStyle = FontStyle.Italic,
                            fontFamily = FontFamily.Serif,
                            letterSpacing = (-0.5).sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 內嵌搜尋丸：半透明凹槽 + 無框，嵌在玻璃面板裡自成一層
                    val searchVeil = Color.White.copy(alpha = if (isDarkTheme) 0.10f else 0.55f)
                    val searchVeilFocused = Color.White.copy(alpha = if (isDarkTheme) 0.16f else 0.70f)
                    androidx.compose.material3.OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        trailingIcon = {
                            AnimatedVisibility(visible = searchQuery.isNotBlank()) {
                                IconButton(onClick = { onSearchQueryChange("") }) {
                                    Icon(Icons.Outlined.Close, contentDescription = "清除搜尋")
                                }
                            }
                        },
                        placeholder = {
                            Text(
                                "搜尋筆記標題或文件名稱…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                            )
                        },
                        textStyle = MaterialTheme.typography.bodyLarge,
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = searchVeilFocused,
                            unfocusedContainerColor = searchVeil,
                            disabledContainerColor = searchVeil,
                            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                            unfocusedBorderColor = Color.Transparent,
                            cursorColor = MaterialTheme.colorScheme.primary
                        ),
                        shape = CircleShape
                    )
                    
                    if (selectedNavIndex == 1) {
                        // 玻璃丸 + primary 字：跟 view toggle 同語言
                        Surface(
                            shape = CircleShape,
                            color = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fauxGlassPanel(isDarkTheme, CircleShape)
                                .clickable(onClick = onCreateFolder)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                                Text("新增資料夾", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }

                    if (selectedNavIndex == 0) {
                        // 玻璃幽靈鈕：跟面板同一塊玻璃，不再用實心圓底
                        Box(
                            modifier = Modifier.smartGlass(hazeState, isDarkTheme, CircleShape)
                        ) {
                            IconButton(onClick = onToggleGridView) {
                                Icon(
                                    imageVector = if (isGridView) Icons.AutoMirrored.Filled.List else Icons.Outlined.GridView,
                                    contentDescription = "切換檢視",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DocumentLibraryFab(
    brandGradient: Brush,
    showFabMenu: Boolean,
    onToggleMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    isDarkTheme: Boolean,
    hazeState: dev.chrisbanes.haze.HazeState,
    onOpenPdf: () -> Unit,
    onCreateBlank: () -> Unit,
    onCreateFolder: () -> Unit
) {
    val fabContentColor = if (isDarkTheme) Color.White else MaterialTheme.colorScheme.primary
    Box {
        // 啫喱按壓走共用 pressableGlass（縮放 + 提亮）
        val fabInteractionSource = remember { MutableInteractionSource() }

        Box(
            modifier = Modifier
                .smartGlass(hazeState, isDarkTheme)
                .pressableGlass(fabInteractionSource)
                .clickable(interactionSource = fabInteractionSource, indication = androidx.compose.foundation.LocalIndication.current, onClick = onToggleMenu)
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(fabContentColor.copy(alpha = 0.14f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, tint = fabContentColor)
                }
                Column {
                    Text("新增筆記", color = fabContentColor, style = MaterialTheme.typography.labelLarge)
                    Text("空白頁或匯入 PDF", color = fabContentColor.copy(alpha = 0.72f), style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        androidx.compose.material3.DropdownMenu(
            expanded = showFabMenu,
            onDismissRequest = onDismissMenu,
            containerColor = Color.Transparent,
            shadowElevation = 0.dp,
            shape = ShapeLg,
            modifier = Modifier.fauxGlassPanel(isDarkTheme, ShapeLg)
        ) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("開啟 PDF") },
                leadingIcon = { Icon(Icons.Outlined.Description, contentDescription = null) },
                onClick = onOpenPdf
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("空白筆記") },
                leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = onCreateBlank
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("新增資料夾") },
                leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                onClick = onCreateFolder
            )
        }
    }
}

@Composable
internal fun LibraryEmptyState(
    modifier: Modifier = Modifier,
    brandGradient: Brush,
    isSearchActive: Boolean,
    searchQuery: String,
    onClearSearch: () -> Unit,
    onOpenPdf: () -> Unit,
    onCreateBlank: () -> Unit,
    hazeState: dev.chrisbanes.haze.HazeState? = null,
    isDarkTheme: Boolean = false
) {
    val emptyStateFloat by rememberInfiniteTransition(label = "EmptyIconFloat")
        .animateFloat(
            initialValue = 0f,
            targetValue = -14f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2200, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "EmptyFloatY"
        )
    val emptyStateAlpha by rememberInfiniteTransition(label = "EmptyIconPulse")
        .animateFloat(
            initialValue = 0.28f,
            targetValue = 0.58f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2200, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "EmptyAlpha"
        )
    var emptyVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(90); emptyVisible = true }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = emptyVisible,
            enter = fadeIn(tween(500)) + scaleIn(tween(500, easing = androidx.compose.animation.core.FastOutSlowInEasing), initialScale = 0.88f)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .then(if (hazeState != null) Modifier.smartGlass(hazeState, isDarkTheme, ShapeXl) else Modifier),
                color = if (hazeState != null) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.96f),
                shape = ShapeXl,
                border = if (hazeState != null) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                shadowElevation = if (hazeState != null) 0.dp else 10.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 30.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(92.dp)
                            .graphicsLayer { translationY = emptyStateFloat }
                            .clip(CircleShape)
                            .background(brandGradient),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isSearchActive) Icons.Outlined.Search else Icons.AutoMirrored.Outlined.NoteAdd,
                            contentDescription = null,
                            modifier = Modifier.size(38.dp),
                            tint = Color.White.copy(alpha = emptyStateAlpha + 0.34f)
                        )
                    }

                    Text(
                        text = if (isSearchActive) "沒有找到符合 \"$searchQuery\" 的筆記" else "靈感工作台已經就位",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = if (isSearchActive) {
                            "可以清除搜尋條件，或直接建立新的草稿頁，把這組想法先存下來。"
                        } else {
                            "從這裡開啟第一份 PDF，或直接建立一張空白頁，開始你的註記流程。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isSearchActive) {
                            Surface(
                                shape = ShapeLg,
                                color = Color.Transparent,
                                contentColor = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fauxGlassPanel(isDarkTheme, ShapeLg)
                                    .clickable(onClick = onClearSearch)
                            ) {
                                Text(
                                    text = "清除搜尋",
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .clip(ShapeLg)
                                    .background(brandGradient)
                                    .clickable(onClick = onOpenPdf)
                            ) {
                                Text(
                                    text = "開啟 PDF",
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White
                                )
                            }
                        }

                        Surface(
                            shape = ShapeLg,
                            color = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fauxGlassPanel(isDarkTheme, ShapeLg)
                                .clickable(onClick = onCreateBlank)
                        ) {
                            Text(
                                text = "建立空白筆記",
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }
        }
    }
}
