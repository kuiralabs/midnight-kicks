package com.midnight.kicks

/**
 * Discrete states for a match's life cycle. The state machine is the source
 * of truth for "where is this player in the protocol?" — UI observes it,
 * BlockStore snapshots derive from it, and StatePoller (future) advances it
 * when an on-chain event for the opponent fires.
 *
 * Today's PvAI flow walks straight through: [Idle] → ... → [Resolved], with
 * the orchestrator auto-advancing the AI's steps. The shape is the same for
 * future PvP — the only difference is who supplies P2's choices and who
 * triggers the P2 transitions (the StatePoller, not the orchestrator).
 *
 * Each non-Idle state carries enough context (address, role) to either
 * resume after process death or render stage-specific UX without
 * cross-referencing the [MatchManager].
 */
sealed class MatchState {
    /** Contract address once known; null only in [Idle], [InitializingSdk], [SdkReady]. */
    open val address: String? = null

    // ── Setup ──────────────────────────────────────────────────────────
    data object Idle : MatchState()
    data object InitializingSdk : MatchState()
    data object SdkReady : MatchState()

    // ── Deploy ─────────────────────────────────────────────────────────
    data object Deploying : MatchState()
    data class Deployed(override val address: String) : MatchState()

    // ── Join (AI today, scanned opponent in PvP) ────────────────────────
    data class JoiningAsP2(override val address: String) : MatchState()
    data class Joined(override val address: String) : MatchState()

    // ── Commit ─────────────────────────────────────────────────────────
    data class P1Committing(override val address: String) : MatchState()
    data class P1Committed(override val address: String) : MatchState()
    data class P2Committing(override val address: String) : MatchState()
    data class BothCommitted(override val address: String) : MatchState()

    // ── Reveal (regulation) ────────────────────────────────────────────
    data class P1Revealing(override val address: String) : MatchState()
    data class P1Revealed(override val address: String) : MatchState()
    data class P2Revealing(override val address: String) : MatchState()

    // ── Sudden death — one round at a time, parameterised by `round`. ──
    //   After regulation drew, P2's reveal transitions to SdRoundOpen(1).
    //   Each round walks the same Committing → Committed → Revealing →
    //   Revealed sequence as regulation but for {shoot, keep} pairs.
    //   After P2SdRevealed, the contract is either COMPLETE (transition
    //   to Resolved) or back in SD_COMMITTING (transition to
    //   SdRoundOpen(round + 1)).
    data class SdRoundOpen(override val address: String, val round: Int) : MatchState()
    data class P1SdCommitting(override val address: String, val round: Int) : MatchState()
    data class P1SdCommitted(override val address: String, val round: Int) : MatchState()
    data class P2SdCommitting(override val address: String, val round: Int) : MatchState()
    data class BothSdCommitted(override val address: String, val round: Int) : MatchState()
    data class P1SdRevealing(override val address: String, val round: Int) : MatchState()
    data class P1SdRevealed(override val address: String, val round: Int) : MatchState()
    data class P2SdRevealing(override val address: String, val round: Int) : MatchState()

    data class Resolved(val result: MatchResult) : MatchState() {
        override val address: String get() = result.contractAddress
    }

    // ── Failure (preserves the state we were in so the UI can retry) ───
    data class Failed(val previous: MatchState, val error: Throwable) : MatchState() {
        override val address: String? get() = previous.address
    }

    /** Human-friendly label, used as the [MatchManager] progress string. */
    val label: String get() = when (this) {
        is Idle               -> "Idle"
        is InitializingSdk    -> "Initializing SDK…"
        is SdkReady           -> "SDK ready"
        is Deploying          -> "Deploying match…"
        is Deployed           -> "Match deployed"
        is JoiningAsP2        -> "Opponent joining…"
        is Joined             -> "Both players in"
        is P1Committing       -> "Committing your picks…"
        is P1Committed        -> "Your picks committed"
        is P2Committing       -> "Opponent committing…"
        is BothCommitted      -> "Both players committed"
        is P1Revealing        -> "Revealing your picks…"
        is P1Revealed         -> "Your picks revealed"
        is P2Revealing        -> "Opponent revealing…"
        is SdRoundOpen        -> "Sudden death round $round"
        is P1SdCommitting     -> "Committing SD round $round…"
        is P1SdCommitted      -> "Your SD pick committed (round $round)"
        is P2SdCommitting     -> "Opponent committing SD round $round…"
        is BothSdCommitted    -> "Both committed (SD round $round)"
        is P1SdRevealing      -> "Revealing SD round $round…"
        is P1SdRevealed       -> "Your SD pick revealed (round $round)"
        is P2SdRevealing      -> "Opponent revealing SD round $round…"
        is Resolved           -> "Match complete!"
        is Failed             -> "Failed: ${error.message ?: error.javaClass.simpleName}"
    }
}
