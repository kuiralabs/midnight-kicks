// Base simulator — shared logic for both fixed and vulnerable contracts.
// Parameterized by the compiled contract module to avoid code duplication.

import {
  type CircuitContext,
  QueryContext,
  sampleContractAddress,
  createConstructorContext,
  CostModel,
  Contract,
} from "@midnight-ntwrk/compact-runtime";
import {
  type PenaltyPrivateState,
  witnesses,
} from "../witnesses.js";
import { type Choices } from "./utils.js";

// The shape both compiled contracts expose
export interface PenaltyContractModule {
  Contract: new (w: typeof witnesses) => Contract<PenaltyPrivateState>;
  ledger: (state: any) => any;
}

/**
 * Base simulator for penalty contracts — local circuit execution.
 *
 * Works with any compiled penalty contract (fixed or vulnerable)
 * as long as it exposes the same circuits and ledger shape.
 */
export class BasePenaltySimulator {
  readonly contract: Contract<PenaltyPrivateState>;
  circuitContext: CircuitContext<PenaltyPrivateState>;
  private readonly ledgerFn: (state: any) => any;

  constructor(
    contractModule: PenaltyContractModule,
    secretKey: Uint8Array,
    choices: Choices,
    nonce: Uint8Array,
  ) {
    this.contract = new contractModule.Contract(witnesses);
    this.ledgerFn = contractModule.ledger;
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
    choices: Choices,
    nonce: Uint8Array,
  ): void {
    this.circuitContext.currentPrivateState = {
      secretKey,
      choices,
      nonce,
    };
  }

  getLedger() {
    return this.ledgerFn(this.circuitContext.currentQueryContext.state);
  }

  getPrivateState(): PenaltyPrivateState {
    return this.circuitContext.currentPrivateState;
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
