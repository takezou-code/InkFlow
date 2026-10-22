package com.vic.inkflow.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonTest {

    @Test
    fun parsesTheShapesTheAgentUses() {
        assertEquals(
            JsonValue.Obj(mapOf("a" to JsonValue.Num(1.0), "b" to JsonValue.Str("x"))),
            Json.parse("""{"a":1,"b":"x"}""")
        )
        assertEquals(JsonValue.Arr(listOf(JsonValue.Num(1.0), JsonValue.Num(2.0))), Json.parse("[1,2]"))
        assertEquals(JsonValue.Bool(true), Json.parse("true"))
        assertEquals(JsonValue.Null, Json.parse("null"))
        assertEquals(JsonValue.Str("hi"), Json.parse("\"hi\""))
    }

    @Test
    fun malformedInputIsNullNotAnException() {
        // The agent asks a model for JSON. A model that answers with prose is
        // an expected event, so the parser reports it by returning null.
        assertNull(Json.parse("here is your note"))
        assertNull(Json.parse(""))
        assertNull(Json.parse("{"))
        assertNull(Json.parse("""{"a":}"""))
        assertNull(Json.parse("[1,]"))
        assertNull(Json.parse("tru"))
        assertNull(Json.parse("""{"a":1} trailing"""))
    }

    @Test
    fun escapesRoundTrip() {
        val original = JsonValue.Str("line\nbreak \"quoted\" back\\slash\ttab")
        val text = Json.write(original)
        assertEquals(original, Json.parse(text))
    }

    @Test
    fun unicodeEscapesAreRead() {
        assertEquals(JsonValue.Str("A"), Json.parse("\"\\u0041\""))
    }

    @Test
    fun nestingIsBounded() {
        // A confused model can emit a very deep structure; it must not take the
        // app down with a stack overflow.
        val deep = "[".repeat(500) + "]".repeat(500)
        assertNull(Json.parse(deep))
    }

    @Test
    fun writeProducesCompactJson() {
        val value = JsonValue.Obj(
            linkedMapOf(
                "kind" to JsonValue.Str("bullet"),
                "text" to JsonValue.Str("a \"quoted\" value"),
                "n" to JsonValue.Num(3.0)
            )
        )
        assertEquals(
            """{"kind":"bullet","text":"a \"quoted\" value","n":3}""",
            Json.write(value)
        )
    }

    @Test
    fun wholeNumbersKeepTheirShape() {
        assertEquals("""{"n":3}""", Json.write(JsonValue.Obj(mapOf("n" to JsonValue.Num(3.0)))))
        assertEquals("""{"n":3.5}""", Json.write(JsonValue.Obj(mapOf("n" to JsonValue.Num(3.5)))))
    }

    @Test
    fun accessorsDoNotThrowOnTheWrongShape() {
        val text = JsonValue.Str("x")
        assertNull(text.asObject)
        assertNull(text.asArray)
        assertNull(text.asNumber)
        assertNull(text.asBoolean)
        assertEquals("x", text.asString)
        assertTrue(text.field("missing") == null)
    }
}
