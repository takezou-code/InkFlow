package com.vic.inkflow.sync

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * The byte-level half of the sync protocol: frames on a stream, SHA-256 over
 * bytes and over files.
 *
 * ## Why this lives in the shared JVM source set
 *
 * Both of this module's targets are JVMs, so `java.io` and `java.security` are
 * available on both — but `commonMain` cannot name them. The two apps used to
 * carry hand-copied versions of this file, and the copies had already drifted:
 * the tablet enforced the frame cap and reported violations as an `IOException`,
 * the desktop used `require()` and had no cap check on the write side at all;
 * the tablet's `readChunk` rejected a negative offset, the desktop's did not.
 *
 * The frame layout itself is declared in `SyncFrame` (commonMain) so it is visible
 * to any future target; this file is the JVM plumbing around it.
 */
object SyncWire {

    /** Single-frame ceiling. A longer frame is a protocol violation. */
    const val MAX_FRAME_BYTES = SyncFrame.MAX_FRAME_BYTES

    /** Read buffer used while hashing a 300 MB-class file. */
    private const val HASH_BUFFER_BYTES = 256 * 1024

    /**
     * Writes one frame: the 4-byte big-endian length prefix, then the payload.
     *
     * @throws IOException if the payload is larger than [MAX_FRAME_BYTES].
     */
    fun writeFrame(out: DataOutputStream, bytes: ByteArray) {
        val header = try {
            SyncFrame.lengthHeader(bytes.size)
        } catch (e: SyncFrameException) {
            // The tablet used to raise an IOException here and the desktop a
            // require() failure; a caller that handles one of them should not
            // have to learn a third exception type.
            throw IOException(e.message, e)
        }
        out.write(header)
        out.write(bytes)
        out.flush()
    }

    fun writeText(out: DataOutputStream, text: String) =
        writeFrame(out, text.toByteArray(Charsets.UTF_8))

    /**
     * Reads one frame. **A clean EOF returns null**; a truncated length prefix is
     * a broken connection.
     *
     * @throws IOException if the length prefix is invalid or the payload is short.
     */
    fun readFrame(input: DataInputStream): ByteArray? {
        val header = ByteArray(4)
        try {
            input.readFully(header)
        } catch (e: EOFException) {
            return null
        }
        val len = try {
            SyncFrame.payloadLength(header)
        } catch (e: SyncFrameException) {
            throw IOException(e.message, e)
        }
        if (len == 0) return ByteArray(0)
        val buf = ByteArray(len)
        input.readFully(buf)
        return buf
    }

    fun readText(input: DataInputStream): String? =
        readFrame(input)?.toString(Charsets.UTF_8)

    /**
     * SHA-256 of a file, or null when the file is missing.
     *
     * Null matters: the manifest uses it to mark an absent PDF as absent, and the
     * two ends must agree on that spelling.
     *
     * The digest is the shared plain-Kotlin implementation ([Sha256]), fed in
     * chunks so a 300 MB PDF is never held in memory twice. It is the same code
     * every other platform runs, and the golden vectors in `commonTest` pin it
     * against the JDK's implementation.
     */
    fun sha256Hex(file: File): String? {
        if (!file.exists()) return null
        val hasher = Sha256()
        file.inputStream().buffered(HASH_BUFFER_BYTES).use { fis ->
            val buf = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val n = fis.read(buf)
                if (n <= 0) break
                hasher.update(buf, 0, n)
            }
        }
        return hasher.digest().toHex()
    }

    /** SHA-256 of a byte array, lowercase hex. */
    fun sha256Hex(bytes: ByteArray): String = com.vic.inkflow.sync.sha256Hex(bytes)

    /**
     * Reads at most [limit] bytes starting at [offset] without loading the whole
     * file into memory.
     *
     * The desktop asks for 1 MiB at a time, so a 300 MB file is 300 requests.
     * An offset at or past the end returns an empty array — the empty frame is the
     * EOF marker of the file transfer.
     */
    fun readChunk(file: File, offset: Long, limit: Int): ByteArray {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (offset < 0 || offset >= length) return ByteArray(0)
            raf.seek(offset)
            val size = minOf(limit.toLong(), length - offset).toInt()
            if (size <= 0) return ByteArray(0)
            val buf = ByteArray(size)
            raf.readFully(buf)
            return buf
        }
    }
}
