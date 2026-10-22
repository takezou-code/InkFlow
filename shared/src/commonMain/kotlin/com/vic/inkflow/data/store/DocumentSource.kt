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
 * The implementations live in the platform modules:
 * `app/` (SAF), `desktopApp/` (the file system), and — when it exists — the iOS
 * app (security-scoped URLs).
 */
interface DocumentSource {

    /** The documents this source can offer, newest first. */
    suspend fun list(): List<DocumentRef>

    /**
     * Opens a document for reading.
     *
     * @throws DocumentAccessException when the URI is no longer readable (the
     *   permission was revoked, the file was deleted, the iOS token expired).
     *   Callers must handle this: it is a **normal** condition, not a bug, and it
     *   is the one thing the other platforms never have to think about.
     */
    suspend fun open(uri: String): OpenedDocument

    /** Writes a document's bytes back. */
    suspend fun write(uri: String, bytes: ByteArray)

    /** The display name for a URI — a file name, or a provider's `DISPLAY_NAME`. */
    fun displayName(uri: String): String
}

/** A document as offered by a [DocumentSource]. */
data class DocumentRef(
    val uri: String,
    val displayName: String,
    val sizeBytes: Long? = null,
    val lastModified: Long? = null
)

/** A document's bytes plus what the source knows about it. */
data class OpenedDocument(
    val uri: String,
    val displayName: String,
    val bytes: ByteArray,
    val sizeBytes: Long,
    val lastModified: Long?
) {
    // data class + ByteArray: equals/hashCode would compare the array by
    // reference, which is never what anyone means. Compare the contents.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OpenedDocument) return false
        return uri == other.uri &&
            displayName == other.displayName &&
            sizeBytes == other.sizeBytes &&
            lastModified == other.lastModified &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = uri.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + sizeBytes.hashCode()
        result = 31 * result + (lastModified?.hashCode() ?: 0)
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

/** The document could not be read — a revoked permission, a deleted file, an expired token. */
class DocumentAccessException(message: String, cause: Throwable? = null) : Exception(message, cause)
