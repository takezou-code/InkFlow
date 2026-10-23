package com.vic.inkflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.Description
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vic.inkflow.data.DatabaseManager
import com.vic.inkflow.data.Document as SharedDocument
import com.vic.inkflow.ui.kit.DeskDims
import com.vic.inkflow.ui.kit.DeskPillButton
import com.vic.inkflow.ui.kit.DeskScreen
import com.vic.inkflow.ui.kit.DeskSearchField
import com.vic.inkflow.ui.kit.DeskSpace
import com.vic.inkflow.ui.theme.ShapeMd
import com.vic.inkflow.ui.theme.ShapeSm
import com.vic.inkflow.ui.theme.ShapeXl
import com.vic.inkflow.util.LocalImport
import com.vic.inkflow.util.PdfManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * The library: the documents on this machine, with a cover, a name, and when it
 * was last opened.
 *
 * ## What changed in the repository migration
 *
 * The screen used to call [DatabaseManager] directly in composition and run the
 * import transaction inline. It now takes a [LibraryViewModel] that owns the
 * query, the folder filter, the document/folder lists and the import flow; the
 * composable only renders and hosts the file picker. That is the same shape the
 * Android side has had for years (`DocumentViewModel` + `DocumentRepository`),
 * and it is the pattern every other desktop screen will follow.
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
        },
        actions = {
            SyncStatusPill(syncStatusText)
            DeskSearchField(
                value = state.query,
                onValueChange = { viewModel.onEvent(LibraryViewModel.LibraryEvent.SetQuery(it)) },
                placeholder = "搜尋標題或檔名",
                modifier = Modifier.width(240.dp)
            )
            DeskPillButton(
                text = if (state.importing) "匯入中…" else "開啟 PDF",
                icon = Icons.Default.UploadFile,
                onClick = { pickAndImport() },
                enabled = !state.importing
            )
        },
        modifier = modifier
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            FolderChips(
                folders = state.folders.map { it.id to it.name },
                selectedFolderId = state.selectedFolderId,
                onFolderSelected = { viewModel.onEvent(LibraryViewModel.LibraryEvent.SetFolder(it)) }
            )

            (state.importError)?.let { msg ->
                Spacer(Modifier.height(DeskSpace.xs))
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(DeskSpace.sm))

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


// (helpers appended below)

/** Sync status pill. */
@Composable
private fun SyncStatusPill(text: String) {
    val isDark = InkThemeState.darkMode
    val online = !text.startsWith("未偵測到") && !text.startsWith("等待")
    Box(
        modifier = Modifier
            .then(
                if (online) Modifier.glassSelectionPill(CircleShape)
                else Modifier.chromeGlass(isDark, CircleShape)
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

/** Folder filter chips in a scrolling row. */
@Composable
private fun FolderChips(
    folders: List<Pair<String, String>>,
    selectedFolderId: String?,
    onFolderSelected: (String?) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(DeskSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        GlassOptionChip(
            text = "全部",
            selected = selectedFolderId == null,
            onClick = { onFolderSelected(null) },
            isDark = InkThemeState.darkMode
        )
        folders.forEach { (id, name) ->
            GlassOptionChip(
                text = name,
                selected = selectedFolderId == id,
                onClick = { onFolderSelected(id) },
                isDark = InkThemeState.darkMode
            )
        }
        GlassOptionChip(
            text = "未分類",
            selected = selectedFolderId == "__none__",
            onClick = { onFolderSelected("__none__") },
            isDark = InkThemeState.darkMode
        )
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
                .chromeGlass(InkThemeState.darkMode, ShapeXl)
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
                if (query.isNotBlank()) "換個關鍵字，或清除搜尋看看全部文件。"
                else "等待局域網同步從平板拉取筆記，或直接開啟本機 PDF。",
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

/** One document card. */
@Composable
private fun DocumentCard(
    document: com.vic.inkflow.data.Document,
    strokeCount: Int,
    folderName: String?,
    onClick: () -> Unit
) {
    val isDark = InkThemeState.darkMode
    Column(
        modifier = Modifier
            .height(DeskDims.cardHeight)
            .chromeGlass(isDark, ShapeMd)
            .glassClickable(onClick = onClick, shape = ShapeMd)
            .clip(ShapeMd)
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
                    .clip(ShapeSm)
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
                    Text(
                        document.displayName.substringAfterLast('.', "PDF").uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        .padding(DeskSpace.xs)
                        .size(16.dp)
                )
            }
        }
        Column(
            modifier = Modifier.padding(
                start = DeskSpace.sm,
                end = DeskSpace.sm,
                top = DeskSpace.xs,
                bottom = DeskSpace.sm
            )
        ) {
            Text(
                document.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append(SimpleDateFormat("MM-dd HH:mm").format(Date(document.lastOpenedAt)))
                    append(" · $strokeCount 筆跡")
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
