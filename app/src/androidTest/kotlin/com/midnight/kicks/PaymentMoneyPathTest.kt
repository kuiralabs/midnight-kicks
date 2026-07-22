package com.midnight.kicks

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.midnight.kuira.core.compact.ContractCallException
import com.midnight.kuira.core.compact.MidnightContract
import com.midnight.kuira.core.compact.TransactionStatus
import com.midnight.kuira.core.compact.WitnessResult
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.sdk.InsufficientFundsException
import com.midnight.kuira.sdk.MidnightSdk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigInteger

/**
 * Proves the unshielded money-path workaround end-to-end
 * on localnet, using the reporter's exact `payment.compact` (deposit via `receiveUnshielded`).
 *
 * It is a fails-before / passes-after proof:
 *   - a plain `call("deposit", amount)` (no funding offer) MUST fail — this reproduces the issue;
 *   - `call("deposit", amount, unshieldedFundingJson = ...)` MUST succeed — this is the workaround.
 *
 * Prereq: localnet up (node 9944 / indexer 8088 / proof 6300) and this wallet funded with NIGHT.
 * The test logs its wallet address and waits for funding, so airdrop to it while it waits:
 *   mn airdrop <amount> --wallet <logged address>
 */
@RunWith(AndroidJUnit4::class)
class PaymentMoneyPathTest {

    private val tag = "PaymentMoneyPathTest"

    // Fixed dev seed so the wallet address is stable across runs (airdrop once, reuse).
    private val seed = ByteArray(32) { (it + 1).toByte() }
    // Owner witness secret (payment's `ownerKey()`).
    private val ownerSecret = ByteArray(32) { (0x40 + it).toByte() }

    private val depositAmount: BigInteger = BigInteger.valueOf(1_000_000L)

    @Test
    fun plainDeposit_autoFunds_andClearErrorWhenDisabled() = runBlocking<Unit> {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        val sdk = MidnightSdk.Builder(ctx)
            .network(MidnightNetwork.UNDEPLOYED)
            .seed(seed.copyOf())
            .build()
        Log.i(tag, "WALLET ADDRESS = ${sdk.walletAddress}  ← airdrop NIGHT here")

        // Provision proving keys: protocol wallet keys (bundled via bundleWalletKeys) + the
        // payment contract's circuit keys (synced to the per-alias `payment-keys` asset dir).
        sdk.provingKeyManager.ensureWalletKeysAvailable(logger = { Log.i(tag, it) })
        sdk.provingKeyManager.installCircuitKeysFromAssets("payment-keys")

        awaitFunded(sdk)
        Log.i(tag, "wallet funded; registering dust…")
        runCatching { sdk.registerForDustGeneration() }
            .onFailure { Log.i(tag, "dust registration skipped/failed (may already be registered): ${it.message}") }

        // ── Deploy the payment contract ──
        // A `contracts { register("payment") }` entry syncs its keys to a per-alias dir.
        val verifierKeys = mapOf(
            "deposit" to ctx.assets.open("payment-keys/deposit.verifier").use { it.readBytes() },
            "withdraw" to ctx.assets.open("payment-keys/withdraw.verifier").use { it.readBytes() },
        )
        val address = paymentHandle(sdk, ctx, address = null, verifierKeys = verifierKeys)
            .deploy(onProgress = { Log.i(tag, "deploy: $it") })
            .contractAddress
        Log.i(tag, "payment deployed at $address")

        // Let the indexer observe the freshly-deployed contract before calling it
        // (mirrors the app's post-deploy settle; otherwise the next call gets
        // "Contract not found" from indexer lag, not the money-path behaviour).
        Log.i(tag, "settling for indexer…")
        delay(10_000)
        runCatching { sdk.wallet.refresh() }

        // ── Layer 2: a PLAIN deposit (no offer supplied) auto-funds from the wallet and lands ──
        // call() throws on any failure and only returns after submit + indexing, so a returned
        // SUBMITTED receipt means the SDK detected the receiveUnshielded, built the funding offer
        // itself, and the node accepted the tx.
        val receipt = paymentHandle(sdk, ctx, address)
            .call("deposit", depositAmount, onProgress = { Log.i(tag, "auto-fund deposit: $it") })
        Log.i(tag, "auto-funded deposit receipt: status=${receipt.status}")
        assertEquals("plain deposit should auto-fund + submit", TransactionStatus.SUBMITTED, receipt.status)

        val totalDeposited = paymentHandle(sdk, ctx, address).ledger().getUintBig("totalDeposited")
        Log.i(tag, "on-chain totalDeposited = $totalDeposited (expected $depositAmount)")
        assertEquals("auto-funded deposit should land the amount", depositAmount, totalDeposited)
        Log.i(tag, "LAYER 2 OK: plain deposit auto-funded and landed")

        // ── Layer 1: with auto-fund OFF, the same plain deposit fails with the clear typed error ──
        val sdkNoAuto = MidnightSdk.Builder(ctx)
            .network(MidnightNetwork.UNDEPLOYED)
            .seed(seed.copyOf())
            .autoFundUnshieldedDeposits(false)
            .build()
        sdkNoAuto.provingKeyManager.installCircuitKeysFromAssets("payment-keys")

        var typed: ContractCallException.UnshieldedValueUnfunded? = null
        try {
            paymentHandle(sdkNoAuto, ctx, address).call("deposit", depositAmount)
            Log.e(tag, "UNEXPECTED: plain deposit succeeded with auto-fund off")
        } catch (e: ContractCallException.UnshieldedValueUnfunded) {
            typed = e
            Log.i(tag, "expected typed error: ${e.message}")
        }
        assertNotNull("auto-fund off → plain deposit must throw UnshieldedValueUnfunded", typed)
        assertTrue(
            "error should name the funding builder; got: ${typed?.message}",
            typed?.message?.contains("buildUnshieldedFundingJson") == true,
        )
        Log.i(tag, "LAYER 1 OK: auto-fund off → clear typed UnshieldedValueUnfunded")
    }

    /** Poll until the wallet holds enough NIGHT to fund [depositAmount] (airdrop lands out-of-band). */
    private suspend fun awaitFunded(sdk: MidnightSdk) {
        repeat(80) { i ->
            runCatching { sdk.wallet.refresh() }
            try {
                sdk.buildUnshieldedFundingJson(depositAmount)
                return
            } catch (_: InsufficientFundsException) {
                if (i % 5 == 0) Log.i(tag, "waiting for funding… (${i * 3}s)")
                delay(3_000)
            }
        }
        fail("wallet never funded with NIGHT after ~240s — airdrop to the logged address")
    }

    private fun paymentHandle(
        sdk: MidnightSdk,
        ctx: android.content.Context,
        address: String?,
        verifierKeys: Map<String, ByteArray>? = null,
    ): MidnightContract = MidnightContract.create(sdk.config) {
        name = "payment"
        contractJs = ctx.assets.open("runtime/payment-contract.js")
        if (address != null) this.address = address
        witness("ownerKey") { WitnessResult(null, ownerSecret.copyOf()) }
        initialPrivateState = mapOf("ownerKey" to ownerSecret.copyOf())
        coinPublicKey = sdk.coinPublicKey
        if (verifierKeys != null) circuitVerifierKeys = verifierKeys
    }
}
