package com.vic.inkflow.data

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The wire shape test: **the tablet's rows and the shared model must serialise to the
 * same JSON.**
 *
 * ## What it protects
 *
 * The tablet's `StrokeEntity` is what the sync protocol actually puts on the wire —
 * Gson serialises the Room row directly, so the row's field names *are* the wire
 * contract. The shared model (`:shared`'s `com.vic.inkflow.data`) is the same
 * contract written down once.
 *
 * The two had already drifted: the tablet's stroke carries a `toolKind` column the
 * desktop's row does not, and the tablet's `TextAnnotationEntity` defaults its
 * colour to `android.graphics.Color.BLACK` — an Android API inside a data class
 * default, which is why the model could not even be compiled for iOS until it was
 * given a plain constant.
 *
 * This test is the mechanical guard: if somebody adds a column to the Room entity
 * and forgets the model (or renames one), the JSON shapes stop matching and this
 * fails — instead of the two sides silently disagreeing on the wire, which is the
 * failure mode this project has already paid for once.
 *
 * It runs on the tablet side and on the desktop side, each against its own row
 * type, so neither side can drift alone.
 */
class ModelWireTest {

    private val gson = Gson()

    // ── the tablet's own row, exactly as the sync protocol serialises it ──

    private val entityStroke = StrokeEntity(
        id = "s1",
        documentUri = "file:///storage/emulated/0/Documents/a.pdf",
        pageIndex = 2,
        docY = 1700f,
        color = 0xFF112233.toInt(),
        strokeWidth = 2.5f,
        boundsLeft = 1f,
        boundsTop = 2f,
        boundsRight = 3f,
        boundsBottom = 4f,
        isHighlighter = true,
        toolKind = "HIGHLIGHTER",
        shapeType = "RECT",
        version = 7,
        versionNonce = 11,
        deletedAt = 99L
    )

    private val entityPoints = listOf(
        PointEntity(id = 7, strokeId = "s1", x = 1.5f, y = 2.5f, width = 2.5f),
        PointEntity(id = 8, strokeId = "s1", x = 3.5f, y = 4.5f, width = 2.4f)
    )

    // ── the shared model, holding the same content ──

    private val modelStroke = Stroke(
        id = "s1",
        documentUri = "file:///storage/emulated/0/Documents/a.pdf",
        pageIndex = 2,
        docY = 1700f,
        color = 0xFF112233.toInt(),
        strokeWidth = 2.5f,
        boundsLeft = 1f,
        boundsTop = 2f,
        boundsRight = 3f,
        boundsBottom = 4f,
        isHighlighter = true,
        toolKind = "HIGHLIGHTER",
        shapeType = "RECT",
        version = 7,
        versionNonce = 11,
        deletedAt = 99L
    )

    private val modelPoints = listOf(
        Point(id = 7, strokeId = "s1", x = 1.5f, y = 2.5f, width = 2.5f),
        Point(id = 8, strokeId = "s1", x = 3.5f, y = 4.5f, width = 2.4f)
    )

    @Test
    fun `a stroke serialises to the same JSON shape from the row and from the model`() {
        val fromRow = gson.toJson(StrokeWithPoints(entityStroke, entityPoints))
        val fromModel = gson.toJson(StrokeWithSamples(modelStroke, modelPoints))
        assertEquals(jsonShape(fromRow), jsonShape(fromModel))
    }

    @Test
    fun `the shared model reads the tablet's own wire payload`() {
        val wire = gson.toJson(StrokeWithPoints(entityStroke, entityPoints))
        val parsed = gson.fromJson(wire, StrokeWithSamples::class.java)

        assertEquals(entityStroke.id, parsed.stroke.id)
        assertEquals(entityStroke.documentUri, parsed.stroke.documentUri)
        assertEquals(entityStroke.pageIndex, parsed.stroke.pageIndex)
        assertEquals(entityStroke.docY, parsed.stroke.docY)
        assertEquals(entityStroke.color, parsed.stroke.color)
        assertEquals(entityStroke.strokeWidth, parsed.stroke.strokeWidth)
        assertEquals(entityStroke.boundsLeft, parsed.stroke.boundsLeft)
        assertEquals(entityStroke.boundsTop, parsed.stroke.boundsTop)
        assertEquals(entityStroke.boundsRight, parsed.stroke.boundsRight)
        assertEquals(entityStroke.boundsBottom, parsed.stroke.boundsBottom)
        assertEquals(entityStroke.isHighlighter, parsed.stroke.isHighlighter)
        assertEquals(entityStroke.toolKind, parsed.stroke.toolKind)
        assertEquals(entityStroke.shapeType, parsed.stroke.shapeType)
        assertEquals(entityStroke.version, parsed.stroke.version)
        assertEquals(entityStroke.versionNonce, parsed.stroke.versionNonce)
        assertEquals(entityStroke.deletedAt, parsed.stroke.deletedAt)

        assertEquals(entityPoints.size, parsed.points.size)
        assertEquals(entityPoints.map { it.x }, parsed.points.map { it.x })
        assertEquals(entityPoints.map { it.id }, parsed.points.map { it.id })
    }

    @Test
    fun `a text annotation serialises to the same JSON shape from the row and from the model`() {
        val row = TextAnnotationEntity(
            id = "t1",
            documentUri = "file:///a.pdf",
            pageIndex = 1,
            docY = 900f,
            text = "hello",
            modelX = 10f,
            modelY = 20f,
            fontSize = 18f,
            colorArgb = 0xFF00FF00.toInt(),
            isStamp = true,
            boxWidth = 120f,
            alignment = 2,
            version = 3,
            versionNonce = 5,
            deletedAt = null
        )
        val model = TextAnnotation(
            id = "t1",
            documentUri = "file:///a.pdf",
            pageIndex = 1,
            docY = 900f,
            text = "hello",
            modelX = 10f,
            modelY = 20f,
            fontSize = 18f,
            colorArgb = 0xFF00FF00.toInt(),
            isStamp = true,
            boxWidth = 120f,
            alignment = 2,
            version = 3,
            versionNonce = 5,
            deletedAt = null
        )
        assertEquals(jsonShape(gson.toJson(row)), jsonShape(gson.toJson(model)))
    }

    @Test
    fun `an image annotation serialises to the same JSON shape from the row and from the model`() {
        val row = ImageAnnotationEntity(
            id = "i1",
            documentUri = "file:///a.pdf",
            pageIndex = 1,
            docY = 900f,
            uri = "file:///i.png",
            modelX = 10f,
            modelY = 20f,
            modelWidth = 30f,
            modelHeight = 40f,
            rotation = 90f
        )
        val model = ImageAnnotation(
            id = "i1",
            documentUri = "file:///a.pdf",
            pageIndex = 1,
            docY = 900f,
            uri = "file:///i.png",
            modelX = 10f,
            modelY = 20f,
            modelWidth = 30f,
            modelHeight = 40f,
            rotation = 90f
        )
        assertEquals(jsonShape(gson.toJson(row)), jsonShape(gson.toJson(model)))
    }

    @Test
    fun `a document serialises to the same JSON shape from the row and from the model`() {
        val row = DocumentEntity(
            uri = "file:///a.pdf",
            displayName = "a.pdf",
            lastOpenedAt = 1234L,
            lastPageIndex = 3,
            isFavorite = true,
            folderId = "f1"
        )
        val model = Document(
            uri = "file:///a.pdf",
            displayName = "a.pdf",
            lastOpenedAt = 1234L,
            lastPageIndex = 3,
            isFavorite = true,
            folderId = "f1"
        )
        assertEquals(jsonShape(gson.toJson(row)), jsonShape(gson.toJson(model)))
    }

    @Test
    fun `a folder serialises to the same JSON shape from the row and from the model`() {
        val row = FolderEntity(id = "f1", name = "Notes", parentFolderId = null, sortOrder = 2, createdAt = 10L, updatedAt = 20L)
        val model = Folder(id = "f1", name = "Notes", parentFolderId = null, sortOrder = 2, createdAt = 10L, updatedAt = 20L)
        assertEquals(jsonShape(gson.toJson(row)), jsonShape(gson.toJson(model)))
    }

    @Test
    fun `a payload without toolKind still lands in the database with the storage default`() {
        // The desktop's payload has no `toolKind` (its tables have no such
        // column), and Gson fills fields by reflection without running Kotlin's
        // defaults — so the value arrives as null. The column is NOT NULL, so
        // writing the null straight through fails the insert. The storage
        // mapping is where "absent" becomes a value.
        val wire = """
            {"stroke":{"id":"s1","documentUri":"file:///a.pdf","pageIndex":0,
            "color":-16777217,"strokeWidth":1.0},"points":[]}
        """.trimIndent()
        val model = gson.fromJson(wire, StrokeWithSamples::class.java)

        assertNull(model.stroke.toolKind)
        assertEquals(Stroke.TOOL_PEN, model.stroke.toRow().toolKind)
    }

    /**
     * The set of JSON paths in [json] — `stroke.id`, `points[0].x`, …
     *
     * Comparing the *shapes* rather than the values is deliberate: the values are
     * the same by construction in these tests, whereas the shape is the thing that
     * drifts (a renamed column, a new column, a dropped one).
     */
    private fun jsonShape(json: String): Set<String> {
        val out = mutableSetOf<String>()
        fun walk(prefix: String, value: Any?) {
            when (value) {
                is Map<*, *> -> value.forEach { (k, v) -> walk(if (prefix.isEmpty()) "$k" else "$prefix.$k", v) }
                is List<*> -> value.forEachIndexed { i, v -> walk("$prefix[$i]", v) }
                else -> out += prefix
            }
        }
        walk("", gson.fromJson(json, Map::class.java))
        return out
    }
}
