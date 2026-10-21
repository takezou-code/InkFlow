package com.vic.inkflow.sync

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * The frame layout, locked down.
 *
 * The length prefix is the one thing every byte on the wire passes through, on
 * both ends, for every request. A change here is not a compile error on either
 * side — it is a protocol that stops talking to itself, and the symptom is a
 * sync that connects and then silently transfers nothing.
 */
class SyncFrameTest {

    @Test
    fun `length header is big endian`() {
        assertContentEquals(byteArrayOf(0, 0, 0, 1), SyncFrame.lengthHeader(1))
        assertContentEquals(byteArrayOf(0, 0, 1, 0), SyncFrame.lengthHeader(256))
        assertContentEquals(byteArrayOf(1, 2, 3, 4), SyncFrame.lengthHeader(0x01020304))
        assertContentEquals(byteArrayOf(0, 0, 0, 0), SyncFrame.lengthHeader(0))
    }

    @Test
    fun `length header round trips`() {
        for (size in listOf(0, 1, 255, 256, 65_536, 1024 * 1024)) {
            assertEquals(size, SyncFrame.payloadLength(SyncFrame.lengthHeader(size)), "size $size")
        }
    }

    @Test
    fun `payload length rejects a frame above the cap`() {
        val header = byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertFailsWith<SyncFrameException> { SyncFrame.payloadLength(header) }
    }

    @Test
    fun `payload length rejects a negative length`() {
        val header = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertFailsWith<SyncFrameException> { SyncFrame.payloadLength(header) }
    }

    @Test
    fun `length header refuses to build an oversized frame`() {
        assertFailsWith<SyncFrameException> { SyncFrame.lengthHeader(SyncFrame.MAX_FRAME_BYTES + 1) }
        assertFailsWith<SyncFrameException> { SyncFrame.lengthHeader(-1) }
    }

    @Test
    fun `a header of the wrong size is a protocol violation`() {
        assertFailsWith<SyncFrameException> { SyncFrame.payloadLength(ByteArray(3)) }
        assertFailsWith<SyncFrameException> { SyncFrame.payloadLength(ByteArray(5)) }
    }

    @Test
    fun `different sizes produce different headers`() {
        assertNotEquals(
            SyncFrame.lengthHeader(1).toList(),
            SyncFrame.lengthHeader(2).toList()
        )
    }
}
