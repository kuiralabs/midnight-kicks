package com.midnight.kicks

import com.midnight.kuira.core.compact.MidnightLedger

/**
 * A typed snapshot of the penalty contract's ledger.
 *
 * **Construction.** Built via [fromLedger] from a [MidnightLedger]
 * read off `MidnightContract.ledger()`. The SDK's runtime-driven
 * ledger read is lossless — see PLAN.md wishlist #9 for the
 * post-mortem on the cell-hex parser this replaces.
 *
 * **Field map.** Mirrors `penalty.compact`'s ledger declarations
 * one-for-one; each name here corresponds exactly to a Compact
 * `export ledger` line. Field types use Kotlin primitives where
 * lossless (Vector<5, Uint<8>> → IntArray of 5 elements, Bytes<32>
 * → 32-byte ByteArray, Counter → Int via Uint<64>→Long→Int because
 * scores never exceed 5+SDrounds in practice).
 *
 * **Equality.** Hand-rolled because Kotlin's data class auto-equals
 * uses reference equality for arrays (`IntArray`, `ByteArray`) which
 * would break `StateFlow.distinctUntilChanged` on otherwise-identical
 * snapshots.
 */
data class ContractStateSnapshot(
    val phase: Int,
    val player1: ByteArray,
    val player2: ByteArray,
    val p1Commitment: ByteArray,
    val p2Commitment: ByteArray,
    val p1Committed: Boolean,
    val p2Committed: Boolean,
    val p1Shoots: IntArray,
    val p1Keeps:  IntArray,
    val p2Shoots: IntArray,
    val p2Keeps:  IntArray,
    val p1SdShoot: Int,
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

    // Data class equality with arrays needs explicit overrides — the
    // generated equals uses reference equality on Array fields, which
    // makes `distinctUntilChanged` re-emit on every poll.

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
        /**
         * Build a snapshot from a [MidnightLedger] (lossless,
         * runtime-driven ledger read from `MidnightContract.ledger()`).
         * Each typed accessor enforces its expected shape — contract-schema
         * drift surfaces as [com.midnight.kuira.core.compact.WrongLedgerFieldTypeException]
         * or [com.midnight.kuira.core.compact.MissingLedgerFieldException]
         * rather than silent zeros.
         */
        fun fromLedger(ledger: MidnightLedger): ContractStateSnapshot = ContractStateSnapshot(
            phase        = ledger.getUint8("phase"),
            player1      = ledger.getBytes("player1", BYTES32_LEN),
            player2      = ledger.getBytes("player2", BYTES32_LEN),
            p1Commitment = ledger.getBytes("p1Commitment", BYTES32_LEN),
            p2Commitment = ledger.getBytes("p2Commitment", BYTES32_LEN),
            p1Committed  = ledger.getBoolean("p1Committed"),
            p2Committed  = ledger.getBoolean("p2Committed"),
            p1Shoots     = ledger.getVectorUint8("p1Shoots", PICKS_PER_ARRAY),
            p1Keeps      = ledger.getVectorUint8("p1Keeps",  PICKS_PER_ARRAY),
            p2Shoots     = ledger.getVectorUint8("p2Shoots", PICKS_PER_ARRAY),
            p2Keeps      = ledger.getVectorUint8("p2Keeps",  PICKS_PER_ARRAY),
            p1SdShoot    = ledger.getUint8("p1SdShoot"),
            p1SdKeep     = ledger.getUint8("p1SdKeep"),
            p2SdShoot    = ledger.getUint8("p2SdShoot"),
            p2SdKeep     = ledger.getUint8("p2SdKeep"),
            p1Revealed   = ledger.getBoolean("p1Revealed"),
            p2Revealed   = ledger.getBoolean("p2Revealed"),
            p1Score      = ledger.getUint64("p1Score").toInt(),
            p2Score      = ledger.getUint64("p2Score").toInt(),
            winner       = ledger.getBytes("winner", BYTES32_LEN),
            isDraw       = ledger.getBoolean("isDraw"),
            deadline     = ledger.getUint64("deadline"),
            sdRound      = ledger.getUint64("sdRound").toInt(),
        )

        private const val BYTES32_LEN = 32
        private const val PICKS_PER_ARRAY = 5
    }
}
