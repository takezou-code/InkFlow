package com.vic.inkflow.data.store

/**
 * The file/document boundary — the one thing a platform must provide.
 *
 * ## Why this is an interface and not a utility
 *
 * Every platform stores "a document" somewhere different, and each one has a
 * failure mode the others do not:
 *
 *  - **Android**: a SAF `content://` URI, whose permission can be revoked between
 *    sessions, and whose *file name* has to be queried through the provider
 *    (`OpenableColumns`) — there is no path to read.
 *  - **Windows**: a plain file path, with no permission model at all.
 *  - **iOS**: a security-scoped URL inside the Files app, whose access token
 *    expires and has to be refreshed, and whose file is not in the app's
 *    container at all.
 *
 * Anything above this interface can therefore be written once — the editor, the
 * exporter, the sync server — and it will work on all three. Anything that tries
 * to "just open the file" has to be written three times, and the third one is
 * usually the one nobody tests.
 *
 * ## Why reads are ranges, not "give me the bytes"
 *
 * A document here can be a 300 MB PDF. An `open(): ByteArray` contract would push
 * every caller into loading it whole, and the Android implementation would have to
 * either do that (an OOM waiting to happen) or break the contract.
 *
 * A range read is the same shape the sync protocol already uses for file bodies
 * (`file_data`, 1 MiB chunks), so the platform implementations stream, and the
 * shared code can hash or copy a document without ever holding it in memory.
 *
 * The implementations live in the platform modules:
 * `app/` (SAF), `desktopApp/` (the file system), and — when it exists — the iOS
 * app (security-scoped URLs).
 */
interface DocumentSource {

    /** The documents this source can offer, newest first. */
    suspend fun list(): List<DocumentRef>

    /** The display name for a URI — a file name, or a provider's `DISPLAY_NAME`. */
    fun displayName(uri: String): String

    /** The document's size in bytes, or null when it cannot be determined. */
    suspend fun sizeOf(uri: String): Long?

    /**
     * Reads [length] bytes starting at [offset].
     *
     * @return null when the document cannot be read (the permission was revoked,
     *   the file was deleted, the iOS token expired). A short read at the end of
     *   the document is **not** null — it is simply shorter.
     */
    suspend fun read(uri: String, offset: Long, length: Int): ByteArray?

    /**
     * Writes [bytes] to [uri], replacing what is there.
     *
     * Used by the exporters (a PDF or a PNG the user just made). The bytes are
     * written whole because an export is produced in memory anyway — a document
     * that is *only* ever streamed never comes through here.
     */
    suspend fun write(uri: String, bytes: ByteArray)

    /**
     * Creates a new empty document with [fileName] and returns its URI.
     *
     * The name is a hint: on Android the app owns the file name outright, on
     * Windows the platform may disambiguate, and on iOS the name is proposed to
     * the user. Implementations may return a different name than the one asked
     * for — that is why the caller uses the returned URI and asks
     * [displayName] for the name.
     */
    suspend fun create(fileName: String): String

    /**
     * Copies [sourceUri] into this source's own storage and returns the new URI.
     *
     * **Streaming**: a document can be a 300 MB PDF, and the copy must not
     * materialise it in memory. This is the "the user picked a file" path (the
     * Android SAF picker, the desktop's file dialog, iOS's document picker).
     *
     * The copy is a **copy, not a link**: a link breaks the moment the user moves
     * or deletes the original, and the document then cannot be opened at all —
     * which looks like the app losing the file.
     */
    suspend fun import(sourceUri: String, fileName: String): String
}

/** A document as offered by a [DocumentSource]. */
data class DocumentRef(
    val uri: String,
    val displayName: String,
    val sizeBytes: Long? = null,
    val lastModified: Long? = null
)

/** The document could not be read — a revoked permission, a deleted file, an expired token. */
class DocumentAccessException(message: String, cause: Throwable? = null) : Exception(message, cause)
