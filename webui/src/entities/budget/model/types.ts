// Spend budgets, typed from FEATURES.md section 5 ("spend budgets per head and per day with warn
// and block", one of the table-stakes items the operator kept in) and section 6 (GET/PUT
// /api/budgets; server-side today: new).
//
// PENDING V4-133: the route and the store behind it do not exist, so the console holds the pending
// empty and never a budget it invented.
import type { PendingRoute } from '@shared/api';

/** What happens when a head reaches its budget. `warn` tells the operator, `block` refuses the
 *  turn; the console renders the difference and never issues one itself. */
export type BudgetAction = 'warn' | 'block';

/** One head's daily spend budget. */
export interface Budget {
  head: string;
  /** USD per day, or null when the head has NO budget. Null is not zero: a head with a budget of
   *  zero would be blocked on its first turn, and "no budget" is the state every head starts in. */
  daily_usd: number | null;
  action: BudgetAction;
}

export interface BudgetsPayload {
  budgets: Budget[];
}

export type BudgetsSlice = BudgetsPayload | PendingRoute;
