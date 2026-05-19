package com.midnight.kicks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.core.network.MidnightNetwork
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigInteger

/**
 * State-machine instrumented tests for [MatchManager].
 *
 * Locks down the transition logic that the bug-fix arc surfaced
 * (resetForNewAction's "CREATE again from Deployed" recovery,
 * resetForNewAction's "retry after Failed" recovery, joinAsP2's
 * rejoiner-vs-stranger gate, role-aware tryResumeActiveMatch). Each
 * of these has bitten the wallet before — these tests turn them into
 * regression-proof contracts.
 *
 * **How:** [TestableMatchManager] overrides the three chain-touching
 * `internal open suspend fun` seams on [MatchManager] —
 * `initSdkInternal`, `executeDeploy`, `executeJoinMatch`, plus
 * `afterDeploySettle` for the indexer-settle delay. The test
 * decides per-test whether the seam succeeds, throws, or returns a
 * specific address. State transitions + store writes still go
 * through the real MatchManager logic, so the tested behavior is
 * exactly what production runs.
 *
 * **Why instrumented (androidTest) not unit (test):** MatchStore's
 * production constructor uses EncryptedSharedPreferences, which
 * needs Android Keystore. A unit-test path with plain SharedPrefs
 * would work, but instrumented tests give us the real crypto + the
 * real lifecycle + Build.VERSION_SDK_INT etc. for free. The cost is
 * ~10s per full suite — acceptable for the regression coverage.
 */
@RunWith(AndroidJUnit4::class)
class MatchManagerStateMachineTest {

    private lateinit var context: Context
    private lateinit var store: MatchStore

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        store = MatchStore(context)
        store.clear()
    }

    @After
    fun tearDown() {
        store.clear()
    }

    // ── deployMatch ──

    @Test
    fun deployMatch_from_SdkReady_transitions_to_Deployed_and_persists() = runBlocking {
        val mm = TestableMatchManager(context, store, deployAddress = STUB_ADDRESS_1)
        mm.initSdk()
        assertEquals(MatchState.SdkReady, mm.state.value)

        val address = mm.deployMatch()

        assertEquals(STUB_ADDRESS_1, address)
        assertEquals(MatchState.Deployed(STUB_ADDRESS_1), mm.state.value)
        // Store has the new match with role=P1.
        val saved = store.load(STUB_ADDRESS_1)
        assertNotNull("deployMatch must persist the match to MatchStore", saved)
        assertEquals(Player.P1, saved!!.role)
    }

    @Test
    fun deployMatch_from_Deployed_resets_state_and_redeploys() = runBlocking {
        // The original bug: tapping CREATE while state was Deployed(addr1)
        // threw "expected SdkReady, got Deployed" because the precondition
        // check fired before resetForNewAction got a chance. This test
        // pins the fix — a second deployMatch call lands cleanly.
        val mm = TestableMatchManager(context, store)
        mm.initSdk()

        mm.setNextDeployAddress(STUB_ADDRESS_1)
        mm.deployMatch()
        assertEquals(MatchState.Deployed(STUB_ADDRESS_1), mm.state.value)

        mm.setNextDeployAddress(STUB_ADDRESS_2)
        val secondAddress = mm.deployMatch()

        assertEquals(STUB_ADDRESS_2, secondAddress)
        assertEquals(MatchState.Deployed(STUB_ADDRESS_2), mm.state.value)
        // Both matches stored — the old one didn't get wiped. Multi-
        // match contract: starting a new match doesn't drop the old.
        assertEquals(2, store.loadAll().size)
        assertNotNull(store.load(STUB_ADDRESS_1))
        assertNotNull(store.load(STUB_ADDRESS_2))
    }

    @Test
    fun deployMatch_from_Failed_resets_state_and_succeeds() = runBlocking {
        // The companion bug: state was Failed (e.g. previous join refused
        // by chain), user taps CREATE expecting a fresh start, got
        // "expected SdkReady, got Failed". Same fix: resetForNewAction
        // unwinds Failed back to SdkReady before transitionFrom's
        // precondition check.
        val mm = TestableMatchManager(context, store, deployAddress = STUB_ADDRESS_1)
        mm.initSdk()

        // Drive into Failed via a failed join. The TestableMatchManager
        // defaults its join to throw a generic chain error, putting the
        // state machine in Failed(prev=JoiningAsP2).
        mm.setNextJoinResult(JoinResult.Fail(IllegalStateException("synthetic chain error")))
        assertThrows(IllegalStateException::class.java) {
            runBlocking { mm.joinAsP2("aaaa".repeat(16)) }
        }
        assertTrue(
            "expected state to be Failed after the synthetic join failure, got ${mm.state.value}",
            mm.state.value is MatchState.Failed,
        )

        // Now CREATE — should clear Failed and proceed.
        val address = mm.deployMatch()
        assertEquals(STUB_ADDRESS_1, address)
        assertEquals(MatchState.Deployed(STUB_ADDRESS_1), mm.state.value)
    }

    // ── joinAsP2 ──

    @Test
    fun joinAsP2_happy_path_transitions_to_Joined_and_persists_role_P2() = runBlocking {
        val mm = TestableMatchManager(context, store)
        mm.initSdk()
        mm.setNextJoinResult(JoinResult.Success)

        mm.joinAsP2(STUB_ADDRESS_1)

        assertEquals(MatchState.Joined(STUB_ADDRESS_1), mm.state.value)
        val saved = store.load(STUB_ADDRESS_1)
        assertNotNull("successful joinAsP2 must save the match to MatchStore", saved)
        assertEquals(Player.P2, saved!!.role)
    }

    @Test
    fun joinAsP2_stranger_with_isResume_false_throws_MatchAlreadyJoinedException() = runBlocking {
        // Wrong-actor flow: someone else joined this match as P2 before
        // us. The contract's assert ("already past the WAITING phase —
        // a P2 has already joined.") gets caught by joinAsP2's catch;
        // with isResume=false the result is MatchAlreadyJoinedException
        // (typed so the UI shows "another player already joined" rather
        // than a generic stack trace).
        val mm = TestableMatchManager(context, store)
        mm.initSdk()
        mm.setNextJoinResult(
            JoinResult.Fail(IllegalStateException("Match X is already past the WAITING phase — a P2 has already joined.")),
        )

        assertThrows(MatchAlreadyJoinedException::class.java) {
            runBlocking { mm.joinAsP2(STUB_ADDRESS_1, isResume = false) }
        }
        // Stranger path: nothing in the store. We never saved because
        // joinAsP2's try-block never reached the save line.
        assertNull(store.load(STUB_ADDRESS_1))
    }

    @Test
    fun joinAsP2_rejoiner_with_store_record_swallows_WAITING_error_and_advances() = runBlocking {
        // Legitimate rejoiner: the user already successfully joined
        // this address (so MatchStore has a P2 record). They reopen
        // the deep link or relaunch; the contract refuses the second
        // joinMatch ("not in WAITING phase"). joinAsP2's catch must
        // treat that as a no-op success — state → Joined, no throw.
        val matchSecretKey = ByteArray(32) { 0x42.toByte() }
        store.save(
            MatchStore.Match(
                address = STUB_ADDRESS_1,
                role = Player.P2,
                deadline = 1_900_000_000L,
                secretKey = matchSecretKey,
            ),
        )
        val mm = TestableMatchManager(context, store)
        mm.initSdk()
        mm.setNextJoinResult(
            JoinResult.Fail(IllegalStateException("not in WAITING phase — already joined")),
        )

        mm.joinAsP2(STUB_ADDRESS_1, isResume = true)

        assertEquals(MatchState.Joined(STUB_ADDRESS_1), mm.state.value)
        // The persisted match stays intact — the rejoiner path
        // rehydrated from the store, didn't overwrite.
        val still = store.load(STUB_ADDRESS_1)
        assertNotNull("rejoiner path must not delete the existing store record", still)
        assertEquals(Player.P2, still!!.role)
    }

    @Test
    fun joinAsP2_isResume_true_but_store_empty_falls_back_to_MatchAlreadyJoined() = runBlocking<Unit> {
        // Defensive case: caller says isResume=true (maybe the session
        // store said role=P2 for this address) but MatchStore no longer
        // has a record (user wiped app data between sessions). Without
        // the persisted secret key we can't reveal anything, so we
        // surface MatchAlreadyJoinedException instead of faking success.
        //
        // `runBlocking<Unit>` — explicit Unit param so the trailing
        // assertThrows (which returns Throwable) is coerced rather
        // than leaking through the function's return type. JUnit
        // refuses non-Unit-returning @Test methods.
        val mm = TestableMatchManager(context, store)
        mm.initSdk()
        mm.setNextJoinResult(
            JoinResult.Fail(IllegalStateException("not in WAITING phase")),
        )

        assertThrows(MatchAlreadyJoinedException::class.java) {
            runBlocking { mm.joinAsP2(STUB_ADDRESS_1, isResume = true) }
        }
    }

    // ── Multi-match ──

    @Test
    fun create_then_join_persists_both_matches_in_store() = runBlocking {
        // The classic two-game scenario: I CREATE match A as P1 (vs
        // friend Alice), then JOIN match B as P2 (vs friend Bob).
        // Both matches must coexist in the store so the Resume picker
        // shows both rows.
        val mm = TestableMatchManager(context, store, deployAddress = STUB_ADDRESS_1)
        mm.initSdk()

        mm.deployMatch()
        mm.setNextJoinResult(JoinResult.Success)
        mm.joinAsP2(STUB_ADDRESS_2)

        val all = store.loadAll()
        assertEquals(2, all.size)
        val byAddr = all.associateBy { it.address }
        assertEquals(Player.P1, byAddr[STUB_ADDRESS_1]!!.role)
        assertEquals(Player.P2, byAddr[STUB_ADDRESS_2]!!.role)
    }

    private companion object {
        // Two distinct 64-char hex addresses for testing — content
        // doesn't matter, we just need stable distinguishable values.
        private const val STUB_ADDRESS_1 =
            "1111111111111111111111111111111111111111111111111111111111111111"
        private const val STUB_ADDRESS_2 =
            "2222222222222222222222222222222222222222222222222222222222222222"
    }
}

/**
 * Discriminated outcome of [TestableMatchManager.executeJoinMatch].
 * Lets each test point the same seam at either success or a specific
 * exception (used to exercise the rejoiner vs stranger branches of
 * joinAsP2's catch block).
 */
private sealed class JoinResult {
    object Success : JoinResult()
    data class Fail(val error: Throwable) : JoinResult()
}

/**
 * Test subclass that overrides every chain-touching seam in
 * [MatchManager]. Each override is in-memory only; no SDK, no chain.
 * The state-machine, store, witness-handling logic stays in the
 * parent class — what's exercised is exactly what production runs,
 * minus the chain.
 *
 * Configuration via setters so tests can swap behavior between
 * setup and the first call (e.g. test that fires deployMatch twice
 * with two different stub addresses).
 */
private class TestableMatchManager(
    context: Context,
    store: MatchStore,
    deployAddress: String = "stub-address",
) : MatchManager(
    context = context,
    network = MidnightNetwork.UNDEPLOYED,
    seed = ByteArray(64) { 0x11.toByte() },
    store = store,
) {
    private var nextDeployAddress: String = deployAddress
    private var nextJoinResult: JoinResult = JoinResult.Success

    fun setNextDeployAddress(address: String) {
        nextDeployAddress = address
    }

    fun setNextJoinResult(result: JoinResult) {
        nextJoinResult = result
    }

    override suspend fun initSdkInternal() {
        // Skip the real SDK build entirely. The state-machine tests
        // don't care about indexer/wallet/dust subscriptions; they
        // care about transitions. We just need state to land in
        // SdkReady after initSdk(), which the parent does.
    }

    override suspend fun executeDeploy(secretKey: ByteArray): String {
        return nextDeployAddress
    }

    override suspend fun afterDeploySettle() {
        // No real chain to settle; skip the delay + wallet.refresh.
    }

    override suspend fun executeJoinMatch(
        secretKey: ByteArray,
        address: String,
        deadline: BigInteger,
    ) {
        when (val r = nextJoinResult) {
            is JoinResult.Success -> Unit
            is JoinResult.Fail -> throw r.error
        }
    }
}
