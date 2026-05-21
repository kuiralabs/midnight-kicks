package com.midnight.kicks

import android.util.Log
import com.midnight.kuira.core.compact.MidnightContract
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/**
 * Watches a deployed contract's ledger state by polling.
 *
 * **Why polling, not subscription:**
 *   The Kuira SDK exposes `IndexerClient.subscribeToBlocks()` privately
 *   inside `MidnightSdk` — it isn't reachable from app code. Until that's
 *   surfaced (PLAN.md wishlist #2), we poll [MidnightContract.ledger].
 *   3s interval gives ~1.5s average detection latency on PREPROD's ~6s
 *   block time, well under the typical match's commit / reveal cadence.
 *
 * **Why [MidnightContract.ledger] and not [MidnightConfig.queryState]:**
 *   `queryState` returns a flattened JSON cell tree where
 *   `Vector<N, Uint<8>>` fields lose positional information for zero
 *   elements (each Uint<8>=0 serializes to zero bytes in the cell, not
 *   a zero byte). `MidnightContract.ledger()` reads through
 *   `queryLedgerState` per field, returning the full structured value
 *   losslessly. See PLAN.md wishlist #9.
 *
 * **Lifecycle:**
 *   `snapshots()` returns a cold Flow. Collect it from a coroutine scope;
 *   cancelling that scope (or the collection) stops the poll loop. The
 *   flow uses `distinctUntilChanged` so consumers see one emission per
 *   actual state change.
 *
 * **Errors:**
 *   Individual `ledger()` failures are logged and swallowed — the next
 *   poll picks up where we left off. The 3s interval is its own backoff;
 *   no retry loop needed. If you need a hard "indexer is dead" signal,
 *   wrap the flow with a timeout from the caller side.
 *
 * **Future:**
 *   When the SDK promotes block subscriptions (or adds
 *   `MidnightContract.stateFlow()`), the body of `snapshots()` becomes a
 *   pass-through; the public surface stays.
 */
class StatePoller(
    private val contract: MidnightContract,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) {
    init {
        Log.i(TAG, "poller for ${contract.contractAddress} ready (interval=${pollIntervalMs}ms)")
    }

    /**
     * Cold flow of contract-state snapshots. Emits once per change, never
     * emits null. Completes only when the collecting scope is cancelled.
     */
    fun snapshots(): Flow<ContractStateSnapshot> = flow {
        while (currentCoroutineContext().isActive) {
            val snapshot = readOnce()
            if (snapshot != null) emit(snapshot)
            delay(pollIntervalMs)
        }
    }.distinctUntilChanged()

    /**
     * One-shot read — useful from synchronous code paths (e.g. confirming
     * a transaction landed before advancing state) without spinning up a
     * flow collection.
     */
    suspend fun readOnce(): ContractStateSnapshot? {
        return try {
            val ledger = contract.ledger()
            ContractStateSnapshot.fromLedger(ledger).also { snap ->
                Log.d(TAG, "snapshot: ${snap.summary()}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "ledger() failed for ${contract.contractAddress} — will retry in ${pollIntervalMs}ms: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "StatePoller"
        /** 3s polling — half a typical PREPROD block, balances latency vs load. */
        const val DEFAULT_POLL_INTERVAL_MS = 3_000L
    }
}
