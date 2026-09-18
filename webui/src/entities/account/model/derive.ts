// The accounts entity's pure arithmetic and prose: which window is nearest to exhaustion, how a
// window is labelled from its own reported length, and which account the selector takes next.
//
// These are pinned by test because each one has a wrong answer that LOOKS right:
//   - treating a missing used-percent as 0 makes an unreported window the emptiest thing on the
//     page, so the account the operator should watch reads as the one with the most room;
//   - labelling a window by its position (first = 5h, second = weekly) is wrong the moment a
//     provider reports a 30-day period, which Grok already does;
//   - "lowest used" without the primary/sticky rules names a different account than the daemon
//     will actually take next, so the mark lands on the wrong strip.
import type { AccountRow, AccountWindow } from './types';

/** What a window with no figure is called. The world's rule: never 0 (FEATURES 2.2, 4.5). */
export const NOT_REPORTED = 'not reported by provider';

/** The selector's real order (AccountPool.kt:101-112), as one printed sentence. */
export const SELECTOR_ORDER_TEXT = 'primary then sticky then lowest 7-day used';

export const SELECTOR_RULES = ['primary', 'sticky', 'lowest 7-day used'] as const;
export type SelectorRule = (typeof SELECTOR_RULES)[number];

/** The window length the daemon calls the seven-day window, for the selector's third rule. */
const SEVEN_DAY_SECONDS = 604800;

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
 * An account's seven-day used figure for the selector's third rule. An account with NO snapshot
 * sorts as zero used — that is the daemon's own rule (AccountPool.kt:101-112), not a convenience:
 * a freshly added account has no poller reading yet and must still be selectable.
 */
export function sevenDayUsed(account: AccountRow): number {
  const window = account.windows.find((w) => w.seconds === SEVEN_DAY_SECONDS);
  if (window === undefined || window.used_percent === null) return 0;
  return window.used_percent;
}

export interface NextTarget {
  label: string;
  rule: SelectorRule;
}

/**
 * The account the selector takes next, by the daemon's real order: primary if available, else the
 * session's sticky account, else the lowest seven-day used. Returns null only when nothing in the
 * pool is available at all — which is the state that fails a turn in words naming the earliest
 * reset, so it is a real answer and not an edge case to paper over.
 */
export function nextTarget(accounts: readonly AccountRow[], stickyLabel?: string): NextTarget | null {
  const usable = accounts.filter((account) => account.available);
  if (usable.length === 0) return null;

  const primary = usable.find((account) => account.primary);
  if (primary !== undefined) return { label: primary.label, rule: 'primary' };

  if (stickyLabel !== undefined) {
    const sticky = usable.find((account) => account.label === stickyLabel);
    if (sticky !== undefined) return { label: sticky.label, rule: 'sticky' };
  }

  let best: AccountRow | null = null;
  let bestUsed = Number.POSITIVE_INFINITY;
  for (const account of usable) {
    const used = sevenDayUsed(account);
    if (used < bestUsed) { bestUsed = used; best = account; }
  }
  return best === null ? null : { label: best.label, rule: 'lowest 7-day used' };
}
