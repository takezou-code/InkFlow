package com.vic.inkflow.json

/**
 * A minimal JSON value model.
 *
 * Why this exists instead of a JSON library
 * ----------------------------------------
 * The agent core has to read two things: the model's answer (which arrives as
 * JSON text) and the tool input the model asked for. Both happen in
 * `:shared`'s `commonMain`, which is compiled for Android, Windows and iOS.
 *
 * The obvious move is to add `kotlinx-serialization-json` to the shared
 * module. The reason this file exists instead:
 *
 *  - `:shared` deliberately has no serialization plugin and no JSON
 *    dependency. The apps serialise with Gson, in the platform layer, where
 *    the bytes actually travel (the sync protocol's DTOs live in `:shared`,
 *    but the encoding is the platform's job — see `sync/SyncDtos.kt`).
 *  - The agent core needs a *reader* and a small *writer* for JSON Schema
 *    documents, not a general-purpose object mapper. That is a few hundred
 *    lines, and it can be pinned down with tests that run on every platform.
 *
 * If the app later needs JSON everywhere, this should be deleted in favour of
 * the library — not grown.
 */
sealed interface JsonValue {

    data object Null : JsonValue

    data class Bool(val value: Boolean) : JsonValue

    data class Num(val value: Double) : JsonValue

    data class Str(val value: String) : JsonValue

    data class Arr(val items: List<JsonValue>) : JsonValue

    data class Obj(val fields: Map<String, JsonValue>) : JsonValue
}

/** The object's fields, or `null` when this is not an object. */
val JsonValue.asObject: Map<String, JsonValue>?
    get() = (this as? JsonValue.Obj)?.fields

/** The array's items, or `null` when this is not an array. */
val JsonValue.asArray: List<JsonValue>?
    get() = (this as? JsonValue.Arr)?.items

/** The string, or `null` when this is not a string. */
val JsonValue.asString: String?
    get() = (this as? JsonValue.Str)?.value

/** The number, or `null` when this is not a number. */
val JsonValue.asNumber: Double?
    get() = (this as? JsonValue.Num)?.value

/** The boolean, or `null` when this is not a boolean. */
val JsonValue.asBoolean: Boolean?
    get() = (this as? JsonValue.Bool)?.value

/** One field of an object, or `null` when absent or when this is not an object. */
fun JsonValue.field(name: String): JsonValue? = asObject?.get(name)

/** One field of an object read as a string, or `null`. */
fun JsonValue.stringField(name: String): String? = field(name)?.asString
