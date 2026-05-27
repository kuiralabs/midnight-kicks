package com.midnight.kicks

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Round-trip tests for [MatchHud]'s cross-process relay serialization.
 *
 * Post-split (Approach A — see `docs/PLAN.md`) the HUD publisher runs in the
 * **main** process and the overlays render in **`:unity`**. They stay mirrored
 * by serializing each change through [MatchHud.relayHook] and re-applying it
 * with [MatchHud.applyRemote] on the other side. If that serialization drops a
 * field (e.g. `sdRoundNumber`), fumbles a null (`role`, `secondary`), or
 * mismatches an enum name, the overlay silently shows the wrong thing across
 * the boundary — a bug that's painful to trace through IPC logs but trivial to
 * catch here.
 *
 * The test simulates the boundary in one process. Applying a relayed payload
 * onto the very state that produced it is idempotent — so the invariant is:
 * after `publish*`, snapshot the state, `applyRemote` the captured payload, and
 * the state must be **unchanged**. Any dropped/garbled field shows up as a
 * mismatch. (Modelling a "fresh peer" via reset would be wrong for delta events
 * like `secondary`, which legitimately layer onto state the peer already holds.)
 *
 * [MatchHud] is an `object`, so each test resets shared state in setup/teardown.
 */
class MatchHudRelayTest {

    /** Last payload [MatchHud.relayHook] emitted — the bytes that cross the boundary. */
    private var lastRelayed: String? = null

    @Before
    fun setUp() {
        // Reset with no hook so the reset itself doesn't get captured.
        MatchHud.relayHook = null
        MatchHud.reset()
        MatchHud.relayHook = { lastRelayed = it }
        lastRelayed = null
    }

    @After
    fun tearDown() {
        MatchHud.relayHook = null
        MatchHud.reset()
    }

    /** The payload [MatchHud.relayHook] just emitted — what crosses the boundary. */
    private fun relayed(): String = requireNotNull(lastRelayed) { "publish did not relay" }

    @Test
    fun primary_roundTrips_withRole() {
        MatchHud.publishPrimary("Waiting for opponent", MatchHud.Mode.WAITING_FOR_OPPONENT, Player.P2)
        val expected = MatchHud.state.value

        MatchHud.applyRemote(relayed())

        assertEquals(expected, MatchHud.state.value)
    }

    @Test
    fun primary_roundTrips_withNullRole_pvAi() {
        MatchHud.publishPrimary("Pick your 5 directions", MatchHud.Mode.PICKING, role = null)
        val expected = MatchHud.state.value
        assertNull("PvAI has no role", expected.role)

        MatchHud.applyRemote(relayed())

        assertEquals(expected, MatchHud.state.value)
    }

    @Test
    fun secondary_roundTrips_text() {
        // Primary first so there's a base state the sub-line layers onto.
        MatchHud.publishPrimary("Submitting", MatchHud.Mode.TX_IN_FLIGHT, Player.P1)

        MatchHud.publishSecondary("Proving…")
        val expected = MatchHud.state.value
        assertEquals("Proving…", expected.secondary)

        MatchHud.applyRemote(relayed())

        assertEquals(expected, MatchHud.state.value)
    }

    @Test
    fun secondary_roundTrips_null() {
        MatchHud.publishPrimary("Submitting", MatchHud.Mode.TX_IN_FLIGHT, Player.P1)

        MatchHud.publishSecondary(null) // circuit completed → clear sub-line
        val expected = MatchHud.state.value
        assertNull(expected.secondary)

        MatchHud.applyRemote(relayed())

        assertEquals(expected, MatchHud.state.value)
    }

    @Test
    fun replay_regulation_roundTrips() {
        val show = MatchHud.ReplayShow(
            rounds = listOf(
                RoundResult(round = 1, shooter = "P1", shootDir = 0, keepDir = 2, result = "goal"),
                RoundResult(round = 2, shooter = "P2", shootDir = 1, keepDir = 1, result = "save"),
                RoundResult(round = 3, shooter = "P1", shootDir = 2, keepDir = 0, result = "goal"),
            ),
            p1Score = 2,
            p2Score = 0,
            kind = MatchHud.ReplayKind.REGULATION,
            sdRoundNumber = null,
        )
        MatchHud.publishReplay(show)
        val expected = MatchHud.replay.value
        assertNotNull(expected)

        MatchHud.applyRemote(relayed())

        assertEquals(expected, MatchHud.replay.value)
    }

    @Test
    fun replay_suddenDeath_roundTrips_withSdRoundNumber() {
        val show = MatchHud.ReplayShow(
            rounds = listOf(
                RoundResult(round = 1, shooter = "P1", shootDir = 1, keepDir = 0, result = "goal"),
                RoundResult(round = 1, shooter = "P2", shootDir = 0, keepDir = 1, result = "goal"),
            ),
            p1Score = 1,
            p2Score = 1,
            kind = MatchHud.ReplayKind.SUDDEN_DEATH_ROUND,
            sdRoundNumber = 3,
        )
        MatchHud.publishReplay(show)
        val expected = MatchHud.replay.value
        assertEquals(3, expected?.show?.sdRoundNumber)

        MatchHud.applyRemote(relayed())

        assertEquals(expected, MatchHud.replay.value)
    }

    @Test
    fun dismissReplay_roundTrips_toNull() {
        MatchHud.publishReplay(
            MatchHud.ReplayShow(
                rounds = listOf(RoundResult(1, "P1", 0, 1, "goal")),
                p1Score = 1,
                p2Score = 0,
            ),
        )
        assertNotNull(MatchHud.replay.value)

        // The Continue tap (in :unity) → dismissReplay → relays back to main.
        MatchHud.dismissReplay()

        MatchHud.applyRemote(relayed())

        assertNull("replay clears across the boundary", MatchHud.replay.value)
    }

    @Test
    fun `picker roundTrips_rolesAndTitle`() {
        MatchHud.showPicker(roles = listOf("shoot", "keep", "shoot"), title = "Regulation")
        val expected = MatchHud.picker.value
        assertNotNull(expected)

        MatchHud.applyRemote(relayed())

        assertEquals(expected, MatchHud.picker.value)
    }

    @Test
    fun `connectionLost roundTrips_bothWays`() {
        MatchHud.publishConnectionLost(true)
        assertTrue(MatchHud.state.value.connectionLost)
        MatchHud.applyRemote(relayed())
        assertTrue("lost relays across the boundary", MatchHud.state.value.connectionLost)

        MatchHud.publishConnectionLost(false)
        MatchHud.applyRemote(relayed())
        assertFalse("clear relays across the boundary", MatchHud.state.value.connectionLost)
    }

    @Test
    fun `dismissPicker roundTrips_toNull`() {
        MatchHud.showPicker(roles = listOf("shoot", "keep"), title = "Sudden death — round 2")
        assertNotNull(MatchHud.picker.value)

        // The overlay closes the picker once all picks are locked (in :unity);
        // it relays back to main.
        MatchHud.dismissPicker()

        MatchHud.applyRemote(relayed())

        assertNull("picker clears across the boundary", MatchHud.picker.value)
    }

    @Test
    fun reset_roundTrips_clearingBothFlows() {
        MatchHud.publishPrimary("Resolved — you win", MatchHud.Mode.DONE, Player.P1)

        MatchHud.reset()

        MatchHud.applyRemote(relayed()) // relayed() is now the reset payload

        assertEquals(MatchHud.Mode.IDLE, MatchHud.state.value.mode)
        assertNull(MatchHud.state.value.primary)
        assertNull(MatchHud.replay.value)
    }
}
