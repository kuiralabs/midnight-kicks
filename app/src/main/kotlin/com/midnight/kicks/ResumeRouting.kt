package com.midnight.kicks

/**
 * Pure decision for "after [MatchManager.tryResumeActiveMatch] lands, what
 * screen should [KicksActivity] route into?"
 *
 * Extracted to a top-level function so the routing tree is covered by a
 * unit test instead of an instrumented Activity test.
 *
 * Rules, ordered by specificity:
 *  1. **Finished** — a [MatchState.Resolved] (or [MatchState.Failed]) match has
 *     nowhere to "continue" to: routing it to [KicksScreen.MatchReady] left a dead
 *     CONTINUE that re-ran `resumePlayAsP1` from `Resolved` in a loop. Send it to
 *     the menu, which surfaces the result.
 *  2. **P2** — by definition they've already joined. There's no
 *     intermediate "waiting for opponent" screen that makes sense for
 *     them; route straight to [KicksScreen.MatchReady].
 *  3. **P1 with opponent in or past** — state has advanced past
 *     [MatchState.Deployed], meaning the opponent's join landed. Same
 *     `MatchReady` destination as P2; gameplay can resume.
 *  4. **P1 still alone** — state is [MatchState.Deployed] (or anything
 *     pre-deploy that still has an address attached). Land on
 *     [KicksScreen.Creating] so the user can keep tapping CHECK STATUS.
 *
 * @param address the address [MatchManager.tryResumeActiveMatch] returned.
 * @param role    the persisted role from [MatchStore]; `null` means the
 *                store record was missing (defensive — should be rare).
 * @param state   the [MatchState] the manager is in after the resume.
 */
internal fun chooseResumeScreen(
    address: String,
    role: Player?,
    state: MatchState,
): KicksScreen {
    val opponentInOrPast = state !is MatchState.Deployed
    return when {
        // Finished/failed: no "continue" target — go to the menu (avoids the dead
        // MatchReady CONTINUE that looped resumePlayAsP1 from Resolved).
        state is MatchState.Resolved || state is MatchState.Failed -> KicksScreen.Menu
        role == Player.P2 -> KicksScreen.MatchReady(address, Player.P2)
        role == Player.P1 && opponentInOrPast -> KicksScreen.MatchReady(address, Player.P1)
        else -> KicksScreen.Creating(address)
    }
}
