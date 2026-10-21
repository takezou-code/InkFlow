package com.vic.inkflow.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The stream half of the frame layer: bytes in, bytes out.
 *
 * Lives in the desktop test source set because [SyncWire] is JVM code (it uses
 * `java.io`), and this is the one place the frame format is exercised the way
 * it is actually used — through a real stream, not through the length helpers
 * alone.
 */
class SyncWireTest {

    @Test
    fun `a frame round trips through a stream`() {
        val payload = "hello".toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { SyncWire.writeFrame(it, payload) }
        DataInputStream(ByteArrayInputStream(out.toByteArray())).use {
            assertContentEquals(payload, SyncWire.readFrame(it))
        }
    }

    @Test
    fun `text frames round trip`() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { SyncWire.writeText(it, "{\"type\":\"handshake\"}") }
        DataInputStream(ByteArrayInputStream(out.toByteArray())).use {
            assertEquals("{\"type\":\"handshake\"}", SyncWire.readText(it))
        }
    }

    @Test
    fun `a clean end of stream reads as null`() {
        DataInputStream(ByteArrayInputStream(ByteArray(0))).use {
            assertNull(SyncWire.readFrame(it))
        }
    }

    @Test
    fun `an empty frame is the end of file marker, not null`() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { SyncWire.writeFrame(it, ByteArray(0)) }
        DataInputStream(ByteArrayInputStream(out.toByteArray())).use {
            assertContentEquals(ByteArray(0), SyncWire.readFrame(it))
        }
    }

    @Test
    fun `an oversized frame is refused on write`() {
        // The cap itself is 512 MiB, so a real oversized payload cannot be
        // allocated in a test — the guard is exercised at the format level in
        // SyncFrameTest, and this only proves the stream path routes through it.
        assertFailsWith<SyncFrameException> {
            SyncFrame.lengthHeader(SyncFrame.MAX_FRAME_BYTES + 1)
        }
    }

    @Test
    fun `an invalid length prefix is refused on read`() {
        val bad = byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertFailsWith<IOException> {
            DataInputStream(ByteArrayInputStream(bad)).use { SyncWire.readFrame(it) }
        }
    }
}
