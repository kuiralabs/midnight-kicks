package com.midnight.kicks

import android.content.Context
import android.util.Log
import com.midnight.kuira.core.compact.ContractCallStage
import com.midnight.kuira.core.compact.MidnightContract
import com.midnight.kuira.core.compact.WitnessResult
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.sdk.MidnightSdk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.SecureRandom

/**
 * Drives a penalty match through the protocol as a discrete state machine.
 *
 * The state machine — [MatchState] — is the source of truth. Each public
 * suspend method represents ONE on-chain transaction (or one logical step),
 * preconditions on the current state, advances to an in-progress state,
 * does the work, then either advances to the success state or transitions
 * to [MatchState.Failed]. Callers observe [state] (a StateFlow) to render
 * stage-specific UX, snapshot to BlockStore, or trigger the next step.
 *
 * Two ways to run a match:
 *
 * 1. **Step-by-step** — call individual transitions in order. Use this for
 *    real PvP (StatePoller advances P2 steps when on-chain events fire) and
 *    for any UX that wants between-step pauses or cancel/retry surfaces.
 *
 *        deployMatch() → aiJoin() → submitP1Choices(c) → submitP2Choices(c)
 *        → revealP1() → revealP2()
 *
 * 2. **Orchestrated** — call [playAgainstAi]. Hands in P1's choices, AI
 *    auto-generates P2's, all transitions chain. Less control, but the
 *    canonical "play one match against an AI" entry point for demos.
 *
 * For now P2 is an AI on-device; the [submitP2Choices] / [revealP2] split
 * exists so a future PvP code path can swap "AI auto-decides" for "wait
 * for the friend's transaction on chain".
 */
class MatchManager(
    private val context: Context,
    private val network: MidnightNetwork,
    private val seed: ByteArray,
) {
    private val _state = MutableStateFlow<MatchState>(MatchState.Idle)
    /** Observable state for UI / BlockStore / StatePoller. */
    val state: StateFlow<MatchState> = _state.asStateFlow()

    private var sdk: MidnightSdk? = null

    // P1 (local human) identity
    private val p1SecretKey = ByteArray(SECRET_KEY_BYTES).also { SecureRandom().nextBytes(it) }

    // P2 identity. Today this is an on-device AI; tomorrow it's the friend
    // on the other phone (their key, not stored here at all).
    private val p2SecretKey = ByteArray(SECRET_KEY_BYTES).also { SecureRandom().nextBytes(it) }

    // Choices + nonces are captured at commit time and reused at reveal.
    private var p1Choices: IntArray? = null
    private var p1Nonce: ByteArray? = null
    private var p2Choices: IntArray? = null
    private var p2Nonce: ByteArray? = null

    /** Last completed match's data, used by [KicksActivity] for the replay payload. */
    var lastResult: MatchResult? = null
        private set

    // ── Setup ──────────────────────────────────────────────────────────

    suspend fun initSdk() {
        require(state.value is MatchState.Idle) { "initSdk requires Idle, got ${state.value}" }
        setState(MatchState.InitializingSdk)

        installProvingKeys()
        sdk = MidnightSdk.Builder(context)
            .network(network)
            .seed(seed)
            .build()
        Log.i(TAG, "SDK initialized, wallet: ${sdk!!.walletAddress}")
        Log.i(TAG, "Wallet keys installed: ${sdk!!.provingKeyManager.hasWalletKeys()}")

        setState(MatchState.SdkReady)
    }

    // ── Transition steps (one per circuit / logical action) ─────────────

    /** P1 deploys a fresh contract. Transitions [SdkReady] → [Deployed]. */
    suspend fun deployMatch(): String = transition(MatchState.Deploying) {
        val verifierKeys = loadVerifierKeys()
        val contract = createContractHandle(p1SecretKey, address = null, verifierKeys = verifierKeys)
        val result = contract.deploy { stage -> Log.d(TAG, "deploy: ${stage.javaClass.simpleName}") }
        Log.i(TAG, "Match at: ${result.contractAddress}")

        // Indexer needs a beat to ingest the deploy block, and the deploy
        // consumed a dust UTXO so we need to refresh the wallet's view.
        delay(INDEXER_SETTLE_MS)
        sdk!!.wallet.forceResyncDust()

        setState(MatchState.Deployed(result.contractAddress))
        result.contractAddress
    }

    /** P2 (AI today) joins the match. Transitions [Deployed] → [Joined]. */
    suspend fun aiJoin() {
        val prev = state.value
        require(prev is MatchState.Deployed) { "aiJoin requires Deployed, got $prev" }
        setState(MatchState.JoiningAsP2(prev.address))

        // Retry loop: the indexer may not yet see the freshly-deployed
        // contract. Backs off across 10 attempts before giving up.
        try {
            val deadline = java.math.BigInteger.valueOf(
                System.currentTimeMillis() / 1000 + COMMIT_DEADLINE_DURATION_SECS
            )
            var joined = false
            for (attempt in 1..JOIN_RETRY_LIMIT) {
                delay(JOIN_RETRY_DELAY_MS)
                try {
                    callCircuit(p2SecretKey, prev.address, "joinMatch", arrayOf(deadline))
                    joined = true
                    break
                } catch (e: Exception) {
                    if (e.message?.contains("not found") == true && attempt < JOIN_RETRY_LIMIT) {
                        Log.w(TAG, "Indexer not ready (attempt $attempt), retrying")
                        continue
                    }
                    throw e
                }
            }
            if (!joined) error("Failed to join after $JOIN_RETRY_LIMIT attempts")
            setState(MatchState.Joined(prev.address))
        } catch (e: Exception) {
            setState(MatchState.Failed(prev, e)); throw e
        }
    }

    /** P1 commits their five choices. Transitions [Joined] → [P1Committed]. */
    suspend fun submitP1Choices(choices: IntArray) {
        require(choices.size == 5) { "Need 5 choices" }
        val prev = state.value
        require(prev is MatchState.Joined) { "submitP1Choices requires Joined, got $prev" }
        setState(MatchState.P1Committing(prev.address))

        try {
            delay(POST_JOIN_SETTLE_MS) // join must finalize before next tx
            sdk!!.wallet.forceResyncDust()

            val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
            commitChoices(p1SecretKey, prev.address, choices, nonce)
            p1Choices = choices.copyOf()
            p1Nonce = nonce

            setState(MatchState.P1Committed(prev.address))
        } catch (e: Exception) {
            setState(MatchState.Failed(prev, e)); throw e
        }
    }

    /** P2 commits their five choices. Transitions [P1Committed] → [BothCommitted]. */
    suspend fun submitP2Choices(choices: IntArray) {
        require(choices.size == 5) { "Need 5 choices" }
        val prev = state.value
        require(prev is MatchState.P1Committed) { "submitP2Choices requires P1Committed, got $prev" }
        setState(MatchState.P2Committing(prev.address))

        try {
            delay(INTER_TX_SETTLE_MS)
            sdk!!.wallet.forceResyncDust()

            val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
            commitChoices(p2SecretKey, prev.address, choices, nonce)
            p2Choices = choices.copyOf()
            p2Nonce = nonce

            setState(MatchState.BothCommitted(prev.address))
        } catch (e: Exception) {
            setState(MatchState.Failed(prev, e)); throw e
        }
    }

    /** P1 reveals their choices. Transitions [BothCommitted] → [P1Revealed]. */
    suspend fun revealP1() {
        val prev = state.value
        require(prev is MatchState.BothCommitted) { "revealP1 requires BothCommitted, got $prev" }
        val choices = requireNotNull(p1Choices) { "No P1 choices captured" }
        val nonce = requireNotNull(p1Nonce) { "No P1 nonce captured" }
        setState(MatchState.P1Revealing(prev.address))

        try {
            delay(INTER_TX_SETTLE_MS)
            sdk!!.wallet.forceResyncDust()
            revealChoices(p1SecretKey, prev.address, choices, nonce)
            setState(MatchState.P1Revealed(prev.address))
        } catch (e: Exception) {
            setState(MatchState.Failed(prev, e)); throw e
        }
    }

    /**
     * P2 reveals. The contract auto-resolves when the second reveal lands.
     * Transitions [P1Revealed] → [Resolved].
     */
    suspend fun revealP2() {
        val prev = state.value
        require(prev is MatchState.P1Revealed) { "revealP2 requires P1Revealed, got $prev" }
        val p1c = requireNotNull(p1Choices) { "No P1 choices captured" }
        val p2c = requireNotNull(p2Choices) { "No P2 choices captured" }
        val p2n = requireNotNull(p2Nonce) { "No P2 nonce captured" }
        setState(MatchState.P2Revealing(prev.address))

        try {
            delay(INTER_TX_SETTLE_MS)
            sdk!!.wallet.forceResyncDust()
            revealChoices(p2SecretKey, prev.address, p2c, p2n)

            val result = MatchResult(playerChoices = p1c, aiChoices = p2c, contractAddress = prev.address)
            lastResult = result
            setState(MatchState.Resolved(result))
        } catch (e: Exception) {
            setState(MatchState.Failed(prev, e)); throw e
        }
    }

    // ── Orchestrators ───────────────────────────────────────────────────

    /**
     * Orchestrator: runs a full PvAI match end-to-end given P1's five
     * choices. P2's choices are generated locally. Real PvP doesn't use
     * this method — it calls the individual transitions as the
     * [StatePoller] reports opponent activity.
     *
     * Callers observe [state] for UX. No progress callback — the state
     * flow is the only progress surface. This is the canonical pattern
     * for a Kuira dApp.
     */
    suspend fun playAgainstAi(playerChoices: IntArray): MatchResult {
        val aiChoices = generateAiChoices()
        Log.i(TAG, "AI choices: ${aiChoices.map { dirLabel(it) }}")

        if (state.value is MatchState.Idle) initSdk()
        deployMatch()
        aiJoin()
        submitP1Choices(playerChoices)
        submitP2Choices(aiChoices)
        revealP1()
        revealP2()
        return requireNotNull(lastResult) { "Reveal completed but lastResult is null" }
    }

    fun close() {
        sdk?.close()
        sdk = null
        p1SecretKey.fill(0)
        p2SecretKey.fill(0)
        p1Nonce?.fill(0)
        p2Nonce?.fill(0)
    }

    // ── Internal helpers ────────────────────────────────────────────────

    /** Generate AI choices locally. Each round is independent [0..2]. */
    private fun generateAiChoices(): IntArray =
        IntArray(5) { SecureRandom().nextInt(3) }

    private fun dirLabel(d: Int): String = when (d) { 0 -> "L"; 1 -> "C"; 2 -> "R"; else -> "?" }

    /**
     * Convenience for transitions that have a simple in-progress → done
     * shape and need to capture the previous state for [Failed].
     */
    private suspend fun <T> transition(
        inProgress: MatchState,
        block: suspend () -> T,
    ): T {
        val prev = state.value
        setState(inProgress)
        return try {
            block()
        } catch (e: Exception) {
            setState(MatchState.Failed(prev, e))
            throw e
        }
    }

    /**
     * Centralised state setter — logs the transition (essential for
     * debugging "is the state machine even running?") and updates the flow.
     * Every other state assignment in this file routes through here.
     */
    private fun setState(newState: MatchState) {
        val prev = _state.value
        _state.value = newState
        // Log both the class (for grep / cheap pattern matching) and the
        // human label (so the log doubles as a script of what the user sees).
        Log.i(TAG, "state: ${prev::class.simpleName} → ${newState::class.simpleName}  «${newState.label}»")
    }

    // ── Circuit invocations ─────────────────────────────────────────────

    private suspend fun callCircuit(
        secretKey: ByteArray,
        address: String,
        circuitName: String,
        args: Array<Any?> = emptyArray(),
    ) {
        val contract = createContractHandle(secretKey, address)
        contract.call(circuitName, *args) { stage -> Log.d(TAG, "$circuitName: ${stage.javaClass.simpleName}") }
    }

    private suspend fun commitChoices(
        secretKey: ByteArray,
        address: String,
        choices: IntArray,
        nonce: ByteArray,
    ) {
        val contract = createContractHandle(secretKey, address, choices, nonce)
        contract.call("commitBatch") { stage -> Log.d(TAG, "commit: ${stage.javaClass.simpleName}") }
    }

    private suspend fun revealChoices(
        secretKey: ByteArray,
        address: String,
        choices: IntArray,
        nonce: ByteArray,
    ) {
        val contract = createContractHandle(secretKey, address, choices, nonce)
        contract.call("revealBatch") { stage -> Log.d(TAG, "reveal: ${stage.javaClass.simpleName}") }
    }

    private fun createContractHandle(
        secretKey: ByteArray,
        address: String?,
        choices: IntArray? = null,
        nonce: ByteArray? = null,
        verifierKeys: Map<String, ByteArray>? = null,
    ): MidnightContract {
        val midnightSdk = requireNotNull(sdk) { "SDK not initialized — call initSdk first" }
        val dummyNonce = ByteArray(NONCE_BYTES)

        return MidnightContract.create(midnightSdk.config) {
            name = "penalty"
            contractJs = context.assets.open("runtime/penalty-contract.js")
            if (address != null) this.address = address

            // Each witness returns a fresh ByteArray. The SDK zeroizes the
            // returned bytes after consumption (CircuitExecutor#registerWitnesses),
            // so callers' original arrays must not be exposed by reference.
            witness("localSecretKey") { WitnessResult(null, secretKey.copyOf()) }
            witness("localChoice0") { WitnessResult(null, byteArrayOf((choices?.get(0) ?: 0).toByte())) }
            witness("localChoice1") { WitnessResult(null, byteArrayOf((choices?.get(1) ?: 0).toByte())) }
            witness("localChoice2") { WitnessResult(null, byteArrayOf((choices?.get(2) ?: 0).toByte())) }
            witness("localChoice3") { WitnessResult(null, byteArrayOf((choices?.get(3) ?: 0).toByte())) }
            witness("localChoice4") { WitnessResult(null, byteArrayOf((choices?.get(4) ?: 0).toByte())) }
            witness("localNonce") { WitnessResult(null, (nonce ?: dummyNonce).copyOf()) }

            initialPrivateState = mapOf("secretKey" to secretKey.copyOf())
            coinPublicKey = midnightSdk.coinPublicKey
            if (verifierKeys != null) circuitVerifierKeys = verifierKeys
        }
    }

    // ── Bootstrap (keys, verifier files) ────────────────────────────────

    private fun loadVerifierKeys(): Map<String, ByteArray> {
        val circuits = listOf("commitBatch", "revealBatch", "joinMatch", "cancelMatch", "claimTimeout")
        return circuits.associateWith { name ->
            context.assets.open("keys/$name.verifier").use { it.readBytes() }
        }
    }

    private fun installProvingKeys() {
        val keysDir = File(context.filesDir, "proving_keys")
        keysDir.mkdirs()
        File(keysDir, "zswap").mkdirs()
        File(keysDir, "dust").mkdirs()

        val blsDir = File("/data/local/tmp/bboard_keys")
        listOf("bls_midnight_2p13", "bls_midnight_2p14", "bls_midnight_2p15").forEach { name ->
            val src = File(blsDir, name)
            val dst = File(keysDir, name)
            if (src.exists() && !dst.exists()) {
                src.copyTo(dst)
                Log.d(TAG, "Installed BLS: $name")
            }
        }

        val walletDir = File("/data/local/tmp/wallet_keys")
        listOf("zswap/spend", "zswap/output", "zswap/sign", "dust/spend").forEach { base ->
            listOf("prover", "verifier", "bzkir").forEach { ext ->
                val src = File(walletDir, "$base.$ext")
                val dst = File(keysDir, "$base.$ext")
                if (src.exists() && !dst.exists()) {
                    dst.parentFile?.mkdirs()
                    src.copyTo(dst)
                    Log.d(TAG, "Installed wallet key: $base.$ext")
                }
            }
        }

        val assetKeys = context.assets.list("keys") ?: emptyArray()
        assetKeys.filter { it.endsWith(".prover") || it.endsWith(".bzkir") || it.endsWith(".verifier") }.forEach { name ->
            val dst = File(keysDir, name)
            if (!dst.exists()) {
                context.assets.open("keys/$name").use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
                Log.d(TAG, "Installed contract key: $name")
            }
        }

        val versionFile = File(keysDir, "version.txt")
        if (!versionFile.exists()) versionFile.writeText("9")
    }

    companion object {
        private const val TAG = "MatchManager"
        private const val COMMIT_DEADLINE_DURATION_SECS = 300L
        private const val SECRET_KEY_BYTES = 32
        private const val NONCE_BYTES = 32

        /** How long to wait after deploy for the indexer to see the contract. */
        private const val INDEXER_SETTLE_MS = 5_000L

        /** How long to wait after a join before committing — joinMatch state must finalize. */
        private const val POST_JOIN_SETTLE_MS = 8_000L

        /** Between commit/reveal txs, the previous tx must be confirmed. */
        private const val INTER_TX_SETTLE_MS = 3_000L

        /** Retry budget for indexer-not-ready errors when joining. */
        private const val JOIN_RETRY_LIMIT = 10
        private const val JOIN_RETRY_DELAY_MS = 2_000L
    }
}

/**
 * Result of a completed match — used to build the replay data for Unity.
 *
 * Field name `aiChoices` is historical (AI was the only P2 implementation
 * when this was written). For PvP it holds the friend's choices.
 */
data class MatchResult(
    val playerChoices: IntArray,
    val aiChoices: IntArray,
    val contractAddress: String,
) {
    /** Build round results for Unity replay. */
    fun toRoundResults(): List<RoundResult> {
        return (0 until 5).map { i ->
            val isPlayerShooting = i % 2 == 0
            val shootDir = if (isPlayerShooting) playerChoices[i] else aiChoices[i]
            val keepDir = if (isPlayerShooting) aiChoices[i] else playerChoices[i]
            val isGoal = shootDir != keepDir

            RoundResult(
                round = i + 1,
                shooter = if (isPlayerShooting) "P1" else "P2",
                shootDir = shootDir,
                keepDir = keepDir,
                result = if (isGoal) "goal" else "save",
            )
        }
    }

    fun scores(): Pair<Int, Int> {
        val rounds = toRoundResults()
        val p1Goals = rounds.count { it.shooter == "P1" && it.result == "goal" }
        val p2Goals = rounds.count { it.shooter == "P2" && it.result == "goal" }
        return p1Goals to p2Goals
    }
}
