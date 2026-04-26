// Simulator for the VULNERABLE penalty contract (pre-fix)
// Used by security.test.ts to reproduce exploits

import * as contractModule from "../managed/penalty-vulnerable/contract/index.js";
import { BasePenaltySimulator } from "./base-simulator.js";
import { type Choices } from "./utils.js";

export class VulnerablePenaltySimulator extends BasePenaltySimulator {
  constructor(secretKey: Uint8Array, choices: Choices, nonce: Uint8Array) {
    super(contractModule, secretKey, choices, nonce);
  }
}
