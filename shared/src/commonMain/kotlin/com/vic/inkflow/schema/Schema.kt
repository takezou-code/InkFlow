package com.vic.inkflow.schema

import com.vic.inkflow.json.Json
import com.vic.inkflow.json.JsonValue
import com.vic.inkflow.json.asArray
import com.vic.inkflow.json.asBoolean
import com.vic.inkflow.json.asNumber
import com.vic.inkflow.json.asObject
import com.vic.inkflow.json.asString

/**
 * A tiny schema language: enough to describe what a model is allowed to
 * answer, enough to check that the answer is allowed.
 *
 * Why not JSON Schema objects all the way down
 * --------------------------------------------
 * Two jobs need a schema here:
 *
 *  1. **Tell the model.** Local engines (llama.cpp, LM Studio) accept a JSON
 *     Schema document and constrain generation with it. That document has to
 *     be built from something, and building it from a typed tree keeps the
 *     model-facing schema and the validation code from drifting apart.
 *  2. **Check the model.** A constrained server *usually* returns valid JSON,
 *     but "usually" is not a guarantee: a model can be wrong about the schema
 *     it was given, an engine can ignore the constraint, and a server can be
 *     answering something else entirely. The loop has to be able to say "that
 *     is not what I asked for" and try again.
 *
 * So the schema is a typed tree that can be rendered to a JSON Schema
 * document ([toJsonSchemaDocument]) and can validate a parsed value
 * ([validate]).
 */
sealed interface SchemaNode {

    /** An object with named properties. */
    data class Obj(val properties: List<SchemaProp>) : SchemaNode

    /** An array of one item shape. */
    data class Arr(val items: SchemaNode) : SchemaNode

    /** A string, optionally restricted to a fixed set of values. */
    data class Str(val enum: List<String> = emptyList()) : SchemaNode

    /** A number, optionally restricted to integers. */
    data class Num(val integer: Boolean = false) : SchemaNode

    /** A boolean. */
    data object Bool : SchemaNode

    /**
     * Anything at all — used where the shape is decided by something other
     * than the schema (a tool's own input, which is described in the prompt
     * and checked against the tool's own schema).
     */
    data object Any : SchemaNode
}

/** One named property of an [SchemaNode.Obj]. */
data class SchemaProp(
    val name: String,
    val schema: SchemaNode,
    /** Shown to the model; it is the only documentation the model gets. */
    val description: String = "",
    /** Whether the property may be missing. */
    val required: Boolean = true
)

/**
 * Renders this schema as a JSON Schema document.
 *
 * The output is what gets handed to the engine's `response_format`, so it is
 * strict on purpose: `additionalProperties` is `false` because a model that
 * invents extra fields is a model whose answer we cannot check.
 */
fun SchemaNode.toJsonSchemaDocument(): String = Json.write(toJsonValue())

private fun SchemaNode.toJsonValue(): JsonValue = when (this) {
    is SchemaNode.Obj -> JsonValue.Obj(
        buildMap {
            put("type", JsonValue.Str("object"))
            put(
                "properties",
                JsonValue.Obj(
                    properties.associate { prop ->
                        prop.name to prop.schema.toJsonValue().let { node ->
                            if (prop.description.isEmpty()) node else JsonValue.Obj(
                                node.asObject.orEmpty() + ("description" to JsonValue.Str(prop.description))
                            )
                        }
                    }
                )
            )
            val required = properties.filter { it.required }.map { it.name }
            if (required.isNotEmpty()) {
                put("required", JsonValue.Arr(required.map { JsonValue.Str(it) }))
            }
            put("additionalProperties", JsonValue.Bool(false))
        }
    )

    is SchemaNode.Arr -> JsonValue.Obj(
        mapOf(
            "type" to JsonValue.Str("array"),
            "items" to items.toJsonValue()
        )
    )

    is SchemaNode.Str -> JsonValue.Obj(
        buildMap {
            put("type", JsonValue.Str("string"))
            if (enum.isNotEmpty()) {
                put("enum", JsonValue.Arr(enum.map { JsonValue.Str(it) }))
            }
        }
    )

    is SchemaNode.Num -> JsonValue.Obj(
        mapOf("type" to JsonValue.Str(if (integer) "integer" else "number"))
    )

    is SchemaNode.Bool -> JsonValue.Obj(mapOf("type" to JsonValue.Str("boolean")))

    is SchemaNode.Any -> JsonValue.Obj(emptyMap())
}

/**
 * Checks [value] against this schema and returns the problems, empty when the
 * value fits.
 *
 * The messages are written to be shown to a model: the loop feeds them back
 * when the first answer was rejected, and "expected a string, got a number" is
 * more useful to a model than "validation failed".
 */
fun SchemaNode.validate(value: JsonValue, path: String = ""): List<String> {
    val where = if (path.isEmpty()) "value" else path
    return when (this) {
        is SchemaNode.Any -> emptyList()

        is SchemaNode.Obj -> {
            val fields = value.asObject
            if (fields == null) {
                listOf("$where: expected an object, got ${value.kindName()}")
            } else {
                val problems = mutableListOf<String>()
                for (prop in properties) {
                    val child = fields[prop.name]
                    if (child == null) {
                        if (prop.required) problems += "$where.${prop.name}: missing required field"
                    } else {
                        problems += prop.schema.validate(child, "$where.${prop.name}")
                    }
                }
                val known = properties.map { it.name }.toSet()
                val extra = fields.keys - known
                if (extra.isNotEmpty()) {
                    problems += "$where: unexpected field(s) ${extra.sorted().joinToString(", ")}"
                }
                problems
            }
        }

        is SchemaNode.Arr -> {
            val elements = value.asArray
            if (elements == null) {
                listOf("$where: expected an array, got ${value.kindName()}")
            } else {
                elements.flatMapIndexed { index, item -> items.validate(item, "$where[$index]") }
            }
        }

        is SchemaNode.Str -> {
            val text = value.asString
            when {
                text == null -> listOf("$where: expected a string, got ${value.kindName()}")
                enum.isNotEmpty() && text !in enum ->
                    listOf("$where: expected one of ${enum.joinToString(", ")}, got \"$text\"")
                else -> emptyList()
            }
        }

        is SchemaNode.Num -> {
            val number = value.asNumber
            when {
                number == null -> listOf("$where: expected a number, got ${value.kindName()}")
                integer && number % 1.0 != 0.0 ->
                    listOf("$where: expected a whole number, got $number")
                else -> emptyList()
            }
        }

        is SchemaNode.Bool -> {
            if (value.asBoolean == null) {
                listOf("$where: expected a boolean, got ${value.kindName()}")
            } else {
                emptyList()
            }
        }
    }
}

/** A short, human-readable name of the value's shape, for error messages. */
fun JsonValue.kindName(): String = when (this) {
    is JsonValue.Null -> "null"
    is JsonValue.Bool -> "a boolean"
    is JsonValue.Num -> "a number"
    is JsonValue.Str -> "a string"
    is JsonValue.Arr -> "an array"
    is JsonValue.Obj -> "an object"
}
