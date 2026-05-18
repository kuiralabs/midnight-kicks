// Base simulator — shared logic for both V3 and the vulnerable variant.

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
import { type Picks5 } from "./utils.js";

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
    shoots: Picks5,
    keeps:  Picks5,
    nonce:  Uint8Array,
    sdShoot: bigint = 0n,
    sdKeep:  bigint = 0n,
  ) {
    this.contract = new contractModule.Contract(witnesses);
    this.ledgerFn = contractModule.ledger;
    const {
      currentPrivateState,
      currentContractState,
      currentZswapLocalState,
    } = this.contract.initialState(
      createConstructorContext(
        { secretKey, shoots, keeps, sdShoot, sdKeep, nonce },
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

  // Swap the active player's full private state (regulation picks +
  // SD picks + secret + nonce). SD picks default to 0 for tests that
  // only care about regulation.
  switchPlayer(
    secretKey: Uint8Array,
    shoots: Picks5,
    keeps:  Picks5,
    nonce:  Uint8Array,
    sdShoot: bigint = 0n,
    sdKeep:  bigint = 0n,
  ): void {
    this.circuitContext.currentPrivateState = {
      secretKey,
      shoots,
      keeps,
      sdShoot,
      sdKeep,
      nonce,
    };
  }

  // Update just the SD pick + nonce, preserving secret + regulation
  // arrays. Use this between SD rounds.
  setSuddenDeath(
    sdShoot: bigint,
    sdKeep:  bigint,
    nonce:   Uint8Array,
  ): void {
    const prev = this.circuitContext.currentPrivateState;
    this.circuitContext.currentPrivateState = {
      ...prev,
      sdShoot,
      sdKeep,
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
    // V2/V3 take commitDeadlineSecs, the older vulnerable variant
    // doesn't. Try with the arg, fall back to no-arg on signature
    // mismatch. Re-throw contract assertion errors so tests catch them.
    try {
      this.circuitContext = this.contract.impureCircuits.joinMatch(
        this.circuitContext,
        commitDeadlineSecs,
      ).context;
    } catch (e: any) {
      const msg = String(e?.message ?? e ?? "");
      if (msg.includes("argument") && (msg.includes("expected") || msg.includes("received"))) {
        this.circuitContext = this.contract.impureCircuits.joinMatch(
          this.circuitContext,
        ).context;
      } else {
        throw e;
      }
    }
    return this.getLedger();
  }

  commitRegulation() {
    this.circuitContext = this.contract.impureCircuits.commitRegulation(
      this.circuitContext,
    ).context;
    return this.getLedger();
  }

  revealRegulation() {
    this.circuitContext = this.contract.impureCircuits.revealRegulation(
      this.circuitContext,
    ).context;
    return this.getLedger();
  }

  commitSuddenDeath() {
    this.circuitContext = this.contract.impureCircuits.commitSuddenDeath(
      this.circuitContext,
    ).context;
    return this.getLedger();
  }

  revealSuddenDeath() {
    this.circuitContext = this.contract.impureCircuits.revealSuddenDeath(
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
