// The accounts entity's HTTP segment: one route, no rendering. The client helper carries the
// management key, the 401 lockout and the error envelope, and `pendingOf` carries the "route not
// built yet" mapping (CONTRACTS.md 8), so nothing here re-implements any of them.
import { pendingOf, request } from '@shared/api';
import { poll } from '@shared/lib';
import { accountsStore } from '../model/store';
import { PENDING_ACCOUNTS } from '../model/types';
import type { AccountsWire } from '../model/types';
import { accountsFromWire } from '../model/wire';

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

export async function fetchAccounts(): Promise<void> {
  accountsStore.startLoading();
  try {
    accountsStore.setData(accountsFromWire(await request<AccountsWire>('/api/accounts')));
  } catch (err) {
    const pending = pendingOf(err, PENDING_ACCOUNTS);
    if (pending !== null) {
      accountsStore.setData(pending);
      return;
    }
    accountsStore.setError(messageOf(err));
  }
}

export function startAccountsPolling(intervalMs = 15000): () => void {
  return poll(fetchAccounts, intervalMs);
}
