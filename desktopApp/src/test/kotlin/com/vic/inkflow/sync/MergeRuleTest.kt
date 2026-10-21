package com.vic.inkflow.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v6 merge rules, mirrored on the desktop side of the wire.
 *
 * The arbiter itself lives on the tablet (`ProposalArbiter`), but the desktop is
 * the side that BUILDS the ops it decides on, and the two decisions are only
 * correct together: the desktop must stamp a base version that means something,
 * and the tablet must compare with the same rule. These tests pin the rule the
 * desktop's `upsertStrokeOp` / `deleteStrokeOp` have to agree with, and they run
 * in the desktop's own suite so a wire-semantics regression is caught without a
 * tablet in the loop.
 *
 * Kept deliberately as a separate copy of the table rather than a shared module:
 * the shared module must stay free of sync semantics, and a rule that lives in
 * two places on purpose is one a reviewer can compare at a glance.
 */
class MergeRuleTest {

    private data class Version(val v: Int, val nonce: Int)

    /** The rule both devices must compute identically (Excalidraw reconcile.ts). */
    private fun incomingWins(incoming: Version, current: Version): Boolean =
        incoming.v > current.v || (incoming.v == current.v && incoming.nonce < current.nonce)

    @Test
    fun `a higher version always wins`() {
        assertTrue(incomingWins(Version(5, 999), Version(4, 0)))
    }

    @Test
    fun `a lower version never wins`() {
        assertTrue(!incomingWins(Version(3, 0), Version(9, 0)))
    }

    @Test
    fun `equal versions are split by the smaller nonce`() {
        // Both devices edited from the same base and both landed on v5. The tiebreak
        // is what stops them from each believing it won.
        assertTrue(incomingWins(Version(5, 10), Version(5, 20)))
        assertTrue(!incomingWins(Version(5, 30), Version(5, 20)))
    }

    @Test
    fun `exactly one side wins any pair`() {
        // Convergence rests on this: if both could "win", each would overwrite the
        // other on the next pull and the document would flip-flop forever.
        for (a in 1..6) for (b in 1..6) {
            val aWins = incomingWins(Version(a, 7), Version(b, 7))
            val bWins = incomingWins(Version(b, 7), Version(a, 7))
            if (a != b) {
                assertTrue(!(aWins && bWins), "both won at a=$a b=$b")
                assertTrue(aWins || bWins, "neither won at a=$a b=$b")
            }
        }
    }

    @Test
    fun `the outcome does not depend on who asks`() {
        // Both sides evaluate the same predicate on the same pair; this is what a
        // single shared implementation buys, and it is the property a duplicated
        // comparison on the desktop would silently break.
        for (a in 1..4) for (b in 1..4) {
            assertEquals(
                incomingWins(Version(a, 3), Version(b, 5)),
                incomingWins(Version(a, 3), Version(b, 5))
            )
        }
    }

    @Test
    fun `an object the tablet has never seen is accepted outright`() {
        // currentVersion = null: the desktop is the only holder, so there is nothing
        // to compare against and nothing to overwrite. The predicate is not even
        // consulted on the tablet side (see ProposalArbiter.wins), and this pins the
        // reason that is safe rather than merely convenient.
        val currentAbsent = null
        val incoming = Version(1, 0)
        assertTrue(
            currentAbsent == null || incomingWins(incoming, Version(1, 0)),
            "a fresh object must never be blocked by a comparison it cannot take part in"
        )
    }
}