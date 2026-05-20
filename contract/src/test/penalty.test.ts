// Midnight Kicks — Penalty contract V3 unit tests
//
// V3 model: each player commits 5 shoots + 5 keeps for regulation,
// then a single {shoot, keep} pair per sudden-death round.
//
// Scoring (regulation, 10 pairings):
//   P1 scores on round i if  p1Shoots[i] != p2Keeps[i]
//   P2 scores on round i if  p2Shoots[i] != p1Keeps[i]
//
// Sudden death (single pairing per round): decisive when exactly one
// player scores; otherwise sdRound++ and re-enter SD_COMMITTING.

import { PenaltySimulator } from "./penalty-simulator.js";
import {
  NetworkId,
  setNetworkId,
} from "@midnight-ntwrk/midnight-js-network-id";
import { describe, it, expect } from "vitest";
import { randomBytes, LEFT, CENTER, RIGHT, type Picks5 } from "./utils.js";
import { Phase } from "../managed/penalty/contract/index.js";

setNetworkId("undeployed" as NetworkId);

// ───────────────────────────────────────────────────────────────────
// Test fixtures
// ───────────────────────────────────────────────────────────────────

// A 1-1 draw: each player scores exactly once.
//   P1 shoots [L,L,L,L,L] vs P2 keeps [R,L,L,L,L] → P1 goal on i=0 only
//   P2 shoots [L,L,L,L,L] vs P1 keeps [R,L,L,L,L] → P2 goal on i=0 only
const DRAW_P1_SHOOTS: Picks5 = [LEFT, LEFT, LEFT, LEFT, LEFT];
const DRAW_P1_KEEPS:  Picks5 = [RIGHT, LEFT, LEFT, LEFT, LEFT];
const DRAW_P2_SHOOTS: Picks5 = [LEFT, LEFT, LEFT, LEFT, LEFT];
const DRAW_P2_KEEPS:  Picks5 = [RIGHT, LEFT, LEFT, LEFT, LEFT];

// P1 5-0 sweep:
//   P1 shoots all different from P2's keeps → P1 scores 5
//   P2 shoots all matched by P1's keeps → P2 scores 0
const P1_SWEEP_SHOOTS: Picks5 = [LEFT, CENTER, RIGHT, LEFT, CENTER];
const P2_SWEEP_KEEPS:  Picks5 = [RIGHT, RIGHT, LEFT, CENTER, LEFT];
const P2_BLOCKED_SHOOTS: Picks5 = [LEFT, LEFT, LEFT, LEFT, LEFT];
const P1_PERFECT_KEEPS:  Picks5 = [LEFT, LEFT, LEFT, LEFT, LEFT];

// ───────────────────────────────────────────────────────────────────
// Helpers
// ───────────────────────────────────────────────────────────────────

type Player = {
  key: Uint8Array;
  shoots: Picks5;
  keeps: Picks5;
  nonce: Uint8Array;
};

function makePlayer(shoots: Picks5, keeps: Picks5): Player {
  return {
    key: randomBytes(32),
    shoots,
    keeps,
    nonce: randomBytes(32),
  };
}

// Create match, P2 joins, ready for regulation commit.
function setupMatch(p1: Player, p2: Player) {
  const sim = new PenaltySimulator(p1.key, p1.shoots, p1.keeps, p1.nonce);
  sim.switchPlayer(p2.key, p2.shoots, p2.keeps, p2.nonce);
  sim.joinMatch();
  return sim;
}

function loadPlayer(sim: PenaltySimulator, p: Player) {
  sim.switchPlayer(p.key, p.shoots, p.keeps, p.nonce);
}

// Full regulation: both commit + both reveal. Returns final ledger.
function playRegulation(sim: PenaltySimulator, p1: Player, p2: Player) {
  loadPlayer(sim, p1); sim.commitRegulation();
  loadPlayer(sim, p2); sim.commitRegulation();
  loadPlayer(sim, p1); sim.revealRegulation();
  loadPlayer(sim, p2); return sim.revealRegulation();
}

// One SD round: both commit + both reveal with the given pair picks.
function playSdRound(
  sim: PenaltySimulator,
  p1Key: Uint8Array, p1Shoot: bigint, p1Keep: bigint, p1Nonce: Uint8Array,
  p2Key: Uint8Array, p2Shoot: bigint, p2Keep: bigint, p2Nonce: Uint8Array,
) {
  // Carry over regulation arrays from current state (any value is fine
  // for SD circuits — they don't read shoots/keeps).
  const dummy: Picks5 = [0n, 0n, 0n, 0n, 0n];

  sim.switchPlayer(p1Key, dummy, dummy, p1Nonce, p1Shoot, p1Keep);
  sim.commitSuddenDeath();
  sim.switchPlayer(p2Key, dummy, dummy, p2Nonce, p2Shoot, p2Keep);
  sim.commitSuddenDeath();

  sim.switchPlayer(p1Key, dummy, dummy, p1Nonce, p1Shoot, p1Keep);
  sim.revealSuddenDeath();
  sim.switchPlayer(p2Key, dummy, dummy, p2Nonce, p2Shoot, p2Keep);
  return sim.revealSuddenDeath();
}

// ───────────────────────────────────────────────────────────────────
// Tests
// ───────────────────────────────────────────────────────────────────

describe("Penalty contract V3", () => {
  describe("constructor + joinMatch", () => {
    it("initializes in WAITING phase", () => {
      const p1 = makePlayer(DRAW_P1_SHOOTS, DRAW_P1_KEEPS);
      const sim = new PenaltySimulator(p1.key, p1.shoots, p1.keeps, p1.nonce);
      expect(sim.getLedger().phase).toEqual(Phase.WAITING);
    });

    it("advances to COMMITTING when P2 joins", () => {
      const p1 = makePlayer(DRAW_P1_SHOOTS, DRAW_P1_KEEPS);
      const p2 = makePlayer(DRAW_P2_SHOOTS, DRAW_P2_KEEPS);
      const sim = setupMatch(p1, p2);
      expect(sim.getLedger().phase).toEqual(Phase.COMMITTING);
    });

    it("rejects joining your own match", () => {
      const p1 = makePlayer(DRAW_P1_SHOOTS, DRAW_P1_KEEPS);
      const sim = new PenaltySimulator(p1.key, p1.shoots, p1.keeps, p1.nonce);
      // Same key → same identity → can't join
      expect(() => sim.joinMatch()).toThrow("Cannot join your own match");
    });

    it("rejects joining when not in WAITING phase", () => {
      const p1 = makePlayer(DRAW_P1_SHOOTS, DRAW_P1_KEEPS);
      const p2 = makePlayer(DRAW_P2_SHOOTS, DRAW_P2_KEEPS);
      const sim = setupMatch(p1, p2);
      const p3 = makePlayer(DRAW_P1_SHOOTS, DRAW_P1_KEEPS);
      sim.switchPlayer(p3.key, p3.shoots, p3.keeps, p3.nonce);
      expect(() => sim.joinMatch()).toThrow("Match not in WAITING phase");
    });
  });

  describe("commitRegulation", () => {
    it("records P1 commitment, stays in COMMITTING", () => {
      const p1 = makePlayer(DRAW_P1_SHOOTS, DRAW_P1_KEEPS);
      const p2 = makePlayer(DRAW_P2_SHOOTS, DRAW_P2_KEEPS);
      const sim = setupMatch(p1, p2);
      loadPlayer(sim, p1);
      const state = sim.commitRegulation();
      expect(state.p1Committed).toEqual(true);
      expect(state.p2Committed).toEqual(false);
      expect(state.phase).toEqual(Phase.COMMITTING);
    });

    it("advances to REVEALING when both commit", () => {
      const p1 = makePlayer(DRAW_P1_SHOOTS, DRAW_P1_KEEPS);
      const p2 = makePlayer(DRAW_P2_SHOOTS, DRAW_P2_KEEPS);
      const sim = setupMatch(p1, p2);
      loadPlayer(sim, p1); sim.commitRegulation();
      loadPlayer(sim, p2);
      const state = sim.commitRegulation();
      expect(state.phase).toEqual(Phase.REVEALING);
    });

    it("rejects double commitment", () => {
      const p1 = makePlayer(DRAW_P1_SHOOTS, DRAW_P1_KEEPS);
      const p2 = makePlayer(DRAW_P2_SHOOTS, DRAW_P2_KEEPS);
      const sim = setupMatch(p1, p2);
      loadPlayer(sim, p1); sim.commitRegulation();
      expect(() => sim.commitRegulation()).toThrow("Player 1 already committed");
    });

    it("rejects non-player", () => {
      const p1 = makePlayer(DRAW_P1_SHOOTS, DRAW_P1_KEEPS);
      const p2 = makePlayer(DRAW_P2_SHOOTS, DRAW_P2_KEEPS);
      const sim = setupMatch(p1, p2);
      const stranger = makePlayer(DRAW_P1_SHOOTS, DRAW_P1_KEEPS);
      loadPlayer(sim, stranger);
      expect(() => sim.commitRegulation()).toThrow("Not a player in this match");
    });
  });

  describe("revealRegulation", () => {
    it("rejects reveal before both committed", () => {
      const p1 = makePlayer(DRAW_P1_SHOOTS, DRAW_P1_KEEPS);
      const p2 = makePlayer(DRAW_P2_SHOOTS, DRAW_P2_KEEPS);
      const sim = setupMatch(p1, p2);
      loadPlayer(sim, p1); sim.commitRegulation();
      expect(() => sim.revealRegulation()).toThrow(
        "Match not in regulation reveal phase",
      );
    });

    it("rejects wrong shoots (commitment mismatch)", () => {
      const p1 = makePlayer(DRAW_P1_SHOOTS, DRAW_P1_KEEPS);
      const p2 = makePlayer(DRAW_P2_SHOOTS, DRAW_P2_KEEPS);
      const sim = setupMatch(p1, p2);
      loadPlayer(sim, p1); sim.commitRegulation();
      loadPlayer(sim, p2); sim.commitRegulation();

      // Reveal with tampered shoots
      const tampered: Picks5 = [RIGHT, RIGHT, RIGHT, RIGHT, RIGHT];
      sim.switchPlayer(p1.key, tampered, p1.keeps, p1.nonce);
      expect(() => sim.revealRegulation()).toThrow("Commitment mismatch");
    });

    it("rejects wrong keeps (commitment mismatch)", () => {
      const p1 = makePlayer(DRAW_P1_SHOOTS, DRAW_P1_KEEPS);
      const p2 = makePlayer(DRAW_P2_SHOOTS, DRAW_P2_KEEPS);
      const sim = setupMatch(p1, p2);
      loadPlayer(sim, p1); sim.commitRegulation();
      loadPlayer(sim, p2); sim.commitRegulation();

      const tampered: Picks5 = [CENTER, CENTER, CENTER, CENTER, CENTER];
      sim.switchPlayer(p1.key, p1.shoots, tampered, p1.nonce);
      expect(() => sim.revealRegulation()).toThrow("Commitment mismatch");
    });

    it("rejects wrong nonce", () => {
      const p1 = makePlayer(DRAW_P1_SHOOTS, DRAW_P1_KEEPS);
      const p2 = makePlayer(DRAW_P2_SHOOTS, DRAW_P2_KEEPS);
      const sim = setupMatch(p1, p2);
      loadPlayer(sim, p1); sim.commitRegulation();
      loadPlayer(sim, p2); sim.commitRegulation();

      sim.switchPlayer(p1.key, p1.shoots, p1.keeps, randomBytes(32));
      expect(() => sim.revealRegulation()).toThrow("Commitment mismatch");
    });
  });

  describe("full regulation match", () => {
    it("P1 sweeps 5-0", () => {
      const p1 = makePlayer(P1_SWEEP_SHOOTS, P1_PERFECT_KEEPS);
      const p2 = makePlayer(P2_BLOCKED_SHOOTS, P2_SWEEP_KEEPS);
      const sim = setupMatch(p1, p2);
      const state = playRegulation(sim, p1, p2);

      expect(state.phase).toEqual(Phase.COMPLETE);
      expect(state.p1Score).toEqual(5n);
      expect(state.p2Score).toEqual(0n);
      expect(state.isDraw).toEqual(false);
      expect(state.winner).toEqual(sim.getLedger().player1);
    });

    it("P2 sweeps 0-5", () => {
      // Flip the sweep: P1 is now the blocked shooter / weak keeper
      const p1 = makePlayer(P2_BLOCKED_SHOOTS, P2_SWEEP_KEEPS);
      const p2 = makePlayer(P1_SWEEP_SHOOTS, P1_PERFECT_KEEPS);
      const sim = setupMatch(p1, p2);
      const state = playRegulation(sim, p1, p2);

      expect(state.phase).toEqual(Phase.COMPLETE);
      expect(state.p1Score).toEqual(0n);
      expect(state.p2Score).toEqual(5n);
      expect(state.winner).toEqual(sim.getLedger().player2);
    });

    it("P1 wins close (3-1)", () => {
      // P1 shoots vs P2 keeps: diffs at i=1,2,4 → P1 = 3
      // P2 shoots vs P1 keeps: diff at i=2 only  → P2 = 1
      const p1Shoots: Picks5 = [LEFT,   CENTER, RIGHT, LEFT, RIGHT];
      const p2Keeps:  Picks5 = [LEFT,   LEFT,   LEFT,  LEFT, CENTER];
      const p2Shoots: Picks5 = [LEFT,   CENTER, RIGHT, LEFT, CENTER];
      const p1Keeps:  Picks5 = [LEFT,   CENTER, LEFT,  LEFT, CENTER];

      const p1 = makePlayer(p1Shoots, p1Keeps);
      const p2 = makePlayer(p2Shoots, p2Keeps);
      const sim = setupMatch(p1, p2);
      const state = playRegulation(sim, p1, p2);

      expect(state.phase).toEqual(Phase.COMPLETE);
      expect(state.p1Score).toEqual(3n);
      expect(state.p2Score).toEqual(1n);
      expect(state.winner).toEqual(sim.getLedger().player1);
    });
  });

  describe("draw → sudden death", () => {
    it("draw enters SD_COMMITTING with sdRound=1", () => {
      const p1 = makePlayer(DRAW_P1_SHOOTS, DRAW_P1_KEEPS);
      const p2 = makePlayer(DRAW_P2_SHOOTS, DRAW_P2_KEEPS);
      const sim = setupMatch(p1, p2);
      const state = playRegulation(sim, p1, p2);

      expect(state.phase).toEqual(Phase.SD_COMMITTING);
      expect(state.sdRound).toEqual(1n);
      expect(state.p1Score).toEqual(1n);
      expect(state.p2Score).toEqual(1n);
      expect(state.p1Committed).toEqual(false);
      expect(state.p2Committed).toEqual(false);
    });

    it("SD resolves when P1 scores and P2 misses", () => {
      const p1 = makePlayer(DRAW_P1_SHOOTS, DRAW_P1_KEEPS);
      const p2 = makePlayer(DRAW_P2_SHOOTS, DRAW_P2_KEEPS);
      const sim = setupMatch(p1, p2);
      playRegulation(sim, p1, p2);

      // P1 shoots LEFT, P2 keeps RIGHT → P1 goal (different)
      // P2 shoots LEFT, P1 keeps LEFT  → P2 save  (same)
      const state = playSdRound(
        sim,
        p1.key, LEFT, LEFT, randomBytes(32),
        p2.key, LEFT, RIGHT, randomBytes(32),
      );

      expect(state.phase).toEqual(Phase.COMPLETE);
      expect(state.winner).toEqual(sim.getLedger().player1);
      expect(state.p1Score).toEqual(2n);
      expect(state.p2Score).toEqual(1n);
    });

    it("SD resolves when P2 scores and P1 misses", () => {
      const p1 = makePlayer(DRAW_P1_SHOOTS, DRAW_P1_KEEPS);
      const p2 = makePlayer(DRAW_P2_SHOOTS, DRAW_P2_KEEPS);
      const sim = setupMatch(p1, p2);
      playRegulation(sim, p1, p2);

      // P1 shoots LEFT, P2 keeps LEFT  → P1 save
      // P2 shoots RIGHT, P1 keeps LEFT → P2 goal
      const state = playSdRound(
        sim,
        p1.key, LEFT,  LEFT, randomBytes(32),
        p2.key, RIGHT, LEFT, randomBytes(32),
      );

      expect(state.phase).toEqual(Phase.COMPLETE);
      expect(state.winner).toEqual(sim.getLedger().player2);
    });

    it("SD continues when both score", () => {
      const p1 = makePlayer(DRAW_P1_SHOOTS, DRAW_P1_KEEPS);
      const p2 = makePlayer(DRAW_P2_SHOOTS, DRAW_P2_KEEPS);
      const sim = setupMatch(p1, p2);
      playRegulation(sim, p1, p2);

      // Both score:
      //   P1{shoot=L, keep=L}, P2{shoot=R, keep=R}
      //   p1Goal = L != R = true
      //   p2Goal = R != L = true
      const state = playSdRound(
        sim,
        p1.key, LEFT,  LEFT,  randomBytes(32),
        p2.key, RIGHT, RIGHT, randomBytes(32),
      );

      expect(state.phase).toEqual(Phase.SD_COMMITTING);
      expect(state.sdRound).toEqual(2n);
      expect(state.p1Score).toEqual(2n);
      expect(state.p2Score).toEqual(2n);
    });

    it("SD continues when both miss", () => {
      const p1 = makePlayer(DRAW_P1_SHOOTS, DRAW_P1_KEEPS);
      const p2 = makePlayer(DRAW_P2_SHOOTS, DRAW_P2_KEEPS);
      const sim = setupMatch(p1, p2);
      playRegulation(sim, p1, p2);

      // Both miss: shoot == keep for both pairings.
      //   P1.shoot == P2.keep  AND  P2.shoot == P1.keep
      // Use: P1{shoot=L, keep=R}, P2{shoot=R, keep=L}
      //   p1Goal = L != L = false
      //   p2Goal = R != R = false
      const state = playSdRound(
        sim,
        p1.key, LEFT,  RIGHT, randomBytes(32),
        p2.key, RIGHT, LEFT,  randomBytes(32),
      );

      expect(state.phase).toEqual(Phase.SD_COMMITTING);
      expect(state.sdRound).toEqual(2n);
      // Scores unchanged from regulation draw
      expect(state.p1Score).toEqual(1n);
      expect(state.p2Score).toEqual(1n);
    });

    it("sdRound counts the round number, not rounds played", () => {
      // After draw → SD, sdRound = 1 (round 1 is active).
      // Each stalemate increments before the next round opens.
      // Decisive rounds do NOT increment (no follow-up).
      const p1 = makePlayer(DRAW_P1_SHOOTS, DRAW_P1_KEEPS);
      const p2 = makePlayer(DRAW_P2_SHOOTS, DRAW_P2_KEEPS);
      const sim = setupMatch(p1, p2);
      const afterRegulation = playRegulation(sim, p1, p2);
      expect(afterRegulation.sdRound).toEqual(1n);

      // Stalemate round 1 → sdRound = 2
      const afterRound1 = playSdRound(
        sim,
        p1.key, LEFT,  RIGHT, randomBytes(32),
        p2.key, RIGHT, LEFT,  randomBytes(32),
      );
      expect(afterRound1.sdRound).toEqual(2n);
      expect(afterRound1.phase).toEqual(Phase.SD_COMMITTING);
    });

    it("SD resolves on round 2 after round 1 stalemate", () => {
      const p1 = makePlayer(DRAW_P1_SHOOTS, DRAW_P1_KEEPS);
      const p2 = makePlayer(DRAW_P2_SHOOTS, DRAW_P2_KEEPS);
      const sim = setupMatch(p1, p2);
      playRegulation(sim, p1, p2);

      // Round 1: both miss (see above pattern)
      playSdRound(
        sim,
        p1.key, LEFT,  RIGHT, randomBytes(32),
        p2.key, RIGHT, LEFT,  randomBytes(32),
      );

      // Round 2: P1 scores, P2 misses
      const state = playSdRound(
        sim,
        p1.key, LEFT, LEFT,  randomBytes(32),
        p2.key, LEFT, RIGHT, randomBytes(32),
      );

      expect(state.phase).toEqual(Phase.COMPLETE);
      expect(state.sdRound).toEqual(2n);
      expect(state.winner).toEqual(sim.getLedger().player1);
    });
  });

  // ── Second-reveal atomicity — regression guard for the app-layer poll predicate ──
  //
  // Context for future readers: the regulation reveal circuit and the
  // sudden-death reveal circuit BOTH do the second-reveal score-and-
  // dispatch in a single atomic call. When the result is a draw /
  // stalemate, `resetRoundState()` runs *inside the same circuit call*
  // that set `p2Revealed = true`, so the ledger never contains an
  // observable snapshot with `p2Revealed == true` along the draw path.
  //
  // The Kotlin app's `StatePoller` reads the ledger every ~3 s. Any
  // predicate that watches for a transient flag (e.g. `p2Revealed`)
  // will miss the draw path and deadlock waiting for a state that
  // never exists. The supported predicates for "second reveal landed"
  // therefore have to key off either:
  //   - phase == COMPLETE       (decisive path), or
  //   - sdRound >= 1            (drew into SD round 1), or
  //   - phase moved out of REVEALING / SD_REVEALING (either path).
  //
  // These tests pin the contract behavior so a future contract refactor
  // can't silently break the assumption (e.g. by clearing the round
  // state in a *separate* circuit call, which would re-introduce the
  // race in a different shape).
  describe("second-reveal atomicity (app-poll predicate invariants)", () => {
    it("drawn revealRegulation: p1Revealed AND p2Revealed both observe as false (poller cannot witness p2Revealed=true)", () => {
      // Same fixture as the existing `"draw enters SD_COMMITTING …"`
      // test, but asserts the *reveal* flags. The existing test only
      // covered the commit flags — leaving the bug we hit live in the
      // app layer (waitForP2Revealed polling on { it.p2Revealed }).
      const p1 = makePlayer(DRAW_P1_SHOOTS, DRAW_P1_KEEPS);
      const p2 = makePlayer(DRAW_P2_SHOOTS, DRAW_P2_KEEPS);
      const sim = setupMatch(p1, p2);
      const state = playRegulation(sim, p1, p2);

      // Sanity — we are on the draw path.
      expect(state.phase).toEqual(Phase.SD_COMMITTING);
      expect(state.sdRound).toEqual(1n);

      // The load-bearing assertions:
      expect(state.p1Revealed).toEqual(false);
      expect(state.p2Revealed).toEqual(false);
    });

    it("drawn revealRegulation: phase==SD_COMMITTING AND sdRound==1 are visible in the same atomic snapshot (fix-predicate witness)", () => {
      // This pins the *positive* signal that the corrected app-layer
      // predicate keys off: even though pXRevealed flags reset, the
      // transition to SD is observable in the same post-call ledger.
      const p1 = makePlayer(DRAW_P1_SHOOTS, DRAW_P1_KEEPS);
      const p2 = makePlayer(DRAW_P2_SHOOTS, DRAW_P2_KEEPS);
      const sim = setupMatch(p1, p2);
      const state = playRegulation(sim, p1, p2);

      // Combined predicate the poller MUST use after the fix.
      const predicateSecondRevealLanded =
        state.p2Revealed ||
        state.phase === Phase.COMPLETE ||
        state.sdRound >= 1n;
      expect(predicateSecondRevealLanded).toEqual(true);

      // And the specific signals — split out for diagnosability if the
      // combined assertion ever fires.
      expect(state.phase).toEqual(Phase.SD_COMMITTING);
      expect(state.sdRound).toEqual(1n);
    });

    it("decisive revealRegulation: p2Revealed remains true (non-regression for non-draw path)", () => {
      // The old predicate { it.p2Revealed } worked here. Lock that in
      // so the fix doesn't accidentally drop the decisive-path signal.
      const p1 = makePlayer(P1_SWEEP_SHOOTS, P1_PERFECT_KEEPS);
      const p2 = makePlayer(P2_BLOCKED_SHOOTS, P2_SWEEP_KEEPS);
      const sim = setupMatch(p1, p2);
      const state = playRegulation(sim, p1, p2);

      expect(state.phase).toEqual(Phase.COMPLETE);
      expect(state.p1Revealed).toEqual(true);
      expect(state.p2Revealed).toEqual(true);

      // Combined predicate also passes — keeps the test honest.
      const predicateSecondRevealLanded =
        state.p2Revealed ||
        state.phase === Phase.COMPLETE ||
        state.sdRound >= 1n;
      expect(predicateSecondRevealLanded).toEqual(true);
    });

    it("stalemate revealSuddenDeath: p1Revealed AND p2Revealed both reset, sdRound advances atomically", () => {
      // Same race shape as the regulation-draw case, in the SD loop.
      // After a stalemate round, the contract calls resetRoundState()
      // and increments sdRound in the same circuit. Any P1-side poll
      // predicate on `p2Revealed && sdRound == prev.round` would
      // deadlock the same way once a stalemate round closes.
      const p1 = makePlayer(DRAW_P1_SHOOTS, DRAW_P1_KEEPS);
      const p2 = makePlayer(DRAW_P2_SHOOTS, DRAW_P2_KEEPS);
      const sim = setupMatch(p1, p2);
      playRegulation(sim, p1, p2);

      // Round 1 stalemate (both miss). Lifted from existing
      // `"SD continues when both miss"` test.
      const afterRound1 = playSdRound(
        sim,
        p1.key, LEFT,  RIGHT, randomBytes(32),
        p2.key, RIGHT, LEFT,  randomBytes(32),
      );

      expect(afterRound1.phase).toEqual(Phase.SD_COMMITTING);
      expect(afterRound1.sdRound).toEqual(2n);

      // The race-evidence assertions — both pXRevealed flags should be
      // cleared in the same observable snapshot that advanced sdRound.
      expect(afterRound1.p1Revealed).toEqual(false);
      expect(afterRound1.p2Revealed).toEqual(false);

      // The fix-predicate signal for the SD-loop variant: phase still
      // SD_COMMITTING but sdRound has advanced past the round the
      // caller was waiting on. The app-layer
      // `waitForP2SdRevealed` must accept "sdRound > prev.round" as
      // a stalemate completion signal.
      const sdRoundCaller = 1n; // the round the poller was watching
      const predicateSdRevealResolved =
        afterRound1.p2Revealed ||
        afterRound1.phase === Phase.COMPLETE ||
        afterRound1.sdRound > sdRoundCaller;
      expect(predicateSdRevealResolved).toEqual(true);
    });

    it("mid-reveal: after P1 revealed but before P2 revealed, p1Revealed observes as true (P2-side predicate is race-free)", () => {
      // Why this exists: the P2-side orchestrator polls on
      // { it.p1Revealed } to learn that P1 finished revealing. Unlike
      // the second-reveal path, this snapshot is stable — there is no
      // reset between the two reveal calls, so the predicate is safe.
      // Pin that here so a future contract refactor can't silently
      // collapse the two reveals into one transaction (which would
      // break P2's predicate too).
      const p1 = makePlayer(DRAW_P1_SHOOTS, DRAW_P1_KEEPS);
      const p2 = makePlayer(DRAW_P2_SHOOTS, DRAW_P2_KEEPS);
      const sim = setupMatch(p1, p2);
      loadPlayer(sim, p1); sim.commitRegulation();
      loadPlayer(sim, p2); sim.commitRegulation();
      loadPlayer(sim, p1); const midReveal = sim.revealRegulation();

      // Mid-reveal-phase snapshot: P1 done, P2 still pending. The chain
      // holds this state stably for as long as P2 takes to submit; the
      // 3-second app poller has any amount of time to observe it.
      expect(midReveal.phase).toEqual(Phase.REVEALING);
      expect(midReveal.p1Revealed).toEqual(true);
      expect(midReveal.p2Revealed).toEqual(false);
    });

    it("decisive revealSuddenDeath: p2Revealed remains true, phase==COMPLETE (non-regression for decisive SD)", () => {
      // Mirror of the decisive-regulation test, for the SD path.
      const p1 = makePlayer(DRAW_P1_SHOOTS, DRAW_P1_KEEPS);
      const p2 = makePlayer(DRAW_P2_SHOOTS, DRAW_P2_KEEPS);
      const sim = setupMatch(p1, p2);
      playRegulation(sim, p1, p2);

      // P1 scores, P2 misses — decisive (lifted from existing test).
      const state = playSdRound(
        sim,
        p1.key, LEFT, LEFT,  randomBytes(32),
        p2.key, LEFT, RIGHT, randomBytes(32),
      );

      expect(state.phase).toEqual(Phase.COMPLETE);
      expect(state.p1Revealed).toEqual(true);
      expect(state.p2Revealed).toEqual(true);
    });
  });

  describe("cancelMatch", () => {
    it("creator cancels a WAITING match → COMPLETE + isDraw=true", () => {
      const p1 = makePlayer(DRAW_P1_SHOOTS, DRAW_P1_KEEPS);
      const sim = new PenaltySimulator(p1.key, p1.shoots, p1.keeps, p1.nonce);
      const state = sim.cancelMatch();
      expect(state.phase).toEqual(Phase.COMPLETE);
      expect(state.isDraw).toEqual(true);
    });

    it("non-creator cannot cancel", () => {
      const p1 = makePlayer(DRAW_P1_SHOOTS, DRAW_P1_KEEPS);
      const sim = new PenaltySimulator(p1.key, p1.shoots, p1.keeps, p1.nonce);
      const stranger = makePlayer(DRAW_P1_SHOOTS, DRAW_P1_KEEPS);
      loadPlayer(sim, stranger);
      expect(() => sim.cancelMatch()).toThrow("Only the creator can cancel");
    });

    it("cannot cancel after P2 joined", () => {
      const p1 = makePlayer(DRAW_P1_SHOOTS, DRAW_P1_KEEPS);
      const p2 = makePlayer(DRAW_P2_SHOOTS, DRAW_P2_KEEPS);
      const sim = setupMatch(p1, p2);
      loadPlayer(sim, p1);
      expect(() => sim.cancelMatch()).toThrow("Can only cancel in WAITING phase");
    });
  });
});
