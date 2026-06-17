package com.midnight.kicks

import com.midnight.kicks.LastMatchSummary.Outcome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for [LastMatchSummary.hasMarks] / [hasScore] — the two gates that
 * decide whether the menu's [LastMatchCard] renders its scoreline and its
 * per-kick goal/save scoring recap.
 *
 * Three shapes feed the card and each must render correctly:
 *  - a played-through result (score + per-kick goal/save marks),
 *  - a bootstrap "prior match finished" result (score only — the chain knows the
 *    scoreline but not the per-kick outcomes),
 *  - an early-ended result (forfeit / cancel — neither a scoreline nor marks).
 */
class LastMatchSummaryTest {

    private fun summary(
        outcome: Outcome,
        myMarks: List<Boolean> = emptyList(),
        theirMarks: List<Boolean> = emptyList(),
    ) = LastMatchSummary(
        outcome = outcome,
        myScore = 3,
        theirScore = 2,
        myName = "You",
        theirName = "Opponent",
        myMarks = myMarks,
        theirMarks = theirMarks,
    )

    @Test
    fun `played-through result has both a scoreline and a goal-save recap`() {
        val s = summary(
            Outcome.WIN,
            myMarks = listOf(true, true, false),
            theirMarks = listOf(false, true, false),
        )
        assertTrue(s.hasScore)
        assertTrue(s.hasMarks)
    }

    @Test
    fun `win, loss and draw all show a scoreline`() {
        listOf(Outcome.WIN, Outcome.LOSS, Outcome.DRAW).forEach {
            assertTrue("$it should have a scoreline", summary(it).hasScore)
        }
    }

    @Test
    fun `prior-finished (score-only) shows the scoreline but no recap`() {
        // Bootstrap path: per-kick outcomes are unknown, so the card shows the
        // result + score without the goal/save recap rows.
        val s = summary(Outcome.WIN) // no marks
        assertTrue(s.hasScore)
        assertFalse(s.hasMarks)
    }

    @Test
    fun `early-ended outcomes show neither a scoreline nor a recap`() {
        listOf(Outcome.FORFEIT_WIN, Outcome.CANCELLED_REFUND).forEach {
            val s = summary(it)
            assertFalse("$it should have no scoreline", s.hasScore)
            assertFalse("$it should have no recap", s.hasMarks)
        }
    }

    @Test
    fun `a half-populated recap (one side empty) does not render`() {
        // Defensive: the recap needs both sides; one-sided data is treated as no recap.
        assertFalse(summary(Outcome.WIN, myMarks = listOf(true, false)).hasMarks)
        assertFalse(summary(Outcome.WIN, theirMarks = listOf(true, false)).hasMarks)
    }
}
