package com.midnight.kicks

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Unified, multi-match, encrypted store for every in-flight match this
 * device is participating in.
 *
 * Supersedes the original split — `KicksSessionStore` (unencrypted
 * metadata: address, role, deadline) + `MatchVault` (encrypted
 * witnesses: secret key, shoots/keeps, nonce). Splitting them caused
 * lifecycle drift (the bug class on 2026-05-19 where a successful
 * join's witnesses had been persisted but the session save had not).
 * One store, one lifecycle, one source of truth.
 *
 * **Encryption:** AES-256-GCM (values) + AES-256-SIV (keys), backed by
 * an Android Keystore master key. The witnesses are sensitive (a leak
 * would let an attacker forge as this player in the current match), so
 * everything in the record is encrypted at rest. The address being
 * encrypted is harmless overhead — it leaks through the on-screen QR
 * code and the deep link anyway.
 *
 * **Durability:** writes use `commit()` (not `apply()`). The save runs
 * immediately after a successful `joinAsP2` / `deployMatch` / commit
 * circuit; a `SIGKILL` between an `apply()` and its async write
 * silently loses the record and the user wakes up next launch with no
 * record of the in-flight match. Same bug class as `persistSigil`
 * (ac78fdf) and `dismissBackup`.
 *
 * **Multi-match:** keyed by contract address. The user can have
 * several PvP matches in flight simultaneously (waiting for opponent
 * commits, between rounds, etc.). The Resume UI enumerates
 * [loadAll]; each row routes to the right state for its match.
 *
 * **Cloud-backup seam:** [snapshotBytes] / [restoreFromBytes] produce
 * an opaque blob that fits into `SigilBackup.appMetadata`, so a sigil
 * backup carries match state along for free. Restoring the sigil on a
 * new device replays both seed and matches.
 *
 * **Serialization:** JSON via Android's bundled `org.json`. Compact
 * enough for the size class (~200 bytes per match), introspectable
 * for debugging, and version-tolerant via the `schemaVersion` field
 * — future schema changes can gate on it without breaking older
 * cloud blobs.
 */
class MatchStore internal constructor(
    private val prefs: SharedPreferences,
) {
    /**
     * Production constructor — wraps [EncryptedSharedPreferences] over
     * an Android Keystore master key. Use this from app code. The
     * primary constructor (above) accepts any [SharedPreferences] so
     * unit tests can pass plain in-memory prefs without standing up
     * the Keystore (Robolectric doesn't shadow AndroidKeyStore).
     */
    constructor(context: Context) : this(buildEncryptedPrefs(context))

    /**
     * Complete in-flight match record. Everything the state machine
     * needs to either reveal a prior commit (witnesses!) or render the
     * resume screen (address + role + deadline).
     *
     * [regulation] and [sd] are nullable because they only populate
     * once the corresponding commit lands — fresh matches have neither,
     * post-regulation matches have [regulation] but not [sd], etc.
     *
     * **Not a `data class`** because Kotlin's auto-generated `equals` /
     * `hashCode` use *reference* equality on `ByteArray` fields —
     * `loaded == saved` would be false even when the bytes are
     * identical (same trap that bit `ContractStateSnapshot`, see
     * PLAN.md friction-log item #9). Hand-rolled equality below uses
     * `contentEquals` / `contentHashCode` so the type behaves the way
     * callers expect.
     */
    class Match(
        val address: String,
        val role: Player,
        val deadline: Long,
        val secretKey: ByteArray,
        val regulation: RegulationWitnesses? = null,
        val sd: SdWitnesses? = null,
        /**
         * The AI opponent's (P2) persisted identity + witnesses — present iff
         * this is a PvAI match. The device controls both players, so the AI's
         * own secret key + picks must survive process death for resume to drive
         * the AI's reveal with the SAME key that committed on chain. Null for
         * PvP (the opponent is a separate device) — that's the PvAI discriminator.
         */
        val ai: AiState? = null,
    ) {
        fun copy(
            address: String = this.address,
            role: Player = this.role,
            deadline: Long = this.deadline,
            secretKey: ByteArray = this.secretKey,
            regulation: RegulationWitnesses? = this.regulation,
            sd: SdWitnesses? = this.sd,
            ai: AiState? = this.ai,
        ): Match = Match(address, role, deadline, secretKey, regulation, sd, ai)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Match) return false
            return address == other.address &&
                role == other.role &&
                deadline == other.deadline &&
                secretKey.contentEquals(other.secretKey) &&
                regulation == other.regulation &&
                sd == other.sd &&
                ai == other.ai
        }

        override fun hashCode(): Int {
            var result = address.hashCode()
            result = 31 * result + role.hashCode()
            result = 31 * result + deadline.hashCode()
            result = 31 * result + secretKey.contentHashCode()
            result = 31 * result + (regulation?.hashCode() ?: 0)
            result = 31 * result + (sd?.hashCode() ?: 0)
            result = 31 * result + (ai?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "Match(address=${address.take(16)}…, role=$role, deadline=$deadline, " +
                "regulation=${if (regulation != null) "[set]" else "null"}, " +
                "sd=${if (sd != null) "[round=${sd.round}]" else "null"}, " +
                "ai=${if (ai != null) "[set]" else "null"})"
    }

    /**
     * The AI opponent's persisted side of a PvAI match: its secret key plus the
     * regulation / sudden-death witnesses it committed. Kept separate from the
     * local player's slots ([Match.regulation] / [Match.sd]) so a PvAI device —
     * which commits for BOTH players — doesn't overwrite its own witnesses with
     * the AI's. Same `data class` ByteArray trap as the others → hand-rolled.
     */
    class AiState(
        val secretKey: ByteArray,
        val regulation: RegulationWitnesses? = null,
        val sd: SdWitnesses? = null,
    ) {
        fun copy(
            secretKey: ByteArray = this.secretKey,
            regulation: RegulationWitnesses? = this.regulation,
            sd: SdWitnesses? = this.sd,
        ): AiState = AiState(secretKey, regulation, sd)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AiState) return false
            return secretKey.contentEquals(other.secretKey) &&
                regulation == other.regulation &&
                sd == other.sd
        }

        override fun hashCode(): Int {
            var result = secretKey.contentHashCode()
            result = 31 * result + (regulation?.hashCode() ?: 0)
            result = 31 * result + (sd?.hashCode() ?: 0)
            return result
        }
    }

    /** Same `data class` trap as [Match] — `ByteArray`/`IntArray` require content-equality. */
    class RegulationWitnesses(
        val shoots: IntArray,
        val keeps: IntArray,
        val nonce: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RegulationWitnesses) return false
            return shoots.contentEquals(other.shoots) &&
                keeps.contentEquals(other.keeps) &&
                nonce.contentEquals(other.nonce)
        }

        override fun hashCode(): Int {
            var result = shoots.contentHashCode()
            result = 31 * result + keeps.contentHashCode()
            result = 31 * result + nonce.contentHashCode()
            return result
        }
    }

    /** Same `data class` trap as [Match] — `ByteArray` requires content-equality. */
    class SdWitnesses(
        val round: Int,
        val shoot: Int,
        val keep: Int,
        val nonce: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SdWitnesses) return false
            return round == other.round &&
                shoot == other.shoot &&
                keep == other.keep &&
                nonce.contentEquals(other.nonce)
        }

        override fun hashCode(): Int {
            var result = round
            result = 31 * result + shoot
            result = 31 * result + keep
            result = 31 * result + nonce.contentHashCode()
            return result
        }
    }

    /**
     * Guards every read-modify-write of the address-index + per-match
     * record pair. Without it, concurrent `save(A)`/`delete(B)` can
     * race on the index list and resurrect a deleted address. Reads
     * (`load`, `loadAll`) don't need it — `EncryptedSharedPreferences`
     * reads are atomic and a stale view is acceptable.
     */
    private val indexLock = Any()

    /** All matches currently in flight on this device. */
    fun loadAll(): List<Match> = readAddressIndex().mapNotNull { load(it) }

    /** Lookup by contract address. `null` when the address isn't in the store. */
    fun load(address: String): Match? {
        val json = prefs.getString(matchKey(address), null) ?: return null
        return runCatching { decodeMatch(JSONObject(json)) }.getOrNull()
    }

    /** Persist (insert or update). Idempotent on identical content. */
    fun save(match: Match) {
        val json = encodeMatch(match).toString()
        synchronized(indexLock) {
            // Read the current index inside the lock — TOCTOU-safe
            // against a concurrent delete that filters out an address
            // we'd otherwise re-distinct here.
            val newAddresses = (readAddressIndex() + match.address).distinct()
            prefs.edit()
                .putString(matchKey(match.address), json)
                .putString(KEY_INDEX, JSONArray(newAddresses).toString())
                .commit()
            Log.i(TAG, "Saved match ${match.address.take(16)}… (${match.role}), ${newAddresses.size} total")
        }
    }

    /** Remove a single match. No-op if [address] isn't stored. */
    fun delete(address: String) {
        synchronized(indexLock) {
            val remaining = readAddressIndex().filter { it != address }
            prefs.edit()
                .remove(matchKey(address))
                .putString(KEY_INDEX, JSONArray(remaining).toString())
                .commit()
            Log.i(TAG, "Deleted match ${address.take(16)}… (${remaining.size} remaining)")
        }
    }

    /**
     * Read just the address index without decoding each record. Used
     * by [save] and [delete] to avoid an O(n) decode pass on every
     * mutation. Returns an empty list when the index is missing or
     * malformed.
     */
    private fun readAddressIndex(): List<String> {
        val indexJson = prefs.getString(KEY_INDEX, null) ?: return emptyList()
        val arr = runCatching { JSONArray(indexJson) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    /** Wipe everything. Called on factory reset / sigil restore-and-replace. */
    fun clear() {
        synchronized(indexLock) {
            prefs.edit().clear().commit()
            Log.i(TAG, "Cleared all matches")
        }
    }

    // ── Cloud-backup seam ──

    /**
     * Serialize every match into an opaque blob suitable for
     * `SigilBackup.appMetadata`. The blob is wrapped in a versioned
     * envelope so a future schema rev can be loaded by an older app
     * version with a graceful "unknown schema, skip" fallback rather
     * than a parse crash.
     */
    fun snapshotBytes(): ByteArray {
        val matches = loadAll()
        val envelope = JSONObject().apply {
            put(KEY_ENVELOPE_VERSION, SCHEMA_VERSION)
            put(KEY_MATCHES, JSONArray().apply {
                matches.forEach { put(encodeMatch(it)) }
            })
        }
        return envelope.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Restore from a [snapshotBytes] blob, overwriting any local
     * entries. Used during sigil restore to repopulate matches on a
     * new device.
     *
     * **Safety:** unknown schema versions, non-JSON blobs, or blobs
     * where every record fails to decode are dropped — the existing
     * local store is preserved untouched. Only blobs we can decode at
     * least one record from trigger a clear-and-replace.
     */
    fun restoreFromBytes(blob: ByteArray) {
        val text = blob.toString(Charsets.UTF_8)
        val envelope = runCatching { JSONObject(text) }.getOrNull() ?: run {
            Log.w(TAG, "restoreFromBytes: blob is not valid JSON — local store preserved")
            return
        }
        val version = envelope.optInt(KEY_ENVELOPE_VERSION, -1)
        if (version != SCHEMA_VERSION) {
            Log.w(TAG, "restoreFromBytes: schema version $version != current $SCHEMA_VERSION — local store preserved")
            return
        }
        val arr = envelope.optJSONArray(KEY_MATCHES) ?: return

        // Decode all records BEFORE touching the local store. If
        // every decode fails, we leave local state alone rather than
        // wiping it for nothing.
        val decoded = (0 until arr.length()).mapNotNull { i ->
            runCatching { decodeMatch(arr.getJSONObject(i)) }
                .onFailure { Log.w(TAG, "restoreFromBytes: skipping malformed match #$i: ${it.message}") }
                .getOrNull()
        }
        if (decoded.isEmpty()) {
            Log.w(TAG, "restoreFromBytes: blob had ${arr.length()} records but none decoded — local store preserved")
            return
        }

        // Atomic restore: hold the index lock across the
        // clear-and-replace so a concurrent save() can't interleave a
        // local match between clear() and the loop's saves.
        synchronized(indexLock) {
            prefs.edit().clear().commit()
            decoded.forEach { match ->
                val json = encodeMatch(match).toString()
                val newAddresses = (readAddressIndex() + match.address).distinct()
                prefs.edit()
                    .putString(matchKey(match.address), json)
                    .putString(KEY_INDEX, JSONArray(newAddresses).toString())
                    .commit()
            }
        }
        Log.i(TAG, "Restored ${decoded.size} matches from cloud blob")
    }

    // ── Serialization ──

    private fun encodeMatch(match: Match): JSONObject = JSONObject().apply {
        put(KEY_ADDRESS, match.address)
        put(KEY_ROLE, match.role.name)
        put(KEY_DEADLINE, match.deadline)
        put(KEY_SECRET_KEY, match.secretKey.toB64())
        match.regulation?.let { put(KEY_REGULATION, encodeRegulation(it)) }
        match.sd?.let { put(KEY_SD, encodeSd(it)) }
        match.ai?.let { ai ->
            put(KEY_AI, JSONObject().apply {
                put(KEY_AI_SECRET, ai.secretKey.toB64())
                ai.regulation?.let { put(KEY_REGULATION, encodeRegulation(it)) }
                ai.sd?.let { put(KEY_SD, encodeSd(it)) }
            })
        }
    }

    private fun decodeMatch(json: JSONObject): Match = Match(
        address = json.getString(KEY_ADDRESS),
        role = Player.valueOf(json.getString(KEY_ROLE)),
        deadline = json.getLong(KEY_DEADLINE),
        secretKey = json.getString(KEY_SECRET_KEY).fromB64(),
        regulation = json.optJSONObject(KEY_REGULATION)?.let(::decodeRegulation),
        sd = json.optJSONObject(KEY_SD)?.let(::decodeSd),
        ai = json.optJSONObject(KEY_AI)?.let { ai ->
            AiState(
                secretKey = ai.getString(KEY_AI_SECRET).fromB64(),
                regulation = ai.optJSONObject(KEY_REGULATION)?.let(::decodeRegulation),
                sd = ai.optJSONObject(KEY_SD)?.let(::decodeSd),
            )
        },
    )

    private fun encodeRegulation(reg: RegulationWitnesses): JSONObject = JSONObject().apply {
        put(KEY_REG_SHOOTS, encodePicks(reg.shoots))
        put(KEY_REG_KEEPS, encodePicks(reg.keeps))
        put(KEY_REG_NONCE, reg.nonce.toB64())
    }

    private fun decodeRegulation(reg: JSONObject): RegulationWitnesses = RegulationWitnesses(
        shoots = decodePicks(reg.getString(KEY_REG_SHOOTS)),
        keeps = decodePicks(reg.getString(KEY_REG_KEEPS)),
        nonce = reg.getString(KEY_REG_NONCE).fromB64(),
    )

    private fun encodeSd(sd: SdWitnesses): JSONObject = JSONObject().apply {
        put(KEY_SD_ROUND, sd.round)
        put(KEY_SD_SHOOT, sd.shoot)
        put(KEY_SD_KEEP, sd.keep)
        put(KEY_SD_NONCE, sd.nonce.toB64())
    }

    private fun decodeSd(sd: JSONObject): SdWitnesses = SdWitnesses(
        round = sd.getInt(KEY_SD_ROUND),
        shoot = sd.getInt(KEY_SD_SHOOT),
        keep = sd.getInt(KEY_SD_KEEP),
        nonce = sd.getString(KEY_SD_NONCE).fromB64(),
    )

    private fun encodePicks(picks: IntArray): String = picks.joinToString(",")

    private fun decodePicks(encoded: String): IntArray =
        if (encoded.isEmpty()) IntArray(0)
        else encoded.split(",").map { it.toInt() }.toIntArray()

    private fun ByteArray.toB64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.fromB64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    private fun matchKey(address: String): String = "match.$address"

    private companion object {
        private const val TAG = "MatchStore"
        private const val PREFS_FILE = "kicks_match_store"

        /**
         * Build an EncryptedSharedPreferences backed by a Keystore
         * master key. Lives at this layer (not directly inside the
         * constructor) so the test-friendly primary constructor can
         * accept plain prefs and skip Keystore entirely.
         */
        private fun buildEncryptedPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        /**
         * Bump when [encodeMatch] / [decodeMatch] change format. The
         * cloud envelope tags every snapshot with this so a restore on
         * an older app version refuses politely instead of crashing.
         */
        private const val SCHEMA_VERSION = 1

        /** Pref key holding a JSON array of every saved address. */
        private const val KEY_INDEX = "index"

        /** Envelope keys (cloud-backup blob). */
        private const val KEY_ENVELOPE_VERSION = "v"
        private const val KEY_MATCHES = "matches"

        /** Per-match record keys. */
        private const val KEY_ADDRESS = "address"
        private const val KEY_ROLE = "role"
        private const val KEY_DEADLINE = "deadline"
        private const val KEY_SECRET_KEY = "secretKey"
        private const val KEY_REGULATION = "regulation"
        private const val KEY_REG_SHOOTS = "shoots"
        private const val KEY_REG_KEEPS = "keeps"
        private const val KEY_REG_NONCE = "nonce"
        private const val KEY_SD = "sd"
        private const val KEY_SD_ROUND = "round"
        private const val KEY_SD_SHOOT = "shoot"
        private const val KEY_SD_KEEP = "keep"
        private const val KEY_SD_NONCE = "nonce"
        // PvAI: the AI opponent's persisted side (additive + optional, so old
        // PvP records decode with ai=null — no SCHEMA_VERSION bump needed).
        private const val KEY_AI = "ai"
        private const val KEY_AI_SECRET = "aiSecret"
    }
}
