package com.vic.inkflow.sync

import java.security.MessageDigest

/**
 * InkFlow Local LAN Sync Protocol v2.
 *
 * Transport design:
 *  - Discovery: UDP broadcast on [SyncPorts.DISCOVERY_PORT] ("who is there?" / "I am here").
 *  - Data transfer: TCP socket on [SyncPorts.TRANSFER_PORT].
 *    Every message on the wire is a length-prefixed UTF-8 JSON frame:
 *      [4-byte big-endian payload length][payload bytes]
 *    Length framing (instead of line-based JSON) lets us safely stream large
 *    binary payloads such as whole PDF files.
 *
 * Conflict resolution:
 *  - Documents are compared by lastOpenedAt (the Android Room schema has no
 *    dedicated lastModified column; lastOpenedAt is updated on every edit/open,
 *    so it doubles as the modification timestamp). Newer wins.
 *  - Strokes are document-scoped snapshots: if the remote document is newer,
 *    the desktop pulls ALL strokes for that document and replaces its local set.
 *  - File bodies are transferred only when missing locally or when the remote
 *    checksum differs. Transferred PDFs are stored under documents/<uri-filename>,
 *    mirroring the Android layout where PdfManager.copyPdfToAppDir() saves files
 *    into the app-private Documents directory and records a file:// URI.
 */
object SyncPorts {
    const val DISCOVERY_PORT = 53530
    const val TRANSFER_PORT = 53531
}

object SyncConstants {
    const val PROTOCOL_VERSION = 2
    const val APP_TAG = "InkFlow"
    const val DOCUMENTS_SUBDIR = "documents"
}

/** Role used in discovery messages. */
enum class DeviceRole { TABLET, DESKTOP }

// ─── Discovery (UDP) ─────────────────────────────────────────────────────────

data class DiscoveryRequest(
    val app: String = SyncConstants.APP_TAG,
    val type: String = "discover",           // "discover" = Who is InkFlow Tablet?
    val requesterRole: String,               // "tablet" | "desktop"
    val deviceId: String,
    val deviceName: String
)

data class DiscoveryResponse(
    val app: String = SyncConstants.APP_TAG,
    val type: String = "response",
    val deviceId: String,
    val deviceName: String,
    val role: String,                        // "tablet" | "desktop"
    val ip: String,
    val transferPort: Int = SyncPorts.TRANSFER_PORT,
    val protocolVersion: Int = SyncConstants.PROTOCOL_VERSION
)

// ─── TCP request / response envelopes ────────────────────────────────────────

/**
 * Client -> Server request. Exactly one verb is used per connection phase;
 * the server answers with a single [SyncResponse] (or a binary frame for files).
 */
data class SyncRequest(
    val type: String,                        // see TYPE_* constants
    val deviceId: String,
    /** Only for TYPE_STROKE_DELTA / TYPE_FILE_META / TYPE_FILE_DATA. */
    val documentUri: String? = null,
    /** Only for TYPE_STROKE_PAGE. */
    val pageIndex: Int? = null,
    /** Only for TYPE_FILE_DATA. */
    val offset: Long? = null,
    val limit: Int? = null
) {
    companion object {
        const val TYPE_HANDSHAKE = "handshake"
        const val TYPE_DOC_MANIFEST = "doc_manifest"
        const val TYPE_DOCUMENT_DETAIL = "document_detail"
        const val TYPE_STROKE_DELTA = "stroke_delta"
        const val TYPE_STROKE_PAGE = "stroke_page"
        const val TYPE_FILE_META = "file_meta"
        const val TYPE_FILE_DATA = "file_data"
    }
}

/**
 * Server -> client response envelope.
 * For text responses [payload] carries the JSON body; for binary frames
 * [binary] is true and raw bytes follow the header on the wire.
 */
data class SyncResponse(
    val type: String,                        // mirrors request type, or "error" / "ack"
    val ok: Boolean = true,
    val error: String? = null,
    val payload: String? = null              // JSON-encoded body (see Payloads below)
)

// ─── JSON payload bodies ─────────────────────────────────────────────────────

/** Answer to TYPE_DOC_MANIFEST: lightweight list for diffing. */
data class DocumentManifestEntry(
    val uri: String,
    val displayName: String,
    val lastOpenedAt: Long,
    val strokeCount: Int,
    val filePresent: Boolean,
    val fileSha256: String? = null,
    val fileSize: Long? = null
)

/** Answer to TYPE_DOCUMENT_DETAIL: full row + all strokes with points. */
data class DocumentDetailPayload(
    val document: Any?,                      // DocumentEntity (serialized by caller)
    val strokes: List<Any>                   // List<StrokeWithPoints>
)

/** Answer to TYPE_STROKE_DELTA / TYPE_STROKE_PAGE. */
data class StrokeDeltaPayload(
    val documentUri: String,
    val strokes: List<Any>,                  // List<StrokeWithPoints>
    val deletedStrokeIds: List<String> = emptyList(),
    val hasMore: Boolean = false,
    val nextOffset: Int = 0
)

/** Answer to TYPE_FILE_META. */
data class FileMetaPayload(
    val exists: Boolean,
    val size: Long = 0,
    val sha256: String? = null
)

// ─── Wire helpers ────────────────────────────────────────────────────────────

object SyncWire {

    fun writeFrame(out: java.io.DataOutputStream, bytes: ByteArray) {
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    fun writeText(out: java.io.DataOutputStream, text: String) =
        writeFrame(out, text.toByteArray(Charsets.UTF_8))

    /** Returns null at clean EOF. */
    fun readFrame(input: java.io.DataInputStream): ByteArray? {
        val len = try {
            input.readInt()
        } catch (e: java.io.EOFException) {
            return null
        }
        require(len in 0 until 512 * 1024 * 1024) { "Invalid frame length $len" }
        val buf = ByteArray(len)
        input.readFully(buf)
        return buf
    }

    fun readText(input: java.io.DataInputStream): String? =
        readFrame(input)?.toString(Charsets.UTF_8)

    fun sha256Hex(file: java.io.File): String? {
        if (!file.exists()) return null
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = fis.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
