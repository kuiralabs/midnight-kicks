package com.midnight.kicks

import com.midnight.kuira.core.compact.ContractCallException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Turns a [MatchState.Failed] throwable into a calm, plain-language line for
 * the HUD. Beta players should never read a raw `ContractCallException: …` —
 * they should read what happened and how to get unstuck. The original
 * throwable stays in logs (MatchManager logs every failure) for bug reports.
 *
 * Classification is deliberately conservative: a handful of known shapes get
 * tailored copy; everything else falls back to a safe generic line rather
 * than leaking internals. Matching is on message content because the SDK
 * surfaces most chain/indexer problems as a single [ContractCallException]
 * or a generic exception rather than typed subclasses.
 *
 * The recovery path is real: the HUD banner is status-only (no buttons), but
 * the pause button exits to the menu where **RESUME MATCH** re-drives the
 * resume-aware state machine — it walks chain state and picks up where the
 * match actually is, so retrying after a transient failure is safe.
 */
fun Throwable.toMatchErrorMessage(): String {
    val raw = message ?: ""
    return when {
        this is TimeoutCancellationException ->
            "Timed out waiting for the network. Exit and tap RESUME MATCH to try again."

        raw.containsAnyIgnoreCase("deadline passed", "deadline") ->
            "This round's deadline passed — your opponent didn't respond in time."

        raw.containsAnyIgnoreCase("not found", "indexer", "no contract") ->
            "Still syncing with the network. Give it a moment, then RESUME MATCH from the menu."

        raw.containsAnyIgnoreCase("dust", "invaliddustspendproof", "error 170") ->
            "Not enough DUST to cover the fee. Register for dust generation, then RESUME MATCH."

        raw.containsAnyIgnoreCase("insufficient", "not enough night", "balance too low") ->
            "Not enough NIGHT to cover the stake + fee on this network."

        // Contract asserts are already human-ish ("Commit deadline passed",
        // "Cannot claim timeout in this phase"); surface that, not the
        // wrapping type. Fall back to a generic move-rejected line.
        this is ContractCallException ->
            raw.takeIf { it.isNotBlank() }?.let { "Move rejected: $it" }
                ?: "The contract rejected that move. Exit and RESUME MATCH to retry."

        else ->
            "Something went wrong. Exit and tap RESUME MATCH to try again."
    }
}

private fun String.containsAnyIgnoreCase(vararg needles: String): Boolean =
    needles.any { this.contains(it, ignoreCase = true) }
