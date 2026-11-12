package com.vic.inkflow.data

import com.vic.inkflow.data.Document as SharedDocument
import com.vic.inkflow.data.Folder as SharedFolder
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared `DocumentRepository` / `FolderRepository` contract on the desktop.
 *
 * These used to be `NotImplementedError` stubs on the desktop - the shared
 * interfaces had methods the JDBC implementation never implemented, so any
 * screen that used them (rename, favourite, folders, "last page I was on")
 * would have crashed the moment it called them.
 *
 * Two things are worth pinning down here, beyond "it does not throw":
 *
 *  1. **Single-column updates.** The sync path writes the same rows at the
 *     same time. A rename that rewrote the whole row would put back a stale
 *     `lastPageIndex` the tablet had already moved on. So every field update
 *     must leave the other columns exactly as they were.
 *  2. **Folders are labels, not containers.** Deleting a folder detaches the
 *     documents; it must never delete them.
 */
class JdbcRepositoryTest {

    private val files = mutableListOf<Path>()

    private fun newDb(name: String): Pair<DatabaseManager, JdbcDocumentRepository> {
        val dir = Files.createTempDirectory("inkflow-repo-$name")
        files.add(dir)
        val mgr = DatabaseManager(dir.resolve("test.db").toString())
        mgr.connect()
        return mgr to JdbcDocumentRepository(mgr)
    }

    @AfterTest
    fun cleanup() {
        files.forEach { runCatching { it.toFile().deleteRecursively() } }
    }

    private fun doc(
        uri: String = "file:///a.pdf",
        name: String = "A.pdf",
        lastPage: Int = 0,
        favorite: Boolean = false,
        folderId: String? = null,
        lastOpenedAt: Long = 1000L
    ) = SharedDocument(
        uri = uri,
        displayName = name,
        lastOpenedAt = lastOpenedAt,
        lastPageIndex = lastPage,
        isFavorite = favorite,
        folderId = folderId
    )

    private fun folder(
        id: String,
        name: String = id,
        parent: String? = null,
        sortOrder: Int = 0
    ) = SharedFolder(
        id = id,
        name = name,
        parentFolderId = parent,
        sortOrder = sortOrder,
        createdAt = 1L,
        updatedAt = 1L
    )

    // ── documents ────────────────────────────────────────────────────────────

    @Test
    fun `a renamed document keeps every other column`() = runBlocking {
        val (db, repo) = newDb("rename")
        repo.upsert(doc(lastPage = 7, favorite = true, lastOpenedAt = 4242L))

        repo.renameDocument("file:///a.pdf", "B.pdf")

        val after = db.getDocument("file:///a.pdf")!!
        assertEquals("B.pdf", after.displayName)
        assertEquals(7, after.lastPageIndex)
        assertTrue(after.isFavorite)
        assertEquals(4242L, after.lastOpenedAt)
    }

    @Test
    fun `favourite and last page can be toggled`() = runBlocking {
        val (db, repo) = newDb("favorite")
        repo.upsert(doc())

        repo.updateFavoriteStatus("file:///a.pdf", true)
        repo.updateLastPage("file:///a.pdf", 12)

        val after = db.getDocument("file:///a.pdf")!!
        assertTrue(after.isFavorite)
        assertEquals(12, after.lastPageIndex)
        assertEquals(12, repo.getLastPageIndex("file:///a.pdf"))

        repo.updateFavoriteStatus("file:///a.pdf", false)
        assertFalse(db.getDocument("file:///a.pdf")!!.isFavorite)
    }

    @Test
    fun `an unknown document has no last page`() = runBlocking {
        val (_, repo) = newDb("nolastpage")
        assertNull(repo.getLastPageIndex("file:///nope.pdf"))
    }

    @Test
    fun `last opened at is written as given`() = runBlocking {
        val (db, repo) = newDb("lastopened")
        repo.upsert(doc(lastOpenedAt = 1L))

        repo.updateLastOpenedAt("file:///a.pdf", 99_999L)

        assertEquals(99_999L, db.getDocument("file:///a.pdf")!!.lastOpenedAt)
    }

    @Test
    fun `documents can be moved into a folder in one batch`() = runBlocking {
        val (db, repo) = newDb("move")
        repo.upsert(doc(uri = "file:///a.pdf"))
        repo.upsert(doc(uri = "file:///b.pdf"))

        repo.moveDocumentsToFolder(listOf("file:///a.pdf", "file:///b.pdf"), "f1")

        assertEquals("f1", db.getDocument("file:///a.pdf")!!.folderId)
        assertEquals("f1", db.getDocument("file:///b.pdf")!!.folderId)

        repo.moveDocumentsToFolder(listOf("file:///a.pdf"), null)
        assertNull(db.getDocument("file:///a.pdf")!!.folderId)
    }

    @Test
    fun `clearing a folder detaches its documents but keeps them`() = runBlocking {
        val (db, repo) = newDb("clear")
        repo.upsert(doc(folderId = "f1"))
        repo.upsert(doc(uri = "file:///b.pdf", folderId = "f2"))

        repo.clearFolderAssignmentsInFolders(listOf("f1", "f2"))

        assertNull(db.getDocument("file:///a.pdf")!!.folderId)
        assertNull(db.getDocument("file:///b.pdf")!!.folderId)
        // still there
        assertEquals(2, repo.getAllDocumentsSync().size)
    }

    @Test
    fun `deleting a document reports whether it existed`() = runBlocking {
        val (db, repo) = newDb("delete")
        repo.upsert(doc())

        assertEquals(1, repo.delete("file:///a.pdf"))
        assertNull(db.getDocument("file:///a.pdf"))
        assertEquals(0, repo.delete("file:///a.pdf"))
    }

    // ── folders ──────────────────────────────────────────────────────────────

    @Test
    fun `a folder can be inserted, renamed and reordered`() = runBlocking {
        val (db, repo) = newDb("folders")
        val folders = JdbcFolderRepository(db)

        folders.insert(folder("f1", "物理"))
        folders.rename("f1", "物理學", updatedAt = 5L)
        folders.updateSortOrder("f1", 3, updatedAt = 6L)

        val after = db.getAllFolders().single()
        assertEquals("物理學", after.name)
        assertEquals(3, after.sortOrder)
        assertEquals(6L, after.updatedAt)
    }

    @Test
    fun `the next sort order is max plus one per parent`() = runBlocking {
        val (db, repo) = newDb("sortorder")
        val folders = JdbcFolderRepository(db)

        assertEquals(1, folders.getNextSortOrder(null))
        folders.insert(folder("f1", sortOrder = 1))
        folders.insert(folder("f2", sortOrder = 5))
        assertEquals(6, folders.getNextSortOrder(null))

        // a child of f1 has its own sequence
        folders.insert(folder("c1", parent = "f1", sortOrder = 2))
        assertEquals(3, folders.getNextSortOrder("f1"))
    }

    @Test
    fun `descendants are found recursively`() = runBlocking {
        val (db, repo) = newDb("descendants")
        val folders = JdbcFolderRepository(db)

        folders.insert(folder("root"))
        folders.insert(folder("child", parent = "root"))
        folders.insert(folder("grandchild", parent = "child"))
        folders.insert(folder("other"))

        val ids = folders.getFolderAndDescendantIds("root")
        assertEquals(setOf("root", "child", "grandchild"), ids.toSet())
        assertEquals(listOf("other"), folders.getFolderAndDescendantIds("other"))
    }

    @Test
    fun `a folder cannot be moved inside its own descendant`() = runBlocking {
        val (db, repo) = newDb("cycle")
        val folders = JdbcFolderRepository(db)

        folders.insert(folder("root"))
        folders.insert(folder("child", parent = "root"))

        folders.moveToParent("root", "child", sortOrder = 0, updatedAt = 1L)

        // unchanged: root is still a top-level folder
        assertNull(db.getAllFolders().first { it.id == "root" }.parentFolderId)
    }

    @Test
    fun `moving a folder under a parent works`() = runBlocking {
        val (db, repo) = newDb("movefolder")
        val folders = JdbcFolderRepository(db)

        folders.insert(folder("parent"))
        folders.insert(folder("child"))

        folders.moveToParent("child", "parent", sortOrder = 2, updatedAt = 9L)

        val moved = db.getAllFolders().first { it.id == "child" }
        assertEquals("parent", moved.parentFolderId)
        assertEquals(2, moved.sortOrder)
    }

    @Test
    fun `deleting a folder detaches the documents inside it`() = runBlocking {
        val (db, repo) = newDb("deletefolder")
        val folders = JdbcFolderRepository(db)

        folders.insert(folder("f1"))
        repo.upsert(doc(folderId = "f1"))

        folders.deleteByIds(listOf("f1"))

        assertTrue(db.getAllFolders().isEmpty())
        val orphan = db.getDocument("file:///a.pdf")!!
        assertNull(orphan.folderId)
        // the document itself survives: a folder is a label, not a container
        assertEquals(1, repo.getAllDocumentsSync().size)
    }
}
