// Utility functions for penalty contract tests

import { randomBytes as nodeRandomBytes } from "crypto";

export function randomBytes(length: number): Uint8Array {
  return new Uint8Array(nodeRandomBytes(length));
}

// Direction constants — match the contract's Uint<8> encoding.
// Shooter's perspective: when keeping, LEFT means "I dive to the
// shooter's left side".
export const LEFT = 0n;
export const CENTER = 1n;
export const RIGHT = 2n;

// V3: 5 picks per array — players commit shoots[5] + keeps[5].
export type Picks5 = [bigint, bigint, bigint, bigint, bigint];
