package com.vic.inkflow.schema

import com.vic.inkflow.json.Json
import com.vic.inkflow.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchemaTest {

    private val noteSchema = SchemaNode.Obj(
        listOf(
            SchemaProp("title", SchemaNode.Str()),
            SchemaProp("kind", SchemaNode.Str(listOf("heading", "bullet"))),
            SchemaProp("count", SchemaNode.Num(integer = true)),
            SchemaProp("lines", SchemaNode.Arr(SchemaNode.Str())),
            SchemaProp("flag", SchemaNode.Bool),
            SchemaProp("note", SchemaNode.Str(), required = false)
        )
    )

    @Test
    fun aValidValueHasNoProblems() {
        val value = Json.parse(
            """{"title":"t","kind":"heading","count":2,"lines":["a"],"flag":true}"""
        )!!
        assertEquals(emptyList(), noteSchema.validate(value))
    }

    @Test
    fun missingRequiredFieldsAreReportedByName() {
        val value = Json.parse("""{"kind":"heading"}""")!!
        val problems = noteSchema.validate(value)
        assertTrue(problems.any { it.contains("title") }, "expected title to be reported: $problems")
        assertTrue(problems.any { it.contains("count") }, "expected count to be reported: $problems")
    }

    @Test
    fun unexpectedFieldsAreRejected() {
        // The model-facing schema says `additionalProperties: false`; the
        // validator has to agree with it, or a model could smuggle fields in
        // that the decoder then silently ignores.
        val value = Json.parse(
            """{"title":"t","kind":"heading","count":1,"lines":[],"flag":true,"extra":1}"""
        )!!
        val problems = noteSchema.validate(value)
        assertTrue(problems.any { it.contains("extra") }, "expected extra to be reported: $problems")
    }

    @Test
    fun enumValuesAreChecked() {
        val value = Json.parse("""{"title":"t","kind":"dance","count":1,"lines":[],"flag":true}""")!!
        val problems = noteSchema.validate(value)
        assertTrue(problems.any { it.contains("heading") }, "expected the allowed values: $problems")
    }

    @Test
    fun wrongTypesAreNamedInTheMessage() {
        val value = Json.parse("""{"title":5,"kind":"heading","count":1,"lines":[],"flag":true}""")!!
        val problems = noteSchema.validate(value)
        assertTrue(problems.any { it.contains("string") }, "expected a type complaint: $problems")
    }

    @Test
    fun wholeNumbersAreRequiredWhereItSays() {
        val value = Json.parse("""{"title":"t","kind":"heading","count":1.5,"lines":[],"flag":true}""")!!
        val problems = noteSchema.validate(value)
        assertTrue(problems.any { it.contains("whole number") }, "expected an integer complaint: $problems")
    }

    @Test
    fun arrayItemsAreChecked() {
        val value = Json.parse("""{"title":"t","kind":"heading","count":1,"lines":[1],"flag":true}""")!!
        val problems = noteSchema.validate(value)
        assertTrue(problems.any { it.contains("lines[0]") }, "expected the item path: $problems")
    }

    @Test
    fun theJsonSchemaDocumentIsStrict() {
        val document = noteSchema.toJsonSchemaDocument()
        assertTrue(document.contains("\"additionalProperties\":false"), document)
        assertTrue(document.contains("\"required\":[\"title\""), document)
        assertTrue(document.contains("\"enum\":[\"heading\",\"bullet\"]"), document)
        // The document has to be valid JSON itself — it is handed to an engine.
        val parsed = Json.parse(document)
        assertTrue(parsed is JsonValue.Obj, "schema document was not valid JSON: $document")
    }

    @Test
    fun optionalPropertiesAreNotRequired() {
        val value = Json.parse("""{"title":"t","kind":"bullet","count":0,"lines":[],"flag":false}""")!!
        assertEquals(emptyList(), noteSchema.validate(value))
    }
}
