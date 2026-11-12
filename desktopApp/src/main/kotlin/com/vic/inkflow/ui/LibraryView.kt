package com.vic.inkflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vic.inkflow.data.Document as SharedDocument
import com.vic.inkflow.data.Folder as SharedFolder
import com.vic.inkflow.ui.kit.DeskDims
import com.vic.inkflow.ui.kit.DeskNavItem
import com.vic.inkflow.ui.kit.DeskNavLabel
import com.vic.inkflow.ui.kit.DeskPillButton
import com.vic.inkflow.ui.kit.DeskScreen
import com.vic.inkflow.ui.kit.DeskSearchField
import com.vic.inkflow.ui.kit.DeskSpace
import com.vic.inkflow.ui.theme.ShapeLg
import com.vic.inkflow.ui.theme.ShapeMd
import com.vic.inkflow.util.LocalImport
import com.vic.inkflow.util.PdfManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * The library: the documents on this machine.
 *
 * ## The layout
 *
 * A navigation pane on the left (all / favourites / uncategorised / the folder
 * tree) and the documents on the right. That is the Windows 11 Explorer shape,
 * and it is also the only shape that can hold a folder tree: the previous
 * horizontal row of filter chips could not, and it pushed the content down
 * the screen while still being unable to say "everything".
 *
 * The pane is **read-only navigation**. Renaming, favouriting and moving
 * documents are tablet-side operations - the desktop mirrors the document list
 * and the sync replaces it wholesale - so offering them here would be an
 * action that silently undoes itself (see `LibraryViewModel.Destination`).
 */
@Composable
fun LibraryView(
    viewModel: LibraryViewModel,
    syncStatusText: String,
    onDocumentSelected: (String) -> Unit,
    mirrorRoot: String,
    onLibraryChanged: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()

    // File dialog. The native AWT FileDialog, not Swing's JFileChooser.
    var importError by remember { mutableStateOf<String?>(null) }

    fun pickAndImport() {
        val fd = java.awt.FileDialog(null as java.awt.Frame?, "開啟 PDF", java.awt.FileDialog.LOAD)
        fd.filenameFilter = java.io.FilenameFilter { _, name -> name.lowercase().endsWith(".pdf") }
        fd.isVisible = true
        val chosen = if (fd.file != null) File(fd.directory, fd.file) else null
        fd.dispose()

        if (chosen == null) return
        viewModel.onEvent(LibraryViewModel.LibraryEvent.ImportDocument(chosen, mirrorRoot, onLibraryChanged))
    }

    DeskScreen(
        title = "文件庫",
        subtitle = buildString {
            append("${state.documents.size} 份文件")
            if (state.query.isNotBlank()) append(" · 搜尋「${state.query}」")
            when (val d = state.destination) {
                LibraryViewModel.Destination.All -> Unit
                LibraryViewModel.Destination.Favorites -> append(" · 我的最愛")
                LibraryViewModel.Destination.Uncategorized -> append(" · 未分類")
                is LibraryViewModel.Destination.Folder ->
                    append(" · " + (state.folders.firstOrNull { it.id == d.id }?.name ?: "資料夾"))
            }
        },
        actions = {
            SyncStatusPill(syncStatusText)
            DeskSearchField(
                value = state.query,
                onValueChange = { viewModel.onEvent(LibraryViewModel.LibraryEvent.SetQuery(it)) },
                placeholder = "搜尋標題或檔名",
                // 固定寬度的話，視窗一窄就被推出畫面；讓它可以收，
                // 寬度不夠時動作列自己會捲動（見 DeskScreen）。
                modifier = Modifier.widthIn(min = 150.dp, max = 260.dp)
            )
            DeskPillButton(
                text = if (state.importing) "匯入中…" else "開啟 PDF",
                icon = Icons.Outlined.UploadFile,
                onClick = { pickAndImport() },
                enabled = !state.importing
            )
        },
        modifier = modifier
    ) {
        Row(Modifier.fillMaxSize()) {
            LibraryNavPane(
                state = state,
                onDestination = { viewModel.onEvent(LibraryViewModel.LibraryEvent.SetDestination(it)) }
            )
            // 一條細線把窗格和內容分開，不用再畫一整塊面板（Windows 11 就是這樣）。
            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = DeskSpace.md)
            ) {
                (state.importError)?.let { msg ->
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(DeskSpace.sm))
                }

                if (state.documents.isEmpty()) {
                    EmptyLibrary(query = state.query, onImport = { pickAndImport() })
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = DeskDims.cardMinWidth),
                        horizontalArrangement = Arrangement.spacedBy(DeskSpace.md),
                        verticalArrangement = Arrangement.spacedBy(DeskSpace.md),
                        contentPadding = PaddingValues(bottom = DeskSpace.lg),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(state.documents.size, key = { state.documents[it].uri }) { i ->
                            val doc = state.documents[i]
                            DocumentCard(
                                document = doc,
                                strokeCount = viewModel.strokeCountForDocument(doc.uri),
                                folderName = state.folders.firstOrNull { it.id == doc.folderId }?.name,
                                onClick = { onDocumentSelected(doc.uri) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The navigation pane.
 *
 * Everything in it is a *view*, never an edit - see the file header.
 */
@Composable
private fun LibraryNavPane(
    state: LibraryViewModel.UiState,
    onDestination: (LibraryViewModel.Destination) -> Unit
) {
    val destination = state.destination
    Column(
        modifier = Modifier
            .width(232.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(end = DeskSpace.sm)
    ) {
        DeskNavLabel("文件庫")
        DeskNavItem(
            icon = Icons.Outlined.Home,
            label = "全部文件",
            selected = destination == LibraryViewModel.Destination.All,
            onClick = { onDestination(LibraryViewModel.Destination.All) },
            trailing = state.folders.size.let { null }
        )
        DeskNavItem(
            icon = Icons.Default.Star,
            label = "我的最愛",
            selected = destination == LibraryViewModel.Destination.Favorites,
            onClick = { onDestination(LibraryViewModel.Destination.Favorites) }
        )
        DeskNavItem(
            icon = Icons.Outlined.Inbox,
            label = "未分類",
            selected = destination == LibraryViewModel.Destination.Uncategorized,
            onClick = { onDestination(LibraryViewModel.Destination.Uncategorized) }
        )

        if (state.folders.isNotEmpty()) {
            DeskNavLabel(
                text = "資料夾",
                modifier = Modifier.padding(top = DeskSpace.sm)
            )
            FolderTree(
                folders = state.folders,
                destination = destination,
                onDestination = onDestination,
                depth = 0
            )
        }
    }
}

/**
 * The folder tree, indented by depth.
 *
 * The folders are the tablet's, mirrored - they have a parent id, so they are
 * a tree, not a list. A tree rendered as a flat list looks like the data is
 * flat when it is not.
 */
@Composable
private fun FolderTree(
    folders: List<SharedFolder>,
    destination: LibraryViewModel.Destination,
    onDestination: (LibraryViewModel.Destination) -> Unit,
    depth: Int
) {
    // Top level = no parent, or a parent that is not in the list (a folder whose
    // parent vanished must still be reachable, not disappear).
    val roots = folders.filter { f -> f.parentFolderId == null || folders.none { it.id == f.parentFolderId } }
    roots.forEach { folder ->
        DeskNavItem(
            icon = Icons.Outlined.Folder,
            label = folder.name,
            selected = destination == LibraryViewModel.Destination.Folder(folder.id),
            onClick = { onDestination(LibraryViewModel.Destination.Folder(folder.id)) },
            modifier = Modifier.padding(start = (depth * 14).dp)
        )
        val sub = folders.filter { it.parentFolderId == folder.id }
        if (sub.isNotEmpty()) {
            FolderTree(
                folders = sub,
                destination = destination,
                onDestination = onDestination,
                depth = depth + 1
            )
        }
    }
}

/** Sync status pill. */
@Composable
private fun SyncStatusPill(text: String) {
    val isDark = InkThemeState.darkMode
    val online = !text.startsWith("未偵測到") && !text.startsWith("等待")
    Box(
        modifier = Modifier
            .then(
                if (online) Modifier.glassSelectionPill(CircleShape)
                else Modifier.background(MaterialTheme.colorScheme.surface, CircleShape)
            )
            .clip(CircleShape)
            .padding(horizontal = DeskSpace.sm, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        if (online) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline
                    )
            )
            Spacer(Modifier.width(DeskSpace.xs))
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = if (online) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp)
            )
        }
    }
}

/** Empty library state. */
@Composable
private fun EmptyLibrary(query: String, onImport: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 420.dp)
                .clip(ShapeLg)
                .background(MaterialTheme.colorScheme.surface, ShapeLg)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    ShapeLg
                )
                .padding(DeskSpace.lg)
        ) {
            Icon(
                Icons.Outlined.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.height(DeskSpace.sm))
            Text(
                if (query.isNotBlank()) "找不到符合「$query」的文件" else "尚無文件",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(DeskSpace.xs))
            Text(
                if (query.isNotBlank()) "換個關鍵字，或回到「全部文件」。"
                else "等待區域網同步從平板拉取筆記，或直接開啟本機 PDF。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (query.isBlank()) {
                Spacer(Modifier.height(DeskSpace.md))
                GlassTextButton(text = "開啟本機 PDF", onClick = onImport)
            }
        }
    }
}

/**
 * One document card.
 *
 * A flat surface with a hairline, not a floating card with a shadow: the
 * window already sits on a Mica backdrop, and a shadow there reads as dirt.
 * The thumbnail keeps the page's own aspect so a row of documents reads as a
 * row of pages.
 */
@Composable
private fun DocumentCard(
    document: SharedDocument,
    strokeCount: Int,
    folderName: String?,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .height(DeskDims.cardHeight)
            .clip(ShapeLg)
            .background(MaterialTheme.colorScheme.surface, ShapeLg)
            .border(
                1.dp,
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                ShapeLg
            )
            .glassClickable(onClick = onClick, shape = ShapeLg)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(DeskSpace.sm)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(ShapeMd)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                val thumb = remember(document.uri) {
                    PdfManager.resolveFile(document.uri)?.absolutePath
                }
                if (thumb != null) {
                    PdfThumbnail(
                        uri = thumb,
                        modifier = Modifier.fillMaxSize().padding(6.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Icon(
                        Icons.Outlined.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            if (document.isFavorite) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = "我的最愛",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(16.dp)
                )
            }
        }
        Column(
            modifier = Modifier.padding(
                start = DeskSpace.sm,
                end = DeskSpace.sm,
                top = DeskSpace.xxs,
                bottom = DeskSpace.sm
            )
        ) {
            Text(
                document.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append(relativeTime(document.lastOpenedAt))
                    if (strokeCount > 0) append(" · $strokeCount 筆跡")
                    if (folderName != null) append(" · $folderName")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * "3 分鐘前" instead of "10-09 10:46".
 *
 * A date string is only meaningful if you remember what day it was; a
 * relative time answers the question the card is actually asking ("is this
 * something I was working on?"). Older than a week it falls back to the date,
 * because "23 天前" stops helping.
 */
private fun relativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    val minutes = diff / 60_000
    return when {
        minutes < 1 -> "剛剛"
        minutes < 60 -> "$minutes 分鐘前"
        minutes < 60 * 24 -> "${minutes / 60} 小時前"
        minutes < 60 * 48 -> "昨天"
        minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)} 天前"
        else -> SimpleDateFormat("yyyy-MM-dd").format(Date(timestamp))
    }
}
