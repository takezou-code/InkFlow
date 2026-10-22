package com.vic.inkflow.data

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The desktop's stroke store — the **mirror** side of the shared contract.
 *
 * What this pins:
 *  - the model round trip (the shared model in, the model's values out) — a field
 *    that is silently dropped in the mapping shows up here as a mismatch;
 *  - that the mirror implements the *storage* half of the contract and nothing
 *    more. It has no version numbers and no tombstones to bump, because the
 *    tablet owns those; an interface that demanded them would have to be
 *    half-implemented, and a half-implemented interface is where "nobody ever
 *    calls this on the desktop" hides until the shared editor core does.
 *
 * It runs against a real temporary database file, like the other database tests:
 * `user_version` and `PRAGMA table_info` behave differently across connection
 * lifetimes, and an in-memory database would pass while the shipped path broke.
 */
class DesktopStrokeStoreTest {

    private val files = mutableListOf<Path>()

    private fun newStore(): DesktopStrokeStore {
        val dir = Files.createTempDirectory("inkflow-store-")
        files.add(dir)
        val mgr = DatabaseManager(dir.resolve("test.db").toString())
        mgr.connect()
        return DesktopStrokeStore(mgr)
    }

    @AfterTest
    fun cleanup() {
        files.forEach { runCatching { it.toFile().deleteRecursively() } }
    }

    private fun stroke(id: String = "s1") = Stroke(
        id = id,
        documentUri = "file:///a.pdf",
        pageIndex = 2,
        docY = 1700.5f,
        color = 0xFF112233.toInt(),
        strokeWidth = 2.5f,
        boundsLeft = 1f,
        boundsTop = 2f,
        boundsRight = 3f,
        boundsBottom = 4f,
        isHighlighter = true,
        toolKind = "HIGHLIGHTER",
        version = 7,
        versionNonce = 11
    )

    private fun point(id: Long, strokeId: String, x: Float, y: Float) = Point(
        id = id,
        strokeId = strokeId,
        x = x,
        y = y,
        width = 2.5f
    )

    @Test
    fun `a stroke and its points survive the round trip`() {
        val store = newStore()
        store.saveStrokeWithPointsNow(
            stroke(),
            listOf(point(1, "s1", 10f, 20f), point(2, "s1", 30f, 40f))
        )

        val back = runBlocking { store.getStrokesForPageSync("file:///a.pdf", 2) }
        assertEquals(1, back.size)
        val s = back.first().stroke
        assertEquals("s1", s.id)
        assertEquals(2, s.pageIndex)
        assertEquals(1700.5f, s.docY)
        assertEquals(0xFF112233.toInt(), s.color)
        assertEquals(2.5f, s.strokeWidth)
        assertEquals(true, s.isHighlighter)
        // The desktop's tables have no `toolKind` column (it was added on the
        // tablet in DB v28 and never mirrored), so the round trip comes back as
        // the default. This is the *known* difference between the two ends —
        // `ModelWireTest` on the desktop side pins the same one field — and it is
        // harmless today because the tablet does not read it either.
        assertEquals("PEN", s.toolKind)
        assertEquals(7, s.version)
        assertEquals(11, s.versionNonce)
        assertEquals(listOf(10f, 30f), back.first().points.map { it.x })
    }

    @Test
    fun `the per page counts match the strokes`() {
        val store = newStore()
        store.saveStrokeWithPointsNow(stroke("s1"), listOf(point(1, "s1", 0f, 0f)))
        store.saveStrokeWithPointsNow(stroke("s2"), listOf(point(2, "s2", 0f, 0f)))

        val counts = runBlocking { store.countStrokesByPage("file:///a.pdf") }
        assertEquals(1, counts.size)
        assertEquals(2, counts.first().pageIndex)
        assertEquals(2, counts.first().strokeCount)
    }

    @Test
    fun `replacing the snapshots replaces the document's strokes`() {
        val store = newStore()
        store.saveStrokeWithPointsNow(stroke("old"), listOf(point(1, "old", 0f, 0f)))
        store.replaceStrokeSnapshotsNow(
            listOf(
                com.vic.inkflow.data.StrokeWithSamples(
                    stroke("new"),
                    listOf(point(2, "new", 5f, 6f))
                )
            )
        )

        val back = runBlocking { store.getAllStrokesForDocument("file:///a.pdf") }
        assertEquals(1, back.size)
        assertEquals("new", back.first().stroke.id)
    }

    @Test
    fun `the mirror deletes what the tablet says is gone`() {
        val store = newStore()
        store.saveStrokeWithPointsNow(stroke("s1"), listOf(point(1, "s1", 0f, 0f)))
        assertEquals(1, runBlocking { store.deleteStrokesByIds(listOf("s1")) })
        assertTrue(runBlocking { store.getAllStrokesForDocument("file:///a.pdf") }.isEmpty())
    }
}
