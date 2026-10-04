// The auth writes and the sign-in poll: start a login, poll it, refresh a credential, switch or unpin
// an account, remove or relabel a pooled one, and the key store. The pure `async` functions carry the
// paths and the pending-route rule so they are testable; the hooks wrap them for the pages.
//
// A route the daemon does not serve (404, or the daemon naming an unknown route) is a STATE the console
// renders on purpose: every login write and the login poll resolve it to `{ pending }` instead of
// throwing, and the page shows LOGIN_PENDING_EMPTY. Anything else the daemon says rejects with its
// sentence, verbatim (`request` puts it in the MgmtError's message).
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { MgmtError, request } from './client';
import { keys, read } from './queries';
import { awaitRefetch } from './refetch';
import { PENDING_AUTH_WRITES } from '../types/login';
import type { PendingRoute } from '../types/budget';
import type { AuthActionResult } from '../types/core';
import type { AccountWire, ClaudeLoginPlaceId } from '../types/accounts';
import type {
  AccountMutationPayload,
  AuthActionOutcome,
  AuthActionState,
  KeyState,
  KeysPayload,
  LoginStatusPayload,
  SwitchPayload,
} from '../types/login';

/** How often an open login is polled: the sign-in tab takes focus and hides the console, so the poll runs
 *  in the background too, or the tab that is already open would never learn where to go. */
export const LOGIN_POLL_MS = 2000;

export const keyStoreKey = ['key-store'] as const;
const loginKey = (head: string, id: string) => ['login', head, id] as const;

const seg = encodeURIComponent;

// ── paths: every segment the operator or the daemon chose is encoded ─────────────────────────────

export const loginPath = (head: string): string => `/api/auth/${seg(head)}/login`;
export const loginStatusPath = (head: string, id: string): string => `${loginPath(head)}/${seg(id)}`;
export const refreshPath = (head: string): string => `/api/auth/${seg(head)}/refresh`;
export const switchPath = (head: string): string => `/api/auth/${seg(head)}/switch`;
export const accountPath = (head: string, label: string): string => `/api/auth/${seg(head)}/accounts/${seg(label)}`;
export const keyPath = (name: string): string => `/api/keys/${seg(name)}`;

// ── the pending-route rule ───────────────────────────────────────────────────────────────────────

/** What a 404 that is no handler's answer says: the bare status, or the router's own `not found`. */
const MISSING_ROUTE = /^(HTTP 404|not found)$/i;

/** A 404 nothing answered, or the daemon naming an unknown route, means the daemon does not serve it; any other failure is a real
 *  error and stays one. A 404 carrying a handler's own sentence (`unknown head`) is that handler refusing a thing it was asked
 *  about, so it is never a missing route. */
export function pendingOf(err: unknown, row: string): PendingRoute | null {
  if (!(err instanceof MgmtError)) return null;
  if ((err.status === 404 && MISSING_ROUTE.test(err.message.trim())) || /unknown route|no such route/i.test(err.message)) return { pending: row };
  return null;
}

export const isPendingRoute = (value: unknown): value is PendingRoute =>
  typeof value === 'object' && value !== null && 'pending' in value;

async function settle<T>(path: string, init: RequestInit, wrap: (result: T) => AuthActionOutcome): Promise<AuthActionState> {
  try {
    return wrap(await request<T>(path, init));
  } catch (err) {
    const pending = pendingOf(err, PENDING_AUTH_WRITES);
    if (pending !== null) return pending;
    throw err;
  }
}

// ── the writes ───────────────────────────────────────────────────────────────────────────────────

/** POST /api/auth/{head}/login: start a device or browser login for a new account on this head. The label
 *  is the operator's name for it and becomes the credential file's name. The answer is the login's
 *  STARTING view: its id to poll, and no code or link yet. */
export const startLogin = (head: string, label: string, place?: ClaudeLoginPlaceId): Promise<AuthActionState> =>
  settle<LoginStatusPayload>(place === undefined ? loginPath(head) : `/api/claude-logins/${seg(place)}/login`,
    { method: 'POST', body: JSON.stringify({ label }) }, (result) => ({ action: 'login', result }));

/** Refresh only the named Claude folder; the response is that place's row, not a head-wide switch. */
export const refreshClaudeLogin = (place: ClaudeLoginPlaceId): Promise<AccountWire> =>
  request<AccountWire>(`/api/claude-logins/${seg(place)}/refresh`, { method: 'POST' });

/** GET /api/auth/{head}/login/{id}: one login now. Its code or link once the flow announces them, then
 *  signed in (the credential is on disk), then live after restart, or failed with the daemon's reason. */
export async function fetchLoginStatus(head: string, id: string): Promise<LoginStatusPayload | PendingRoute> {
  try {
    return await request<LoginStatusPayload>(loginStatusPath(head, id));
  } catch (err) {
    const pending = pendingOf(err, PENDING_AUTH_WRITES);
    if (pending !== null) return pending;
    throw err;
  }
}

/** POST /api/auth/{head}/refresh: the head refreshes its credential. `{ ok: false, note }` is the daemon
 *  saying it did not happen. The route predates the rebuild, so a 404 is a real error here, not a pending row. */
export async function refreshLogin(head: string): Promise<AuthActionOutcome> {
  return { action: 'refresh', result: await request<AuthActionResult>(refreshPath(head), { method: 'POST' }) };
}

/** POST /api/auth/{head}/switch: pin the account the head takes from the NEXT turn. A pin, not a swap: a
 *  turn already streaming keeps its account. */
export const switchAccount = (head: string, label: string): Promise<AuthActionState> =>
  settle<SwitchPayload>(switchPath(head), { method: 'POST', body: JSON.stringify({ label }) }, (result) => ({ action: 'switch', result }));

/** DELETE /api/auth/{head}/switch: drop the head's pin, so the selector's own order picks again from the
 *  next turn. Idempotent. A daemon older than the route answers 405 (the path exists for POST): the same
 *  fact as a 404 here, this version cannot do it. */
export async function unpinAccount(head: string): Promise<AuthActionState> {
  try {
    return await settle<SwitchPayload>(switchPath(head), { method: 'DELETE' }, (result) => ({ action: 'unpin', result }));
  } catch (err) {
    if (err instanceof MgmtError && err.status === 405) return { pending: PENDING_AUTH_WRITES };
    throw err;
  }
}

/** DELETE /api/auth/{head}/accounts/{label}: remove a pooled account. Addressed by a head key the pool rides, which is how the daemon
 *  resolves the name; the kind (`chatgpt-oauth`) is no head and was a 404. */
export const removeAccount = (head: string, label: string): Promise<AuthActionState> =>
  settle<AccountMutationPayload>(accountPath(head, label), { method: 'DELETE' }, (result) => ({ action: 'remove', result }));

/** PATCH /api/auth/{head}/accounts/{label}: relabel a pooled account. Its windows, exclusions and pool
 *  position are keyed by the credential path, so a relabel loses none of them. */
export const relabelAccount = (head: string, label: string, nextLabel: string): Promise<AuthActionState> =>
  settle<AccountMutationPayload>(accountPath(head, label), { method: 'PATCH', body: JSON.stringify({ label: nextLabel }) }, (result) => ({ action: 'relabel', result }));

/** PUT /api/keys/{ENV}: store (or replace) a key. THE VALUE GOES ONE WAY: it is the body and nothing else;
 *  no cache holds it and no answer carries it. A refusal (400 name or value, 409 store) throws the daemon's words. */
export const putKey = (name: string, value: string): Promise<KeyState> =>
  request<KeyState>(keyPath(name), { method: 'PUT', body: JSON.stringify({ value }) });

/** DELETE /api/keys/{ENV}: remove a stored key; 404 when the store did not hold it. */
export const deleteKey = (name: string): Promise<KeyState> => request<KeyState>(keyPath(name), { method: 'DELETE' });

// ── hooks ────────────────────────────────────────────────────────────────────────────────────────

/** A write, then everything it may have changed is read again. */
function useAuthWrite<Vars, Out>(run: (vars: Vars) => Promise<Out>, stale: readonly (readonly string[])[]) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: run,
    // Awaited: an account switched, removed or relabelled, or a key stored, is done when its rows show it.
    onSettled: () => awaitRefetch(client, stale),
  });
}

const accountsChanged = [keys.accounts, keys.auth, keys.heads, keys.usage] as const;

/** Start a login. Resolves `{ action: 'login', result }` (poll `result.id`) or `{ pending }`. */
export const useStartLogin = () => useMutation({ mutationFn: ({ head, label, place }: { head: string; label: string; place?: ClaudeLoginPlaceId }) => startLogin(head, label, place) });

/**
 * Poll one login every LOGIN_POLL_MS while `enabled` (pass `polling(state)`). Data is the login's view or
 * `{ pending }`. The account joining the pool changes what the accounts pages read, so the poll that first
 * sees `live_after_restart` marks them stale. Keyed apart from the auth reads on purpose: invalidating
 * those must not restart this poll.
 */
export function useLoginStatus(head: string, id: string | null, enabled: boolean) {
  const client = useQueryClient();
  return useQuery({
    queryKey: loginKey(head, id ?? ''),
    enabled: enabled && id !== null,
    refetchInterval: LOGIN_POLL_MS,
    refetchIntervalInBackground: true,
    staleTime: 0,
    queryFn: async () => {
      const status = await fetchLoginStatus(head, id ?? '');
      if (!isPendingRoute(status) && status.state === 'live_after_restart') {
        await awaitRefetch(client, accountsChanged);
      }
      return status;
    },
  });
}

export const useRefreshLogin = () => useAuthWrite((head: string) => refreshLogin(head), [keys.auth, keys.accounts, keys.heads]);
export const useSwitchAccount = () => useAuthWrite(({ head, label }: { head: string; label: string }) => switchAccount(head, label), accountsChanged);
export const useUnpinAccount = () => useAuthWrite((head: string) => unpinAccount(head), accountsChanged);
export const useRemoveAccount = () =>
  useAuthWrite(({ head, label }: { head: string; label: string }) => removeAccount(head, label), accountsChanged);
export const useRelabelAccount = () =>
  useAuthWrite(({ head, label, next }: { head: string; label: string; next: string }) => relabelAccount(head, label, next), accountsChanged);

/** The key store: every key a head reads or the store holds, by name, never by value. */
export const useKeyStore = () => useQuery(read<KeysPayload>(keyStoreKey, '/api/keys'));
/** A key write changes what the heads read: the list, and the auth card whose masked key it prints. */
export const usePutKey = () => useAuthWrite(({ name, value }: { name: string; value: string }) => putKey(name, value), [keyStoreKey, keys.auth]);
export const useDeleteKey = () => useAuthWrite((name: string) => deleteKey(name), [keyStoreKey, keys.auth]);
