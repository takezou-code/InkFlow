package com.vic.inkflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.secondaryContainer,
                tonalElevation = 2.dp
            ) {
                Text(
                    syncStatusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        // ── Search bar ────────────────────────────────────────────────────────
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
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            )
        )

        Spacer(Modifier.height(10.dp))

        // ── Folder filter chips ──────────────────────────────────────────────
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            FilterChip(
                selected = selectedFolderId == null,
                onClick = { onFolderSelected(null) },
                label = { Text("全部") }
            )
            folders.forEach { f ->
                FilterChip(
                    selected = selectedFolderId == f.id,
                    onClick = { onFolderSelected(f.id) },
                    label = { Text(f.name) },
                    leadingIcon = { Icon(Icons.Default.Folder, null, modifier = Modifier.size(16.dp)) }
                )
            }
            FilterChip(
                selected = selectedFolderId == "__none__",
                onClick = { onFolderSelected("__none__") },
                label = { Text("未分類") }
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
    ElevatedCard(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium, // 12dp per spec
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp),
        modifier = Modifier.height(210.dp)
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
