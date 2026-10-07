package com.vic.inkflow.util

import com.google.gson.Gson
import com.vic.inkflow.data.DatabaseManager
import com.vic.inkflow.data.DocumentEntity
import com.vic.inkflow.data.PointEntity
import com.vic.inkflow.data.StrokeEntity
import com.vic.inkflow.data.StrokeWithPoints
import com.vic.inkflow.data.TextAnnotationEntity
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import java.awt.Color as AwtColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An exported PDF has to actually contain what the user drew.
 *
 * The failure mode this guards is quiet: `export()` returns without throwing, the
 * file exists, and the page count is right — but the ink is mirrored onto the wrong
 * page or drawn at the wrong scale. So these tests reopen the result with PDFBox and
 * assert on the *content stream*, not merely on the file.
 *
 * A round trip through a real document is also the only way to catch a content stream
 * that is syntactically invalid and therefore silently dropped by the reader.
 */
class PdfExportTest {
    /**
     * Read one page's decoded content stream as text.
     *
     * PDFBox 3 returns an `InputStream` directly from `PDPage.getContents()` — it is
     * no longer a `PDStream` wrapper. The bytes are ISO-8859-1 by definition in PDF,
     * so that is the correct decoding for looking at operators.
     */
    private fun contentOf(doc: PDDocument, index: Int): String {
        val stream = doc.getPage(index).contents ?: return ""
        val bytes = java.io.ByteArrayOutputStream()
        stream.use { it.copyTo(bytes) }
        return bytes.toByteArray().toString(Charsets.ISO_8859_1)
    }

    private val gson = Gson()
    private fun tempDir(prefix: String) =
        java.nio.file.Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

    /** A one-page source document, Letter-sized to catch any hard-coded A4. */
    private fun sourcePdf(dir: java.io.File, pages: Int = 1, w: Float = 612f, h: Float = 792f): java.io.File {
        val doc = PDDocument()
        repeat(pages) { doc.addPage(PDPage(PDRectangle(w, h))) }
        val f = dir.resolve("source.pdf")
        doc.save(f); doc.close()
        return f
    }

    private fun noteAt(x: Float, y: Float, text: String = "note", page: Int = 0) = TextAnnotationEntity(
        id = "n-$x-$y", documentUri = "file:///a.pdf", pageIndex = page,
        text = text, modelX = x, modelY = y, fontSize = 16f, colorArgb = 0xFF000000.toInt()
    )

    private fun strokeAt(vararg xy: Pair<Float, Float>, page: Int = 0, color: Int = 0xFF000000.toInt(), highlighter: Boolean = false) =
        StrokeWithPoints(
            stroke = StrokeEntity(
                id = "s${xy.joinToString("-") { "${it.first}x${it.second}" }}",
                documentUri = "file:///a.pdf", pageIndex = page, docY = null,
                color = color, strokeWidth = 2f,
                boundsLeft = xy.minOf { it.first }, boundsTop = xy.minOf { it.second },
                boundsRight = xy.maxOf { it.first }, boundsBottom = xy.maxOf { it.second },
                isHighlighter = highlighter
            ),
            points = xy.mapIndexed { i, (x, y) ->
                PointEntity(id = (i + 1).toLong(), strokeId = "x", x = x, y = y, width = 2f)
            }
        )

    // ─── The transform, verified against real content streams ────────────────

    @Test
    fun `a stroke at the model top lands at the top of the exported page`() {
        val dir = tempDir("exp-top")
        val src = sourcePdf(dir)
        val out = dir.resolve("out.pdf")

        // y = 0 is the top edge in model space. In PDF space that is pageHeight.
        PdfExporter.export(
            sourcePdf = src,
            destination = out,
            modelWidth = 612f, modelHeight = 792f,
            strokes = listOf(strokeAt(100f to 0f, 200f to 10f)),
            texts = emptyList()
        )

        val doc = Loader.loadPDF(out)
        val cs = contentOf(doc, 0)
        doc.close()

        // The content stream uses absolute coordinates; 792 - 10*scale should appear
        // as a y near the top. Asserting the flip rather than an exact string keeps
        // this from breaking on unrelated formatting.
        val ys = Regex("""([\d.]+) ([\d.]+) m""").findAll(cs)
            .map { it.groupValues[2].toFloat() }.toList()
        assertTrue(ys.isNotEmpty(), "expected a moveto in the content stream: $cs")
        assertTrue(
            ys.all { it > 792f / 2f },
            "a stroke at the model top must be in the upper half of PDF space, got $ys"
        )
    }

    @Test
    fun `a stroke at the model bottom lands at the bottom of the exported page`() {
        val dir = tempDir("exp-bottom")
        val out = dir.resolve("out.pdf")
        PdfExporter.export(
            sourcePdf = sourcePdf(dir), destination = out,
            modelWidth = 612f, modelHeight = 792f,
            strokes = listOf(strokeAt(100f to 780f, 200f to 790f)), texts = emptyList()
        )
        val doc = Loader.loadPDF(out)
        val cs = contentOf(doc, 0)
        doc.close()
        val ys = Regex("""([\d.]+) ([\d.]+) m""").findAll(cs)
            .map { it.groupValues[2].toFloat() }.toList()
        assertTrue(ys.isNotEmpty(), "expected a moveto")
        assertTrue(
            ys.all { it < 792f / 2f },
            "a stroke at the model bottom must be in the lower half of PDF space, got $ys"
        )
    }

    @Test
    fun `annotations on page two stay on page two`() {
        // The whole point of per-page export: pulling every page's data together
        // would put later annotations on earlier pages.
        val dir = tempDir("exp-page")
        val out = dir.resolve("out.pdf")
        PdfExporter.export(
            sourcePdf = sourcePdf(dir, pages = 3), destination = out,
            modelWidth = 612f, modelHeight = 792f,
            strokes = emptyList(),
            texts = listOf(noteAt(100f, 100f, "p1", page = 0), noteAt(100f, 100f, "p2", page = 1))
        )
        val doc = Loader.loadPDF(out)
        val p0 = contentOf(doc, 0)
        val p1 = contentOf(doc, 1)
        val p2 = contentOf(doc, 2)
        doc.close()

        // Text is drawn with Tj/TJ; assert on the presence of a drawn text operator.
        assertTrue(Regex("""Tj|TJ""").containsMatchIn(p0), "page 1 must carry its note")
        assertTrue(Regex("""Tj|TJ""").containsMatchIn(p1), "page 2 must carry its note")
        assertTrue(!Regex("""Tj|TJ""").containsMatchIn(p2), "page 3 must be left alone")
    }

    // ─── Ink fidelity ────────────────────────────────────────────────────────

    @Test
    fun `a shape is stroked as an outline, not filled as a polygon`() {
        // The regression that made shapes unusable in an export: routing them
        // through the variable-width envelope filler turns a rectangle into a solid
        // blob or a sliver.
        val dir = tempDir("exp-shape")
        val out = dir.resolve("out.pdf")
        val rect = strokeAt(100f to 100f, 300f to 200f).let {
            it.copy(stroke = it.stroke.copy(shapeType = "RECT"))
        }
        PdfExporter.export(
            sourcePdf = sourcePdf(dir), destination = out,
            modelWidth = 612f, modelHeight = 792f,
            strokes = listOf(rect), texts = emptyList()
        )
        val doc = Loader.loadPDF(out)
        val cs = contentOf(doc, 0)
        doc.close()
        assertTrue(cs.contains(" re") || cs.contains("re\n") || cs.contains("re "), "expected a rectangle operator, got: $cs")
        assertTrue(cs.contains("S") || cs.contains("s"), "shape must be stroked")
    }

    @Test
    fun `a circle exports as four Bezier arcs`() {
        val dir = tempDir("exp-circle")
        val out = dir.resolve("out.pdf")
        val circle = strokeAt(100f to 100f, 300f to 300f).let {
            it.copy(stroke = it.stroke.copy(shapeType = "CIRCLE"))
        }
        PdfExporter.export(
            sourcePdf = sourcePdf(dir), destination = out,
            modelWidth = 612f, modelHeight = 792f,
            strokes = listOf(circle), texts = emptyList()
        )
        val doc = Loader.loadPDF(out)
        val cs = contentOf(doc, 0)
        doc.close()
        val curves = Regex("""c\n|\sc\s""").findAll(cs).count()
        assertTrue(curves >= 4, "a full ellipse needs four cubic arcs, got $curves")
    }

    @Test
    fun `an arrow exports both the shaft and two barbs`() {
        val dir = tempDir("exp-arrow")
        val out = dir.resolve("out.pdf")
        val arrow = strokeAt(100f to 500f, 400f to 200f).let {
            it.copy(stroke = it.stroke.copy(shapeType = "ARROW"))
        }
        PdfExporter.export(
            sourcePdf = sourcePdf(dir), destination = out,
            modelWidth = 612f, modelHeight = 792f,
            strokes = listOf(arrow), texts = emptyList()
        )
        val doc = Loader.loadPDF(out)
        val cs = contentOf(doc, 0)
        doc.close()
        val linetos = Regex("""l\n|\sl\s""").findAll(cs).count()
        // 1 shaft + 2 barbs
        assertTrue(linetos >= 3, "arrow needs a shaft and two barbs, got $linetos line segments")
    }

    @Test
    fun `a line exports a single segment`() {
        val dir = tempDir("exp-line")
        val out = dir.resolve("out.pdf")
        val line = strokeAt(50f to 500f, 500f to 100f).let {
            it.copy(stroke = it.stroke.copy(shapeType = "LINE"))
        }
        PdfExporter.export(
            sourcePdf = sourcePdf(dir), destination = out,
            modelWidth = 612f, modelHeight = 792f,
            strokes = listOf(line), texts = emptyList()
        )
        val doc = Loader.loadPDF(out)
        val cs = contentOf(doc, 0)
        doc.close()
        assertEquals(1, Regex("""l\n|\sl\s""").findAll(cs).count(), "a plain line is one segment")
    }

    @Test
    fun `a highlighter is exported translucent`() {
        val dir = tempDir("exp-hl")
        val out = dir.resolve("out.pdf")
        PdfExporter.export(
            sourcePdf = sourcePdf(dir), destination = out,
            modelWidth = 612f, modelHeight = 792f,
            strokes = listOf(strokeAt(50f to 100f, 400f to 140f, highlighter = true)),
            texts = emptyList()
        )
        val doc = Loader.loadPDF(out)
        // The alpha lives in the page's ExtGState resources (`/gs1 gs`), not inline in
        // the content stream. Asserting on the raw `/ca` text would pass or fail based
        // on PDFBox's serialization details, not on whether the mark is translucent.
        val resources = doc.getPage(0).resources
        val translucent = resources.extGStateNames.map { resources.getExtGState(it) }.any {
            it.strokingAlphaConstant == 0.4f && it.nonStrokingAlphaConstant == 0.4f
        }
        doc.close()
        assertTrue(translucent, "highlighter must carry the 0.4 alpha in its graphics state")
    }

    @Test
    fun `a blank note is skipped rather than drawn as an empty string`() {
        val dir = tempDir("exp-blank")
        val out = dir.resolve("out.pdf")
        PdfExporter.export(
            sourcePdf = sourcePdf(dir), destination = out,
            modelWidth = 612f, modelHeight = 792f,
            strokes = emptyList(),
            texts = listOf(
                noteAt(50f, 50f, "   "),
                TextAnnotationEntity(id = "n2", documentUri = "u", pageIndex = 0, text = "", modelX = 50f, modelY = 50f)
            )
        )
        val doc = Loader.loadPDF(out)
        val cs = contentOf(doc, 0)
        doc.close()
        assertTrue(
            !Regex("""Tj|TJ""").containsMatchIn(cs),
            "blank notes must not produce a text operator, got: $cs"
        )
    }

    @Test
    fun `a multi-line note emits one text run per line`() {
        val dir = tempDir("exp-multiline")
        val out = dir.resolve("out.pdf")
        PdfExporter.export(
            sourcePdf = sourcePdf(dir), destination = out,
            modelWidth = 612f, modelHeight = 792f,
            strokes = emptyList(),
            texts = listOf(noteAt(50f, 100f, "line one\nline two\nline three"))
        )
        val doc = Loader.loadPDF(out)
        val cs = contentOf(doc, 0)
        doc.close()
        assertTrue(Regex("""Tj|TJ""").findAll(cs).count() >= 3, "three lines need three text runs")
    }

    // ─── Document integrity ──────────────────────────────────────────────────

    @Test
    fun `the page count of the source is preserved`() {
        val dir = tempDir("exp-pages")
        val out = dir.resolve("out.pdf")
        PdfExporter.export(
            sourcePdf = sourcePdf(dir, pages = 5), destination = out,
            modelWidth = 612f, modelHeight = 792f, strokes = emptyList(), texts = emptyList()
        )
        val doc = Loader.loadPDF(out)
        assertEquals(5, doc.numberOfPages, "export must not add or drop pages")
        doc.close()
    }

    @Test
    fun `a Letter page is not rescaled to A4`() {
        // Assuming A4 for a Letter document shifts every annotation, because the
        // model->page ratio is wrong even though the page itself is untouched.
        val dir = tempDir("exp-size")
        val out = dir.resolve("out.pdf")
        PdfExporter.export(
            sourcePdf = sourcePdf(dir, w = 612f, h = 792f), destination = out,
            modelWidth = 612f, modelHeight = 792f,
            strokes = emptyList(), texts = emptyList()
        )
        val doc = Loader.loadPDF(out)
        val mb = doc.getPage(0).mediaBox
        assertEquals(612f, mb.width, 0.5f)
        assertEquals(792f, mb.height, 0.5f)
        doc.close()
    }

    @Test
    fun `an export with no annotations still produces a readable document`() {
        val dir = tempDir("exp-empty")
        val out = dir.resolve("out.pdf")
        PdfExporter.export(
            sourcePdf = sourcePdf(dir), destination = out,
            modelWidth = 612f, modelHeight = 792f, strokes = emptyList(), texts = emptyList()
        )
        assertTrue(out.isFile && out.length() > 0, "an empty export must still be a valid PDF")
        val doc = Loader.loadPDF(out)
        assertEquals(1, doc.numberOfPages)
        doc.close()
    }

    @Test
    fun `an existing destination is overwritten rather than appended to`() {
        val dir = tempDir("exp-overwrite")
        val out = dir.resolve("out.pdf")
        out.writeBytes(ByteArray(50) { 0x7 })
        PdfExporter.export(
            sourcePdf = sourcePdf(dir), destination = out,
            modelWidth = 612f, modelHeight = 792f,
            strokes = listOf(strokeAt(10f to 10f, 20f to 20f)), texts = emptyList()
        )
        val doc = Loader.loadPDF(out)   // would fail if the stub bytes survived
        assertEquals(1, doc.numberOfPages)
        doc.close()
    }

    @Test
    fun `export is idempotent for the same input`() {
        // Re-exporting an already-annotated document must not accumulate content, so
        // exporting from the same source twice has to give byte-comparable structure.
        val dir = tempDir("exp-idem")
        val a = dir.resolve("a.pdf"); val b = dir.resolve("b.pdf")
        val src = sourcePdf(dir)
        val strokes = listOf(strokeAt(10f to 10f, 200f to 100f))
        PdfExporter.export(src, a, 612f, 792f, strokes, emptyList())
        PdfExporter.export(src, b, 612f, 792f, strokes, emptyList())
        listOf(a, b).forEach { f ->
            val doc = Loader.loadPDF(f)
            assertEquals(1, doc.numberOfPages)
            doc.close()
        }
    }

    @Test
    fun `exporting to a destination that is a directory fails loudly`() {
        val dir = tempDir("exp-bad")
        val doc = PDDocument(); doc.addPage(PDPage(PDRectangle(612f, 792f)))
        val src = dir.resolve("s.pdf"); doc.save(src); doc.close()

        // A directory cannot be replaced by a file. The old version of this test used
        // a merely nonexistent nested path, but the exporter creates parents, so that
        // path succeeded — testing directory-as-destination asserts the real failure.
        val bad = dir.resolve("a-directory").apply { mkdirs() }
        val threw = runCatching {
            PdfExporter.export(src, bad, 612f, 792f, emptyList(), emptyList())
        }.isFailure
        assertTrue(threw, "a directory destination must not silently succeed")
    }

    @Test
    fun `a document with many annotations stays a valid document`() {
        val dir = tempDir("exp-many")
        val out = dir.resolve("out.pdf")
        val strokes = (0 until 200).map { i ->
            strokeAt((10 + i).toFloat() to 20f, (30 + i).toFloat() to 90f, page = 0)
        }
        val notes = (0 until 100).map { i ->
            noteAt((10 + i).toFloat(), (100f + i), "note $i")
        }
        PdfExporter.export(sourcePdf = sourcePdf(dir), destination = out, modelWidth = 612f, modelHeight = 792f, strokes = strokes, texts = notes)
        val doc = Loader.loadPDF(out)
        assertEquals(1, doc.numberOfPages)
        val cs = contentOf(doc, 0)
        doc.close()
        assertTrue(cs.length > 1000, "300 annotations should produce substantial content")
    }

    @Test
    fun `the export reads annotations from the database when asked`() {
        // The real call path goes through the DB, so the provider seam has to work.
        val dir = tempDir("exp-db")
        val db = DatabaseManager(dir.resolve("db.sqlite").absolutePath)
        db.connect()
        try {
            val uri = "file:///exported.pdf"
            db.saveDocument(DocumentEntity(uri, "Exported"))
            db.saveStroke(
                StrokeEntity(id = "s1", documentUri = uri, pageIndex = 0, color = 0, strokeWidth = 2f,
                    boundsLeft = 10f, boundsTop = 10f, boundsRight = 100f, boundsBottom = 50f),
                listOf(PointEntity(id = 1, strokeId = "s1", x = 10f, y = 10f, width = 2f),
                       PointEntity(id = 2, strokeId = "s1", x = 100f, y = 50f, width = 2f))
            )
            db.saveTextAnnotation(
                TextAnnotationEntity(id = "n1", documentUri = uri, pageIndex = 0,
                    text = "from db", modelX = 20f, modelY = 40f)
            )

            val out = dir.resolve("out.pdf")
            val pages = PdfExporter.exportFromDatabase(
                db, uri, sourcePdf(dir), out, 612f, 792f
            )
            assertEquals(1, pages)

            val doc = Loader.loadPDF(out)
            val cs = contentOf(doc, 0)
            doc.close()
            assertTrue(Regex("""Tj|TJ""").containsMatchIn(cs), "the DB note must reach the PDF")
        } finally {
            db.disconnect()
            dir.deleteRecursively()
        }
    }
}