package com.vic.inkflow.data.repository

import com.vic.inkflow.data.store.StrokeArbitration
import com.vic.inkflow.data.store.StrokeAuthoring
import com.vic.inkflow.data.store.StrokeStore

/**
 * The tablet's stroke store — **the author side**, so it is the union of all three
 * shared halves:
 *  - [StrokeStore]: the storage basics, which every platform (including the
 *    mirrors) can do.
 *  - [StrokeArbitration]: the version/tombstone bookkeeping, which only the
 *    author keeps.
 *  - [StrokeAuthoring]: the structural edits (page moves, the docY backfill),
 *    which only the author performs.
 *
 * The desktop is a mirror and implements only the first half — and that is a fact
 * about the product, not a missing method.
 *
 * The split came from the desktop's existence: an interface that demands
 * `bumpStrokeVersion` from a mirror is one the mirror can only half-implement.
 *
 * [PageShiftTarget] is the three-step page move (temp index → shift → temp back)
 * that the page operations need; it stays part of the tablet's contract because
 * only the author moves pages.
 */
interface StrokeRepository : StrokeStore, StrokeArbitration, StrokeAuthoring, PageShiftTarget
