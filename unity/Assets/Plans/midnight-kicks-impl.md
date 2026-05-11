# Project Overview
- Game Title: Midnight Kicks
- High-Level Concept: A ZK-powered penalty shootout game where players commit to 5 directions, then reveal and watch the cinematic replay.
- Players: Single player vs AI or Local/Networking (Multiplayer logic handled by Kotlin/Midnight).
- Inspiration / Reference Games: FIFA Penalty Shootouts.
- Tone / Art Direction: Modern Stadium, Midnight Theme.
- Target Platform: Android (UaaL).
- Render Pipeline: URP (PC_RPAsset).

# Game Mechanics
## Core Gameplay Loop
1. **Choice Phase**: Player picks 5 kick directions (Left, Center, Right).
2. **Commit/Reveal**: Handled by Kotlin/Midnight layer. Unity waits for the "replay" message.
3. **Replay Phase**: Unity plays back the 5 rounds sequentially.
   - For each round:
     - Determine shooter and keeper directions from JSON.
     - Animate the ball kick.
     - Animate the keeper dive.
     - Show "GOAL!" or "SAVE!" based on logic (shooter_dir != keeper_dir).
4. **Resolution**: After 5 rounds (or sudden death), show the final score and winner.

## Controls and Input Methods
- Touch/Mouse clicks on "LEFT", "CENTER", "RIGHT" buttons during Choice Phase.

# UI
- **Choice UI**: 3 buttons for directions, progress indicators for the 5 choices.
- **Overlay**: Current round and running score during replay.
- **Feedback**: Large "GOAL!" or "SAVE!" text after each kick.
- **Result Screen**: Final score and winner announcement.

# Key Asset & Context
- `GameController.cs`: Main bridge and state manager.
- `BallKicker.cs`: Handles physics-based ball kicking.
- `Keeper.cs`: (To be created) Handles keeper animations/movement.
- `ShotManager.cs`: (To be created) Orchestrates individual shots during replay.

# Implementation Steps
1. **Model & Prefab Setup**:
   - Create a simple Keeper object (Cube/Capsule) and add the `Keeper` script.
   - Ensure the `Ball` has the `BallKicker` script properly configured.
2. **Bridge Update (GameController.cs)**:
   - Enhance JSON parsing for `replay` and `suddenDeathReplay` messages.
   - Implement the sequencing of shots.
3. **Keeper Logic (Keeper.cs)**:
   - Implement `Dive(int direction)` which moves the keeper to Left (-1.5), Center (0), or Right (1.5).
4. **Shot Orchestration (ShotManager.cs)**:
   - Link `BallKicker` and `Keeper`.
   - Implement a coroutine to play a single shot:
     - Reset ball and keeper positions.
     - Wait 1s.
     - Trigger kick and dive.
     - Wait for collision or goal.
     - Show feedback text.
     - Wait 2s.
5. **UI & Cinematic Polish**:
   - Add camera positioning for a better "cinematic" feel.
   - Implement the "Result Screen" logic.

# Verification & Testing
- Use a mock JSON message in the Editor to test the Choice Phase.
- Use a mock `replay` JSON to verify the cinematic playback.
- Verify that `replayComplete` is sent back to Kotlin after all rounds.
