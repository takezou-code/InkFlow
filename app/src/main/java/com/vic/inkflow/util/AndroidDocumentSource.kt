package com.vic.inkflow.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.vic.inkflow.data.store.DocumentAccessException
import com.vic.inkflow.data.store.DocumentRef
import com.vic.inkflow.data.store.DocumentSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * The Android document source — SAF (the Storage Access Framework) plus the app's
 * own private directory.
 *
 * ## The two kinds of URI that live side by side
 *
 *  - **`content://`** — something the user picked through the document picker. The
 *    permission behind it can be **revoked** between sessions (the user clears
 *    "Files and media" permission), and the file name has to be asked of the
 *    provider through `OpenableColumns` — there is no path to read.
 *  - **`file://`** — a document the app itself copied into its private
 *    directory. Always readable, and the app owns the name.
 *
 * Treating them the same is where "the document disappeared" bugs come from, so
 * the two paths are handled separately here.
 *
 * ## Reads are ranges
 *
 * A document can be a 300 MB PDF. [read] streams a range through the content
 * resolver instead of loading the whole thing — the same shape the sync protocol
 * uses for file bodies.
 */
internal class AndroidDocumentSource(
    private val context: Context
) : DocumentSource {

    private val resolver get() = context.contentResolver

    override suspend fun list(): List<DocumentRef> = withContext(Dispatchers.IO) {
        // The app's own documents: the ones it copied in and the ones the sync
        // wrote. The SAF-picked ones are not listed here — the user picks those
        // explicitly, they are not "documents of the app".
        PdfManager.pdfDir(context)
            .listFiles()
            ?.filter { it.isFile && it.name.endsWith(".pdf", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                DocumentRef(
                    uri = Uri.fromFile(file).toString(),
                    displayName = file.name,
                    sizeBytes = file.length(),
                    lastModified = file.lastModified()
                )
            }
            ?: emptyList()
    }

    override fun displayName(uri: String): String {
        val parsed = Uri.parse(uri)
        if (parsed.scheme == "file") return parsed.lastPathSegment ?: uri
        // A provider's display name: the file name is *not* in the URI.
        return runCatching {
            resolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull() ?: parsed.lastPathSegment ?: uri
    }

    override suspend fun sizeOf(uri: String): Long? = withContext(Dispatchers.IO) {
        val parsed = Uri.parse(uri)
        if (parsed.scheme == "file") return@withContext parsed.path?.let { File(it).length() }
        runCatching {
            resolver.query(parsed, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else null
            }
        }.getOrNull()
    }

    override suspend fun read(uri: String, offset: Long, length: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            if (length <= 0) return@withContext ByteArray(0)
            val parsed = Uri.parse(uri)
            try {
                if (parsed.scheme == "file") {
                    val file = parsed.path?.let { File(it) }
                        ?: return@withContext null
                    if (!file.isFile) return@withContext null
                    file.inputStream().use { stream ->
                        if (offset > 0) stream.skip(offset)
                        stream.readNBytes(length)
                    }
                } else {
                    resolver.openInputStream(parsed)?.use { stream ->
                        if (offset > 0) stream.skip(offset)
                        stream.readNBytes(length)
                    }
                }
            } catch (e: Exception) {
                // A revoked permission is a **normal** condition, not a bug: the
                // user cleared "Files and media" access. The shared code decides
                // what to do about it.
                if (e is SecurityException || e is java.io.FileNotFoundException) {
                    throw DocumentAccessException("cannot read $uri: ${e.message}", e)
                }
                null
            }
        }

    override suspend fun write(uri: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val parsed = Uri.parse(uri)
        if (parsed.scheme == "file") {
            val file = parsed.path?.let { File(it) } ?: throw DocumentAccessException("not a file uri: $uri")
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { it.write(bytes) }
        } else {
            resolver.openOutputStream(parsed, "wt")?.use { it.write(bytes) }
                ?: throw DocumentAccessException("cannot write $uri")
        }
    }

    override suspend fun create(fileName: String): String = withContext(Dispatchers.IO) {
        val dir = PdfManager.pdfDir(context)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, fileName)
        Uri.fromFile(file).toString()
    }

    /**
     * Copies a picked file into the app's own directory — **streaming**.
     *
     * A 300 MB PDF must not be read into memory to be copied, so the bytes go
     * from the provider's stream to the file in chunks.
     */
    override suspend fun import(sourceUri: String, fileName: String): String =
        withContext(Dispatchers.IO) {
            val source = Uri.parse(sourceUri)
            val dir = PdfManager.pdfDir(context)
            if (!dir.exists()) dir.mkdirs()
            val dest = File(dir, fileName)
            resolver.openInputStream(source)?.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            } ?: throw DocumentAccessException("cannot read $sourceUri")
            Uri.fromFile(dest).toString()
        }
}
