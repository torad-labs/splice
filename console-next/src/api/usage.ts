// What Usage reads and writes beyond `useUsage` (queries.ts): the token economics, spend budgets, alert
// settings, and the two add flows (Add a plan: POST /api/add and its steps; Add models: /api/add-model).
//
// Budgets and alerts are v0.4.0 rows: a daemon older than them answers 404, and the read resolves `{ pending }`
// instead of throwing, the page printing the honest empty. A write resolves `{ pending }` the same way; any other
// refusal rejects with the daemon's sentence, verbatim. Every write's cache takes the ANSWER, never the request: a value
// the daemon clamped or refused must not read as applied.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { isPendingRoute, pendingOf } from './auth';
import { MgmtError, request } from './client';
import { modelsKey } from './models';
import { topologyKey } from './config';
import { keys, read } from './queries';
import type { AlertSettings, AlertsSlice } from '../types/alerts';
import type { Budget, BudgetsPayload, BudgetsSlice, PendingRoute } from '../types/budget';
import type { AddChecked, AddChecksFailed, AddCheck, AddModelOffers, AddModelsAdded, AddProfilesPayload, AddRequest, AddView } from '../types/add';
import type { EconomicsPayload } from '../types/economics';

/** The v0.4.0 item that serves the budget and alert routes. */
export const PENDING_BUDGETS = 'V4-133';
export const PENDING_ALERTS = 'V4-133';

export const economicsKey = ['economics'] as const;
export const budgetsKey = ['budgets'] as const;
export const alertsKey = ['alerts'] as const;
export const addProfilesKey = ['add-profiles'] as const;
export const addModelOffersKey = ['add-model'] as const;
export const addKey = (id: string) => ['add', id] as const;

/** Hourly buckets: a 30 s poll is already far finer than the data's own resolution. */
export const ECONOMICS_POLL_MS = 30_000;
export const BUDGETS_POLL_MS = 30_000;
/** How often an open add is re-read: the credential is re-checked and the sign-in polled at each read. */
export const ADD_POLL_MS = 2500;

// ── economics ────────────────────────────────────────────────────────────────────────────────────

/** GET /api/economics: one hour of SUMS per head; every ratio is derived (lib/economics). */
export const useEconomics = () => useQuery(read<EconomicsPayload>(economicsKey, '/api/economics', { refetchInterval: ECONOMICS_POLL_MS }));

// ── budgets and alerts ───────────────────────────────────────────────────────────────────────────

async function orPending<T>(run: () => Promise<T>, row: string): Promise<T | PendingRoute> {
  try {
    return await run();
  } catch (err) {
    const pending = pendingOf(err, row);
    if (pending !== null) return pending;
    throw err;
  }
}

/** GET /api/budgets: every head's daily budget, or `{ pending }`. */
export const fetchBudgets = (): Promise<BudgetsSlice> => orPending(() => request<BudgetsPayload>('/api/budgets'), PENDING_BUDGETS);

export const useBudgets = () => useQuery({ queryKey: [...budgetsKey], queryFn: fetchBudgets, refetchInterval: BUDGETS_POLL_MS });

/** PUT /api/budgets: the WHOLE budget set. Answers the budgets the daemon now holds, or `{ pending }`. */
export const putBudgets = (budgets: readonly Budget[]): Promise<BudgetsSlice> =>
  orPending(() => request<BudgetsPayload>('/api/budgets', { method: 'PUT', body: JSON.stringify({ budgets }) }), PENDING_BUDGETS);

/** Write the budget set; the cache takes what the daemon answers (a refusal rejects and leaves the cache as it was). */
export function usePutBudgets() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: putBudgets,
    onSuccess: (answer) => client.setQueryData<BudgetsSlice>([...budgetsKey], answer),
  });
}

/** GET /api/alerts, or `{ pending }`. Settings change only when the operator changes them, so it is not polled. */
export const fetchAlerts = (): Promise<AlertsSlice> => orPending(() => request<AlertSettings>('/api/alerts'), PENDING_ALERTS);

export const useAlerts = () => useQuery({ queryKey: [...alertsKey], queryFn: fetchAlerts, refetchInterval: false });

/** PUT /api/alerts: the settings; answers what the daemon now holds, or `{ pending }`. */
export const putAlerts = (settings: AlertSettings): Promise<AlertsSlice> =>
  orPending(() => request<AlertSettings>('/api/alerts', { method: 'PUT', body: JSON.stringify(settings) }), PENDING_ALERTS);

export function usePutAlerts() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: putAlerts,
    onSuccess: (answer) => client.setQueryData<AlertsSlice>([...alertsKey], answer),
  });
}

/** POST /api/alerts/test: fire one test alert. `{ pending }` when the daemon does not serve it (the route is spelled
 *  after the section's "test send"; the pending state names the row, not the path). */
export const sendTestAlert = (): Promise<{ sent: true } | PendingRoute> =>
  orPending(async () => {
    await request<unknown>('/api/alerts/test', { method: 'POST' });
    return { sent: true as const };
  }, PENDING_ALERTS);

/** Send a test alert; a pending answer marks the whole alerts read pending, as the row that serves it is one. */
export function useTestAlert() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: sendTestAlert,
    onSuccess: (answer) => {
      if (isPendingRoute(answer)) client.setQueryData<AlertsSlice>([...alertsKey], answer);
    },
  });
}

// ── Add a plan (AddRoutes.kt) ────────────────────────────────────────────────────────────────────
// Every write answers the session view, or a refusal `{error}` whose status says which kind. A verify or save the
// checks refused is a 409 that also carries the rows, returned as a value (`{ failed }`) because the rows are the answer.

export const addPath = (id: string, step = ''): string => `/api/add/${encodeURIComponent(id)}${step}`;

/** A check refusal's rows, or null for any other failure. */
function checksFailed(err: unknown): AddChecksFailed | null {
  if (!(err instanceof MgmtError) || err.status !== 409) return null;
  const body = err.body;
  if (body === null || typeof body !== 'object' || !('checks' in body) || !Array.isArray(body.checks)) return null;
  return { error: err.message, checks: body.checks as AddCheck[] };
}

async function checked(run: () => Promise<AddView>): Promise<AddChecked> {
  try {
    return { view: await run() };
  } catch (err) {
    const failed = checksFailed(err);
    if (failed === null) throw err;
    return { failed };
  }
}

/** POST /api/add: open an add. 400, 404 (no such profile) and 409 (a taken key) reject with the daemon's sentence. */
export const openAdd = (body: AddRequest): Promise<AddView> => request<AddView>('/api/add', { method: 'POST', body: JSON.stringify(body) });

/** GET /api/add/{id}: the add as it stands. */
export const readAdd = (id: string): Promise<AddView> => request<AddView>(addPath(id));

/** POST /api/add/{id}/login: the view once the sign-in runs; 409 for a head that signs in another way. */
export const signInAdd = (id: string): Promise<AddView> => request<AddView>(addPath(id, '/login'), { method: 'POST' });

/** POST /api/add/{id}/verify: the CLI's checks; `live` adds the one short turn a key-signed profile allows. */
export const verifyAdd = (id: string, live: boolean): Promise<AddChecked> =>
  checked(() => request<AddView>(addPath(id, '/verify'), { method: 'POST', body: JSON.stringify(live ? { live: true } : {}) }));

/** POST /api/add/{id}/save: the checks again, the write, the wrapper, then the restart the daemon takes after it has answered. */
export const saveAdd = (id: string): Promise<AddChecked> => checked(() => request<AddView>(addPath(id, '/save'), { method: 'POST' }));

/** DELETE /api/add/{id}: close an add that will not be saved. */
export async function discardAdd(id: string): Promise<void> {
  await request<{ discarded: string }>(addPath(id), { method: 'DELETE' });
}

/** GET /api/add/profiles: the profiles an add can start from. Read once. */
export const useAddProfiles = () =>
  useQuery({ ...read<AddProfilesPayload>(addProfilesKey, '/api/add/profiles', { refetchInterval: false }), select: (payload) => payload.profiles });

/** One open add, polled every ADD_POLL_MS. `loginActive` keeps the poll running in a background tab: the sign-in
 *  tab takes focus and hides the console, and the add would otherwise never learn the login finished. */
export const useAddView = (id: string | null, loginActive = false) =>
  useQuery({
    queryKey: [...addKey(id ?? '')],
    queryFn: () => readAdd(id ?? ''),
    enabled: id !== null,
    refetchInterval: ADD_POLL_MS,
    refetchIntervalInBackground: loginActive,
  });

/** A write that answers the add's view: the cache takes it. */
function useAddWrite<Vars, Out>(run: (vars: Vars) => Promise<Out>, viewOf: (out: Out) => AddView | null, stale: readonly (readonly string[])[] = []) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: run,
    onSuccess: (out) => {
      const view = viewOf(out);
      if (view !== null) client.setQueryData<AddView>([...addKey(view.id)], view);
    },
    onSettled: () => Promise.all(stale.map((key) => client.invalidateQueries({ queryKey: [...key] }))),
  });
}

const viewOfChecked = (out: AddChecked): AddView | null => ('view' in out ? out.view : null);

/** Open an add: its id is `answer.id`, the key of every step after. */
export const useOpenAdd = () => useAddWrite(openAdd, (view) => view);
export const useSignInAdd = () => useAddWrite(signInAdd, (view) => view);
export const useVerifyAdd = () => useAddWrite(({ id, live }: { id: string; live: boolean }) => verifyAdd(id, live), viewOfChecked);
/** A save writes splice.toml, links the wrapper and restarts the daemon: the heads, the catalog and the topology read again. */
export const useSaveAdd = () =>
  useAddWrite(saveAdd, viewOfChecked, [keys.heads, keys.status, keys.auth, keys.usage, modelsKey, topologyKey]);

export function useDiscardAdd() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: discardAdd,
    onSuccess: (_, id) => client.removeQueries({ queryKey: [...addKey(id)] }),
  });
}

// ── Add models (AddModelRoutes.kt) ───────────────────────────────────────────────────────────────

/** GET /api/add-model: each OpenRouter head with the catalogue models it does not reach yet. A splice.toml that does
 *  not load is a 409 whose sentence names the file. */
export const useAddModelOffers = () => useQuery(read<AddModelOffers>(addModelOffersKey, '/api/add-model', { refetchInterval: false }));

/** POST /api/add-model: `ids` onto `head`'s roster, then the restart that makes them reachable. A refusal rejects with
 *  the daemon's one sentence (400 no ids, 404 no such head, 409 an id no longer on offer or a file that changed under
 *  the write, 503 unwired). */
export const addModels = (head: string, ids: readonly string[]): Promise<AddModelsAdded> =>
  request<AddModelsAdded>('/api/add-model', { method: 'POST', body: JSON.stringify({ head, models: ids }) });

export function useAddModels() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ head, ids }: { head: string; ids: readonly string[] }) => addModels(head, ids),
    onSettled: () =>
      Promise.all([addModelOffersKey, modelsKey, topologyKey, keys.heads, keys.status].map((key) => client.invalidateQueries({ queryKey: [...key] }))),
  });
}
