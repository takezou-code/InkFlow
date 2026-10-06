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
    /**
     * v3. See [DiscoveryRequest.instanceId] for why this field exists and why it
     * must ship now — it is the one field that cannot be retrofitted later.
     * v2 = length-prefixed frames + manifest diff + SHA-256 + chunked transfer.
     */
    /**
 * v4 = text annotations travel in `document_detail`.
 *
 * The bump is mandatory rather than cosmetic. `document_detail` gained a `texts`
 * field and `docVersion` gained a text count; an older tablet would decode the
 * extra field as absent, compute a hash over a different set of fields, and then
 * disagree with this build about every single document — which looks like a network
 * fault and is very hard to diagnose. The handshake already rejects a version
 * mismatch, so bumping turns a silent corruption into one clear error.
 */
const val PROTOCOL_VERSION = 4
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
    val deviceName: String,
    /**
     * Identity of the SENDER's install of the tablet app (v3).
     *
     * A random UUID generated once per install. The tablet's `documents.uri` is
     * a `file://` path with a random UUID filename inside app-private storage,
     * which is destroyed by a reinstall — so the URI alone cannot distinguish
     * "user deleted this" from "new install". With `instanceId` the desktop can
     * detect a reinstall and drop the previous generation wholesale, and within
     * a generation, absence from a complete manifest means deletion.
     *
     * Needs no Android schema change: the app generates and stores it in
     * SharedPreferences. This is the one protocol field that cannot be added
     * retroactively, because a desktop that already shipped without it has
     * permanent, unrecoverable orphans on disk.
     */
    val instanceId: String? = null
)

data class DiscoveryResponse(
    val app: String = SyncConstants.APP_TAG,
    val type: String = "response",
    val deviceId: String,
    val deviceName: String,
    val role: String,                        // "tablet" | "desktop"
    val ip: String,
    val transferPort: Int = SyncPorts.TRANSFER_PORT,
    val protocolVersion: Int = SyncConstants.PROTOCOL_VERSION,
    /** v3: see [DiscoveryRequest.instanceId]. Echoed so the desktop can record the generation. */
    val instanceId: String? = null
)

// ─── TCP request / response envelopes ────────────────────────────────────────

/**
 * Client -> Server request. Exactly one verb is used per connection phase;
 * the server answers with a single [SyncResponse] (or a binary frame for files).
 */
data class SyncRequest(
    val type: String,                        // see TYPE_* constants
    val deviceId: String,
    /** v3: the tablet's install identity; echoed on every request so the server can scope. */
    val instanceId: String? = null,
    /** Only for TYPE_STROKE_DELTA / TYPE_FILE_META / TYPE_FILE_DATA. */
    val documentUri: String? = null,
    /** Only for TYPE_STROKE_PAGE. */
    val pageIndex: Int? = null,
    /** Only for TYPE_FILE_DATA. */
    val offset: Long? = null,
    val limit: Int? = null,
    /**
     * v3: shared secret from the pairing flow, sent only on TYPE_HANDSHAKE.
     * The server answers with a proof so the desktop can verify the tablet holds
     * the same secret. Compared in constant time on both sides.
     */
    val psk: String? = null
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

/**
 * Answer to TYPE_DOC_MANIFEST: lightweight list for diffing (v3).
 *
 * [docVersion] is the field the desktop actually diffs on. It is a
 * content-addressed hash, deliberately NOT a timestamp:
 *
 *  - `lastOpenedAt` produced false positives (open a document to read it, no
 *    drawing, and the desktop re-pulled the row, every stroke and the whole PDF)
 *    and false negatives (any write path that mutates strokes without touching
 *    `lastOpenedAt` — undo/redo, import, page reorder — left the desktop
 *    permanently stale with no way to self-heal, because the manifest said
 *    "not newer").
 *  - A hash of the actual content cannot do either. It also removes the need
 *    for any clock agreement between the two machines, which matters because
 *    the two devices' clocks are independent.
 *
 * The desktop stores this in its OWN `sync_state` table, so the Android schema
 * is untouched. See SYNC_PROTOCOL.md §6.
 */
data class DocumentManifestEntry(
    val uri: String,
    val displayName: String,
    val lastOpenedAt: Long,
    val strokeCount: Int,
    val filePresent: Boolean,
    val fileSha256: String? = null,
    val fileSize: Long? = null,
    /** v3: content hash of (instanceId, uri, strokeCount, fileSha256, fileSize). */
    val docVersion: String? = null,
    /**
     * v4: number of text annotations. Part of `docVersion`, so adding or removing a
     * note makes the document look changed even when no stroke moved.
     *
     * Defaults to 0 so a v3 payload decodes without error. It must NOT be -1:
     * a negative count would also read as "changed" forever and re-pull the whole
     * document on every pass.
     */
    val textCount: Int = 0
)

/**
 * Answer to TYPE_DOCUMENT_DETAIL: full row, all strokes, and (v4) all notes.
 *
 * Notes ship in the same payload as strokes rather than in a verb of their own.
 * The reason is the deletion model: the manifest is complete and the tablet is
 * the only writer, so the desktop replaces a document's contents wholesale. A
 * separate `text_detail` verb would need its own "seen" bookkeeping for no benefit
 * — the two tables are always fetched together.
 *
 * Defaults to an empty list so a v3 payload still decodes.
 */
data class DocumentDetailPayload(
    val document: Any?,                      // DocumentEntity (serialized by caller)
    val strokes: List<Any>,                  // List<StrokeWithPoints>
    /** v4: List<TextAnnotationEntity>. */
    val texts: List<Any> = emptyList()
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

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /**
     * v3 document version. Content-addressed, so it is correct by construction:
     * identical content always yields an identical string, and any change to the
     * stroke count, the file bytes or the file size changes it.
     *
     * The separator is a NUL byte, which cannot occur inside a file URI or a hex
     * digest. A space would NOT be safe: document URIs routinely contain spaces
     * (".../My Notes.pdf"), and ("/a b", "c") would then collide with
     * ("/a", "b c"). The Android side must use the same separator — see
     * `SyncIdentity.docVersion` in app/src/main/java/com/vic/inkflow/sync/.
     */
fun docVersion(
            instanceId: String,
            uri: String,
            strokeCount: Int,
            fileSha256: String?,
            fileSize: Long?,
            /**
             * v4. Number of text annotations, appended after [fileSize] so the
             * field order stays a prefix-extension rather than a reshuffle.
             *
             * Why the count is enough: a note's coordinates changing while the count
             * stays the same is missed, exactly like moving a single stroke point.
             * The trade-off is identical to the stroke case and for the same reason —
             * the occasional miss costs nothing because the next full snapshot pull
             * corrects it, whereas hashing every coordinate would cost a full document
             * read on every manifest.
             */
            textCount: Int = 0
        ): String = sha256Hex(
            buildString {
                append(instanceId); append(NUL)
                append(uri); append(NUL)
                append(strokeCount); append(NUL)
                append(fileSha256 ?: "-"); append(NUL)
                append(fileSize ?: -1L); append(NUL)
                append(textCount)
            }.toByteArray(Charsets.UTF_8)
        )

    /** Field separator for [docVersion]. Must match the Android implementation. */
    const val NUL: Char = '\u0000'
}
