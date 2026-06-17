package com.midnight.kicks

import com.midnight.kicks.MatchState as S
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for [MatchState.protocolRank] + [PhaseRank] + the
 * [MatchState.sdRound] extension.
 *
 * Why this matters: six `waitFor*` functions in [MatchManager] now use
 * `current.protocolRank >= PhaseRank.X` to skip idempotently when the
 * chain has already advanced past their target step. If the rank
 * ordering breaks (e.g. someone re-numbers SdRoundOpen above
 * P1SdCommitted), every wait function silently no-ops on legitimate
 * "still need to wait" states. Pin the ordering loud + machine-verified.
 *
 * Pairs with the audit fix for the `require(state is X)`-rigidity bug
 * class (Audit High #3, 2026-05-20).
 */
class MatchProtocolRankTest {

    @Test
    fun `pre-match states rank below SDK_READY`() {
        assertTrue(S.Idle.protocolRank < PhaseRank.SDK_READY)
        assertTrue(S.InitializingSdk.protocolRank < PhaseRank.SDK_READY)
        assertEquals(PhaseRank.SDK_READY, S.SdkReady.protocolRank)
    }

    @Test
    fun `regulation progression is strictly increasing`() {
        val a = ADDR
        val ranks = listOf(
            S.SdkReady,
            S.Deploying,
            S.Deployed(a),
            S.JoiningAsP2(a),
            S.Joined(a),
            S.P1Committing(a),
            S.P1Committed(a),
            S.P2Committing(a),
            S.BothCommitted(a),
            S.P1Revealing(a),
            S.P1Revealed(a),
            S.P2Revealing(a),
        ).map { it.protocolRank }

        ranks.zipWithNext().forEachIndexed { i, (lo, hi) ->
            assertTrue("Rank should strictly increase at index $i: $lo → $hi", lo < hi)
        }
    }

    @Test
    fun `named PhaseRank constants match the corresponding state ranks`() {
        val a = ADDR
        assertEquals(PhaseRank.DEPLOYED, S.Deployed(a).protocolRank)
        assertEquals(PhaseRank.JOINED, S.Joined(a).protocolRank)
        assertEquals(PhaseRank.P1_COMMITTED, S.P1Committed(a).protocolRank)
        assertEquals(PhaseRank.BOTH_COMMITTED, S.BothCommitted(a).protocolRank)
        assertEquals(PhaseRank.P1_REVEALED, S.P1Revealed(a).protocolRank)
    }

    @Test
    fun `SD ranks for round 1 fall between regulation and round 2`() {
        val a = ADDR
        val regulationMax = S.P2Revealing(a).protocolRank
        val sdRound1Min = S.SdRoundOpen(a, round = 1).protocolRank
        val sdRound2Min = S.SdRoundOpen(a, round = 2).protocolRank

        assertTrue(regulationMax < sdRound1Min)
        assertTrue(sdRound1Min < sdRound2Min)
    }

    @Test
    fun `SD progression within one round is strictly increasing`() {
        val a = ADDR
        val r = 1
        val ranks = listOf(
            S.SdRoundOpen(a, r),
            S.P1SdCommitting(a, r),
            S.P1SdCommitted(a, r),
            S.P2SdCommitting(a, r),
            S.BothSdCommitted(a, r),
            S.P1SdRevealing(a, r),
            S.P1SdRevealed(a, r),
            S.P2SdRevealing(a, r),
        ).map { it.protocolRank }

        ranks.zipWithNext().forEachIndexed { i, (lo, hi) ->
            assertTrue("SD step $i: $lo → $hi should increase", lo < hi)
        }
    }

    @Test
    fun `PhaseRank sdP1CommittedAt is consistent with the P1SdCommitted state`() {
        listOf(1, 2, 3).forEach { round ->
            assertEquals(
                "round $round",
                PhaseRank.sdP1CommittedAt(round),
                S.P1SdCommitted(ADDR, round).protocolRank,
            )
            assertEquals(
                "round $round bothCommitted",
                PhaseRank.sdBothCommittedAt(round),
                S.BothSdCommitted(ADDR, round).protocolRank,
            )
            assertEquals(
                "round $round p1Revealed",
                PhaseRank.sdP1RevealedAt(round),
                S.P1SdRevealed(ADDR, round).protocolRank,
            )
        }
    }

    @Test
    fun `Resolved ranks above everything`() {
        assertEquals(Int.MAX_VALUE, S.Resolved(STUB_RESULT).protocolRank)
        assertTrue(
            "Resolved > deep SD round",
            S.Resolved(STUB_RESULT).protocolRank > S.P2SdRevealing(ADDR, round = 99).protocolRank,
        )
    }

    @Test
    fun `Failed delegates to previous state — same rank as what's stored`() {
        val previous = S.P1Committed(ADDR)
        val failed = S.Failed(previous = previous, error = IllegalStateException("test"))
        assertEquals(previous.protocolRank, failed.protocolRank)
    }

    @Test
    fun `sdRound is null for non-SD states`() {
        listOf(
            S.Idle, S.SdkReady,
            S.Deployed(ADDR), S.Joined(ADDR), S.P1Committed(ADDR),
            S.BothCommitted(ADDR), S.P1Revealed(ADDR),
            S.Resolved(STUB_RESULT),
        ).forEach { state ->
            assertNull("$state should have null sdRound", state.sdRound)
        }
    }

    @Test
    fun `sdRound returns the round for SD states`() {
        assertEquals(1, S.SdRoundOpen(ADDR, round = 1).sdRound)
        assertEquals(3, S.P1SdCommitting(ADDR, round = 3).sdRound)
        assertEquals(7, S.BothSdCommitted(ADDR, round = 7).sdRound)
        assertEquals(2, S.P2SdRevealing(ADDR, round = 2).sdRound)
    }

    @Test
    fun `sdRound on Failed delegates to previous`() {
        val previous = S.P1SdCommitted(ADDR, round = 4)
        val failed = S.Failed(previous = previous, error = RuntimeException("test"))
        assertEquals(4, failed.sdRound)
    }

    // ── isTxInFlight — gates [MatchManager.settleInFlightState], the
    //    black-screen-on-CONTINUE fix. The resume step-ladders branch only on
    //    STABLE states, so a transient must be flagged in-flight (waited out /
    //    reconciled) and a stable state must NOT be (settle is a no-op). The
    //    exact bug: resume re-entered at P1SdRevealing and the ladder threw.

    @Test
    fun `transient submit states are tx-in-flight`() {
        val a = ADDR
        listOf(
            S.Deploying,
            S.JoiningAsP2(a),
            S.P1Committing(a),
            S.P2Committing(a),
            S.P1Revealing(a),
            S.P2Revealing(a),
            S.P1SdCommitting(a, round = 1),
            S.P2SdCommitting(a, round = 1),
            S.P1SdRevealing(a, round = 1), // ← the state that black-screened
            S.P2SdRevealing(a, round = 1),
        ).forEach { state ->
            assertTrue("$state should be tx-in-flight", state.isTxInFlight)
        }
    }

    @Test
    fun `stable states the resume ladder acts on are not tx-in-flight`() {
        val a = ADDR
        listOf(
            S.Idle, S.SdkReady, S.Deployed(a), S.Joined(a),
            S.P1Committed(a), S.BothCommitted(a), S.P1Revealed(a),
            S.SdRoundOpen(a, round = 1),
            S.P1SdCommitted(a, round = 1),
            S.BothSdCommitted(a, round = 1),
            S.P1SdRevealed(a, round = 1),
            S.Resolved(STUB_RESULT),
        ).forEach { state ->
            assertFalse("$state should NOT be tx-in-flight", state.isTxInFlight)
        }
    }

    @Test
    fun `isTxInFlight on Failed delegates to previous`() {
        // A submission that threw mid-flight wraps the transient in Failed;
        // settleInFlightState unwraps first, so the flag must follow through.
        assertTrue(
            S.Failed(previous = S.P1SdRevealing(ADDR, round = 1), error = RuntimeException("x")).isTxInFlight,
        )
        assertFalse(
            S.Failed(previous = S.BothSdCommitted(ADDR, round = 1), error = RuntimeException("x")).isTxInFlight,
        )
    }

    companion object {
        private const val ADDR =
            "1111111111111111111111111111111111111111111111111111111111111111"
        private val STUB_RESULT = MatchResult(
            p1Shoots = IntArray(5), p1Keeps = IntArray(5),
            p2Shoots = IntArray(5), p2Keeps = IntArray(5),
            sdRounds = emptyList(),
            contractAddress = ADDR,
        )
    }
}
