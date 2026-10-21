package com.vic.inkflow.sync

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

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
     */
    fun sha256Hex(file: File): String? {
        if (!file.exists()) return null
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(HASH_BUFFER_BYTES).use { fis ->
            val buf = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val n = fis.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().toHex()
    }

    /** SHA-256 of a byte array, lowercase hex. */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

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

    private fun ByteArray.toHex(): String {
        val out = CharArray(size * 2)
        for (i in indices) {
            val v = this[i].toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

/**
 * SHA-256 of a byte array, lowercase hex.
 *
 * The single implementation behind both [SyncIdentity]'s protocol hashes and
 * [SyncWire.sha256Hex]; `commonMain` cannot name `java.security`, so it declares
 * this as `expect` and the JVM source set provides the `actual`.
 */
internal actual fun sha256Hex(bytes: ByteArray): String = SyncWire.sha256Hex(bytes)
