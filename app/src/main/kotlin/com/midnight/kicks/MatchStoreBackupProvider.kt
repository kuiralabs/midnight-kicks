package com.midnight.kicks

import com.midnight.kuira.dapp.backup.AppDataBackupProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kicks's [AppDataBackupProvider] implementation — round-trips every
 * in-flight match through the Sigil cloud-backup pipeline by
 * delegating to [MatchStore.snapshotBytes] / [restoreFromBytes].
 *
 * Bound via [com.midnight.kicks.di.KicksBackupBindings] so the SDK's
 * `Optional<AppDataBackupProvider>` is non-empty for Kicks (Kicks =
 * matches restore alongside seed) but stays empty for BBoard
 * (BBoard = no app data to round-trip).
 *
 * Stateless apart from the injected [store]; safe as a singleton.
 */
@Singleton
class MatchStoreBackupProvider @Inject constructor(
    private val store: MatchStore,
) : AppDataBackupProvider {

    /**
     * Snapshot the current match set. Returns `null` when the store
     * is empty so the SDK packs `appMetadata = null` rather than an
     * empty envelope — the wire blob stays lean and a future
     * `appMetadata == null` short-circuit (e.g. "skip the
     * appState field entirely") keeps working.
     */
    override suspend fun snapshot(): ByteArray? {
        val matches = store.loadAll()
        if (matches.isEmpty()) return null
        return store.snapshotBytes()
    }

    /**
     * Restore from a sigil-cloud blob. Delegates straight to
     * [MatchStore.restoreFromBytes], which already handles corrupt
     * blobs + schema mismatches defensively (preserves local store
     * if the cloud blob can't yield a single decoded record).
     */
    override suspend fun restore(bytes: ByteArray) {
        store.restoreFromBytes(bytes)
    }
}
