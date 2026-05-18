// Simulator for the V3 penalty contract

import * as contractModule from "../managed/penalty/contract/index.js";
import { BasePenaltySimulator } from "./base-simulator.js";
import { type Picks5 } from "./utils.js";

export class PenaltySimulator extends BasePenaltySimulator {
  constructor(
    secretKey: Uint8Array,
    shoots: Picks5,
    keeps:  Picks5,
    nonce:  Uint8Array,
    sdShoot: bigint = 0n,
    sdKeep:  bigint = 0n,
  ) {
    super(contractModule, secretKey, shoots, keeps, nonce, sdShoot, sdKeep);
  }
}
