// Base simulator — shared logic for both fixed and vulnerable contracts.

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

export interface PenaltyContractModule {
  Contract: new (w: typeof witnesses) => Contract<PenaltyPrivateState>;
  ledger: (state: any) => any;
}

const FAR_FUTURE = 9999999999n;

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

  joinMatch(commitDeadlineSecs: bigint = FAR_FUTURE) {
    // V2 (fixed) takes commitDeadlineSecs, V1 (vulnerable) doesn't.
    // Always try with the arg first — if the contract doesn't accept
    // it, fall back to no-arg. Re-throw contract assertion errors
    // (e.g., "Cannot join your own match") so tests can catch them.
    try {
      this.circuitContext = this.contract.impureCircuits.joinMatch(
        this.circuitContext,
        commitDeadlineSecs,
      ).context;
    } catch (e: any) {
      const msg = String(e?.message ?? e ?? "");
      // If the error is about wrong arg count, retry without arg
      if (msg.includes("argument") && (msg.includes("expected") || msg.includes("received"))) {
        this.circuitContext = this.contract.impureCircuits.joinMatch(
          this.circuitContext,
        ).context;
      } else {
        throw e; // contract assertion error — re-throw for tests
      }
    }
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

  claimTimeout() {
    if (!('claimTimeout' in this.contract.impureCircuits)) {
      throw new Error("claimTimeout not available in this contract version");
    }
    this.circuitContext = (this.contract.impureCircuits as any).claimTimeout(
      this.circuitContext,
    ).context;
    return this.getLedger();
  }

  cancelMatch() {
    if (!('cancelMatch' in this.contract.impureCircuits)) {
      throw new Error("cancelMatch not available in this contract version");
    }
    this.circuitContext = (this.contract.impureCircuits as any).cancelMatch(
      this.circuitContext,
    ).context;
    return this.getLedger();
  }
}
