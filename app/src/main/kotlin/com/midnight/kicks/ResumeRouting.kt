package com.midnight.kicks

/**
 * Pure decision for "after [MatchManager.tryResumeActiveMatch] lands, what
 * screen should [KicksActivity] route into?"
 *
 * Extracted to a top-level function so the routing tree is covered by a
 * unit test instead of an instrumented Activity test.
 *
 * Rules, ordered by specificity:
 *  1. **P2** — by definition they've already joined. There's no
 *     intermediate "waiting for opponent" screen that makes sense for
 *     them; route straight to [KicksScreen.MatchReady].
 *  2. **P1 with opponent in or past** — state has advanced past
 *     [MatchState.Deployed], meaning the opponent's join landed. Same
 *     `MatchReady` destination as P2; gameplay can resume.
 *  3. **P1 still alone** — state is [MatchState.Deployed] (or anything
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
        role == Player.P2 -> KicksScreen.MatchReady(address, Player.P2)
        role == Player.P1 && opponentInOrPast -> KicksScreen.MatchReady(address, Player.P1)
        else -> KicksScreen.Creating(address)
    }
}
