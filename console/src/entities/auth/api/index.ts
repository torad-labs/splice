import { MgmtError, control, pendingOf, request } from '@shared/api';
import type { AuthActionResult, PendingRoute } from '@shared/api';
import { poll } from '@shared/lib';
import { authActionStore, authStore, keysStore } from '../model/store';
import { PENDING_AUTH_WRITES } from '../model/types';
import type {
  AccountMutationPayload,
  AuthActionOutcome,
  KeyState,
  KeysPayload,
  LoginStartPayload,
  LoginStatusPayload,
  SwitchPayload,
} from '../model/types';

// The read route predates the rebuild and still goes through `control`; the five WRITES below are
// new routes and call `request` directly (CONTRACTS.md 8).

export async function fetchAuth(): Promise<void> {
  authStore.startLoading();
  try {
    authStore.setData(await control.auth());
  } catch (err) {
    authStore.setError(err instanceof Error ? err.message : String(err));
  }
}

export function startAuthPolling(intervalMs = 15000): () => void {
  return poll(fetchAuth, intervalMs);
}

/** POST /api/auth/:head/refresh, then reload the card from the source of
 * truth (the refresh response is a transient outcome, not the full card). */
export async function refreshAuth(head: string): Promise<AuthActionResult> {
  const result = await control.refreshAuth(head);
  await fetchAuth();
  return result;
}

// ── the five writes (all PENDING V4-132) ─────────────────────────────────────
//
// Every one of them resolves a 404 to the honest empty rather than to a thrown error, because an
// unbuilt route is a state the console renders on purpose (CONTRACTS.md 8). Each returns the value
// AND writes `authActionStore`, so a page can render the outcome without holding it in component
// state, and a poller can show it without a second copy.

function segment(value: string): string {
  return encodeURIComponent(value);
}

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

async function settle<T>(
  path: string,
  init: RequestInit,
  wrap: (value: T) => AuthActionOutcome,
): Promise<AuthActionOutcome | PendingRoute> {
  authActionStore.startLoading();
  try {
    const outcome = wrap(await request<T>(path, init));
    authActionStore.setData(outcome);
    return outcome;
  } catch (err) {
    const pending = pendingOf(err, PENDING_AUTH_WRITES);
    if (pending !== null) {
      authActionStore.setData(pending);
      return pending;
    }
    authActionStore.setError(messageOf(err));
    throw err;
  }
}

/**
 * POST /api/auth/{head}/switch — pin the account the head takes from the NEXT turn.
 *
 * A pin, not a swap: selection happens between turns and is sticky per session, so a turn already
 * streaming keeps the account it started on. The console must say "next turn", never "switched".
 */
export function switchAccount(head: string, label: string): Promise<AuthActionOutcome | PendingRoute> {
  return settle<SwitchPayload>(
    `/api/auth/${segment(head)}/switch`,
    { method: 'POST', body: JSON.stringify({ label }) },
    (result) => ({ action: 'switch', result }),
  );
}

/** DELETE /api/auth/{head}/switch — drop the head's pin, so the selector's own order picks again
 *  from the next turn. Idempotent: ok whether or not an account was pinned. A daemon older than the
 *  route answers 405 (the path exists for POST), which is the same fact as a 404 here: this version
 *  cannot do it. */
export async function unpinAccount(head: string): Promise<AuthActionOutcome | PendingRoute> {
  try {
    return await settle<SwitchPayload>(
      `/api/auth/${segment(head)}/switch`,
      { method: 'DELETE' },
      (result) => ({ action: 'unpin', result }),
    );
  } catch (err) {
    if (err instanceof MgmtError && err.status === 405) {
      const pending = { pending: PENDING_AUTH_WRITES };
      authActionStore.setData(pending);
      return pending;
    }
    throw err;
  }
}

/** POST /api/auth/{head}/login — start a device or browser login for a new account on this head.
 *  The label is the operator's name for it and becomes the credential file's name. */
export function startLogin(head: string, label: string): Promise<AuthActionOutcome | PendingRoute> {
  return settle<LoginStartPayload>(
    `/api/auth/${segment(head)}/login`,
    { method: 'POST', body: JSON.stringify({ label }) },
    (result) => ({ action: 'login', result }),
  );
}

/** GET /api/auth/{head}/login/{id} — poll one login to landed or failed. A landed login still needs
 *  the head restarted before the account is in the pool, which the payload carries as
 *  `restart_required`; the strip stays cocked until it clears. */
export function fetchLoginStatus(head: string, id: string): Promise<AuthActionOutcome | PendingRoute> {
  return settle<LoginStatusPayload>(
    `/api/auth/${segment(head)}/login/${segment(id)}`,
    {},
    (result) => ({ action: 'login-status', result }),
  );
}

/** DELETE /api/auth/{kind}/accounts/{label} — remove a pooled account. Addressed by KIND, not by
 *  head: the pool is the kind's, and several heads may be riding the same login. */
export function removeAccount(kind: string, label: string): Promise<AuthActionOutcome | PendingRoute> {
  return settle<AccountMutationPayload>(
    `/api/auth/${segment(kind)}/accounts/${segment(label)}`,
    { method: 'DELETE' },
    (result) => ({ action: 'remove', result }),
  );
}

/** PATCH /api/auth/{kind}/accounts/{label} — relabel a pooled account. Its windows, exclusions and
 *  pool position are keyed by the credential path, so a relabel loses none of them. */
export function relabelAccount(
  kind: string,
  label: string,
  nextLabel: string,
): Promise<AuthActionOutcome | PendingRoute> {
  return settle<AccountMutationPayload>(
    `/api/auth/${segment(kind)}/accounts/${segment(label)}`,
    { method: 'PATCH', body: JSON.stringify({ label: nextLabel }) },
    (result) => ({ action: 'relabel', result }),
  );
}

// ── the key store (V4-220 item 1) ────────────────────────────────────────────
//
// THE VALUE GOES ONE WAY: it is the PUT's body and nothing else. No store holds it and no answer
// carries it; the daemon answers a write with the key's applied state, re-read (KeyRoutes.kt).

export async function fetchKeys(): Promise<void> {
  keysStore.startLoading();
  try {
    keysStore.setData(await request<KeysPayload>('/api/keys'));
  } catch (err) {
    keysStore.setError(messageOf(err));
  }
}

export function startKeysPolling(intervalMs = 15000): () => void {
  return poll(fetchKeys, intervalMs);
}

/** A write changes what the heads read, so both reads are taken again: the key list, and the auth
 *  card whose masked key and present flag the key table prints. */
async function afterKeyWrite(applied: KeyState): Promise<KeyState> {
  await Promise.all([fetchKeys(), fetchAuth()]);
  return applied;
}

/** PUT /api/keys/{ENV} — store (or replace) a key. The answer names, per head that reads it, where
 *  that head reads it from now; a refusal (400 name or value, 409 store) throws the daemon's words. */
export async function storeKey(name: string, value: string): Promise<KeyState> {
  const applied = await request<KeyState>(`/api/keys/${segment(name)}`, { method: 'PUT', body: JSON.stringify({ value }) });
  return afterKeyWrite(applied);
}

/** DELETE /api/keys/{ENV} — remove a stored key; 404 when the store did not hold it. */
export async function removeKey(name: string): Promise<KeyState> {
  const applied = await request<KeyState>(`/api/keys/${segment(name)}`, { method: 'DELETE' });
  return afterKeyWrite(applied);
}
