package com.vic.inkflow.data

import com.vic.inkflow.data.repository.DocumentRepository
import com.vic.inkflow.data.repository.FolderRepository
import com.vic.inkflow.data.Document as SharedDocument
import com.vic.inkflow.data.Folder as SharedFolder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Desktop (JDBC/SQLite) implementation of the shared [DocumentRepository].
 *
 * ## Why a wrapper instead of making [DatabaseManager] implement the interface
 *
 * [DatabaseManager] is a 1200-line class that owns the connection, the lock and
 * every SQL statement. Making it implement two shared interfaces would force it
 * to speak the shared model (`Document`/`Folder`) everywhere, leaking the
 * mapping into every method and making the diff unreadable. The wrapper keeps
 * the boundary in one place: in here, `DocumentEntity` becomes `Document` and
 * back, exactly the way `RoomDocumentRepository` does it on Android.
 *
 * ## Status
 *
 * Only the methods the library screen needs are wired to real SQL. The rest throw
 * [NotImplementedError] with a `TODO(desktop)` tag so a missing implementation
 * fails loudly instead of silently returning empty data. They get filled in as
 * the other screens (reader, sync, settings) are migrated onto the repository.
 */
class JdbcDocumentRepository(private val db: DatabaseManager) : DocumentRepository {

    override suspend fun upsert(document: SharedDocument) {
        db.saveDocument(document.toEntity())
    }

    override fun getAllDocuments(): Flow<List<SharedDocument>> = flow {
        emit(db.getAllDocuments().map { it.toShared() })
    }

    override suspend fun getAllDocumentsSync(): List<SharedDocument> =
        db.getAllDocuments().map { it.toShared() }

    override suspend fun delete(uri: String): Int {
        // TODO(desktop): implement DELETE FROM documents WHERE uri = ?
        throw NotImplementedError("TODO(desktop): JdbcDocumentRepository.delete")
    }

    override suspend fun updateLastPage(uri: String, pageIndex: Int) {
        // TODO(desktop): UPDATE documents SET lastPageIndex = ? WHERE uri = ?
        throw NotImplementedError("TODO(desktop): JdbcDocumentRepository.updateLastPage")
    }

    override suspend fun renameDocument(uri: String, newName: String) {
        // TODO(desktop): UPDATE documents SET displayName = ? WHERE uri = ?
        throw NotImplementedError("TODO(desktop): JdbcDocumentRepository.renameDocument")
    }

    override suspend fun updateFavoriteStatus(uri: String, isFavorite: Boolean) {
        // TODO(desktop): UPDATE documents SET isFavorite = ? WHERE uri = ?
        throw NotImplementedError("TODO(desktop): JdbcDocumentRepository.updateFavoriteStatus")
    }

    override suspend fun updateFolder(uri: String, folderId: String?) {
        // TODO(desktop): UPDATE documents SET folderId = ? WHERE uri = ?
        throw NotImplementedError("TODO(desktop): JdbcDocumentRepository.updateFolder")
    }

    override suspend fun moveDocumentsToFolder(uris: List<String>, folderId: String?) {
        // TODO(desktop): batch UPDATE
        throw NotImplementedError("TODO(desktop): JdbcDocumentRepository.moveDocumentsToFolder")
    }

    override suspend fun clearFolderAssignmentsInFolders(folderIds: List<String>) {
        // TODO(desktop): UPDATE documents SET folderId = NULL WHERE folderId IN (...)
        throw NotImplementedError("TODO(desktop): JdbcDocumentRepository.clearFolderAssignmentsInFolders")
    }

    override suspend fun updateLastOpenedAt(uri: String, timestamp: Long) {
        // TODO(desktop): UPDATE documents SET lastOpenedAt = ? WHERE uri = ?
        throw NotImplementedError("TODO(desktop): JdbcDocumentRepository.updateLastOpenedAt")
    }

    override suspend fun getLastPageIndex(uri: String): Int? {
        // TODO(desktop): SELECT lastPageIndex FROM documents WHERE uri = ?
        throw NotImplementedError("TODO(desktop): JdbcDocumentRepository.getLastPageIndex")
    }

    // ── Desktop-only helpers (not part of the shared contract) ────────────────

    /** Search by display name or folder name (case-insensitive substring). */
    suspend fun searchDocuments(query: String): List<SharedDocument> {
        if (query.isBlank()) return getAllDocumentsSync()
        return db.searchDocuments(query).map { it.toShared() }
    }

    /** Look up a single document. */
    suspend fun getDocument(uri: String): SharedDocument? =
        db.getDocument(uri)?.toShared()

    /** Stroke count for a document (desktop-only query). */
    fun strokeCountForDocument(uri: String): Int = db.strokeCountForDocument(uri)

    // ── Mapping ──────────────────────────────────────────────────────────────

    private fun DocumentEntity.toShared() = SharedDocument(
        uri = uri,
        displayName = displayName,
        lastOpenedAt = lastOpenedAt,
        lastPageIndex = lastPageIndex,
        isFavorite = isFavorite,
        folderId = folderId
    )

    private fun SharedDocument.toEntity() = DocumentEntity(
        uri = uri,
        displayName = displayName,
        lastOpenedAt = lastOpenedAt,
        lastPageIndex = lastPageIndex,
        isFavorite = isFavorite,
        folderId = folderId
    )
}

/**
 * Desktop (JDBC/SQLite) implementation of the shared [FolderRepository].
 */
class JdbcFolderRepository(private val db: DatabaseManager) : FolderRepository {

    override suspend fun insert(folder: SharedFolder) {
        // TODO(desktop): implement INSERT INTO folders
        throw NotImplementedError("TODO(desktop): JdbcFolderRepository.insert")
    }

    override fun getAllFolders(): Flow<List<SharedFolder>> = flow {
        emit(db.getAllFolders().map { it.toShared() })
    }

    override suspend fun rename(folderId: String, newName: String, updatedAt: Long) {
        // TODO(desktop): UPDATE folders SET name = ?, updatedAt = ? WHERE id = ?
        throw NotImplementedError("TODO(desktop): JdbcFolderRepository.rename")
    }

    override suspend fun updateSortOrder(folderId: String, sortOrder: Int, updatedAt: Long) {
        // TODO(desktop): UPDATE folders SET sortOrder = ?, updatedAt = ? WHERE id = ?
        throw NotImplementedError("TODO(desktop): JdbcFolderRepository.updateSortOrder")
    }

    override suspend fun moveToParent(folderId: String, parentFolderId: String?, sortOrder: Int, updatedAt: Long) {
        // TODO(desktop): UPDATE folders SET parentFolderId = ?, sortOrder = ?, updatedAt = ? WHERE id = ?
        throw NotImplementedError("TODO(desktop): JdbcFolderRepository.moveToParent")
    }

    override suspend fun getNextSortOrder(parentFolderId: String?): Int {
        // TODO(desktop): SELECT COALESCE(MAX(sortOrder),0)+1 FROM folders WHERE parentFolderId IS ?
        throw NotImplementedError("TODO(desktop): JdbcFolderRepository.getNextSortOrder")
    }

    override suspend fun getFolderAndDescendantIds(folderId: String): List<String> {
        // TODO(desktop): recursive CTE
        throw NotImplementedError("TODO(desktop): JdbcFolderRepository.getFolderAndDescendantIds")
    }

    override suspend fun deleteByIds(folderIds: List<String>) {
        // TODO(desktop): DELETE FROM folders WHERE id IN (...)
        throw NotImplementedError("TODO(desktop): JdbcFolderRepository.deleteByIds")
    }

    // ── Desktop-only helper ──────────────────────────────────────────────────

    suspend fun getAllFoldersSync(): List<SharedFolder> =
        db.getAllFolders().map { it.toShared() }

    private fun FolderEntity.toShared() = SharedFolder(
        id = id,
        name = name,
        parentFolderId = parentFolderId,
        sortOrder = sortOrder,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun SharedFolder.toEntity() = FolderEntity(
        id = id,
        name = name,
        parentFolderId = parentFolderId,
        sortOrder = sortOrder,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
