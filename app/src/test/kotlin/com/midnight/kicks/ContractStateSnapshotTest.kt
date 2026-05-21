package com.midnight.kicks

import com.midnight.kuira.core.compact.MidnightLedger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * Unit tests for [ContractStateSnapshot] — the typed snapshot of the
 * penalty contract's ledger.
 *
 * Now that the lossy cell-hex parser is gone (replaced by
 * `MidnightContract.ledger()` driven by the runtime), these tests
 * focus on what's left at this layer:
 *  - [ContractStateSnapshot.fromLedger] correctly threads each
 *    typed `MidnightLedger.getX` into the right field — a wrong
 *    accessor or a swapped field would fail loudly here, before
 *    reaching live code.
 *  - The hand-rolled `equals` / `hashCode` work as expected over
 *    array fields (otherwise `distinctUntilChanged` in the poller
 *    re-emits on identical snapshots).
 *  - Convenience accessors (`bothCommitted`, `matchJoined`, etc.)
 *    reflect their underlying fields.
 *
 * Lossless decoding of `Vector<N, Uint<8>>` (the bug class this whole
 * refactor eliminated — see PLAN.md wishlist #9) is now tested at the
 * SDK level in `MidnightLedgerTest` and (eventually) in an
 * instrumented test against the real penalty runtime.
 */
class ContractStateSnapshotTest {

    @Test
    fun `fromLedger threads each typed accessor into the right field`() {
        val ledger = ledgerOf(
            "phase"        to BigInteger.valueOf(3),
            "player1"      to ByteArray(32) { 0x11.toByte() },
            "player2"      to ByteArray(32) { 0x22.toByte() },
            "p1Commitment" to ByteArray(32) { 0xAA.toByte() },
            "p2Commitment" to ByteArray(32) { 0xBB.toByte() },
            "p1Committed"  to true,
            "p2Committed"  to true,
            "p1Shoots"     to listOfBig(0, 1, 2, 1, 0),
            "p1Keeps"      to listOfBig(2, 1, 0, 1, 2),  // internal zero — the bug case
            "p2Shoots"     to listOfBig(1, 0, 2, 0, 1),  // multiple internal zeros
            "p2Keeps"      to listOfBig(0, 0, 0, 1, 2),  // leading zeros
            "p1SdShoot"    to BigInteger.valueOf(1),
            "p1SdKeep"     to BigInteger.valueOf(2),
            "p2SdShoot"    to BigInteger.valueOf(0),
            "p2SdKeep"     to BigInteger.valueOf(1),
            "p1Revealed"   to true,
            "p2Revealed"   to false,
            "p1Score"      to BigInteger.valueOf(3),
            "p2Score"      to BigInteger.valueOf(2),
            "winner"       to ByteArray(32),
            "isDraw"       to false,
            "deadline"     to BigInteger.valueOf(9_999_999_999L),
            "sdRound"      to BigInteger.valueOf(1),
        )

        val snap = ContractStateSnapshot.fromLedger(ledger)

        assertEquals(3, snap.phase)
        assertEquals(0x11.toByte(), snap.player1[0])
        assertEquals(0x22.toByte(), snap.player2[0])
        assertEquals(0xAA.toByte(), snap.p1Commitment[0])
        assertEquals(0xBB.toByte(), snap.p2Commitment[0])
        assertTrue(snap.p1Committed)
        assertTrue(snap.p2Committed)
        assertArrayEquals(intArrayOf(0, 1, 2, 1, 0), snap.p1Shoots)
        // The load-bearing assertion: internal zeros survive the
        // ledger → snapshot pipeline. Pre-refactor, this position
        // would be silently zeroed by the lossy cell-hex parser.
        assertArrayEquals(intArrayOf(2, 1, 0, 1, 2), snap.p1Keeps)
        assertArrayEquals(intArrayOf(1, 0, 2, 0, 1), snap.p2Shoots)
        assertArrayEquals(intArrayOf(0, 0, 0, 1, 2), snap.p2Keeps)
        assertEquals(1, snap.p1SdShoot)
        assertEquals(2, snap.p1SdKeep)
        assertEquals(0, snap.p2SdShoot)  // zero is real data, not "missing"
        assertEquals(1, snap.p2SdKeep)
        assertTrue(snap.p1Revealed)
        assertFalse(snap.p2Revealed)
        assertEquals(3, snap.p1Score)
        assertEquals(2, snap.p2Score)
        assertEquals(9_999_999_999L, snap.deadline)
        assertEquals(1, snap.sdRound)
    }

    @Test
    fun `fromLedger preserves all-zero arrays — pre-commit and pre-reveal state`() {
        // Fresh-deploy or pre-reveal cells return all zeros from the
        // ledger. Used to be the smoking-gun pattern for the lossy
        // parser (which also returned all zeros even POST-reveal).
        val ledger = ledgerOf(
            "phase"        to BigInteger.ZERO,
            "player1"      to ByteArray(32),
            "player2"      to ByteArray(32),
            "p1Commitment" to ByteArray(32),
            "p2Commitment" to ByteArray(32),
            "p1Committed"  to false,
            "p2Committed"  to false,
            "p1Shoots"     to listOfBig(0, 0, 0, 0, 0),
            "p1Keeps"      to listOfBig(0, 0, 0, 0, 0),
            "p2Shoots"     to listOfBig(0, 0, 0, 0, 0),
            "p2Keeps"      to listOfBig(0, 0, 0, 0, 0),
            "p1SdShoot"    to BigInteger.ZERO,
            "p1SdKeep"     to BigInteger.ZERO,
            "p2SdShoot"    to BigInteger.ZERO,
            "p2SdKeep"     to BigInteger.ZERO,
            "p1Revealed"   to false,
            "p2Revealed"   to false,
            "p1Score"      to BigInteger.ZERO,
            "p2Score"      to BigInteger.ZERO,
            "winner"       to ByteArray(32),
            "isDraw"       to false,
            "deadline"     to BigInteger.ZERO,
            "sdRound"      to BigInteger.ZERO,
        )

        val snap = ContractStateSnapshot.fromLedger(ledger)

        assertArrayEquals(IntArray(5), snap.p1Shoots)
        assertArrayEquals(IntArray(5), snap.p1Keeps)
        assertArrayEquals(IntArray(5), snap.p2Shoots)
        assertArrayEquals(IntArray(5), snap.p2Keeps)
        assertFalse(snap.matchJoined)
        assertFalse(snap.bothCommitted)
        assertFalse(snap.bothRevealed)
    }

    @Test
    fun `equals uses content equality on array fields`() {
        // Without content-aware equals, two structurally-equal
        // snapshots produced by two consecutive polls would compare
        // as different (Array reference equality), and
        // distinctUntilChanged would re-emit every tick.
        val fields = defaultLedger()
        val a = ContractStateSnapshot.fromLedger(MidnightLedgerTestFactory.fromMap(fields))
        val b = ContractStateSnapshot.fromLedger(MidnightLedgerTestFactory.fromMap(fields))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `equals returns false when a Vector field differs`() {
        val a = ContractStateSnapshot.fromLedger(MidnightLedgerTestFactory.fromMap(defaultLedger()))
        val b = ContractStateSnapshot.fromLedger(
            MidnightLedgerTestFactory.fromMap(
                defaultLedger() + ("p1Shoots" to listOfBig(2, 1, 0, 1, 2)),
            ),
        )
        assertNotEquals(a, b)
    }

    @Test
    fun `matchJoined flips true when player2 has any non-zero byte`() {
        val a = ContractStateSnapshot.fromLedger(MidnightLedgerTestFactory.fromMap(defaultLedger()))
        assertFalse(a.matchJoined)

        val withP2 = ContractStateSnapshot.fromLedger(
            MidnightLedgerTestFactory.fromMap(
                defaultLedger() + ("player2" to ByteArray(32) { if (it == 0) 0x99.toByte() else 0 }),
            ),
        )
        assertTrue(withP2.matchJoined)
    }

    @Test
    fun `summary contains the high-signal fields a developer scans for`() {
        val fields = defaultLedger()
            .toMutableMap()
            .apply { put("phase", BigInteger.valueOf(3)); put("sdRound", BigInteger.valueOf(2)) }
        val snap = ContractStateSnapshot.fromLedger(MidnightLedgerTestFactory.fromMap(fields))
        val s = snap.summary()
        assertTrue(s, s.contains("phase=3"))
        assertTrue(s, s.contains("sdRound=2"))
        assertTrue(s, s.contains("score=0-0"))
    }

    // ── Test helpers ────────────────────────────────────────────────────

    /** Build a [MidnightLedger] from raw `name to value` pairs. */
    private fun ledgerOf(vararg pairs: Pair<String, Any?>): MidnightLedger =
        MidnightLedgerTestFactory.fromMap(pairs.toMap())

    /** Construct a list of BigIntegers from Ints. */
    private fun listOfBig(vararg values: Int): List<BigInteger> =
        values.map { BigInteger.valueOf(it.toLong()) }

    private fun defaultLedger(): Map<String, Any?> = mapOf(
        "phase"        to BigInteger.ZERO,
        "player1"      to ByteArray(32),
        "player2"      to ByteArray(32),
        "p1Commitment" to ByteArray(32),
        "p2Commitment" to ByteArray(32),
        "p1Committed"  to false,
        "p2Committed"  to false,
        "p1Shoots"     to listOfBig(0, 0, 0, 0, 0),
        "p1Keeps"      to listOfBig(0, 0, 0, 0, 0),
        "p2Shoots"     to listOfBig(0, 0, 0, 0, 0),
        "p2Keeps"      to listOfBig(0, 0, 0, 0, 0),
        "p1SdShoot"    to BigInteger.ZERO,
        "p1SdKeep"     to BigInteger.ZERO,
        "p2SdShoot"    to BigInteger.ZERO,
        "p2SdKeep"     to BigInteger.ZERO,
        "p1Revealed"   to false,
        "p2Revealed"   to false,
        "p1Score"      to BigInteger.ZERO,
        "p2Score"      to BigInteger.ZERO,
        "winner"       to ByteArray(32),
        "isDraw"       to false,
        "deadline"     to BigInteger.ZERO,
        "sdRound"      to BigInteger.ZERO,
    )
}

/**
 * Test-only factory for [MidnightLedger] — the production constructor
 * is `internal` to the SDK module. This exists in the test source so
 * Kicks tests can drive the snapshot factory directly without spinning
 * up the full QuickJs runtime. Uses Kotlin reflection to access the
 * SDK's package-private constructor.
 */
private object MidnightLedgerTestFactory {
    fun fromMap(fields: Map<String, Any?>): MidnightLedger {
        // The internal constructor takes `Map<String, Any?>` — we hit
        // it via reflection. This couples the test to the SDK's
        // current internal shape; if MidnightLedger's constructor
        // signature ever changes, this factory needs an update too
        // (compile-time error in the SDK module would surface that).
        val ctor = MidnightLedger::class.java.getDeclaredConstructor(Map::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(fields)
    }
}
