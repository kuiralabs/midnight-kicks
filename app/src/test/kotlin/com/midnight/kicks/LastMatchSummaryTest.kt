package com.midnight.kicks

import com.midnight.kicks.LastMatchSummary.Outcome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for [LastMatchSummary.hasPicks] / [hasScore] — the two gates that
 * decide whether the menu's [LastMatchCard] renders its score line and its
 * two-row pick recap.
 *
 * Three shapes feed the card and each must render correctly:
 *  - a played-through result (score + picks),
 *  - a bootstrap "prior match finished" result (score only — the chain knows the
 *    scoreline but not the per-kick picks),
 *  - an early-ended result (forfeit / cancel — neither a scoreline nor picks).
 */
class LastMatchSummaryTest {

    private fun summary(
        outcome: Outcome,
        myPicks: List<String> = emptyList(),
        theirPicks: List<String> = emptyList(),
    ) = LastMatchSummary(
        outcome = outcome,
        myScore = 3,
        theirScore = 2,
        myName = "You",
        theirName = "Opponent",
        myPicks = myPicks,
        theirPicks = theirPicks,
    )

    @Test
    fun `played-through result has both a scoreline and a pick recap`() {
        val s = summary(Outcome.WIN, myPicks = listOf("L", "C", "R"), theirPicks = listOf("R", "R", "C"))
        assertTrue(s.hasScore)
        assertTrue(s.hasPicks)
    }

    @Test
    fun `win, loss and draw all show a scoreline`() {
        listOf(Outcome.WIN, Outcome.LOSS, Outcome.DRAW).forEach {
            assertTrue("$it should have a scoreline", summary(it).hasScore)
        }
    }

    @Test
    fun `prior-finished (score-only) shows the scoreline but no pick recap`() {
        // Bootstrap path: picks are unknown, so the card shows the result + score
        // without the recap rows.
        val s = summary(Outcome.WIN) // empty picks
        assertTrue(s.hasScore)
        assertFalse(s.hasPicks)
    }

    @Test
    fun `early-ended outcomes show neither a scoreline nor a pick recap`() {
        listOf(Outcome.FORFEIT_WIN, Outcome.CANCELLED_REFUND).forEach {
            val s = summary(it)
            assertFalse("$it should have no scoreline", s.hasScore)
            assertFalse("$it should have no pick recap", s.hasPicks)
        }
    }

    @Test
    fun `a half-populated pick recap (one side empty) does not render`() {
        // Defensive: the recap needs both sides; one-sided data is treated as no recap.
        assertFalse(summary(Outcome.WIN, myPicks = listOf("L", "C", "R")).hasPicks)
        assertFalse(summary(Outcome.WIN, theirPicks = listOf("L", "C", "R")).hasPicks)
    }
}
