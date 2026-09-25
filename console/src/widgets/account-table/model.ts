// An account as the console draws it, wherever it is drawn (the accounts page and a fleet head's
// pool): its state as a tone and a mark, and its windows as figures and countdowns. Pure, so the
// thresholds and the stale rule are pinned without a renderer.
import {
  COCK_AT_PERCENT, EXHAUSTED_AT_PERCENT, isExcluded, isStale, nearestWindow,
} from '@entities/account';
import type { AccountRow, AccountWindow } from '@entities/account';
import { windowName } from '@features/nearest-limit';
import { fmtDurationS } from '@shared/lib';
import type { BarPart, Mark, Tone } from '@shared/ui';
import { S } from './strings';

/** What an account is, for the selector: takeable with room, near its limit, spent, refused by the
 *  pool, unknown because its provider reported no figure, or signed out because its credential is
 *  gone. */
export type AccountStateKey = keyof typeof S.stateName;

/** Order matters and is the whole design: an excluded account is excluded even when a window is
 *  nearly spent, because the pool will not take it at all, and reporting it as near its limit would
 *  point the operator at an account the daemon is already refusing. The thresholds are the entity's
 *  (COCK_AT_PERCENT, EXHAUSTED_AT_PERCENT). */
export function stateOf(account: AccountRow, nowMs: number): AccountStateKey {
  // A login with no credential file serves nothing, so its last window is history, not a state
  // (Marlin, 2026-09-25: it read OK beside a missing credential). Signing in is the fix, and it
  // comes before any exclusion the pool made while it could not authenticate.
  if (!account.credential_present) return 'signedOut';
  if (isExcluded(account, nowMs)) return 'excluded';
  const used = nearestWindow(account, nowMs)?.used_percent ?? null;
  if (used === null) return 'unknown';
  if (used >= EXHAUSTED_AT_PERCENT) return 'spent';
  if (used >= COCK_AT_PERCENT) return 'warn';
  return 'ok';
}

/** A state's badge tone and chart mark, from one mapping each. Unknown is never green: green would
 *  claim a health nobody measured. */
export const TONE: Record<AccountStateKey, Tone> = { ok: 'ok', warn: 'warn', spent: 'danger', excluded: 'neutral', unknown: 'neutral', signedOut: 'warn' };
export const MARK: Record<AccountStateKey, Mark> = { ok: 'ok', warn: 'warn', spent: 'danger', excluded: 'series-3', unknown: 'series-2', signedOut: 'warn' };

/** The bar's order: the two amber states stand apart so they never read as one part. Signed out is
 *  amber, as a signed-out head is on the fleet (heads derive: only unhealthy is red) and a signed-out
 *  head is on this page: a warning the operator can act on. */
const STATES: readonly AccountStateKey[] = ['ok', 'warn', 'spent', 'excluded', 'unknown', 'signedOut'];

/** The accounts by state as bar parts, in a fixed order so the colours never swap places. */
export function stateParts(accounts: readonly AccountRow[], nowMs: number): BarPart[] {
  const counts = new Map<AccountStateKey, number>();
  for (const account of accounts) {
    const state = stateOf(account, nowMs);
    counts.set(state, (counts.get(state) ?? 0) + 1);
  }
  return STATES.map((state) => ({ key: state, label: S.stateName[state], value: counts.get(state) ?? 0, mark: MARK[state] }));
}

/** A used figure's tone: the same thresholds as the account's state. */
export function usedTone(percent: number): Tone {
  if (percent >= EXHAUSTED_AT_PERCENT) return 'danger';
  if (percent >= COCK_AT_PERCENT) return 'warn';
  return 'ok';
}

/** An account's name: its pool label, or `Single login` for an OAuth head with no pool. */
export function accountName(account: AccountRow): string {
  return account.label ?? S.singleLogin;
}

/** A window's name, from the one definition the nearest limit also prints. */
export { windowName };

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
