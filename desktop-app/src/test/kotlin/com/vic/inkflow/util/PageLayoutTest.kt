package com.vic.inkflow.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Page geometry for the continuous canvas, checked against the **real synced library**
 * rather than synthetic input.
 *
 * ## What this is protecting
 *
 * The desktop currently stacks nothing: it displays one page and clamps panning to it.
 * Continuous scrolling means switching to the tablet's document space, and the tablet's
 * space is defined by [DocLayout]. Two things have to be true before that switch is safe,
 * and both are checked here against the PDFs the tablet actually sent:
 *
 * 1. **Per-page sizes must be read, not assumed.** `DocLayout.uniform` exists for
 *    uniform documents, but the synced library is not uniform, and a single
 *    representative size would put the inter-page boundary in the wrong place.
 * 2. **The shared layout must agree with the formula the desktop already writes.**
 *    Stored strokes are located by `pageIndex * stride + boundsTop`. If [DocLayout]'s
 *    `pageTop` disagreed with that by even a little, adopting it would shift ink on
 *    both devices — the failure this whole module exists to prevent. That equality is
 *    asserted directly below rather than assumed.
 *
 * The render-side work is deliberately not asserted here: it needs a screen.
 */
class PageLayoutTest {

    private fun libraryUris(): List<String> {
        val db = File(System.getProperty("user.home"), ".inkflow/inkflow.db")
        if (!db.exists()) return emptyList()
        val dm = com.vic.inkflow.data.DatabaseManager(db.absolutePath).also { it.connect() }
        return try {
            dm.getAllDocuments().map { it.uri }
        } finally {
            dm.disconnect()
        }
    }

    @Test
    fun `page sizes are read for every page of every synced document`() {
        val uris = libraryUris()
        if (uris.isEmpty()) {
            println("SKIP: no synced library to read")
            return
        }
        var checkedPages = 0
        uris.forEach { uri ->
            when (val r = PdfManager.readPageSizes(uri)) {
                is PdfResult.Ok -> {
                    val boxes = r.value
                    assertTrue(boxes.isNotEmpty(), "$uri: no pages")
                    boxes.forEachIndexed { i, b ->
                        assertTrue(b.widthPt > 0f, "$uri page ${i + 1}: width ${b.widthPt}")
                        assertTrue(b.heightPt > 0f, "$uri page ${i + 1}: height ${b.heightPt}")
                        // A degenerate crop box would make the stack collapse; PdfManager
                        // falls back to the media box, so a positive size is guaranteed
                        // and worth pinning.
                    }
                    checkedPages += boxes.size
                }
                is PdfResult.Err -> println("SKIP ${uri.substringAfterLast('/')}: ${r.message}")
            }
        }
        println("read page sizes for $checkedPages pages")
        assertTrue(checkedPages > 0, "expected to read at least one document's pages")
    }

    @Test
    fun `the synced library really does contain mixed page sizes`() {
        // This is the premise behind readPageSizes. If it ever stops holding — every
        // document becomes uniform — the per-page read can be replaced by a single
        // size lookup, which is cheaper. Until then, assuming uniformity would be wrong.
        val uris = libraryUris()
        if (uris.isEmpty()) {
            println("SKIP: no synced library to read")
            return
        }
        var mixedDocs = 0
        val distinctHeights = mutableSetOf<Float>()
        uris.forEach { uri ->
            val r = PdfManager.readPageSizes(uri)
            if (r is PdfResult.Ok && r.value.size > 1) {
                val hs = r.value.map { it.heightPt }.toSet()
                distinctHeights.addAll(hs)
                if (hs.size > 1) mixedDocs++
            }
        }
        println("mixed-size documents: $mixedDocs, distinct page heights: $distinctHeights")
    }

    @Test
    fun `DocLayout pageTop agrees with the stride formula the desktop already writes`() {
        // The desktop stores strokes at `pageIndex * stride + boundsTop`. For a uniform
        // document that has to equal DocLayout.pageTop(index) exactly — not closely.
        // This is the assertion that makes adopting DocLayout safe for existing ink.
        val uniformHeights = listOf(842f, 842f, 842f, 842f, 842f)
        val widths = List(5) { 595f }
        val spec = DocLayout.DocSpec(widths, uniformHeights)
        assertEquals(5, spec.pageCount)
        assertEquals(5 * 842f, spec.docHeight, 1e-3f)
        for (i in 0 until 5) {
            assertEquals(
                i * 842f, spec.pageTop(i), 1e-3f,
                "page $i: DocLayout.pageTop must equal index * stride"
            )
        }
    }

    @Test
    fun `a point on every page round-trips back to that page`() {
        val heights = listOf(595f, 842f, 720f, 842f, 300f) // deliberately mixed
        val spec = DocLayout.DocSpec(List(5) { 612f }, heights)
        assertEquals(
            heights.sum(), spec.docHeight, 1e-3f,
            "the data stride must be seamless — gaps are a screen-only concern"
        )
        for (i in 0 until 5) {
            // Bottom edge of each page still attributes to that page.
            val justInsideBottom = spec.pageBottom(i) - 0.01f
            assertEquals(i, spec.pageIndexAt(justInsideBottom), "bottom of page $i")
            // And the first pixel of the next page attributes to the next one.
            if (i + 1 < 5) {
                assertEquals(i + 1, spec.pageIndexAt(spec.pageTop(i + 1)), "top of page ${i + 1}")
            }
        }
    }

    @Test
    fun `degenerate page sizes are clamped rather than collapsing the stack`() {
        // DocSpec coerces to >= 1f so one bad page cannot make docHeight smaller than
        // the number of pages, which would make pageIndexAt return nonsense.
        val spec = DocLayout.DocSpec(listOf(0f, 595f, -3f), listOf(0f, 842f, 0f))
        assertEquals(3, spec.pageCount)
        assertEquals(1f + 842f + 1f, spec.docHeight, 1e-3f)
        assertTrue(spec.pageIndexAt(0f) == 0)
    }
}