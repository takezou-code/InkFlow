package com.vic.inkflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vic.inkflow.data.DatabaseManager
import com.vic.inkflow.data.DocumentEntity
import com.vic.inkflow.util.LocalImport
import com.vic.inkflow.ui.theme.ShapeMd
import com.vic.inkflow.ui.fauxGlassPanel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Library View (spec §4A): category rail on the left (handled by MainKt),
 * search field, and a card grid of synced documents with thumbnails.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    // File chooser. Uses the AWT dialog directly rather than Compose's
    // rememberFileDialogLauncher: the launcher API has to be created in a
    // @Composable and remembered, and its desktop behaviour around single-instance
    // dialogs has changed between Compose releases. The AWT call is stable, and it
    // is modal anyway.
    var importError by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }

    fun pickAndImport() {
        val chosen = runCatching {
            javax.swing.JFileChooser().apply {
                fileSelectionMode = javax.swing.JFileChooser.FILES_ONLY
                isAcceptAllFileFilterUsed = false
                fileFilter = javax.swing.filechooser.FileNameExtensionFilter("PDF 檔案", "pdf")
                isMultiSelectionEnabled = false
            }.let { chooser ->
                val result = chooser.showOpenDialog(null)
                if (result == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
            }
        }.getOrNull()

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

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // The tablet's own hero panel — serif italic wordmark with a moving gradient
        // shimmer, staggered entrance, glass search field, grid/list toggle — lifted
        // into `:shared` so the desktop runs the same component instead of a
        // hand-built header row.
        LibraryHeroPanel(
            searchQuery = query,
            onSearchQueryChange = { query = it },
            isDarkTheme = InkThemeState.darkMode,
            isGridView = true,
            onToggleGridView = {},
            hazeState = rememberHazeState()
        )

        // Sync status stays here rather than in the hero panel: it has no slot there,
        // and "has this ever synced" is the only signal the user gets that the
        // library is populated at all.
        val tabletOnline = remember(syncStatusText) {
            !syncStatusText.startsWith("未偵測到") && !syncStatusText.startsWith("等待")
        }
        // Sync status rides the shared selection pill when online (armed state, in
        // the tablet's language) and plain faux glass when offline. The old
        // private glassPill is gone — one selection language everywhere.
        Box(
            modifier = Modifier.padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .then(
                        if (tabletOnline) Modifier.glassSelectionPill(CircleShape)
                        else Modifier.fauxGlassPanel(InkThemeState.darkMode, CircleShape)
                    )
                    .clip(CircleShape)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    syncStatusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (tabletOnline) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }


        Spacer(Modifier.height(12.dp))

        // ── Folder filter ──────────────────────────────────────────────────
        // Shared option chips in a scrolling row, not M3 FilterChips: the folder
        // list comes from the tablet and has no bounded length, so the row
        // scrolls instead of clipping.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
            folders.forEach { f ->
                GlassOptionChip(
                    text = f.name,
                    selected = selectedFolderId == f.id,
                    onClick = { onFolderSelected(f.id) },
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

        Spacer(Modifier.height(12.dp))

        // ── Actions row ───────────────────────────────────────────────────
        // The import control sits above the grid rather than inside the hero panel:
        // the hero is the tablet's shared component and has no slot for one, and
        // bolting a button onto it would fork shared UI just to suit the desktop.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // Glass pill button, not an M3 OutlinedButton: actions on glass stay in
            // the shared press language (glassClickable) instead of bolting an
            // opaque control onto the backdrop.
            Box(
                modifier = Modifier
                    .fauxGlassPanel(InkThemeState.darkMode, CircleShape)
                    .glassClickable(
                        onClick = { pickAndImport() },
                        shape = CircleShape,
                        enabled = !importing
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val contentColor = glassContentColor(InkThemeState.darkMode)
                        .copy(alpha = if (importing) 0.38f else 1f)
                    Icon(Icons.Default.UploadFile, null, modifier = Modifier.size(16.dp), tint = contentColor)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (importing) "匯入中…" else "開啟本機 PDF",
                        style = MaterialTheme.typography.labelLarge,
                        color = contentColor
                    )
                }
            }
            importError?.let { msg ->
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Document card grid ───────────────────────────────────────────────
        if (docs.isEmpty()) {
            EmptyLibrary(query = query)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 210.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
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

@Composable
private fun EmptyLibrary(query: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (query.isNotBlank()) "找不到符合「$query」的文件"
                else "尚無文件",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "等待局域網同步從平板拉取筆記，或在平板端開啟 InkFlow 後點擊右上角同步按鈕。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentCard(
    document: DocumentEntity,
    strokeCount: Int,
    folderName: String?,
    onClick: () -> Unit
) {
    // Was `ElevatedCard(elevation = 3.dp)`: a solid M3 surface floating by cast
    // shadow. Replaced with the shared glass material, which is what the tablet
    // uses for the same card — translucency plus a top rim instead of a drop
    // shadow, so the thumbnail behind the glass actually shows through.
    Box(
        modifier = Modifier
            .height(210.dp)
            .fauxGlassPanel(InkThemeState.darkMode, ShapeMd)
            .glassClickable(onClick = onClick, shape = ShapeMd)
            .clip(ShapeMd)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Thumbnail area: local PDF first page rendered lazily; fallback gradient.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(
                        // surfaceVariant to surface, never secondaryContainer: the
                        // near-white container flashes on dark scroll.
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                val thumb = remember(document.uri) { findLocalPdfThumbSource(document) }
                if (thumb != null) {
                    PdfThumbnail(uri = thumb, modifier = Modifier.fillMaxSize())
                } else {
                    Text(
                        document.displayName.substringAfterLast('.', "PDF").uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (document.isFavorite) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = "Favorite",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(18.dp)
                    )
                }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    document.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
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
}

/** Resolve an existing local file for thumbnail rendering (mirrors PdfViewer logic). */
private fun findLocalPdfThumbSource(doc: DocumentEntity): String? {
    val direct = File(doc.localPath)
    if (direct.exists()) return doc.localPath
    val mirrored = File(
        System.getProperty("user.home") + File.separator + ".inkflow" +
            File.separator + "documents" + File.separator + direct.name
    )
    return if (mirrored.exists()) mirrored.absolutePath else null
}
