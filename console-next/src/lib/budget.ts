// The budget entity's pure half: which budget a head has.
import type { Budget, BudgetsPayload } from '../types/budget';
import type { HeadCatalog } from '../types/models';

/** The next daily boundary used by BudgetLedger, independent of the viewer's calendar day. */
export function nextBudgetResetAtEpochSeconds(nowMs: number): number {
  const at = new Date(nowMs);
  return Date.UTC(at.getUTCFullYear(), at.getUTCMonth(), at.getUTCDate() + 1) / 1000;
}

/** Only the command's declared rate cards can make new spending count toward its budget. */
export const hasBudgetPrices = (catalog: HeadCatalog | undefined): boolean => catalog?.models.some(model => model.rates != null) === true;

/** One head's budget, or null when the payload carries none for it. No budget is a state, not a
 *  zero: a zero budget would block a head on its first turn, and every head starts with none. */
export function budgetFor(payload: BudgetsPayload | null, head: string): Budget | null {
  return payload?.budgets.find((row) => row.head === head) ?? null;
}
