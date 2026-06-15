# Midnight Kicks

ZK-powered penalty shootout for Android. Unity 3D + Kotlin (UaaL) + a Midnight Compact contract acting as the referee.

Two players. Five rounds. Commit-reveal so neither side can cheat. Designed to ship around the **FIFA World Cup 2026** (June 11 - July 19).

This repo is a separate GitHub project that consumes the Kuira Android SDK as a pre-built AAR (published under `io.github.kuiralabs` / `kuiralabs.github.io`) — proving the SDK can stand on its own.

---

## Documentation map

Four documents, four concerns. Look at the one that matches what you're doing.

| Document | Use when you need… | Update cadence |
|---|---|---|
| **[`docs/PLAN.md`](docs/PLAN.md)** | Project journal — concept, target dates, architecture, **progress checklist**, SDK friction log, decision log | Per milestone |
| **[`docs/GAME_DESIGN.md`](docs/GAME_DESIGN.md)** | Specification — match state machine, batch commit-reveal protocol, contract circuits, screen IA, Unity↔Kotlin bridge JSON, edge cases | When the game rules or bridge protocol change |
| **[`docs/VISUAL_ROADMAP.md`](docs/VISUAL_ROADMAP.md)** | Plan for lifting visuals toward FC25 / FIFA quality — tiered asset drop-ins, lighting & post-FX, stadium environment, polish. The "what would it take" list | When a visual milestone closes or priorities shift |
| **[`docs/UI_ROADMAP.md`](docs/UI_ROADMAP.md)** | Visible-work checklist — Unity asset phases (environment, animation, UI, audio, ship). Frequently edited, low ceremony | Per session |

**Quick navigation:**
- *"How does the match flow?"* → GAME_DESIGN §1 (state machine)
- *"How is cheating prevented?"* → GAME_DESIGN §2 (commit-reveal)
- *"What's the contract API surface?"* → GAME_DESIGN §3 (circuits)
- *"What's left to ship?"* → PLAN (Phase tracker) + UI_ROADMAP (Unity work)
- *"Why does the SDK do X?"* → PLAN (SDK friction log + Decision log)
- *"What would it take to look like FIFA?"* → VISUAL_ROADMAP (tiered asset/lighting/audio plan)

---

## Status snapshot

Last updated by writer of this README. Source of truth lives in PLAN and UI_ROADMAP — check those for fine-grained state.

**Last reconciled against code: 2026-06-14 (HEAD 3bd8e52)**

**Project milestones (from `docs/PLAN.md`):**
- ✅ Phase 1 — Compact contract (penalty.compact **V3** — symmetric 10-round shootout, 7 proof circuits, 43 `it()` tests; deployed on-demand at runtime, not a fixed address)
- ✅ Phase 2 — Midnight Android SDK (validated 2026-04-28)
- ✅ Phase 3 — Unity + Kotlin integration (replay system, MatchManager, StatePoller, PvP wait helpers)
- 🔄 Phase 4 — Full two-player game (matchmaking create/join/deep-link, per-role PvP orchestrators, on-chain polling, results display, and **cross-process resume via encrypted MatchStore all landed**; still pending: an automated two-device on-chain E2E test — the existing "E2E" test stubs the chain seams — and the on-chain leaderboard, deferred to v1.1)
- ⏳ Phase 5 — Polish + release (Unity in separate process, uGUI choice phase + per-role visual design, in-app QR scanner)
- ⏳ Phase 6 — Launch

**Unity asset roadmap (from `docs/UI_ROADMAP.md`):**
- ✅ Phase 1 — Environment (ball, goal, grass pitch + FIFA markings, curved stadium crowd backdrop, daylight lighting — all wired in code)
- 🔄 Phase 2 — Animation & cinematic (choreographed 3D cinematic — run-up/dive/ball-to-net + live GOAL/SAVED overlay — built; multi-camera director still pending, single fixed camera today)
- 🔄 Phase 3 — UI (result screen shipped as a 2D Compose overlay — verdict/score/recap/REMATCH-MENU; uGUI choice phase + uGUI HUD still pending)
- ✅ Phase 4 — Audio (La Bombonera match mix + lobby theme wired; needs a Unity re-export to be audible on device)
- ⏳ Phase 5 — Integration & ship

---

## Project layout

```
midnight-kicks/
├── README.md                ← you are here
├── docs/
│   ├── PLAN.md              ← project journal + progress
│   ├── GAME_DESIGN.md       ← spec
│   ├── UI_ROADMAP.md        ← Unity visible-work checklist
│   └── VISUAL_ROADMAP.md    ← FC25-tier visual polish plan
├── app/                     ← Kotlin Android app (game logic, SDK consumer)
├── unity/                   ← Unity project (3D stadium, replay, choice UI)
├── unityLibrary/            ← Exported UaaL artifact (generated, gitignored where appropriate)
├── contract/                ← Compact contract source + tests
└── build-kicks.sh           ← End-to-end pipeline: Rust FFI → SDK AARs → Unity sync → APK
```

---

## Build

```bash
./build-kicks.sh
```

Picks up newer Unity exports from `unity/build/android-export/` automatically. To refresh Unity content: open `unity/` in Unity Editor → menu **Midnight Kicks → Export Android Library**, then run the build script. See PLAN §Architecture for the full pipeline.
