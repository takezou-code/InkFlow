package com.vic.inkflow.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arbitration decision is the three lines the whole of bidirectional sync
 * stands on: accept what should be accepted, reject the rest, never merge.
 *
 * A wrong accept is silent overwriting; a wrong reject makes the user's edits
 * vanish. Both look like "sync works" until someone compares the two devices —
 * which is why this is pinned here instead of only exercised through a live
 * tablet.
 */
class ProposalArbiterTest {

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
    fun movedVersionConflictsWithEveryTouchedId() {
        val d = ProposalArbiter.decide(
            currentVersion = "v11", currentInstance = "inst-a",
            baseVersion = "v10", baseInstance = "inst-a",
            opIds = listOf("s1", "n2")
        )
        assertTrue("got $d", d is ProposalArbiter.Decision.ConflictStale)
        assertEquals(listOf("s1", "n2"), (d as ProposalArbiter.Decision.ConflictStale).conflictIds)
    }

    @Test
    fun movedGenerationRejectsWithoutComparingVersions() {
        // The versions belong to different installs and are not comparable at all:
        // even an "equal" pair must reject, because equality across generations is
        // a coincidence, not an agreement.
        val d = ProposalArbiter.decide(
            currentVersion = "v10", currentInstance = "inst-b",
            baseVersion = "v10", baseInstance = "inst-a",
            opIds = listOf("s1")
        )
        assertTrue("got $d", d is ProposalArbiter.Decision.Reject)
    }

    @Test
    fun nullBaseInstanceSkipsTheGenerationCheck() {
        // Older desktops never sent one. Treating null as "different" would reject
        // every proposal from a client that simply predates the field.
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
    fun emptyOpListStillFollowsTheVersionRule() {
        // An empty proposal against a moved base is stale, not vacuously accepted:
        // accepting it would advance nothing while claiming agreement.
        val d = ProposalArbiter.decide(
            currentVersion = "v11", currentInstance = "inst-a",
            baseVersion = "v10", baseInstance = "inst-a",
            opIds = emptyList()
        )
        assertTrue("got $d", d is ProposalArbiter.Decision.ConflictStale)
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
}
