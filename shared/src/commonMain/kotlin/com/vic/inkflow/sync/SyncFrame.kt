package com.vic.inkflow.sync

/**
 * The frame format of the TCP transfer (§2).
 *
 * ```
 * [4-byte big-endian int: payload length][payload bytes]
 * ```
 *
 * - text frame: the payload is UTF-8 JSON
 * - binary frame: only the `file_data` answer carries raw bytes
 * - length 0 is the EOF marker (no more data for that file)
 *
 * Why length-prefixed instead of newline-delimited JSON: a whole PDF (300 MB and
 * up on the tablet) has to survive the trip, and newline/escaping turns byte-level
 * content into noise.
 *
 * ## Why the length rules live here
 *
 * The two ends had two copies of this rule and they had drifted: the tablet
 * enforced a 512 MiB cap and reported a violation as an `IOException`, the desktop
 * enforced it with `require()` — a different exception type for the same protocol
 * violation, and the desktop had no cap check on the write side at all. The frame
 * layout is the one thing both ends must agree on byte for byte, so it is declared
 * once, here, and both sides call the same functions.
 */
object SyncFrame {

    /** Single-frame ceiling. A longer length is a protocol violation. */
    const val MAX_FRAME_BYTES = 512 * 1024 * 1024

    /**
     * The 4-byte big-endian length prefix for a payload of [payloadSize] bytes.
     *
     * @throws SyncFrameException if the size is negative or above
     *   [MAX_FRAME_BYTES].
     */
    fun lengthHeader(payloadSize: Int): ByteArray {
        if (payloadSize < 0 || payloadSize > MAX_FRAME_BYTES) {
            throw SyncFrameException(
                "Invalid frame length $payloadSize (cap $MAX_FRAME_BYTES)"
            )
        }
        return byteArrayOf(
            (payloadSize ushr 24).toByte(),
            (payloadSize ushr 16).toByte(),
            (payloadSize ushr 8).toByte(),
            payloadSize.toByte()
        )
    }

    /**
     * The payload length encoded in a 4-byte header.
     *
     * @throws SyncFrameException if the header is not 4 bytes or the length it
     *   encodes is negative or above [MAX_FRAME_BYTES].
     */
    fun payloadLength(header: ByteArray): Int {
        if (header.size != 4) {
            throw SyncFrameException("Frame header must be 4 bytes, was ${header.size}")
        }
        val len = ((header[0].toInt() and 0xFF) shl 24) or
            ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)
        if (len < 0 || len > MAX_FRAME_BYTES) {
            throw SyncFrameException("Invalid frame length $len (cap $MAX_FRAME_BYTES)")
        }
        return len
    }
}

/** A frame that violates the protocol. The connection is dropped when this is thrown. */
class SyncFrameException(message: String) : Exception(message)
