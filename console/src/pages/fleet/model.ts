// The fleet page's pure half: how a saved view turns the head list into groups, how severe a head's
// state is and which colour says it, the latency series each head draws, how the two field sources
// are read when they exist, and which accounts an opened head rides.
import { isExcluded } from '@entities/account';
import type { AccountRow } from '@entities/account';
import { headAttention, providerFamily } from '@entities/heads';
import type { HeadSignals, HeadState, ProviderFamily } from '@entities/heads';
import type { TurnRow } from '@entities/perf';
import type { HeadWindow } from '@entities/usage';
import type { HeadStatus, ProviderAuth } from '@shared/api';
import type { BarPart, Mark, Tone } from '@shared/ui';
import type { View } from '@features/views';
import { H, S } from './strings';

export interface HeadGroup {
  /** The provider family in the `By provider` view, and '' for the one run of every other. */
  key: ProviderFamily | '';
  heads: HeadStatus[];
}

/**
 * How severe a head's state is, for the `Attention first` view. Higher sorts first.
 *
 * `down` outranks everything: a stopped head is not waiting on the operator to fix something, and
 * burying it under a swarm of amber warnings is exactly the failure this view exists to prevent.
 * `ok` is the floor so a healthy head never appears above a broken one.
 */
const SEVERITY: Record<HeadState, number> = {
  down: 7,
  unhealthy: 6,
  'version mismatch': 5,
  'signed out': 5,
  'key missing': 5,
  'login expired': 5,
  'account excluded': 4,
  'queue full': 3,
  'restart needed': 2,
  ok: 0,
};

export function attentionRank(head: HeadStatus, signals: HeadSignals): number {
  return SEVERITY[headAttention(head, signals).cause];
}

/** The four buckets the fleet's split bar counts: healthy, needing the operator, failing, stopped. */
export type Health = keyof typeof S.healthName;

/** Unhealthy is the only failing state: every other cause is a warning the operator can act on,
 *  while an unhealthy head has already broken a promise it made (the entity's red). */
export function healthOf(cause: HeadState): Health {
  if (cause === 'ok') return 'ok';
  if (cause === 'down') return 'down';
  return cause === 'unhealthy' ? 'failing' : 'attention';
}

const HEALTH_TONE: Record<Health, Tone> = { ok: 'ok', attention: 'warn', failing: 'danger', down: 'neutral' };
const HEALTH_MARK: Record<Health, Mark> = { ok: 'ok', attention: 'warn', failing: 'danger', down: 'series-3' };
const HEALTHS: readonly Health[] = ['ok', 'attention', 'failing', 'down'];

/** A state's badge tone. A stopped head is grey, never red: it is not failing, it is not running. */
export function stateTone(cause: HeadState): Tone {
  return HEALTH_TONE[healthOf(cause)];
}

/** A row's tint: only the states that need the operator. OK and down rows stay plain, and the badge
 *  says which they are. */
export function rowTone(cause: HeadState): Tone | null {
  const health = healthOf(cause);
  return health === 'attention' ? 'warn' : health === 'failing' ? 'danger' : null;
}

/** The heads by health as bar parts, in a fixed order so the colours never swap places. */
export function healthParts(causes: readonly HeadState[]): BarPart[] {
  const counts = new Map<Health, number>();
  for (const cause of causes) counts.set(healthOf(cause), (counts.get(healthOf(cause)) ?? 0) + 1);
  return HEALTHS.map((health) => ({ key: health, label: S.healthName[health], value: counts.get(health) ?? 0, mark: HEALTH_MARK[health] }));
}

/** A plan window's level as a tone: the daemon's own levels (UsageWarn.kt), not the console's. */
export function windowTone(window: HeadWindow): Tone {
  return window.level === 'critical' ? 'danger' : window.level === 'warn' ? 'warn' : 'ok';
}

/** The head whose plan window is fullest, or null when no head reports one: an absent window is
 *  never a candidate, so a fleet that reports none says so rather than naming a 0%. */
export function fullestWindow<T extends { window: HeadWindow }>(lines: readonly T[]): T | null {
  let best: T | null = null;
  for (const line of lines) {
    if (line.window.pct === null) continue;
    if (best === null || line.window.pct > (best.window.pct ?? 0)) best = line;
  }
  return best;
}

/** What the fleet has in flight against its ceiling. The ceiling is null when any running head's
 *  gate is unlimited: a sum with an unlimited term has no ceiling. */
export function inflightTotals(heads: readonly HeadStatus[]): { inflight: number; max: number | null } {
  let inflight = 0;
  let max: number | null = 0;
  for (const head of heads) {
    if (head.gate === null) continue;
    inflight += head.gate.inflight;
    max = max === null || head.gate.max === 'unlimited' ? null : max + head.gate.max;
  }
  return { inflight, max };
}

/**
 * Time to first byte, in ms, per landed turn in the order they landed: the head's own when `head` is
 * given, every head's otherwise. A turn that never got a first byte (it failed first) is left out
 * rather than drawn as zero, which would read as the fastest turn of the day.
 */
export function firstBytes(landed: readonly TurnRow[], head?: string): number[] {
  return landed
    .filter((row) => head === undefined || row.head === head)
    .flatMap((row) => (row.first_byte === undefined ? [] : [row.first_byte]));
}

/** The middle value, or null for an empty series. */
export function median(values: readonly number[]): number | null {
  if (values.length === 0) return null;
  const sorted = [...values].sort((left, right) => left - right);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 1 ? sorted[middle] ?? null : ((sorted[middle - 1] ?? 0) + (sorted[middle] ?? 0)) / 2;
}

/**
 * The groups for one saved view.
 *
 * `By head` (group: null, no sort) is the fleet itself: one run of every head, by key. `By provider`
 * makes one run per family. `Attention first` sorts by severity inside whatever grouping the view
 * carries, so a head that needs the operator is never below the fold.
 */
export function arrangeHeads(
  heads: readonly HeadStatus[],
  view: View,
  signalsFor: (head: HeadStatus) => HeadSignals,
): HeadGroup[] {
  const grouped = new Map<ProviderFamily | '', HeadStatus[]>();
  for (const head of heads) {
    const key: ProviderFamily | '' = view.group === 'provider' ? providerFamily(head.authKind) : '';
    const bucket = grouped.get(key);
    if (bucket === undefined) grouped.set(key, [head]);
    else bucket.push(head);
  }

  const groups = [...grouped.entries()].map(([key, rows]) => ({ key, heads: rows }));
  for (const group of groups) {
    if (view.sort?.field === 'attention') {
      group.heads = [...group.heads].sort((left, right) => {
        const delta = attentionRank(right, signalsFor(right)) - attentionRank(left, signalsFor(left));
        // Ties break on the key so two equally-severe heads do not swap places between polls and
        // make the table flicker under the operator's cursor.
        return delta !== 0 ? delta : left.key.localeCompare(right.key);
      });
    } else {
      group.heads = [...group.heads].sort((left, right) => left.key.localeCompare(right.key));
    }
  }
  groups.sort((left, right) => left.key.localeCompare(right.key));
  return groups;
}

/** The columns a saved view may name. The head and its state are not in this set: they are what
 *  the table is for. The dialect and port are the opened head's facts. */
export const HEAD_FIELDS = ['provider', 'model', 'account', 'inflight', 'window', 'latency', 'turn'] as const;

/** Which columns a view shows. An empty list means every column. */
export function columnsOf(view: View, every: readonly string[]): readonly string[] {
  return view.fields.length === 0 ? every : view.fields;
}

/**
 * A head's dialect, out of the topology payload.
 *
 * Two hops, and both are in the file the daemon reads: `heads.<key>.provider` names a
 * `[providers.<name>]` block and that block carries `dialect` (FEATURES.md 2.3). Pure over a plain
 * object so it is testable without the route.
 */
export function dialectOf(topology: Record<string, unknown> | null, headKey: string): string | null {
  const heads = asTable(topology?.heads);
  const head = asTable(heads?.[headKey]);
  const provider = typeof head?.provider === 'string' ? head.provider : null;
  if (provider === null) return null;
  const providers = asTable(topology?.providers);
  const block = asTable(providers?.[provider]);
  return typeof block?.dialect === 'string' ? block.dialect : null;
}

function asTable(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

/**
 * The last-turn cell: the turn in flight if there is one, else how long ago the head's newest turn
 * was (the perf summary's `last_ts`), `None` when it has never run one, and null when the daemon
 * does not say. Its only source used to be `gate.live`, which the daemon serves empty, so a head
 * with forty turns behind it said none.
 */
export type LastTurn =
  | { kind: 'live'; phase: string; ageMs: number }
  | { kind: 'ago'; ts: number }
  | { kind: 'none' }
  | { kind: 'unknown' };

export function lastTurnOf(head: HeadStatus, lastTs: number | null | undefined): LastTurn {
  const live = head.gate?.live[0];
  if (live !== undefined) return { kind: 'live', phase: live.phase, ageMs: live.age_ms };
  if (lastTs === undefined) return { kind: 'unknown' };
  return lastTs === null ? { kind: 'none' } : { kind: 'ago', ts: lastTs };
}

// ── the opened head's account pool (M4-02) ──────────────────────────────────────────────────────

/**
 * The accounts one head rides, out of GET /api/accounts: every row whose `heads` names it.
 *
 * Read off the row rather than joined on anything the console knows, because the daemon already did
 * the join: a login two heads share is ONE row carrying both keys (AccountsRoute.merge, joined on the
 * credential path), so it belongs to both pools and appears in both.
 */
export function poolOf(accounts: readonly AccountRow[], headKey: string): AccountRow[] {
  return accounts.filter((account) => account.heads.includes(headKey));
}

/**
 * HeadSignals.accountExcluded, exactly as that field's contract states it: the head rides a pool
 * whose SELECTED account is excluded. `isExcluded` is the predicate the account's own state uses, so
 * the head and its pool can never disagree about the same account. A single login's `selected` is
 * null (no pool selects it), so it never trips this.
 */
export function selectedExcluded(pool: readonly AccountRow[], nowMs: number): boolean {
  return pool.some((account) => account.selected === true && isExcluded(account, nowMs));
}

/**
 * A pool with labelled accounts and no next target: nothing is available, which is the state that
 * fails the head's next turn. The mark is the daemon's own `next_target` flag (AccountPool.kt:163),
 * never a re-derivation, so no flag is the daemon saying none. A single login has no pool to select
 * from, so it never reads as none available.
 */
export function noneAvailable(pool: readonly AccountRow[]): boolean {
  return pool.some((account) => account.label !== null) && !pool.some((account) => account.next_target === true);
}

/** The families whose heads ride OAuth logins, so GET /api/accounts reports them (AuthKindRegistry
 *  .isOAuth, AccountsRoute.fold). Every other kind is outside the join by the daemon's own rule. */
const OAUTH_FAMILIES: ReadonlySet<ProviderFamily> = new Set<ProviderFamily>(['chatgpt', 'grok', 'kimi', 'muse']);

/** What the opened head says about its state: the cause in one sentence, and the one step that
 *  clears it, as a command to copy or a page to open. The badge has room for one word; this is where
 *  the word is explained (walkthrough S1, S2). */
export interface CauseHelp {
  text: string;
  command?: string;
  href?: string;
  link?: string;
}

export function causeHelp(head: HeadStatus, cause: HeadState, auth: ProviderAuth | undefined): CauseHelp | null {
  const signIn = { href: '#/accounts', link: S.signIn };
  switch (cause) {
    case 'ok':
      return null;
    case 'down':
      return { text: H.down };
    case 'unhealthy':
      return { text: H.unhealthy, href: `#/logs?head=${encodeURIComponent(head.key)}`, link: S.openLog };
    case 'version mismatch':
      return { text: H.mismatch };
    case 'signed out':
      return { text: H.signedOut, ...signIn };
    case 'key missing': {
      // `splice key set` writes ~/.config/splice/keys.toml, and the next request reads it: no
      // restart (KeyCommand.kt). An exported variable would need the daemon restarted to be seen.
      const variable = auth?.env_var;
      return variable === undefined ? { text: H.keyMissingBare } : { text: H.keyMissing, command: `splice key set ${variable}` };
    }
    case 'login expired':
      return { text: H.loginExpired, ...signIn };
    case 'account excluded':
      return { text: H.accountExcluded };
    case 'queue full':
      return { text: H.queueFull };
    case 'restart needed':
      return { text: H.restartNeeded };
  }
}

/**
 * The page's empties, as data rather than inline JSX, so a test can assert each one names its
 * source (CONTRACTS.md section 8): one line, and the help behind its info mark.
 */
export const EMPTIES = {
  /** The model and dialect while GET /api/topology or GET /api/models answers 404: only a daemon
   *  older than this console does, since the console ships inside the daemon's jar. */
  fields: { text: S.fieldsUnavailable, source: H.fields },
  /** The pooled accounts while GET /api/accounts answers 404 (entities/account marks it pending). */
  pool: { text: S.poolsUnavailable, source: H.pools },
  noHeads: { text: S.noHeads, source: H.noHeads },
  oneLogin: { text: S.oneLogin, source: H.oneLogin },
  noAccounts: { text: S.noAccounts, source: H.noAccounts },
  apiKey: { text: S.noPool, source: H.apiKey },
  local: { text: S.noPool, source: H.local },
  noneAvailable: { text: S.noneAvailable, source: H.noneAvailable },
} as const;

/**
 * What an opened head's pool section says when GET /api/accounts names no row for it. Four
 * different facts, never one blank table:
 *   - a Claude head is `client`: it uses the Claude Code login it was started with, and never pools;
 *   - an api-key head signs every request with its one key, so there is nothing to pool;
 *   - a local head needs no login at all;
 *   - an OAuth head with no row has no signed-in account yet, and the empty says where to add one.
 */
export function poolEmpty(authKind: string): { text: string; source: string } {
  if (authKind === 'client') return EMPTIES.oneLogin;
  if (OAUTH_FAMILIES.has(providerFamily(authKind))) return EMPTIES.noAccounts;
  if (authKind === 'api-key') return EMPTIES.apiKey;
  return EMPTIES.local;
}
