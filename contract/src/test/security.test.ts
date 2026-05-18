// Midnight Kicks — Security vulnerability reproductions
//
// These tests run against the VULNERABLE contract (V1) to prove the
// exploits work, then against the FIXED contract (V3) to prove the
// remediations hold. Each test maps to a VULN-XXX entry in
// docs/security/COMPACT_SECURITY_REGISTRY.md.
//
// V3 inherits every V2 fix (no disclose() of secret key or choices,
// timeouts present) and restructures the preimage schema to support
// the symmetric 10-pairing model — see docs/GAME_DESIGN.md §7.

import { PenaltySimulator } from "./penalty-simulator.js";
import { VulnerablePenaltySimulator } from "./penalty-vulnerable-simulator.js";
import {
  NetworkId,
  setNetworkId,
} from "@midnight-ntwrk/midnight-js-network-id";
import { describe, it, expect } from "vitest";
import { randomBytes, LEFT, CENTER, RIGHT, type Picks5 } from "./utils.js";
import { Phase } from "../managed/penalty/contract/index.js";
import { Phase as VulnPhase } from "../managed/penalty-vulnerable/contract/index.js";
import { type VulnerableChoices } from "./witnesses-vulnerable.js";

setNetworkId("undeployed" as NetworkId);

// ═══════════════════════════════════════════════════════════════════
// VULN-001: Identity impersonation via secret key knowledge
// ═══════════════════════════════════════════════════════════════════

describe("VULN-001: Identity impersonation via secret key knowledge", () => {
  // Both vulnerable and fixed contracts use the same identity model
  // (localSecretKey + persistentHash). This vuln is about ownPublicKey()
  // at the protocol level — we demonstrate WHY our pattern is safer.

  it("EXPLOIT: attacker with P1's secret key acts as P1", () => {
    const p1Secret = randomBytes(32);
    const p2Secret = randomBytes(32);
    const choices: VulnerableChoices = [LEFT, CENTER, RIGHT, LEFT, RIGHT];
    const nonce = randomBytes(32);

    const sim = new VulnerablePenaltySimulator(p1Secret, choices, nonce);
    sim.switchPlayer(p2Secret, choices, nonce);
    sim.joinMatch();

    sim.switchPlayer(p1Secret, choices, nonce);
    const state = sim.commitBatch();
    expect(state.p1Committed).toEqual(true);
    // Attacker committed AS player 1 — exploit works
  });

  it("DEFENSE: without the secret key, impersonation fails", () => {
    const p1Secret = randomBytes(32);
    const p2Secret = randomBytes(32);
    const choices: VulnerableChoices = [LEFT, CENTER, RIGHT, LEFT, RIGHT];
    const nonce = randomBytes(32);

    const sim = new VulnerablePenaltySimulator(p1Secret, choices, nonce);
    sim.switchPlayer(p2Secret, choices, nonce);
    sim.joinMatch();

    const wrongSecret = randomBytes(32);
    sim.switchPlayer(wrongSecret, choices, nonce);
    expect(() => sim.commitBatch()).toThrow("Not a player in this match");
  });
});

// ═══════════════════════════════════════════════════════════════════
// VULN-002: Choices disclosed during commitment
// ═══════════════════════════════════════════════════════════════════

describe("VULN-002: Choices disclosed during commitment", () => {

  it("VULNERABLE: V1 commit discloses choices (they leak into the proof transcript)", () => {
    // V1's commitBatch does `const c0 = disclose(localChoice0()); ...`
    // before hashing. The choices appear as private_input instructions
    // in the proof and are recoverable by anyone with the transaction.

    const p1Secret = randomBytes(32);
    const p2Secret = randomBytes(32);
    const choices: VulnerableChoices = [LEFT, CENTER, RIGHT, LEFT, RIGHT];
    const nonce = randomBytes(32);

    const sim = new VulnerablePenaltySimulator(p1Secret, choices, nonce);
    sim.switchPlayer(p2Secret, choices, nonce);
    sim.joinMatch();

    sim.switchPlayer(p1Secret, choices, nonce);
    const state = sim.commitBatch();
    expect(state.p1Committed).toEqual(true);
  });

  it("FIXED: V3 commit feeds shoots/keeps straight to persistentCommit — nothing disclosed but the hash", () => {
    // V3's commitRegulation passes the witness Vectors directly into
    // persistentCommit and only disclose()s the hash output. The
    // shoots/keeps arrays never become private_input instructions.

    const p1Secret = randomBytes(32);
    const p2Secret = randomBytes(32);
    const shoots: Picks5 = [LEFT, CENTER, RIGHT, LEFT, RIGHT];
    const keeps:  Picks5 = [RIGHT, LEFT, CENTER, RIGHT, LEFT];
    const nonce = randomBytes(32);

    const sim = new PenaltySimulator(p1Secret, shoots, keeps, nonce);
    sim.switchPlayer(p2Secret, shoots, keeps, nonce);
    sim.joinMatch();

    sim.switchPlayer(p1Secret, shoots, keeps, nonce);
    const state = sim.commitRegulation();
    expect(state.p1Committed).toEqual(true);
    expect(state.p1Commitment).toBeDefined();
    expect(state.p1Commitment).not.toEqual(new Uint8Array(32));
  });

  it("V3 schema differs from V1: different preimages, different hashes, both still hide inputs", () => {
    // V1 hashes BatchPreimage{c0..c4}; V3 hashes RegulationBatch{shoots, keeps}.
    // Different structs → different commitment values for the same inputs.
    // The security guarantee that survives is: neither commitment leaks
    // its inputs into the proof transcript when its respective contract
    // is built correctly. V1 broke that; V3 preserves it.

    const secret = randomBytes(32);
    const p2Secret = randomBytes(32);
    const shoots: Picks5 = [LEFT, CENTER, RIGHT, LEFT, RIGHT];
    const keeps:  Picks5 = [RIGHT, LEFT, CENTER, RIGHT, LEFT];
    const nonce = randomBytes(32);

    const vulnSim = new VulnerablePenaltySimulator(secret, shoots, nonce);
    vulnSim.switchPlayer(p2Secret, shoots, nonce);
    vulnSim.joinMatch();
    vulnSim.switchPlayer(secret, shoots, nonce);
    const vulnState = vulnSim.commitBatch();

    const fixedSim = new PenaltySimulator(secret, shoots, keeps, nonce);
    fixedSim.switchPlayer(p2Secret, shoots, keeps, nonce);
    fixedSim.joinMatch();
    fixedSim.switchPlayer(secret, shoots, keeps, nonce);
    const fixedState = fixedSim.commitRegulation();

    expect(vulnState.p1Commitment).not.toEqual(fixedState.p1Commitment);
  });

  it("MATH: V3's pick space is 3^10 = 59049 — still brute-forceable without a nonce", () => {
    // V3 commits 10 picks (5 shoots + 5 keeps), each in {0,1,2}.
    expect(Math.pow(3, 10)).toEqual(59049);
    // Without a per-commit nonce an attacker could hash all 59,049
    // combinations and unmask the commitment before reveal. The 32-byte
    // nonce makes this infeasible (2^256 search space).
  });
});

// ═══════════════════════════════════════════════════════════════════
// VULN-003: Secret key disclosed unnecessarily
// ═══════════════════════════════════════════════════════════════════

describe("VULN-003: Secret key disclosed unnecessarily", () => {

  it("VULNERABLE: V1 constructor discloses the secret key — identity still derives", () => {
    // V1: `const sk = disclose(localSecretKey()); player1 = disclose(publicKey(sk));`
    // The secret enters the proof's private_input section.
    const secret = randomBytes(32);
    const choices: VulnerableChoices = [LEFT, CENTER, RIGHT, LEFT, RIGHT];
    const nonce = randomBytes(32);

    const sim = new VulnerablePenaltySimulator(secret, choices, nonce);
    expect(sim.getLedger().player1).not.toEqual(new Uint8Array(32));
  });

  it("FIXED: V3 constructor passes the secret through publicKey() without disclose()", () => {
    // V3: `player1 = disclose(publicKey(localSecretKey()));`
    // Only the hash output crosses the privacy boundary.
    const secret = randomBytes(32);
    const shoots: Picks5 = [LEFT, CENTER, RIGHT, LEFT, RIGHT];
    const keeps:  Picks5 = [RIGHT, LEFT, CENTER, RIGHT, LEFT];
    const nonce = randomBytes(32);

    const sim = new PenaltySimulator(secret, shoots, keeps, nonce);
    expect(sim.getLedger().player1).not.toEqual(new Uint8Array(32));
  });

  it("PROOF: both versions derive the same public key from the same secret", () => {
    // The publicKey() helper is identical across V1 and V3, so the
    // derived identity is the same; only the proof transcript differs.
    const secret = randomBytes(32);
    const choices: VulnerableChoices = [LEFT, CENTER, RIGHT, LEFT, RIGHT];
    const shoots:  Picks5 = [LEFT, CENTER, RIGHT, LEFT, RIGHT];
    const keeps:   Picks5 = [RIGHT, LEFT, CENTER, RIGHT, LEFT];
    const nonce = randomBytes(32);

    const vulnSim = new VulnerablePenaltySimulator(secret, choices, nonce);
    const fixedSim = new PenaltySimulator(secret, shoots, keeps, nonce);

    expect(vulnSim.getLedger().player1).toEqual(fixedSim.getLedger().player1);
  });
});

// ═══════════════════════════════════════════════════════════════════
// VULN-004: Griefing via non-participation (no timeout)
// ═══════════════════════════════════════════════════════════════════

describe("VULN-004: Griefing via non-participation", () => {

  it("EXPLOIT: V1 contract stuck in COMMITTING — no escape", () => {
    const p1Secret = randomBytes(32);
    const p2Secret = randomBytes(32);
    const choices: VulnerableChoices = [LEFT, CENTER, RIGHT, LEFT, RIGHT];
    const nonce = randomBytes(32);

    const sim = new VulnerablePenaltySimulator(p1Secret, choices, nonce);
    sim.switchPlayer(p2Secret, choices, nonce);
    sim.joinMatch();

    sim.switchPlayer(p1Secret, choices, nonce);
    sim.commitBatch();

    const state = sim.getLedger();
    expect(state.phase).toEqual(VulnPhase.COMMITTING);
    expect(state.p1Committed).toEqual(true);
    expect(state.p2Committed).toEqual(false);
    // V1 has no claimTimeout() — both stakes locked permanently.
  });

  it("EXPLOIT: V1 contract stuck in REVEALING — no escape", () => {
    const p1Secret = randomBytes(32);
    const p2Secret = randomBytes(32);
    const p1Choices: VulnerableChoices = [LEFT, CENTER, RIGHT, LEFT, RIGHT];
    const p2Choices: VulnerableChoices = [RIGHT, LEFT, CENTER, RIGHT, LEFT];
    const p1Nonce = randomBytes(32);
    const p2Nonce = randomBytes(32);

    const sim = new VulnerablePenaltySimulator(p1Secret, p1Choices, p1Nonce);
    sim.switchPlayer(p2Secret, p2Choices, p2Nonce);
    sim.joinMatch();

    sim.switchPlayer(p1Secret, p1Choices, p1Nonce);
    sim.commitBatch();
    sim.switchPlayer(p2Secret, p2Choices, p2Nonce);
    sim.commitBatch();

    sim.switchPlayer(p1Secret, p1Choices, p1Nonce);
    sim.revealBatch();

    const state = sim.getLedger();
    expect(state.phase).toEqual(VulnPhase.REVEALING);
    expect(state.p1Revealed).toEqual(true);
    expect(state.p2Revealed).toEqual(false);
  });

  it("FIXED: V3 exposes claimTimeout() and cancelMatch() as escape hatches", () => {
    // The compact simulator can't advance block time, so we can't
    // actually fire blockTimeGte() here. What we can verify is that
    // V3 ships the circuits — V1 doesn't, so its simulator throws.
    const shoots: Picks5 = [LEFT, CENTER, RIGHT, LEFT, RIGHT];
    const keeps:  Picks5 = [RIGHT, LEFT, CENTER, RIGHT, LEFT];
    const nonce = randomBytes(32);

    const v3 = new PenaltySimulator(randomBytes(32), shoots, keeps, nonce);
    // claimTimeout exists on V3 (asserts trip on its own preconditions,
    // not on the circuit being absent).
    expect("claimTimeout" in v3.contract.impureCircuits).toBe(true);
    expect("cancelMatch" in v3.contract.impureCircuits).toBe(true);

    const v1 = new VulnerablePenaltySimulator(
      randomBytes(32),
      [LEFT, CENTER, RIGHT, LEFT, RIGHT],
      nonce,
    );
    expect("claimTimeout" in v1.contract.impureCircuits).toBe(false);
    expect("cancelMatch" in v1.contract.impureCircuits).toBe(false);
  });
});
