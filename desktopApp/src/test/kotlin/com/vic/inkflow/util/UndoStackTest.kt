package com.vic.inkflow.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The history discipline, tested without any database or Compose involvement.
 *
 * This is the part that both platforms share, and the part that is easy to get
 * subtly wrong: a redo branch that survives a new action produces a "future" the
 * user can scroll into even though nothing ever led there.
 */
class UndoStackTest {

    private sealed interface Cmd {
        data class Add(val id: Int) : Cmd
        data class Erase(val ids: List<Int>) : Cmd
    }

    @Test
    fun `empty stack offers neither undo nor redo`() {
        val s = UndoStack<Cmd>()
        assertFalse(s.canUndo)
        assertFalse(s.canRedo)
        assertNull(s.popUndo())
        assertNull(s.popRedo())
    }

    @Test
    fun `push then undo returns the command in reverse order`() {
        val s = UndoStack<Cmd>()
        s.push(Cmd.Add(1))
        s.push(Cmd.Add(2))
        s.push(Cmd.Erase(listOf(3, 4)))

        assertEquals(Cmd.Erase(listOf(3, 4)), s.popUndo())
        assertEquals(Cmd.Add(2), s.popUndo())
        assertEquals(Cmd.Add(1), s.popUndo())
        assertNull(s.popUndo())
        assertFalse(s.canUndo)
    }

    @Test
    fun `popRedo yields commands oldest first`() {
        val s = UndoStack<Cmd>()
        s.push(Cmd.Add(1))
        s.push(Cmd.Add(2))
        s.popUndo()
        s.popUndo()

        assertEquals(Cmd.Add(1), s.popRedo())
        assertEquals(Cmd.Add(2), s.popRedo())
        assertNull(s.popRedo())
    }

    @Test
    fun `a new action discards the redo branch`() {
        val s = UndoStack<Cmd>()
        s.push(Cmd.Add(1))
        s.push(Cmd.Add(2))
        s.popUndo()
        assertTrue(s.canRedo)

        s.push(Cmd.Add(99))

        assertFalse(s.canRedo, "redo must not survive a new action")
        assertNull(s.popRedo())
        // The original Add(2) must not be reachable in either direction any more.
        assertEquals(Cmd.Add(99), s.popUndo())
        assertEquals(Cmd.Add(1), s.popUndo())
    }

    @Test
    fun `depth cap drops the oldest entries`() {
        val s = UndoStack<Cmd>(maxDepth = 3)
        (1..5).forEach { s.push(Cmd.Add(it)) }

        assertEquals(3, s.undoDepth)
        assertEquals(Cmd.Add(5), s.popUndo())
        assertEquals(Cmd.Add(4), s.popUndo())
        assertEquals(Cmd.Add(3), s.popUndo())
        assertNull(s.popUndo(), "Add(1) and Add(2) should have aged out")
    }

    @Test
    fun `undo then redo round-trips the same command`() {
        val s = UndoStack<Cmd>()
        s.push(Cmd.Add(1))
        val undone = s.popUndo()
        assertEquals(Cmd.Add(1), undone)
        assertEquals(Cmd.Add(1), s.popRedo())
    }

    @Test
    fun `reset drops both directions`() {
        val s = UndoStack<Cmd>()
        s.push(Cmd.Add(1))
        s.popUndo()
        assertTrue(s.canRedo)

        s.reset()

        assertFalse(s.canUndo)
        assertFalse(s.canRedo)
    }

    @Test
    fun `a non-positive depth is rejected`() {
        assertFailsWith<IllegalArgumentException> { UndoStack<Cmd>(maxDepth = 0) }
    }
}