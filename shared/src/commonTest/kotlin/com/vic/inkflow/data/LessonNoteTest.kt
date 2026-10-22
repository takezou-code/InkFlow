package com.vic.inkflow.data

import com.vic.inkflow.json.Json
import com.vic.inkflow.schema.validate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LessonNoteTest {

    private val valid = """
        {"title":"Derivatives","blocks":[
          {"kind":"heading","text":"Chapter 3"},
          {"kind":"paragraph","text":"A derivative measures change."},
          {"kind":"bullet","text":"Derivative of x^2 is 2x"},
          {"kind":"example","text":"d/dx x^2","detail":"2x"},
          {"kind":"flashcard","text":"d/dx x^2?","detail":"2x"},
          {"kind":"quiz","text":"d/dx x^2?","options":["2x","x^2"],"answerIndex":0}
        ]}
    """.trimIndent()

    @Test
    fun decodesEveryBlockKind() {
        val note = LessonNote.decode(Json.parse(valid)!!)
        assertEquals("Derivatives", note?.title)
        assertEquals(6, note?.blocks?.size)
        assertTrue(note?.blocks?.get(3) is NoteBlock.Example)
        assertTrue(note?.blocks?.get(4) is NoteBlock.Flashcard)
        assertTrue(note?.blocks?.get(5) is NoteBlock.Quiz)
        assertEquals(0, (note?.blocks?.get(5) as NoteBlock.Quiz).answerIndex)
    }

    @Test
    fun reEncodesToTheSameShape() {
        val note = LessonNote.decode(Json.parse(valid)!!)!!
        val again = LessonNote.decode(note.toJson())
        assertEquals(note, again)
    }

    @Test
    fun anUnknownBlockKindMakesTheNoteUnusable() {
        // A model that invents a block kind must not produce a half-decoded
        // note; the whole answer is rejected instead.
        assertNull(LessonNote.decode(Json.parse("""{"title":"t","blocks":[{"kind":"song","text":"x"}]}""")!!))
    }

    @Test
    fun anExampleWithoutAnAnswerIsRejected() {
        assertNull(LessonNote.decode(Json.parse("""{"title":"t","blocks":[{"kind":"example","text":"q"}]}""")!!))
    }

    @Test
    fun aQuizWithoutUsableOptionsIsRejected() {
        assertNull(LessonNote.decode(Json.parse("""{"title":"t","blocks":[{"kind":"quiz","text":"q","options":["a"],"answerIndex":0}]}""")!!))
        assertNull(LessonNote.decode(Json.parse("""{"title":"t","blocks":[{"kind":"quiz","text":"q","options":["a","b"],"answerIndex":5}]}""")!!))
    }

    @Test
    fun anEmptyNoteIsRejected() {
        assertNull(LessonNote.decode(Json.parse("""{"title":"t","blocks":[]}""")!!))
        assertNull(LessonNote.decode(Json.parse("""{"title":"t"}""")!!))
    }

    @Test
    fun theSchemaMatchesWhatTheDecoderAccepts() {
        // The schema is what the model is constrained to, so it has to accept
        // the same documents the decoder accepts.
        val value = Json.parse(valid)!!
        assertEquals(emptyList(), LessonNote.schema.validate(value))
    }
}
