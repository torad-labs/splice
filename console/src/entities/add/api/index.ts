// The add's HTTP segment (AddRoutes.kt). Every write answers with the session view, or a refusal
// `{error}` whose status says which kind; a verify or save the checks refused is a 409 that also
// carries the rows, returned here as a value because the rows are the answer, not a fault.
import { MgmtError, request } from '@shared/api';
import type { AddChecksFailed, AddModelOffers, AddModelsAdded, AddProfile, AddProfilesPayload, AddRequest, AddView } from '../model/types';

function path(id: string, step = ''): string {
  return `/api/add/${encodeURIComponent(id)}${step}`;
}

export async function fetchAddProfiles(): Promise<AddProfile[]> {
  return (await request<AddProfilesPayload>('/api/add/profiles')).profiles;
}

/** POST /api/add: 200 with the new add's view. 400, 404 (no such profile) and 409 (a taken key)
 *  throw the daemon's sentence. */
export function openAdd(body: AddRequest): Promise<AddView> {
  return request<AddView>('/api/add', { method: 'POST', body: JSON.stringify(body) });
}

/** GET /api/add/{id}: the add as it stands, the credential re-read and the sign-in polled. */
export function readAdd(id: string): Promise<AddView> {
  return request<AddView>(path(id));
}

/** POST /api/add/{id}/login: the view once the sign-in runs; 409 for a head that signs in another way. */
export function signInAdd(id: string): Promise<AddView> {
  return request<AddView>(path(id, '/login'), { method: 'POST' });
}

/** A check refusal's rows, or null for any other failure. */
function checksFailed(err: unknown): AddChecksFailed | null {
  if (!(err instanceof MgmtError) || err.status !== 409) return null;
  const body = err.body;
  if (body === null || typeof body !== 'object' || !('checks' in body) || !Array.isArray(body.checks)) return null;
  return { error: err.message, checks: body.checks as AddChecksFailed['checks'] };
}

async function checked(run: () => Promise<AddView>): Promise<{ view: AddView } | { failed: AddChecksFailed }> {
  try {
    return { view: await run() };
  } catch (err) {
    const failed = checksFailed(err);
    if (failed === null) throw err;
    return { failed };
  }
}

/** POST /api/add/{id}/verify: the CLI's checks; `live` adds the one short turn a key-signed profile
 *  allows. A failed check is an answer with its rows. */
export function verifyAdd(id: string, live: boolean): Promise<{ view: AddView } | { failed: AddChecksFailed }> {
  return checked(() => request<AddView>(path(id, '/verify'), { method: 'POST', body: JSON.stringify(live ? { live: true } : {}) }));
}

/** POST /api/add/{id}/save: the checks again, the write, the wrapper, then the restart, which the
 *  daemon takes after it has answered. */
export function saveAdd(id: string): Promise<{ view: AddView } | { failed: AddChecksFailed }> {
  return checked(() => request<AddView>(path(id, '/save'), { method: 'POST' }));
}

/** DELETE /api/add/{id}: close an add that will not be saved. */
export async function discardAdd(id: string): Promise<void> {
  await request<{ discarded: string }>(path(id), { method: 'DELETE' });
}

/** GET /api/add-model (AddModelRoutes.kt): each OpenRouter head with the catalogue models it does not
 *  reach yet. A splice.toml that does not load is a 409 whose sentence names the file. */
export async function fetchAddModelOffers(): Promise<AddModelOffers> {
  return request<AddModelOffers>('/api/add-model');
}

/** POST /api/add-model: [ids] onto [head]'s roster, then the restart that makes them reachable. A
 *  refusal rejects with the daemon's one sentence (400 no ids, 404 no such head, 409 an id not on offer
 *  any more or a file that changed under the write, 503 unwired). */
export async function addModels(head: string, ids: readonly string[]): Promise<AddModelsAdded> {
  return request<AddModelsAdded>('/api/add-model', { method: 'POST', body: JSON.stringify({ head, models: ids }) });
}
