// Payload types of the control plane, typed from the daemon's own routes. Times are as the daemon writes them
// (epoch seconds or milliseconds, said on each field).

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
  /** For a local head: for a local head whose runtime did not answer at the daemon's last background probe, the
   *  endpoint it was asked on (`:8099`). Absent when it answers, when the head is not a local runtime,
   *  and before the first probe: absence claims nothing, so it never reads as OK by itself. */
  runtimeNotAnswering?: string;
  /** for a running head whose provider refuses turns until a known instant (usage's
   *  `provider_reset`), that instant in epoch SECONDS: the figure /health carries as
   *  `quotaResetAtEpochSeconds`. Absent when the provider is not refusing; a value already past is a
   *  refusal that is over. */
  quotaResetAtEpochSeconds?: number;
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

interface PatchApplied {
  applied: EffectiveConfig;
  rejected: Record<string, string>;
  restart_required: string[];
  targets: ConfigPatchTarget[];
}

/** PATCH /api/config's answer. `persisted` names the file the change reached; null means it did not,
 *  and `not_persisted` says why: the value is live until a restart reads the saved one (). */
export type PatchResult = PatchApplied & ({ persisted: string } | { persisted: null; not_persisted: string });

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

/** What the daemon knows of a credential (6b, CredentialVerdictJson.kt): `held` when splice
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
  /** Absent on a daemon older than 6b, whose client heads said `present` unconditionally. */
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

/** Where one login stands (LoginSessions.kt): STARTING until the flow announces itself, WAITING once
 *  it has handed out its code or link, SIGNED_IN once the credential is on disk, LIVE_AFTER_RESTART
 *  once the daemon has restarted the head and the account is in its pool, FAILED at any point. */
export const LOGIN_STATES = ['starting', 'waiting', 'signed_in', 'live_after_restart', 'failed'] as const;
export type LoginState = (typeof LOGIN_STATES)[number];

/** One login as the daemon reports it. The code and the links arrive on a poll, once the flow
 *  announces them; which of them a login carries is the flow's (a device flow a code and its link, a
 *  browser flow the URL to open), so each is null until then and never defaulted. Two routes answer
 *  with it: an account login's (LoginRoutes, as LoginStatusPayload, which adds the head) and a
 *  console add's `sign_in` (AddViews.login), whose head is in no file yet. Here, in shared, because
 *  both the auth and the add entities read it. */
export interface LoginView {
  id: string;
  state: LoginState;
  user_code: string | null;
  verification_uri: string | null;
  browser_url: string | null;
  /** The daemon's own sentence when the login failed. */
  failure_reason: string | null;
}

/** POST /api/auth/:head/refresh|login — a transient outcome, not the full card
 * (callers re-fetch /api/auth for the authoritative card state). */
export interface AuthActionResult {
  ok: boolean;
  head?: string;
  note?: string;
}
