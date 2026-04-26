// Midnight Kicks — Penalty contract unit tests
// Tests the full match lifecycle using local circuit execution

import { PenaltySimulator } from "./penalty-simulator.js";
import {
  NetworkId,
  setNetworkId,
} from "@midnight-ntwrk/midnight-js-network-id";
import { describe, it, expect } from "vitest";
import { randomBytes, LEFT, CENTER, RIGHT, type Choices } from "./utils.js";
import { Phase } from "../managed/penalty/contract/index.js";

setNetworkId("undeployed" as NetworkId);

// Helper: set up a match with two players ready to commit
function setupMatch(p1Choices: Choices, p2Choices: Choices) {
  const p1Key = randomBytes(32);
  const p2Key = randomBytes(32);
  const p1Nonce = randomBytes(32);
  const p2Nonce = randomBytes(32);
  const sim = new PenaltySimulator(p1Key, p1Choices, p1Nonce);
  sim.switchPlayer(p2Key, p2Choices, p2Nonce);
  sim.joinMatch();
  return { sim, p1Key, p2Key, p1Nonce, p2Nonce, p1Choices, p2Choices };
}

// Default choices for draw scenario (2-2)
const DRAW_P1: Choices = [LEFT, CENTER, RIGHT, LEFT, RIGHT];
const DRAW_P2: Choices = [RIGHT, LEFT, RIGHT, CENTER, LEFT];

describe("Penalty contract", () => {
  describe("constructor", () => {
    it("initializes in WAITING phase with player 1 set", () => {
      const sim = new PenaltySimulator(randomBytes(32), DRAW_P1, randomBytes(32));
      const state = sim.getLedger();
      expect(state.phase).toEqual(Phase.WAITING);
      expect(state.p1Committed).toEqual(false);
      expect(state.p2Committed).toEqual(false);
      expect(state.p1Revealed).toEqual(false);
      expect(state.p2Revealed).toEqual(false);
      expect(state.isDraw).toEqual(false);
    });
  });

  describe("joinMatch", () => {
    it("advances to COMMITTING phase", () => {
      const sim = new PenaltySimulator(randomBytes(32), DRAW_P1, randomBytes(32));
      sim.switchPlayer(randomBytes(32), DRAW_P2, randomBytes(32));
      const state = sim.joinMatch();
      expect(state.phase).toEqual(Phase.COMMITTING);
    });

    it("rejects joining your own match", () => {
      const key = randomBytes(32);
      const sim = new PenaltySimulator(key, DRAW_P1, randomBytes(32));
      expect(() => sim.joinMatch()).toThrow("Cannot join your own match");
    });

    it("rejects joining when not in WAITING phase", () => {
      const { sim } = setupMatch(DRAW_P1, DRAW_P2);
      sim.switchPlayer(randomBytes(32), DRAW_P1, randomBytes(32));
      expect(() => sim.joinMatch()).toThrow("Match not in WAITING phase");
    });
  });

  describe("commitBatch", () => {
    it("records player 1 commitment", () => {
      const { sim, p1Key, p1Choices, p1Nonce } = setupMatch(DRAW_P1, DRAW_P2);
      sim.switchPlayer(p1Key, p1Choices, p1Nonce);
      const state = sim.commitBatch();
      expect(state.p1Committed).toEqual(true);
      expect(state.p2Committed).toEqual(false);
      expect(state.phase).toEqual(Phase.COMMITTING);
    });

    it("advances to REVEALING when both commit", () => {
      const { sim, p1Key, p2Key, p1Choices, p2Choices, p1Nonce, p2Nonce } =
        setupMatch(DRAW_P1, DRAW_P2);

      sim.switchPlayer(p1Key, p1Choices, p1Nonce);
      sim.commitBatch();
      sim.switchPlayer(p2Key, p2Choices, p2Nonce);
      const state = sim.commitBatch();

      expect(state.p1Committed).toEqual(true);
      expect(state.p2Committed).toEqual(true);
      expect(state.phase).toEqual(Phase.REVEALING);
    });

    it("rejects double commitment by same player", () => {
      const { sim, p1Key, p1Choices, p1Nonce } = setupMatch(DRAW_P1, DRAW_P2);
      sim.switchPlayer(p1Key, p1Choices, p1Nonce);
      sim.commitBatch();
      expect(() => sim.commitBatch()).toThrow("Player 1 already committed");
    });

    it("rejects non-player commitment", () => {
      const { sim } = setupMatch(DRAW_P1, DRAW_P2);
      sim.switchPlayer(randomBytes(32), DRAW_P1, randomBytes(32));
      expect(() => sim.commitBatch()).toThrow("Not a player in this match");
    });
  });

  describe("revealBatch", () => {
    it("rejects reveal before both committed", () => {
      const { sim, p1Key, p1Choices, p1Nonce } = setupMatch(DRAW_P1, DRAW_P2);
      sim.switchPlayer(p1Key, p1Choices, p1Nonce);
      sim.commitBatch();
      expect(() => sim.revealBatch()).toThrow("Match not in REVEALING phase");
    });

    it("rejects reveal with wrong choices (commitment mismatch)", () => {
      const { sim, p1Key, p2Key, p1Choices, p2Choices, p1Nonce, p2Nonce } =
        setupMatch(DRAW_P1, DRAW_P2);

      sim.switchPlayer(p1Key, p1Choices, p1Nonce);
      sim.commitBatch();
      sim.switchPlayer(p2Key, p2Choices, p2Nonce);
      sim.commitBatch();

      const wrongChoices: Choices = [RIGHT, RIGHT, RIGHT, RIGHT, RIGHT];
      sim.switchPlayer(p1Key, wrongChoices, p1Nonce);
      expect(() => sim.revealBatch()).toThrow("Commitment mismatch");
    });

    it("rejects reveal with wrong nonce", () => {
      const { sim, p1Key, p2Key, p1Choices, p2Choices, p1Nonce, p2Nonce } =
        setupMatch(DRAW_P1, DRAW_P2);

      sim.switchPlayer(p1Key, p1Choices, p1Nonce);
      sim.commitBatch();
      sim.switchPlayer(p2Key, p2Choices, p2Nonce);
      sim.commitBatch();

      sim.switchPlayer(p1Key, p1Choices, randomBytes(32));
      expect(() => sim.revealBatch()).toThrow("Commitment mismatch");
    });
  });

  describe("full match — draw scenario", () => {
    it("resolves to a draw (2-2)", () => {
      const { sim, p1Key, p2Key, p1Choices, p2Choices, p1Nonce, p2Nonce } =
        setupMatch(DRAW_P1, DRAW_P2);

      sim.switchPlayer(p1Key, p1Choices, p1Nonce);
      sim.commitBatch();
      sim.switchPlayer(p2Key, p2Choices, p2Nonce);
      sim.commitBatch();

      sim.switchPlayer(p1Key, p1Choices, p1Nonce);
      sim.revealBatch();
      sim.switchPlayer(p2Key, p2Choices, p2Nonce);
      const state = sim.revealBatch();

      expect(state.phase).toEqual(Phase.COMPLETE);
      expect(state.p1Score).toEqual(2n);
      expect(state.p2Score).toEqual(2n);
      expect(state.isDraw).toEqual(true);
    });
  });

  describe("full match — P1 wins", () => {
    it("resolves with P1 as winner (3-1)", () => {
      // R0: P1 LEFT vs P2 RIGHT → P1 GOAL
      // R1: P2 CENTER vs P1 CENTER → SAVE
      // R2: P1 RIGHT vs P2 LEFT → P1 GOAL
      // R3: P2 LEFT vs P1 CENTER → P2 GOAL
      // R4: P1 LEFT vs P2 RIGHT → P1 GOAL → 3-1
      const p1Win: Choices = [LEFT, CENTER, RIGHT, CENTER, LEFT];
      const p2Lose: Choices = [RIGHT, CENTER, LEFT, LEFT, RIGHT];

      const { sim, p1Key, p2Key, p1Choices, p2Choices, p1Nonce, p2Nonce } =
        setupMatch(p1Win, p2Lose);

      sim.switchPlayer(p1Key, p1Choices, p1Nonce);
      sim.commitBatch();
      sim.switchPlayer(p2Key, p2Choices, p2Nonce);
      sim.commitBatch();

      sim.switchPlayer(p1Key, p1Choices, p1Nonce);
      sim.revealBatch();
      sim.switchPlayer(p2Key, p2Choices, p2Nonce);
      const state = sim.revealBatch();

      expect(state.phase).toEqual(Phase.COMPLETE);
      expect(state.p1Score).toEqual(3n);
      expect(state.p2Score).toEqual(1n);
      expect(state.isDraw).toEqual(false);
      expect(state.winner).toEqual(sim.getLedger().player1);
    });
  });

  describe("full match — P2 wins", () => {
    it("resolves with P2 as winner (0-2)", () => {
      // R0: P1 LEFT vs P2 LEFT → SAVE
      // R1: P2 RIGHT vs P1 LEFT → P2 GOAL
      // R2: P1 LEFT vs P2 LEFT → SAVE
      // R3: P2 RIGHT vs P1 LEFT → P2 GOAL
      // R4: P1 LEFT vs P2 LEFT → SAVE → 0-2
      const p1Lose: Choices = [LEFT, LEFT, LEFT, LEFT, LEFT];
      const p2Win: Choices = [LEFT, RIGHT, LEFT, RIGHT, LEFT];

      const { sim, p1Key, p2Key, p1Choices, p2Choices, p1Nonce, p2Nonce } =
        setupMatch(p1Lose, p2Win);

      sim.switchPlayer(p1Key, p1Choices, p1Nonce);
      sim.commitBatch();
      sim.switchPlayer(p2Key, p2Choices, p2Nonce);
      sim.commitBatch();

      sim.switchPlayer(p1Key, p1Choices, p1Nonce);
      sim.revealBatch();
      sim.switchPlayer(p2Key, p2Choices, p2Nonce);
      const state = sim.revealBatch();

      expect(state.phase).toEqual(Phase.COMPLETE);
      expect(state.p1Score).toEqual(0n);
      expect(state.p2Score).toEqual(2n);
      expect(state.isDraw).toEqual(false);
      expect(state.winner).toEqual(sim.getLedger().player2);
    });
  });
});
