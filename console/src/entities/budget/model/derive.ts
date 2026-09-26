// The budget entity's pure half: which budget a head has.
import type { Budget, BudgetsPayload } from './types';

/** One head's budget, or null when the payload carries none for it. No budget is a state, not a
 *  zero: a zero budget would block a head on its first turn, and every head starts with none. */
export function budgetFor(payload: BudgetsPayload | null, head: string): Budget | null {
  return payload?.budgets.find((row) => row.head === head) ?? null;
}
