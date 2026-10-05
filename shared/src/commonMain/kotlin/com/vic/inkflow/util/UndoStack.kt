package com.vic.inkflow.util

/**
 * A bounded undo/redo history, generic over the command type.
 *
 * ## Why this is generic rather than the tablet's `DrawCommand`
 *
 * The tablet's `DrawCommand` (in `app/.../ui/DrawCommand.kt`) is a rich model —
 * move/resize/erase/extract across strokes, text annotations and image annotations.
 * But every case carries a Room entity, so it cannot go in `commonMain`, and the
 * desktop has none of the annotation tables to begin with. Copying the stroke-shaped
 * subset across would reintroduce a second copy of the same concepts, which is the
 * problem `:shared` exists to remove.
 *
 * What is genuinely shared is the *history discipline*, and that has nothing to do
 * with what a command contains. The caller supplies its own command type and knows
 * how to apply it forwards and backwards; this owns the stack, the depth cap, and
 * the redo-invalidation rule.
 *
 * ## The rule that is easy to get wrong
 *
 * Pushing a new command after an undo must discard the redo branch. Keeping it lets
 * the user undo back to a state, do something else, then redo into a history that no
 * longer exists — the classic "future that never was". That is why [push] clears
 * [redoStack] unconditionally rather than only when the stack is non-empty.
 */
class UndoStack<C>(
    /** Oldest entries are dropped past this depth. */
    private val maxDepth: Int = DEFAULT_MAX_DEPTH
) {
    init {
        require(maxDepth > 0) { "maxDepth must be positive, was $maxDepth" }
    }

    private val undoStack = ArrayDeque<C>()
    private val redoStack = ArrayDeque<C>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoDepth: Int get() = undoStack.size
    val redoDepth: Int get() = redoStack.size

    /**
     * Records a freshly applied command.
     *
     * Call this *after* the command has taken effect, so undo has something real to
     * reverse.
     */
    fun push(command: C) {
        undoStack.addLast(command)
        // A new action invalidates the redo branch unconditionally.
        redoStack.clear()
        while (undoStack.size > maxDepth) undoStack.removeFirst()
    }

    /**
     * Removes the command to reverse and moves it onto the redo branch, or returns null
     * when there is nothing to undo.
     *
     * The move is automatic on purpose. An earlier version left it to the caller
     * (`popRedo` plus a separate `pushRedone`), which is two steps to get right per
     * call site and silently loses history if one is forgotten — the exact failure
     * the unit tests here were written to catch.
     */
    fun popUndo(): C? = undoStack.removeLastOrNull()?.also { redoStack.addLast(it) }

    /**
     * Removes the command to re-apply and moves it back onto the undo branch, or
     * returns null when there is nothing to redo.
     */
    fun popRedo(): C? = redoStack.removeLastOrNull()?.also { undoStack.addLast(it) }

    /**
     * Drops all history.
     *
     * For when the document changes underneath: undo entries that reference strokes
     * from a different document would resurrect content that is no longer there.
     */
    fun reset() {
        undoStack.clear()
        redoStack.clear()
    }

    companion object {
        /**
         * Deep enough for a full editing session, shallow enough that holding the
         * payload of every step is not a memory problem. Each entry can carry a
         * whole erased gesture, so this is measured in gestures rather than strokes.
         */
        const val DEFAULT_MAX_DEPTH = 64
    }
}