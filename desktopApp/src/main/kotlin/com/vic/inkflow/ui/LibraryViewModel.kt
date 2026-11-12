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
 * Screen state holder for the library, following unidirectional data flow (UDF).
 *
 * ## UDF contract
 *
 * The composable reads [state] (a [StateFlow]) and dispatches [LibraryEvent]s via
 * [onEvent]. It never mutates state directly. All state transitions happen
 * inside [onEvent], which is the single entry point for user actions.
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
        /**
         * 只看最愛。
         *
         * 這是**唯讀**的檢視：最愛這個旗標是平板的權威，桌面端沒有權威來源，
         * 自己在桌面標最愛會在下一次同步被平板的資料整份蓋掉（sync.md 的
         * tablet-wins）。所以桌面只提供「篩選已同步下來的最愛」，不提供「改」——
         * 提供一個會被吃掉的操作，比不提供更糟。
         */
        val favoritesOnly: Boolean = false,
        val importError: String? = null,
        val importing: Boolean = false
    )

    sealed interface LibraryEvent {
        data class SetQuery(val query: String) : LibraryEvent
        data class SetFolder(val folderId: String?) : LibraryEvent
        data class SetFavoritesOnly(val enabled: Boolean) : LibraryEvent
        data class ImportDocument(val chosen: File, val mirrorRoot: String, val onLibraryChanged: () -> Unit) : LibraryEvent
        data object ClearImportError : LibraryEvent
        data object Refresh : LibraryEvent
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        onEvent(LibraryEvent.Refresh)
    }

    fun onEvent(event: LibraryEvent) {
        when (event) {
            is LibraryEvent.SetQuery -> {
                _state.value = _state.value.copy(query = event.query)
                onEvent(LibraryEvent.Refresh)
            }
            is LibraryEvent.SetFolder -> {
                _state.value = _state.value.copy(selectedFolderId = event.folderId)
                onEvent(LibraryEvent.Refresh)
            }
            is LibraryEvent.SetFavoritesOnly -> {
                _state.value = _state.value.copy(favoritesOnly = event.enabled)
                onEvent(LibraryEvent.Refresh)
            }
            is LibraryEvent.ImportDocument -> importDocument(event.chosen, event.mirrorRoot, event.onLibraryChanged)
            is LibraryEvent.ClearImportError -> _state.value = _state.value.copy(importError = null)
            is LibraryEvent.Refresh -> refresh()
        }
    }

    /** Stroke count for a document (desktop-only query, delegates to the repository). */
    fun strokeCountForDocument(uri: String): Int =
        (documentRepository as? com.vic.inkflow.data.JdbcDocumentRepository)?.strokeCountForDocument(uri) ?: 0

    private fun importDocument(chosen: File, mirrorRoot: String, onLibraryChanged: () -> Unit) {
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
            onEvent(LibraryEvent.Refresh)
        }
    }

    private fun failImport(message: String?) {
        _state.value = _state.value.copy(importing = false, importError = message)
    }

    private fun refresh() {
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
            val favorites = if (s.favoritesOnly) searched.filter { it.isFavorite } else searched
            _state.value = s.copy(documents = favorites, folders = folders)
        }
    }
}
