// Walls for the accounts derivations (src/lib/accounts.ts), ported from the old console's
// accounts.test.ts, entities-accounts.test.ts and refused-account.test.ts: only the blocks that need
// plain data and the pure functions, no rendering and no stores.
//
//   - `a nearly-spent window cocks, an exhausted one goes red`. The strip's whole job is to move the
//     operator's eye to the account that is about to fail.
//   - `an excluded account is struck even when its window is nearly spent`. Exclusion is the pool's
//     own verdict; rendering it as "nearly out" points the eye at an account the daemon is refusing.
//   - `a missing window is not an empty one`. A provider that reports no usage has said nothing;
//     reading that as 0 hides the account that is nearly spent.
//   - `the next-target mark is the daemon's own`. The daemon's `next_target` flag picks the strip and
//     the console only names the rule.
import { describe, expect, test } from 'vitest';
import {
  COCK_AT_PERCENT,
  NOT_REPORTED,
  SELECTOR_ORDER_TEXT,
  accountState,
  accountsFromWire,
  canRefresh,
  exclusionText,
  isExcluded,
  isServable,
  nearestOverall,
  nearestWindow,
  nextRuleOf,
  readAgeText,
  refusalText,
  resetText,
  sevenDayUsed,
  slotWindows,
  steppedPast,
  windowLengthText,
  windowSpan,
  windowUsedText,
} from '../src/lib/accounts';
import type { AccountRow, AccountWindow, AccountWire } from '../src/types/accounts';

const HOUR_5 = 18000;
const DAY_7 = 604800;
const DAY_30 = 2592000;

/** The clock of the strip and reset blocks. */
const NOW = 1_800_000_000_000;

/** Before every fixture reset in the nearest-window blocks below, so no window there has reset yet. */
const EARLIER = 1_700_000_000_000;

function account(over: Partial<AccountRow> = {}): AccountRow {
  return {
    kind: 'chatgpt-oauth',
    label: 'acct-a',
    single_login: false,
    credential_path: null,
    primary: false,
    selected: false,
    available: true,
    pinned: false,
    next_target: false,
    credential_present: true,
    windows: [],
    heads: ['claudex'],
    ...over,
  };
}

/** A five-hour window with no reset reported. */
function window5h(used: number | null): AccountWindow {
  return { seconds: HOUR_5, used_percent: used, reset_epoch_seconds: null };
}

/** A five-hour window whose reset is far ahead of [EARLIER]. */
function window5hResetting(used: number | null): AccountWindow {
  return { seconds: HOUR_5, used_percent: used, reset_epoch_seconds: 1_800_000_000 };
}

function window7d(used: number | null): AccountWindow {
  return { seconds: DAY_7, used_percent: used, reset_epoch_seconds: null };
}

describe('the strip state', () => {
  test('a spent window goes red and cocked', () => {
    const state = accountState(account({ windows: [window5h(100)] }), NOW);
    expect(state.edge).toBe('red');
    expect(state.cocked).toBe(true);
    expect(state.struck).toBe(false);
    expect(state.label).toBe('spent 100%');
  });

  test('a window inside its last tenth cocks amber, and the label carries the number', () => {
    const state = accountState(account({ windows: [window5h(COCK_AT_PERCENT)] }), NOW);
    expect(state.edge).toBe('amber');
    expect(state.cocked).toBe(true);
    expect(state.label).toBe('warn 90%');
  });

  test('one point below the threshold is still ok, so the warning means something', () => {
    const state = accountState(account({ windows: [window5h(COCK_AT_PERCENT - 1)] }), NOW);
    expect(state.edge).toBe('green');
    expect(state.cocked).toBe(false);
    expect(state.label).toBe('ok');
  });

  test('a provider that reported nothing is quiet grey, never green', () => {
    const state = accountState(account({ windows: [window5h(null)] }), NOW);
    expect(state.edge).toBe('grey');
    expect(state.cocked).toBe(false);
    expect(state.struck).toBe(false);
    expect(state.label).toBe(NOT_REPORTED);
  });

  test('an account with no windows at all is quiet the same way', () => {
    expect(accountState(account({ windows: [] }), NOW).label).toBe(NOT_REPORTED);
  });

  test('the last reported window decides, not the first', () => {
    const state = accountState(account({
      windows: [window5h(12), { seconds: DAY_7, used_percent: 96, reset_epoch_seconds: null }],
    }), NOW);
    expect(state.edge).toBe('amber');
  });
});

describe('exclusion', () => {
  test('an unavailable account is struck even with a nearly spent window', () => {
    const state = accountState(account({ available: false, windows: [window5h(99)] }), NOW);
    expect(state.struck).toBe(true);
    expect(state.edge).toBe('grey');
    expect(state.cocked).toBe(false); // struck outranks cocked: a disabled strip is not needs-me
    expect(state.label).toBe('excluded');
  });

  test('an exclusion that has lapsed is not an exclusion', () => {
    const lapsed = account({
      auth_excluded_until_epoch_millis: NOW - 1000,
      auth_exclusion_reason: 'cooling down',
    });
    expect(isExcluded(lapsed, NOW)).toBe(false);
    expect(accountState(lapsed, NOW).struck).toBe(false);
  });

  test('an exclusion still running is an exclusion', () => {
    const running = account({
      auth_excluded_until_epoch_millis: NOW + 60_000,
      auth_exclusion_reason: 'cooling down',
    });
    expect(isExcluded(running, NOW)).toBe(true);
    expect(exclusionText(running)).toBe('cooling down');
  });

  test('an exclusion with no reason still says something rather than nothing', () => {
    expect(exclusionText(account({ available: false }))).toBe('Excluded by the pool, with no reason given.');
  });
});

describe('not reported by provider', () => {
  test('is the printed text for a window with no figure', () => {
    expect(windowUsedText(window5h(null))).toBe(NOT_REPORTED);
    expect(windowUsedText(window5h(0))).toBe('0%'); // a MEASURED zero is a zero and prints as one
  });

  test('a reset the daemon did not send prints nothing, not a placeholder', () => {
    expect(resetText(null, NOW)).toBeNull();
  });

  test('a reset in the future reads as a duration', () => {
    expect(resetText(Math.floor(NOW / 1000) + 7200, NOW)).toBe('in 2h 0m');
  });

  test('a reset already past reads as now', () => {
    expect(resetText(Math.floor(NOW / 1000) - 5, NOW)).toBe('now');
  });
});

describe('the selector order is printed, not implied', () => {
  test('the sentence is the daemon rule word for word', () => {
    expect(SELECTOR_ORDER_TEXT).toBe('pinned, then saved order, then primary, then last used, then most weekly room');
  });
});

describe('a window read before its reset', () => {
  // muse on 2026-09-24: its seven-day window read 99%, read 6.5 days earlier, and reset 91 hours ago
  const reset = NOW / 1000 - 91 * 3600;
  const stale = { seconds: 7 * 86_400, used_percent: 99, reset_epoch_seconds: reset };
  const muse = account({ kind: 'muse-oauth', label: null, single_login: true, windows: [stale], observed_at_epoch_seconds: NOW / 1000 - 6.5 * 86_400 });

  // Partial port: the old test also rendered the accounts row (widgets/account-table, React) and
  // asserted the `Reset, not re-read` slot text; only the state assertions are pure.
  test('is no figure at all: no warn edge, and the row reads stale in its slot', () => {
    expect(accountState(muse, NOW).label).toBe(NOT_REPORTED);
    expect(accountState(muse, NOW).edge).toBe('grey');
    // the same window before its reset is the figure it was
    expect(accountState(muse, reset * 1000 - 1).label).toBe('warn 99%');
  });

  test('the opened account says when it was read, and which window reset since', () => {
    expect(readAgeText(muse, NOW)).toBe('windows read 6d ago; the 7d window has reset since, so its figure is unknown until the next reading');
    expect(readAgeText({ ...muse, windows: [] }, NOW)).toBe('windows read 6d ago');
    expect(readAgeText({ ...muse, observed_at_epoch_seconds: null }, NOW)).toBeNull();
  });
});

describe('nearest window', () => {
  test('picks the highest reported used percent', () => {
    const a = account({ windows: [window5hResetting(12), window7d(74)] });
    expect(nearestWindow(a, EARLIER)?.used_percent).toBe(74);
  });

  test('a window the provider does not report is NOT a candidate, never a zero', () => {
    const a = account({ windows: [window5hResetting(null), window7d(74)] });
    expect(nearestWindow(a, EARLIER)?.used_percent).toBe(74);
    expect(windowUsedText(window5hResetting(null))).toBe(NOT_REPORTED);
    expect(windowUsedText(window5hResetting(null))).not.toBe('0%');
  });

  test('an account reporting nothing has no nearest window at all', () => {
    const a = account({ windows: [window5hResetting(null)] });
    expect(nearestWindow(a, EARLIER)).toBeNull();
    expect(nearestOverall([a], EARLIER)).toBeNull();
  });

  test('a tie goes to the shorter window, which resets first', () => {
    const a = account({ windows: [window7d(50), window5hResetting(50)] });
    expect(nearestWindow(a, EARLIER)?.seconds).toBe(HOUR_5);
  });

  test('overall takes the nearest across accounts, ignoring the ones that report nothing', () => {
    const quiet = account({ label: 'quiet', windows: [window5hResetting(null)] });
    const spent = account({ label: 'spent', windows: [window5hResetting(91)] });
    const mild = account({ label: 'mild', windows: [window5hResetting(20)] });
    expect(nearestOverall([quiet, mild, spent], EARLIER)?.account.label).toBe('spent');
  });
});

describe('window labels come from the reported length', () => {
  test('5h, 7d and Grok 30d', () => {
    expect(windowLengthText(HOUR_5)).toBe('5h');
    expect(windowLengthText(DAY_7)).toBe('7d');
    expect(windowLengthText(DAY_30)).toBe('30d');
  });

  test('nothing is ever labelled weekly by position', () => {
    // Grok reports a 30-day period; a console that called the second window "weekly" would be
    // naming a length no provider sent (FEATURES 2.6, GrokQuotaProbe.kt:41-52).
    expect(windowLengthText(DAY_30)).not.toContain('w');
  });
});

// The two row shapes GET /api/accounts sends (AccountsRoute.write), as a live daemon sent them on
// 2026-09-22: a pooled account whose provider reported both windows, and a single-login head whose
// label, flags and windows are all null. The page crashed on the second (`e.windows is not
// iterable`) for as long as the console typed the wire it wished for.
function wireRow(over: Partial<AccountWire> = {}): AccountWire {
  return {
    credential_path: '/home/op/.codex/auth.json',
    kind: 'chatgpt-oauth',
    label: 'work',
    primary: false,
    single_login: false,
    plan: 'plus',
    five_hour_used_percent: 42,
    five_hour_reset_epoch_seconds: 1_800_003_600,
    five_hour_window_seconds: HOUR_5,
    five_hour_current: true,
    seven_day_used_percent: 7,
    seven_day_reset_epoch_seconds: 1_800_086_400,
    seven_day_window_seconds: DAY_7,
    seven_day_current: true,
    available: true,
    credential_present: true,
    auth_excluded_until_epoch_millis: null,
    auth_exclusion_reason: null,
    selected: false,
    pinned: false,
    next_target: true,
    heads: ['e2e-codex'],
    observed_at_epoch_seconds: null,
    ...over,
  };
}

const SINGLE_LOGIN = wireRow({
  label: null,
  primary: true,
  single_login: true,
  plan: null,
  five_hour_used_percent: null,
  five_hour_reset_epoch_seconds: null,
  five_hour_window_seconds: null,
  seven_day_used_percent: null,
  seven_day_reset_epoch_seconds: null,
  seven_day_window_seconds: null,
  five_hour_current: false,
  seven_day_current: false,
  available: null,
  selected: null,
  pinned: null,
  next_target: null,
});

function windowUsedTextOf(row: AccountRow): string {
  const nearest = nearestWindow(row, EARLIER);
  return nearest === null ? NOT_REPORTED : windowUsedText(nearest);
}

describe('the accounts wire becomes the page model', () => {
  test('each reported slot becomes a window at the length the provider reported', () => {
    const [row] = accountsFromWire({ accounts: [wireRow({ seven_day_window_seconds: DAY_30 })] }).accounts;
    expect(row?.windows).toEqual([
      { seconds: HOUR_5, used_percent: 42, reset_epoch_seconds: 1_800_003_600, current: true },
      { seconds: DAY_30, used_percent: 7, reset_epoch_seconds: 1_800_086_400, current: true },
    ]);
  });

  test('a window the daemon sent no length for keeps its slot but never claims seven days', () => {
    const [row] = accountsFromWire({ accounts: [wireRow({ label: null, single_login: true, seven_day_window_seconds: null, five_hour_window_seconds: null })] }).accounts;
    const [short, long] = row?.windows ?? [];
    expect(short).toMatchObject({ length_known: false });
    expect(long).toMatchObject({ length_known: false });
    expect(long === undefined ? null : windowSpan(long)).toBe('long window');
    expect(short === undefined ? null : windowSpan(short)).toBe('short window');
    expect(slotWindows(row as AccountRow).long).toBe(long);
    const [known] = accountsFromWire({ accounts: [wireRow({ seven_day_window_seconds: DAY_30 })] }).accounts;
    expect(known?.windows[1]).not.toHaveProperty('length_known');
    expect(known?.windows[1] === undefined ? null : windowSpan(known.windows[1])).toBe('30d');
  });

  test('a slot the provider reported nothing for is no window, never a zero', () => {
    const [row] = accountsFromWire({ accounts: [SINGLE_LOGIN] }).accounts;
    expect(row?.windows).toEqual([]);
    expect(row === undefined ? null : windowUsedTextOf(row)).toBe(NOT_REPORTED);
  });

  test('a single-login head keeps its nulls and is never a selector candidate', () => {
    const rows = accountsFromWire({ accounts: [SINGLE_LOGIN] }).accounts;
    expect(rows[0]).toMatchObject({ label: null, single_login: true, available: null, selected: null });
    expect(rows.map((row) => nextRuleOf(row, rows))).toEqual([null]);
  });
});

describe('the reading time', () => {
  test('rides from the wire to the row, and a daemon that sends none leaves it null', () => {
    const [dated] = accountsFromWire({ accounts: [wireRow({ observed_at_epoch_seconds: 1_800_000_000 })] }).accounts;
    expect(dated?.observed_at_epoch_seconds).toBe(1_800_000_000);
    const [undated] = accountsFromWire({ accounts: [wireRow()] }).accounts;
    expect(undated?.observed_at_epoch_seconds).toBeNull();
  });
});

describe('the selector order', () => {
  test('is printed as the sentence the daemon implements, the pin first', () => {
    expect(SELECTOR_ORDER_TEXT).toBe('pinned, then saved order, then primary, then last used, then most weekly room');
  });

  /** Every row's rule in one pool, in order: null for each strip the daemon did not flag. */
  const rules = (pool: AccountRow[]) => pool.map((row) => nextRuleOf(row, pool));

  test('the flag picks the strip: an available primary the daemon did not flag is not marked', () => {
    const primary = account({ label: 'primary', primary: true, windows: [window7d(5)] });
    const pinned = account({ label: 'pinned', pinned: true, next_target: true, windows: [window7d(90)] });
    expect(rules([primary, pinned])).toEqual([null, 'pinned']);
  });

  test('a flagged primary is named primary', () => {
    const primary = account({ label: 'primary', primary: true, next_target: true, windows: [window7d(90)] });
    const roomy = account({ label: 'roomy', windows: [window7d(5)] });
    expect(rules([primary, roomy])).toEqual(['primary', null]);
  });

  test('a flagged account that is the pool\'s lowest seven-day used is named for that rule', () => {
    const primary = account({ label: 'primary', primary: true, available: false });
    const heavy = account({ label: 'heavy', windows: [window7d(80)] });
    const roomy = account({ label: 'roomy', next_target: true, windows: [window7d(5)] });
    expect(rules([primary, heavy, roomy])).toEqual([null, null, 'most weekly room']);
  });

  test('a flagged account that is neither can only be the previous one, the sticky rule', () => {
    const primary = account({ label: 'primary', primary: true, available: false });
    const sticky = account({ label: 'sticky', next_target: true, windows: [window7d(80)] });
    const roomy = account({ label: 'roomy', windows: [window7d(5)] });
    expect(rules([primary, sticky, roomy])).toEqual([null, 'last used', null]);
  });

  test('the lowest is found inside the flagged account\'s own pool, never across pools', () => {
    // `other` rides another head with more room; the flagged account is still its own pool's lowest.
    const flagged = account({ label: 'mine', next_target: true, heads: ['codex-a'], windows: [window7d(40)] });
    const other = account({ label: 'other', heads: ['codex-b'], windows: [window7d(1)] });
    expect(nextRuleOf(flagged, [flagged, other])).toBe('most weekly room');
  });

  test('an account with no seven-day snapshot sorts as zero used, as the daemon does', () => {
    const fresh = account({ label: 'fresh', next_target: true, windows: [] });
    expect(sevenDayUsed(fresh)).toBe(0);
    const used = account({ label: 'used', windows: [window7d(40)] });
    expect(rules([used, fresh])).toEqual([null, 'most weekly room']);
  });

  test('a window present but unreported also sorts as zero, never as unavailable', () => {
    expect(sevenDayUsed(account({ windows: [window7d(null)] }))).toBe(0);
  });

  test("the seven-day SLOT is any window past six hours, as the daemon files it: Grok's 30 days counts", () => {
    const grok = account({ windows: [{ seconds: 5 * 3600, used_percent: 90, reset_epoch_seconds: null }, { seconds: 30 * 86400, used_percent: 55, reset_epoch_seconds: null }] });
    expect(sevenDayUsed(grok)).toBe(55);
  });

  test('a pool the daemon flagged nothing in has no next target, and says so with null', () => {
    expect(rules([account({ available: false }), account({ label: 'b' })])).toEqual([null, null]);
  });
});

describe('a refused credential on the wire', () => {
  const REASON = 'is a symbolic link, and splice does not load a linked credential';
  const SENTENCE = `'linked' ${REASON}; remove the link and sign in again, or sign in under a different label`;

  /** The refused-credential wire row: no credential, unavailable, no windows. */
  function refusedWire(over: Partial<AccountWire> = {}): AccountWire {
    return wireRow({
      credential_path: '/pool/linked.json.refused', label: 'linked', plan: null,
      five_hour_used_percent: null, five_hour_reset_epoch_seconds: null, five_hour_window_seconds: null,
      seven_day_used_percent: null, seven_day_reset_epoch_seconds: null, seven_day_window_seconds: null,
      available: false, credential_present: false, next_target: false, heads: ['claudex'],
      five_hour_current: false, seven_day_current: false, ...over,
    });
  }

  test('a refusal on the wire reaches the row, and a daemon without one reads as none', () => {
    const [carried] = accountsFromWire({ accounts: [refusedWire({ refusal: SENTENCE })] }).accounts;
    const [older] = accountsFromWire({ accounts: [refusedWire()] }).accounts;

    expect(carried?.refusal).toBe(SENTENCE);
    expect(older?.refusal).toBeNull();
  });
});

describe('an account the daemon cannot load', () => {
  const REFUSED = "'linked' is a symbolic link, and splice does not load a linked credential";

  test('one with a credential and no refusal is servable, a missing file or a refusal is not', () => {
    expect(isServable(account())).toBe(true);
    expect(isServable(account({ credential_present: false }))).toBe(false);
    expect(isServable(account({ refusal: REFUSED }))).toBe(false);
    expect(isServable(account({ refusal: '  ' }))).toBe(true);
    expect(refusalText(account({ refusal: ` ${REFUSED} ` }))).toBe(REFUSED);
    expect(refusalText(account({ refusal: null }))).toBeNull();
  });

  test('a head is refreshed only while the account that serves it can be loaded', () => {
    const serving = account({ label: 'a', selected: true });
    const refused = account({ label: 'b', credential_present: false, refusal: REFUSED });
    expect(canRefresh([])).toBe(true);
    expect(canRefresh([serving, refused])).toBe(true);
    expect(canRefresh([{ ...serving, selected: false }, { ...refused, selected: true }])).toBe(false);
    expect(canRefresh([account({ credential_present: false })])).toBe(false);
    expect(canRefresh([account()])).toBe(true);
  });

  test('a refused account reads as refused, not as a missing figure and not as merely excluded', () => {
    const state = accountState(account({ credential_present: false, available: false, refusal: REFUSED }), NOW);
    expect(state).toMatchObject({ label: 'refused', struck: true, cocked: false, edge: 'grey' });
    expect(accountState(account({ credential_present: false, available: false }), NOW).label).toBe('excluded');
  });
});

describe('a spent account the pool has stepped past', () => {
  const spent = (over: Partial<AccountRow> = {}) => account({ label: 'primary', primary: true, available: false, windows: [window7d(100)], ...over });
  const serving = (over: Partial<AccountRow> = {}) => account({ label: 'work', selected: true, windows: [window7d(20)], ...over });
  test('names the account that serves and the window that is used, and reads quiet, never as an alarm', () => {
    const pool = [spent(), serving()];
    expect(steppedPast(pool[0] as AccountRow, pool, NOW)).toEqual({ serving: 'work', window: pool[0]?.windows[0] });
    expect(accountState(pool[0] as AccountRow, NOW, pool)).toEqual({ edge: 'grey', cocked: false, struck: false, label: 'used' });
    expect(accountState(pool[0] as AccountRow, NOW)).toMatchObject({ edge: 'grey', struck: true, label: 'excluded' });
  });
  test('is not claimed when nothing else can serve, when the account serves itself, or when the daemon gave its own reason', () => {
    const alone = [spent()];
    expect(steppedPast(alone[0] as AccountRow, alone, NOW)).toBeNull();
    const refusing = [spent(), serving({ available: false })];
    expect(steppedPast(refusing[0] as AccountRow, refusing, NOW)).toBeNull();
    const itself = [spent({ selected: true }), serving({ selected: false })];
    expect(steppedPast(itself[0] as AccountRow, itself, NOW)).toBeNull();
    const reasoned = [spent({ auth_exclusion_reason: 'Synthetic refusal.' }), serving()];
    expect(steppedPast(reasoned[0] as AccountRow, reasoned, NOW)).toBeNull();
    const roomy = [spent({ windows: [window7d(60)] }), serving()];
    expect(steppedPast(roomy[0] as AccountRow, roomy, NOW)).toBeNull();
  });
});
