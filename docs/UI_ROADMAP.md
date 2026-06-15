# Midnight Kicks — Roadmap

ZK-powered penalty shootout. Android UaaL. URP, mobile-friendly stylized art.

> Last reconciled against code: 2026-06-14 (HEAD 3bd8e52). Fidelity target is STYLIZED, not photoreal — characters are flat-painted Mixamo meshes, grass/crowd are procedural-or-drop-in textures. Several Unity-side C# changes (per-kick `roundResult` emit, audio cues) require a Unity re-export to take effect on device; source is committed but the shipped binary may lag.

## Done
- [x] Real soccer characters in scene (Shooter, Keeper) — `soccer_character.fbx`; the rigged X Bot survives only as inactive scene backups (`Shooter_XBot_Backup` / `Keeper_old`)
- [x] Mixamo Soccer Game Pack imported, Humanoid retargeting working
- [x] AnimatorControllers wired (Idle, Kick, DiveLeft, DiveRight, JumpCenter)
- [x] ShotManager orchestrates run-up → kick → dive → score
- [x] GameController bridge (choicePhase, replay, suddenDeathReplay, status)
- [x] BallKicker physics + Keeper.cs dive (side-tilt fake-animation hook)
- [x] Midnight lighting & post-FX baseline
- [x] Migrated into midnight-kicks repo

## Phase 1 — Environment
- [x] **Real soccer ball** — 0.22m regulation, not a primitive sphere
- [x] **Real goal + net** — FIFA proportions (7.32×2.44m); update keeper/ball offsets to match
- [x] **Grass pitch with painted markings** — penalty box, spot, arc (`GrassPitch.cs` + `PitchMarkings.cs`; procedural mowing-stripe/grass texture fallback unless a real grass photo is dropped in)
- [x] **Stadium backdrop + floodlights** — fills the void, sells "midnight" (curved panoramic crowd arc via `CrowdBackdrop.cs` + daylight lighting via `StadiumFloodlights.cs`). NOTE: crowd is an image wall only — no modeled stadium architecture (roof/stands/scoreboard)

## Phase 2 — Animation & cinematic
- [x] **Choreographed 3D cinematic** — run-up → kick → keeper dive → ball-to-net/parry, with procedural goal/defeat reactions (`ShotManager.PlayRound`/`PlayReplay`, `BallKicker.cs`, `Keeper.cs`). STYLIZED: scripted kinematic (non-physics) ball; shooter has only Idle/Run/Kick states; reactions are procedural, not authored celebration clips
- [~] **Verify dive directions** — left vs right may need Mirror; goal/save/miss reaction states (FallenIdle chained; standing up + dive-direction mirror pending)
- [~] **Multi-camera director** — single aspect-aware behind-shooter camera with intro dolly is wired (`CameraSetup.cs` + `ShotManager.PlayIntro`), reads FC25-style; the Cinemachine multi-camera broadcast director (wide / kick close-up / behind-keeper) is unbuilt and parked
- [x] **Lighting refinement** — daylight pitch lighting via `StadiumFloodlights.cs` (note: shipped as daylight rather than the original night-skybox/floodlight-pool concept)

## Phase 3 — UI

> ARCHITECTURE NOTE: UI was deliberately delivered as a **2D Compose (Kotlin) overlay over Unity**, not Unity uGUI — Unity is pure 3D choreography (`ShotManager` has no `OnGUI`). The uGUI-specific items below are intentionally NOT pursued in Unity; the functionality below them is built in Compose.

- [ ] **uGUI choice phase** — replace IMGUI direction buttons (superseded — choice phase handled in Compose, not uGUI)
- [x] **HUD — score, round counter, GOAL/SAVE punch** — built as a Compose overlay: live `LiveScoreboard` (per-shot pips + running tally) and spring-punch `GOAL!`/`SAVED!` `KickFlash` (`MatchReplayOverlay.kt`), driven by Unity per-kick `roundResult` events. CAVEAT: the per-kick emit is a dormant C# change pending a Unity re-export; degrades cleanly to nothing on older binaries
- [x] **Result screen** — winner, round summary, return-to-Kotlin: Compose result/celebration end screen (verdict badge, final score, per-shot recap, REMATCH/MENU) via `MatchReplayOverlay` `ResultHud`/`EndScreen`. A *dedicated 3D results scene* specifically does not exist (pending/by design — none needed)

## Phase 4 — Audio
- [x] **Core SFX** — kick, net, drum-strike + 3 random crowd roars + goal/save/match-end cues (`AudioManager.cs`, files committed under `Resources/Audio`). CAVEAT: Unity-side cues need a re-export to be audible on device
- [x] **Mix + ambient loop** — looping La Bombonera drum-chant bed under the match (`AudioManager.cs`), plus a lobby theme on menu resume (`LobbyMusic.kt`, `res/raw/intro_theme.mp3`)

## Phase 5 — Integration & ship

> RE-EXPORT DEPENDENCY: several committed C# changes (per-kick `roundResult` emit, audio cues) only take effect after a Unity re-export of the UaaL binary — owner = the Unity toolchain. Source is committed; the shipped binary may lag until re-exported.

- [ ] **Bridge protocol verification** — Editor mocks for every message type
- [ ] **Android build + on-device test** — UaaL export, real device flow (app runs on device; an automated two-device / two-emulator on-chain E2E test does not exist — the headline "two-emulator E2E green" is a one-off manual run, and the only instrumented test stubs the chain seams)
- [ ] **In-app QR scanner** — camera/barcode scan to join a match (QR *generation* exists in `CreateMatchScreen`; the scanner is still Phase 5 polish — opponent currently uses an external scanner or pastes the address)
- [ ] **Performance + APK size** — IL2CPP, ASTC, light probes, shader stripping
- [ ] **Final polish pass** — bugs, timing, screenshots

## Cross-cutting (touch at any session)
- Code: extract magic numbers into a tuning ScriptableObject; replace runtime `Find*` with serialized refs
- Git: one session → one branch → one merge; never commit half-broken state
- Verify: press `T` (mock replay hotkey) after every change

## Important concepts to preserve
- **+Z is forward in Unity** — shooter faces +Z toward goal, keeper faces -Z toward shooter
- **Tripo origins are baked at body center** (Y≈0.5 needed); **Mixamo origins are at feet** (Y=0)
- **Humanoid retargeting** is the bridge between Mixamo clips and our character avatar — both sides need Rig→Humanoid + Avatar
- **Static GLB prefabs cannot accept bone animation** — only the rigged X Bot can
- **Bridge is JSON over UnitySendMessage / AndroidJavaClass** — Kotlin owns game logic, Unity is render/animation slave
- **Editor `.unity` and `.prefab` files are YAML** — hand-edits are possible but every prefab GUID swap requires re-anchoring fileIDs (see `unity-study-notes.md`)
