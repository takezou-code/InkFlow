package com.vic.inkflow.ui

import com.vic.inkflow.data.Document as SharedDocument
import com.vic.inkflow.data.Folder as SharedFolder
import com.vic.inkflow.data.repository.DocumentRepository
import com.vic.inkflow.data.repository.FolderRepository
import com.vic.inkflow.util.LocalImport
import com.vic.inkflow.util.PdfManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Screen state holder for the library.
 *
 * ## Why this exists
 *
 * `LibraryView` used to call `DatabaseManager` directly in composition, which
 * means the screen re-queried the database on every recomposition and the import
 * flow's side effects ran inside a composable. That is the pattern the Android
 * side moved away from years ago (`DocumentViewModel` + `DocumentRepository`);
 * the desktop is catching up.
 *
 * The ViewModel owns:
 *  - the query / folder filter (UI state)
 *  - the document & folder lists (loaded from the repositories)
 *  - the import transaction (validation → copy → upsert)
 *
 * The composable owns only the rendering and the file picker dialog.
 *
 * ## Scope
 *
 * A `CoroutineScope` is passed in rather than created internally so the owner
 * (the composable's `rememberCoroutineScope()`) controls cancellation. When the
 * composable leaves composition the scope is cancelled and in-flight imports
 * stop.
 */
class LibraryViewModel(
    private val documentRepository: DocumentRepository,
    private val folderRepository: FolderRepository,
    private val scope: CoroutineScope
) {

    data class UiState(
        val documents: List<SharedDocument> = emptyList(),
        val folders: List<SharedFolder> = emptyList(),
        val query: String = "",
        val selectedFolderId: String? = null,
        val importError: String? = null,
        val importing: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
        refresh()
    }

    fun setFolder(folderId: String?) {
        _state.value = _state.value.copy(selectedFolderId = folderId)
        refresh()
    }

    fun clearImportError() {
        _state.value = _state.value.copy(importError = null)
    }

    fun refresh() {
        scope.launch {
            val all = documentRepository.getAllDocumentsSync()
            val folders = (folderRepository as? com.vic.inkflow.data.JdbcFolderRepository)?.getAllFoldersSync() ?: emptyList()
            val s = _state.value
            val filtered = when (s.selectedFolderId) {
                null -> all
                "__none__" -> all.filter { it.folderId == null }
                else -> all.filter { it.folderId == s.selectedFolderId }
            }
            val searched = if (s.query.isBlank()) filtered
            else filtered.filter { doc ->
                doc.displayName.contains(s.query, ignoreCase = true) ||
                    folders.firstOrNull { it.id == doc.folderId }?.name?.contains(s.query, ignoreCase = true) == true
            }
            _state.value = s.copy(documents = searched, folders = folders)
        }
    }

    /**
     * Run the full import transaction. Returns true on success so the caller
     * (the composable) can bump its refresh token / close the picker.
     *
     * Validation happens before any I/O so a rejected file costs nothing; the
     * copy happens before the upsert so a failed copy never leaves a dangling
     * library entry.
     */
    fun importDocument(chosen: File, mirrorRoot: String, onLibraryChanged: () -> Unit) {
        scope.launch {
            _state.value = _state.value.copy(importing = true, importError = null)

            val ext = LocalImport.checkExtension(chosen.name)
            if (!ext.isOk) { failImport(ext.message); return@launch }
            val exists = LocalImport.checkExists(chosen)
            if (!exists.isOk) { failImport(exists.message); return@launch }
            val size = LocalImport.checkSize(chosen.length())
            if (!size.isOk) { failImport(size.message); return@launch }

            val uri = LocalImport.toDocumentUri(chosen.absolutePath)
            val plan = LocalImport.plan(uri, chosen, File(mirrorRoot))
            val copied = LocalImport.copyIntoMirror(plan)
            if (!copied.isOk) { failImport(copied.message); return@launch }

            val existing = documentRepository.getAllDocumentsSync().firstOrNull { it.uri == uri }
            documentRepository.upsert(
                SharedDocument(
                    uri = uri,
                    displayName = plan.displayName,
                    lastOpenedAt = existing?.lastOpenedAt ?: System.currentTimeMillis(),
                    lastPageIndex = existing?.lastPageIndex ?: 0,
                    isFavorite = existing?.isFavorite ?: false,
                    folderId = existing?.folderId
                )
            )

            _state.value = _state.value.copy(importing = false)
            onLibraryChanged()
            refresh()
        }
    }

    private fun failImport(message: String?) {
        _state.value = _state.value.copy(importing = false, importError = message)
    }

    /** Stroke count for a document (desktop-only query, delegates to the repository). */
    fun strokeCountForDocument(uri: String): Int =
        (documentRepository as? com.vic.inkflow.data.JdbcDocumentRepository)?.strokeCountForDocument(uri) ?: 0
}
