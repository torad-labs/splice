// The nearest limit is ONE derivation wherever it is printed (review of #264). The status strip and the
// fleet printed 38% while the accounts page printed 64% for one fleet: the first two read /api/usage,
// which reports only each head's SELECTED account, and the third read every pooled account. The pure
// half is pinned here: fed the sources, the one derivation names the number every surface prints.
import { describe, expect, test } from 'vitest';
import { accountsFromWire, readAgeText } from '../src/lib/accounts';
import { nearestLimit } from '../src/lib/nearest-limit';
import { nearestWindow } from '../src/lib/usage';
import type { AccountRow, AccountWindow, AccountWire } from '../src/types/accounts';
import type { AuthPayload, HeadUsageEntry, UsagePayload } from '../src/types/core';

// The fixture's resets are placed ahead of the real clock.
const NOW = Math.floor(Date.now() / 1000) * 1000;
const at = (seconds: number) => NOW / 1000 + seconds;
const HOUR_5 = 18_000;
const DAY_7 = 604_800;

function win(seconds: number, used: number, resetIn: number): AccountWindow {
  return { seconds, used_percent: used, reset_epoch_seconds: at(resetIn) };
}

function account(label: string, windows: AccountWindow[], over: Partial<AccountRow> = {}): AccountRow {
  return {
    kind: 'chatgpt-oauth', label, single_login: false, credential_path: null, primary: false, selected: false,
    available: true, pinned: false, next_target: false, credential_present: true, windows, heads: ['claudex'], ...over,
  };
}

/** One head's /api/usage entry reporting its plan windows, as the daemon folds them. */
function planHead(key: string, fiveHour: number, sevenDay: number): HeadUsageEntry {
  return {
    key, label: key,
    usage: {
      output_tokens_5h: 0, entries: 0, ratelimit: null,
      warn: { level: 'ok', pct: fiveHour, source: 'quota_5h', reset: null },
      quota: { five_hour: { used_pct: fiveHour, resets_at: at(2 * 3600) }, seven_day: { used_pct: sevenDay, resets_at: at(6 * 86_400) } },
    },
  };
}

const auth: AuthPayload = {
  claudex: { kind: 'chatgpt-oauth', login: 'browser', present: true },
  claude: { kind: 'client', login: 'browser', present: true, account_id_masked: 'acct-c' },
};

/** The review's fleet: claudex rides a pool of two, and /api/usage reports only the selected one. */
const pool = [
  account('primary', [win(HOUR_5, 38, 2 * 3600 + 4 * 60), win(DAY_7, 21, 6 * 86_400)], { selected: true }),
  account('work', [win(HOUR_5, 12, 41 * 60 + 24), win(DAY_7, 64, 3 * 86_400 + 10 * 3600)]),
];

function usageOf(claude: number): UsagePayload {
  return { window_hours: 5, warn_pct: 80, warn_tokens_5h: 0, heads: [planHead('claudex', 38, 21), planHead('claude', claude, 10)] };
}

describe('the nearest limit is one number on the strip, the fleet and the accounts page', () => {
  test('a pooled account the head has not selected counts: work at 64% weekly, not the selected 38%', () => {
    const sources = { accounts: pool, usage: usageOf(20), auth };
    // what the strip and the fleet used to read: the selected account alone
    expect(nearestWindow(sources.usage, auth, NOW)?.pct).toBe(38);
    const limit = nearestLimit(sources, NOW);
    if (limit === null) throw new Error('the review fleet reports windows, so it has a nearest limit');
    expect(limit).toMatchObject({ head: 'claudex', account: 'work', window: '7d', pct: 64 });
  });

  test('a head no account row names counts too: the Claude head at 70% is the limit on all three', () => {
    const sources = { accounts: pool, usage: usageOf(70), auth };
    expect(nearestLimit(sources, NOW)).toMatchObject({ head: 'claude', account: 'acct-c', window: '5h', pct: 70 });
  });

  test('an account that cannot serve ranks after every one that can, and is named once none can', () => {
    const spent = account('spent', [win(HOUR_5, 100, 3600)]);
    const refused = account('refused', [win(HOUR_5, 97, 3600)], { available: false });
    const room = account('room', [win(HOUR_5, 30, 3600)]);
    const noKey = account('nokey', [win(HOUR_5, 90, 3600)], { credential_present: false });
    const quiet = { window_hours: 5, warn_pct: 80, warn_tokens_5h: 0, heads: [] };
    expect(nearestLimit({ accounts: [spent, refused, noKey, room], usage: quiet, auth: null }, NOW)?.account).toBe('room');
    expect(nearestLimit({ accounts: [refused, spent], usage: quiet, auth: null }, NOW)).toMatchObject({ account: 'spent', pct: 100, level: 'critical' });
    expect(nearestLimit({ accounts: [spent, room], usage: quiet, auth: null }, NOW)).toMatchObject({ account: 'room', pct: 30 });
  });

  test('nothing reported anywhere is no limit, never a zero', () => {
    expect(nearestLimit({ accounts: [account('quiet', [])], usage: null, auth: null }, NOW)).toBeNull();
  });
});

// The console half of the daemon's per-window currency flag. A codex 7d reading at 100%, read 4.5 h earlier
// on a home with no ChatGPT sign-in, was the header's, Fleet's and Needs you's nearest limit for as long as
// its reset was ahead. The daemon now says per window whether the reading may count as the plan's usage now
// (`five_hour_current`, `seven_day_current`); the nearest limit reads only those, while the Accounts
// page keeps the old reading and its age.
const ago = (seconds: number): number => at(-seconds);
const stale = (seconds: number, used: number, resetIn: number): AccountWindow => ({ ...win(seconds, used, resetIn), current: false });
const current = (seconds: number, used: number, resetIn: number): AccountWindow => ({ ...win(seconds, used, resetIn), current: true });
const QUIET: UsagePayload = { window_hours: 5, warn_pct: 80, warn_tokens_5h: 0, heads: [] };

/** A daemon row in the shape /api/accounts sends it, its windows and their flags set by [over]. */
function wireRow(over: Partial<AccountWire>): AccountWire {
  return {
    credential_path: '/home/op/.codex/auth.json', kind: 'chatgpt-oauth', label: 'work', primary: false, single_login: false,
    plan: 'plus', five_hour_used_percent: null, five_hour_reset_epoch_seconds: null, five_hour_window_seconds: null,
    seven_day_used_percent: null, seven_day_reset_epoch_seconds: null, seven_day_window_seconds: null, available: true,
    credential_present: true, auth_excluded_until_epoch_millis: null, auth_exclusion_reason: null, selected: false, pinned: false,
    next_target: false, heads: ['claudex'], observed_at_epoch_seconds: null, five_hour_current: true, seven_day_current: true, ...over,
  };
}

describe('the nearest limit reads only the windows the daemon calls current', () => {
  const old = account('old', [stale(DAY_7, 100, 6 * 86_400)], { credential_present: false, observed_at_epoch_seconds: ago(4.5 * 3600) });

  test('a 100% weekly reading that is not current is no limit on the strip, Fleet or Accounts', () => {
    const sources = { accounts: [old], usage: QUIET, auth: null };
    expect(nearestLimit(sources, NOW)).toBeNull();
  });

  test('the next current window is the limit: a stale 90% on an account that can serve beside a current 60%', () => {
    const serving = account('serving', [stale(DAY_7, 90, 6 * 86_400)], { observed_at_epoch_seconds: ago(4.5 * 3600) });
    const sources = { accounts: [serving, account('work', [current(DAY_7, 60, 3 * 86_400)])], usage: QUIET, auth: null };
    expect(nearestLimit(sources, NOW)).toMatchObject({ account: 'work', window: '7d', pct: 60 });
  });

  test('within one account only the current window counts', () => {
    const both = account('both', [current(HOUR_5, 30, 3600), stale(DAY_7, 100, 6 * 86_400)]);
    expect(nearestLimit({ accounts: [both], usage: QUIET, auth: null }, NOW)).toMatchObject({ window: '5h', pct: 30, level: 'ok' });
  });

  test('a current window counts, and so does one the daemon made no claim about', () => {
    const now = account('now', [current(DAY_7, 100, 6 * 86_400)]);
    expect(nearestLimit({ accounts: [now], usage: QUIET, auth: null }, NOW)).toMatchObject({ pct: 100, level: 'critical' });
    const undated = account('undated', [win(DAY_7, 55, 3 * 86_400)]);
    expect(nearestLimit({ accounts: [undated], usage: QUIET, auth: null }, NOW)).toMatchObject({ account: 'undated', pct: 55 });
  });

  test('the flags ride the wire into the model, and the ranking follows them', () => {
    const flagged = wireRow({
      five_hour_used_percent: 30, five_hour_reset_epoch_seconds: at(3600), five_hour_window_seconds: HOUR_5, five_hour_current: true,
      seven_day_used_percent: 100, seven_day_reset_epoch_seconds: at(6 * 86_400), seven_day_window_seconds: DAY_7, seven_day_current: false,
    });
    const [row] = accountsFromWire({ accounts: [flagged] }).accounts;
    expect(row?.windows.map((window) => window.current)).toEqual([true, false]);
    expect(nearestLimit({ accounts: [row as AccountRow], usage: QUIET, auth: null }, NOW)).toMatchObject({ window: '5h', pct: 30 });
  });

  test('the account keeps the old reading and says how old it is', () => {
    expect(readAgeText(old, NOW)).toMatch(/^windows read 4h/);
  });
});

// The nearest limit named a head that could serve. Marlin's re-walk: the header and Fleet's Nearest limit
// read "claude-grok 7d 1%" while claudex's one login sat at 7d 100%, current, read a minute before, so one of
// the operator's commands was out for six days behind a calm 1%. The serving rule ranks an account that
// cannot serve after every one that can because a POOL steps past it. That holds inside one head's pool
// only: a single login has nothing to step to. A head with no account that can serve has reached a limit,
// and ranks by its own reading with every other one.
describe('a head with no account that can serve is a limit the fleet has reached', () => {
  const claudexLogin = account('claudex', [current(DAY_7, 100, 6 * 86_400 + 5 * 3600)], {
    label: null, single_login: true, heads: ['claudex'],
  });
  const grokLogin = account('grok', [current(DAY_7, 1, 6 * 3600 + 51 * 60)], {
    kind: 'grok-oauth', label: null, single_login: true, heads: ['grok'],
  });

  test('a single login at 7d 100% current names its head, critical, with its reset, before another head at 1%', () => {
    const sources = { accounts: [grokLogin, claudexLogin], usage: QUIET, auth: null };
    const limit = nearestLimit(sources, NOW);
    expect(limit).toMatchObject({ head: 'claudex', window: '7d', pct: 100, level: 'critical' });
    expect(limit?.reset).toMatch(/^in 6d /);
    expect(nearestLimit({ ...sources, accounts: [claudexLogin, grokLogin] }, NOW)).toEqual(limit);
  });

  test('a pooled head whose spent account has a serving sibling still ranks the sibling, and not the spent one', () => {
    const pooled = [
      account('spent', [current(DAY_7, 100, 6 * 86_400)], { heads: ['claudex'] }),
      account('sibling', [current(DAY_7, 40, 3 * 86_400)], { heads: ['claudex'] }),
    ];
    expect(nearestLimit({ accounts: [...pooled, grokLogin], usage: QUIET, auth: null }, NOW))
      .toMatchObject({ head: 'claudex', account: 'sibling', pct: 40 });
    expect(nearestLimit({ accounts: [...pooled], usage: QUIET, auth: null }, NOW)).toMatchObject({ account: 'sibling', pct: 40 });
  });

  test('a stale 100% stays out, so the head that can serve is named', () => {
    const staleLogin = account('claudex', [stale(DAY_7, 100, 6 * 86_400)], { label: null, single_login: true, heads: ['claudex'] });
    expect(nearestLimit({ accounts: [staleLogin, grokLogin], usage: QUIET, auth: null }, NOW))
      .toMatchObject({ head: 'grok', pct: 1 });
  });

  test('an account two heads ride is stepped past only when both have another that serves', () => {
    const shared = account('shared', [current(DAY_7, 100, 6 * 86_400)], { heads: ['claudex', 'grok'] });
    const claudexSpare = account('spare', [current(DAY_7, 20, 6 * 86_400)], { heads: ['claudex'] });
    const grokSpare = account('grok-spare', [current(DAY_7, 5, 6 * 86_400)], { kind: 'grok-oauth', heads: ['grok'] });
    expect(nearestLimit({ accounts: [shared, claudexSpare], usage: QUIET, auth: null }, NOW))
      .toMatchObject({ account: 'shared', pct: 100 });
    expect(nearestLimit({ accounts: [shared, claudexSpare, grokSpare], usage: QUIET, auth: null }, NOW))
      .toMatchObject({ account: 'spare', pct: 20 });
  });

  test('a spent account no head rides blocks nothing, so it still ranks after one that can serve', () => {
    const orphan = account('orphan', [current(DAY_7, 100, 6 * 86_400)], { heads: [] });
    expect(nearestLimit({ accounts: [orphan, grokLogin], usage: QUIET, auth: null }, NOW)).toMatchObject({ head: 'grok', pct: 1 });
  });

  test('a head only /api/usage reports, spent, is a limit reached beside another head at 1%', () => {
    const spentHead: UsagePayload = { window_hours: 5, warn_pct: 80, warn_tokens_5h: 0, heads: [planHead('claude', 100, 10)] };
    expect(nearestLimit({ accounts: [grokLogin], usage: spentHead, auth: null }, NOW))
      .toMatchObject({ head: 'claude', window: '5h', pct: 100, level: 'critical' });
  });
});
