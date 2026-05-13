# Midnight Kicks — Visual Roadmap

**Goal:** Lift the game's visual quality toward FC25 / FIFA-style penalty-kick
presentation. Use this doc to plan asset acquisition, art passes, and
lighting work after the gameplay loop is locked in.

**Status:** Phase 1 environment is in place (procedural grass, true-FIFA
pitch markings, flat crowd backdrop with tileable texture, daylight
lighting, FC25-style camera framing). Everything below this point is
incremental polish.

---

## Where we are vs FC25

Honest gap analysis:

| Element | Now | FC25 |
|---|---|---|
| Field surface | Procedural mowing-stripes texture on a Plane primitive | High-res grass photo + normal map, animated 3D blades near camera |
| Pitch markings | Procedural LineRenderers, true FIFA dimensions | Baked into the field texture, slightly worn/dirty |
| Goal | Imported FBX, white materials, no physics | Detailed mesh, cloth-physics net that ripples on impact |
| Ball | Default + texture | High-poly with hex/pent panel pattern, motion blur, deformation |
| Players | Mixamo X Bot (one generic robot mannequin, recolored) | Photo-scanned individual players in team kits |
| Animations | 3 states (Idle/Run/Kick), no celebration | Dozens of states with proper transitions, ragdoll on falls |
| Crowd | Tiled photo on a flat backdrop wall | Animated 3D crowd with section colors, banners, choreographed waves |
| Stadium structure | None — flat backdrop | Full architectural model: roof, trusses, scoreboards, tunnels, ad boards |
| Sky | Solid color | HDRI photo with real cloud detail |
| Lighting | Daylight directional + flat ambient | Time-of-day system, real-time floodlights, baked GI, volumetric cones |
| Post-processing | Disabled (was crushing brightness) | Tuned tonemapping, color grade, subtle bloom on net hits, DoF on close-ups |
| Cameras | Single Main Camera, fixed angle | Cinemachine multi-camera director with broadcast cuts |
| Audio | None | Crowd roar, kick thump, post clang, net swish, whistle, commentary |
| UI | IMGUI text labels | uGUI canvas with score, time, lower-third punch graphics |

The gap is **mostly assets**, not code. Procedural work hits a ceiling — beyond
this, every win needs a model, texture, or animation clip.

---

## Tier 1 — Quick wins (asset drop-ins)

Lowest effort, often biggest visual jump per hour.

### 1A. Better grass texture
- **What:** Replace the procedural mowing-stripes with a real photo + normal map.
- **Drop-in path:** `unity/Assets/Resources/GrassField.png` (script already wired in `GrassPitch.cs`, falls back to procedural).
- **Sources:** [ambientcg.com](https://ambientcg.com) (search "grass", "lawn"), [polyhaven.com](https://polyhaven.com) (search "grass"), [texturecan.com](https://www.texturecan.com), [freepbr.com](https://freepbr.com)
- **Pick:** Top-down or slightly-angled real grass, seamless tile, 1024×1024 or 2048×2048. Bonus: a "stadium pitch" texture with mowing stripes baked in.

### 1B. Better goal model
- **What:** Replace the current goal FBX with a sharper mesh (cleaner frame edges, proper net topology).
- **Sources:** [Sketchfab](https://sketchfab.com) (filter Downloadable + CC), search "football goal" / "soccer goal frame".
- **Drop-in path:** Replace `unity/Assets/Models/Goal/footbal_goal.fbx`, keep the GameObject named `footbal_goal` in the scene so `GoalEnhancer.cs` still finds it.

### 1C. Better ball model + texture
- **What:** Real FIFA-style soccer ball with hex/pent panel pattern.
- **Sources:** Sketchfab (search "soccer ball"), or many free PBR balls on the Unity Asset Store.
- **Drop-in:** Replace the Ball GameObject's mesh + material. Keep the `BallKicker` script attached.

### 1D. Real player models  ⭐ (biggest visual jump for least effort)
- **What:** Swap the Mixamo X Bot (generic robot) currently used for both `Shooter`
  and `Keeper` for real-looking characters in soccer kits.
- **Why high impact:** Players are on-screen the entire match, foreground, large.
  Going from robot mannequin to recognizable footballer transforms perception
  of the game.

- **The existing project prefabs (`Shooter_Professional`, `Shooter_HQ`, `Goalkeeper_V3`,
  etc.) can't be used as-is.** They reference `.glb` files from Tripo3D
  (the AI 3D-model generator — node names start with `tripo_node_*`). Inspecting
  the GLB binary: **skins = 0, animations = 0**. They are pure static meshes
  with no skeleton and no bone weights, which means the `ShooterController` /
  `KeeperController` Animator state machines can't deform them. The `_Archive/`
  folder of earlier versions confirms we hit this wall before and ended up
  keeping the rigged Mixamo `X Bot.fbx` for both Shooter and Keeper.

- **Two real paths forward** (do one, not both):

  **Path A — Pre-rigged Mixamo character (recommended).** Free with Adobe ID.
  Mixamo's character library has ~70 humanoid characters that already have the
  same Humanoid rig X Bot uses, with the mesh skinned to it. The existing
  `ShooterController` / `KeeperController` animation clips work without any
  rebinding. Workflow: mixamo.com → Characters tab → pick (e.g. Adam, Liam,
  Lewis) → Download FBX Binary, T-pose, With Skin → drop into
  `Assets/Models/` → set Rig type to Humanoid → replace the `Shooter` and
  `Keeper` GameObjects in `SampleScene.unity` with the new model, reassign the
  ShooterController/KeeperController, uncheck Apply Root Motion. ~10 minutes
  per character.

  **Path B — Mixamo Auto-Rigger on the existing Tripo meshes.** Honors the
  AI-generated likenesses we already produced, but adds a conversion step
  (Mixamo doesn't accept GLB) and an Auto-Rigger pass with manual marker
  placement. Result quality depends on the Tripo mesh's anatomical accuracy.
  Workflow: GLB → FBX (via Blender's glTF Import + FBX Export, or an online
  converter) → mixamo.com → Upload Character → place 6 markers (chin, wrists,
  elbows, knees, groin) → wait for auto-rig → Download FBX, T-pose, With Skin
  → drop into Unity, set Rig to Humanoid → replace in scene. ~25 minutes per
  character.

  Recommendation is Path A — Path B can be retried later if specific Tripo
  characters are needed for branding reasons.

---

## Tier 2 — Animation & feel

### 2A. Better Mixamo animations
- **What:** Swap the current Idle/Run/Kick clips for more polished versions.
- **Sources:** Mixamo has dozens of soccer-specific clips: `Soccer Pass`, `Soccer Header`, `Soccer Slide Tackle`, etc.
- **Workflow:** Download in-place animation, assign to `ShooterController.controller` state.

### 2B. Goal net cloth physics
- **What:** Net ripples and deforms when the ball hits it.
- **Approach A (proper):** Unity `Cloth` component on the net submesh, with pin-points at the frame edges and a sphere-collider on the ball.
- **Approach B (cheap):** Vertex shader that animates the net's vertices based on ball proximity. Less convincing but no physics cost.

### 2C. Particle effects
- **What:** Grass kicked up at the kick, dust at slide tackles, confetti on goal celebration.
- **How:** Unity `ParticleSystem` GameObjects, triggered from `ShotManager.cs` at the right moment.
- **Cost:** Free; comes with Unity.

### 2D. Animated crowd
- **What:** The static crowd texture wobbles slightly to feel alive.
- **Approach A:** Vertex displacement on the wall mesh with low-amplitude sine over time.
- **Approach B:** A short video clip as the wall texture (Unity `VideoPlayer`).
- **Approach C:** Two crowd textures, alternated every few frames for a "wave" effect.

---

## Tier 3 — Lighting & post-FX

### 3A. HDRI skybox  ⭐
- **What:** Replace the flat sky color with a real photographic sky.
- **Why high impact:** Reflections on the ball, ambient color, and any visible sky region all read as "real" instantly.
- **Sources:** [polyhaven.com/hdris](https://polyhaven.com/hdris) — free CC0 HDR photos. Pick a daylight stadium or open-sky one.
- **Workflow:** Drop the `.hdr` into `unity/Assets/Skyboxes/`. Lighting Settings → Skybox Material → assign. Update `StadiumFloodlights.cs` to NOT override the skybox.

### 3B. Cinemachine multi-camera director  ⭐
- **What:** Multiple cameras (wide stadium shot, behind-shooter, kick close-up, behind-keeper, slow-mo replay) that cut between angles automatically during the match.
- **Why high impact:** Single camera = static game feel. Cuts = broadcast feel.
- **How:** Install Cinemachine via Package Manager. Create `CinemachineVirtualCamera` objects for each angle. Use `CinemachineBrain` to blend between them. Drive cuts from `ShotManager.cs` with `priority` swaps.

### 3C. Tuned post-processing
- **What:** Re-enable the URP Volume after dialing in: color grading, subtle bloom on bright objects (white goal, sky), depth of field on close-up shots, slight vignette.
- **How:** Unity Editor work, no code. Edit the existing Global Volume profile in the scene.
- **Caveat:** This is what blew us up earlier — the existing Volume was crushing brightness. Tune carefully and test on device.

### 3D. Volumetric floodlights (night matches)
- **What:** Visible cones of light coming down from stadium towers, with fog/dust catching the beams.
- **How:** URP Volumetric Fog (`Universal RP > Renderer Features`), or custom shader, or simple fake cone meshes with additive shader.

---

## Tier 4 — Stadium environment

### 4A. Real stadium 3D model  ⭐ (biggest single visual change available)
- **What:** Replace the flat crowd wall with a properly modeled stadium — roof trusses, multiple stand tiers, scoreboards, tunnels.
- **Why huge impact:** Most of the scene's "feel" comes from the environment. A real stadium model puts everything else in context.
- **Sources:** Sketchfab (search "soccer stadium", filter CC + downloadable), Unity Asset Store ("Soccer Stadium" packs ~$20-30), free models on TurboSquid / OpenGameArt.
- **Workflow:** Drop FBX into project, place around the pitch, scale to fit. Delete the procedural `CrowdBackdrop` since the model now contains the stadium.

### 4B. LED advertising boards
- **What:** Animated scrolling LED ad boards along the goal lines and side lines.
- **How:** Quad meshes with an unlit shader that scrolls a texture. Drop in a row of sponsor logos.
- **Where:** Free LED-ad-board shaders on Unity Asset Store.

### 4C. World-space scoreboard
- **What:** A large jumbotron-style scoreboard visible in the stadium showing P1 / P2 / round / score.
- **How:** World-space Canvas + TextMeshPro. Mount on the back wall or hang from rafters.

---

## Tier 5 — Polish

### 5A. Audio  ⭐
- **What:** Crowd roar (loops), kick thump, post clang, net swish, whistle, save grunt, optional commentator-style snippets.
- **Why high impact:** Audio fills the missing 50% of "production value". Watching the same animation with vs without crowd noise feels completely different.
- **Sources:** Free CC0 SFX: [freesound.org](https://freesound.org), [opengameart.org](https://opengameart.org). Crowd loops searchable as "stadium crowd ambience".
- **How:** Drop WAV/MP3 files into `Assets/Audio/`, AudioSource components on relevant GameObjects, trigger via existing `ShotManager.cs` callbacks.

### 5B. Match-flow uGUI
- **What:** Replace the `OnGUI` text labels (`GET READY`, `PLAYER 1 WINS!`, score, feedback) with a proper uGUI Canvas with TextMeshPro, slide-in/slide-out animations, lower-third punch graphics.
- **Why:** IMGUI looks placeholder-y. uGUI = "this is finished software".
- **How:** Unity Editor: Canvas → World/Screen Space → TextMeshPro objects → Animator for entry/exit transitions.

### 5C. Replay system with multiple angles
- **What:** After each shot, replay it from 2-3 cinematic angles (already half-built via `ShotManager.PlayReplay` + the planned Cinemachine work in 3B).
- **How:** Cinemachine virtual cameras + a coroutine that cycles them with timed cuts.

---

## The 80/20 — if we do five things, do these

Picking the items where effort × impact is highest:

1. **1D — Real player models from Mixamo** (1-2 hours)
2. **3A — HDRI skybox from Poly Haven** (30 minutes)
3. **3B — Cinemachine multi-camera replays** (1-2 days)
4. **4A — Real stadium 3D model** (1 day to find + integrate)
5. **5A — Audio (crowd loop + 4-5 SFX)** (2-4 hours)

These five together get us probably **80% of the way to FC25-level feel**, without
custom shaders, art skills, or paid assets. Three are pure drop-ins (1D, 3A, 5A);
two need Unity Editor scene work (3B, 4A).

---

## What we deliberately won't do

- **Custom shaders for grass blades / cloth / water.** Asset packs already do this better than we'd hand-roll.
- **Photoreal player faces.** Mixamo characters are good enough; face scanning is out of scope.
- **Real-time match commentary AI.** Cool idea, separate project.
- **Physics-based ball flight.** The current arc is gameplay-sufficient. A real ball trajectory simulation is a rabbit hole.

These can come back into scope if the project warrants it, but they're not on
the path to "looks like FIFA".
