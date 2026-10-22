package com.vic.inkflow.util

import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop's PDF engine — the contract, on the desktop's side.
 *
 * The PDF is generated in the test (PDFBox writes one) rather than checked in as a
 * fixture: a binary fixture in the repo is a file nobody can review, and a
 * generated one says exactly what was tested.
 *
 * What this pins:
 *  - the page count and the page box come from the *document*, not from a guess;
 *  - "render me a page this many pixels wide" really produces that width — the
 *    contract asks for pixels while PDFBox works in DPI, and the conversion is
 *    where that goes wrong;
 *  - a missing file is `null`, not an exception (a document can vanish mid-session
 *    when the tablet syncs a deletion).
 */
class DesktopPdfEngineTest {

    private val files = mutableListOf<Path>()

    private fun newPdf(): String {
        val dir = Files.createTempDirectory("inkflow-pdfengine-")
        files.add(dir)
        val file = dir.resolve("a.pdf").toFile()
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle.A4))
            doc.save(file)
        }
        return "file://" + file.absolutePath.replace('\\', '/')
    }

    @AfterTest
    fun cleanup() {
        files.forEach { runCatching { it.toFile().deleteRecursively() } }
    }

    @Test
    fun `the engine reports the page count and the page box`() {
        val uri = newPdf()
        val engine = DesktopPdfEngine()

        assertEquals(1, runBlocking { engine.pageCount(uri) })

        val box = runBlocking { engine.pageSize(uri, 0) }
        assertNotNull(box)
        assertEquals(595.28f, box.widthPt, 1f)
        assertEquals(841.89f, box.heightPt, 1f)
    }

    @Test
    fun `a page is rendered at the width that was asked for`() {
        val uri = newPdf()
        val engine = DesktopPdfEngine()

        val bitmap = runBlocking { engine.renderPage(uri, 0, 400) }
        assertNotNull(bitmap)
        // The contract is a width, not a DPI: the desktop's own DPI is clamped
        // (memory budget) and quantised (the raster cache), so the engine scales
        // the result to the width that was asked for.
        assertEquals(400, bitmap.width)
    }

    @Test
    fun `a missing document is null, not a crash`() {
        val engine = DesktopPdfEngine()
        assertNull(runBlocking { engine.renderPage("file:///nope.pdf", 0, 200) })
        assertNull(runBlocking { engine.pageSize("file:///nope.pdf", 0) })
        assertEquals(0, runBlocking { engine.pageCount("file:///nope.pdf") })
    }
}
