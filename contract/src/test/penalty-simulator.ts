// Simulator for the FIXED penalty contract

import * as contractModule from "../managed/penalty/contract/index.js";
import { BasePenaltySimulator } from "./base-simulator.js";
import { type Choices } from "./utils.js";

export class PenaltySimulator extends BasePenaltySimulator {
  constructor(secretKey: Uint8Array, choices: Choices, nonce: Uint8Array) {
    super(contractModule, secretKey, choices, nonce);
  }
}
