package com.vic.inkflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vic.inkflow.data.DocumentEntity
import com.vic.inkflow.ui.kit.DeskNavItem
import com.vic.inkflow.ui.kit.DeskSpace
import com.vic.inkflow.ui.kit.DeskTextField
import com.vic.inkflow.ui.theme.ShapeMd

/**
 * The command palette (Ctrl+K / Cmd+K).
 *
 * Every modern desktop app has one, and it earns its place here for a
 * specific reason: this app's library is a *mirror* of the tablet's, so the
 * fastest way to open a document is to type its name — you do not have to
 * find it in a grid, and you do not have to know which folder it is in.
 *
 * It also carries the few actions that have no home yet (open a local PDF,
 * flip the theme), so they are reachable without hunting for a toolbar.
 */
@Composable
fun CommandPalette(
    open: Boolean,
    documents: List<DocumentEntity>,
    onOpenDocument: (String) -> Unit,
    onOpenLocalPdf: () -> Unit,
    onToggleTheme: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!open) return

    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }

    val results: List<PaletteItem> = remember(documents, query) {
        val commands = if (query.isBlank()) {
            listOf(PaletteItem.OpenLocalPdf, PaletteItem.ToggleTheme)
        } else {
            emptyList()
        }
        val docs = documents
            .filter { it.displayName.contains(query, ignoreCase = true) }
            .take(8)
            .map { PaletteItem.Document(it) }
        commands + docs
    }

    // The palette opens empty; a stale query from last time would be confusing.
    LaunchedEffect(Unit) {
        query = ""
        selected = 0
        runCatching { focusRequester.requestFocus() }
    }

    fun run(item: PaletteItem) {
        when (item) {
            is PaletteItem.Document -> onOpenDocument(item.document.uri)
            PaletteItem.OpenLocalPdf -> onOpenLocalPdf()
            PaletteItem.ToggleTheme -> onToggleTheme()
        }
        onDismiss()
    }

    GlassDialogCustom(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "快速前往",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .width(520.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionDown -> {
                                selected = (selected + 1).coerceAtMost(results.lastIndex.coerceAtLeast(0))
                                true
                            }
                            Key.DirectionUp -> {
                                selected = (selected - 1).coerceAtLeast(0)
                                true
                            }
                            Key.Enter -> {
                                results.getOrNull(selected)?.let { run(it) }
                                true
                            }
                            Key.Escape -> {
                                onDismiss()
                                true
                            }
                            else -> false
                        }
                    }
            ) {
                DeskTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        selected = 0
                    },
                    placeholder = "搜尋文件或輸入指令",
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
                Spacer(Modifier.height(DeskSpace.sm))
                if (results.isEmpty()) {
                    Text(
                        text = "沒有符合的文件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = DeskSpace.sm)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .clip(ShapeMd)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    ) {
                        itemsIndexed(results, key = { _, item -> item.key }) { index, item ->
                            DeskNavItem(
                                icon = item.icon,
                                label = item.label,
                                selected = index == selected,
                                onClick = { run(item) }
                            )
                        }
                    }
                }
            }
        },
        buttons = {
            GlassTextButton(text = "關閉", onClick = onDismiss)
        }
    )
}

/** What the palette can offer. */
private sealed interface PaletteItem {
    val icon: androidx.compose.ui.graphics.vector.ImageVector
    val label: String
    val key: String

    data class Document(val document: DocumentEntity) : PaletteItem {
        override val icon = Icons.Outlined.Description
        override val label = document.displayName
        override val key = "doc:${document.uri}"
    }

    data object OpenLocalPdf : PaletteItem {
        override val icon = Icons.Outlined.UploadFile
        override val label = "開啟本機 PDF"
        override val key = "cmd:open"
    }

    data object ToggleTheme : PaletteItem {
        override val icon = if (InkThemeState.darkMode) Icons.Outlined.LightMode else Icons.Outlined.DarkMode
        override val label = if (InkThemeState.darkMode) "切換成淺色" else "切換成深色"
        override val key = "cmd:theme"
    }
}

/** The native file dialog, filtered to PDFs. Returns null if the user cancelled. */
internal fun pickPdfFile(): java.io.File? {
    val fd = java.awt.FileDialog(null as java.awt.Frame?, "開啟 PDF", java.awt.FileDialog.LOAD)
    fd.filenameFilter = java.io.FilenameFilter { _, name -> name.lowercase().endsWith(".pdf") }
    fd.isVisible = true
    val chosen = if (fd.file != null) java.io.File(fd.directory, fd.file) else null
    fd.dispose()
    return chosen
}

/**
 * Import a PDF into the library.
 *
 * Shared by the library screen's import button and the command palette, so the
 * two paths cannot drift (the palette used to be a second, thinner copy of the
 * import rules).
 */
internal suspend fun importPdfIntoLibrary(
    chosen: java.io.File,
    repository: com.vic.inkflow.data.repository.DocumentRepository
): Boolean {
    if (!com.vic.inkflow.util.LocalImport.checkExtension(chosen.name).isOk) return false
    if (!com.vic.inkflow.util.LocalImport.checkExists(chosen).isOk) return false
    if (!com.vic.inkflow.util.LocalImport.checkSize(chosen.length()).isOk) return false

    val uri = com.vic.inkflow.util.LocalImport.toDocumentUri(chosen.absolutePath)
    val plan = com.vic.inkflow.util.LocalImport.plan(
        uri,
        chosen,
        com.vic.inkflow.util.PdfManager.mirroredDir()
    )
    if (!com.vic.inkflow.util.LocalImport.copyIntoMirror(plan).isOk) return false

    val existing = repository.getAllDocumentsSync().firstOrNull { it.uri == uri }
    repository.upsert(
        com.vic.inkflow.data.Document(
            uri = uri,
            displayName = plan.displayName,
            lastOpenedAt = existing?.lastOpenedAt ?: System.currentTimeMillis(),
            lastPageIndex = existing?.lastPageIndex ?: 0,
            isFavorite = existing?.isFavorite ?: false,
            folderId = existing?.folderId
        )
    )
    return true
}
