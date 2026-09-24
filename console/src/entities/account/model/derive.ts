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
import { fmtDurationS } from '@shared/lib';
import type { Edge } from '@shared/ui';
import type { AccountRow, AccountWindow } from './types';

/** What a window with no figure is called. The world's rule: never 0 (FEATURES 2.2, 4.5), and the
 *  word is `unknown` - "we asked and were NOT TOLD" - which is the same fact this site used to spell
 *  out in three words (M1-74). A second phrasing for one fact is how a console reaches eleven ways
 *  of saying nothing is here. */
export const NOT_REPORTED = 'unknown';

/** The selector's real order (AccountPool.candidates, AccountPool.kt:179-186): the operator's pin,
 *  then primary, then the caller's previous account (the session's sticky one), then the lowest
 *  seven-day used. Each is also the reason printed beside the account that rule chose, so each is
 *  said the way the operator would say it: the session's sticky account is the one it `last used`,
 *  and the lowest seven-day figure is the account with the `most weekly room` (console review,
 *  2026-09-24; they printed `sticky` and `lowest 7-day used`). */
export const SELECTOR_RULES = ['pinned', 'primary', 'last used', 'most weekly room'] as const;
export type SelectorRule = (typeof SELECTOR_RULES)[number];

/** The selector's order as one printed sentence, made from the rules so the two cannot disagree. */
export const SELECTOR_ORDER_TEXT = SELECTOR_RULES.join(', then ');

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
 * The window closest to exhaustion, or null when the account reports no used figure at all.
 *
 * A window with no reported figure is NOT a candidate: it is an absence, and an absence cannot be
 * "nearest to exhausted". Ties go to the shorter window, which is the one that resets sooner and
 * therefore the one the operator is actually waiting on.
 */
export function nearestWindow(account: AccountRow): AccountWindow | null {
  let best: AccountWindow | null = null;
  for (const window of account.windows) {
    if (window.used_percent === null) continue;
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
export function nearestOverall(accounts: readonly AccountRow[]): NearestOverall | null {
  let best: NearestOverall | null = null;
  for (const account of accounts) {
    const window = nearestWindow(account);
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
export const EXCLUDED_REASON = 'excluded by the pool';

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

/** The exclusion's reason, in the daemon's own words where it sent any. */
export function exclusionText(account: AccountRow): string {
  const reason = account.auth_exclusion_reason ?? '';
  return reason.trim() === '' ? EXCLUDED_REASON : reason;
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
export function accountState(account: AccountRow, nowMs: number): AccountState {
  if (isExcluded(account, nowMs)) {
    return { edge: 'grey', cocked: false, struck: true, label: 'excluded' };
  }
  const window = nearestWindow(account);
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
