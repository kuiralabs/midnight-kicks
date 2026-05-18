// Simulator for the VULNERABLE penalty contract (V1, pre-fix).
// Used only by security.test.ts to reproduce exploits documented in
// docs/security/COMPACT_SECURITY_REGISTRY.md.
//
// V1 has a different witness shape and circuit set from V3, so this
// simulator stands alone instead of extending BasePenaltySimulator.

import {
  type CircuitContext,
  QueryContext,
  sampleContractAddress,
  createConstructorContext,
  CostModel,
  Contract,
} from "@midnight-ntwrk/compact-runtime";
import * as contractModule from "../managed/penalty-vulnerable/contract/index.js";
import {
  type VulnerableChoices,
  type VulnerablePenaltyPrivateState,
  vulnerableWitnesses,
} from "./witnesses-vulnerable.js";

export class VulnerablePenaltySimulator {
  readonly contract: Contract<VulnerablePenaltyPrivateState>;
  circuitContext: CircuitContext<VulnerablePenaltyPrivateState>;

  constructor(
    secretKey: Uint8Array,
    choices: VulnerableChoices,
    nonce: Uint8Array,
  ) {
    this.contract = new contractModule.Contract(vulnerableWitnesses as any);
    const {
      currentPrivateState,
      currentContractState,
      currentZswapLocalState,
    } = this.contract.initialState(
      createConstructorContext(
        { secretKey, choices, nonce },
        "0".repeat(64),
      ),
    );
    this.circuitContext = {
      currentPrivateState,
      currentZswapLocalState,
      costModel: CostModel.initialCostModel(),
      currentQueryContext: new QueryContext(
        currentContractState.data,
        sampleContractAddress(),
      ),
    };
  }

  switchPlayer(
    secretKey: Uint8Array,
    choices: VulnerableChoices,
    nonce: Uint8Array,
  ): void {
    this.circuitContext.currentPrivateState = { secretKey, choices, nonce };
  }

  getLedger() {
    return contractModule.ledger(this.circuitContext.currentQueryContext.state);
  }

  joinMatch() {
    this.circuitContext = this.contract.impureCircuits.joinMatch(
      this.circuitContext,
    ).context;
    return this.getLedger();
  }

  commitBatch() {
    this.circuitContext = this.contract.impureCircuits.commitBatch(
      this.circuitContext,
    ).context;
    return this.getLedger();
  }

  revealBatch() {
    this.circuitContext = this.contract.impureCircuits.revealBatch(
      this.circuitContext,
    ).context;
    return this.getLedger();
  }
}
