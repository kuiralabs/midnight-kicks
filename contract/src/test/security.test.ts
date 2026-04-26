// Midnight Kicks — Security vulnerability reproductions
//
// These tests demonstrate known Compact/Midnight vulnerabilities
// against the penalty contract. Each test maps to a VULN-XXX entry
// in docs/security/COMPACT_SECURITY_REGISTRY.md.
//
// Purpose: proof-of-concept for audiences, not functional testing.
// Functional tests are in penalty.test.ts.

import { PenaltySimulator } from "./penalty-simulator.js";
import {
  NetworkId,
  setNetworkId,
} from "@midnight-ntwrk/midnight-js-network-id";
import { describe, it, expect } from "vitest";
import { randomBytes, LEFT, CENTER, RIGHT, type Choices } from "./utils.js";
import { Phase } from "../managed/penalty/contract/index.js";

setNetworkId("undeployed" as NetworkId);

describe("VULN-001: Identity impersonation via secret key knowledge", () => {
  // Our contract uses localSecretKey() + persistentHash (safe pattern).
  // But this demonstrates WHY it matters: anyone who knows the secret
  // key can impersonate the player. The identity is knowledge-based,
  // not cryptographically bound to a wallet.

  it("attacker who knows P1's secret key can act as P1", () => {
    const p1Secret = randomBytes(32);
    const p2Secret = randomBytes(32);
    const choices: Choices = [LEFT, CENTER, RIGHT, LEFT, RIGHT];
    const nonce = randomBytes(32);

    // P1 creates match
    const sim = new PenaltySimulator(p1Secret, choices, nonce);
    // P2 joins
    sim.switchPlayer(p2Secret, choices, nonce);
    sim.joinMatch();

    // ATTACK: attacker uses P1's secret key to commit AS P1
    // No wallet needed. No biometric. Just the 32-byte secret.
    const attackerUsingP1Key = p1Secret; // attacker obtained this
    sim.switchPlayer(attackerUsingP1Key, choices, nonce);

    // This SUCCEEDS — the contract thinks it's P1 because
    // identity = publicKey(secretKey), and the attacker has the secret
    const state = sim.commitBatch();
    expect(state.p1Committed).toEqual(true);

    // LESSON: protect the secret key. If it leaks, your identity
    // is compromised. This is why Kuira stores it in the TEE.
  });

  it("attacker WITHOUT the secret key cannot impersonate P1", () => {
    const p1Secret = randomBytes(32);
    const p2Secret = randomBytes(32);
    const choices: Choices = [LEFT, CENTER, RIGHT, LEFT, RIGHT];
    const nonce = randomBytes(32);

    const sim = new PenaltySimulator(p1Secret, choices, nonce);
    sim.switchPlayer(p2Secret, choices, nonce);
    sim.joinMatch();

    // Attacker uses a DIFFERENT secret key — derives a different
    // public key — contract rejects because it doesn't match
    // player1 or player2
    const wrongSecret = randomBytes(32);
    sim.switchPlayer(wrongSecret, choices, nonce);
    expect(() => sim.commitBatch()).toThrow("Not a player in this match");

    // LESSON: without the secret key, the persistentHash pattern
    // prevents impersonation. This is the safe alternative to
    // ownPublicKey() which requires NO secret key to spoof.
  });
});

describe("VULN-002: Commitment determinism — same inputs produce same hash", () => {
  // This demonstrates WHY disclosed choices in the commit phase are
  // dangerous: if an observer can see the inputs, they can verify
  // what the player committed and counter it.

  it("same choices + nonce always produce the same commitment", () => {
    const secret = randomBytes(32);
    const choices: Choices = [LEFT, CENTER, RIGHT, LEFT, RIGHT];
    const nonce = randomBytes(32);

    // Two independent simulators with same inputs
    const sim1 = new PenaltySimulator(secret, choices, nonce);
    const sim2 = new PenaltySimulator(secret, choices, nonce);

    const p2Secret = randomBytes(32);

    // P2 joins both matches
    sim1.switchPlayer(p2Secret, choices, nonce);
    sim1.joinMatch();
    sim2.switchPlayer(p2Secret, choices, nonce);
    sim2.joinMatch();

    // P1 commits in both
    sim1.switchPlayer(secret, choices, nonce);
    const state1 = sim1.commitBatch();
    sim2.switchPlayer(secret, choices, nonce);
    const state2 = sim2.commitBatch();

    // Commitments are IDENTICAL — deterministic
    expect(state1.p1Commitment).toEqual(state2.p1Commitment);

    // LESSON: if an attacker sees the raw choices (via disclosed
    // private_input in the proof transcript), they can verify the
    // commitment hash and know EXACTLY what was committed.
    // The nonce provides randomness, but if the choices are leaked
    // alongside the nonce, the commitment is fully transparent.
  });

  it("different nonce produces different commitment (nonce protects)", () => {
    const secret = randomBytes(32);
    const choices: Choices = [LEFT, CENTER, RIGHT, LEFT, RIGHT];
    const nonce1 = randomBytes(32);
    const nonce2 = randomBytes(32);

    const sim1 = new PenaltySimulator(secret, choices, nonce1);
    const sim2 = new PenaltySimulator(secret, choices, nonce2);

    const p2Secret = randomBytes(32);

    sim1.switchPlayer(p2Secret, choices, nonce1);
    sim1.joinMatch();
    sim2.switchPlayer(p2Secret, choices, nonce2);
    sim2.joinMatch();

    sim1.switchPlayer(secret, choices, nonce1);
    const state1 = sim1.commitBatch();
    sim2.switchPlayer(secret, choices, nonce2);
    const state2 = sim2.commitBatch();

    // Different nonces → different commitments
    expect(state1.p1Commitment).not.toEqual(state2.p1Commitment);

    // LESSON: the nonce is critical. It prevents brute-force
    // attacks on the commitment (there are only 3^5 = 243 possible
    // choice combinations — trivially enumerable without a nonce).
  });

  it("without nonce, 243 choice combinations are brute-forceable", () => {
    // With only 3 directions and 5 rounds, there are 3^5 = 243
    // possible choice combinations. Without a nonce, an attacker
    // could hash all 243 and compare to the on-chain commitment.
    const totalCombinations = Math.pow(3, 5);
    expect(totalCombinations).toEqual(243);

    // LESSON: the nonce (32 random bytes) makes brute-force
    // infeasible. Without it, commit-reveal offers zero protection
    // for a game with such a small input space.
  });
});

describe("VULN-003: Secret key leak compromises identity permanently", () => {

  it("public key is deterministically derived from secret key", () => {
    const secret = randomBytes(32);
    const choices: Choices = [LEFT, CENTER, RIGHT, LEFT, RIGHT];
    const nonce = randomBytes(32);

    const sim1 = new PenaltySimulator(secret, choices, nonce);
    const sim2 = new PenaltySimulator(secret, choices, nonce);

    // Same secret → same player1 public key
    expect(sim1.getLedger().player1).toEqual(sim2.getLedger().player1);

    // LESSON: the public key is a permanent fingerprint of the
    // secret. If the secret leaks (via unnecessary disclose() in
    // the proof transcript), the attacker has permanent access to
    // that identity across ALL contracts using the same key.
  });

  it("different secret key produces different identity", () => {
    const secret1 = randomBytes(32);
    const secret2 = randomBytes(32);
    const choices: Choices = [LEFT, CENTER, RIGHT, LEFT, RIGHT];
    const nonce = randomBytes(32);

    const sim1 = new PenaltySimulator(secret1, choices, nonce);
    const sim2 = new PenaltySimulator(secret2, choices, nonce);

    // Different secrets → different identities
    expect(sim1.getLedger().player1).not.toEqual(sim2.getLedger().player1);

    // LESSON: key rotation is possible — generate a new secret,
    // get a new identity. But the old identity is permanently
    // compromised if the old secret leaked.
  });
});

describe("VULN-004: Griefing via non-participation (no timeout)", () => {

  it("contract stuck in COMMITTING if one player never commits", () => {
    const p1Secret = randomBytes(32);
    const p2Secret = randomBytes(32);
    const choices: Choices = [LEFT, CENTER, RIGHT, LEFT, RIGHT];
    const nonce = randomBytes(32);

    const sim = new PenaltySimulator(p1Secret, choices, nonce);
    sim.switchPlayer(p2Secret, choices, nonce);
    sim.joinMatch();

    // P1 commits
    sim.switchPlayer(p1Secret, choices, nonce);
    sim.commitBatch();

    // P2 never commits — contract is stuck
    const state = sim.getLedger();
    expect(state.phase).toEqual(Phase.COMMITTING);
    expect(state.p1Committed).toEqual(true);
    expect(state.p2Committed).toEqual(false);

    // There is NO circuit to escape this state.
    // P1's commitment (and future stake) is locked.
    // The contract has no claimTimeout() function.

    // LESSON: multi-party contracts MUST enforce timeouts.
    // Without blockTimeGte() deadlines and a claimTimeout()
    // escape hatch, the contract is vulnerable to griefing.
  });

  it("contract stuck in REVEALING if one player never reveals", () => {
    const p1Secret = randomBytes(32);
    const p2Secret = randomBytes(32);
    const p1Choices: Choices = [LEFT, CENTER, RIGHT, LEFT, RIGHT];
    const p2Choices: Choices = [RIGHT, LEFT, CENTER, RIGHT, LEFT];
    const p1Nonce = randomBytes(32);
    const p2Nonce = randomBytes(32);

    const sim = new PenaltySimulator(p1Secret, p1Choices, p1Nonce);
    sim.switchPlayer(p2Secret, p2Choices, p2Nonce);
    sim.joinMatch();

    // Both commit
    sim.switchPlayer(p1Secret, p1Choices, p1Nonce);
    sim.commitBatch();
    sim.switchPlayer(p2Secret, p2Choices, p2Nonce);
    sim.commitBatch();

    // P1 reveals
    sim.switchPlayer(p1Secret, p1Choices, p1Nonce);
    sim.revealBatch();

    // P2 never reveals — contract stuck in REVEALING
    const state = sim.getLedger();
    expect(state.phase).toEqual(Phase.REVEALING);
    expect(state.p1Revealed).toEqual(true);
    expect(state.p2Revealed).toEqual(false);

    // Match will never resolve. Both players' commitments
    // (and future stakes) are locked forever.

    // LESSON: the REVEALING phase is even more dangerous for
    // griefing because BOTH players have committed resources.
    // The reveal deadline should be shorter than the commit
    // deadline to minimize exposure.
  });
});
