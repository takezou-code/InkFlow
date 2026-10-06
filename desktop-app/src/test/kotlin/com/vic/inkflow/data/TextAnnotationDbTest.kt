package com.vic.inkflow.data

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Text annotations have to survive a round trip through SQLite unchanged, and the
 * migration has to be safe to run on a database that already has notes.
 *
 * These run against a real temporary database file rather than an in-memory one,
 * because `user_version` and `PRAGMA table_info` — the two things the migration
 * actually depends on — behave differently across connection lifetimes, and an
 * in-memory test would pass while the shipped upgrade path broke.
 */
class TextAnnotationDbTest {

    private val files = mutableListOf<Path>()

    private fun newDb(name: String): DatabaseManager {
        val dir = Files.createTempDirectory("inkflow-text-$name")
        files.add(dir)
        val mgr = DatabaseManager(dir.resolve("test.db").toString())
        mgr.connect()
        return mgr
    }

    @AfterTest
    fun cleanup() {
        files.forEach { runCatching { it.toFile().deleteRecursively() } }
    }

    private val ann = TextAnnotationEntity(
        id = "note-1",
        documentUri = "file:///a.pdf",
        pageIndex = 2,
        docY = 1700.5f,
        text = "重點\n第二行",
        modelX = 72f,
        modelY = 700f,
        fontSize = 18f,
        colorArgb = 0xFF112233.toInt(),
        isStamp = true
    )

    @Test
    fun `a text annotation survives a save and read back unchanged`() {
        val db = newDb("roundtrip")
        db.saveTextAnnotation(ann)

        val loaded = db.getAllTextAnnotationsForDocument("file:///a.pdf")
        assertEquals(1, loaded.size)
        with(loaded.single()) {
            assertEquals("note-1", id)
            assertEquals(2, pageIndex)
            assertEquals(1700.5f, docY)
            assertEquals("重點\n第二行", text)
            assertEquals(72f, modelX)
            assertEquals(700f, modelY)
            assertEquals(18f, fontSize)
            assertEquals(0xFF112233.toInt(), colorArgb)
            assertTrue(isStamp)
        }
        db.disconnect()
    }

    @Test
    fun `saving the same id updates in place instead of duplicating`() {
        // The id is how the tablet recognises the note. A second row under a new
        // id would sync as "deleted here, added there".
        val db = newDb("upsert")
        db.saveTextAnnotation(ann)
        db.saveTextAnnotation(ann.copy(text = "改過了", modelX = 99f))

        val all = db.getAllTextAnnotationsForDocument("file:///a.pdf")
        assertEquals(1, all.size, "expected an update, not a second row")
        assertEquals("改過了", all.single().text)
        assertEquals(99f, all.single().modelX)
        assertEquals("note-1", all.single().id, "id must survive the update")
        db.disconnect()
    }

    @Test
    fun `a null docY stays null instead of being invented`() {
        // A guessed stride places notes on the wrong page — the failure is silent,
        // so preserving NULL is load-bearing, not cosmetic.
        val db = newDb("nulldocy")
        db.saveTextAnnotation(ann.copy(id = "pre-v24", docY = null))
        val loaded = db.getAllTextAnnotationsForDocument("file:///a.pdf").single()
        assertNull(loaded.docY)
        db.disconnect()
    }

    @Test
    fun `page filter returns only that page and keeps row order`() {
        val db = newDb("page")
        db.saveTextAnnotation(ann.copy(id = "p2-a"))
        db.saveTextAnnotation(ann.copy(id = "p2-b", text = "second"))
        db.saveTextAnnotation(ann.copy(id = "p3", pageIndex = 3))

        val onPage2 = db.getTextAnnotationsForPage("file:///a.pdf", 2)
        assertEquals(listOf("p2-a", "p2-b"), onPage2.map { it.id }, "rowid order is the draw order")
        assertEquals(1, db.getTextAnnotationsForPage("file:///a.pdf", 3).size)
        db.disconnect()
    }

    @Test
    fun `replace swaps the whole document's notes and deletes the rest`() {
        val db = newDb("replace")
        db.saveTextAnnotation(ann.copy(id = "old-1"))
        db.saveTextAnnotation(ann.copy(id = "old-2"))

        db.replaceTextAnnotationsForDocument(
            "file:///a.pdf",
            listOf(ann.copy(id = "new-1"))
        )

        val all = db.getAllTextAnnotationsForDocument("file:///a.pdf")
        assertEquals(listOf("new-1"), all.map { it.id })
        db.disconnect()
    }

    @Test
    fun `counts and ids reflect only the requested document`() {
        val db = newDb("counts")
        db.saveTextAnnotation(ann.copy(id = "mine-1"))
        db.saveTextAnnotation(ann.copy(id = "mine-2"))
        db.saveTextAnnotation(ann.copy(id = "theirs", documentUri = "file:///b.pdf"))

        assertEquals(2, db.textAnnotationCountForDocument("file:///a.pdf"))
        assertEquals(setOf("mine-1", "mine-2"), db.getTextAnnotationIdsForDocument("file:///a.pdf"))
        db.disconnect()
    }

    @Test
    fun `deleting a document's notes leaves other documents untouched`() {
        val db = newDb("delete")
        db.saveTextAnnotation(ann.copy(id = "a1"))
        db.saveTextAnnotation(ann.copy(id = "b1", documentUri = "file:///b.pdf"))

        db.deleteTextAnnotationsForDocument("file:///a.pdf")

        assertTrue(db.getAllTextAnnotationsForDocument("file:///a.pdf").isEmpty())
        assertEquals(1, db.getAllTextAnnotationsForDocument("file:///b.pdf").size)
        db.disconnect()
    }

    @Test
    fun `the notes table exists after migration on a database that predates it`() {
        // Simulates the shipped upgrade: an old database gets opened and must end
        // up with notes available, without losing the strokes it already had.
        val dir = Files.createTempDirectory("inkflow-migrate")
        files.add(dir)
        val file = dir.resolve("old.db")
        val legacy = java.sql.DriverManager.getConnection("jdbc:sqlite:$file")
        legacy.createStatement().use { s ->
            s.execute("CREATE TABLE documents (uri TEXT PRIMARY KEY, displayName TEXT NOT NULL, lastOpenedAt INTEGER NOT NULL DEFAULT 0, lastPageIndex INTEGER NOT NULL DEFAULT 0, isFavorite INTEGER NOT NULL DEFAULT 0, folderId TEXT)")
            s.execute("CREATE TABLE strokes (id TEXT PRIMARY KEY, documentUri TEXT NOT NULL, pageIndex INTEGER NOT NULL, color INTEGER NOT NULL, strokeWidth REAL NOT NULL, boundsLeft REAL NOT NULL, boundsTop REAL NOT NULL, boundsRight REAL NOT NULL, boundsBottom REAL NOT NULL, isHighlighter INTEGER NOT NULL DEFAULT 0, shapeType TEXT)")
            s.execute("CREATE TABLE points (id INTEGER PRIMARY KEY AUTOINCREMENT, strokeId TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, width REAL NOT NULL DEFAULT 0)")
            s.execute("PRAGMA user_version = 1")
        }
        legacy.close()

        val db = DatabaseManager(file.toString())
        db.connect()
        db.disconnect()

        val after = java.sql.DriverManager.getConnection("jdbc:sqlite:$file")
        val hasNotes = after.createStatement().use { s ->
            s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='text_annotations'").use { it.next() }
        }
        val version = after.createStatement().use { s ->
            s.executeQuery("PRAGMA user_version").use { if (it.next()) it.getInt(1) else -1 }
        }
        val stillHasStrokes = after.createStatement().use { s ->
            s.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='strokes'").use { if (it.next()) it.getInt(1) == 1 else false }
        }
        after.close()

        assertTrue(hasNotes, "migration must create text_annotations")
        assertEquals(3, version, "user_version must advance to SCHEMA_VERSION")
        assertTrue(stillHasStrokes, "migration must not disturb existing tables")
    }
}