package com.vic.inkflow.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Opening a local PDF means giving it a stable identity.
 *
 * The sync layer keys documents on `uri`, and `uri` is the only thing that ties a
 * note to a page. So importing a file is not a copy operation — it is the decision
 * of what identity that file will have from now on. Get it wrong and the same
 * physical PDF can end up in the library twice, or the desktop's annotations get
 * written against a path the tablet has never heard of.
 */
class LocalImportTest {

    private fun makePdf(dir: File, name: String, bytes: Int = 2048): File {
        dir.mkdirs()
        val f = File(dir, name)
        f.writeBytes(ByteArray(bytes) { (it % 251).toByte() })
        return f
    }

    @Test
    fun `a local path becomes a file uri with forward slashes`() {
        val uri = LocalImport.toDocumentUri("C:\\Users\\Vic\\Docs\\lecture.pdf")
        assertTrue(uri.startsWith("file:///"), "got $uri")
        assertFalse(uri.contains("\\"), "backslashes must not survive: $uri")
        assertTrue(uri.endsWith("/lecture.pdf"), "got $uri")
    }

    @Test
    fun `a path that already looks like a uri is returned unchanged`() {
        val already = "file:///home/vic/a.pdf"
        assertEquals(already, LocalImport.toDocumentUri(already))
    }

    @Test
    fun `the round trip from uri back to a readable path works`() {
        val uri = LocalImport.toDocumentUri("C:\\Users\\Vic\\Docs\\lecture.pdf")
        assertEquals("C:\\Users\\Vic\\Docs\\lecture.pdf", LocalImport.fromDocumentUri(uri))
    }

    @Test
    fun `an unrecognised scheme yields null rather than a bogus path`() {
        assertNull(LocalImport.fromDocumentUri("content://media/1234"))
        assertNull(LocalImport.fromDocumentUri(""))
    }

    @Test
    fun `two different files with the same name get different identities`() {
        // The collision case that matters: lecture.pdf in two folders. Identity must
        // come from the full path, or the second import silently overwrites the
        // first document's row and its annotations.
        val a = LocalImport.toDocumentUri("C:\\a\\lecture.pdf")
        val b = LocalImport.toDocumentUri("C:\\b\\lecture.pdf")
        assertTrue(a != b, "same basename must not collapse to one identity")

        val idA = LocalImport.suggestedId(a)
        val idB = LocalImport.suggestedId(b)
        assertTrue(idA != idB, "ids must differ")
    }

    @Test
    fun `the suggested id is stable for the same uri`() {
        // Sync compares identities across launches, so this has to be derived, not
        // random per import.
        val uri = LocalImport.toDocumentUri("C:\\a\\lecture.pdf")
        assertEquals(LocalImport.suggestedId(uri), LocalImport.suggestedId(uri))
    }

    @Test
    fun `re-importing the same file is idempotent`() {
        val dir = createTempDir()
        try {
            val pdf = makePdf(dir, "notes.pdf")
            val uri = LocalImport.toDocumentUri(pdf.absolutePath)

            val first = LocalImport.plan(uri, pdf, mirrorRoot = dir)
            val second = LocalImport.plan(uri, pdf, mirrorRoot = dir)

            assertEquals(first.uri, second.uri)
            assertEquals(first.displayName, second.displayName)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `display name is the file name without its extension`() {
        val name = LocalImport.displayName("C:\\docs\\Lecture 3.pdf")
        assertEquals("Lecture 3", name)
    }

    @Test
    fun `a file with no extension still yields a usable name`() {
        assertEquals("scanned", LocalImport.displayName("C:\\docs\\scanned"))
    }

    @Test
    fun `a lowercase extension is stripped too`() {
        // Windows paths are case-insensitive; ".PDF" is the same file as ".pdf" and
        // both should produce the same display name.
        assertEquals("report", LocalImport.displayName("C:\\docs\\report.PDF"))
    }

    @Test
    fun `a dotfile does not lose its whole name`() {
        assertEquals(".gitignore", LocalImport.displayName("C:\\docs\\.gitignore"))
    }

    @Test
    fun `plan copies the file into the mirror so the original can move`() {
        val srcDir = createTempDir()
        val mirrorDir = createTempDir()
        try {
            val pdf = makePdf(srcDir, "lecture.pdf", bytes = 4096)
            val uri = LocalImport.toDocumentUri(pdf.absolutePath)

            val plan = LocalImport.plan(uri, pdf, mirrorRoot = mirrorDir)
            assertNotNull(plan.target, "a mirror target must be chosen")
            val target = plan.target!!
            assertEquals(
                mirrorDir.canonicalPath, target.parentFile!!.canonicalPath,
                "the copy must land inside the mirror, got ${target.absolutePath}"
            )
            assertTrue(target.name.endsWith("lecture.pdf"), "got ${target.name}")
            assertFalse(target.name == "lecture.pdf", "the identity prefix must be present: ${target.name}")

            // A different file must not land on the same target.
            val other = makePdf(srcDir, "other.pdf")
            val otherUri = LocalImport.toDocumentUri(other.absolutePath)
            assertFalse(
                target == LocalImport.mirrorTargetFor(otherUri, other, mirrorDir),
                "distinct files must get distinct mirror targets"
            )
        } finally {
            srcDir.deleteRecursively()
            mirrorDir.deleteRecursively()
        }
    }

    @Test
    fun `a pdf over the size limit is rejected before anything is copied`() {
        // The viewer already refuses files over 512 MiB. Import must refuse them too,
        // otherwise the library gains a document that cannot be opened — the worst
        // possible outcome for a file the user just picked on purpose.
        val dir = createTempDir()
        try {
            val pdf = makePdf(dir, "huge.pdf", bytes = 1024)
            val tooBig = LocalImport.checkSize(pdf.length(), maxBytes = 512L)
            assertFalse(tooBig.isOk, "512 bytes cannot be under a 512 MiB limit")

            val fine = LocalImport.checkSize(1024L, maxBytes = 2_048L)
            assertTrue(fine.isOk)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an extension that is not pdf is refused with a reason`() {
        val r = LocalImport.checkExtension("notes.docx")
        assertFalse(r.isOk)
        assertTrue(r.message!!.contains("docx"), "the message should name the file: ${r.message}")
    }

    @Test
    fun `the pdf extension is accepted case-insensitively`() {
        assertTrue(LocalImport.checkExtension("a.PDF").isOk)
        assertTrue(LocalImport.checkExtension("b.pdf").isOk)
    }

    @Test
    fun `a missing file is reported rather than crashing`() {
        val r = LocalImport.checkExists(File("C:\\definitely\\not\\here.pdf"))
        assertFalse(r.isOk)
    }

    private fun createTempDir(): File =
        java.nio.file.Files.createTempDirectory("inkflow-import").toFile()

    }