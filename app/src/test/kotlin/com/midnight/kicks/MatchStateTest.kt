package com.midnight.kicks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [MatchState]. Covers:
 *  - every variant resolves a non-empty label,
 *  - the `address` getter propagates correctly through [MatchState.Resolved]
 *    and [MatchState.Failed],
 *  - states that legitimately have no address return null.
 */
class MatchStateTest {

    private val address = "deadbeef".repeat(8)
    private val sampleResult = MatchResult(
        p1Shoots = intArrayOf(0, 1, 2, 1, 0),
        p1Keeps  = intArrayOf(2, 1, 0, 1, 2),
        p2Shoots = intArrayOf(2, 0, 1, 2, 0),
        p2Keeps  = intArrayOf(0, 2, 1, 0, 2),
        sdRounds = emptyList(),
        contractAddress = address,
    )

    private val allStates: List<MatchState> = listOf(
        MatchState.Idle,
        MatchState.InitializingSdk,
        MatchState.SdkReady,
        MatchState.Deploying,
        MatchState.Deployed(address),
        MatchState.JoiningAsP2(address),
        MatchState.Joined(address),
        MatchState.P1Committing(address),
        MatchState.P1Committed(address),
        MatchState.P2Committing(address),
        MatchState.BothCommitted(address),
        MatchState.P1Revealing(address),
        MatchState.P1Revealed(address),
        MatchState.P2Revealing(address),
        MatchState.SdRoundOpen(address, round = 1),
        MatchState.P1SdCommitting(address, round = 1),
        MatchState.P1SdCommitted(address, round = 1),
        MatchState.P2SdCommitting(address, round = 1),
        MatchState.BothSdCommitted(address, round = 1),
        MatchState.P1SdRevealing(address, round = 1),
        MatchState.P1SdRevealed(address, round = 1),
        MatchState.P2SdRevealing(address, round = 1),
        MatchState.Resolved(sampleResult),
        MatchState.Failed(MatchState.Deployed(address), RuntimeException("boom")),
    )

    @Test
    fun `every state variant has a non-empty label`() {
        for (state in allStates) {
            val label = state.label
            assertTrue("Label for ${state::class.simpleName} is blank", label.isNotBlank())
        }
    }

    @Test
    fun `labels are unique per state class`() {
        // Variants with the same class but different params (Deployed,
        // JoiningAsP2, …) share a label by design. But two different
        // state classes should never collide.
        val labelsByClass = allStates.associate { it::class.simpleName to it.label }
        assertEquals(
            "Class-to-label mapping is not 1:1",
            labelsByClass.values.toSet().size,
            labelsByClass.size,
        )
    }

    @Test
    fun `address is null for pre-deploy states`() {
        assertNull(MatchState.Idle.address)
        assertNull(MatchState.InitializingSdk.address)
        assertNull(MatchState.SdkReady.address)
        assertNull(MatchState.Deploying.address)
    }

    @Test
    fun `address is set for post-deploy states`() {
        val withAddress = listOf(
            MatchState.Deployed(address),
            MatchState.JoiningAsP2(address),
            MatchState.Joined(address),
            MatchState.P1Committing(address),
            MatchState.P1Committed(address),
            MatchState.P2Committing(address),
            MatchState.BothCommitted(address),
            MatchState.P1Revealing(address),
            MatchState.P1Revealed(address),
            MatchState.P2Revealing(address),
            MatchState.SdRoundOpen(address, round = 1),
            MatchState.BothSdCommitted(address, round = 2),
        )
        for (state in withAddress) {
            assertEquals("Address for ${state::class.simpleName}", address, state.address)
        }
    }

    @Test
    fun `SD state labels mention the round number`() {
        val r3 = MatchState.SdRoundOpen(address, round = 3)
        assertTrue(r3.label, r3.label.contains("3"))

        val committing = MatchState.P1SdCommitting(address, round = 7)
        assertTrue(committing.label, committing.label.contains("7"))
    }

    @Test
    fun `Resolved address comes from the contained MatchResult`() {
        val resolved = MatchState.Resolved(sampleResult)
        assertEquals(address, resolved.address)
    }

    @Test
    fun `Failed propagates the previous state's address`() {
        val failedFromDeployed = MatchState.Failed(MatchState.Deployed(address), Exception("x"))
        assertEquals(address, failedFromDeployed.address)

        val failedFromIdle = MatchState.Failed(MatchState.Idle, Exception("x"))
        assertNull(failedFromIdle.address)
    }

    @Test
    fun `Failed label includes the underlying error message`() {
        val failed = MatchState.Failed(MatchState.Joined(address), Exception("indexer down"))
        assertTrue("Label should contain 'indexer down': ${failed.label}", failed.label.contains("indexer down"))
    }

    @Test
    fun `Failed label falls back to class name when error message is null`() {
        val failed = MatchState.Failed(MatchState.Joined(address), Exception())
        // Bare Exception has null message → label uses simple class name.
        assertNotNull(failed.label)
        assertTrue("Label should mention exception class: ${failed.label}", failed.label.contains("Exception"))
    }
}
