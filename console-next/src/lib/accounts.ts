// The accounts entity's pure arithmetic and prose: which window is nearest to exhaustion, how a
// window is labelled from its own reported length, and which account the selector takes next.
//
// These are pinned by test because each one has a wrong answer that LOOKS right:
//   - treating a missing used-percent as 0 makes an unreported window the emptiest thing on the
//     page, so the account the operator should watch reads as the one with the most room;
//   - labelling a window by its position (first = 5h, second = weekly) is wrong the moment a
//     provider reports a 30-day period, which Grok already does;
//   - a next-target mark the console derives for itself names a different account than the daemon
//     will actually take next (it skipped the pin, and ran over every pool at once), so the mark
//     is the daemon's own flag and the console only names the rule that explains it.
import type { AccountRow, AccountWindow, AccountWire, AccountsPayload, AccountsWire } from '../types/accounts';
import { fmtDurationS, timeAgo } from './format';
import { W } from './words';

/** The colour a strip's edge wears. */
export type Edge = 'green' | 'amber' | 'red' | 'grey';

/** What a window with no figure is called. The world's rule: never 0 (FEATURES 2.2, 4.5), and the
 *  word is `unknown` - "we asked and were NOT TOLD" - which is the same fact this site used to spell
 *  out in three words (M1-74). A second phrasing for one fact is how a console reaches eleven ways
 *  of saying nothing is here. */
export const NOT_REPORTED = 'unknown';

/** The selector's pin and legacy fallback vocabulary. Persisted priority comes between them.
 *  The next-target flag alone cannot distinguish saved priority from a fallback cause. */
export const SELECTOR_RULES = ['pinned', 'primary', 'last used', 'most weekly room'] as const;
export type SelectorRule = (typeof SELECTOR_RULES)[number];

/** The window length the daemon calls the seven-day window, for the selector's third rule. */
/** The daemon's slot boundary (Quota.kt FIVE_HOUR_SLOT_MAX_SECONDS): a provider window up to six
 *  hours long is the five-hour slot, anything longer the seven-day slot, whatever its length. */
const FIVE_HOUR_SLOT_MAX_SECONDS = 6 * 3600;

/** A window's used figure as printed text. */
export function windowUsedText(window: AccountWindow): string {
  return window.used_percent === null ? NOT_REPORTED : `${Math.round(window.used_percent)}%`;
}

/** The window's own reported length as printed text (5h, 7d, 30d). Derived from `seconds` and
 *  never from the window's position, because Grok already reports a length nothing else uses. */
export function windowLengthText(seconds: number): string {
  if (seconds >= 86400 && seconds % 86400 === 0) return `${seconds / 86400}d`;
  if (seconds >= 3600 && seconds % 3600 === 0) return `${seconds / 3600}h`;
  return `${seconds}s`;
}

/** A window's length as a page prints it: the provider's own (`5h`, `7d`, `30d`), or only its slot when the daemon sent no length. */
export function windowSpan(window: AccountWindow): string {
  if (window.length_known === false) return window.seconds > FIVE_HOUR_SLOT_MAX_SECONDS ? W.longWindow : W.shortWindow;
  return windowLengthText(window.seconds);
}

/** The account's windows in the daemon's two slots: `short` up to six hours, `long` beyond. Each is
 *  the slot's fullest window (a Claude account can carry several model-scoped long windows; the
 *  detail lists every one), or null when the slot holds none. A rack prints one track per slot so
 *  every row has the same cells: a row with one window and a row with none used to differ by a
 *  cell, and the columns after them slid under the wrong names (walkthrough B3). */
export function slotWindows(account: AccountRow): { short: AccountWindow | null; long: AccountWindow | null } {
  const fullest = (windows: AccountWindow[]): AccountWindow | null => windows.reduce<AccountWindow | null>(
    (held, next) => (held === null || (next.used_percent ?? -1) > (held.used_percent ?? -1) ? next : held),
    null,
  );
  return {
    short: fullest(account.windows.filter((window) => window.seconds <= FIVE_HOUR_SLOT_MAX_SECONDS)),
    long: fullest(account.windows.filter((window) => window.seconds > FIVE_HOUR_SLOT_MAX_SECONDS)),
  };
}

/**
 * A window whose reset has passed: its figure is from before the reset, so it says nothing about
 * now. #235 made these visible, since a single-login head's window is only re-read when the head
 * runs a turn: muse printed `warn 99%` on an amber edge for a seven-day window that had reset 91 hours
 * earlier. The usage page's plan rack treats the same window the same way.
 */
export function isStale(window: AccountWindow, nowMs: number): boolean {
  return window.reset_epoch_seconds !== null && window.reset_epoch_seconds * 1000 <= nowMs;
}

/** What a stale window says in its slot, on the accounts table and the usage plan cards alike. */
export const NOT_REREAD = W.notReread;

/**
 * The window closest to exhaustion, or null when the account reports no used figure at all. A
 * stale window (its reset has passed) is not a candidate either: it is a reading of a window that
 * no longer exists.
 *
 * A window with no reported figure is NOT a candidate: it is an absence, and an absence cannot be
 * "nearest to exhausted". Ties go to the shorter window, which is the one that resets sooner and
 * therefore the one the operator is actually waiting on.
 */
export function nearestWindow(account: AccountRow, nowMs: number): AccountWindow | null {
  let best: AccountWindow | null = null;
  for (const window of account.windows) {
    if (window.used_percent === null || isStale(window, nowMs)) continue;
    if (best === null || best.used_percent === null) { best = window; continue; }
    if (window.used_percent > best.used_percent) { best = window; continue; }
    if (window.used_percent === best.used_percent && window.seconds < best.seconds) best = window;
  }
  return best;
}

export interface NearestOverall {
  account: AccountRow;
  window: AccountWindow;
}

/** The single window nearest exhaustion across every account, for the rule's own readout. */
export function nearestOverall(accounts: readonly AccountRow[], nowMs: number): NearestOverall | null {
  let best: NearestOverall | null = null;
  for (const account of accounts) {
    const window = nearestWindow(account, nowMs);
    if (window === null || window.used_percent === null) continue;
    if (best === null || window.used_percent > (best.window.used_percent ?? -1)) {
      best = { account, window };
    }
  }
  return best;
}

/**
 * An account's seven-day SLOT used figure for the selector's third rule, read the way the daemon
 * reads it (AccountPool.kt:258 sevenDayUsed over QuotaSlots' seven-day slot): the first window
 * longer than six hours, so Grok's 30-day window counts, where matching 604800 exactly read it as 0.
 * An account with NO snapshot sorts as zero used, which is the daemon's own rule, not a convenience:
 * a freshly added account has no poller reading yet and must still be selectable.
 */
export function sevenDayUsed(account: AccountRow): number {
  const window = account.windows.find((w) => w.seconds > FIVE_HOUR_SLOT_MAX_SECONDS);
  if (window === undefined || window.used_percent === null) return 0;
  return window.used_percent;
}

/**
 * Whether two rows are accounts of one pool. A head rides at most one pool, and the daemon folds a
 * pool once per head riding it, joining a login two heads share into ONE row that carries both
 * (AccountsRoute.merge): so the rows of one pool carry that pool's heads, and two pools share none.
 */
function samePool(left: AccountRow, right: AccountRow): boolean {
  return left === right || left.heads.some((head) => right.heads.includes(head));
}

/** The daemon's tie-break inside its seven-day rule: the label, in Kotlin's String order (UTF-16
 *  code units), which is `<` on JS strings and not localeCompare. */
function byLabel(left: string, right: string): number {
  return left < right ? -1 : left > right ? 1 : 0;
}

/**
 * Why the daemon takes [account] next, or null when it does not.
 *
 * THE MARK IS THE DAEMON'S FLAG, NOT A RE-DERIVATION (M4-08). AccountsRoute writes `next_target`
 * per pool from that pool's own nextTargetLabel (AccountPool.kt:163), which walks the pin, primary,
 * the caller's previous account and the lowest seven-day used, in that order. The console used to
 * run a selector of its own over every account on the page at once and mark each strip whose label
 * matched its one answer: one pool's answer stamped on every pool with an account of that label, a
 * pool whose answer differed left unmarked, and the pin never consulted. So the flag picks the
 * account, and the order only NAMES why, inside the account's own pool: pinned, then primary, then
 * the lowest seven-day account; a target that is none of those can only have been the previous
 * one, which is the sticky rule. A single login's flag is null, since no pool selects it.
 */
export function nextRuleOf(account: AccountRow, accounts: readonly AccountRow[]): SelectorRule | null {
  if (account.next_target !== true || account.label === null) return null;
  if (account.pinned === true) return 'pinned';
  if (account.primary) return 'primary';
  const lowest = accounts
    .filter((row): row is AccountRow & { label: string } =>
      row.available === true && row.label !== null && samePool(row, account))
    .sort((left, right) => sevenDayUsed(left) - sevenDayUsed(right) || byLabel(left.label, right.label))[0];
  return lowest?.label === account.label ? 'most weekly room' : 'last used';
}

/** A window inside this much of its length is cocked: the operator wants the warning while there
 *  is still room to move a session, not after the turn has already failed. */
export const COCK_AT_PERCENT = 90;

/** At or past its length the window is spent and the strip goes red. */
export const EXHAUSTED_AT_PERCENT = 100;

/** Printed when the pool excludes an account and the daemon sent no reason of its own. */
export const EXCLUDED_REASON = W.excluded;

/**
 * Whether the pool will pass this account over. `available` is the pool's OWN verdict, so a false
 * is an exclusion whatever the reason field says; the expiry is checked as well because an
 * exclusion can lapse between polls without the flag having been recomputed.
 */
export function isExcluded(account: AccountRow, nowMs: number): boolean {
  // Only the pool's own `false` excludes; a single-login head's null means no pool judged it.
  if (account.available === false) return true;
  const until = account.auth_excluded_until_epoch_millis ?? null;
  return until !== null && until > nowMs;
}

/**
 * The daemon's sentence for a credential it refuses to load at all a symlinked credential file),
 * or null when it refuses none. A refused account has no credential either, but signing in cannot renew
 * it, so it is never the same state as a credential that is simply gone.
 */
export function refusalText(account: AccountRow): string | null {
  const text = account.refusal?.trim() ?? '';
  return text === '' ? null : text;
}

/** True when the daemon can load this account's credential: a file is there and no link refuses it.
 *  Switching to one that cannot be loaded pins it and serves nothing, and refreshing it fails on the missing file. */
export const isServable = (account: AccountRow): boolean => account.credential_present && refusalText(account) === null;

/** Whether a head's sign-in can be refreshed: the account that serves it must be loadable.
 *  With no account pool there is one login and the head's own state judges it. */
export function canRefresh(pool: readonly AccountRow[]): boolean {
  const serving = pool.find((account) => account.selected === true) ?? (pool.length === 1 ? pool[0] : undefined);
  return serving === undefined || isServable(serving);
}

/** The exclusion's reason, in the daemon's own words where it sent any. */
export function exclusionText(account: AccountRow): string {
  const reason = account.auth_exclusion_reason ?? '';
  return reason.trim() === '' ? EXCLUDED_REASON : reason;
}

/**
 * A spent account the pool has already stepped past: the account's window is used up, the daemon gave no reason of its own for
 * setting it aside, and another account of the pool is serving. Nothing is wrong, so it is not an alarm; the row says where
 * turns go. Null for a spent account with nothing to step to, or that is itself the one serving.
 */
export function steppedPast(account: AccountRow, pool: readonly AccountRow[], nowMs: number): { serving: string; window: AccountWindow } | null {
  if (account.single_login || account.selected === true || (account.auth_exclusion_reason ?? '').trim() !== '') return null;
  const window = nearestWindow(account, nowMs);
  if (window === null || (window.used_percent ?? 0) < EXHAUSTED_AT_PERCENT) return null;
  const serving = pool.find((other) => other !== account && other.selected === true && other.label !== null && isServable(other) && !isExcluded(other, nowMs));
  return serving?.label == null ? null : { serving: serving.label, window };
}

export interface AccountState {
  edge: Edge;
  /** True when the strip carries a warning edge: needs-me, while there is still room to act. */
  cocked: boolean;
  /** True when the strip is disabled: an excluded account cannot be selected, so it must not
   *  read as merely quiet. */
  struck: boolean;
  /** The printed label that always rides beside the edge, so the state survives a grayscale
   *  screenshot. */
  label: string;
}

/**
 * The strip's state, from the account's own numbers.
 *
 * Order matters and is the whole design: an excluded account is struck even when a window is
 * nearly spent, because a struck strip is a disabled one and an excluded account cannot be taken
 * at all. Reporting it as "nearly out" would point the operator at an account the pool is already
 * refusing.
 */
export function accountState(account: AccountRow, nowMs: number, pool: readonly AccountRow[] = []): AccountState {
  // The daemon's own refusal is the most specific thing it can say about a credential, so it is named before the pool's exclusion.
  if (refusalText(account) !== null) {
    return { edge: 'grey', cocked: false, struck: true, label: 'refused' };
  }
  if (steppedPast(account, pool, nowMs) !== null) return { edge: 'grey', cocked: false, struck: false, label: 'used' };
  if (isExcluded(account, nowMs)) {
    return { edge: 'grey', cocked: false, struck: true, label: 'excluded' };
  }
  const window = nearestWindow(account, nowMs);
  const used = window?.used_percent ?? null;
  if (used === null) {
    // No provider figure at all. Grey and quiet, never green: green would claim a health nobody
    // measured.
    return { edge: 'grey', cocked: false, struck: false, label: NOT_REPORTED };
  }
  if (used >= EXHAUSTED_AT_PERCENT) {
    return { edge: 'red', cocked: true, struck: false, label: `spent ${Math.round(used)}%` };
  }
  if (used >= COCK_AT_PERCENT) {
    return { edge: 'amber', cocked: true, struck: false, label: `warn ${Math.round(used)}%` };
  }
  return { edge: 'green', cocked: false, struck: false, label: 'ok' };
}

/**
 * When a window resets, as printed text. Relative rather than a clock time: the operator's
 * question is "how long until I can work again", and a wall clock would need a timezone that the
 * rule bar already carries.
 */
export function resetText(resetEpochSeconds: number | null, nowMs: number): string | null {
  if (resetEpochSeconds === null) return null;
  const deltaS = resetEpochSeconds - Math.floor(nowMs / 1000);
  if (deltaS <= 0) return 'now';
  return `in ${fmtDurationS(deltaS)}`;
}

/**
 * When an account's windows were read, and which of them have reset since, as the opened account
 * says it. Null when the daemon does not date the reading. A single-login head's windows are read
 * only when the head runs a turn, so a reading days old is normal there, and the sentence says why
 * its figure reads `unknown` rather than leaving a blank to guess at.
 */
export function readAgeText(account: AccountRow, nowMs: number): string | null {
  const observed = account.observed_at_epoch_seconds;
  if (observed === null || observed === undefined) return null;
  const read = `windows read ${timeAgo(observed * 1000, nowMs)}`;
  const reset = account.windows.filter((window) => isStale(window, nowMs)).map(windowSpan);
  if (reset.length === 0) return read;
  return `${read}; the ${reset.join(' and ')} ${reset.length === 1 ? 'window has' : 'windows have'} reset since, so ${reset.length === 1 ? 'its figure is' : 'their figures are'} unknown until the next reading`;
}

/** Subscriptions a head rides. Management keeps every login place; pool counts join only proven UUIDs.
 *  Keep the selected credential as the representative so the head's exclusion signal is not lost. */
export function poolOf(accounts: readonly AccountRow[], headKey: string): AccountRow[] {
  const pool: AccountRow[] = [];
  const known = new Map<string, number>();
  for (const account of accounts) {
    if (account.kind === 'api-key' || !account.heads.includes(headKey)) continue;
    const uuid = account.account?.uuid;
    const prior = uuid == null || uuid === '' ? undefined : known.get(uuid);
    if (prior === undefined) {
      if (uuid != null && uuid !== '') known.set(uuid, pool.length);
      pool.push(account);
    } else {
      const current = pool[prior];
      const newlySelected = account.selected === true && current?.selected !== true;
      const healthier = current?.selected !== true && current !== undefined && !isServable(current) && isServable(account);
      if (newlySelected || healthier) pool[prior] = account;
    }
  }
  return pool;
}

/**
 * The heads' `accountExcluded` signal (Fleet and Needs you), exactly as that field's contract states it: the head rides a pool
 * whose SELECTED account is excluded. `isExcluded` is the predicate the account's own state uses, so
 * the head and its pool can never disagree about the same account. A single login's `selected` is
 * null (no pool selects it), so it never trips this.
 */
export function selectedExcluded(pool: readonly AccountRow[], nowMs: number): boolean {
  return pool.some((account) => account.selected === true && isExcluded(account, nowMs));
}

// ---- the wire ----

/** The length of each slot the daemon files a provider's windows into. The daemon names the slots by
 *  their length (QuotaSlots) and sends the provider's own length beside each; this only places a window whose
 *  length it sent none for in its slot (`length_known` is then false, and no page prints the number). */
const FIVE_HOUR_SECONDS = 18000;
const SEVEN_DAY_SECONDS = 604800;

/** One slot as a window, or null when the provider reported nothing for it: an absent slot is an
 *  absence, and the page says `unknown` for an account with no windows rather than inventing 0. */
function slot(
  usedPercent: number | null,
  resetEpochSeconds: number | null,
  windowSeconds: number | null,
  fallbackSeconds: number,
  current: boolean,
): AccountWindow | null {
  if (usedPercent === null && resetEpochSeconds === null && windowSeconds === null) return null;
  return {
    seconds: windowSeconds ?? fallbackSeconds,
    used_percent: usedPercent,
    reset_epoch_seconds: resetEpochSeconds,
    current,
    ...(windowSeconds === null ? { length_known: false as const } : {}),
  };
}

function accountFromWire(wire: AccountWire): AccountRow {
  const windows = [
    slot(wire.five_hour_used_percent, wire.five_hour_reset_epoch_seconds, wire.five_hour_window_seconds, FIVE_HOUR_SECONDS, wire.five_hour_current),
    slot(wire.seven_day_used_percent, wire.seven_day_reset_epoch_seconds, wire.seven_day_window_seconds, SEVEN_DAY_SECONDS, wire.seven_day_current),
  ].filter((window): window is AccountWindow => window !== null)
    .map((window) => ({ ...window, observed_at_epoch_seconds: wire.observed_at_epoch_seconds }));
  return {
    kind: wire.kind,
    label: wire.label,
    single_login: wire.single_login,
    credential_path: wire.credential_path,
    plan: wire.plan,
    primary: wire.primary,
    selected: wire.selected,
    available: wire.available,
    pinned: wire.pinned,
    next_target: wire.next_target,
    credential_present: wire.credential_present,
    ...(wire.provider === undefined ? {} : { provider: wire.provider }),
    ...(wire.login_place === undefined ? {} : { login_place: wire.login_place }),
    ...(wire.account === undefined ? {} : { account: wire.account }),
    ...(wire.held === undefined ? {} : { held: wire.held }),
    ...(wire.held_until_epoch_seconds === undefined ? {} : { held_until_epoch_seconds: wire.held_until_epoch_seconds }),
    ...(wire.failover_positions === undefined ? {} : { failover_positions: wire.failover_positions }),
    auth_excluded_until_epoch_millis: wire.auth_excluded_until_epoch_millis,
    auth_exclusion_reason: wire.auth_exclusion_reason,
    refusal: wire.refusal ?? null,
    windows,
    heads: wire.heads,
    observed_at_epoch_seconds: wire.observed_at_epoch_seconds,
  };
}

export function accountsFromWire(wire: AccountsWire): AccountsPayload {
  return { accounts: wire.accounts.map(accountFromWire) };
}
