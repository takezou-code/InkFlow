package com.vic.inkflow.util

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle

/**
 * Open-document cache: parsing the whole file on every call made opening one
 * N-page document parse it 2N+1 times. These tests pin the reuse behaviour —
 * repeated reads parse once, a replaced file reopens, and the cache stays
 * bounded — without rasterising (no Skia in unit tests).
 */
class PdfDocumentCacheTest {

    private fun makePdf(pages: Int): File {
        val f = Files.createTempFile("inkflow-cache", ".pdf").toFile()
        PDDocument().use { doc ->
            repeat(pages) { doc.addPage(PDPage(PDRectangle.A4)) }
            doc.save(f)
        }
        return f
    }

    private fun pageCountOf(uri: String, page: Int = 0): Int {
        val info = PdfManager.readPageInfo(uri, page)
        assertTrue(info is PdfResult.Ok, "readPageInfo failed: ${(info as? PdfResult.Err)?.message}")
        return info.value.pageCount
    }

    @Test
    fun repeatedReadsParseOnce() {
        val f = makePdf(2)
        try {
            PdfManager.resetDocumentOpensForTest()
            val uri = "file://${f.absolutePath}"
            repeat(3) { assertEquals(2, pageCountOf(uri)) }
            val sizes = PdfManager.readPageSizes(uri)
            assertTrue(sizes is PdfResult.Ok)
            assertEquals(2, sizes.value.size)
            assertEquals(1, PdfManager.documentOpens)
        } finally {
            PdfManager.closeOpenDocuments()
            f.delete()
        }
    }

    @Test
    fun replacedFileReopens() {
        val f = makePdf(1)
        try {
            PdfManager.resetDocumentOpensForTest()
            val uri = "file://${f.absolutePath}"
            assertEquals(1, pageCountOf(uri))
            assertEquals(1, PdfManager.documentOpens)
            // Same path, different bytes: the stamp must miss and reopen.
            // Closed first so the rewrite never races the open handle.
            PdfManager.closeOpenDocuments()
            PDDocument().use { doc ->
                repeat(2) { doc.addPage(PDPage(PDRectangle.A4)) }
                doc.save(f)
            }
            assertEquals(2, pageCountOf(uri))
            assertEquals(2, PdfManager.documentOpens)
        } finally {
            PdfManager.closeOpenDocuments()
            f.delete()
        }
    }

    @Test
    fun cacheStaysBounded() {
        val files = (1..6).map { makePdf(1) }
        try {
            PdfManager.resetDocumentOpensForTest()
            val uris = files.map { "file://${it.absolutePath}" }
            uris.forEach { assertEquals(1, pageCountOf(it)) }
            assertEquals(6, PdfManager.documentOpens)
            // Capacity is 4, so the first file was evicted: reading it reopens.
            assertEquals(1, pageCountOf(uris.first()))
            assertEquals(7, PdfManager.documentOpens)
        } finally {
            PdfManager.closeOpenDocuments()
            files.forEach { it.delete() }
        }
    }
}
