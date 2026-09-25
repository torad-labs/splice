// The control-plane HTTP client — the ONLY module in the app that talks to the
// network. Importable solely from entity api segments (lint-enforced boundary;
// an ast-grep wall additionally forbids fetch() anywhere else in console/src).
// Served same-origin by the control server (spliced), which also hosts this
// dashboard at /; the bearer key is the state root's mgmt-key. `splice dashboard`
// opens the console with it in the address's fragment (takeLaunchKey below), so
// the operator never pastes it; the gate's paste is the fallback.

import type { WriteResult } from '@shared/lib';
import { H } from './strings';

const KEY_STORAGE = 'myx-mgmt-key';

export class MgmtError extends Error {
  status: number;
  /** The daemon's whole error envelope, when it sent one: an entity reads a structured field of its
   *  own route's refusal (a transcript's `searched` directories) rather than matching the sentence. */
  readonly body: unknown;
  constructor(status: number, message: string, body: unknown = null) {
    super(message);
    this.status = status;
    this.body = body;
  }
}

function pastedKey(): string {
  try {
    return localStorage.getItem(KEY_STORAGE) ?? '';
  } catch {
    return '';
  }
}

/** The bearer every request carries, resolved on every call: the key handed over THIS session (by
 *  `splice dashboard` or the gate), then the one kept from before. Module state as well as storage:
 *  in a private window the key never reaches localStorage, and the event stream re-reading storage
 *  alone sent an empty bearer. */
export function currentKey(): string {
  return sessionKey || pastedKey();
}

export function storeKey(key: string): void {
  try {
    localStorage.setItem(KEY_STORAGE, key.trim());
  } catch { /* private mode: key lives for the session via module state below */ }
  sessionKey = key.trim();
  locked = false; // re-arm the pollers; the next tick retries with the new key
}

let sessionKey = '';

// While locked (last response was 401) requests short-circuit without touching
// the network — no poller 401 spam behind the key gate. storeKey() re-arms.
let locked = false;

type UnauthorizedListener = () => void;
let onUnauthorized: UnauthorizedListener | null = null;

/** The key `splice dashboard` hands the page it opens, as `#k=<key>`: a fragment never reaches the
 *  daemon or a log, and the CLI writes it only into an owner-only redirect page (DashboardCommand).
 *  Taken once at load, before the hash router reads the address, kept like a pasted key, and
 *  replaced in the address so it is not left in view, in a copied link or in the tab's history
 *  entry. The daemon never serves the key: every local process can read what it serves. */
function takeLaunchKey(): void {
  if (typeof location === 'undefined' || typeof history === 'undefined') return;
  const handed = /^#k=([^&/]+)$/.exec(location.hash);
  if (handed === null) return;
  storeKey(decodeURIComponent(handed[1]));
  history.replaceState(history.state, '', `${location.pathname}${location.search}#/`);
}
takeLaunchKey();
export function bindUnauthorized(fn: UnauthorizedListener): void {
  onUnauthorized = fn;
}

/** A 401 seen outside request<T> (the events stream reads its own response) lands the same lock. */
export function noteUnauthorized(): void {
  locked = true;
  onUnauthorized?.();
}

/** BOTH envelopes, because both are real and this client sees both. The PROXY ports answer
 *  Anthropic-shaped (`{"error": {"message": …}}`); the control plane this client actually talks to
 *  answers `{"error": "<sentence>"}` — one `buildJsonObject { put("error", message) }` at every
 *  refusal site in `splice.control.api`. */
type ErrorBody = { error?: string | { message?: string } };

/** V4-175: reading only `error.message` dropped every control-plane refusal on the floor — a string
 *  has no `.message`, so the sentence the daemon wrote became `HTTP 409` on screen for all of them.
 *  Found on POST /api/claude-head/wrap, where "claude is not currently wrapped" and "the
 *  'claude-splice' head is not configured" ARE the whole answer; it was never claude-head's bug. */
function errorMessage(body: unknown, status: number): string {
  const error = (body as ErrorBody | null)?.error;
  const sentence = typeof error === 'string' ? error : error?.message;
  return sentence !== undefined && sentence.trim() !== '' ? sentence : `HTTP ${status}`;
}

/** What a read says when the daemon did not answer at all. The browser's own words for a refused
 *  connection ("Failed to fetch", "NetworkError when attempting to fetch resource.", "Load failed",
 *  one per engine) name the transport and not the fact, and every page printed them verbatim
 *  (console walkthrough, 2026-09-24). Mapped here, once, so no store ever holds them; status 0 is
 *  the response that never came. */
const NOT_ANSWERING = H.notAnswering;

/** What an HTTP header value may hold here: printable ASCII, no space. */
const HEADER_SAFE = /^[\x21-\x7e]*$/;

// Exported for entity api segments (entities/*/api), which own their routes and payload types
// locally (CONTRACTS.md section 8); the key, the 401 lockout and the error envelope stay here.
// `control` below keeps the routes that predate the console rebuild.
export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  if (locked) throw new MgmtError(401, 'management key required');
  // A key a header cannot carry never reaches the daemon: fetch throws on it before sending, and that
  // throw read as `splice is not answering` on every page. A key pasted from rich text is the usual
  // one (a smart quote, a zero-width space). The daemon's keys are printable ASCII, so the gate
  // reopens and asks for it again (code review, 2026-09-24).
  if (!HEADER_SAFE.test(currentKey())) {
    noteUnauthorized();
    throw new MgmtError(401, 'management key required');
  }
  let res: Response;
  try {
    res = await fetch(path, {
      ...init,
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${currentKey()}`,
        ...(init?.headers ?? {}),
      },
    });
  } catch (err) {
    // A caller's own abort stays an abort; anything else thrown before a response is no answer.
    if (init?.signal?.aborted) throw err;
    throw new MgmtError(0, NOT_ANSWERING);
  }
  if (res.status === 401) {
    locked = true;
    onUnauthorized?.();
    throw new MgmtError(401, 'management key required');
  }
  const body = (await res.json().catch(() => null)) as T | ErrorBody | null;
  if (!res.ok) {
    throw new MgmtError(res.status, errorMessage(body, res.status), body);
  }
  return body as T;
}

// A route the daemon does not serve yet: the store carries the v0.4.0 row that will serve it
// and the page prints the honest empty naming it (CONTRACTS.md sections 4 and 8). Shared here
// so every entity maps the same daemon answers to the same shape (hoisted from M2-D1's slices).
export interface PendingRoute {
  pending: string;
}

/** 404, or the daemon naming an unknown route, means "not built yet" for a route that is still
 *  a v0.4.0 row; any other failure is a real error the store must show as one. */
export function pendingOf(err: unknown, row: string): PendingRoute | null {
  if (!(err instanceof MgmtError)) return null;
  if (err.status === 404 || /unknown route|no such route/i.test(err.message)) return { pending: row };
  return null;
}

/** A write's failure as its result (WriteResult, shared/lib): a route not built yet is `pending` on
 *  `row`, anything else is `failed` with the error's own words. */
export function writeFailure(err: unknown, row: string): WriteResult<never> {
  const pending = pendingOf(err, row);
  if (pending !== null) return { status: 'pending', item: pending.pending };
  return { status: 'failed', reason: err instanceof Error ? err.message : String(err) };
}

// ── payload types ────────────────────────────────────────────────────────────

export interface RegistryEntry {
  key: string;
  label: string;
  authKind: string;
  /** The provider's vendor family (`openai`, `local`, ...), null where the daemon names none; an
   *  older daemon omits it. The console colours a head by it. */
  family?: string | null;
}

export interface ControlStatusPayload {
  server: string;
  version: string;
  heads: string[];
  registry: RegistryEntry[];
}

export interface GateLive {
  label: string;
  compact: boolean;
  phase: 'connect' | 'streaming' | string;
  age_ms: number;
  idle_ms: number;
}

export interface GateSnapshot {
  inflight: number;
  queued: number;
  max: number | 'unlimited';
  acquired: number;
  released: number;
  waited: number;
  avg_wait_ms: number;
  live: GateLive[];
  stream_idle_ms: number;
}

/** G20: passive per-head health counters, local-origin vs provider-error split (diagnosis only). */
export interface HeadHealthCounters {
  localOriginErrors: number;
  providerErrors: number;
}

export interface HeadStatus {
  key: string;
  label: string;
  name: string;
  port: number;
  authKind: string;
  wantVersion: string;
  running: boolean;
  healthy: boolean;
  version: string | null;
  versionMatch: boolean | null;
  mode: string | null;
  gate: GateSnapshot | null;
  maxInflight: number | null;
  health: HeadHealthCounters;
  pids: number[];
}

/** The lifecycle endpoints return the fresh head status plus a transient note. */
export interface HeadActionResult extends HeadStatus {
  started?: boolean;
  stopped?: boolean;
  note?: string;
  logPath?: string;
}

export interface HeadsPayload {
  heads: HeadStatus[];
}

export type ConfigValue = string | number | boolean | null;
export type EffectiveConfig = Record<string, ConfigValue>;

export interface ConfigPayload {
  effective: EffectiveConfig;
  /** JW-06: present when the view was fetched for one head (?head=<key>). */
  head?: string;
  layers: {
    defaults: EffectiveConfig;
    toml: EffectiveConfig;
    /** JW-06: headKey -> its [heads.<key>.overrides] knobs; only override-carrying heads appear.
     * Precedence position: directly above the global TOML layer. */
    perHead: Record<string, EffectiveConfig>;
    file: EffectiveConfig;
    env: EffectiveConfig;
    runtime: EffectiveConfig;
  };
  restart_required_keys: string[];
  source: string;
}

export interface ConfigPatchTarget {
  key: string;
  ok: boolean;
}

export interface PatchResult {
  applied: EffectiveConfig;
  rejected: Record<string, string>;
  restart_required: string[];
  targets: ConfigPatchTarget[];
  persisted: string;
}

export interface RatelimitState {
  limit_tokens: number;
  remaining_tokens: number | null;
  reset_tokens: string | null;
}

export type WarnLevel = 'ok' | 'warn' | 'critical';

export interface UsageWarn {
  level: WarnLevel;
  pct: number;
  source: string;
  reset: string | null;
}

/** One plan window as /api/usage writes it (UsagePayloads.window). Times are epoch SECONDS.
 *  `observed_at` is when splice read the figure; the daemon serves it from the data-wire change
 *  on, so an older daemon leaves it out. */
export interface QuotaWindow {
  used_pct: number;
  resets_at: number | null;
  observed_at?: number | null;
}

/** The head's plan windows (QuotaView): absent when the head tracks none. */
export interface HeadQuota {
  plan?: string;
  five_hour?: QuotaWindow;
  seven_day?: QuotaWindow;
}

export interface HeadUsage {
  output_tokens_5h: number;
  entries: number;
  /** The plan's own windows, which `warn` does not read: warn is computed from the rate-limit
   *  headers and the 5h token count only (UsageWarnPolicy.computeUsageWarn). */
  quota?: HeadQuota;
  ratelimit: RatelimitState | null;
  warn: UsageWarn;
}

export interface HeadUsageEntry {
  key: string;
  label: string;
  usage: HeadUsage | null;
}

export interface UsagePayload {
  window_hours: number;
  warn_pct: number;
  warn_tokens_5h: number;
  heads: HeadUsageEntry[];
}

export interface CompactRow {
  head: string;
  ts: number;
  outcome?: string;
  chars?: number;
  ms?: number;
  error?: string;
  /** The instructions the compaction ran under: `client` for the client's own, else a rule's
   *  source label (StreamCompact.kt writes it from TurnMeta.compactionInstructionsSource). */
  instructions_source?: string;
}

/** One head's compaction counts and the span of rows they came from (#226). `first_ts`/`last_ts`
 *  are absent for a head with no rows: no rows, no span to claim. */
export interface CompactHeadStats {
  total: number;
  by_outcome: Record<string, number>;
  by_outcome_7d?: Record<string, number>;
  first_ts?: number;
  last_ts?: number;
}

export interface CompactPayload {
  stats: {
    total: number;
    by_outcome: Record<string, number>;
    tail: CompactRow[];
    /** The last seven days among the counted rows; absent on a daemon older than #226. */
    by_outcome_7d?: Record<string, number>;
    heads?: Record<string, CompactHeadStats>;
  };
}

/** What the daemon knows of a credential (V4-220 6b, CredentialVerdictJson.kt): `held` when splice
 *  holds it itself and `present` is the whole fact; for a client head's forwarded Claude login,
 *  `unverified` until upstream answers a forwarded turn, then `accepted` or `rejected` at the time of
 *  that answer. A 403, 429, 5xx or 400 leaves the last verdict standing. */
export type CredentialVerdict =
  | { state: 'held' }
  | { state: 'unverified' }
  | { state: 'accepted' | 'rejected'; at_epoch_ms: number };

export interface ProviderAuth {
  kind: string;
  login: string;
  /** False only when the credential is known missing: for a client head, once upstream rejected it. */
  present: boolean;
  /** Absent on a daemon older than V4-220 6b, whose client heads said `present` unconditionally. */
  verdict?: CredentialVerdict;
  account_id_masked?: string;
  last_refresh?: string;
  auth_path?: string;
  refresh_latched?: string;
  /** api-key heads: the variable the key is read from, and the key masked (`sk-f…ee94`). */
  env_var?: string;
  api_key_masked?: string;
  /** The head's key_file, path only: read before the key store, after the variable. */
  key_file?: string;
}

export type AuthPayload = Record<string, ProviderAuth>;

/** POST /api/auth/:head/refresh|login — a transient outcome, not the full card
 * (callers re-fetch /api/auth for the authoritative card state). */
export interface AuthActionResult {
  ok: boolean;
  head?: string;
  note?: string;
}

export interface LogsPayload {
  key: string;
  path: string;
  lines: string[];
  note?: string;
}

/** One hour of a head's token economics. SUMS ONLY — the daemon deliberately ships no ratios,
 * so every rate on screen is derived here and stays recomputable when the window changes. */
export interface EconomicsBucket {
  hour: number;
  turns: number;
  in_tokens: number;
  cached_tokens: number;
  /** V4-86: the cache-WRITE half of in_tokens, disjoint from cached_tokens (the read half). Its
   * own field because it bills at the vendor's cache_write rate, not the input rate — and because
   * netting it into either of the other two would make a read and a write indistinguishable here.
   * Absent on a bucket the daemon loaded from a pre-V4-86 economics file, where it reads as 0. */
  cache_write_tokens: number;
  out_tokens: number;
  req_bytes: number;
  upstream_req_bytes: number;
  tools_eager: number;
  tools_deferred: number;
  /** Turns that REPORTED a tool partition. 0 on a dialect that cannot defer — which the ledger
   * must render as "n/a", never as a deferral rate of zero. */
  deferral_turns: number;
  rate_limited: number;
  /** V4-221: the hour's dollars, each turn priced by the daemon at its own model's card. Null is
   *  "not priced then", an hour recorded before the daemon priced turns, and never $0. Absent from
   *  a daemon older than V4-221, which read the same: not priced. */
  cost_usd?: number | null;
  /** V4-221: turns whose model had no rate card; their dollars are not in cost_usd. Absent beside
   *  an absent cost_usd, when every turn of the hour is unpriced. */
  unpriced_turns?: number;
}

export interface HeadEconomics {
  key: string;
  label: string;
  /** The provider's own x-ratelimit-limit-tokens, or null where it sends none. A null ceiling
   * renders as "no ceiling known" — never as a guess. */
  ceiling_tokens: number | null;
  buckets: EconomicsBucket[];
}

export interface EconomicsPayload {
  retention_hours: number;
  generated_at: number;
  heads: HeadEconomics[];
}

// ── endpoints ────────────────────────────────────────────────────────────────

export const control = {
  status: () => request<ControlStatusPayload>('/api/status'),
  heads: () => request<HeadsPayload>('/api/heads'),
  startHead: (head: string) => request<HeadActionResult>(`/api/heads/${head}/start`, { method: 'POST' }),
  stopHead: (head: string) => request<HeadActionResult>(`/api/heads/${head}/stop`, { method: 'POST' }),
  restartHead: (head: string) => request<HeadActionResult>(`/api/heads/${head}/restart`, { method: 'POST' }),
  config: (head?: string) =>
    request<ConfigPayload>(head ? `/api/config?head=${encodeURIComponent(head)}` : '/api/config'),
  patchConfig: (patch: Record<string, ConfigValue>) =>
    request<PatchResult>('/api/config', { method: 'PATCH', body: JSON.stringify(patch) }),
  usage: () => request<UsagePayload>('/api/usage'),
  auth: () => request<AuthPayload>('/api/auth'),
  refreshAuth: (head: string) => request<AuthActionResult>(`/api/auth/${head}/refresh`, { method: 'POST' }),
  compact: () => request<CompactPayload>('/api/compact'),
  economics: () => request<EconomicsPayload>('/api/economics'),
  logs: (head: string, tail: number) => request<LogsPayload>(`/api/logs/${head}?tail=${tail}`),
};

// JW-04: the unauthenticated /health probe carries topologyStale — the daemon re-compares its
// booted splice.toml digest against the file on disk per request (fail-open on unreadable).
// An edited-but-inert topology used to be invisible everywhere; the config page banners it.
export async function fetchTopologyStale(): Promise<boolean> {
  try {
    const res = await fetch('/health');
    const body = (await res.json()) as { topologyStale?: boolean };
    return body.topologyStale === true;
  } catch {
    return false; // fail open — a health hiccup must never block the page
  }
}
