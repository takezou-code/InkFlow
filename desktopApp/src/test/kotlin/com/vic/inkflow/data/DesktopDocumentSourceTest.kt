package com.vic.inkflow.data

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The desktop's document source — the file system side of the shared contract.
 *
 * The contract is what the iOS app will have to satisfy too, so the things it
 * promises are worth pinning here: a range read that does not read the whole
 * file, a name that comes from the path, and an import that **copies**.
 */
class DesktopDocumentSourceTest {

    private val files = mutableListOf<Path>()

    private fun newSource(): Pair<DesktopDocumentSource, Path> {
        val dir = Files.createTempDirectory("inkflow-docsrc-")
        files.add(dir)
        val docs = dir.resolve("documents")
        docs.toFile().mkdirs()
        return DesktopDocumentSource(docs.toFile()) to dir
    }

    @AfterTest
    fun cleanup() {
        files.forEach { runCatching { it.toFile().deleteRecursively() } }
    }

    @Test
    fun `a range read returns only the requested bytes`() {
        val (source, dir) = newSource()
        val file = dir.resolve("documents/a.pdf")
        file.toFile().writeBytes(ByteArray(1000) { it.toByte() })

        val slice = runBlocking { source.read("file:///${file.toString().replace('\\', '/')}", 100L, 10) }
        assertEquals(10, slice?.size)
        assertEquals(100.toByte(), slice?.first())
    }

    @Test
    fun `a read past the end is short, not null`() {
        val (source, dir) = newSource()
        val file = dir.resolve("documents/small.pdf")
        file.toFile().writeBytes(ByteArray(10))

        val slice = runBlocking { source.read("file:///${file.toString().replace('\\', '/')}", 0L, 100) }
        assertEquals(10, slice?.size)
    }

    @Test
    fun `a missing document reads as null`() {
        val (source, _) = newSource()
        assertNull(runBlocking { source.read("file:///nope.pdf", 0L, 10) })
        assertNull(runBlocking { source.sizeOf("file:///nope.pdf") })
    }

    @Test
    fun `the display name is the file name`() {
        val (source, dir) = newSource()
        val file = dir.resolve("documents/lecture 01.pdf")
        file.toFile().writeBytes(byteArrayOf(1))
        assertEquals("lecture 01.pdf", source.displayName("file:///${file.toString().replace('\\', '/')}"))
    }

    @Test
    fun `an import copies the file into the app's own directory`() {
        val (source, dir) = newSource()
        val outside = dir.resolve("outside.pdf")
        outside.toFile().writeBytes(byteArrayOf(7, 7, 7))

        val uri = runBlocking {
            source.import("file:///${outside.toString().replace('\\', '/')}", "copied.pdf")
        }

        // A copy, not a link: the original can vanish and the copy must survive.
        outside.toFile().delete()
        val copied = File(uri.removePrefix("file://").removePrefix("/"))
        assertTrue(copied.isFile, "the copy must exist at $uri")
        assertEquals(3, copied.length())
    }
}
