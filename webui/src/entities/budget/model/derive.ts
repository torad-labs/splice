// The budget entity's pure half: how one head's budget reads.
import type { Budget, BudgetsPayload } from './types';

/** No budget is a state, not a zero: a zero budget would block a head on its first turn, and every
 *  head starts with none. This is the sentence the page prints for it. */
export const NO_BUDGET = 'no budget';

/** One head's budget, or null when the payload carries none for it. */
export function budgetFor(payload: BudgetsPayload | null, head: string): Budget | null {
  return payload?.budgets.find((row) => row.head === head) ?? null;
}

/** A budget as printed: dollars per day, or the honest empty. Never `$0.00`. */
export function budgetText(budget: Budget | null): string {
  if (budget === null || budget.daily_usd === null) return NO_BUDGET;
  return `$${budget.daily_usd.toFixed(2)}/day`;
}

/** What reaching the budget does, in the daemon's own two words. A `block` head refuses the turn,
 *  which the operator must see before they set it rather than after. */
export function budgetActionText(budget: Budget | null): string | null {
  return budget === null ? null : budget.action;
}
