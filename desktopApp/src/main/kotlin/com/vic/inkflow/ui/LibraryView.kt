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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.vic.inkflow.data.DatabaseManager
import com.vic.inkflow.data.DocumentEntity
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
 * ## What changed, and why
 *
 * It used to open with the tablet's `LibraryHeroPanel` — a serif wordmark and a
 * full-width search pill — and then stack three more full-width rows (sync pill,
 * folder chips, import button) before the first card. Four rows of chrome, each
 * with its own inset, so nothing lined up with anything and the content started
 * 400px down the window.
 *
 * Now the screen is a [DeskScreen]: one title, one row of actions, one filter row,
 * then the documents. The search field is in the header where a search field
 * belongs, and the tablet's hero stays where it is — on the tablet, where it has
 * room.
 */
@Composable
fun LibraryView(
    databaseManager: DatabaseManager,
    refreshToken: Int,
    syncStatusText: String,
    selectedFolderId: String?,            // null = All, "__none__" = Uncategorized
    onFolderSelected: (String?) -> Unit,
    onDocumentSelected: (String) -> Unit,
    /** Mirror directory the imported copies land in. */
    mirrorRoot: String,
    onLibraryChanged: () -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }

    // File dialog. The native AWT FileDialog, not Swing's JFileChooser: the Swing
    // dialog looks like 2005 and breaks the whole visual language, while the
    // native one matches the OS. It is modal and blocking, same as before.
    var importError by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }

    fun pickAndImport() {
        val fd = java.awt.FileDialog(null as java.awt.Frame?, "開啟 PDF", java.awt.FileDialog.LOAD)
        fd.filenameFilter = java.io.FilenameFilter { _, name -> name.lowercase().endsWith(".pdf") }
        fd.isVisible = true
        val chosen = if (fd.file != null) File(fd.directory, fd.file) else null
        fd.dispose()

        if (chosen == null) return

        // Validation before any I/O, so a rejected file costs nothing and the
        // reason reaches the user in words rather than as a stack trace.
        val ext = LocalImport.checkExtension(chosen.name)
        if (!ext.isOk) { importError = ext.message; return }
        val exists = LocalImport.checkExists(chosen)
        if (!exists.isOk) { importError = exists.message; return }
        val size = LocalImport.checkSize(chosen.length())
        if (!size.isOk) { importError = size.message; return }

        importing = true
        importError = null
        val uri = LocalImport.toDocumentUri(chosen.absolutePath)
        val plan = LocalImport.plan(uri, chosen, File(mirrorRoot))
        val copied = LocalImport.copyIntoMirror(plan)

        if (!copied.isOk) {
            importError = copied.message
            importing = false
            return
        }

        // Only now does the library learn about the document. Registering it before
        // the copy succeeded would leave an entry that cannot be opened — the one
        // state worse than "the file is not in my library yet".
        val existing = databaseManager.getDocument(uri)
        databaseManager.saveDocument(
            DocumentEntity(
                uri = uri,
                displayName = plan.displayName,
                lastOpenedAt = existing?.lastOpenedAt ?: System.currentTimeMillis(),
                lastPageIndex = existing?.lastPageIndex ?: 0,
                isFavorite = existing?.isFavorite ?: false,
                folderId = existing?.folderId
            )
        )
        importing = false
        onLibraryChanged()
    }

    val folders = remember(refreshToken) { databaseManager.getAllFolders() }
    val allDocs = remember(refreshToken, query) {
        if (query.isBlank()) databaseManager.getAllDocuments()
        else databaseManager.searchDocuments(query)
    }
    val docs = remember(allDocs, selectedFolderId) {
        when (selectedFolderId) {
            null -> allDocs
            "__none__" -> allDocs.filter { it.folderId == null }
            else -> allDocs.filter { it.folderId == selectedFolderId }
        }
    }

    DeskScreen(
        title = "文件庫",
        subtitle = buildString {
            append("${docs.size} 份文件")
            if (query.isNotBlank()) append(" · 搜尋「$query」")
        },
        actions = {
            SyncStatusPill(syncStatusText)
            DeskSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "搜尋標題或檔名",
                modifier = Modifier.width(240.dp)
            )
            DeskPillButton(
                text = if (importing) "匯入中…" else "開啟 PDF",
                icon = Icons.Default.UploadFile,
                onClick = { pickAndImport() },
                enabled = !importing
            )
        },
        modifier = modifier
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            FolderChips(
                folders = folders.map { it.id to it.name },
                selectedFolderId = selectedFolderId,
                onFolderSelected = onFolderSelected
            )

            importError?.let { msg ->
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

            if (docs.isEmpty()) {
                EmptyLibrary(query = query, onImport = { pickAndImport() })
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = DeskDims.cardMinWidth),
                    horizontalArrangement = Arrangement.spacedBy(DeskSpace.md),
                    verticalArrangement = Arrangement.spacedBy(DeskSpace.md),
                    contentPadding = PaddingValues(bottom = DeskSpace.lg),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(docs.size, key = { docs[it].uri }) { i ->
                        DocumentCard(
                            document = docs[i],
                            strokeCount = databaseManager.strokeCountForDocument(docs[i].uri),
                            folderName = folders.firstOrNull { it.id == docs[i].folderId }?.name,
                            onClick = { onDocumentSelected(docs[i].uri) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * The tablet-connection pill.
 *
 * Rides the shared selection pill when a tablet is present (the tablet's own
 * "armed" language) and plain glass when it is not. The dot is not decoration:
 * colour alone would be the only online/offline signal for a user who cannot see
 * the difference, so the text carries it too.
 */
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

/**
 * Folder filters, in a scrolling row.
 *
 * Shared option chips, not M3 `FilterChip`: the folder list comes from the tablet
 * and has no bounded length, so the row scrolls instead of clipping, and the
 * chips stay in the product's press language.
 */
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

@Composable
private fun EmptyLibrary(query: String, onImport: () -> Unit) {
    // A real empty state, not two grey lines: glass card, icon, guidance, and
    // the one action that gets the user out of here.
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
                if (query.isNotBlank()) "找不到符合「$query」的文件"
                else "尚無文件",
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

/**
 * One document: a page-shaped cover, its name, and when it was last opened.
 *
 * The cover is a real render of page 1 (the same `PdfManager` cache the reader
 * uses), not a file-type icon — a library of identical placeholders tells the
 * user nothing about which document is which. It is drawn `Fit` on a paper-grey
 * plate so a blank page reads as "a blank page", not as a missing image.
 */
@Composable
private fun DocumentCard(
    document: DocumentEntity,
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
