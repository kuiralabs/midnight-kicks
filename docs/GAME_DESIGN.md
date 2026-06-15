# Midnight Kicks — Game Design Spec

**Source of truth for game logic, state machine, UI flows, and integration specs.**
Contract implementation: [`../contract/src/penalty.compact`](../contract/src/penalty.compact)

> **Last reconciled against code: 2026-06-14 (HEAD 3bd8e52).**
>
> **Contract version: V3 (live — the only contract that exists).**
> V3 is wired end to end across `penalty.compact`, the compiled
> `managed/penalty` artifacts, the Kotlin witnesses, and the on-device
> app assets. Each player commits **5 shots + 5 keeps (10 directions)**;
> both play shooter and keeper an equal number of times, aligned with
> real penalty-shootout rules. See §2 "V3 model" for the data shape and
> scoring. There is **no V2 contract in the tree** — V2 was fully
> replaced by the V3 migration (commit 7370c7f); the §2 "V2 reference"
> and §7 "V2 → V3 migration" sections below are retained as historical
> context only. The contract is **deployed dynamically per match at
> runtime** (`MatchManager.deploy()`), not to a fixed static address.

---

## 1. Match state machine

```
                    ┌──────────────────────────────┐
                    │          WAITING              │
                    │  P1 created match, escrow in  │
                    │  Waiting for P2 to join       │
                    └──────────┬───────────────┬────┘
                               │               │
                          P2 joins         timeout
                               │               │
                    ┌──────────▼──────────┐   ┌─▼─────────┐
                    │     COMMITTING       │   │ COMPLETE   │
                    │  Both commit         │   │ P1 refund  │
                    │  shoots[5]+keeps[5]  │   └────────────┘
                    └──────┬─────┬────────┘
                           │     │
                     both commit  timeout (one committed)
                           │     │
                    ┌──────▼──┐  ┌▼──────────┐
                    │REVEALING│  │  COMPLETE  │
                    │Both show│  │  Forfeit → │
                    │preimage │  │  committed │
                    └────┬────┘  │  player    │
                         │       └────────────┘
                    both reveal
                         │
                    ┌────▼─────────────────────┐
                    │  Resolve regulation       │
                    │  Score 10 rounds          │
                    │  (5 kicks each)           │
                    └─────┬──────────────┬──────┘
                          │              │
                     clear winner     tied
                          │              │
                   ┌──────▼──────┐  ┌───▼──────────────┐
                   │  COMPLETE   │  │SD_COMMITTING      │
                   │  Payout to  │  │Both commit one    │
                   │  winner     │  │pairing: shoot+keep│
                   └─────────────┘  └───┬──────────────┘
                                        │
                                   both reveal,
                                   score pairing
                                        │
                                  ┌─────┴─────┐
                              decisive       both score
                                  │           or both miss
                                  ▼              │
                          ┌────────────┐         ▼
                          │  COMPLETE   │  back to SD_COMMITTING
                          │  SD winner  │  for next pairing
                          └────────────┘
```

### Phases (V3)

| Phase | Description | Transitions to |
|-------|-------------|----------------|
| `WAITING` | P1 created match + escrowed stake. Waiting for P2. | → `COMMITTING` (P2 joins) or `COMPLETE` (timeout) |
| `COMMITTING` | Both players submit `RegulationBatch` commitments (hash of `shoots[5]` + `keeps[5]` + nonce). | → `REVEALING` (both committed) or `COMPLETE` (timeout forfeit) |
| `REVEALING` | Both reveal `RegulationBatch` preimages. Circuit verifies and scores 10 rounds. | → `COMPLETE` (clear winner) or `SD_COMMITTING` (tied) |
| `SD_COMMITTING` | Sudden death: both commit one `SuddenDeathBatch` (a single `shoot` + `keep` for this pairing). | → `SD_REVEALING` (both committed) or `COMPLETE` (timeout forfeit) |
| `SD_REVEALING` | Both reveal SD preimages. Circuit scores the pairing: decisive → `COMPLETE`; tied (both scored or both missed) → another `SD_COMMITTING`. | → `COMPLETE` (winner found) or `SD_COMMITTING` (still tied) |
| `COMPLETE` | Match over. Winner claims payout, or draw refund. | Terminal |

### Timeout rules

- Each phase has a `deadline` (unix seconds). Default: 5 minutes.
- If deadline passes with only one player having acted → that player can call `claimTimeout()` to win by forfeit.
- WAITING phase: P1 can cancel and reclaim stake if P2 never joins.

---

## 2. Batch commit-reveal protocol

### Direction encoding

| Value | Direction |
|-------|-----------|
| 0 | LEFT |
| 1 | CENTER |
| 2 | RIGHT |

Any value > 2 is rejected by `validDirection()` check in circuit.

### Coordinate convention

**Directions are always from the SHOOTER's perspective.** Reference:
`unity/Assets/Keeper.cs:105` — the `direction` arg into `Keeper.Dive`
expects `0=left, 2=right` "from the shooter's perspective." The keeper
faces the shooter (mirrored), so `Dive(0)` plays `DiveRight` animation
even though the direction value is "left" — same physical spot, just
opposite-handed body lean.

This matters for the choice UI: when a keeper taps LEFT during the
commit phase, the underlying value committed is `0` (shooter's left),
not "the keeper's left." The keep prompt copy is reframed accordingly
("predict where they'll kick") — see §4 *Per-role choice UI* for the
locked-in framing.

### V3 model — shoots[5] + keeps[5] per player

**Each player commits two independent arrays of 5 directions:**

```
shoots: [Uint<8>; 5]   // where I aim when it's my turn to kick
keeps:  [Uint<8>; 5]   // where I dive when the opponent is shooting at me
```

10 directions per player → matches real penalty rules: 5 kicks taken,
5 kicks faced.

**Regulation: 10 rounds, alternating shooter.**

| Round (0-indexed) | Shooter | Compares… |
|---|---|---|
| 0 | P1 | `P1.shoots[0]` vs `P2.keeps[0]` |
| 1 | P2 | `P2.shoots[0]` vs `P1.keeps[0]` |
| 2 | P1 | `P1.shoots[1]` vs `P2.keeps[1]` |
| 3 | P2 | `P2.shoots[1]` vs `P1.keeps[1]` |
| 4 | P1 | `P1.shoots[2]` vs `P2.keeps[2]` |
| 5 | P2 | `P2.shoots[2]` vs `P1.keeps[2]` |
| 6 | P1 | `P1.shoots[3]` vs `P2.keeps[3]` |
| 7 | P2 | `P2.shoots[3]` vs `P1.keeps[3]` |
| 8 | P1 | `P1.shoots[4]` vs `P2.keeps[4]` |
| 9 | P2 | `P2.shoots[4]` vs `P1.keeps[4]` |

Goal if `shoot != keep`, save otherwise. Scorer earns +1.

Round-to-array mapping: `kick_index = round / 2`. Round parity selects
shooter (`round % 2 == 0` → P1 shoots).

**Players can strategize offense and defense independently.** A player
might pick `shoots = [L, L, R, C, R]` and `keeps = [R, R, L, L, C]` —
the contract reads each from its own array; one doesn't constrain the
other.

### V2 reference (historical — superseded by V3)

> **Historical only.** V2 no longer exists in the tree; V3 is the live
> contract (commit 7370c7f replaced V2). Kept for context on why the
> shape changed.

V2 used a single dual-purpose array per player:

```compact
struct BatchPreimage { c0, c1, c2, c3, c4 : Uint<8> }
```

5 dual-purpose directions per player. `c[i]` plays as shoot when that
player is the shooter for round `i`, and as keep when they're the
keeper. P1 shoots rounds 0, 2, 4 (3 shots); P2 shoots rounds 1, 3
(2 shots). Asymmetric and conflates offense/defense — the reason for
the V3 migration.

### Commitment (V3)

Two preimage structs, one per phase:

```compact
struct RegulationBatch {
  shoots: Vector<5, Uint<8>>;
  keeps:  Vector<5, Uint<8>>;
}

struct SuddenDeathBatch {
  shoot: Uint<8>;   // my single kick direction for this SD pairing
  keep:  Uint<8>;   // my single dive direction for this SD pairing
}
```

Commitment per phase:

```
regulation_commitment = persistentCommit<RegulationBatch>(preimage, nonce)
sd_commitment         = persistentCommit<SuddenDeathBatch>(preimage, nonce)
```

- `nonce`: 32-byte random. Regulation preimage space is `3^10 = 59049`
  possibilities; SD is `3^2 = 9`. Nonce still required to prevent
  rainbow-table attacks; far more important for SD where the space is
  tiny.
- `persistentCommit`: Midnight's native Pedersen commitment (binding +
  hiding).

### Reveal (V3)

Player reveals all directions + nonce for the current phase's preimage.
Circuit recomputes commitment and verifies it matches the stored hash.
Mismatch → tx fails (cheating detected). 10 reveal values for
regulation, 2 for sudden death.

### Scoring (regulation, V3)

10 rounds, sequential. Round `r` (0-indexed):

```
let kick_index = r / 2
if r is even:                                  // P1 shoots
  if P1.shoots[kick_index] != P2.keeps[kick_index]:
    p1Score += 1
else:                                          // P2 shoots
  if P2.shoots[kick_index] != P1.keeps[kick_index]:
    p2Score += 1
```

End state:
- `p1Score > p2Score` → P1 wins, phase → COMPLETE
- `p2Score > p1Score` → P2 wins, phase → COMPLETE
- `p1Score == p2Score` → tied, phase → SD_COMMITTING

### Scoring (sudden death, V3)

Each SD batch = **one pairing**: P1 shoots → P2 keeps, then P2 shoots
→ P1 keeps. Resolution after both reveal:

```
p1_scored = (P1.shoot != P2.keep)
p2_scored = (P2.shoot != P1.keep)

if p1_scored && !p2_scored:    P1 wins, phase → COMPLETE
elif p2_scored && !p1_scored:  P2 wins, phase → COMPLETE
else (both scored or both missed):
    phase → SD_COMMITTING       // another pairing
```

**ZK property:** SD pairings are scored individually; pairings after a
decisive one are never played. The committed direction values for a
pairing are revealed (necessary for verification), but no information
leaks about subsequent unplayed pairings.

In practice 3^2 = 9 SD outcomes per pairing; expected pairings to a
decisive result is ~2. The protocol supports arbitrary SD batches.

---

## 3. Contract circuits (V3)

> **Names reconciled to the live V3 contract (`penalty.compact`,
> `managed/penalty/compiler/contract-info.json`).** V3 exposes exactly
> 7 proof circuits (plus the constructor); the earlier draft used the
> V2-era names `commitBatch`/`revealBatch`/`sdCommitBatch`/
> `sdRevealAndResolve`/`claimPayout`, which no longer exist.

| Circuit | When | Inputs (public) | Witnesses (private) | Effects |
|---------|------|-----------------|--------------------|---------|
| `constructor` | Deploy | — | — | `phase=WAITING`, P1=caller, deadline=now+1hr |
| `joinMatch` | P2 joins | `commitDeadlineSecs` | `localSecretKey` | Set P2, escrow stake, `phase→COMMITTING`, set deadline |
| `commitRegulation` | Each player, regulation | — | `localShoots[5]` + `localKeeps[5]` + `localNonce` | Store commitment hash of `RegulationBatch`, set committed flag. Auto-advances `phase→REVEALING` when both committed. |
| `revealRegulation` | Each player, regulation | — | `localShoots[5]` + `localKeeps[5]` + `localNonce` | Verify vs commitment, store revealed `shoots` & `keeps`. Last reveal auto-scores 10 rounds, sets winner or `phase→SD_COMMITTING`. |
| `commitSuddenDeath` | Each player, SD pairing | — | `localSdShoot` + `localSdKeep` + `localNonce` | Store SD-pairing commitment hash. Auto-advances `phase→SD_REVEALING` when both committed. |
| `revealSuddenDeath` | Each player, SD pairing | — | `localSdShoot` + `localSdKeep` + `localNonce` | Verify, last reveal scores the pairing. Decisive → `phase→COMPLETE`; tied → `phase→SD_COMMITTING` (next pairing). |
| `claimTimeout` | Deadline passed (any non-WAITING, non-COMPLETE phase) | — | `localSecretKey` | Forfeit opponent → `phase→COMPLETE`, caller recorded as winner |
| `cancelMatch` | WAITING, P1 only | — | `localSecretKey` | P1 cancels before P2 joins, reclaim stake → `phase→COMPLETE` |

> **Payout:** V3 records `winner`/`isDraw` in ledger state on resolution;
> there is no separate `claimPayout` circuit in this revision (the old
> draft listed one). Escrow settlement is driven off that recorded result.

### Witness shape change from V2

V2 circuits expect 5 scalar witnesses (`localChoice0..localChoice4`).
V3 needs **two arrays per regulation commit/reveal** and **two scalars
per SD commit/reveal**. The witness wiring on the Kotlin side
(`MatchManager.createContractHandle`) needs to expose `localShoots`
and `localKeeps` as `Vector<5, Uint<8>>` witnesses for regulation,
and `localShoot` / `localKeep` as `Uint<8>` for SD.

---

## 4. Screen flow (IA)

State-based navigation via `KicksScreen` (sealed class in
`app/.../KicksScreen.kt`). No `Navigation-Compose` dep; `KicksActivity`
holds a `mutableStateOf<KicksScreen>` and renders the matching
Composable.

```
[Menu]
  │  ┌────────────────────────────────────────────────────────┐
  │  │ Buttons (top-to-bottom):                                │
  │  │   RESUME MATCH   ← only if MatchStore has a saved row   │
  │  │   CREATE MATCH                                          │
  │  │   JOIN MATCH                                            │
  │  │   PRACTICE VS AI ← dev affordance, PvAI legacy path     │
  │  └────────────────────────────────────────────────────────┘
  │
  ├─ CREATE MATCH ────► [Creating(address=null)]
  │                       ↓ MatchManager.deployMatch()
  │                       ↓ MatchStore.save({addr, P1, deadline}) (encrypted)
  │                     [Creating(address=<hex64>)]
  │                       • QR encodes "midnight://kicks?match=<addr>"
  │                       • COPY button → clipboard
  │                       • CHECK STATUS → MatchManager.awaitOpponentJoin(4s)
  │                         · success → [MatchReady(addr, P1)]
  │                         · timeout → "Still waiting — tap again"
  │                         · the state machine stays Deployed; timeout
  │                           is non-terminal so the user can keep
  │                           polling
  │
  ├─ JOIN MATCH ─────► [Joining(prefilledAddress=null)]
  │                       • Text field accepts 64-char hex contract addr
  │                       • JOIN MATCH enabled when regex matches
  │                       ↓ MatchManager.joinAsP2(address)
  │                       ↓ MatchStore.save({addr, P2, deadline}) (encrypted)
  │                     [MatchReady(addr, P2)]
  │
  ├─ midnight://kicks?match=<addr> (deep link from QR or share)
  │                  ─► [Joining(prefilledAddress=addr, "↑ filled from deep link")]
  │                       (same as JOIN MATCH from here)
  │
  ├─ RESUME MATCH ──► reopen by role:
  │                     P1 → [Creating(address=session.address)]
  │                     P2 → [Joining(prefilledAddress=session.address)]
  │
  └─ PRACTICE VS AI ─► launches Unity choice phase with currentRole=null
                        → MatchManager.playAgainstAi(choices) end-to-end
                        → replay (single device)

[MatchReady(address, role)]
  ✓ BOTH PLAYERS IN — You are P1 / P2
  • CONTINUE → launchUnityChoicePhase() with currentRole=role
  • BACK → [Menu]

[Unity Choice Phase] (UnityPlayerGameActivity, separate Activity)
  • Per-pick role banner: "YOU SHOOT — pick where to kick" or
    "YOU KEEP — predict where they'll kick" (see §2 coordinate
    convention + §4 Per-role choice UI for the framing rationale)
  • L/C/R buttons commit a direction per pick
  • Pick count is round-aligned and role-alternating:
      regulation : 10 picks  (5 shoots + 5 keeps for each player,
                              interleaved by round; P1 sees
                              shoot/keep/shoot/keep/…, P2 sees the
                              flipped pattern)
      SD         :  2 picks  (your shoot + your keep for the pairing)
  • choicesLocked back to Kotlin with the raw flat array; Kotlin
    splits it into shoots[] + keeps[] using the same `roles` it sent

KicksActivity.handleChoicesLocked dispatches by currentRole:
  null     → MatchManager.playAgainstAi(choices)  ── PvAI single-device
  Player.P1 → MatchManager.playAsP1(choices)       ── submit, await P2 commit,
                                                       reveal, await P2 reveal
  Player.P2 → MatchManager.playAsP2(choices)       ── await P1 commit, submit,
                                                       await P1 reveal, reveal

After regulation reveal (both reveals landed, chain phase advanced past
REVEALING — i.e. either COMPLETE or SD_COMMITTING):
  • REGULATION REPLAY (always, regardless of decisive/tied) — players
    must see all 10 kicks resolve cinematically before learning the
    outcome. SD must NOT be announced before the replay.
    – Kotlin: build a RegulationReplay payload from chain snapshot
      (p1Shoots, p1Keeps, p2Shoots, p2Keeps, p1Score, p2Score). The
      contract preserves these across `resetRoundState()`, so they're
      readable even on the post-tie SD snapshot.
    – Trigger: UnityBridge.playReplayCinematic(rounds=regulationRounds,
        p1Score, p2Score, winner=null /* unknown until SD resolves */)
        → Unity 3D cinematic (ShotManager.PlayReplay — built).
    – Compose overlay (live): MatchReplayOverlay now renders a live
      broadcast scoreboard + per-kick GOAL!/SAVED! flashes *over* the
      running Unity cinematic, then the result/celebration end screen.
      (Was a stop-gap row-by-row scoreboard before the cinematic landed.)
    – Unity sends replayComplete → KicksActivity dispatches:
        · phase == COMPLETE (decisive regulation) → winner announce
          beat → claim payout
        · phase == SD_COMMITTING (tied)           → "SUDDEN DEATH —
          ROUND 1" full-screen beat → SD choicePhase to Unity
  • SD picker dispatch (UnityBridge.sendChoicePhase round=suddenDeath)
    must NOT fire until replay dismissal. Players do not learn that SD
    exists until the regulation replay has played.

After every SD round reveal (similar shape):
  • SD round replay — single pairing animation (P1 shoot vs P2 keep,
    P2 shoot vs P1 keep). Same gate as regulation: replay first, THEN
    either winner reveal or next SD round prompt.
  • If decisive → winner announce → claim payout
  • If stalemate → "SUDDEN DEATH — ROUND N+1" beat → next SD choicePhase

On final result (MatchState → Resolved):
  • MatchStore.delete() if PvP (RESUME MATCH disappears from menu)
  • Winner announce beat (full-screen, opaque) — score + winner + payout
    claim CTA. Surfaces only after the relevant replay has played, never
    before.

[Forfeit / Timeout]
  • Each MatchManager.waitFor* helper has a timeout default
    (DEFAULT_OPPONENT_WAIT_MS). On timeout the state machine goes to
    Failed(prev, e) — user retries or backs out. claimTimeout circuit
    + payout flow not yet wired in the UI (covered by the contract
    but not surfaced in the matchmaking screens).
```

### What's NOT in the IA yet

- **Pause / mid-match exit** — Unity pause button hard-kills the process
  (workaround for shared-process ANR); user relaunches from launcher.
  Proper polish item, see PLAN Phase 5.
- ✅ **Regulation replay** — DONE. The Unity 3D cinematic is built
  (`ShotManager.PlayReplay`: run-up → kick → keeper dive → ball flight),
  triggered via `UnityBridge.playReplayCinematic`. The Compose
  `MatchReplayOverlay` now sits *over* the live cinematic — a
  broadcast-style live scoreboard plus per-kick GOAL!/SAVED! flashes —
  rather than the old Compose-only stop-gap scoreboard. Still honors the
  SD-gate per the "regulation replay before SD" rule. Caveat: the
  per-kick `roundResult` emit is a C# change that needs a Unity
  re-export to be live on the shipped binary (degrades cleanly if the
  binary predates it; PLAN Phase 5).
- **SD transition beat** — the full-screen "SUDDEN DEATH — ROUND N"
  moment is still missing. Today the HUD badge label changes but there's
  no full-screen beat, so the SD picker re-appearing can read as "the
  game restarted". (The "YOU WON / YOU LOST" verdict is now covered —
  the result EndScreen renders a verdict badge; see "Result display"
  below.)
- **SD round replay** — single-pairing cinematic between each SD round.
  Same Compose-fallback approach as regulation replay.
- ✅ **Result display** — DONE (no separate screen by design). The
  post-match result/celebration renders as a 2D Compose overlay
  (`MatchReplayOverlay`: ResultHud/EndScreen — verdict badge, final
  score, per-shot shoot-out recap, REMATCH/MENU) drawn over Unity off
  `MatchState.Resolved`. The payout claim CTA lives here. PLAN Phase 4
  records this as "No separate results screen needed."
- **Leaderboard** — still pending. No leaderboard code exists anywhere;
  deferred to v1.1 (PLAN Phase 4).
- **QR scanner** — JoinMatchScreen accepts hex paste / deep link only.
  Google Code Scanner is PLAN Phase 5 polish.

### State polling

While waiting for opponent actions, the Kotlin layer subscribes to the
contract state via the indexer (`MatchManager.awaitContractState` →
`StatePoller`, 3s tick). When the opponent's `committed`/`revealed`
flags change → the suspending `waitFor*` helper returns and the state
machine advances. The poller is started lazily inside the wait window
and torn down on return — no background polling between waits.

### Per-role choice UI

**Decided:** the keep prompt is reframed to match the contract's
coordinate convention. The player picks a goal corner in both roles:

| Role | Banner copy | Meaning |
|---|---|---|
| Shoot | `YOU SHOOT — pick where to kick` | "I'm kicking to *that* corner" |
| Keep | `YOU KEEP — predict where they'll kick` | "I think they'll aim *that* corner" |

Same L/C/R buttons in both roles, same shooter-perspective coordinate
space, same committed value semantics. The keeper's body lean in the
replay is derived from this committed value, not chosen separately —
see `Keeper.cs:Dive` for the mirror animation logic.

Considered and deferred:
- **Visual goal diagram** above the buttons — would eliminate the
  "whose left is left" ambiguity by rendering the target zones. Polish
  item, one PNG/SVG asset + a layout pass. Worth doing pre-launch.
- **Per-role 3D camera + interaction** — shoot rounds show goal from
  behind the ball, keep rounds show shooter from inside the goal,
  player taps regions of the 3D scene. Substantial Unity work,
  post-launch.

The decision locks in: **contract stays shooter-coord, presentation
changes only.**

---

## 5. Unity ↔ Kotlin bridge

UaaL renders Unity full-screen as an Android Activity. Communication via `UnitySendMessage` (Kotlin → Unity) and `AndroidJavaObject` callbacks (Unity → Kotlin).

### Kotlin → Unity messages

```json
// Start choice phase (V3: 10 picks regulation, 2 picks SD)
// `roles` is the per-pick role from THIS device's perspective.
//
// Regulation (10 entries):
//   P1: ["shoot","keep","shoot","keep","shoot","keep","shoot","keep","shoot","keep"]
//   P2: ["keep","shoot","keep","shoot","keep","shoot","keep","shoot","keep","shoot"]
//
// Sudden death (2 entries, same for both players):
//   ["shoot","keep"]
//
// Unity uses roles[i] to label each pick "YOU SHOOT — pick where to kick"
// or "YOU KEEP — predict where they'll kick". Player picks a goal corner
// (shooter-perspective L/C/R) in both roles — see §2 coordinate convention.
{
  "type": "choicePhase",
  "round": "regulation",
  "roles": ["shoot", "keep", "shoot", "keep", "shoot", "keep", "shoot", "keep", "shoot", "keep"]
}

// V3 regulation replay — 10 rounds. P1 shoots rounds 1,3,5,7,9; P2
// shoots rounds 2,4,6,8,10. Each round entry carries the shoot/keep
// pair revealed from chain.
{
  "type": "replay",
  "rounds": [
    { "round": 1,  "shooter": "P1", "shootDir": 0, "keepDir": 2, "result": "goal" },
    { "round": 2,  "shooter": "P2", "shootDir": 1, "keepDir": 1, "result": "save" },
    { "round": 3,  "shooter": "P1", "shootDir": 2, "keepDir": 0, "result": "goal" },
    { "round": 4,  "shooter": "P2", "shootDir": 0, "keepDir": 2, "result": "goal" },
    { "round": 5,  "shooter": "P1", "shootDir": 1, "keepDir": 0, "result": "goal" },
    { "round": 6,  "shooter": "P2", "shootDir": 2, "keepDir": 1, "result": "goal" },
    { "round": 7,  "shooter": "P1", "shootDir": 0, "keepDir": 0, "result": "save" },
    { "round": 8,  "shooter": "P2", "shootDir": 1, "keepDir": 2, "result": "goal" },
    { "round": 9,  "shooter": "P1", "shootDir": 2, "keepDir": 1, "result": "goal" },
    { "round": 10, "shooter": "P2", "shootDir": 0, "keepDir": 0, "result": "save" }
  ],
  "finalScore": { "p1": 4, "p2": 3 },
  "winner": "P1"
}

// V3 sudden death replay — one pairing = two kicks (P1 then P2).
// Each entry represents one of the two kicks in the pairing.
{
  "type": "suddenDeathReplay",
  "rounds": [
    { "round": 1, "shooter": "P1", "shootDir": 2, "keepDir": 2, "result": "save" },
    { "round": 1, "shooter": "P2", "shootDir": 0, "keepDir": 1, "result": "goal" }
  ],
  "winner": "P2"
}
```

### Unity → Kotlin messages

```json
// Player made choices (V3)
//   regulation: 10 entries, indexed by role[i]
//   sudden death: 2 entries
//
// Kotlin splits the array into `shoots` / `keeps` using the same
// `roles` it sent in choicePhase, then builds the contract witnesses:
//   for i in 0..choices.size:
//     if roles[i] == "shoot": shoots.add(choices[i])
//     else:                   keeps.add(choices[i])
{
  "type": "choicesLocked",
  "choices": [0, 1, 2, 0, 1, 2, 0, 1, 2, 0]
}

// Replay finished
{ "type": "replayComplete" }

// Pause requested (user tapped the pause HUD button)
// KicksActivity logs it and updates the menu's status line. Unity
// immediately follows up with Process.killProcess to bypass Unity's
// 10s onDestroy hang on the shared OS process (otherwise KicksActivity
// ANRs while Unity tears down). User relaunches from the launcher.
{ "type": "matchPaused" }
```

---

## 6. Edge cases (V3)

| Scenario | Handling |
|----------|----------|
| Both players pick identical `keeps` matching the other's `shoots` exactly | Score 0-0 → sudden death |
| Both score or both miss in an SD pairing | Non-decisive → next SD pairing |
| Player commits then closes app | Timeout → opponent claims forfeit |
| Both players timeout (neither commits) | Both can reclaim their stake |
| Player tries to reveal wrong preimage | Circuit rejects (commitment mismatch) |
| Player submits invalid direction (>2) | `validDirection()` check fails in circuit |
| Network disconnect during replay | On-chain result unchanged, replay is cosmetic |
| Player opens app after match resolved | Sees result screen, can claim payout |
| Many sudden death pairings (deep tie) | Protocol supports unbounded batches. Each pairing has 3² = 9 possible outcomes and ~33% decisive odds, so deep ties are exceedingly rare. |

---

## 7. V2 → V3 migration ✅ DONE (historical)

> **Completed.** This migration has landed — V3 is the only contract in
> the tree (commit 7370c7f) and is wired end to end. The plan below is
> kept verbatim as a record of the work; it is no longer a TODO. (Note:
> the as-built V3 circuit names are `commitRegulation`/`revealRegulation`
> /`commitSuddenDeath`/`revealSuddenDeath`/`cancelMatch`, not the
> `commitBatch`/`revealBatch` names sketched below — see §3.)

V3 is a contract revision. Implementation order (as executed):

1. **Contract (`penalty.compact`)**
   - Replace `BatchPreimage { c0..c4 }` with `RegulationBatch { shoots[5], keeps[5] }`.
   - Add `SuddenDeathBatch { shoot, keep }` struct.
   - Rewrite `commitBatch` / `revealBatch` for the new preimage shape.
   - Replace the regulation scoring block (lines ~232-237 of V2) with the
     10-round loop from §2.
   - Replace the SD scoring block with the single-pairing model.
   - Bump deployment + tests; the test suite needs full new fixtures.
   - Generate a new contract address; V2 matches are stranded (no on-chain
     upgrade path).

2. **MatchManager (Kotlin)**
   - Replace `localChoice0..localChoice4` witnesses with `localShoots` +
     `localKeeps` (regulation) and `localShoot` / `localKeep` (SD).
   - `submitP1Choices(shoots, keeps)` and `submitP2Choices(shoots, keeps)`
     take both arrays.
   - `playAsP1` / `playAsP2` accept `(shoots, keeps)` not `IntArray`.
   - `toRoundResults` rewrites to walk 10 rounds, alternating shooter,
     reading from the appropriate `shoots`/`keeps` array.
   - `MatchResult.aiChoices` field becomes `opponentShoots` + `opponentKeeps`
     (or split fields). The historical `aiChoices` name is finally retired.

3. **Bridge (Kotlin + Unity)**
   - `UnityBridge.sendChoicePhase` accepts 10-entry `roles` for regulation
     and 2-entry for SD.
   - `KicksActivity.handleChoicesLocked` splits the flat `choices` array
     by `roles` into `shoots` + `keeps` before calling MatchManager.

4. **Unity HUD**
   - `GameController.OnGUI` already drives picks off `roundRoles[]`, so
     extending from 5 to 10 picks is mostly bumping array sizes. Specific
     fixes:
     - The `$"Round {currentChoice + 1} / 5"` label is hardcoded — change
       to `$"Round {currentChoice + 1} / {roundRoles.Length}"`.
     - The choice array `private int[] choices = new int[5];` in
       `StartChoicePhase` must size to `msg.roles.Length` instead of
       a fixed 5.
   - The replay path (`ShotManager.PlayReplay`) iterates over a
     `List<RoundData>` of arbitrary length — already handles 10 rounds
     (and 2-kick SD pairings) without code change. Pacing may need a
     pass; 10 cinematic rounds at ~4s each is ~40s.

5. **PLAN.md**
   - Add V3 contract redeploy + migration as a Phase 4 follow-up (after
     the current two-emulator E2E lands on V2).

> **Outcome:** the migration shipped — V3 is now the canonical (and
> only) contract; V2 has been fully replaced. The original plan noted
> this work could be branch-isolated with V2 as the interim shipping
> target; that interim is over.
