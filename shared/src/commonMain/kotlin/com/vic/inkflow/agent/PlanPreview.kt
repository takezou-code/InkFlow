package com.vic.inkflow.agent

import com.vic.inkflow.data.TextAnnotation

/**
 * What applying an [EditPlan] would do to the document's text set, computed without
 * touching the database or the page.
 *
 * The preview is the bridge between "the agent proposed ops" and "the user sees
 * the finished page": the renderer draws [proposed] instead of the stored list,
 * highlights [addedIds] and [changedIds], and ghosts [deleted] as struck-through
 * outlines. Nothing is persisted until the user accepts — rejecting the preview
 * means throwing this value away.
 *
 * The simulation mirrors the write path exactly (same insert/replace semantics,
 * same field defaults), so the preview cannot promise something different from
 * what pressing "apply" will do. The rule it follows:
 *
 *  - `upsert_text` with an `id` that exists → replace (text/geometry/family
 *    from the op; `flowId`, `docY`, `boxWidth` kept when the op omits them).
 *  - `upsert_text` with an `id` that does not exist → insert keeping the model's
 *    id (the write path is an unconditional INSERT-or-REPLACE either way).
 *  - `upsert_text` without an `id` → insert with a preview-only id `pv-<n>`;
 *    accepting mints a real one.
 *  - `delete_text` with a missing id → listed in [problems], skipped there.
 *  - `*_stroke` ops → listed in [problems], skipped.
 */
data class PlanPreview(
    /** The text set after the plan, in drawing order (page, then baseline, then id). */
    val proposed: List<TextAnnotation>,
    /** Annotations the plan deletes — ghosts for the preview, real tombstones only on apply. */
    val deleted: List<TextAnnotation>,
    /** Preview ids of newly added annotations (`pv-<n>` or the model's id). */
    val addedIds: Set<String>,
    /** Ids of annotations whose content or geometry changed. */
    val changedIds: Set<String>,
    /** Ops the simulation could not understand; non-empty means the preview is partial. */
    val problems: List<String>
) {
    /** Applying the plan would change nothing. */
    val isNoop: Boolean get() = addedIds.isEmpty() && changedIds.isEmpty() && deleted.isEmpty()
}

fun previewEditPlan(
    documentUri: String,
    current: List<TextAnnotation>,
    plan: EditPlan
): PlanPreview {
    val byId = LinkedHashMap<String, TextAnnotation>()
    for (ann in current) byId[ann.id] = ann

    val addedIds = LinkedHashSet<String>()
    val changedIds = LinkedHashSet<String>()
    val removedIds = LinkedHashSet<String>()
    val problems = mutableListOf<String>()
    var previewCounter = 0

    for (op in plan.ops) {
        when (op) {
            is EditOp.UpsertText -> {
                val existing = op.id?.let(byId::get)
                val entity = if (existing == null) {
                    val id = op.id ?: "pv-${previewCounter++}"
                    addedIds += id
                    TextAnnotation(
                        id = id,
                        documentUri = documentUri,
                        pageIndex = op.pageIndex,
                        docY = null,
                        text = op.text,
                        modelX = op.modelX,
                        modelY = op.modelY,
                        fontSize = op.fontSize,
                        colorArgb = op.colorArgb,
                        isStamp = false,
                        // No op carries a flowId yet, so a new block starts as its
                        // own flow — same as the tablet's standalone new boxes.
                        boxWidth = op.boxWidth,
                        alignment = op.alignment
                    )
                } else {
                    changedIds += existing.id
                    existing.copy(
                        pageIndex = op.pageIndex,
                        text = op.text,
                        modelX = op.modelX,
                        modelY = op.modelY,
                        fontSize = op.fontSize,
                        colorArgb = op.colorArgb,
                        // boxWidth is kept when the op omits it: the edit path must not
                        // silently un-wrap a paragraph just because the model rewrote it.
                        boxWidth = op.boxWidth ?: existing.boxWidth,
                        alignment = op.alignment,
                        flowId = existing.flowId
                    )
                }
                byId[entity.id] = entity
            }
            is EditOp.DeleteText -> {
                val existing = byId.remove(op.id)
                if (existing == null) {
                    problems += "delete_text：找不到註記 ${op.id}，略過"
                } else {
                    removedIds += existing.id
                }
            }
            is EditOp.UpsertStroke -> problems += "upsert_stroke：尚未支援，略過"
            is EditOp.DeleteStroke -> problems += "delete_stroke：尚未支援，略過"
        }
    }

    val ghosts = current.filter { it.id in removedIds }
    val proposed = byId.values.sortedWith(compareBy({ it.pageIndex }, { it.modelY }, { it.id }))

    return PlanPreview(
        proposed = proposed,
        deleted = ghosts,
        addedIds = addedIds,
        changedIds = changedIds,
        problems = problems
    )
}
