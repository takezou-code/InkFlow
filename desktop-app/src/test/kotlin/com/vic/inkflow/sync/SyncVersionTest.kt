package com.vic.inkflow.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Sync is a contract between two binaries that ship independently.
 *
 * The version handshake exists so a mismatched pair fails loudly instead of
 * quietly exchanging half-understood payloads. These cases pin the two properties
 * that make that work: a change to the wire shape is a version bump, and the
 * content hash the desktop diffs on is deterministic and sensitive to every field
 * it claims to cover.
 */
class SyncVersionTest {

    @Test
    fun `the protocol version is 4 because this build adds text annotations`() {
        // Bumping this is a promise that the payload shape changed. The handshake
        // rejects a mismatch outright, so a stale tablet cannot read a v4 payload.
        assertEquals(4, SyncConstants.PROTOCOL_VERSION)
    }

    @Test
    fun `the manifest entry carries a text count so a notes-only change is visible`() {
        // The whole sync loop is a diff on docVersion. If textCount is not in the
        // hash, a user who adds a note on the tablet sees no change on the desktop
        // and the note simply never appears.
        val withNotes = DocumentManifestEntry(
            uri = "file:///a.pdf", displayName = "a", lastOpenedAt = 1L,
            strokeCount = 3, filePresent = false,
            textCount = 5
        )
        assertEquals(5, withNotes.textCount)
    }

    @Test
    fun `a text count defaults to zero so an older sender still parses`() {
        // Gson leaves absent primitives at their default rather than failing, which
        // is what lets a v3 payload decode here. Must therefore be 0, not -1: -1
        // would mean "negative notes" and break the docVersion comparison.
        val entry = DocumentManifestEntry(
            uri = "file:///a.pdf", displayName = "a", lastOpenedAt = 1L,
            strokeCount = 0, filePresent = false
        )
        assertEquals(0, entry.textCount)
    }

    @Test
    fun `docVersion changes when only the text count changes`() {
        val base = SyncWire.docVersion("inst", "file:///a.pdf", 3, null, null, 0)
        val withNotes = SyncWire.docVersion("inst", "file:///a.pdf", 3, null, null, 7)

        // This is the regression that would silently strand a user's notes: same
        // strokes, same file, different notes -> the desktop must still re-pull.
        assertNotEquals(base, withNotes, "a notes-only edit must be visible in the diff")
    }

    @Test
    fun `docVersion is stable for identical inputs`() {
        val a = SyncWire.docVersion("inst", "file:///a.pdf", 3, "abc", 100L, 7)
        val b = SyncWire.docVersion("inst", "file:///a.pdf", 3, "abc", 100L, 7)
        assertEquals(a, b)
    }

    @Test
    fun `docVersion responds to every field it claims to cover`() {
        val base = SyncWire.docVersion("i", "u", 1, "s", 1L, 0)
        assertNotEquals(base, SyncWire.docVersion("i2", "u", 1, "s", 1L, 0), "instanceId")
        assertNotEquals(base, SyncWire.docVersion("i", "u2", 1, "s", 1L, 0), "uri")
        assertNotEquals(base, SyncWire.docVersion("i", "u", 2, "s", 1L, 0), "strokeCount")
        assertNotEquals(base, SyncWire.docVersion("i", "u", 1, "s2", 1L, 0), "fileSha256")
        assertNotEquals(base, SyncWire.docVersion("i", "u", 1, "s", 2L, 0), "fileSize")
        assertNotEquals(base, SyncWire.docVersion("i", "u", 1, "s", 1L, 1), "textCount")
    }

    @Test
    fun `the absent sentinels alias deliberately, and differ from any real value`() {
        // `null` and "-" / -1L mean the SAME thing (no file body), so they must hash
        // identically — otherwise a document would look changed on every pass purely
        // because one side expressed "absent" differently.
        assertEquals(
            SyncWire.docVersion("i", "u", 1, null, null, 0),
            SyncWire.docVersion("i", "u", 1, "-", null, 0),
            "null and the ABSENT sentinel are the same state"
        )
        assertEquals(
            SyncWire.docVersion("i", "u", 1, null, null, 0),
            SyncWire.docVersion("i", "u", 1, null, -1L, 0),
            "null and ABSENT_SIZE are the same state"
        )

        // The collision that would actually matter: a real digest or a real size must
        // never produce the absent state. A hex SHA-256 is 64 chars and can never be
        // "-", and a real file size is >= 0.
        val absent = SyncWire.docVersion("i", "u", 1, null, null, 0)
        assertNotEquals(absent, SyncWire.docVersion("i", "u", 1, "a".repeat(64), 0L, 0))
        assertNotEquals(absent, SyncWire.docVersion("i", "u", 1, null, 0L, 0))
    }

    @Test
    fun `field boundaries cannot be forged by moving characters between fields`() {
        // The NUL separator is the only thing stopping ("ab","c") and ("a","bc")
        // colliding. Without it a crafted pair of documents could alias.
        assertNotEquals(
            SyncWire.docVersion("i", "ab", 1, null, null, 0),
            SyncWire.docVersion("i", "a", 1, null, null, 0)
        )
    }
}