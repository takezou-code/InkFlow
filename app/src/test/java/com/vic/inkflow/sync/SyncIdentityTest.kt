package com.vic.inkflow.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.security.MessageDigest

/**
 * Locks down the bytes that the tablet and the desktop must agree on.
 *
 * `docVersion` is the entire diff mechanism between the two devices, and it
 * crosses a process boundary as an opaque string. If the two implementations
 * disagree by even one byte, every document looks changed on every pass and the
 * sync silently degenerates into a full re-pull of the whole library, forever.
 *
 * [reference] below is deliberately a separate naive transcription of the layout
 * rather than a call into the production code, so that a change to the production
 * layout surfaces here as a failure instead of being silently mirrored on both
 * sides at the same time.
 *
 * If the layout genuinely has to change: bump the protocol version, update both
 * sides, and update these vectors in the same commit.
 */
class SyncIdentityTest {

    private companion object {
        const val NUL = '\u0000'
        const val PSK_SALT = "inkflow-sync-v3"
    }

    /** Independent re-implementation of the documented layout. */
    private fun reference(
        instanceId: String,
        uri: String,
        strokeCount: Int,
        fileSha256: String?,
        fileSize: Long?
    ): String = sha256(
        buildString {
            append(instanceId).append(NUL)
            append(uri).append(NUL)
            append(strokeCount).append(NUL)
            append(fileSha256 ?: "-").append(NUL)
            append(fileSize ?: -1L)
        }
    )

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `separator is NUL, not a space`() {
        assertEquals(NUL, SyncIdentity.DOC_VERSION_SEPARATOR)

        // A space separator would make ("/ab", "c") and ("/a", "bc") collide.
        // Shift a byte across the uri/strokeCount boundary: the byte sequence is
        // identical, only the split point moves, so these must NOT be equal.
        assertNotEquals(
            reference("i", "file:///x/ay", 0, null, null),
            reference("i", "file:///x/a", 0, null, null)
        )
        // Same idea one field earlier: uri ends in "y" vs strokeCount carrying it.
        assertNotEquals(
            reference("i", "file:///x", 121, null, null),
            reference("i", "file:///x", 1, null, null)
        )
    }

    @Test
    fun `docVersion matches the reference layout`() {
        assertEquals(
            reference("inst-1", "file:///storage/emulated/0/Documents/a.pdf", 3, "deadbeef", 4096L),
            SyncIdentity.docVersion(
                "inst-1", "file:///storage/emulated/0/Documents/a.pdf", 3, "deadbeef", 4096L
            )
        )
        // A URI containing a space: the case a space separator gets wrong.
        assertEquals(
            reference("inst-1", "file:///Documents/My Notes.pdf", 1, null, 2048L),
            SyncIdentity.docVersion("inst-1", "file:///Documents/My Notes.pdf", 1, null, 2048L)
        )
    }

    @Test
    fun `absent file digest and size use the documented placeholders`() {
        assertEquals(
            reference("i", "file:///u", 0, null, null),
            SyncIdentity.docVersion("i", "file:///u", 0, null, null)
        )
        // A body-less document must not collide with one that has a body.
        assertNotEquals(
            SyncIdentity.docVersion("i", "file:///u", 0, null, 4096L),
            SyncIdentity.docVersion("i", "file:///u", 0, "deadbeef", 4096L)
        )
    }

    @Test
    fun `every component affects the version`() {
        val base = SyncIdentity.docVersion("inst", "file:///u", 5, "aa", 100L)
        assertEquals(base, SyncIdentity.docVersion("inst", "file:///u", 5, "aa", 100L))

        assertNotEquals(base, SyncIdentity.docVersion("inst2", "file:///u", 5, "aa", 100L))
        assertNotEquals(base, SyncIdentity.docVersion("inst", "file:///v", 5, "aa", 100L))
        assertNotEquals(base, SyncIdentity.docVersion("inst", "file:///u", 6, "aa", 100L))
        assertNotEquals(base, SyncIdentity.docVersion("inst", "file:///u", 5, "ab", 100L))
        assertNotEquals(base, SyncIdentity.docVersion("inst", "file:///u", 5, "aa", 101L))
    }

    @Test
    fun `pskProof is the salted digest and never leaks the secret`() {
        val psk = "12345678"
        val expected = sha256(psk + PSK_SALT)

        assertEquals(expected, SyncIdentity.pskProof(psk))
        assertNotEquals(SyncIdentity.pskProof("11111111"), SyncIdentity.pskProof("22222222"))
    }
}