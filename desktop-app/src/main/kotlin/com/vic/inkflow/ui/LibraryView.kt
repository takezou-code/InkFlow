package com.vic.inkflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
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
import com.vic.inkflow.ui.ShapeMd
import com.vic.inkflow.ui.fauxGlassPanel
import com.vic.inkflow.ui.pressableGlass
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
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }

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

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        // ── Header row: title + sync status pill ─────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "文件庫",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            // "Has this ever synced" is the signal that matters here: the pill is the only
            // place the user gets feedback that the library is populated at all.
            val tabletOnline = remember(syncStatusText) {
                !syncStatusText.startsWith("未偵測到") && !syncStatusText.startsWith("等待")
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .glassPill(
                        // Connected reads as armed, so it takes the tinted fill
                        // rather than neutral glass that would look decorative.
                        selected = tabletOnline,
                        alpha = 0.45f
                    )
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

// ── Search bar ────────────────────────────────────────────────────────
// Wrapped rather than restyled: OutlinedTextField draws its own 1dp outline, and
// the glass would sit behind that border instead of replacing it, so the field
// reads as a boxed input with a panel behind it. `specular = false` keeps a
// hairline off a 48dp-tall control where it would only look like a border.
Box(
    modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(14.dp))
        .fauxGlassPanel(InkThemeState.darkMode, RoundedCornerShape(14.dp), specular = false)
        .padding(horizontal = 12.dp, vertical = 2.dp)
) {
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("搜尋文件名或分類…") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { query = "" }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear")
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = Color.Transparent
        )
    )
}

        Spacer(Modifier.height(10.dp))

        // ── Folder filter chips ──────────────────────────────────────────────
        // Folder chips take the glass pill so the filter row belongs to the same
            // material as everything else. They stay inline (not a FlowRow) because
            // the folder list comes from the tablet and has no bounded length yet.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                FilterChip(
                    selected = selectedFolderId == null,
                    onClick = { onFolderSelected(null) },
                    label = { Text("全部") },
                    colors = glassChipColors()
                )
                folders.forEach { f ->
                    FilterChip(
                        selected = selectedFolderId == f.id,
                        onClick = { onFolderSelected(f.id) },
                        label = { Text(f.name) },
                        leadingIcon = { Icon(Icons.Default.Folder, null, modifier = Modifier.size(16.dp)) },
                        colors = glassChipColors()
                    )
                }
                FilterChip(
                    selected = selectedFolderId == "__none__",
                    onClick = { onFolderSelected("__none__") },
                    label = { Text("未分類") },
                    colors = glassChipColors()
                )
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

/**
 * Filter-chip colours that match the glass material.
 *
 * The unselected fill has to be translucent or the chip reads as an opaque M3
 * surface sitting on glass, which is the mismatch that made the first pass look
 * like two apps in one window.
 */
@Composable
private fun glassChipColors(): SelectableChipColors {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    return SelectableChipColors(
        containerColor = if (InkThemeState.darkMode) GlassTintDark else GlassTintLight,
        labelColor = muted,
        leadingIconColor = muted,
        trailingIconColor = muted,
        disabledContainerColor = Color.Transparent,
        disabledLabelColor = muted,
        disabledLeadingIconColor = muted,
        disabledTrailingIconColor = muted,
        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
        selectedLabelColor = MaterialTheme.colorScheme.primary,
        selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
        selectedTrailingIconColor = MaterialTheme.colorScheme.primary,
        // SelectableChipColors has no default for this one either; the chips are
        // never disabled on this screen.
        disabledSelectedContainerColor = Color.Transparent
    )
}

@Composable
private fun EmptyLibrary(query: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (query.isNotBlank()) "找不到符合「$query」的文件"
                else "尚無文件",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
            .pressableGlass(
                isDark = InkThemeState.darkMode,
                shape = ShapeMd,
                onClick = onClick
            )
            .clip(ShapeMd)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Thumbnail area: local PDF first page rendered lazily; fallback gradient.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.colorScheme.secondaryContainer
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
