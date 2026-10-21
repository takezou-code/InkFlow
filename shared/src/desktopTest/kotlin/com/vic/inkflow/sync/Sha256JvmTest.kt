package com.vic.inkflow.sync

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shared SHA-256, compared against the JDK's implementation.
 *
 * This is the differential test: the JDK is the reference implementation, the
 * shared code is the copy. It runs in the desktop test source set because
 * `commonTest` cannot use `java.security` — and it is the reason the shared
 * digest can be trusted on platforms that have no `java.security` at all.
 *
 * The lengths matter more than the count: SHA-256 pads and blocks at 64 bytes,
 * so the interesting lengths are 0, 1, 55, 56, 63, 64, 65 and the multiples of
 * 64 around them. A digest that is wrong only for one of those produces a
 * `docVersion` mismatch on exactly some documents — the hardest kind of bug to
 * notice, because most documents would still sync.
 */
class Sha256JvmTest {

    private fun jdk(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun payload(length: Int): ByteArray =
        ByteArray(length) { ((it * 37 + 11) and 0xFF).toByte() }

    @Test
    fun `matches the JDK across the block boundaries`() {
        val lengths = listOf(
            0, 1, 2, 3, 31, 32, 54, 55, 56, 57, 63, 64, 65, 119, 120, 127, 128, 129,
            1000, 4096, 65_536, 65_537
        )
        for (length in lengths) {
            val data = payload(length)
            assertEquals(jdk(data), sha256Hex(data), "length $length")
        }
    }

    @Test
    fun `matches the JDK for a megabyte`() {
        val data = payload(1024 * 1024)
        assertEquals(jdk(data), sha256Hex(data))
    }

    @Test
    fun `chunked digest matches the JDK`() {
        val data = payload(300_000)
        val jdkDigest = jdk(data)
        for (chunkSize in listOf(1, 63, 64, 65, 4096, 65_536)) {
            val chunks = data.toList().chunked(chunkSize).asSequence().map { it.toByteArray() }
            assertEquals(jdkDigest, sha256Hex(chunks), "chunk $chunkSize")
        }
    }

    @Test
    fun `the JVM file hash uses the same digest`() {
        // SyncWire hashes files in chunks through the same implementation; a
        // mismatch here would mean the manifest's file digest differs from the
        // one the file hash produces.
        val bytes = payload(5000)
        val file = java.io.File.createTempFile("inkflow-sha", ".bin")
        try {
            file.writeBytes(bytes)
            val viaFile = com.vic.inkflow.sync.SyncWire.sha256Hex(file)
            assertEquals(jdk(bytes), viaFile)
        } finally {
            file.delete()
        }
    }
}
