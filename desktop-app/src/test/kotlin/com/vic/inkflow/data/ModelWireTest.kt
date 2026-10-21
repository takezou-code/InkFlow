package com.vic.inkflow.data

import com.google.gson.Gson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wire shape test, desktop side.
 *
 * The tablet's side of this pair lives in `:app`'s test source set
 * (`ModelWireTest`); this is the same guard run against the desktop's own rows.
 *
 * ## Why the desktop's assertion is a *subset*, not an equality
 *
 * The desktop's SQLite tables have no `toolKind` column: the field was added on
 * the tablet (DB v28) and never mirrored here. The shared model has it, because
 * the model is the union of what the two ends know.
 *
 * So the desktop's rows are asserted to be a **subset** of the model — every field
 * the desktop stores must exist in the model under the same name (that is what
 * catches a rename or a drop, which is the drift that matters) — and the one
 * known difference is asserted *explicitly* below, so that closing the gap forces
 * a deliberate update of this file instead of silently changing what the desktop
 * puts on the wire.
 */
class ModelWireTest {

    private val gson = Gson()

    private val rowStroke = StrokeEntity(
        id = "s1",
        documentUri = "file:///docs/a.pdf",
        pageIndex = 2,
        docY = 1700f,
        color = 0xFF112233.toInt(),
        strokeWidth = 2.5f,
        boundsLeft = 1f,
        boundsTop = 2f,
        boundsRight = 3f,
        boundsBottom = 4f,
        isHighlighter = true,
        shapeType = "RECT",
        version = 7,
        versionNonce = 11,
        deletedAt = 99L
    )

    private val rowPoints = listOf(
        PointEntity(id = 7, strokeId = "s1", x = 1.5f, y = 2.5f, width = 2.5f)
    )

    @Test
    fun `every field the desktop stores exists in the shared model`() {
        val fromRow = jsonShape(gson.toJson(StrokeWithPoints(rowStroke, rowPoints)))
        val fromModel = jsonShape(gson.toJson(StrokeWithPoints(rowStroke, rowPoints).toModel()))
        val missing = fromRow - fromModel
        assertTrue(missing.isEmpty(), "the desktop stores fields the model does not have: $missing")
    }

    @Test
    fun `the known difference is exactly the tablet's toolKind column`() {
        val fromRow = jsonShape(gson.toJson(StrokeWithPoints(rowStroke, rowPoints)))
        val fromModel = jsonShape(gson.toJson(StrokeWithPoints(rowStroke, rowPoints).toModel()))
        val extra = fromModel - fromRow
        assertEquals(setOf("stroke.toolKind"), extra)
    }

    @Test
    fun `the shared model reads a payload that carries the tablet's toolKind`() {
        // What the tablet actually puts on the wire: the Room row, toolKind and all.
        val wire = """
            {"stroke":{"id":"s1","documentUri":"file:///docs/a.pdf","pageIndex":2,"docY":1700.0,
            "color":-16777217,"strokeWidth":2.5,"boundsLeft":1.0,"boundsTop":2.0,"boundsRight":3.0,
            "boundsBottom":4.0,"isHighlighter":true,"toolKind":"CRAYON","shapeType":"RECT",
            "version":7,"versionNonce":11,"deletedAt":99},
            "points":[{"id":7,"strokeId":"s1","x":1.5,"y":2.5,"width":2.5}]}
        """.trimIndent()
        val parsed = gson.fromJson(wire, StrokeWithSamples::class.java)

        assertEquals("s1", parsed.stroke.id)
        assertEquals(1700f, parsed.stroke.docY)
        assertEquals("CRAYON", parsed.stroke.toolKind)
        assertEquals(7, parsed.stroke.version)
        assertEquals(99L, parsed.stroke.deletedAt)
        assertEquals(1, parsed.points.size)
        assertEquals(1.5f, parsed.points.first().x)
    }

    @Test
    fun `a row survives the round trip through the model`() {
        val model = rowStroke.toModel()
        val back = model.toRow()
        assertEquals(rowStroke, back)
    }

    @Test
    fun `a text annotation survives the round trip through the model`() {
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
        assertEquals(row, row.toModel().toRow())
    }

    @Test
    fun `a document survives the round trip through the model`() {
        val row = DocumentEntity(
            uri = "file:///a.pdf",
            displayName = "a.pdf",
            lastOpenedAt = 1234L,
            lastPageIndex = 3,
            isFavorite = true,
            folderId = "f1"
        )
        assertEquals(row, row.toModel().toRow())
    }

    @Test
    fun `a folder survives the round trip through the model`() {
        val row = FolderEntity(id = "f1", name = "Notes", parentFolderId = null, sortOrder = 2, createdAt = 10L, updatedAt = 20L)
        assertEquals(row, row.toModel().toRow())
    }

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
