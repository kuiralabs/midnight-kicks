package com.midnight.kicks

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ContractStateSnapshot] parsing (V3 layout).
 *
 * V3 stores its ledger in two groups (8 cells + 15 cells = 23 cells flat).
 * Each `Vector<5, Uint<8>>` occupies one cell whose hex is the 5
 * concatenated element bytes (10 hex chars).
 *
 * These tests guard the cell-index map against drift from
 * `penalty-contract.js`. If the .compact field order changes, exactly
 * these tests will start failing — far better than the silent "Match
 * complete" with garbage scores you'd see if the parser kept rolling.
 */
class ContractStateSnapshotTest {

    @Test
    fun `parse returns null for a too-short array`() {
        val short = JSONArray().apply { put(numberCell(0)) }
        assertNull(ContractStateSnapshot.parse(short))
    }

    @Test
    fun `parse returns null for an empty array`() {
        assertNull(ContractStateSnapshot.parse(JSONArray()))
    }

    @Test
    fun `parse returns null when neither flat nor nested layout matches`() {
        val malformed = JSONArray()
            .put(numberCell(0))
            .put(numberCell(1))
        assertNull(ContractStateSnapshot.parse(malformed))
    }

    // ── Split-storage on-chain shape: 8 cells in group 0 + 15 in group 1 ─

    @Test
    fun `parse handles real split-storage layout`() {
        val state = defaultStateArray()
        val snap = ContractStateSnapshot.parse(state)
        requireNotNull(snap)
        assertEquals(0, snap.phase)
        assertFalse(snap.p1Committed)
        assertFalse(snap.p2Committed)
        assertEquals(0, snap.p1Score)
        assertEquals(0, snap.p2Score)
        assertEquals(0, snap.sdRound)
    }

    @Test
    fun `parse fills empty vectors with zeros (fresh post-deploy state)`() {
        val snap = ContractStateSnapshot.parse(defaultStateArray())
        requireNotNull(snap)
        assertEquals(5, snap.p1Shoots.size)
        assertEquals(5, snap.p1Keeps.size)
        assertEquals(5, snap.p2Shoots.size)
        assertEquals(5, snap.p2Keeps.size)
        assertArrayEquals(IntArray(5), snap.p1Shoots)
        assertArrayEquals(IntArray(5), snap.p2Keeps)
    }

    @Test
    fun `parse reads player1 from group 0 cell 1`() {
        val player1Hex = "e8fde5e25c2d4c589f181c10042304f1e9badca11baabafeac4d53812a7b7c07"
        val state = defaultStateArray().apply {
            // group 0 is state[0]; player1 is at group 0 index 1
            (get(0) as JSONArray).put(1, hexCell(player1Hex))
        }
        val snap = ContractStateSnapshot.parse(state)
        requireNotNull(snap)
        assertEquals(player1Hex.length / 2, snap.player1.size)
        assertEquals(0xe8.toByte(), snap.player1[0])
    }

    @Test
    fun `parse decodes p1Committed when hex is 01`() {
        val state = defaultStateArray().apply {
            // p1Committed is at group 0 index 5
            (get(0) as JSONArray).put(5, hexCell("01"))
        }
        val snap = ContractStateSnapshot.parse(state)
        requireNotNull(snap)
        assertTrue(snap.p1Committed)
        assertFalse(snap.p2Committed)
    }

    @Test
    fun `parse decodes p2Revealed from group 1 cell 8`() {
        val state = defaultStateArray().apply {
            (get(1) as JSONArray).put(8, hexCell("01"))
        }
        val snap = ContractStateSnapshot.parse(state)
        requireNotNull(snap)
        assertTrue(snap.p2Revealed)
        assertFalse(snap.p1Revealed)
    }

    // ── Vector<5, Uint<8>> decoding — the V3 headline change ────────────

    @Test
    fun `parse decodes p1Shoots from a single 5-byte hex cell`() {
        // Vector<5, Uint<8>> serializes as 5 concatenated bytes; the
        // ledger holds them in one cell at group 0 index 7.
        val shoots = intArrayOf(0, 1, 2, 1, 0)
        val state = defaultStateArray().apply {
            (get(0) as JSONArray).put(7, hexCell("0001020100"))
        }
        val snap = ContractStateSnapshot.parse(state)
        requireNotNull(snap)
        assertArrayEquals(shoots, snap.p1Shoots)
        // Other vectors should still be zeroed.
        assertArrayEquals(IntArray(5), snap.p1Keeps)
        assertArrayEquals(IntArray(5), snap.p2Shoots)
        assertArrayEquals(IntArray(5), snap.p2Keeps)
    }

    @Test
    fun `parse decodes p1Keeps p2Shoots p2Keeps from group 1 cells 0-2`() {
        val keeps   = intArrayOf(2, 2, 0, 1, 2)
        val p2Sh    = intArrayOf(0, 2, 0, 2, 0)
        val p2K     = intArrayOf(1, 1, 1, 1, 1)
        val state = defaultStateArray().apply {
            (get(1) as JSONArray).put(0, hexCell("0202000102"))
            (get(1) as JSONArray).put(1, hexCell("0002000200"))
            (get(1) as JSONArray).put(2, hexCell("0101010101"))
        }
        val snap = ContractStateSnapshot.parse(state)
        requireNotNull(snap)
        assertArrayEquals(keeps,  snap.p1Keeps)
        assertArrayEquals(p2Sh,   snap.p2Shoots)
        assertArrayEquals(p2K,    snap.p2Keeps)
        assertArrayEquals(IntArray(5), snap.p1Shoots)
    }

    // ── SD pick scalars (group 1 cells 3-6) ─────────────────────────────

    @Test
    fun `parse decodes SD shoot and keep scalars`() {
        val state = defaultStateArray().apply {
            (get(1) as JSONArray).put(3, hexCell("01")) // p1SdShoot = 1
            (get(1) as JSONArray).put(4, hexCell("02")) // p1SdKeep  = 2
            (get(1) as JSONArray).put(5, hexCell("00")) // p2SdShoot = 0
            (get(1) as JSONArray).put(6, hexCell("01")) // p2SdKeep  = 1
        }
        val snap = ContractStateSnapshot.parse(state)
        requireNotNull(snap)
        assertEquals(1, snap.p1SdShoot)
        assertEquals(2, snap.p1SdKeep)
        assertEquals(0, snap.p2SdShoot)
        assertEquals(1, snap.p2SdKeep)
    }

    @Test
    fun `parse decodes scores from group 1 cells 9-10`() {
        val state = defaultStateArray().apply {
            (get(1) as JSONArray).put(9,  hexCell("03"))   // p1Score = 3
            (get(1) as JSONArray).put(10, hexCell("02"))   // p2Score = 2
        }
        val snap = ContractStateSnapshot.parse(state)
        requireNotNull(snap)
        assertEquals(3, snap.p1Score)
        assertEquals(2, snap.p2Score)
    }

    @Test
    fun `parse decodes winner bytes from group 1 cell 11`() {
        val winnerHex = "ab".repeat(32)
        val state = defaultStateArray().apply {
            (get(1) as JSONArray).put(11, hexCell(winnerHex))
        }
        val snap = ContractStateSnapshot.parse(state)
        requireNotNull(snap)
        assertEquals(32, snap.winner.size)
        assertEquals(0xab.toByte(), snap.winner[0])
        assertEquals(0xab.toByte(), snap.winner[31])
    }

    @Test
    fun `parse decodes deadline as little-endian Uint64`() {
        val state = defaultStateArray().apply {
            // deadline at group 1 index 13; 0x12345678 = 305_419_896 LE
            (get(1) as JSONArray).put(13, hexCell("78563412"))
        }
        val snap = ContractStateSnapshot.parse(state)
        requireNotNull(snap)
        assertEquals(0x12345678L, snap.deadline)
    }

    @Test
    fun `parse decodes sdRound from group 1 cell 14`() {
        val state = defaultStateArray().apply {
            (get(1) as JSONArray).put(14, hexCell("02")) // sdRound = 2
        }
        val snap = ContractStateSnapshot.parse(state)
        requireNotNull(snap)
        assertEquals(2, snap.sdRound)
    }

    // ── Derived helpers ─────────────────────────────────────────────────

    @Test
    fun `bothCommitted is true only when both flags are set`() {
        val state = defaultStateArray().apply {
            (get(0) as JSONArray).put(5, hexCell("01")) // p1Committed
            (get(0) as JSONArray).put(6, hexCell("01")) // p2Committed
        }
        val snap = ContractStateSnapshot.parse(state)!!
        assertTrue(snap.bothCommitted)
    }

    @Test
    fun `matchJoined is true when player2 has any non-zero byte`() {
        val state = defaultStateArray().apply {
            (get(0) as JSONArray).put(2, hexCell("01" + "00".repeat(31)))
        }
        val snap = ContractStateSnapshot.parse(state)!!
        assertTrue(snap.matchJoined)
    }

    @Test
    fun `matchJoined is false when player2 is all zeros`() {
        val snap = ContractStateSnapshot.parse(defaultStateArray())!!
        assertFalse(snap.matchJoined)
    }

    @Test
    fun `equals returns true for two identical snapshots`() {
        val a = ContractStateSnapshot.parse(defaultStateArray())!!
        val b = ContractStateSnapshot.parse(defaultStateArray())!!
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `equals returns false when one boolean flag differs`() {
        val a = ContractStateSnapshot.parse(defaultStateArray())!!
        val b = ContractStateSnapshot.parse(defaultStateArray().apply {
            (get(0) as JSONArray).put(5, hexCell("01"))
        })!!
        assertNotEquals(a, b)
    }

    @Test
    fun `equals returns false when a Vector differs`() {
        val a = ContractStateSnapshot.parse(defaultStateArray())!!
        val b = ContractStateSnapshot.parse(defaultStateArray().apply {
            (get(0) as JSONArray).put(7, hexCell("0102000201"))
        })!!
        assertNotEquals(a, b)
    }

    @Test
    fun `summary string mentions all critical flags`() {
        val state = defaultStateArray().apply {
            (get(0) as JSONArray).put(5, hexCell("01"))  // p1Committed
            (get(1) as JSONArray).put(9, hexCell("02"))  // p1Score
            (get(1) as JSONArray).put(10, hexCell("01")) // p2Score
        }
        val snap = ContractStateSnapshot.parse(state)!!
        val s = snap.summary()
        assertTrue(s, s.contains("p1Committed=true"))
        assertTrue(s, s.contains("score=2-1"))
        assertTrue(s, s.contains("phase=0"))
    }

    // ── Test helpers ────────────────────────────────────────────────────

    /**
     * Build a fresh post-deploy state array — 2 groups, all cells default
     * to empty hex (the shape MidnightConfig.queryState actually returns
     * before any tx lands). Tests override specific group/index cells.
     */
    private fun defaultStateArray(): JSONArray {
        val group0 = JSONArray().apply { repeat(GROUP0_CELLS) { put(hexCell("")) } }
        val group1 = JSONArray().apply { repeat(GROUP1_CELLS) { put(hexCell("")) } }
        return JSONArray().put(group0).put(group1)
    }

    private fun numberCell(value: Number): JSONObject =
        JSONObject().put("type", "cell").put("number", value)

    private fun hexCell(hex: String): JSONObject =
        JSONObject().put("type", "cell").put("hex", hex)

    companion object {
        private const val GROUP0_CELLS = 8
        private const val GROUP1_CELLS = 15
    }
}
