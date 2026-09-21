// The budget entity's HTTP segment. Both routes are pending V4-133.
import { pendingOf, request } from '@shared/api';
import { poll } from '@shared/lib';
import { budgetsStore } from '../model/store';
import type { Budget, BudgetsPayload } from '../model/types';

/** The v0.4.0 item that will serve the budget routes. */
export const PENDING_BUDGETS = 'V4-133';

export async function fetchBudgets(): Promise<void> {
  budgetsStore.startLoading();
  try {
    budgetsStore.setData(await request<BudgetsPayload>('/api/budgets'));
  } catch (err) {
    const pending = pendingOf(err, PENDING_BUDGETS);
    if (pending !== null) {
      budgetsStore.setData(pending);
      return;
    }
    budgetsStore.setError(err instanceof Error ? err.message : String(err));
  }
}

export function startBudgetsPolling(intervalMs = 30000): () => void {
  return poll(fetchBudgets, intervalMs);
}

/**
 * Write the whole budget set. The daemon answers with the budgets it now holds, and the store
 * takes THAT rather than the request: a value the daemon clamped or refused must not read as
 * applied (the same reason the config entity re-reads after a patch).
 */
export async function putBudgets(budgets: readonly Budget[]): Promise<BudgetsPayload | null> {
  try {
    const applied = await request<BudgetsPayload>('/api/budgets', {
      method: 'PUT',
      body: JSON.stringify({ budgets }),
    });
    budgetsStore.setData(applied);
    return applied;
  } catch (err) {
    const pending = pendingOf(err, PENDING_BUDGETS);
    if (pending !== null) {
      budgetsStore.setData(pending);
      return null;
    }
    budgetsStore.setError(err instanceof Error ? err.message : String(err));
    return null;
  }
}
