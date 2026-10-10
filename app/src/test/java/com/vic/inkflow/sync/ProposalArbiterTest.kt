package com.vic.inkflow.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arbitration decision is the most dangerous few lines in bidirectional sync:
 * a wrong accept silently overwrites, a wrong reject makes someone's edit vanish.
 *
 * v6 changed the granularity from "the whole document" to "one object". The old
 * tests pinned "a moved version rejects every touched id" — which is exactly the
 * behaviour that lost a page-7 edit because the tablet had touched page 3. These
 * tests pin the replacement: unrelated ids survive, genuinely concurrent ones
 * conflict, and a tie is broken the same way on both devices.
 */
class ProposalArbiterTest {

    private fun opState(
        id: String,
        incomingVersion: Int,
        incomingNonce: Int = 0,
        currentVersion: Int? = null,
        currentNonce: Int = 0,
        baseVersion: Int? = null,
        currentDeleted: Boolean = false
    ) = ProposalArbiter.OpState(
        id = id,
        incomingVersion = incomingVersion,
        incomingNonce = incomingNonce,
        currentVersion = currentVersion,
        currentNonce = currentNonce,
        baseVersion = baseVersion,
        currentDeleted = currentDeleted
    )

    private fun decide(states: List<ProposalArbiter.OpState>, kinds: Map<String, ProposalArbiter.OpKind> = emptyMap()) =
        ProposalArbiter.decideOps(
            currentVersion = "v10",
            currentInstance = "inst-a",
            baseVersion = "v10",
            baseInstance = "inst-a",
            opIds = states.map { it.id },
            opStates = states,
            kinds = kinds
        )

    // ── v5 semantics that must not change ───────────────────────────────────

    @Test
    fun matchingBaseAndGenerationAccepts() {
        assertEquals(
            ProposalArbiter.Decision.Accept,
            ProposalArbiter.decide(
                currentVersion = "v10", currentInstance = "inst-a",
                baseVersion = "v10", baseInstance = "inst-a",
                opIds = listOf("s1")
            )
        )
    }

    @Test
    fun movedGenerationRejectsWithoutComparingVersions() {
        val d = ProposalArbiter.decide(
            currentVersion = "v10", currentInstance = "inst-b",
            baseVersion = "v10", baseInstance = "inst-a",
            opIds = listOf("s1")
        )
        assertTrue("got $d", d is ProposalArbiter.Decision.Reject)
    }

    @Test
    fun nullBaseInstanceSkipsTheGenerationCheck() {
        assertEquals(
            ProposalArbiter.Decision.Accept,
            ProposalArbiter.decide(
                currentVersion = "v10", currentInstance = "inst-a",
                baseVersion = "v10", baseInstance = null,
                opIds = emptyList()
            )
        )
    }

    @Test
    fun versionsAreComparedExactlyNotByPrefix() {
        val d = ProposalArbiter.decide(
            currentVersion = "abc123", currentInstance = "i",
            baseVersion = "abc", baseInstance = "i",
            opIds = listOf("s1")
        )
        assertTrue("got $d", d is ProposalArbiter.Decision.ConflictStale)
    }

    /**
     * A moved DOCUMENT version still forces a whole-proposal refetch: the document
     * hash covers the file body and the page set, which per-object versions do not
     * describe. Losing that rule would let a replaced PDF keep its old annotations.
     */
    @Test
    fun movedDocumentVersionStillConflictsEverything() {
        val d = ProposalArbiter.decide(
            currentVersion = "v11", currentInstance = "inst-a",
            baseVersion = "v10", baseInstance = "inst-a",
            opIds = listOf("s1", "n2")
        )
        assertTrue("got $d", d is ProposalArbiter.Decision.ConflictStale)
        assertEquals(listOf("s1", "n2"), (d as ProposalArbiter.Decision.ConflictStale).conflictIds)
    }

    // ── v6 per-object merge ────────────────────────────────────────────────

    @Test
    fun editsToUnrelatedObjectsBothSurvive() {
        // The whole point of v6: the tablet drew on page 3, the desktop on page 7.
        // v5 dropped the desktop's page entirely; here both are accepted.
        val d = decide(
            listOf(
                opState("s-tablet", incomingVersion = 2),
                opState("s-desktop", incomingVersion = 2)
            )
        )
        assertEquals(
            ProposalArbiter.Decision.Accept,
            d
        )
    }

    @Test
    fun aNewObjectIsAlwaysAccepted() {
        // The desktop is the only holder: rejecting would delete the user's work.
        val d = decide(listOf(opState("s-new", incomingVersion = 1, currentVersion = null)))
        assertEquals(ProposalArbiter.Decision.Accept, d)
    }

    @Test
    fun higherIncomingVersionWins() {
        val d = decide(listOf(opState("s1", incomingVersion = 7, currentVersion = 3)))
        assertEquals(ProposalArbiter.Decision.Accept, d)
    }

    @Test
    fun lowerIncomingVersionConflicts() {
        // The tablet moved ahead: this is the stale-write case that v5 dropped
        // wholesale and v6 now keeps as a copy.
        val d = decide(listOf(opState("s1", incomingVersion = 2, currentVersion = 9)))
        assertTrue("got $d", d is ProposalArbiter.Decision.Merge)
        assertEquals(listOf("s1"), (d as ProposalArbiter.Decision.Merge).conflictIds)
        assertTrue(d.acceptIds.isEmpty())
    }

    @Test
    fun equalVersionsAreSplitByTheNonce() {
        // Both devices edited from the same base: both land on version 5.
        // Smaller nonce wins, and both sides compute that identically.
        assertTrue(ProposalArbiter.incomingWins(5, 10, 5, 20))
        assertTrue(!ProposalArbiter.incomingWins(5, 30, 5, 20))
    }

    @Test
    fun aVersionTieBreakIsSymmetricAndOrdered() {
        // The two properties that make convergence possible: the winner is the
        // same whichever side asks, and asking twice gives the same answer.
        for (a in 1..5) for (b in 1..5) {
            assertEquals(
                ProposalArbiter.incomingWins(a, 0, b, 0),
                !ProposalArbiter.incomingWins(b, 0, a, 0) || a == b,
                "a=$a b=$b must not both win"
            )
            assertEquals(
                ProposalArbiter.incomingWins(a, 7, b, 7),
                ProposalArbiter.incomingWins(a, 7, b, 7)
            )
        }
    }

    @Test
    fun mixedProposalAcceptsTheWinnableOpsAndConflictsTheRest() {
        val d = decide(
            listOf(
                opState("s-new", incomingVersion = 1, currentVersion = null),
                opState("s-mine", incomingVersion = 5, currentVersion = 2),
                opState("s-theirs", incomingVersion = 2, currentVersion = 8)
            )
        )
        assertTrue("got $d", d is ProposalArbiter.Decision.Merge)
        val m = d as ProposalArbiter.Decision.Merge
        assertEquals(listOf("s-new", "s-mine"), m.acceptIds)
        assertEquals(listOf("s-theirs"), m.conflictIds)
    }

    @Test
    fun anUnresolvableObjectConflictsRatherThanOverwriting() {
        // No version state at all. Accepting could overwrite; rejecting could
        // delete. The copy path is the only one where neither happens.
        val d = ProposalArbiter.decideOps(
            currentVersion = "v10", currentInstance = "inst-a",
            baseVersion = "v10", baseInstance = "inst-a",
            opIds = listOf("s1"),
            opStates = emptyList()
        )
        assertEquals(ProposalArbiter.Decision.Accept, d)

        val partial = ProposalArbiter.decideOps(
            currentVersion = "v10", currentInstance = "inst-a",
            baseVersion = "v10", baseInstance = "inst-a",
            opIds = listOf("s-known", "s-unknown"),
            opStates = listOf(opState("s-known", incomingVersion = 3, currentVersion = 1))
        )
        assertTrue("got $partial", partial is ProposalArbiter.Decision.Merge)
        assertEquals(
            listOf("s-unknown"),
            (partial as ProposalArbiter.Decision.Merge).conflictIds
        )
    }

    @Test
    fun noVersionInfoFallsBackToWholeProposalAccept() {
        // A v5 client sends no per-object versions at all. There is nothing to
        // arbitrate on, so the whole proposal passes under the document rule —
        // which is why PROTOCOL_VERSION had to bump (mixing the two would let a
        // v5-shaped payload merge on a v6 tablet).
        assertEquals(
            ProposalArbiter.Decision.Accept,
            ProposalArbiter.decideOps("v10", "i", "v10", "i", listOf("s1"), emptyList())
        )
    }

    // ── deletion vs modification (§15.4) ───────────────────────────────────

    @Test
    fun deleteWinsWhenNobodyTouchedTheObject() {
        val d = decide(
            listOf(opState("s1", incomingVersion = 5, currentVersion = 5, baseVersion = 5)),
            mapOf("s1" to ProposalArbiter.OpKind.DELETE)
        )
        assertEquals(ProposalArbiter.Decision.Accept, d)
    }

    @Test
    fun modificationBeatsDeleteWhenTheOtherSideChangedItFirst() {
        // Desktop deletes what the tablet just moved. Dropping the modification
        // would erase a stroke the user still has in front of them.
        val d = decide(
            listOf(opState("s1", incomingVersion = 5, currentVersion = 8, baseVersion = 5)),
            mapOf("s1" to ProposalArbiter.OpKind.DELETE)
        )
        assertTrue("got $d", d is ProposalArbiter.Decision.Merge)
    }

    @Test
    fun deletingAnAlreadyDeletedObjectIsIdempotent() {
        val d = decide(
            listOf(opState("s1", incomingVersion = 5, currentVersion = 5, baseVersion = 4, currentDeleted = true)),
            mapOf("s1" to ProposalArbiter.OpKind.DELETE)
        )
        assertEquals(ProposalArbiter.Decision.Accept, d)
    }

    @Test
    fun anUpsertRevivesATombstonedObject() {
        // Modify-wins: the content is still wanted, so it comes back (applyOp
        // clears deletedAt). Tombstoning it again would silently lose ink.
        val d = decide(
            listOf(opState("s1", incomingVersion = 9, currentVersion = 4, currentDeleted = true))
        )
        assertEquals(ProposalArbiter.Decision.Accept, d)
    }

    @Test
    fun anUpsertOlderThanATombstoneConflicts() {
        val d = decide(
            listOf(opState("s1", incomingVersion = 3, currentVersion = 9, currentDeleted = true))
        )
        assertTrue("got $d", d is ProposalArbiter.Decision.Merge)
    }
}