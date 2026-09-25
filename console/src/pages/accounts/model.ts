// The accounts page's pure half: each account's state as a tone and a mark, its windows as figures
// and countdowns, how a saved view turns the payload into groups, the help an api-key head gets, and
// how a capture fixture is selected. Kept out of the component so all of it is testable without a
// renderer, and so the sort rule below is pinned rather than eyeballed.
import {
  COCK_AT_PERCENT, EXHAUSTED_AT_PERCENT, SELECTOR_ORDER_TEXT, isExcluded, isStale, nearestWindow, windowLengthText,
} from '@entities/account';
import type { AccountRow, AccountWindow } from '@entities/account';
import type { View } from '@features/views';
import { fmtDurationS } from '@shared/lib';
import type { BarPart, Mark, Tone } from '@shared/ui';
import { H, S } from './strings';

/** What an account is, for the selector: takeable with room, near its limit, spent, refused by the
 *  pool, or unknown because its provider reported no figure. */
export type AccountStateKey = keyof typeof S.stateName;

/** Order matters and is the whole design: an excluded account is excluded even when a window is
 *  nearly spent, because the pool will not take it at all, and reporting it as near its limit would
 *  point the operator at an account the daemon is already refusing. The thresholds are the entity's
 *  (COCK_AT_PERCENT, EXHAUSTED_AT_PERCENT), so the page and the strip cannot disagree. */
export function stateOf(account: AccountRow, nowMs: number): AccountStateKey {
  if (isExcluded(account, nowMs)) return 'excluded';
  const used = nearestWindow(account, nowMs)?.used_percent ?? null;
  if (used === null) return 'unknown';
  if (used >= EXHAUSTED_AT_PERCENT) return 'spent';
  if (used >= COCK_AT_PERCENT) return 'warn';
  return 'ok';
}

/** A state's badge tone and chart mark, from one mapping each. Unknown is never green: green would
 *  claim a health nobody measured. */
export const TONE: Record<AccountStateKey, Tone> = { ok: 'ok', warn: 'warn', spent: 'danger', excluded: 'neutral', unknown: 'neutral' };
export const MARK: Record<AccountStateKey, Mark> = { ok: 'ok', warn: 'warn', spent: 'danger', excluded: 'series-3', unknown: 'series-2' };

const STATES: readonly AccountStateKey[] = ['ok', 'warn', 'spent', 'excluded', 'unknown'];

/** The accounts by state as bar parts, in a fixed order so the colours never swap places. */
export function stateParts(accounts: readonly AccountRow[], nowMs: number): BarPart[] {
  const counts = new Map<AccountStateKey, number>();
  for (const account of accounts) counts.set(stateOf(account, nowMs), (counts.get(stateOf(account, nowMs)) ?? 0) + 1);
  return STATES.map((state) => ({ key: state, label: S.stateName[state], value: counts.get(state) ?? 0, mark: MARK[state] }));
}

/** A used figure's tone: the same thresholds as the account's state. */
export function usedTone(percent: number): Tone {
  if (percent >= EXHAUSTED_AT_PERCENT) return 'danger';
  if (percent >= COCK_AT_PERCENT) return 'warn';
  return 'ok';
}

/** A window's name: its reported length, prefixed by the model where the provider scopes one. The
 *  length is the window's own (Grok reports 30d), never assumed from its slot. */
export function windowName(window: AccountWindow): string {
  const length = windowLengthText(window.seconds);
  return window.model === undefined ? length : `${window.model} ${length}`;
}

const DAY_S = 86_400;
const HOUR_S = 3_600;

/** How long until a window resets, or null when the provider sent no reset or it has passed. Two
 *  days and more read in days and hours: a weekly window's `111h 6m` is `4d 15h`. */
export function countdown(resetEpochSeconds: number | null, nowMs: number): string | null {
  if (resetEpochSeconds === null) return null;
  const deltaS = resetEpochSeconds - Math.floor(nowMs / 1000);
  if (deltaS <= 0) return null;
  if (deltaS < 2 * DAY_S) return fmtDurationS(deltaS);
  return `${Math.floor(deltaS / DAY_S)}d ${Math.floor((deltaS % DAY_S) / HOUR_S)}h`;
}

/** What a window cell draws: the used share and its reset, a stale reading, or nothing reported. */
export type WindowFigure =
  | { kind: 'used'; percent: number; resets: string | null }
  | { kind: 'stale' }
  | { kind: 'none' };

export function windowFigure(window: AccountWindow | null, nowMs: number): WindowFigure {
  if (window === null || window.used_percent === null) return { kind: 'none' };
  // A window whose reset has passed is a reading of a window that no longer exists: its figure is
  // from before the reset, so it is drawn as stale and never as its old share.
  if (isStale(window, nowMs)) return { kind: 'stale' };
  return { kind: 'used', percent: window.used_percent, resets: countdown(window.reset_epoch_seconds, nowMs) };
}

/** The order the selector walks, once for the page, from the entity's own rule list so the words
 *  cannot drift from the daemon's order. */
export function orderText(): string {
  return `${SELECTOR_ORDER_TEXT.charAt(0).toUpperCase()}${SELECTOR_ORDER_TEXT.slice(1)}.`;
}

export interface AccountGroup {
  key: string;
  accounts: AccountRow[];
}

/** The head-group key for an account no head is riding. Its own group rather than dropped: an
 *  account in the pool that nothing is riding is a fact the operator wants to see. */
export const NO_HEAD_GROUP = S.noHeads;

/**
 * How far into its nearest window an account is, for the `Nearest limit` sort.
 *
 * An account whose provider reports no figure ranks BELOW every account that reported one, which
 * is the opposite of treating the absence as zero. FEATURES 4.5 states the rule for the page
 * ("unknown shown as unknown, never sorted first") and the reason is the operator's: a console
 * that sorts the unknown to the top trains the eye to skip the top, which is exactly where the
 * account that is about to fail belongs.
 */
export function exhaustionRank(account: AccountRow, nowMs: number): number {
  return nearestWindow(account, nowMs)?.used_percent ?? Number.NEGATIVE_INFINITY;
}

function byExhaustion(left: AccountRow, right: AccountRow, nowMs: number): number {
  const delta = exhaustionRank(right, nowMs) - exhaustionRank(left, nowMs);
  if (delta !== 0) return delta;
  // A stable, meaningful tiebreak: the label, so two equally-spent accounts do not swap places
  // between polls and make the table flicker.
  return (left.label ?? '').localeCompare(right.label ?? '');
}

function keysFor(account: AccountRow, group: string | null): string[] {
  if (group === 'head') return account.heads.length === 0 ? [NO_HEAD_GROUP] : [...account.heads].sort();
  if (group === 'provider') return [account.kind];
  return [''];
}

/**
 * The groups for one saved view.
 *
 * Grouping is by provider family (the default), by head, or not at all; sorting is applied INSIDE
 * a group, never across groups, because a table's order is its grouping and re-sorting across it
 * would put an account under a heading that does not name it.
 *
 * One account can appear in more than one group under `By head`, and that is the honest rendering:
 * a login under two heads is one pool row riding both, and hiding it from one of them would make
 * the page disagree with the daemon about which heads are on it.
 */
export function arrangeAccounts(accounts: readonly AccountRow[], view: View, nowMs: number): AccountGroup[] {
  const grouped = new Map<string, AccountRow[]>();
  for (const account of accounts) {
    for (const key of keysFor(account, view.group)) {
      const bucket = grouped.get(key);
      if (bucket === undefined) grouped.set(key, [account]);
      else bucket.push(account);
    }
  }

  const groups = [...grouped.entries()].map(([key, rows]) => ({ key, accounts: rows }));
  for (const group of groups) {
    if (view.sort?.field === 'exhaustion') {
      const ranked = [...group.accounts].sort((left, right) => byExhaustion(left, right, nowMs));
      group.accounts = view.sort.dir === 'desc' ? ranked : ranked.reverse();
    }
  }
  groups.sort((left, right) => left.key.localeCompare(right.key));
  return groups;
}

/** The columns a saved view may name. The state and window columns are not in this set: they are
 *  what the page is for, so a view cannot hide them. */
export const ACCOUNT_FIELDS = ['provider', 'account', 'plan', 'heads', 'next'] as const;

/** Which columns a view shows. An empty list means every column, so a view that never touched its
 *  fields is not a view that hides everything. */
export function columnsOf(view: View): readonly string[] {
  return view.fields.length === 0 ? ACCOUNT_FIELDS : view.fields;
}

/** One head as the page reads it off GET /api/auth: the Claude logins, the api-key heads, and the
 *  pooled heads while GET /api/accounts is not served. */
export interface HeadRow {
  head: string;
  kind: string;
  present: boolean;
  masked: string | null;
  note: string | null;
  /** api-key heads: the variable the key is read from, the key masked, and the key file. */
  envVar?: string | undefined;
  keyMasked?: string | undefined;
  keyFile?: string | undefined;
}

/**
 * The help an api-key head gets. THE DAEMON READS THREE PLACES IN ORDER
 * (ApiKeyAuthProvider.readKey): the variable in its own environment, then the head's key_file, then
 * the key store `splice key set` writes. A stored key is the one a set replaces, so a head whose key
 * is present is told which source wins over the store, and a head reading a key_file is sent to the
 * file: a `splice key set` there writes a key the daemon never reads.
 */
export function keyHelp(row: HeadRow): string {
  if (row.envVar === undefined) return H.keyOne;
  if (!row.present) return H.keyStore;
  return row.keyFile === undefined ? H.keyReplace : H.keyFile;
}

/** The command that stores a head's key, or null where a set would not reach the daemon: a head
 *  reading a key file with a key in it is sent to the file instead. */
export function keyCommand(row: HeadRow): string | null {
  if (row.envVar === undefined) return null;
  if (row.keyFile !== undefined && row.present) return null;
  return `splice key set ${row.envVar}`;
}

/**
 * The capture fixture's name, or null.
 *
 * Gated on `dev` explicitly rather than reading `import.meta.env` here, so the rule is testable:
 * a fixture must never be reachable in a shipped artifact, and the caller passes the real
 * `import.meta.env.DEV`. CONTRACTS.md section 4 fixes the rest: the address carries
 * `?fixture=<name>`, and a rendered fixture is labelled `Sample data`.
 */
export function fixtureName(search: string, dev: boolean): string | null {
  if (!dev) return null;
  const name = new URLSearchParams(search).get('fixture');
  if (name === null) return null;
  const trimmed = name.trim();
  return trimmed === '' ? null : trimmed;
}
