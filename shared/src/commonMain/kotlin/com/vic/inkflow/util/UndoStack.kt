package com.vic.inkflow.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 *
 * ## Two ways to take a command off the stack
 *
 * [popUndo] / [popRedo] move the command to the other branch **for you** — the
 * common case.
 *
 * [takeUndo] / [takeRedo] hand it over **without** moving it, for the case where
 * the command is rewritten on the way: the tablet's undo replaces the command with a
 * variant (a page-delete that actually kept the page becomes a different undo
 * entry). Two steps to get right per call site is exactly the kind of thing that
 * silently loses history when one is forgotten — so the explicit pair exists, and
 * the comment on [pushRedo] says when to use it.
 *
 * ## The depth cap is tuning, not a rule
 *
 * The cap differs per platform on purpose (the tablet keeps a longer session
 * history; the desktop's entries are whole gestures and cost more memory). What
 * must be the same is the *rule* — oldest first, a new command invalidates the
 * redo branch — and that lives here.
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

    /**
     * Bumped by [reset].
     *
     * A late-arriving write (the tablet's erase settlement lands a few
     * milliseconds after the gesture) must not push a command built against a
     * history that has since been cleared — that is a ghost entry referring to
     * strokes that no longer exist. The caller captures [generation] when it starts
     * and checks [isCurrentGeneration] before committing.
     */
    private var _generation = 0
    val generation: Int get() = _generation

    fun isCurrentGeneration(generation: Int): Boolean = generation == _generation

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

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
        refresh()
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
    fun popUndo(): C? = undoStack.removeLastOrNull()?.also { redoStack.addLast(it) }.also { refresh() }

    /**
     * Removes the command to re-apply and moves it back onto the undo branch, or
     * returns null when there is nothing to redo.
     */
    fun popRedo(): C? = redoStack.removeLastOrNull()?.also { undoStack.addLast(it) }.also { refresh() }

    /**
     * Takes the command to reverse **without** moving it to the redo branch.
     *
     * Use this when the command is rewritten on the way (the tablet's undo turns a
     * page-delete-that-kept-the-page into a different entry); then hand the
     * replacement to [pushRedo].
     */
    fun takeUndo(): C? = undoStack.removeLastOrNull().also { refresh() }

    /** Takes the command to re-apply without moving it to the undo branch. */
    fun takeRedo(): C? = redoStack.removeLastOrNull().also { refresh() }

    /**
     * Puts a command onto the redo branch — the counterpart of [takeUndo].
     *
     * The command is usually a *rewritten* version of the one that was taken, which
     * is the whole reason [takeUndo] exists.
     */
    fun pushRedo(command: C) {
        redoStack.addLast(command)
        refresh()
    }

    /** Puts a command back onto the undo branch — the counterpart of [takeRedo]. */
    fun pushUndo(command: C) {
        undoStack.addLast(command)
        while (undoStack.size > maxDepth) undoStack.removeFirst()
        refresh()
    }

    /**
     * Drops all history.
     *
     * For when the document changes underneath: undo entries that reference strokes
     * from a different document would resurrect content that is no longer there.
     */
    fun reset() {
        undoStack.clear()
        redoStack.clear()
        _generation++
        refresh()
    }

    private fun refresh() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
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
