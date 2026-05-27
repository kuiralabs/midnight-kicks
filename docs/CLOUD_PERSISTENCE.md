# Cloud Persistence & Recovery — Investigation

**Status:** design / investigation (2026-05-27). Linked from `PLAN.md` wishlist
**#4** (done — active recovery) and **#30** (the long-term archive + discovery
tier this doc fleshes out).

**Question that started it:** *"Are we storing unfinished contracts so a player
can uninstall → reinstall → restore and pick up their active matches? And for
long-term metadata beyond Block Store's size limit, should we use Drive — maybe
a separate encrypted contract registry to solve discovery?"*

Short answer: **active-match recovery already ships**; the long-term archive +
a Drive-backed **contract registry** for discovery is the next layer. This doc
records what's true today, the target architecture, and a gap-by-gap rundown to
make it better — UX and security.

---

## 1. What ships today (verified)

- `MatchStore` persists matches in `EncryptedSharedPreferences` (Android
  Keystore-backed).
- `MatchStore.snapshotBytes()` / `restoreFromBytes()` round-trip the **active**
  match set — `{address, role, secretKey, nonces, committed picks, flags}` — as
  a versioned JSON blob.
- `MatchStoreBackupProvider` (the SDK's `AppDataBackupProvider`) feeds that blob
  into `SigilBackup.appMetadata`, so it rides into **Block Store right next to
  the seed**. The whole sigil backup is PRF-encrypted by us before upload — the
  cloud sees ciphertext (see the Block-Store-backup notes in agent memory).
- On reinstall → sigil restore, the SDK calls `restore(bytes)` →
  `restoreFromBytes()` repopulates the matches; the resume-aware state machine
  (`resumePlayAsP1/P2`) drives each one from wherever the chain left it.
- Completed matches are **pruned on `Resolved`** (`store.delete`), so the blob
  only ever carries unfinished matches and stays lean.

**So the "uninstall → reinstall → resume active matches" round-trip works now.**
What's missing is (a) an archive for completed-game history/stats/replays, and
(b) a discovery path that doesn't depend on the Block Store blob carrying every
address.

---

## 2. The constraint

Block Store is a **small, transparent, device-transfer** vault — measured in a
few KB, designed for "restore my session on a new device," not archival. The
seed and the active-match witnesses share that budget. Consequences:

- It scales only to a handful of concurrent matches before the blob bumps the
  ceiling.
- It cannot hold history (every completed game, head-to-head stats, replays).
- It re-stores the full *random* witness bytes per match — the bulky part.

---

## 3. Target architecture

```
Tier 0  Chain (indexer)        AUTHORITATIVE. Final truth for every contract's phase/score.
Tier 1  Block Store            seed  (+ active witnesses until §4 derivation lands)
                               small · transparent · no network/consent · the FAST recovery path
Tier 1.5 Drive registry        encrypted [ {address, role, status, opponent, ts}, … ]
                               discovery index + history index · best-effort cache
Tier 2  Drive per-match blobs  completed-game detail / replays · lazy-loaded · referenced by the registry

Witnesses: derived on demand from  seed + contractAddress (+ round)   (§4)
```

**Reconstruction equation:** `{seed} + {registry} + {chain}` rebuilds everything.
The seed (Tier 1) re-derives witnesses; the registry (Tier 1.5) says *which*
contracts to look at; the chain (Tier 0) says what's true for each.

`appDataFolder` is Drive's hidden, per-app, quota-bounded folder — invisible in
the user's Drive UI, doesn't touch their visible files, and is the standard home
for "large, long-term, per-account" app data.

---

## 4. Shrinking Tier 1: derive witnesses instead of backing them up

Today each match's `secretKey` + commit nonces are **random** and must be backed
up. If instead they're **derived** from the seed over a domain-separated path —
`HKDF(seed, "kicks/match" ‖ contractAddress ‖ round)` — then:

- Block Store shrinks to **seed-only** (fixed size, never grows with match count).
- After a seed restore, witnesses for *any* address are recomputed locally.
- The commit-reveal **hiding** property holds: the nonce is unpredictable to the
  opponent (it's a PRF of the secret seed), and unique per `(address, round)`
  so there's no cross-commit reuse. Determinism is fine — you commit once per
  round, and a retry reproduces the identical commitment (idempotent).
- **Domain separation is mandatory:** a salt distinct from the wallet-key
  derivation, so a leaked match nonce can never expose wallet keys.

This is what makes the registry the load-bearing discovery piece (§5): once
Tier 1 no longer carries addresses, *something* must.

---

## 5. Discovery — the real gap

"After a fresh restore, which matches am I in?" Three ways:

| Option | Pro | Con |
|--------|-----|-----|
| Block Store address list | no extra infra; already implicit today | grows Tier 1; lost if we go seed-only |
| Indexer participant query ("contracts where my key is P1/P2") | authoritative, no client cache to sync | **doesn't exist** — needs Midnight indexer support; ecosystem dependency |
| **Drive contract registry** (the recommendation) | infra **we control**; doubles as the history index; survives reinstall | a cache to reconcile; needs Drive consent |

**Recommendation: the Drive registry**, treated as a *best-effort cache* and
**reconciled against chain** on restore (the registry says where to look; the
chain says what's true). It sidesteps the indexer dependency and is the same
file that powers a "past matches" screen.

---

## 6. THE RUNDOWN — gaps & how to make it better

### 6.1 Security

1. **Key derivation / domain separation.** Encrypt the registry + archive with a
   key derived from the sigil via a **dedicated backup salt** (`BACKUP_SALT`),
   never the wallet seed itself. One leaked tier never cascades to another.
2. **Least privilege scope.** Request **`drive.appdata` only** — the app can
   touch *its own* hidden folder, nothing in the user's real Drive. Never
   request full-Drive scope.
3. **AEAD + fail-closed.** AES-GCM (encrypt-then-MAC). A tampered/truncated
   registry must **fail closed** — never act on a partially-decoded list;
   fall back to chain reconcile. Garbage in must not orphan or mis-resume a match.
4. **Rollback / delete resilience.** An attacker with Drive access (but not the
   sigil key) can't *read* the registry, but could **delete or roll it back**.
   Mitigation: the registry is only a cache — chain reconcile recovers active
   matches; the worst case is losing the *convenience index* of old matches,
   never funds or active play.
5. **Account-loss recovery matrix.** Both Block Store and Drive ride the
   Google/Apple account. The escape hatch is the sovereign **BIP-39 mnemonic
   export** (wishlist #24): with §4 derivation, the exported seed re-derives all
   witnesses, so **active matches survive account loss** — only the *discovery
   index* is lost, and that degrades to the indexer query or manual address
   entry. Write this guarantee down; it's the load-bearing self-custody claim.
6. **Witness unpredictability** (see §4) — the only crypto-correctness gate on
   deriving nonces; satisfied by a seed-keyed PRF.
7. **Metadata leakage.** Even encrypted, the registry file's *existence, size,
   and mtime* leak "this user plays Kicks, roughly this often." Opponent
   identity / scores live **inside** the ciphertext. Acceptable, but note it.

### 6.2 UX

1. **Consent timing — lazy & opt-in.** Don't ask for Drive at first launch
   (kills onboarding). The core flow needs **zero Drive** — active recovery is
   Block Store. Surface Drive as an opt-in *"back up my match history & sync
   across devices"* toggle, or on first open of a "history" screen.
2. **Deadline/forfeit SLA — the load-bearing UX constraint.** Active matches
   have an on-chain commit/reveal **timeout**; miss it and the opponent claims
   forfeit. Recovery of an *active* match must therefore beat the deadline —
   which means it must use the **fast path (Block Store: no network, no
   consent)**, never Drive. This is *why* active recovery stays in Tier 1 and
   only history goes to Drive. (Both UX and security: a slow restore = a lost
   match / a forfeit.)
3. **Restore reconcile screen.** After restore: *"Found N active matches,
   checking the chain…"* then route each — resume, or *"this one resolved while
   you were away"* (e.g., opponent forfeited you). Never silently drop one.
4. **Cross-device double-action.** Same sigil on two devices → both can act
   (witnesses derive identically). No client lock needed: the **chain is the
   arbiter** (the contract rejects a second reveal), and the resume-aware state
   machine already treats "already done on chain" as a no-op (wishlist #16/#17).
   The UX just needs to render that gracefully, not error.
5. **Offline writes.** Drive writes fail offline; queue + retry the registry
   append (WorkManager). The match itself never blocks on it (chain + Block
   Store carry the active state); the registry catches up later.
6. **History pruning.** The registry grows forever. Paginate the history screen;
   consider moving very old entries to a separate "cold" Drive blob so the hot
   registry stays small to fetch on restore.

### 6.3 Registry consistency

- **Union-by-address merge on read.** Append-mostly, keyed by contract address →
  merging two devices' registries is a conflict-free union (newest `status` wins
  per address). No transactional append needed; avoids single-file
  last-writer-wins clobber.
- Use Drive **revisions** as a coarse backstop, but rely on the union, not on
  Drive locking.

---

## 7. Open decisions

- **Derive vs back up witnesses (§4)?** Derivation is the scalability + account-
  loss win, but changes how every commit gets its nonce — needs a careful pass
  + tests on the hiding property. Backing up (today) is simpler but caps scale.
- **Registry granularity:** one file (simple, union-merge) vs per-device shard
  files (no merge, more reads). Start single-file + union.
- **When to write archive blobs (Tier 2):** on `Resolved`, before the
  `store.delete` prune, snapshot the finished match to Drive so history survives.
- **Encryption primitive:** confirm the SDK exposes a sigil-derived AEAD key with
  a backup-specific salt, or add one.

---

## 8. Suggested phasing

1. **✅ Done — Block Store active recovery** (wishlist #4).
2. **Witness derivation** (§4) → Tier 1 becomes seed-only; unlocks scale + the
   account-loss guarantee with #24.
3. **Drive registry** (§5) → discovery + history index; opt-in, `drive.appdata`,
   AEAD, union-merge, chain-reconcile on restore.
4. **Drive per-match archive** (Tier 2) → snapshot on `Resolved`; lazy "history"
   screen.

---

## 9. Candidate storage backends (watchlist)

The Drive tiers above are pencilled in as **Google Drive `appDataFolder`** — the
pragmatic default, but it rides the Google account, which is the platform-custody
weakness in §6.1.5. Decentralized, client-encrypted storage is the
self-custody-aligned alternative for **Tier 1.5 (registry) + Tier 2 (archive)** —
it keys data to the *user's* identity (ideally sigil-derived), not a Google
account. It is **archive-only**: decentralized fetch latency disqualifies it from
the active-recovery fast path, which stays on Block Store (the forfeit-deadline
SLA, §6.2.2).

| Candidate | Note |
|-----------|------|
| Google Drive `appDataFolder` | default; rides Google account (platform-custody); fast, free-to-user, mutable, Android-native |
| **dStorage (`dstorage.pro`)** — *watching* | "privacy-first data layer for dApps", SDK-based, decentralized family. Self-custody fit is strong; specifics unknown — verify the criteria below before adopting. |
| IPFS+IPNS / Filecoin / Arweave / Crust | the general decentralized neighborhood, as fallbacks/benchmarks |

**Adoption criteria (must all clear before swapping a tier onto a decentralized
backend):**

1. **Mutability** — the registry needs in-place updates. Content-addressed
   (IPFS) is immutable → requires a mutable pointer (IPNS / contract pointer /
   the service's named-object abstraction). *Make-or-break for the registry.*
2. **Payment model** — does a write cost tokens/gas? User-pays = friction +
   funds requirement; needs a sponsored relay or batched writes.
3. **Android / Kotlin SDK or clean REST** — we're Android-native; a
   JS/browser-only SDK doesn't fit.
4. **Sigil-derivable identity** — can the storage namespace/keypair derive from
   the sigil (one identity, no separate account)? The ideal fit.
5. **Permanence / retention** — persists indefinitely vs re-pin/expire
   (Arweave pay-once · Filecoin deal-renewal · bare-IPFS ephemeral).
6. **Opaque-blob client encryption** — we encrypt with a sigil-derived key
   regardless; backend must take ciphertext with no server-side key custody.
7. **Latency** — confirms archive-only positioning (never active recovery).

`dstorage.pro` is **on watch**, not adopted — the SPA + public docs don't yet
answer 1–7. Re-evaluate when their SDK/docs expose mutability, payment, and an
Android-usable surface.

## See also

- `PLAN.md` wishlist **#4** (active recovery — done), **#30** (this tier),
  **#18** (`WitnessStore` SDK primitive), **#24** (sovereign mnemonic export —
  the account-loss escape hatch), **#16/#17** (resume-aware / idempotent
  protocol — handles cross-device "already done on chain").
