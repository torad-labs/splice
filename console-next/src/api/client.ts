// The control-plane HTTP client: the only file that touches the network (lint forbids fetch anywhere
// else). The page is served same-origin by the daemon, which also serves /api; the bearer is the
// state root's management key. `splice dashboard` opens the console with it in the address's
// fragment (#k=<key>), so nobody pastes it; the unlock screen is the fallback.

import { W } from '../lib/words';

const KEY_STORAGE = 'myx-mgmt-key';

export class MgmtError extends Error {
  readonly status: number;
  /** The daemon's whole error envelope, when it sent one: a page reads a structured field of its
   *  own route's refusal rather than matching the sentence. */
  readonly body: unknown;
  constructor(status: number, message: string, body: unknown = null) {
    super(message);
    this.status = status;
    this.body = body;
  }
}

/** What a read says when the daemon did not answer at all, in place of the browser's own words
 *  for a refused connection. Status 0 is the response that never came. */
export const NOT_ANSWERING = W.notAnswering;

/** What an HTTP header value may hold here: printable ASCII, no space. */
const HEADER_SAFE = /^[\x21-\x7e]*$/;

function keptKey(): string {
  try {
    return localStorage.getItem(KEY_STORAGE) ?? '';
  } catch {
    return '';
  }
}

// Module state as well as storage: in a private window the key never reaches localStorage.
let sessionKey = '';
// While locked (the last answer was a 401) requests short-circuit without touching the network, so
// no poller spams 401s behind the unlock screen. storeKey re-arms.
let locked = false;
const listeners = new Set<() => void>();
const notify = (): void => listeners.forEach((fn) => fn());

/** The bearer every request carries: the key handed over this session, then the one kept before. */
export function currentKey(): string {
  return sessionKey || keptKey();
}

export function storeKey(key: string): void {
  const trimmed = key.trim();
  try {
    localStorage.setItem(KEY_STORAGE, trimmed);
  } catch {
    /* private mode: the key lives for the session in module state */
  }
  sessionKey = trimmed;
  locked = false;
  notify();
}

/** True while the page has no key the daemon accepts: the unlock screen shows. */
export function isLocked(): boolean {
  return locked || currentKey() === '';
}

export function subscribeLock(fn: () => void): () => void {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
}

/** A 401 seen outside `request` (the event stream reads its own response) lands the same lock.
 *  `carried` is the key the refused request sent: a refusal of a key that is no longer the held
 *  one is the late answer of a request from before the last unlock, and locks nothing. */
export function noteUnauthorized(carried: string = currentKey()): void {
  if (carried !== currentKey()) return;
  locked = true;
  notify();
}

/** The key `splice dashboard` hands the page as `#k=<key>`: a fragment never reaches the daemon or a
 *  log. Taken once at load, kept like a pasted key, and replaced in the address so it is not left in
 *  view, in a copied link or in the tab's history entry. */
export function takeLaunchKey(): void {
  const handed = /^#k=([^&/]+)$/.exec(location.hash);
  if (handed?.[1] === undefined) return;
  storeKey(decodeURIComponent(handed[1]));
  history.replaceState(history.state, '', `${location.pathname}${location.search}#/`);
}

/** BOTH envelopes, because both are real: the proxy ports answer Anthropic-shaped
 *  (`{"error": {"message": …}}`), the control plane answers `{"error": "<sentence>"}`. */
type ErrorBody = { error?: string | { message?: string } };

export function errorMessage(body: unknown, status: number): string {
  const error = (body as ErrorBody | null)?.error;
  const sentence = typeof error === 'string' ? error : error?.message;
  return sentence !== undefined && sentence.trim() !== '' ? sentence : `HTTP ${status}`;
}

export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  if (locked) throw new MgmtError(401, 'management key required');
  // A key a header cannot carry never reaches the daemon (fetch throws on it, which would read as
  // "not answering"): the unlock screen reopens and asks again.
  if (!HEADER_SAFE.test(currentKey())) {
    noteUnauthorized();
    throw new MgmtError(401, 'management key required');
  }
  const carried = currentKey();
  let res: Response;
  try {
    res = await fetch(path, {
      ...init,
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${carried}`, ...init?.headers },
    });
  } catch (err) {
    if (init?.signal?.aborted) throw err;
    throw new MgmtError(0, NOT_ANSWERING);
  }
  if (res.status === 401) {
    noteUnauthorized(carried);
    throw new MgmtError(401, 'management key required');
  }
  const body = (await res.json().catch(() => null)) as T | ErrorBody | null;
  if (!res.ok) throw new MgmtError(res.status, errorMessage(body, res.status), body);
  return body as T;
}

/** The page the daemon serves at `/` now, straight from the daemon and never the cache; null when it did not answer. */
export async function servedPage(): Promise<string | null> {
  try {
    const res = await fetch('/', { cache: 'no-store' });
    return res.ok ? await res.text() : null;
  } catch {
    return null;
  }
}

/** GET /health is open (no bearer): the daemon's own answer to "are you there, and is your topology stale". */
export async function health<T>(): Promise<T> {
  let res: Response;
  try {
    res = await fetch('/health');
  } catch {
    throw new MgmtError(0, NOT_ANSWERING);
  }
  if (!res.ok) throw new MgmtError(res.status, `HTTP ${res.status}`);
  return (await res.json()) as T;
}

/** The event stream: a streaming fetch, because the route is bearer-guarded and EventSource cannot send a header. */
export async function openStream(signal: AbortSignal, lastId: number | null): Promise<Response> {
  const carried = currentKey();
  const headers: Record<string, string> = { Authorization: `Bearer ${carried}`, Accept: 'text/event-stream' };
  if (lastId !== null) headers['Last-Event-ID'] = String(lastId);
  const res = await fetch('/api/events', { headers, signal });
  if (res.status === 401) noteUnauthorized(carried);
  return res;
}

/** What a failed call says to a person: the daemon's own sentence, or the browser's. */
export const failureText = (err: unknown): string => (err instanceof Error ? err.message : String(err));
