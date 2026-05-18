// Witness implementations for penalty-vulnerable.compact (V1)
//
// V1 uses 5 scalar localChoiceN() witnesses and exposes the secret
// key + choices in disclose() — that's the whole point of why it's
// vulnerable. Kept isolated from the V3 witnesses.ts so the V3 test
// path doesn't carry V1's private-state shape.

import { Ledger } from "../managed/penalty-vulnerable/contract/index.js";
import { WitnessContext } from "@midnight-ntwrk/compact-runtime";

export type VulnerableChoices = [bigint, bigint, bigint, bigint, bigint];

export type VulnerablePenaltyPrivateState = {
  readonly secretKey: Uint8Array;
  readonly choices: VulnerableChoices;
  readonly nonce: Uint8Array;
};

type Ctx = WitnessContext<Ledger, VulnerablePenaltyPrivateState>;

export const vulnerableWitnesses = {
  localSecretKey: ({ privateState }: Ctx): [
    VulnerablePenaltyPrivateState,
    Uint8Array,
  ] => [privateState, privateState.secretKey],

  localNonce: ({ privateState }: Ctx): [
    VulnerablePenaltyPrivateState,
    Uint8Array,
  ] => [privateState, privateState.nonce],

  localChoice0: ({ privateState }: Ctx): [VulnerablePenaltyPrivateState, bigint] =>
    [privateState, privateState.choices[0]],
  localChoice1: ({ privateState }: Ctx): [VulnerablePenaltyPrivateState, bigint] =>
    [privateState, privateState.choices[1]],
  localChoice2: ({ privateState }: Ctx): [VulnerablePenaltyPrivateState, bigint] =>
    [privateState, privateState.choices[2]],
  localChoice3: ({ privateState }: Ctx): [VulnerablePenaltyPrivateState, bigint] =>
    [privateState, privateState.choices[3]],
  localChoice4: ({ privateState }: Ctx): [VulnerablePenaltyPrivateState, bigint] =>
    [privateState, privateState.choices[4]],
};
