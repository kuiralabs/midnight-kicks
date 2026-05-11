# Unity Study Notes — Midnight Kicks

Notes from a research pass on the Unity systems we're using, plus an asset inventory of what's actually in this project. Goal: stop guessing.

---

## Topic 1 — Scene file YAML serialization

### Practical rules
- Each top-level object: `--- !u!<classID> &<fileID>`. The `&fileID` is a scene-local 64-bit anchor — referenced only inside this file.
- References: `{fileID: N}` (same file) or `{fileID: N, guid: <32hex>, type: T}` (cross-file). `type: 2` = editor asset (AnimatorController, Material, Mesh), `type: 3` = script/prefab/imported asset.
- Class IDs we touch: 1=GameObject, 4=Transform, 23=MeshRenderer, 33=MeshFilter, 54=Rigidbody, 64=MeshCollider, 65=BoxCollider, 95=Animator, 104=RenderSettings, 108=Light, 114=MonoBehaviour, 1001=PrefabInstance, 1660057539=SceneRoots.
- A GameObject lists its components in `m_Component:` by fileID. Each component back-references via `m_GameObject: {fileID: ...}`. Both sides must match.
- Transform parent/child: `m_Father` + `m_Children` — must be consistent on both sides. Roots have `m_Father: {fileID: 0}`.
- The `SceneRoots` block (always last, anchor `&9223372036854775807`) lists scene root **Transforms** by fileID — but for prefab instances, it references the **PrefabInstance fileID**, not its Transform. Editor keeps this in sync; hand-edits don't.

### Foot-guns
- Hand-edits that don't update `SceneRoots` cause objects to vanish or duplicate.
- Component anchors must round-trip through `m_Component` lists; orphans don't show in Inspector but still exist.
- Wrong `type:` on a reference (script ref where editor asset expected) → silent missing reference.

---

## Topic 2 — Prefab system (PrefabInstance YAML)

### Practical rules
- A `!u!1001 PrefabInstance` is the only in-scene representation of an instanced prefab. Unity reconstructs the GameObjects/components at load time from `m_SourcePrefab`.
- Each override has 4 fields:
  - `target: {fileID: <prefab-internal-id>, guid: <prefab-guid>, type: 3}` — fileID anchors *inside the source prefab file*, NOT the scene.
  - `propertyPath` — serialized name (`m_LocalPosition.x`, `m_Name`, `m_Materials.Array.data[0]`).
  - `value` — primitives/strings.
  - `objectReference` — for object refs (else `{fileID: 0}`).
- `m_AddedComponents`: adds a component to a prefab object in this instance. `targetCorrespondingSourceObject` = prefab GO it attaches to. `addedObject` = scene-side component anchor.
- "Stripped GameObjects" (`!u!1 &xxx stripped`): placeholder anchors so scene-side things can target instance objects. Need both `m_CorrespondingSourceObject` and `m_PrefabInstance`.

### Foot-guns specifically for me
1. **Swapping prefab GUID without re-anchoring overrides.** Every `target.fileID` and `targetCorrespondingSourceObject` is an ID *inside the old prefab*. New prefab has different internal fileIDs → all overrides silently miss. *I did this when swapping rigged → Professional. I updated the fileIDs but should always verify by opening the new prefab and matching.*
2. Stripped GameObjects pointing to a non-existent `m_PrefabInstance`.
3. SceneRoots desync after adding/removing root objects.
4. Mismatched `m_Component` list vs actual components.
5. Wrong `type:` on references.

---

## Topic 3 — Transform & Quaternion semantics

### Practical rules (the part I kept getting wrong)
- `qA * qB` applied to a vector: **qB applied first, then qA**. Read right-to-left.
- **World-space rotation delta** (e.g., tilt around world X): `newRot = worldDelta * existingRot` (premultiply).
- **Local-space rotation delta** (around object's own axes): `newRot = existingRot * localDelta` (postmultiply).
- `Transform.Rotate(angles, Space.World)` = premultiply. `Space.Self` = postmultiply (default).
- Parent-child composition: `worldRot = parentRot * localRot`, `worldPos = parentRot * (parentScale ⊙ localPos) + parentPos`.
- `Quaternion.Euler(x,y,z)` builds in **Z, then X, then Y** order. Don't compare quaternions by component (`-q == q` rotationally); use `Quaternion.Angle(a,b)` or `|Dot(a,b)|`.

### Why I had it wrong
I was using `existingRot * Quaternion.Euler(lean, 0, 0)` and expecting world-space tilt. That's local-space — interpreted in the object's frame, which on our rotated shooter (rotated +90° Y) gives a sideways tilt instead of forward lean. Fix: premultiply for world delta. *(Already corrected in `ShotManager.cs` and `Keeper.cs`.)*

---

## Topic 4 — GLB / FBX import + Tripo specifics

### What our Tripo prefabs actually look like
Confirmed structure: **root GO (identity) → "scene" (baked +90° Y rotation) → "tripo_node_<guid>" (identity, holds MeshFilter/MeshRenderer)**.

### Why the +90° on "scene"
- glTF coordinate convention: +Y up, **−Z forward**. Unity: +Y up, **+Z forward**. The importer bakes a rotation on an intermediate node to convert.
- Plus Tripo's own export orientation lands at +90° Y on the "scene" node.
- This is *baked into a transform*, not into vertex data, so we can't undo it by editing the mesh.

### Why feet are below the ground at y=0
- Tripo bakes the model with bounding-box center near the GLB origin.
- Plus the +90° "scene" rotation means the mesh's local Y=0 cuts through the torso.
- **Result**: at scene Y=0, the model appears with origin at body center (~hip/waist), and feet are below the ground.
- **Verified value**: Y=0.5 puts feet at ground for our Tripo characters.

### Why the static Professional models cannot accept Mixamo animations directly
- The Tripo GLBs imported are *static meshes*. No skeleton, no SkinnedMeshRenderer, no Avatar.
- Mixamo retargeting requires the target character to have a **Humanoid Avatar** with all 15 required bones (hips, spine, chest, neck, head, both shoulders/upper arms/lower arms/hands, both upper legs/lower legs/feet).
- The original *rigged* prefabs (`ShooterPlayer.prefab`, `Goalkeeper 1.prefab`, etc.) DO have a 45-bone skeleton — these can potentially be set to Humanoid.

### Recommendations
1. **Don't fight the +90° at the root** — wrap the imported prefab in a clean root with `localRotation = Quaternion.Euler(0, -90, 0)` to cancel it. Move gameplay scripts onto the outer root. Or, ideally, reimport via Blender (Object → Apply → Rotation, re-export FBX) so the bake is gone.
2. **Don't hardcode Y=0.5 forever.** Write a one-off editor script that reads `GetComponentInChildren<Renderer>().bounds.min.y` and computes the foot offset. Saves us from guessing on every new model.
3. **For Mixamo**: only the rigged 48 KB prefabs are usable as targets. The static 4.5 KB ones (Professional, V3, HQ, Final) need to be sent through Mixamo's auto-rigger first to gain a skeleton.
4. Set Scale Factor=1, Convert Units=on in Model tab (default for meters).

---

## Topic 5 — Animator + Humanoid retargeting

### Practical rules
- Only **Humanoid** retargets across rigs. Generic only matches identical bone hierarchies.
- Both source (Mixamo clip) and target (our character) need their own Avatar — generated with `Rig → Humanoid → Create From This Model`.
- Required bones: 15 (listed above). Configure Avatar must show all green.
- T-pose: enforce in Configure Avatar before saving, or limbs twist at runtime.
- Root motion: keep `Apply Root Motion` off for in-place clips; let scripts drive position.

### Step-by-step Mixamo retargeting
1. Mixamo `.fbx` → Inspector → Rig → Animation Type **Humanoid** → **Create From This Model** → Apply.
2. Tripo character's `.fbx` (or its source GLB if rigged) → same: Humanoid → Create From This Model → Configure → verify 15 green bones → Done.
3. Drag the AnimationClip *child* of the Mixamo FBX into the AnimatorController state (not the FBX itself).
4. On the character GO: assign Avatar (the Tripo Avatar) and Controller. Apply Root Motion off.
5. Per-clip Animation tab: set `Bake Into Pose` for Root Y on jump/dive clips so feet stay grounded after retarget.

### Project-specific notes
- `KeeperController` and `ShooterController` both have an empty second layer named `"Base"` with weight 0 and no states — dead weight, can delete.
- Neither has parameters or transitions yet — states are entered via `Animator.Play(name)` from script.
- The existing `.anim` files (`SoccerKick.anim`, `GoalkeeperDiveLeft.anim`, etc.) animate humanoid muscle space (Float curves on attribute IDs) — they ARE Humanoid clips. They just won't drive anything until the *target* characters have Humanoid Avatars set up.

---

## Topic 6 — URP rendering pipeline

### Project setup (verified)
- URP package `com.unity.render-pipelines.universal@17` (Unity 6).
- `PC_RPAsset.asset` and `Mobile_RPAsset.asset` = pipeline settings (HDR, MSAA, shadows, render scale, default volume profile).
- `PC_Renderer.asset`, `Mobile_Renderer.asset` = Renderer Data (Forward / Forward+ / Deferred, renderer features).
- `SampleSceneProfile.asset` = the default Volume Profile referenced from both RP Assets.

### Volume Profile contents (current)
- **Bloom**: threshold 0.85, intensity 0.6, scatter 0.5, HQ filtering on.
- **Vignette**: dark-blue color (0.02, 0, 0.05), intensity 0.4.
- **Tonemapping**: mode 2 (ACES).
- **MotionBlur**: inactive.

### Mobile RP Asset
- Already mobile-tuned: render scale 0.8, Forward (not Forward+), shadow cascades 1, no opaque/depth textures, soft shadows off, additional-light shadows off.

### Recommendations for Android UaaL target
- For perf: drop Bloom intensity to ~0.3 (0.6 blooms every spec). Keep ACES if Unity owns final color, switch to Neutral if the host app does its own color management (avoids double-tonemap).
- Camera Clear Flags: if scene fully covers screen, Solid Color or Don't Care beats Skybox on tile-based mobile GPUs.
- HDR: keep on (we use it for ACES + bloom thresholds), but `m_HDRColorBufferPrecision` could explicitly be R11G11B10 to save bandwidth vs FP16.

---

## Topic 7 — Scene view vs Game view vs Editor camera

Things I should have known to read screenshots correctly:
- **Scene view** = the editor's 3D workspace. Includes a grid, gizmos (sun icon, camera icons), navigation handles, and uses the **editor camera** which the user moves around for editing. **Not what the player sees.** The grid is a pure overlay (default y=0 plane, drawn in editor-only).
- **Game view** = what the actual `Main Camera` renders. No grid, no gizmos, no editor sun icons. This is what ships.
- **Play mode** (▶ button) = run the game. Game view becomes live; physics, scripts, and animations run.
- The "blue plane with squares" in screenshots is the editor grid — *not the field*.
- The Field GameObject in our scene is a Unity Plane primitive (10×10m at scale 1, scaled 3×3 in our scene) at y=0.

When asking the user about visuals, always specify whether to look at Scene view (for editing) or Game view / Play mode (for what the player sees).

---

## Asset Inventory — Models/

22 character/goal prefabs in `Assets/Models/`, but most are duplicates or broken.

### Tier 1: Rigged characters (48 KB, 45-bone skeleton, full Tripo rig)
| File | Status | Notes |
|---|---|---|
| `ShooterPlayer.prefab` | ✅ KEEP | Original rigged shooter |
| `ShooterPlayer_V2.prefab` | ✅ KEEP (probable duplicate of above) | Compare meshes, keep one |
| `Goalkeeper 1.prefab` | ✅ KEEP | Original rigged keeper |
| `Goalkeeper_V2.prefab` | ✅ KEEP (probable duplicate) | Compare meshes, keep one |

These are the **only viable Mixamo retargeting targets** without re-rigging. Currently NOT in the scene; the Professional static prefabs are.

### Tier 2: Static Tripo meshes (4.5 KB, 3-node hierarchy with +90° Y bake on "scene")
| File | Status | Notes |
|---|---|---|
| `Shooter_Professional.prefab` | ✅ IN SCENE | Best-looking shooter, currently used |
| `Goalkeeper_Professional.prefab` | ✅ IN SCENE | Best-looking keeper, currently used |
| `Shooter_HQ.prefab` | 🟡 KEEP-OR-ARCHIVE | Inspect mesh, may be alt visual |
| `Shooter_Final.prefab` | 🟡 KEEP-OR-ARCHIVE | Inspect mesh |
| `Shooter_V3.prefab` | 🟡 KEEP-OR-ARCHIVE | Inspect mesh |
| `Goalkeeper_V3.prefab` | 🟡 KEEP-OR-ARCHIVE | Inspect mesh |
| `Goalkeeper_Final.prefab` | 🟡 KEEP-OR-ARCHIVE | Inspect mesh |

These all reference unique Tripo-generated GLBs in `*_Assets/selected.glb`. They look passable but cannot animate without re-rigging.

### Tier 3: BROKEN — point at default cube (`com.unity.ai.assistant`'s cube.fbx)
| File | Status |
|---|---|
| `Goalkeeper.prefab` | ❌ ARCHIVE — original "goalkeeper" was a cube |
| `Goalkeeper_V4.prefab` | ❌ ARCHIVE — cube |
| `Goalkeeper_V5_Final.prefab` | ❌ ARCHIVE — cube |
| `Shooter_V4.prefab` | ❌ ARCHIVE — cube |
| `Shooter_V4 1.prefab` | ❌ ARCHIVE — cube |
| `Shooter_V5_Final.prefab` | ❌ ARCHIVE — cube |
| `RealisticSoccerGoal.prefab` | ❌ ARCHIVE — supposed to be a goal but is a cube |
| `RealisticSoccerGoal 1.prefab` | ❌ ARCHIVE — cube |
| `RealisticSoccerGoal 2.prefab` | ❌ ARCHIVE — cube |
| `RealisticSoccerGoal_V2.prefab` | ❌ ARCHIVE — cube |
| `RealisticSoccerGoal_Final.prefab` | ❌ ARCHIVE — cube |

**11 prefabs all reference the same `cube.fbx` from the Unity AI Assistant package.** Tripo's mesh generation either failed or wasn't wired up — these are placeholder shells around a default cube primitive.

### Recommendation
Move the Tier 3 (broken) prefabs and their associated `*_Assets/` folders + `.meta` files to `Assets/Models/_Archive/`. Keep Tier 1 + Tier 2 in place. Decide which V2/V3/HQ/Final variants to keep after a visual diff.

**Do not delete anything** — archive only, since the user asked to keep assets even if not used.

---

## Open questions / what to do next

1. **Confirm keeper Y=0.5** is correct (already applied to scene; user verified shooter only).
2. **Decide rigging strategy**:
   - Keep the static Professional models and live with no bone animation (current state, fakes via transform tilt).
   - Switch in-scene back to the rigged `ShooterPlayer.prefab` / `Goalkeeper 1.prefab` so Mixamo retargeting works once `.fbx` files arrive.
   - Send the Professional GLBs through Mixamo's auto-rigger to add a skeleton.
3. **Generate the missing environment assets** (goal, ball, stadium, pitch texture). The "RealisticSoccerGoal" prefabs are not actual goals, just cube placeholders.
4. **Camera Clear Flags** — switch to Solid Color on mobile build for perf.
5. **Run a one-off editor script** to compute foot offsets from Renderer bounds instead of guessing model heights.
