package com.vic.inkflow.agent

import com.vic.inkflow.data.LessonNote

/**
 * A one-method bridge from the agent panel to the reader's page.
 *
 * The agent panel lives outside the reader's composable scope, but the note
 * has to land in the reader's page — the one with the ink, the undo stack and
 * the proposal queue. A shared holder is the smallest thing that connects the
 * two without moving either of them: the reader binds its landing function
 * while it is on screen, the panel calls it when the user accepts the note.
 */
class NoteLandingHandle {

    private var lander: ((LessonNote) -> Int)? = null

    /**
     * Lands [note] on the page the reader is showing.
     *
     * @return how many blocks were placed; `0` when no reader is bound.
     */
    fun land(note: LessonNote): Int = lander?.invoke(note) ?: 0

    /** Whether a reader is currently bound. */
    val isBound: Boolean get() = lander != null

    internal fun bind(lander: (LessonNote) -> Int) {
        this.lander = lander
    }

    internal fun unbind() {
        lander = null
    }
}
