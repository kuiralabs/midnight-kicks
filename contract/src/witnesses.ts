// Midnight Kicks — Witness implementations for penalty.compact V3
// SPDX-License-Identifier: Apache-2.0

import { Ledger } from "./managed/penalty/contract/index.js";
import { WitnessContext } from "@midnight-ntwrk/compact-runtime";

// ═══════════════════════════════════════════════════════════════════
// Private state shape
// ═══════════════════════════════════════════════════════════════════

// Each player's private state holds:
//   - secretKey: 32 bytes, derives the player's public identity
//   - shoots: 5 directions for the player's 5 regulation shots
//   - keeps:  5 directions for the player's 5 regulation saves
//   - sdShoot/sdKeep: 1 direction each for the current SD round
//   - nonce: 32 bytes, fresh per commit (regulation + every SD round)
//
// Direction encoding: 0 = LEFT, 1 = CENTER, 2 = RIGHT
// (Shooter's perspective — keeper picks the side they'll dive to,
// matching the shooter's reference frame.)
//
// nonce MUST be regenerated for each commit (regulation, then once per
// SD round) — reusing it leaks the choices after the first reveal.
export type Picks5 = [bigint, bigint, bigint, bigint, bigint];

export type PenaltyPrivateState = {
  readonly secretKey: Uint8Array;
  readonly shoots: Picks5;
  readonly keeps:  Picks5;
  readonly sdShoot: bigint;
  readonly sdKeep:  bigint;
  readonly nonce: Uint8Array;
};

export const createPenaltyPrivateState = (
  secretKey: Uint8Array,
  shoots: Picks5,
  keeps:  Picks5,
  nonce:  Uint8Array,
  sdShoot: bigint = 0n,
  sdKeep:  bigint = 0n,
): PenaltyPrivateState => ({
  secretKey,
  shoots,
  keeps,
  sdShoot,
  sdKeep,
  nonce,
});

// ═══════════════════════════════════════════════════════════════════
// Witness implementations
// ═══════════════════════════════════════════════════════════════════

export const witnesses = {
  localSecretKey: ({
    privateState,
  }: WitnessContext<Ledger, PenaltyPrivateState>): [
    PenaltyPrivateState,
    Uint8Array,
  ] => [privateState, privateState.secretKey],

  localNonce: ({
    privateState,
  }: WitnessContext<Ledger, PenaltyPrivateState>): [
    PenaltyPrivateState,
    Uint8Array,
  ] => [privateState, privateState.nonce],

  localShoots: ({
    privateState,
  }: WitnessContext<Ledger, PenaltyPrivateState>): [
    PenaltyPrivateState,
    Picks5,
  ] => [privateState, privateState.shoots],

  localKeeps: ({
    privateState,
  }: WitnessContext<Ledger, PenaltyPrivateState>): [
    PenaltyPrivateState,
    Picks5,
  ] => [privateState, privateState.keeps],

  localSdShoot: ({
    privateState,
  }: WitnessContext<Ledger, PenaltyPrivateState>): [
    PenaltyPrivateState,
    bigint,
  ] => [privateState, privateState.sdShoot],

  localSdKeep: ({
    privateState,
  }: WitnessContext<Ledger, PenaltyPrivateState>): [
    PenaltyPrivateState,
    bigint,
  ] => [privateState, privateState.sdKeep],
};
