// The mgmt HTTP client — the ONLY module in the app that talks to the network.
// Importable solely from entity api segments (lint-enforced boundary; an
// ast-grep wall additionally forbids fetch() anywhere else in webui/src).
// Served same-origin by the proxy at /dashboard; the bearer key comes from
// ~/.claude-codex/state/mgmt-key, pasted once by the operator.

const KEY_STORAGE = 'myx-mgmt-key';

export class MgmtError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

export function getStoredKey(): string {
  try {
    return localStorage.getItem(KEY_STORAGE) ?? '';
  } catch {
    return '';
  }
}

export function storeKey(key: string): void {
  try {
    localStorage.setItem(KEY_STORAGE, key.trim());
  } catch { /* private mode: key lives for the session via module state below */ }
  sessionKey = key.trim();
}

let sessionKey = getStoredKey();

type UnauthorizedListener = () => void;
let onUnauthorized: UnauthorizedListener | null = null;
export function bindUnauthorized(fn: UnauthorizedListener): void {
  onUnauthorized = fn;
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${sessionKey}`,
      ...(init?.headers ?? {}),
    },
  });
  if (res.status === 401) {
    onUnauthorized?.();
    throw new MgmtError(401, 'management key required');
  }
  const body = (await res.json().catch(() => null)) as T | { error?: { message?: string } } | null;
  if (!res.ok) {
    const message = (body as { error?: { message?: string } } | null)?.error?.message ?? `HTTP ${res.status}`;
    throw new MgmtError(res.status, message);
  }
  return body as T;
}

// ── payload types ────────────────────────────────────────────────────────────

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

export interface StatusPayload {
  proxy: string;
  version: string;
  uptime_s: number;
  gate?: GateSnapshot;
  mode?: string;
  upstream?: string;
  pinned_model?: string;
  show_reasoning?: string;
  effort_fallback?: string | null;
  summary_fallback?: string | null;
}

export type ConfigValue = string | number | boolean | null;
export type EffectiveConfig = Record<string, ConfigValue>;

export interface ConfigPayload {
  effective: EffectiveConfig;
  layers: {
    defaults: EffectiveConfig;
    file: EffectiveConfig;
    env: EffectiveConfig;
    runtime: EffectiveConfig;
  };
  restart_required_keys: string[];
}

export interface PatchResult {
  applied: EffectiveConfig;
  rejected: Record<string, string>;
  restart_required: string[];
  effective: EffectiveConfig;
}

export interface RatelimitState {
  limit_tokens: number;
  remaining_tokens: number | null;
  reset_tokens: string | null;
  updated_at: number;
}

export interface UsagePayload {
  window_hours: number;
  entries: number;
  output_tokens_5h: number;
  ratelimit: RatelimitState | null;
}

export interface CompactRow {
  ts: number;
  outcome?: string;
  chars?: number;
  ms?: number;
  status?: number;
  error?: string;
}

export interface ShadowRow {
  ts: number;
  compact: boolean;
  has_marker: boolean;
  tool_count: number;
  sys_len: number;
  model: string;
}

export interface CompactPayload {
  stats: { total: number; by_outcome: Record<string, number>; tail: CompactRow[] };
  shadow: ShadowRow[];
}

export interface AuthPayload {
  present: boolean;
  cached?: boolean;
  cache_age_ms?: number | null;
  account_id_masked?: string | null;
  last_refresh?: string | null;
  expires_at?: number | null;
  auth_path?: string;
  cred_path?: string;
  refreshed?: boolean;
}

export interface LogsPayload {
  path: string;
  lines: string[];
  note?: string;
}

export interface ModelOption {
  value: string;
  label: string;
  description: string;
  context_window: number;
}

export interface ModelsPayload {
  catalog: ModelOption[];
  context_windows: Record<string, number>;
  pinned: string;
  discovery: string[];
}

// ── endpoints ────────────────────────────────────────────────────────────────

export const mgmt = {
  status: () => request<StatusPayload>('/mgmt/status'),
  config: () => request<ConfigPayload>('/mgmt/config'),
  patchConfig: (patch: Record<string, ConfigValue>) =>
    request<PatchResult>('/mgmt/config', { method: 'PATCH', body: JSON.stringify(patch) }),
  usage: () => request<UsagePayload>('/mgmt/usage'),
  compact: () => request<CompactPayload>('/mgmt/compact'),
  auth: () => request<AuthPayload>('/mgmt/auth'),
  refreshAuth: () => request<AuthPayload>('/mgmt/auth/refresh', { method: 'POST' }),
  logs: (tail: number) => request<LogsPayload>(`/mgmt/logs?tail=${tail}`),
  models: () => request<ModelsPayload>('/mgmt/models'),
};
