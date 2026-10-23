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

    private var lander: ((LessonNote) -> NoteLanding)? = null

    /**
     * Lands [note] on the page the reader is showing.
     *
     * @return what happened, as a value rather than a count: "wrote 3 blocks",
     *   "the page is full" and "the save failed" are three different things to
     *   tell the user, and a count cannot express the last two.
     *   [NoteLanding.NoPage] when no reader is bound.
     */
    fun land(note: LessonNote): NoteLanding = lander?.invoke(note) ?: NoteLanding.NoPage

    /** Whether a reader is currently bound. */
    val isBound: Boolean get() = lander != null

    internal fun bind(lander: (LessonNote) -> NoteLanding) {
        this.lander = lander
    }

    internal fun unbind() {
        lander = null
    }
}
