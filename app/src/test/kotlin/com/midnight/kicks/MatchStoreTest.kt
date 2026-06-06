package com.midnight.kicks

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioral coverage for [MatchStore].
 *
 * Locks down the consolidation contract — one type holds everything
 * the prior `KicksSessionStore` + `MatchVault` split owned, indexed
 * by contract address, durable via `commit()`, with a cloud-backup
 * round-trip. Catches the bug classes the split caused:
 *  - Witnesses persisted but session lost on apply()-vs-SIGKILL race
 *  - Multi-match shape breaking when a second match save kicks the
 *    first one off the index
 *  - Cloud snapshot/restore losing fields silently
 */
@RunWith(RobolectricTestRunner::class)
// Pin to SDK 34 — Kicks targets 36 (matches the production AGP/SDK we
// ship against), but Robolectric 4.14.1 caps at 35 and won't run our
// tests with a higher target. 34 is a stable plateau for the Keystore
// + SharedPreferences shadows we depend on; bump when Robolectric
// catches up to 36.
@Config(sdk = [34])
class MatchStoreTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var store: MatchStore

    @Before
    fun setup() {
        // Plain (un-encrypted) SharedPreferences for the test. The
        // production constructor wraps EncryptedSharedPreferences over
        // a Keystore master key; Robolectric doesn't shadow
        // AndroidKeyStore, so tests exercise the Store logic with
        // plain prefs. The encryption is a deployment concern that
        // doesn't change the Store's behavioral contract.
        val context: Context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences("test_match_store", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = MatchStore(prefs)
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `save then load returns the same match`() {
        val match = fixtureMatch(address = "aaaa", role = Player.P1)
        store.save(match)

        val loaded = store.load("aaaa")
        assertNotNull(loaded)
        assertEquals(match.address, loaded!!.address)
        assertEquals(match.role, loaded.role)
        assertEquals(match.deadline, loaded.deadline)
        assertArrayEquals(match.secretKey, loaded.secretKey)
    }

    @Test
    fun `load returns null for unknown address`() {
        store.save(fixtureMatch(address = "aaaa"))
        assertNull(store.load("bbbb"))
    }

    @Test
    fun `loadAll returns every saved match`() {
        store.save(fixtureMatch(address = "aaaa", role = Player.P1))
        store.save(fixtureMatch(address = "bbbb", role = Player.P2))
        store.save(fixtureMatch(address = "cccc", role = Player.P1))

        val all = store.loadAll()
        assertEquals(3, all.size)
        // Order isn't part of the contract — assert set membership.
        val addresses = all.map { it.address }.toSet()
        assertEquals(setOf("aaaa", "bbbb", "cccc"), addresses)
    }

    @Test
    fun `saving the same address twice overwrites without duplicating index`() {
        // The original split would have left a partial-write situation
        // possible here. The unified index keeps it sane.
        store.save(fixtureMatch(address = "aaaa", role = Player.P1, deadline = 100L))
        store.save(fixtureMatch(address = "aaaa", role = Player.P1, deadline = 200L))

        val all = store.loadAll()
        assertEquals(1, all.size)
        assertEquals(200L, all.single().deadline)
    }

    @Test
    fun `delete removes the match and shrinks loadAll`() {
        store.save(fixtureMatch(address = "aaaa"))
        store.save(fixtureMatch(address = "bbbb"))
        store.delete("aaaa")

        assertNull(store.load("aaaa"))
        assertNotNull(store.load("bbbb"))
        assertEquals(1, store.loadAll().size)
    }

    @Test
    fun `clear removes everything`() {
        store.save(fixtureMatch(address = "aaaa"))
        store.save(fixtureMatch(address = "bbbb"))
        store.clear()

        assertEquals(emptyList<MatchStore.Match>(), store.loadAll())
    }

    @Test
    fun `regulation witnesses round-trip through save and load`() {
        val match = fixtureMatch(address = "aaaa").copy(
            regulation = MatchStore.RegulationWitnesses(
                shoots = intArrayOf(0, 1, 2, 1, 0),
                keeps = intArrayOf(2, 2, 1, 0, 1),
                nonce = ByteArray(32) { 0xAB.toByte() },
            ),
        )
        store.save(match)

        val loaded = store.load("aaaa")!!
        // Without this round-trip working, a kill between commit and
        // reveal would lose the witnesses and the user's stake gets
        // stranded behind a commitment we can't reopen.
        assertNotNull(loaded.regulation)
        assertArrayEquals(intArrayOf(0, 1, 2, 1, 0), loaded.regulation!!.shoots)
        assertArrayEquals(intArrayOf(2, 2, 1, 0, 1), loaded.regulation.keeps)
        assertArrayEquals(ByteArray(32) { 0xAB.toByte() }, loaded.regulation.nonce)
    }

    @Test
    fun `sd witnesses round-trip through save and load`() {
        val match = fixtureMatch(address = "aaaa").copy(
            sd = MatchStore.SdWitnesses(
                round = 3,
                shoot = 1,
                keep = 2,
                nonce = ByteArray(32) { 0xCD.toByte() },
            ),
        )
        store.save(match)

        val loaded = store.load("aaaa")!!
        assertNotNull(loaded.sd)
        assertEquals(3, loaded.sd!!.round)
        assertEquals(1, loaded.sd.shoot)
        assertEquals(2, loaded.sd.keep)
        assertArrayEquals(ByteArray(32) { 0xCD.toByte() }, loaded.sd.nonce)
    }

    // ── Cloud backup round-trip ──

    @Test
    fun `snapshotBytes round-trips every match via restoreFromBytes`() {
        val matches = listOf(
            fixtureMatch(address = "aaaa", role = Player.P1, deadline = 100L).copy(
                regulation = MatchStore.RegulationWitnesses(
                    shoots = intArrayOf(0, 1, 2, 1, 0),
                    keeps = intArrayOf(2, 2, 1, 0, 1),
                    nonce = ByteArray(32) { 0x11.toByte() },
                ),
            ),
            fixtureMatch(address = "bbbb", role = Player.P2, deadline = 200L).copy(
                sd = MatchStore.SdWitnesses(
                    round = 2,
                    shoot = 0,
                    keep = 1,
                    nonce = ByteArray(32) { 0x22.toByte() },
                ),
            ),
        )
        matches.forEach { store.save(it) }

        val blob = store.snapshotBytes()
        // Round-trip via a new store handle so we exercise the restore
        // path against an empty target (mimics a sigil-restore on a
        // fresh device).
        store.clear()
        assertEquals(0, store.loadAll().size)

        store.restoreFromBytes(blob)
        val restored = store.loadAll()
        assertEquals(2, restored.size)

        val a = restored.single { it.address == "aaaa" }
        assertEquals(Player.P1, a.role)
        assertEquals(100L, a.deadline)
        assertNotNull(a.regulation)
        assertArrayEquals(intArrayOf(0, 1, 2, 1, 0), a.regulation!!.shoots)

        val b = restored.single { it.address == "bbbb" }
        assertEquals(Player.P2, b.role)
        assertEquals(200L, b.deadline)
        assertNotNull(b.sd)
        assertEquals(2, b.sd!!.round)
    }

    @Test
    fun `restoreFromBytes silently drops a corrupt blob`() {
        // We pre-populate so the test can prove restoreFromBytes
        // doesn't wipe local state when the cloud blob is unusable
        // (defensive: an old app version pulling a future-schema blob
        // shouldn't blow away the user's already-good local store).
        store.save(fixtureMatch(address = "aaaa"))

        val garbage = "not-actually-json".toByteArray()
        store.restoreFromBytes(garbage)

        // Local match is intact — the corrupt blob did not overwrite.
        assertNotNull(store.load("aaaa"))
    }

    @Test
    fun `restoreFromBytes preserves local store when every record in the blob is malformed`() {
        // The defensive-restore contract: if the blob is structurally
        // valid (parseable JSON, correct schema version) but every
        // record fails decode, we don't clear local state. The
        // alternative — clearing then trying to save nothing — would
        // lose the user's working matches on a malformed cloud blob.
        store.save(fixtureMatch(address = "aaaa"))

        // Envelope with valid schema version + an array of one
        // junk record (missing required fields).
        val blob = """{"v":1,"matches":[{"address":"only-this-field"}]}""".toByteArray()
        store.restoreFromBytes(blob)

        assertNotNull("local store must survive an all-malformed-records restore", store.load("aaaa"))
    }

    @Test
    fun `Match equals and hashCode use content equality on ByteArray and IntArray`() {
        // This is the test that would have caught the data-class trap.
        // Two Matches with identical *content* but separately
        // allocated arrays should be equal and share hashCode. The
        // auto-generated data-class equals would have returned false
        // here (reference equality on the array fields).
        val a = MatchStore.Match(
            address = "aaaa",
            role = Player.P1,
            deadline = 100L,
            secretKey = ByteArray(32) { 0xAB.toByte() },
            regulation = MatchStore.RegulationWitnesses(
                shoots = intArrayOf(0, 1, 2, 1, 0),
                keeps = intArrayOf(2, 2, 1, 0, 1),
                nonce = ByteArray(32) { 0xCD.toByte() },
            ),
            sd = MatchStore.SdWitnesses(
                round = 3,
                shoot = 1,
                keep = 2,
                nonce = ByteArray(32) { 0xEF.toByte() },
            ),
        )
        val b = MatchStore.Match(
            address = "aaaa",
            role = Player.P1,
            deadline = 100L,
            secretKey = ByteArray(32) { 0xAB.toByte() },
            regulation = MatchStore.RegulationWitnesses(
                shoots = intArrayOf(0, 1, 2, 1, 0),
                keeps = intArrayOf(2, 2, 1, 0, 1),
                nonce = ByteArray(32) { 0xCD.toByte() },
            ),
            sd = MatchStore.SdWitnesses(
                round = 3,
                shoot = 1,
                keep = 2,
                nonce = ByteArray(32) { 0xEF.toByte() },
            ),
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        // And the inner witness types — same trap, same coverage.
        assertEquals(a.regulation, b.regulation)
        assertEquals(a.sd, b.sd)
    }

    @Test
    fun `Match equals returns false when secretKey differs`() {
        // Negative-case for the equals/hashCode contract — confirm we
        // didn't accidentally write an equals that returns true for
        // every Match (which would still pass the positive case
        // above by coincidence).
        val a = fixtureMatch(address = "aaaa").copy(secretKey = ByteArray(32) { 0x11.toByte() })
        val b = fixtureMatch(address = "aaaa").copy(secretKey = ByteArray(32) { 0x22.toByte() })
        assertEquals(false, a == b)
    }

    @Test
    fun `P2 match round-trips with role serialized correctly`() {
        // Every other test defaults role to P1; this one locks down
        // the P2 path so a future serialization regression doesn't
        // silently flip every join-side resume into the create-side
        // routing.
        val p2Match = MatchStore.Match(
            address = "bbbb",
            role = Player.P2,
            deadline = 200L,
            secretKey = ByteArray(32) { 0x77.toByte() },
        )
        store.save(p2Match)
        val loaded = store.load("bbbb")!!
        assertEquals(Player.P2, loaded.role)
        assertEquals(p2Match, loaded)
    }

    // ── PvAI: the AI opponent's persisted side (resumable on-chain PvAI) ──

    @Test
    fun `save then load preserves the AI slot (key + regulation + sd)`() {
        val ai = MatchStore.AiState(
            secretKey = ByteArray(32) { 0xA1.toByte() },
            regulation = MatchStore.RegulationWitnesses(
                shoots = intArrayOf(0, 1, 2, 0, 1),
                keeps = intArrayOf(2, 1, 0, 2, 1),
                nonce = ByteArray(16) { 0xB2.toByte() },
            ),
            sd = MatchStore.SdWitnesses(round = 2, shoot = 1, keep = 0, nonce = ByteArray(16) { 0xC3.toByte() }),
        )
        val match = fixtureMatch(address = "pvai", role = Player.P1).copy(ai = ai)
        store.save(match)

        val loaded = store.load("pvai")
        assertNotNull(loaded)
        assertEquals("whole record (incl. ai) round-trips by value", match, loaded)
        assertNotNull(loaded!!.ai)
        assertArrayEquals(ai.secretKey, loaded.ai!!.secretKey)
        assertArrayEquals(ai.regulation!!.shoots, loaded.ai!!.regulation!!.shoots)
        assertArrayEquals(ai.regulation!!.nonce, loaded.ai!!.regulation!!.nonce)
        assertEquals(2, loaded.ai!!.sd!!.round)
    }

    @Test
    fun `cloud snapshot round-trip preserves the AI slot`() {
        val ai = MatchStore.AiState(
            secretKey = ByteArray(32) { 0x7A.toByte() },
            regulation = MatchStore.RegulationWitnesses(
                shoots = intArrayOf(1, 1, 1, 1, 1),
                keeps = intArrayOf(0, 0, 0, 0, 0),
                nonce = ByteArray(16) { 0x9C.toByte() },
            ),
        )
        store.save(fixtureMatch(address = "pvai-cloud", role = Player.P1).copy(ai = ai))

        val blob = store.snapshotBytes()
        store.clear()
        assertNull("cleared", store.load("pvai-cloud"))
        store.restoreFromBytes(blob)

        val restored = store.load("pvai-cloud")
        assertNotNull(restored)
        assertNotNull("ai survives the cloud blob", restored!!.ai)
        assertArrayEquals(ai.secretKey, restored.ai!!.secretKey)
        assertNull("no sd was set", restored.ai!!.sd)
    }

    @Test
    fun `a PvP record (no AI slot) decodes with ai = null`() {
        // Backward-compat: the ai field is additive + optional, so an existing
        // PvP record (and any pre-PvAI-feature record) loads with ai == null.
        store.save(fixtureMatch(address = "pvp", role = Player.P2))
        assertNull(store.load("pvp")!!.ai)
    }

    // ── Helpers ──

    private fun fixtureMatch(
        address: String,
        role: Player = Player.P1,
        deadline: Long = 1_700_000_000L,
    ): MatchStore.Match = MatchStore.Match(
        address = address,
        role = role,
        deadline = deadline,
        secretKey = ByteArray(32) { 0xEF.toByte() },
    )
}
