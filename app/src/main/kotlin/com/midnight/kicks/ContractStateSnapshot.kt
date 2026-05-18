package com.midnight.kicks

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * A typed view of the penalty contract V3's on-chain ledger.
 *
 * `MidnightConfig.queryState(address)` returns a positional `JSONArray` of
 * SCALE-decoded cells. Each cell is a JSONObject like
 * `{"type":"cell", "number": 1}` for Booleans/Uints/Counters, or
 * `{"type":"cell", "hex": "abc…"}` for Bytes<N> and `Vector<N, Uint<8>>`
 * (concatenated element bytes). This class translates that raw shape into
 * named fields matching `penalty.compact`.
 *
 * V3 splits storage across two top-level groups (verified against
 * `assets/runtime/penalty-contract.js:4024+`):
 *   - Group 0 (8 cells): phase, player1, player2, p1Commitment,
 *     p2Commitment, p1Committed, p2Committed, p1Shoots
 *   - Group 1 (15 cells): p1Keeps, p2Shoots, p2Keeps, p1SdShoot,
 *     p1SdKeep, p2SdShoot, p2SdKeep, p1Revealed, p2Revealed, p1Score,
 *     p2Score, winner, isDraw, deadline, sdRound
 *
 * Each `Vector<5, Uint<8>>` ledger field serializes to ONE cell holding
 * 5 concatenated bytes (10 hex chars).
 *
 * The SDK does not yet expose a typed ledger wrapper for arbitrary
 * contracts; this parsing pattern is what every Kuira dApp ends up writing.
 * See PLAN.md "SDK connector wishlist" item #9 (codegen typed ledger from
 * `.compact`).
 */
data class ContractStateSnapshot(
    val phase: Int,
    val player1: ByteArray,
    val player2: ByteArray,
    val p1Commitment: ByteArray,
    val p2Commitment: ByteArray,
    val p1Committed: Boolean,
    val p2Committed: Boolean,
    val p1Shoots: IntArray,   // 5 entries, all 0 before P1 reveals
    val p1Keeps:  IntArray,
    val p2Shoots: IntArray,
    val p2Keeps:  IntArray,
    val p1SdShoot: Int,       // 0 outside SD or before SD reveal
    val p1SdKeep:  Int,
    val p2SdShoot: Int,
    val p2SdKeep:  Int,
    val p1Revealed: Boolean,
    val p2Revealed: Boolean,
    val p1Score: Int,
    val p2Score: Int,
    val winner: ByteArray,
    val isDraw: Boolean,
    val deadline: Long,
    val sdRound: Int,
) {
    /** Both players have committed but neither has revealed yet. */
    val bothCommitted: Boolean get() = p1Committed && p2Committed

    /** Both players have revealed — match is resolved (or in sudden death). */
    val bothRevealed: Boolean get() = p1Revealed && p2Revealed

    /** Has anyone joined yet (P2 commit slot has data). */
    val matchJoined: Boolean get() = !player2.all { it == 0.toByte() }

    /** Concise log/debug summary. */
    fun summary(): String =
        "phase=$phase  p1Committed=$p1Committed  p2Committed=$p2Committed  " +
        "p1Revealed=$p1Revealed  p2Revealed=$p2Revealed  " +
        "score=$p1Score-$p2Score  isDraw=$isDraw  sdRound=$sdRound"

    // Data class equality with arrays needs explicit overrides.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ContractStateSnapshot
        if (phase != other.phase) return false
        if (!player1.contentEquals(other.player1)) return false
        if (!player2.contentEquals(other.player2)) return false
        if (!p1Commitment.contentEquals(other.p1Commitment)) return false
        if (!p2Commitment.contentEquals(other.p2Commitment)) return false
        if (p1Committed != other.p1Committed) return false
        if (p2Committed != other.p2Committed) return false
        if (!p1Shoots.contentEquals(other.p1Shoots)) return false
        if (!p1Keeps.contentEquals(other.p1Keeps)) return false
        if (!p2Shoots.contentEquals(other.p2Shoots)) return false
        if (!p2Keeps.contentEquals(other.p2Keeps)) return false
        if (p1SdShoot != other.p1SdShoot) return false
        if (p1SdKeep != other.p1SdKeep) return false
        if (p2SdShoot != other.p2SdShoot) return false
        if (p2SdKeep != other.p2SdKeep) return false
        if (p1Revealed != other.p1Revealed) return false
        if (p2Revealed != other.p2Revealed) return false
        if (p1Score != other.p1Score) return false
        if (p2Score != other.p2Score) return false
        if (!winner.contentEquals(other.winner)) return false
        if (isDraw != other.isDraw) return false
        if (deadline != other.deadline) return false
        if (sdRound != other.sdRound) return false
        return true
    }

    override fun hashCode(): Int {
        var r = phase
        r = 31 * r + player1.contentHashCode()
        r = 31 * r + player2.contentHashCode()
        r = 31 * r + p1Commitment.contentHashCode()
        r = 31 * r + p2Commitment.contentHashCode()
        r = 31 * r + p1Committed.hashCode()
        r = 31 * r + p2Committed.hashCode()
        r = 31 * r + p1Shoots.contentHashCode()
        r = 31 * r + p1Keeps.contentHashCode()
        r = 31 * r + p2Shoots.contentHashCode()
        r = 31 * r + p2Keeps.contentHashCode()
        r = 31 * r + p1SdShoot
        r = 31 * r + p1SdKeep
        r = 31 * r + p2SdShoot
        r = 31 * r + p2SdKeep
        r = 31 * r + p1Revealed.hashCode()
        r = 31 * r + p2Revealed.hashCode()
        r = 31 * r + p1Score
        r = 31 * r + p2Score
        r = 31 * r + winner.contentHashCode()
        r = 31 * r + isDraw.hashCode()
        r = 31 * r + deadline.hashCode()
        r = 31 * r + sdRound
        return r
    }

    companion object {
        // ── Flat-cell indices, V3 layout ────────────────────────────
        // Verified against the `idx` paths in
        // assets/runtime/penalty-contract.js (the JS contract emitted
        // by `compactc 0.30.0`). Do NOT reorder unless the .compact
        // field declarations change — every Vector<5, Uint<8>> field
        // occupies exactly one cell, not five.
        private const val CELL_PHASE         = 0
        private const val CELL_PLAYER1       = 1
        private const val CELL_PLAYER2       = 2
        private const val CELL_P1_COMMITMENT = 3
        private const val CELL_P2_COMMITMENT = 4
        private const val CELL_P1_COMMITTED  = 5
        private const val CELL_P2_COMMITTED  = 6
        private const val CELL_P1_SHOOTS     = 7
        private const val CELL_P1_KEEPS      = 8
        private const val CELL_P2_SHOOTS     = 9
        private const val CELL_P2_KEEPS      = 10
        private const val CELL_P1_SD_SHOOT   = 11
        private const val CELL_P1_SD_KEEP    = 12
        private const val CELL_P2_SD_SHOOT   = 13
        private const val CELL_P2_SD_KEEP    = 14
        private const val CELL_P1_REVEALED   = 15
        private const val CELL_P2_REVEALED   = 16
        private const val CELL_P1_SCORE      = 17
        private const val CELL_P2_SCORE      = 18
        private const val CELL_WINNER        = 19
        private const val CELL_IS_DRAW       = 20
        private const val CELL_DEADLINE      = 21
        private const val CELL_SD_ROUND      = 22

        private const val EXPECTED_CELLS = 23
        private const val BYTES32_LEN = 32
        private const val PICKS_PER_ARRAY = 5
        private const val TAG = "ContractStateSnapshot"

        /**
         * Parse a state JSONArray from `MidnightConfig.queryState`. Returns
         * null (and logs at WARN) if the state shape doesn't match what we
         * expect (the contract hasn't deployed yet, or the .compact schema
         * has drifted and our cell-index map is stale).
         *
         * The penalty contract stores its ledger in two top-level groups
         * (`[[8 cells], [15 cells]]`). `flattenCells` walks the nested
         * tree depth-first, producing a single 23-cell positional list.
         */
        fun parse(state: JSONArray): ContractStateSnapshot? {
            val cells = flattenCells(state)
            if (cells.size < EXPECTED_CELLS) {
                Log.w(TAG, "parse: expected $EXPECTED_CELLS cells, got ${cells.size} — dumping: $state")
                return null
            }
            return try {
                ContractStateSnapshot(
                    phase        = cellNumber(cells, CELL_PHASE).toInt(),
                    player1      = cellHex(cells, CELL_PLAYER1, BYTES32_LEN),
                    player2      = cellHex(cells, CELL_PLAYER2, BYTES32_LEN),
                    p1Commitment = cellHex(cells, CELL_P1_COMMITMENT, BYTES32_LEN),
                    p2Commitment = cellHex(cells, CELL_P2_COMMITMENT, BYTES32_LEN),
                    p1Committed  = cellBoolean(cells, CELL_P1_COMMITTED),
                    p2Committed  = cellBoolean(cells, CELL_P2_COMMITTED),
                    p1Shoots     = cellVectorUint8(cells, CELL_P1_SHOOTS, PICKS_PER_ARRAY),
                    p1Keeps      = cellVectorUint8(cells, CELL_P1_KEEPS,  PICKS_PER_ARRAY),
                    p2Shoots     = cellVectorUint8(cells, CELL_P2_SHOOTS, PICKS_PER_ARRAY),
                    p2Keeps      = cellVectorUint8(cells, CELL_P2_KEEPS,  PICKS_PER_ARRAY),
                    p1SdShoot    = cellNumber(cells, CELL_P1_SD_SHOOT).toInt(),
                    p1SdKeep     = cellNumber(cells, CELL_P1_SD_KEEP).toInt(),
                    p2SdShoot    = cellNumber(cells, CELL_P2_SD_SHOOT).toInt(),
                    p2SdKeep     = cellNumber(cells, CELL_P2_SD_KEEP).toInt(),
                    p1Revealed   = cellBoolean(cells, CELL_P1_REVEALED),
                    p2Revealed   = cellBoolean(cells, CELL_P2_REVEALED),
                    p1Score      = cellNumber(cells, CELL_P1_SCORE).toInt(),
                    p2Score      = cellNumber(cells, CELL_P2_SCORE).toInt(),
                    winner       = cellHex(cells, CELL_WINNER, BYTES32_LEN),
                    isDraw       = cellBoolean(cells, CELL_IS_DRAW),
                    deadline     = cellNumber(cells, CELL_DEADLINE),
                    sdRound      = cellNumber(cells, CELL_SD_ROUND).toInt(),
                )
            } catch (e: Exception) {
                Log.w(TAG, "parse failed — cell-index map likely out of sync with .compact", e)
                null
            }
        }

        /**
         * Walk an arbitrarily-nested tree of JSONArrays containing JSONObject
         * cells and return a flat positional list. Handles BBoard's flat
         * layout, penalty's split `[[8], [15]]`, and any other nesting the
         * SDK may surface in the future.
         */
        internal fun flattenCells(state: JSONArray): List<JSONObject> {
            val out = mutableListOf<JSONObject>()
            walk(state, out)
            return out
        }

        private fun walk(node: Any?, out: MutableList<JSONObject>) {
            when (node) {
                is JSONObject -> out.add(node)
                is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), out)
                else -> {} // ignore primitives / nulls
            }
        }

        /**
         * Booleans encode either as `{"number": 0|1}` (BBoard) or
         * `{"hex": "00"|"01"}` (penalty). Treat any non-empty / non-zero
         * value as true.
         */
        private fun cellBoolean(cells: List<JSONObject>, index: Int): Boolean {
            val cell = cells.getOrNull(index) ?: return false
            cell.opt("number").let { if (it is Number) return it.toInt() != 0 }
            val hex = cell.optString("hex", "")
            return hex.isNotEmpty() && hex.any { it != '0' }
        }

        /**
         * Numbers (Uint<N>, Counter, Phase enum) encode as `{"number": N}`
         * or `{"hex": "…"}` (SCALE little-endian). Empty hex = 0.
         */
        private fun cellNumber(cells: List<JSONObject>, index: Int): Long {
            val cell = cells.getOrNull(index) ?: return 0L
            cell.opt("number").let { if (it is Number) return it.toLong() }
            return parseHexLittleEndian(cell.optString("hex", ""))
        }

        /** Bytes<N> encode as `{"hex": "abcd…"}`. Returns zeros for missing. */
        private fun cellHex(cells: List<JSONObject>, index: Int, expectedLen: Int): ByteArray {
            val hex = cells.getOrNull(index)?.optString("hex", "") ?: ""
            if (hex.isEmpty()) return ByteArray(expectedLen)
            return ByteArray(hex.length / 2) {
                hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
            }
        }

        /**
         * `Vector<N, Uint<8>>` encodes as ONE cell whose hex is the
         * concatenated element bytes (`{"hex": "0102000201"}` for the
         * vector [1,2,0,2,1]). Returns zeros if missing or wrong length.
         */
        private fun cellVectorUint8(
            cells: List<JSONObject>,
            index: Int,
            length: Int,
        ): IntArray {
            val hex = cells.getOrNull(index)?.optString("hex", "") ?: ""
            if (hex.length != length * 2) return IntArray(length)
            return IntArray(length) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16)
            }
        }

        /** Parse a little-endian hex string into a Long (SCALE codec convention). */
        private fun parseHexLittleEndian(hex: String): Long {
            if (hex.isEmpty()) return 0L
            val byteCount = (hex.length / 2).coerceAtMost(8)
            var result = 0L
            for (i in 0 until byteCount) {
                val b = hex.substring(i * 2, i * 2 + 2).toInt(16).toLong() and 0xFFL
                result = result or (b shl (i * 8))
            }
            return result
        }
    }
}
