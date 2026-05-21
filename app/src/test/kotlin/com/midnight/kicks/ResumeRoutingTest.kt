package com.midnight.kicks

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure unit tests for [chooseResumeScreen] — the post-resume screen
 * selector that [KicksActivity] uses on bootstrap.
 *
 * Every branch matters: the live 2026-05-20 bug ("emulator B stuck on
 * the Create screen with no continue path") came from the older "always
 * route to `Creating`" rule misclassifying a resumed P2 match. Each
 * scenario below is one variant of "role × resume-state" and the
 * expected destination.
 */
class ResumeRoutingTest {

    @Test
    fun `P2 resume always goes to MatchReady — they have already joined`() {
        // Any state past join lands them on MatchReady. Deployed isn't a
        // legal state for a P2 device but include it defensively — if it
        // ever happens (e.g. store/state desync), still route to
        // MatchReady so the user isn't trapped on a P1-only screen.
        listOf(
            MatchState.Joined(ADDR),
            MatchState.P1Committed(ADDR),
            MatchState.BothCommitted(ADDR),
            MatchState.P1Revealed(ADDR),
            MatchState.SdRoundOpen(ADDR, round = 1),
            MatchState.Deployed(ADDR), // defensive
        ).forEach { state ->
            assertEquals(
                "P2 + $state should go to MatchReady",
                KicksScreen.MatchReady(ADDR, Player.P2),
                chooseResumeScreen(ADDR, Player.P2, state),
            )
        }
    }

    @Test
    fun `P1 with opponent already in goes to MatchReady`() {
        // Any state past Deployed means tryResumeActiveMatch saw the
        // chain at COMMITTING / REVEALING, i.e. the opponent's join
        // landed. Same destination as P2; gameplay can resume.
        listOf(
            MatchState.Joined(ADDR),
            MatchState.P1Committed(ADDR),
            MatchState.BothCommitted(ADDR),
            MatchState.P1Revealed(ADDR),
            MatchState.SdRoundOpen(ADDR, round = 1),
            MatchState.P1SdCommitted(ADDR, round = 2),
        ).forEach { state ->
            assertEquals(
                "P1 + $state should go to MatchReady",
                KicksScreen.MatchReady(ADDR, Player.P1),
                chooseResumeScreen(ADDR, Player.P1, state),
            )
        }
    }

    @Test
    fun `P1 still alone (state is Deployed) goes to Creating`() {
        // Opponent hasn't joined yet — the CHECK STATUS button on the
        // Create screen is exactly the affordance needed here.
        assertEquals(
            KicksScreen.Creating(ADDR),
            chooseResumeScreen(ADDR, Player.P1, MatchState.Deployed(ADDR)),
        )
    }

    @Test
    fun `missing role record falls back to Creating`() {
        // Defensive: tryResumeActiveMatch returned an address but the
        // store record disappeared between the resume and the routing
        // check (race or wipe). Land on Creating, which is the safe
        // P1-shaped screen — it never assumes the user is P2.
        assertEquals(
            KicksScreen.Creating(ADDR),
            chooseResumeScreen(ADDR, role = null, state = MatchState.Joined(ADDR)),
        )
    }

    @Test
    fun `pinning the live 2026-05-20 bug — resumed P2 in Joined state`() {
        // The exact scenario from the bug report: emulator B resumed a
        // P2 match, MatchManager landed at Joined (chain phase=COMMITTING,
        // p1Committed=false), routing then mis-sent the user to the
        // Create screen with no way forward. Post-fix this returns
        // MatchReady, which is what MatchReadyScreen + the CONTINUE
        // button expect.
        val routed = chooseResumeScreen(
            address = ADDR,
            role = Player.P2,
            state = MatchState.Joined(ADDR),
        )
        assertEquals(KicksScreen.MatchReady(ADDR, Player.P2), routed)
    }

    companion object {
        private const val ADDR =
            "037b7a20bdc1f64cbdf1292a56cb418d7c845fc78fe420d31e67f1cac9b429a1"
    }
}
