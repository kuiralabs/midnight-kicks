# Midnight Kicks — Penalty Shootout on Midnight

**Target:** FIFA World Cup 2026 (June 11 - July 19)
**Updated:** 2026-04-28

---

## Concept

PvP penalty shootout on Android. Two players, five rounds, real stakes (PREPROD NIGHT). Unity 3D + Midnight ZK proofs. The contract is the referee — no server, no trust.

**Why:** World Cup timing. Trojan horse for ZK (players don't know they're using zero-knowledge proofs). SDK validation before Kuira ships. Open-source connector SDK as the "Build on Midnight" story.

## Game flow

1. **CHOICE** — each player picks **10 directions** (5 shoots + 5 keeps), submits ONE transaction with both arrays committed (`RegulationBatch`).
2. **PROVE** — one ZK circuit scores all 10 rounds — alternating P1 shoots / P2 shoots — comparing shooter's `shoots[i]` against keeper's `keeps[i]` per kick.
3. **REPLAY** — Unity plays the 10 rounds cinematically (stadium intro masks proof latency).

Sudden death: **one pairing per batch** (your `shoot` + your `keep`). Decisive when exactly one player scored the pairing; otherwise another pairing. Unrevealed pairings stay private (ZK property).

**Anti-cheat:** commit-reveal. Pedersen commitment of `(shoots[5], keeps[5])` + 32-byte nonce stored as private state. ZK circuit proves revealed values match commitments. Cannot change choices after commit.

> **Status note:** the on-chain contract is currently **V2** (5 dual-purpose directions per player, asymmetric P1=3 shots / P2=2 shots). The above is the **V3 target** spec — see `docs/GAME_DESIGN.md` §2 & §7 migration. V3 contract redeploy is queued as a Phase 4 item.

Detailed game logic, state machine, circuit specs, UI flows, and Unity bridge spec in [`GAME_DESIGN.md`](GAME_DESIGN.md). Current Unity work and asset checklist in [`../ROADMAP.md`](../ROADMAP.md).

## Stakes & gas

- PREPROD NIGHT stakes (configurable per match, default 1 NIGHT). Winner takes pot, draw = refund.
- DUST for gas via PREPROD faucet. Provider-pay model on mainnet (developer subsidizes gas).

## Timeout & disconnect

- Commitment timeout (5 min) — if opponent doesn't commit, committed player claims pot (forfeit).
- Replay is client-side (Unity) — disconnect mid-replay doesn't affect on-chain result.
- Match cancellation if opponent never joins (reclaim stake after timeout).

## Matchmaking

- **QR (in-person):** Create Match → show QR → opponent scans → joined.
- **Deep link (remote):** Create Match → share `midnight://kicks?match=<contract_address>` → opponent opens.
- Match ID = deployed contract address. No central server.

## Leaderboard

On-chain, indexer-queryable. Wins/losses/draws/streaks per player address. Verifiable — no fake leaderboards.

## Architecture

- **Unity (UaaL)** — 3D stadium, ball physics, choice UI, cinematic replay. JSON bridge to Kotlin. Knows nothing about blockchain.
- **Kotlin (native)** — SDK for contract interaction, pairing (QR + deep links), UaaL bridge, state polling for opponent commits.
- **Compact contract** — match lifecycle, commit-reveal, scoring, stake escrow, payouts. Each match = new contract instance.
  - Private state (V3): `shoots[5]` + `keeps[5]` + nonce per player for regulation; one `(shoot, keep, nonce)` triple per SD pairing
  - Public ledger: participants, scores, results, winner, stakes, phase
  - Circuits: create, join, commit batch, resolve regulation, resolve sudden death, claim payout

## Repo & relationship to Kuira

Separate repo: `midnight-kicks/` (app/ + unity/ + contract/). Consumes Kuira SDK as pre-built AAR. Proves the SDK works standalone — if Kicks can't build without the full Kuira repo, the SDK isn't self-contained. Connector SDK open-sourced separately.

## Identity (two-tier) — INVESTIGATED, DECIDED

- **Tier 1 (standalone):** SDK generates keys (Android Keystore), manages UTXOs, signs/submits. No external wallet.
- **Tier 2 (Kuira enhanced):** SDK detects Kuira → delegates to TEE-backed sigil. Automatic upgrade, no code change.

| Primitive | Decision | Tier 1 (SDK) | Tier 2 (Kuira) |
|-----------|----------|-------------|----------------|
| Passkey (P-256) | CredentialManager (API 28+) | Google Password Manager | TEE/StrongBox + biometric |
| DID | `did:key` from root passkey | One per user (not per-dApp) | Sigil dashboard |
| Access key | secp256k1 (advocate P-256 to Midnight) | Self-verifiable keyAuthorization | Delegation policies |
| Recovery | PRF-encrypted cloud backup | Zero words, passkey syncs | TEE-hardened key material |

**Our edge over rvcas:** self-verifiable keyAuthorization (TEE signs directly, no server trust). Full investigation in the parent Kuira repo at `kuira-android-wallet/docs/planning/IDENTITY_INVESTIGATION.md` (not copied here — it spans both Kicks and Kuira).

---

## Progress

- [x] **Phase 1 — Compact contract**
  - [x] penalty.compact V2 (commit-reveal, batch, sudden death, timeout)
  - [x] Deploy to undeployed + 27 tests + security registry
- [x] **Phase 2 — Midnight Android SDK** (validated 2026-04-28)
  - [x] MidnightSdk facade + embedded wallet (balance + prove + submit)
  - [x] Proving key auto-download
  - [x] BBoard standalone on PREPROD (no mn serve)
  - [x] Balance progress callbacks
  - [x] Identity investigation + decisions (see IDENTITY_INVESTIGATION.md)
  - [x] Contract deployment API (each match = new contract)
  - [x] Passkey identity (CredentialManager + did:key + keyAuthorization) — verified on emulator
  - [x] PRF-encrypted cloud backup — verified on emulator (same-session round-trip, cross-device needs physical device)
- [x] **Phase 3 — Unity + Kotlin integration** (complete 2026-05-14)
  - [x] kick-prototype with ball physics + 3 directions (URP, IL2CPP, arm64)
  - [x] UaaL export script (ExportAndroidLibrary.cs, batch mode CLI)
  - [x] Separate Gradle project (AGP 9.0.0, independent from Kuira build)
  - [x] GameController.cs — JSON bridge, 5-round choice UI, replay stub
  - [x] UnityBridge.kt — Kotlin↔Unity JSON messaging
  - [x] KicksActivity — main menu + deep link handler
  - [x] GameController receiving choicePhase + sending choicesLocked (end-to-end) — verified by 2026-05-13 logcat round-trip: Kotlin sends `choicePhase` → Unity renders 5-round picker → Unity sends `choicesLocked` → Kotlin advances match
  - [x] Replay system (5 rounds from JSON) + stadium intro cinematic — `ShotManager.PlayReplay` runs a 2.2s intro dolly (`IntroStartCam` → `EstablishingCam` + "GET READY..."), then per-round push-in to `ActionCam` peaking at ball strike, shooter run-up + kick + keeper dive + ball flight + procedural reaction (celebration hops / defeat lean), scoreboard + per-round feedback, final result hold, `replayComplete` back to Kotlin. Sudden-death replays use the same path.
  - [x] MatchManager — deploy/join/commit/reveal/claim circuit calls (state-machine refactor 2026-05-12: discrete suspend transitions, `StateFlow<MatchState>` as source of truth, `KicksActivity` is now a thin presenter over the SDK)
  - [x] StatePoller — watch opponent actions via indexer (2026-05-13: 3s poll on `MidnightConfig.queryState`, parses `penalty.compact` ledger via verified cell indices, exposed on `MatchManager.contractState: StateFlow`)
  - [x] PvP wait helpers — `MatchManager.waitForP2Committed()` / `waitForP2Revealed()` spin up the StatePoller only for the wait window (not continuously), then transition the state machine when chain state matches; `waitForP2Revealed` also reads `p2Choices` from the snapshot to build the final `MatchResult` (we never see the friend's choices locally in PvP). Unblocks Phase 4.
- [ ] **Phase 4 — Full two-player game**
  - [x] Onboarding (passkey → biometric → play) — sigil panel handles it, "create identity" cue shown in `SigilStatusPanel.NoneBody` covers first-launch. Tutorial overlay for "how to play" is a P5 polish item, not a blocker.
  - [x] **Matchmaking — UI scaffolding** — `CreateMatchScreen` (deploy → QR + COPY), `JoinMatchScreen` (paste/prefill + JOIN), state-based nav in `KicksActivity`, `handleDeepLink` populates `JoinMatchScreen` from `midnight://kicks?match=…`.
  - [x] **Matchmaking — chain logic** — `MatchManager.joinAsP2(address)`, `awaitOpponentJoin()`. Plumbed into both screens.
  - [x] **Create-and-go session** — no blocking auto-await on creator's device. Session persisted via `KicksSessionStore` (SharedPrefs), `RESUME MATCH` on menu, `CHECK STATUS` on `CreateMatchScreen` runs a short non-terminal probe. Cross-process resume needs encrypted key persistence (next).
  - [x] **PvP gameplay orchestrators** — `MatchManager.playAsP1` / `playAsP2`, P2-side `waitForP1Committed` / `waitForP1Revealed` (captures P1's choices from chain snapshot). `KicksActivity.handleChoicesLocked` dispatches by role.
  - [ ] Two-emulator E2E on localnet — create on emulator A, deep-link from emulator B via `adb shell am start -a android.intent.action.VIEW -d "midnight://kicks?match=<addr>"`. Ready to test.
  - [ ] **Cross-process resume** — encrypted persistence of per-match secret keys + choices/nonces so resume survives app kill. Shares the data shape with Block Store cross-device sync; doing them together makes sense. (PLAN.md SDK connector wishlist #4.)
  - [ ] **Contract V3** — real penalty rules: each player commits `shoots[5]` + `keeps[5]` (10 directions total, 5 kicks per player, 5 saves each). 10-round regulation, single-pairing SD. Asymmetric V2 model (P1 gets 3 shots vs P2 gets 2) gets dropped. See `docs/GAME_DESIGN.md` §2 V3 model + §7 migration. Contract redeploy required.
  - [ ] Results screen + leaderboard query
- [ ] **Phase 5 — Polish + release**
  - [ ] APK size audit (< 100MB), proof latency tuning
  - [ ] Error handling, timeout UX, disconnect recovery
  - [ ] **Unity in a separate process** (`android:process=":unity"`) — eliminates the shared-main-thread ANR seen when Unity's onDestroy takes >10s on emulator. Requires re-plumbing `UnityBridge` across processes (AIDL/IPC) since static-field passing breaks. Current workaround: `RequestPause` kills the process to bypass Unity teardown.
  - [ ] **Pause button polish** — replace `GUI.Button("II")` with a Canvas-based pause icon + proper styling.
  - [ ] **QR scanner** for matchmaking — Google Code Scanner (no camera permission needed) on `JoinMatchScreen`.
  - [ ] Play Store listing, closed beta
- [ ] **Phase 6 — Launch**
  - [ ] Open beta → announce (World Cup timing, June 11)

---

## SDK friction log

Every friction point building BBoard standalone → becomes SDK improvement.

| # | Friction | Severity | Fix |
|---|---------|----------|-----|
| 1 | Wall-clock `ctime` passed to dust spend caused error 170 (InvalidDustSpendProof) on every contract call after the first. Chain validates dust proof against `root_history.get(ctime)`, a predecessor lookup keyed by block timestamps — wall-clock returned the chain's tip-root, which never matched our locally-replayed root. | Critical | ✅ FIXED 2026-05-13 (commit `868e0d9`). `MidnightWallet.tryBalance` now uses `blockInfo.timestamp` (already fetched for ledgerParameters). Mirrors TS wallet's `currentTime ?? blockData.timestamp`. |
| 2 | DustLocalState serialize/deserialize corrupts Merkle roots | High | In-memory only workaround. Needs SCALE codec fix. |
| 3 | Full dust sync 60s on PREPROD (253k events) | Medium | Background sync + progress bar. Optimize later. |
| 4 | `fromId: null` skips early events (indexer treats null ≠ 0) | Critical | Always pass `id: 0`. |
| 5 | Tag-prefix hex splitting corrupts events at scale | Critical | Line-per-event file format. |
| 6 | No progress during balance+submit (60s opaque) | High | BalanceProgress callbacks (6 stages). |
| 7 | FFI pointer write-back corrupts cached state | High | Don't write back post-spend state. |
| 8 | WebSocket backpressure OOM on 250k events | Medium | File streaming, Rust native memory. |
| 9 | No contract deployment API | Medium | ✅ FIXED. `MidnightContract.deploy()` + FFI. |
| 10 | Content behind system status bar | Low | WindowInsets padding. |

---

## SDK connector — wishlist (non-blockers)

These don't block Kicks shipping, but Kicks is forcing patterns that the
Kuira connector SDK should bake in so the next dApp doesn't reinvent
them. Add to this list whenever a MatchManager-style workaround appears.

| # | Wish | Why it'd help | Today's workaround |
|---|------|---------------|--------------------|
| 1 | `awaitIndexerSynced(blockHeight)` primitive | Replace fixed `delay(5000)` waits between deploy → first call and between sequential tx. Reduces total match time and makes flow deterministic. | Hard-coded 3–8s sleeps in MatchManager (INDEXER_SETTLE_MS, POST_JOIN_SETTLE_MS). |
| 2 | `MidnightContract.stateFlow(): Flow<ContractState>` | StatePoller becomes a one-liner. Lets UI react to opponent actions without polling boilerplate. | Caller writes a polling loop against the indexer GraphQL. |
| 3 | Retry policy on `contract.call(...)` | "Indexer says contract not found, wait + retry" is a near-universal pattern after deploy. Bake retry-with-backoff into the call surface. | MatchManager.aiJoin loops 10× by hand, matching on `"not found"` substring. |
| 4 | Serializable state snapshot for BlockStore backup | A `MatchState` snapshot (address + nonces + commit/reveal flags) should round-trip to bytes for Google BlockStore so a player can resume after process death or device hop. | Manual serialization in the app layer. |
| 5 | First-class `Player` / `Identity` abstraction | The two-player witness pattern (P1 secret key, P2 secret key, swap on each call) is a recurring shape. A `Player` with `secretKey` + `coinPublicKey` + witness-registration helper would shrink each call site. | MatchManager passes raw `ByteArray` secrets through every helper. |
| 6 | Auto force-resync of dust around contract calls | Every tx after a deploy or another tx needs `wallet.forceResyncDust()` to see the new UTXO state. Should happen inside `contract.call` when needed. | Manual `forceResyncDust()` between every tx in MatchManager. |
| 7 | Deadline / timeout helper | `BigInteger.valueOf(System.currentTimeMillis() / 1000 + N)` for unix-second deadlines is everywhere a circuit takes a deadline. | Computed by hand in MatchManager.aiJoin. |
| 8 | "Test-mode seed" path | The shared test seed (`TEST_SEED` in KicksActivity) for fast iteration on faucet networks should be a single SDK opt-in, not a literal in every example app. | Hex literal in KicksActivity. |
| 9 | Typed ledger wrapper from `.compact` | Every dApp parses `queryState`'s positional `JSONArray` by hand: walking nested storage groups, mapping flat indices to fields, knowing the encoding rules for every Compact type (Boolean = `{"number":0\|1}` or `{"hex":"00"\|"01"}`, Uint = LE hex, Bytes<N> = hex, `Vector<N, Uint<8>>` = concatenated element bytes in one cell), AND writing the array-aware `equals` / `hashCode` boilerplate Kotlin data classes can't synthesize for ByteArray/IntArray fields. The V3 migration *doubled* the friction: adding two new Compact types to the ledger (`Vector<5, Uint<8>>` × 4 and SD `Uint<8>` × 4) pushed storage past the single-group limit, so the parser also has to walk `[[8 cells], [15 cells]]` instead of a flat list. Codegen a typed `Ledger` class per contract from the compiled JS's `ledger()` descriptors (same names, same shape, decoded automatically) and tests can construct the typed Ledger directly instead of hand-rolling JSONArray fixtures. | `ContractStateSnapshot.kt` is 250 lines of cell-by-cell decoding + manual `equals`/`hashCode`. `ContractStateSnapshotTest.kt` is 280 lines of hand-crafted JSONArray fixtures that have to mimic the indexer's exact split-group output. New ledger field = 4 places to touch (snapshot class, parser, equals, hashCode) + matching test fixtures. |
| 10 | Public block subscription | `MidnightSdk.indexerClient` is `private`, so app code can't reach `subscribeToBlocks()` for push-based contract-state watching. Expose either the indexer or a thin `MidnightContract.stateFlow()` over it. | `StatePoller` falls back to 3s polling. |
| 11 | Kuira Gradle plugin for contract artifact sync | Copying compiled `.compact` outputs into Android assets (`penalty-contract.js` + `keys/*.{prover,verifier,bzkir}`) is the same shape across every Kuira dApp, but each one hand-rolls a Gradle Copy task with hardcoded contract names and paths. A `com.midnight.kuira.contract` plugin should let dApps declare `kuiraContract { source = "contract/src/managed/penalty" }` (or auto-discover from a sibling module) and get sync-before-`mergeAssets` for free. | `app/build.gradle.kts` registers a hand-rolled `syncContractAssets` Copy task with the contract path and circuit-key globs baked in. |
| 12 | Typed witness factories for non-scalar types | `WitnessResult(null, ByteArray)` works for `Bytes<32>` / `Uint<8>`, but for `Vector<N, T>` and other compound types the developer has to know the runtime's serialization (concatenated element bytes) and assemble it by hand. SDK should expose factory helpers like `vectorWitness(length=5) { intArrayOf(L, C, R, L, R) }` and `boolWitness { true }` that produce the right `ByteArray` for the underlying compact-runtime descriptor. | MatchManager packs Vector witnesses by hand: `byteArrayOf(*shoots.map(Int::toByte).toByteArray())`. |
| 13 | Compactc version pinning | Compactc's `--runtime-version` must match the project's `@midnight-ntwrk/compact-runtime` npm version, but there's no in-repo way to declare which compactc version to use — developers find out via a runtime error after `npm run compact` writes incompatible bytecode. Either a `.compact-version` file in `contract/` honored by the `compact` wrapper, or auto-pinning where `compact compile` reads package.json's pinned runtime version and switches itself. | Kicks V3 requires compactc 0.30.0 (runtime 0.15.0); 0.31.0 is the user default and emits incompatible 0.16.0 bytecode. Workaround: invoke `~/.compact/versions/0.30.0/aarch64-darwin/compactc` directly or run `compact update 0.30.0`. |

Promote items to the friction log once they hit a real user-visible bug.

---

## Decision log

| Decision | Choice | Why |
|----------|--------|-----|
| Batch vs per-round | Batch commit, 1 tx per phase | V2: 5 dual-purpose choices. V3 target: `shoots[5]` + `keeps[5]` in one commit. Either way 2 txs per regulation (commit + reveal) vs 20+ for per-round. Cinematic replay. |
| Symmetric vs asymmetric roles | V3 = symmetric (each player shoots 5 + keeps 5) | V2 was asymmetric (P1: 3 shots, P2: 2 shots) — doesn't match real penalty rules. V3 redesign aligns with real-life shootouts and lets players strategize offense and defense independently. |
| Sudden death | Batches of 5, stop at decisive | Unrevealed rounds private. No infinite loops. |
| Unity vs Compose | Unity (UaaL) | 3D stadium, ball physics, cameras. |
| Standalone repo | Separate from Kuira | Tests SDK boundaries. Separate release. |
| Pairing | QR + deep links (built in Kicks) | Simpler than connector transports. |
| Key curve | secp256k1 (advocate P-256) | Midnight accepts secp256k1 today. |
| keyAuthorization | Self-verifiable (TEE signs) | No server trust needed. |
| DID | One per user from root passkey | Sigil = one identity. |
| Recovery | PRF-encrypted cloud backup | Zero words. Passkey syncs → biometric → restored. |
| Gas | PREPROD faucet (provider-pay on mainnet) | Lowest friction for testnet. |
| Proving | On-device (local) | Proves hardware capability. No server. |
