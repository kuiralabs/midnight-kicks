package com.midnight.kicks

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide HUD state for the in-match Compose overlay.
 *
 * The Unity activity ([KicksMatchActivity]) renders a status banner on
 * top of Unity's surface. The banner has to keep showing during the
 * long blockchain waits between commit / reveal / opponent activity —
 * otherwise the user sees "submitted my pick" and a frozen 3D scene,
 * with no idea whether the system is alive or hung.
 *
 * `MatchManager` publishes here every time it advances state or starts /
 * completes a circuit call. The overlay collects the [StateFlow] and
 * re-renders. Process-wide singleton (object), same pattern as
 * [UnityBridge] — the source-of-truth lives outside any one Activity so
 * KicksActivity ↔ KicksMatchActivity transitions don't lose the HUD
 * state mid-match.
 */
object MatchHud {

    /**
     * Coarse classification of what the user should be doing right now,
     * used by the overlay to pick the right icon / accent color / animation.
     */
    enum class Mode {
        /** Nothing to show — banner hidden. */
        IDLE,

        /**
         * User is actively picking on Unity (regulation 10-pick or SD
         * 2-pick). The banner shows context like "Sudden death round
         * 3" so the user knows which round they're picking for. No
         * elapsed timer (the user is the one being waited on, not the
         * other way around).
         */
        PICKING,

        /** A blockchain transaction is in flight from this device. */
        TX_IN_FLIGHT,

        /** Tx submitted; we're waiting for the opponent to act. */
        WAITING_FOR_OPPONENT,

        /** Match has resolved — show the final result. */
        DONE,

        /** Something went wrong — show the error. */
        ERROR,
    }

    /**
     * Snapshot of what the HUD should show. Driven entirely by
     * [MatchManager]; the overlay is a pure view of this.
     *
     * `primary` mirrors [MatchState.label] — the headline the user reads.
     * `secondary` is the per-circuit-stage detail when a tx is in flight
     *   (Proving / Balancing / Submitting) — kept short so the overlay
     *   doesn't grow into the game area.
     * `mode` drives presentation; the overlay maps each mode to a
     *   distinct color + icon + animation.
     * `sessionEpochMs` is a key the overlay's elapsed-time counter
     *   resets on. Bumped whenever the user starts a fresh wait, so the
     *   "00:23 — waiting for opponent" timer restarts when the state
     *   moves to a different opponent wait (e.g. P1Committed →
     *   BothCommitted → P1Revealed all bump it).
     */
    data class HudState(
        val primary: String? = null,
        val secondary: String? = null,
        val mode: Mode = Mode.IDLE,
        val sessionEpochMs: Long = 0L,
    )

    /**
     * Full-screen replay snapshot rendered above Unity by
     * [MatchReplayOverlay]. Non-null = overlay showing. Cleared via
     * [dismissReplay] when the user taps Continue, at which point any
     * orchestrator awaiting `MatchHud.replay.first { it == null }`
     * (i.e. "user has seen the replay") wakes up.
     *
     * `rounds` is the same `List<RoundResult>` Unity's cinematic would
     * consume (built via `MatchResult.toRoundResults()`), so the
     * Compose fallback and the eventual Unity cinematic share one data
     * contract.
     *
     * `kind` distinguishes the regulation-end replay (10 rounds) from
     * later SD-round replays (2 entries per round). Both flow through
     * the same overlay; kind picks the framing copy.
     */
    enum class ReplayKind { REGULATION, SUDDEN_DEATH_ROUND }

    data class ReplayShow(
        val rounds: List<RoundResult>,
        val p1Score: Int,
        val p2Score: Int,
        val kind: ReplayKind = ReplayKind.REGULATION,
        val sdRoundNumber: Int? = null,   // populated when kind == SUDDEN_DEATH_ROUND
    )

    data class HudReplay(
        val show: ReplayShow,
        val publishedAtMs: Long,
    )

    private val _state = MutableStateFlow(HudState())
    val state: StateFlow<HudState> = _state.asStateFlow()

    /**
     * The replay [StateFlow] doubles as the dismissal gate. Consumers
     * that need to wait for "user has seen the replay" can call
     * `MatchHud.replay.first { it == null }` — StateFlow re-emits its
     * current value on subscription, so callers don't race against
     * dismissals that happened before they started awaiting. The
     * orchestrator in [KicksActivity.gatherSdPicksFromUi] uses this
     * exact pattern to gate the SD picker.
     */
    private val _replay = MutableStateFlow<HudReplay?>(null)
    val replay: StateFlow<HudReplay?> = _replay.asStateFlow()

    /**
     * Replace the primary label + mode, leaving secondary in place and
     * rotating the session epoch so the elapsed-time counter restarts.
     *
     * Called on every [MatchState] transition. Mode is derived by the
     * caller (MatchManager) because the mapping from [MatchState] to
     * [Mode] is policy, not data — kept there so this file stays
     * presentation-agnostic.
     */
    fun publishPrimary(label: String, mode: Mode) {
        val prev = _state.value
        _state.value = prev.copy(
            primary = label,
            // Reset the sub-line — it's tied to a specific circuit
            // call's stages, so a state transition implicitly ends
            // whatever tx-in-flight detail was being shown.
            secondary = null,
            mode = mode,
            // Bump the epoch so the overlay's elapsed counter restarts.
            // Using currentTimeMillis() keeps it monotonic even across
            // process restarts (the StateFlow doesn't survive, but the
            // overlay re-keys correctly when MatchManager republishes).
            sessionEpochMs = System.currentTimeMillis(),
        )
    }

    /**
     * Update only the per-tx-stage secondary line. Cleared (passed `null`)
     * when the circuit call completes / fails, so the overlay drops the
     * sub-line and falls back to whatever the primary mode dictates.
     */
    fun publishSecondary(text: String?) {
        val prev = _state.value
        _state.value = prev.copy(secondary = text)
    }

    /**
     * Surface a full-screen replay above Unity. Called by [MatchManager]
     * after both regulation reveals have landed (or after each SD round
     * has both reveals). The overlay renders the rounds row-by-row and
     * waits for the user to tap Continue.
     */
    fun publishReplay(show: ReplayShow) {
        _replay.value = HudReplay(
            show = show,
            publishedAtMs = System.currentTimeMillis(),
        )
    }

    /**
     * User tapped Continue on the replay. Clearing [_replay] is the
     * dismissal signal: any orchestrator step awaiting
     * `MatchHud.replay.first { it == null }` wakes up on the next
     * StateFlow emission. No separate event channel needed — the
     * absence of a replay IS the dismissed state.
     */
    fun dismissReplay() {
        _replay.value = null
    }

    /**
     * Wipe everything — call when leaving the match screen entirely
     * (e.g. KicksActivity.onDestroy or after the user dismisses the
     * resolved-match dialog) so a stale label doesn't briefly flash on
     * the next match before MatchManager has time to publish.
     */
    fun reset() {
        _state.value = HudState()
        _replay.value = null
    }
}
