package com.vic.inkflow.agent

import com.vic.inkflow.agent.EditPlan

/**
 * A one-session bridge from the agent panel to the reader's page for the
 * *preview* of a proposed edit — the whole-page diff the user approves before
 * anything is persisted.
 *
 * Note [NoteLandingHandle]; this works the same way but carries three
 * callbacks instead of one:
 *
 *  - [preview] simulates the plan against the current page's text set and
 *    renders the result as a ghost layer. Purely in-memory; nothing is
 *    written and nothing is queued.
 *  - [accept] reloads the page's text from the database and drops the preview
 *    layer — called *after* the plan has been persisted by the panel.
 *  - [clear] discards the preview layer without persisting — the reject button.
 *
 * Only the focused page binds, so the preview renders where the user is
 * looking, not wherever the last composed page happened to be.
 */
class PreviewHandle {

    data class Session(
        /** Simulate and show. Returns counts + problems for the panel. */
        val preview: (EditPlan) -> PreviewAck,
        /** Reload texts from the database and clear the overlay (after apply). */
        val accept: () -> Unit,
        /** Discard the overlay (reject). */
        val clear: () -> Unit
    )

    private var session: Session? = null

    val isBound: Boolean get() = session != null

    /** Simulate the plan on the bound page and show the result. */
    fun preview(plan: EditPlan): PreviewAck? = session?.preview?.invoke(plan)

    /** Reload the page text from the DB and drop the overlay. */
    fun accept() {
        session?.accept?.invoke()
    }

    /** Drop the overlay without persisting. */
    fun clear() {
        session?.clear?.invoke()
    }

    internal fun bind(session: Session) {
        this.session = session
    }

    internal fun unbind() {
        this.session = null
    }
}

/** What the panel gets back from a preview run — counts, and what the model did wrong. */
data class PreviewAck(
    val added: Int,
    val changed: Int,
    val deleted: Int,
    val problems: List<String>,
    val isNoop: Boolean
)
