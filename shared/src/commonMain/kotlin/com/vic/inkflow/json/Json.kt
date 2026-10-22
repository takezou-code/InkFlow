package com.vic.inkflow.json

/**
 * A JSON reader and writer, small enough to read in one sitting.
 *
 * The reader is deliberately forgiving about *nothing* and returns `null`
 * instead of throwing: the caller is usually an agent that just received text
 * from a model, and a malformed answer is an expected event, not an
 * exceptional one. Throwing would turn "the model was wrong" into "the app
 * crashed", and the whole point of the agent loop is to survive a bad answer
 * and try again with the error message.
 *
 * The writer exists for JSON Schema documents, which the agent hands to the
 * model provider. It is not a general-purpose serialiser and does not try to
 * be.
 */
object Json {

    /** Nesting limit. Hostile or confused input cannot blow the stack. */
    private const val MAX_DEPTH = 64

    /**
     * Parses [text] into a value, or returns `null` when the text is not
     * valid JSON.
     */
    fun parse(text: String): JsonValue? = try {
        val reader = Reader(text)
        val value = reader.readValue(0)
        reader.skipWhitespace()
        if (reader.atEnd()) value else null
    } catch (_: Exception) {
        null
    }

    /**
     * Writes [value] as JSON text. Output is compact and stable: field order
     * follows the map's iteration order, which for the maps this project
     * builds is insertion order.
     */
    fun write(value: JsonValue): String = buildString { writeTo(this, value) }

    private fun writeTo(out: StringBuilder, value: JsonValue) {
        when (value) {
            is JsonValue.Null -> out.append("null")
            is JsonValue.Bool -> out.append(if (value.value) "true" else "false")
            is JsonValue.Num -> out.append(formatNumber(value.value))
            is JsonValue.Str -> writeString(out, value.value)
            is JsonValue.Arr -> {
                out.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    writeTo(out, item)
                }
                out.append(']')
            }
            is JsonValue.Obj -> {
                out.append('{')
                var first = true
                for ((key, item) in value.fields) {
                    if (!first) out.append(',')
                    first = false
                    writeString(out, key)
                    out.append(':')
                    writeTo(out, item)
                }
                out.append('}')
            }
        }
    }

    /**
     * Numbers are written the way a model would read them back: integral
     * values without a fractional part, everything else with enough digits to
     * survive the round trip.
     */
    private fun formatNumber(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "0"
        if (value == value.toLong().toDouble()) return value.toLong().toString()
        return value.toString()
    }

    private fun writeString(out: StringBuilder, text: String) {
        out.append('"')
        for (ch in text) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (ch < ' ') {
                    out.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(ch)
                }
            }
        }
        out.append('"')
    }

    private class Reader(private val text: String) {
        private var index = 0

        fun atEnd(): Boolean = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun readValue(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) throw IllegalArgumentException("json too deep")
            skipWhitespace()
            if (atEnd()) throw IllegalArgumentException("unexpected end of json")
            return when (val ch = text[index]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> JsonValue.Str(readString())
                't' -> { expect("true"); JsonValue.Bool(true) }
                'f' -> { expect("false"); JsonValue.Bool(false) }
                'n' -> { expect("null"); JsonValue.Null }
                else -> if (ch == '-' || ch in '0'..'9') readNumber()
                else throw IllegalArgumentException("unexpected character '$ch'")
            }
        }

        private fun readObject(depth: Int): JsonValue.Obj {
            expect("{")
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') { index++; return JsonValue.Obj(fields) }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw IllegalArgumentException("object key must be a string")
                val key = readString()
                skipWhitespace()
                expect(":")
                fields[key] = readValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> { index++; return JsonValue.Obj(fields) }
                    else -> throw IllegalArgumentException("expected ',' or '}'")
                }
            }
        }

        private fun readArray(depth: Int): JsonValue.Arr {
            expect("[")
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') { index++; return JsonValue.Arr(items) }
            while (true) {
                items += readValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> { index++; return JsonValue.Arr(items) }
                    else -> throw IllegalArgumentException("expected ',' or ']'")
                }
            }
        }

        private fun readString(): String {
            expect("\"")
            val out = StringBuilder()
            while (true) {
                if (atEnd()) throw IllegalArgumentException("unterminated string")
                when (val ch = text[index++]) {
                    '"' -> return out.toString()
                    '\\' -> out.append(readEscape())
                    else -> out.append(ch)
                }
            }
        }

        private fun readEscape(): Char {
            if (atEnd()) throw IllegalArgumentException("unterminated escape")
            return when (val ch = text[index++]) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (index + 4 > text.length) throw IllegalArgumentException("bad unicode escape")
                    val code = text.substring(index, index + 4).toIntOrNull(16)
                        ?: throw IllegalArgumentException("bad unicode escape")
                    index += 4
                    code.toChar()
                }
                else -> throw IllegalArgumentException("bad escape '\\$ch'")
            }
        }

        private fun readNumber(): JsonValue.Num {
            val start = index
            if (peek() == '-') index++
            while (!atEnd() && text[index] in '0'..'9') index++
            if (!atEnd() && text[index] == '.') {
                index++
                while (!atEnd() && text[index] in '0'..'9') index++
            }
            if (!atEnd() && (text[index] == 'e' || text[index] == 'E')) {
                index++
                if (!atEnd() && (text[index] == '+' || text[index] == '-')) index++
                while (!atEnd() && text[index] in '0'..'9') index++
            }
            val raw = text.substring(start, index)
            val number = raw.toDoubleOrNull() ?: throw IllegalArgumentException("bad number '$raw'")
            return JsonValue.Num(number)
        }

        private fun expect(literal: String) {
            if (!text.startsWith(literal, index)) {
                throw IllegalArgumentException("expected '$literal' at $index")
            }
            index += literal.length
        }

        private fun peek(): Char = if (atEnd()) ' ' else text[index]
    }
}
