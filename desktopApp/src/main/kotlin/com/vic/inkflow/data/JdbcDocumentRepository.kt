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
 * The whole shared [DocumentRepository] contract is implemented against the
 * desktop's SQLite schema. Every method below is a single-column UPDATE (or
 * one small transaction for the batch ones) - never a read-modify-write of the
 * whole row, because the sync path updates the same rows concurrently and a
 * full-row save would resurrect stale columns (see `DatabaseManager`'s
 * "Document field updates" section for the reasoning).
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

    /**
     * Delete a document and everything that belongs to it.
     *
     * The desktop is stricter than the tablet's Room DAO (which only drops the
     * documents row): strokes, text annotations and the queued proposal go
     * with it. On the desktop there is no second copy - the file on disk is
     * the user's, the database is ours - so leaving orphaned strokes behind
     * would mean the ink of a "deleted" document still shows up in every export
     * and in the sync payload.
     *
     * @return 1 if a document was removed, 0 if the uri was unknown.
     */
    override suspend fun delete(uri: String): Int {
        val existed = db.getDocument(uri) != null
        if (existed) db.deleteDocument(uri)
        return if (existed) 1 else 0
    }

    override suspend fun updateLastPage(uri: String, pageIndex: Int) {
        db.updateDocumentLastPage(uri, pageIndex)
    }

    override suspend fun renameDocument(uri: String, newName: String) {
        db.updateDocumentDisplayName(uri, newName)
    }

    override suspend fun updateFavoriteStatus(uri: String, isFavorite: Boolean) {
        db.updateDocumentFavorite(uri, isFavorite)
    }

    override suspend fun updateFolder(uri: String, folderId: String?) {
        db.updateDocumentFolder(uri, folderId)
    }

    override suspend fun moveDocumentsToFolder(uris: List<String>, folderId: String?) {
        db.moveDocumentsToFolder(uris, folderId)
    }

    override suspend fun clearFolderAssignmentsInFolders(folderIds: List<String>) {
        db.clearFolderAssignments(folderIds)
    }

    override suspend fun updateLastOpenedAt(uri: String, timestamp: Long) {
        db.updateDocumentLastOpenedAt(uri, timestamp)
    }

    override suspend fun getLastPageIndex(uri: String): Int? =
        db.getDocumentLastPageIndex(uri)

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
        db.saveFolder(folder.toEntity())
    }

    override fun getAllFolders(): Flow<List<SharedFolder>> = flow {
        emit(db.getAllFolders().map { it.toShared() })
    }

    override suspend fun rename(folderId: String, newName: String, updatedAt: Long) {
        db.renameFolder(folderId, newName, updatedAt)
    }

    override suspend fun updateSortOrder(folderId: String, sortOrder: Int, updatedAt: Long) {
        db.updateFolderSortOrder(folderId, sortOrder, updatedAt)
    }

    /**
     * Move a folder under a new parent (null = top level).
     *
     * A folder may not be moved inside itself or inside one of its own
     * descendants: that would build a cycle, and the recursive "delete this
     * folder and everything below it" walk would then loop forever (SQLite
     * stops the recursion, but the tree is broken in the UI either way).
     */
    override suspend fun moveToParent(
        folderId: String,
        parentFolderId: String?,
        sortOrder: Int,
        updatedAt: Long
    ) {
        if (parentFolderId != null) {
            val descendants = db.folderAndDescendantIds(folderId)
            if (parentFolderId == folderId || parentFolderId in descendants) return
        }
        db.moveFolderToParent(folderId, parentFolderId, sortOrder, updatedAt)
    }

    override suspend fun getNextSortOrder(parentFolderId: String?): Int =
        db.nextFolderSortOrder(parentFolderId)

    override suspend fun getFolderAndDescendantIds(folderId: String): List<String> =
        db.folderAndDescendantIds(folderId)

    /**
     * Delete folders and detach the documents that lived in them.
     *
     * The documents are NOT deleted - they move to "uncategorized". A folder is
     * a label, not a container: the file still sits on the user's disk.
     */
    override suspend fun deleteByIds(folderIds: List<String>) {
        if (folderIds.isEmpty()) return
        db.clearFolderAssignments(folderIds)
        db.deleteFolders(folderIds)
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
