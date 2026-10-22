package com.vic.inkflow.data

import com.vic.inkflow.data.store.DocumentAccessException
import com.vic.inkflow.data.store.DocumentRef
import com.vic.inkflow.data.store.DocumentSource
import java.io.File
import java.io.RandomAccessFile

/**
 * The desktop's document source — the file system, no permission model at all.
 *
 * ## What is different from the other platforms
 *
 * Nothing enforces a permission here, so two things that are hard elsewhere are
 * easy:
 *
 *  - a document's URI **is** its path, and the name comes from the path;
 *  - copying a file in is a plain byte copy, with no "revoked permission" case
 *    to handle.
 *
 * The interface still asks for ranges rather than "the whole file": a 300 MB PDF
 * must not be read into memory, and the desktop is the platform where that is
 * most tempting (it is a file, after all).
 */
internal class DesktopDocumentSource(
    /** Where the app's documents live (`~/.inkflow/documents`). */
    private val documentsDir: File
) : DocumentSource {

    override suspend fun list(): List<DocumentRef> =
        documentsDir.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                DocumentRef(
                    uri = file.toUriString(),
                    displayName = file.name,
                    sizeBytes = file.length(),
                    lastModified = file.lastModified()
                )
            }
            ?: emptyList()

    override fun displayName(uri: String): String = fileOf(uri).name

    override suspend fun sizeOf(uri: String): Long? {
        val file = fileOf(uri)
        return if (file.isFile) file.length() else null
    }

    override suspend fun read(uri: String, offset: Long, length: Int): ByteArray? {
        if (length <= 0) return ByteArray(0)
        val file = fileOf(uri)
        if (!file.isFile) return null
        return RandomAccessFile(file, "r").use { raf ->
            if (offset > 0) raf.seek(offset)
            val buffer = ByteArray(length)
            val read = raf.read(buffer)
            if (read <= 0) ByteArray(0) else if (read == length) buffer else buffer.copyOf(read)
        }
    }

    override suspend fun write(uri: String, bytes: ByteArray) {
        val file = fileOf(uri)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    override suspend fun create(fileName: String): String {
        if (!documentsDir.exists()) documentsDir.mkdirs()
        return File(documentsDir, fileName).toUriString()
    }

    /**
     * Copies a file the user picked into the app's own directory.
     *
     * A **copy**, not a link: a link breaks the moment the user moves or deletes
     * the original, and the document then cannot be opened at all — which looks
     * like the app losing the file.
     */
    override suspend fun import(sourceUri: String, fileName: String): String {
        val source = fileOf(sourceUri)
        if (!source.isFile) throw DocumentAccessException("not a file: $sourceUri")
        if (!documentsDir.exists()) documentsDir.mkdirs()
        val dest = File(documentsDir, fileName)
        source.copyTo(dest, overwrite = true)
        return dest.toUriString()
    }

    private fun fileOf(uri: String): File =
        File(uri.removePrefix("file://").removePrefix("/"))

    private fun File.toUriString(): String = "file:///${path.replace('\\', '/')}"
}
