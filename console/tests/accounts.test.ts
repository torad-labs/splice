// WALLS for the accounts page. The two that matter most are the two that would still render:
//
//   - `a nearly-spent window cocks, an exhausted one goes red`. The strip's whole job is to move
//     the operator's eye to the account that is about to fail. Get the threshold wrong and the
//     page is a tidy table that never warns anyone.
//   - `an excluded account is struck even when its window is nearly spent`. Exclusion is the
//     pool's own verdict that it will not take the account. Rendering that as "nearly out" points
//     the eye at an account the daemon is already refusing, which is worse than no mark: it is a
//     confident wrong instruction.
//
// A `.ts` test cannot hold JSX (TS1161), so elements are built with React.createElement and
// asserted against renderToStaticMarkup's string.
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import {
  COCK_AT_PERCENT,
  NOT_REPORTED,
  SELECTOR_ORDER_TEXT,
  accountState,
  exclusionText,
  isExcluded,
  readAgeText,
  resetText,
  windowUsedText,
} from '../src/entities/account';
import type { AccountRow, AccountWindow } from '../src/entities/account';
import { LOGIN_PENDING_EMPTY, IDLE, canStart, next, stepMessage } from '../src/features/account-login/model';
import { H as LOGIN } from '../src/features/account-login/strings';
import {
  ACCOUNT_FIELDS, ACCOUNT_WORDS as W, AccountFacts, accountColumns, accountKey, countdown, stateOf, TONE, usedTone,
  windowFigure, windowName,
} from '../src/widgets/account-table';
import { refusalOf } from '../src/features/account-login';
import { arrangeAccounts, columnsOf, fixtureName, keyCommand, keyHelp, nextReset } from '../src/pages/accounts/model';
import { dispositions } from '../src/pages/accounts/coverage';
import { AccountsBoard, ApiKeyDetail } from '../src/pages/accounts';
import { H, S } from '../src/pages/accounts/strings';
import { ABSENT } from '../src/shared/lib';
import { DataTable, Empty } from '../src/shared/ui';
import type { View } from '../src/features/views';
import { statOf, tableOf } from './lib/markup';

const h = React.createElement;
const render = (el: React.ReactElement): string => renderToStaticMarkup(el);

const HOUR_5 = 18000;
const DAY_7 = 604800;
const DAY_30 = 2592000;
const NOW = 1_800_000_000_000;

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

function window5h(used: number | null): AccountWindow {
  return { seconds: HOUR_5, used_percent: used, reset_epoch_seconds: null };
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

  test('the longest reported length is never assumed to be weekly', () => {
    expect(windowName({ seconds: DAY_30, used_percent: 10, reset_epoch_seconds: null })).toBe('30d');
    expect(windowName({ seconds: HOUR_5, used_percent: 10, reset_epoch_seconds: null })).toBe('5h');
  });

  test('a model-scoped window says which model', () => {
    expect(windowName({ seconds: DAY_7, used_percent: 10, reset_epoch_seconds: null, model: 'opus' }))
      .toBe('opus 7d');
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
    expect(SELECTOR_ORDER_TEXT).toBe('pinned, then primary, then last used, then most weekly room');
  });
});

describe('the login flow machine', () => {
  test('a blank label cannot start: an unnamed credential is not a credential', () => {
    expect(canStart(IDLE)).toBe(false);
    expect(canStart(next(IDLE, { kind: 'label', value: '   ' }))).toBe(false);
    expect(canStart(next(IDLE, { kind: 'label', value: 'work' }))).toBe(true);
  });

  test('start, started, awaiting: the code is on screen until the credential lands', () => {
    let state = next(IDLE, { kind: 'label', value: 'work' });
    state = next(state, { kind: 'start' });
    expect(state.step).toBe('starting');
    state = next(state, {
      kind: 'started',
      payload: { login_id: 'L1', head: 'claudex', label: 'work', flow: 'device', user_code: 'AB-12' },
    });
    expect(state.step).toBe('awaiting');
    expect(stepMessage(state)).toBe(LOGIN.device);
  });

  test('a poll that is still pending keeps waiting rather than failing', () => {
    let state = next(IDLE, { kind: 'label', value: 'work' });
    state = next(state, { kind: 'started', payload: { login_id: 'L1', head: 'claudex', label: 'work', flow: 'browser' } });
    state = next(state, {
      kind: 'status',
      payload: { login_id: 'L1', head: 'claudex', label: 'work', state: 'pending', restart_required: false },
    });
    expect(state.step).toBe('awaiting');
  });

  test('a landed login that still needs a restart says so, and does not say added', () => {
    const state = next(
      next(IDLE, { kind: 'started', payload: { login_id: 'L1', head: 'claudex', label: 'work', flow: 'browser' } }),
      {
        kind: 'status',
        payload: { login_id: 'L1', head: 'claudex', label: 'work', state: 'landed', restart_required: true },
      },
    );
    expect(state.step).toBe('landed');
    expect(stepMessage(state)).toBe(LOGIN.afterRestart);
  });

  test('a landed login with no restart needed is simply added', () => {
    const state = next(
      next(IDLE, { kind: 'started', payload: { login_id: 'L1', head: 'claudex', label: 'work', flow: 'browser' } }),
      {
        kind: 'status',
        payload: { login_id: 'L1', head: 'claudex', label: 'work', state: 'landed', restart_required: false },
      },
    );
    expect(stepMessage(state)).toBe(LOGIN.added);
  });

  test('a failed login carries the daemon sentence, and the label survives for a retry', () => {
    let state = next(IDLE, { kind: 'label', value: 'work' });
    state = next(state, { kind: 'failed', note: 'device code expired' });
    expect(state.step).toBe('failed');
    expect(stepMessage(state)).toBe('device code expired');
    expect(state.label).toBe('work');
    expect(canStart(state)).toBe(true);
  });

  test('a failed status is a failure, not a wait', () => {
    const state = next(
      next(IDLE, { kind: 'started', payload: { login_id: 'L1', head: 'claudex', label: 'work', flow: 'device' } }),
      {
        kind: 'status',
        payload: { login_id: 'L1', head: 'claudex', label: 'work', state: 'failed', restart_required: false, note: 'denied' },
      },
    );
    expect(state.step).toBe('failed');
    expect(stepMessage(state)).toBe('denied');
  });

  test('a pending route leaves the form and names its row', () => {
    const state = next(next(IDLE, { kind: 'label', value: 'work' }), { kind: 'pending', row: 'V4-132' });
    expect(state.step).toBe('pending');
    expect(state.note).toBe('V4-132');
    expect(stepMessage(state)).toBeNull(); // the page renders the empty, not a sentence
  });

  test('reset returns the machine to its start', () => {
    expect(next({ ...IDLE, step: 'landed', label: 'work' }, { kind: 'reset' })).toEqual(IDLE);
  });
});

describe('pending routes render one line, and its help says what to do instead', () => {
  const heads = [{ head: 'claudex', kind: 'chatgpt-oauth', present: true, masked: 'acct…9f2', note: null }];

  test('pools the daemon does not serve say so once, and the heads\' own logins still show', () => {
    const out = render(h(AccountsBoard, { payload: { pending: 'V4-132' }, headRows: heads, nowMs: NOW }));
    expect(out).toContain(`>${S.poolsUnavailable}<`);
    expect(out).toContain(H.poolsUnavailable);
    expect(out).toContain('acct…9f2');
    expect(out).not.toContain('V4-132');
  });

  test('the login empty says what to do instead, never a row id', () => {
    const out = render(h(Empty, LOGIN_PENDING_EMPTY));
    expect(out).toContain(`>${LOGIN_PENDING_EMPTY.text}<`);
    expect(out).toContain('splice login');
    expect(out).not.toContain('V4-132');
  });

  test('no accounts is one line whose help says how to add one, never the route it read', () => {
    const out = render(h(AccountsBoard, { payload: { accounts: [] }, nowMs: NOW }));
    expect(out).toContain(`>${S.noAccounts}<`);
    expect(H.noAccounts).toContain('sign one in');
    expect(out).not.toContain('/api/');
    expect(out).not.toContain('myx-stat');
  });
});

/** One account as a row of the shared account table (widgets/account-table), the row both the
 *  accounts page and a fleet head's pool print. */
function accountRow(one: AccountRow, fields: readonly string[] = ACCOUNT_FIELDS, pool: readonly AccountRow[] = [one]): string {
  return render(h(DataTable<AccountRow>, {
    columns: accountColumns({ fields, grouped: null, nowMs: NOW, accounts: pool }),
    rows: [one],
    rowKey: accountKey,
    label: 'Pool',
  }));
}

describe('what one account row prints', () => {
  test('the name, the slot and the absence of an unreported window all reach the row, never a zero', () => {
    const out = accountRow(account({ label: 'quiet', windows: [window5h(null)] }));
    expect(out).toContain('>quiet<');
    expect(out).toContain(`>${W.short}<`);
    expect(out).toContain(`>${ABSENT}<`);
    expect(out).not.toContain('>0%<');
    expect(out).toContain(`>${W.stateName.unknown}<`);
  });

  test('every row has the same cells whatever windows it reports, so the columns line up', () => {
    // Walkthrough B3: a cell per reported window made a no-window row one cell short, and the
    // account name landed under "resets".
    const cells = (windows: AccountWindow[]) => (accountRow(account({ label: 'x', windows })).match(/<td/g) ?? []).length;
    const none = cells([]);
    expect(cells([window5h(40)])).toBe(none);
    expect(cells([window5h(40), { seconds: DAY_7, used_percent: 10, reset_epoch_seconds: null }])).toBe(none);
    expect(cells([
      window5h(40),
      { seconds: DAY_7, used_percent: 10, reset_epoch_seconds: null, model: 'opus' },
      { seconds: DAY_7, used_percent: 70, reset_epoch_seconds: null, model: 'sonnet' },
    ])).toBe(none);
  });

  test('the long slot shows its fullest window under that window\'s own length', () => {
    const out = accountRow(account({ label: 'g', windows: [
      { seconds: DAY_7, used_percent: 10, reset_epoch_seconds: null, model: 'opus' },
      { seconds: DAY_7, used_percent: 70, reset_epoch_seconds: null, model: 'sonnet' },
    ] }));
    expect(out).toContain('sonnet 7d');
    expect(out).toContain('>70%<');
  });

  test('the next target prints the rule that chose it, and no other row prints one', () => {
    const primary = account({ label: 'main', primary: true, next_target: true });
    const other = account({ label: 'work' });
    expect(accountRow(primary, ['next'], [primary, other])).toContain(`>${W.ruleName.primary}<`);
    expect(accountRow(other, ['next'], [primary, other])).not.toContain('myx-badge-accent');
  });

  test('an excluded account prints its state on the row and its reason when opened', () => {
    const gone = account({ label: 'gone', available: false, auth_exclusion_reason: 'cooling down' });
    expect(accountRow(gone)).toContain(`>${W.stateName.excluded}<`);
    expect(render(h(AccountFacts, { account: gone, nowMs: NOW }))).toContain('cooling down');
  });
});

describe('the saved views', () => {
  const byProvider: View = { id: 'p', name: 'by provider', layout: 'bay', filter: {}, sort: null, group: 'provider', fields: [] };
  const byHead: View = { id: 'h', name: 'by head', layout: 'bay', filter: {}, sort: null, group: 'head', fields: [] };
  const nearest: View = { id: 'n', name: 'nearest exhaustion', layout: 'bay', filter: {}, sort: { field: 'exhaustion', dir: 'desc' }, group: null, fields: [] };

  test('by provider makes one bay per kind', () => {
    const groups = arrangeAccounts([
      account({ kind: 'grok-oauth', label: 'g' }),
      account({ kind: 'chatgpt-oauth', label: 'c' }),
    ], byProvider, NOW);
    expect(groups.map((group) => group.key)).toEqual(['chatgpt-oauth', 'grok-oauth']);
  });

  test('by head puts one login in every bay that rides it, rather than hiding it from one', () => {
    const groups = arrangeAccounts([account({ label: 'shared', heads: ['a', 'b'] })], byHead, NOW);
    expect(groups.map((group) => group.key)).toEqual(['a', 'b']);
    expect(groups.every((group) => group.accounts[0]?.label === 'shared')).toBe(true);
  });

  test('an account no head rides still gets a bay', () => {
    const groups = arrangeAccounts([account({ label: 'loose', heads: [] })], byHead, NOW);
    expect(groups).toHaveLength(1);
    expect(groups[0]?.accounts[0]?.label).toBe('loose');
  });

  test('nearest exhaustion orders by the reported figure, and the unknown goes LAST', () => {
    const groups = arrangeAccounts([
      account({ label: 'unknown', windows: [window5h(null)] }),
      account({ label: 'mild', windows: [window5h(20)] }),
      account({ label: 'spent', windows: [window5h(95)] }),
    ], nearest, NOW);
    // Not first. FEATURES 4.5: unknown shown as unknown, never sorted first — a list that leads
    // with what nobody measured trains the eye away from the account that is about to fail.
    expect(groups[0]?.accounts.map((row) => row.label)).toEqual(['spent', 'mild', 'unknown']);
  });

  test('a view that names no fields shows every column rather than none', () => {
    expect(columnsOf(byProvider)).toEqual(['provider', 'account', 'plan', 'heads', 'next']);
  });

  test('a view that names fields shows those', () => {
    expect(columnsOf({ ...byProvider, fields: ['account'] })).toEqual(['account']);
  });
});

describe('the capture fixture', () => {
  test('is unreachable outside dev, however it is asked for', () => {
    expect(fixtureName('?fixture=demo', false)).toBeNull();
  });

  test('is picked up from the address in dev', () => {
    expect(fixtureName('?fixture=demo', true)).toBe('demo');
  });

  test('an empty or absent name is not a fixture', () => {
    expect(fixtureName('', true)).toBeNull();
    expect(fixtureName('?fixture=', true)).toBeNull();
    expect(fixtureName('?fixture=%20', true)).toBeNull();
  });
});

describe('the coverage manifest', () => {
  test('takes over exactly the seven routes the baseline held for this row', () => {
    expect(dispositions.map((entry) => entry.name).sort()).toEqual([
      '/api/accounts',
      '/api/auth',
      '/api/auth/{head}/login',
      '/api/auth/{head}/login/{id}',
      '/api/auth/{head}/refresh',
      '/api/auth/{head}/switch',
      '/api/auth/{kind}/accounts/{label}',
    ]);
  });

  // `none is left pending` was here until 2026-09-18 and it pinned a claim that was false when it
  // was written: /api/accounts was disposed `read-only`, and gateway control serves no
  // /api/accounts of any kind (M1-37's wire-check, grepped: 0 literal occurrences). The test held
  // green for as long as the manifest and the test agreed with each other and neither asked the
  // daemon — two hand-authored lists checking each other, which is the defect the wire-check was
  // built to break.
  //
  // So the assertion is now the PROPERTY that survives a route landing: pending is legitimate, and
  // what must never happen is a pending entry with no row to point at. Pinning the pending SET
  // would put this test back in the business of going red every time the daemon ships a route,
  // which is how a wall gets edited to match reality instead of the other way round.
  test('every entry is a route, and every pending one names the row that will land it', () => {
    // The denominator first. Without this line both assertions below are VACUOUS on an empty list —
    // `[].every(...)` is true and a for-loop over `[]` never runs its body — so emptying the
    // manifest entirely leaves this test a green tick at 0ms. Measured, not reasoned: it passes on
    // an emptied array while the two siblings in this file go red, which is the only reason the
    // file stays covered. Found by code-reviewer the same night law 23 was being enforced
    // elsewhere, in a wall I had just relaxed from pinning a set to asserting a property — the
    // relaxation was right and it removed the thing that was implicitly counting.
    expect(dispositions.length).toBeGreaterThan(0);
    expect(dispositions.every((entry) => entry.kind === 'route')).toBe(true);
    for (const entry of dispositions.filter((e) => e.disposition === 'pending')) {
      expect(entry.where, `${entry.name} is pending and names no row`).toMatch(/^V4-\d+$/);
    }
  });

  test('reading and writing are told apart', () => {
    const byName = new Map(dispositions.map((entry) => [entry.name, entry.disposition]));
    // /api/accounts was `pending V4-132` while the route did not exist. It is served now
    // (ControlServer.kt:333, AccountsRoute) and read by this page and by the fleet's head detail
    // (M4-02), so it earns the `read-only` the note above said it would. The two auth routes below
    // are the other half of the point: the manifest tells a route the page reads from one it
    // writes through.
    expect(byName.get('/api/accounts')).toBe('read-only');
    expect(byName.get('/api/auth')).toBe('read-only');
    expect(byName.get('/api/auth/{head}/switch')).toBe('editable');
  });
});

describe('an account action that answered but did not happen says why', () => {
  test('the daemon reason is read from a refresh, a switch and an edit, and a success is null', () => {
    // Walkthrough S6: every action answered with nothing, so a refused one read as done.
    expect(refusalOf({ ok: false, note: 'refresh token revoked' })).toBe('refresh token revoked');
    expect(refusalOf({ action: 'switch', result: { ok: false, error: "unknown account label 'x'" } })).toBe("unknown account label 'x'");
    expect(refusalOf({ ok: false })).toBe(LOGIN.refused);
    expect(refusalOf({ ok: true })).toBeNull();
    expect(refusalOf({ action: 'switch', result: { ok: true } })).toBeNull();
    expect(refusalOf(undefined)).toBeNull();
  });
});

describe('the api-key heads have a table of their own', () => {
  test('each prints its variable and masked key, a missing key is a warn badge, and nothing prints the key itself', () => {
    const out = render(h(AccountsBoard, {
      payload: { accounts: [] },
      nowMs: NOW,
      headRows: [
        { head: 'openrouter', kind: 'api-key', present: true, masked: null, note: null, envVar: 'OPENROUTER_API_KEY', keyMasked: 'sk-o…ddfb' },
        { head: 'claude-deepseek', kind: 'api-key', present: false, masked: null, note: null, envVar: 'DEEPSEEK_API_KEY' },
      ],
    }));
    const keys = tableOf(out, S.apiKeys);
    expect(keys.names).toEqual([S.head, S.variable, S.key, S.state]);
    expect(keys.rows).toHaveLength(2);
    expect(out).toContain('OPENROUTER_API_KEY');
    expect(out).toContain('sk-o…ddfb');
    expect(out).toContain('DEEPSEEK_API_KEY');
    expect(keys.rows.find((row) => row.includes('DEEPSEEK_API_KEY'))).toMatch(new RegExp(`myx-badge-warn[\\s\\S]*>${S.keyMissing}<`));
    expect((out.match(/myx-dt-tone-warn/g) ?? []).length).toBe(1);
  });

  test('an opened api-key head gives the command that stores its key, and says the next request uses it', () => {
    const row = { head: 'claude-deepseek', kind: 'api-key', present: false, masked: null, note: null, envVar: 'DEEPSEEK_API_KEY' };
    const out = render(h(ApiKeyDetail, { row }));
    expect(out).toContain('splice key set DEEPSEEK_API_KEY');
    expect(keyHelp(row)).toBe(H.keyStore);
    expect(out).toContain(H.keyStore);
  });

  test('the help follows the order the daemon reads a key in: variable, key_file, then the store', () => {
    const base = { head: 'claude-or', kind: 'api-key', masked: null, note: null, envVar: 'OR_KEY' };
    // a stored key is what a set replaces, and an exported variable still wins over it
    expect(keyHelp({ ...base, present: true })).toBe(H.keyReplace);
    expect(H.keyReplace).toContain('exported variable still wins');
    // a key_file is read before the store, so the head is sent to the file and offered no command
    const filed = { ...base, present: true, keyFile: '/keys/or.txt' };
    expect(keyHelp(filed)).toBe(H.keyFile);
    expect(keyCommand(filed)).toBeNull();
    expect(render(h(ApiKeyDetail, { row: filed }))).not.toContain('splice key set');
    expect(render(h(ApiKeyDetail, { row: filed }))).toContain('/keys/or.txt');
    // with the variable and the file both empty, the store is what the daemon reads next
    const empty = { ...base, present: false, keyFile: '/keys/or.txt' };
    expect(keyHelp(empty)).toBe(H.keyStore);
    expect(render(h(ApiKeyDetail, { row: empty }))).toContain('splice key set OR_KEY');
  });
});

describe('an account\'s state', () => {
  test('is decided by its nearest window, at the same thresholds the strip used', () => {
    expect(stateOf(account({ windows: [window5h(100)] }), NOW)).toBe('spent');
    expect(stateOf(account({ windows: [window5h(COCK_AT_PERCENT)] }), NOW)).toBe('warn');
    expect(stateOf(account({ windows: [window5h(COCK_AT_PERCENT - 1)] }), NOW)).toBe('ok');
    expect(stateOf(account({ windows: [window5h(null)] }), NOW)).toBe('unknown');
    expect(stateOf(account({ windows: [] }), NOW)).toBe('unknown');
    expect([usedTone(100), usedTone(COCK_AT_PERCENT), usedTone(10)]).toEqual(['danger', 'warn', 'ok']);
  });

  test('an excluded account is excluded even with a nearly spent window, and unknown is never green', () => {
    expect(stateOf(account({ available: false, windows: [window5h(99)] }), NOW)).toBe('excluded');
    expect(TONE.unknown).not.toBe('ok');
    expect(TONE.excluded).not.toBe('ok');
  });

  test('a window reads as its share and its reset, stale when its reset has passed, and nothing when unreported', () => {
    const inTwoHours = { seconds: HOUR_5, used_percent: 40, reset_epoch_seconds: NOW / 1000 + 7200 };
    expect(windowFigure(inTwoHours, NOW)).toEqual({ kind: 'used', percent: 40, resets: countdown(NOW / 1000 + 7200, NOW) });
    expect(windowFigure({ ...inTwoHours, reset_epoch_seconds: NOW / 1000 - 1 }, NOW)).toEqual({ kind: 'stale' });
    expect(windowFigure(window5h(null), NOW)).toEqual({ kind: 'none' });
    expect(windowFigure(null, NOW)).toEqual({ kind: 'none' });
  });

  test('a countdown past two days reads in days and hours', () => {
    expect(countdown(NOW / 1000 + 4 * 86_400 + 15 * 3600 + 60, NOW)).toBe('4d 15h');
    expect(countdown(NOW / 1000 - 5, NOW)).toBeNull();
    expect(countdown(null, NOW)).toBeNull();
  });
});

describe('the accounts table', () => {
  const pool = [
    account({ label: 'spent', windows: [window5h(100)], heads: ['claudex'] }),
    account({ label: 'near', windows: [window5h(95), { seconds: DAY_7, used_percent: 30, reset_epoch_seconds: null }] }),
    account({ label: 'room', windows: [window5h(10)] }),
    account({ label: 'quiet', windows: [] }),
    account({ kind: 'grok-oauth', label: 'grok', windows: [{ seconds: DAY_30, used_percent: 42, reset_epoch_seconds: null }] }),
  ];
  const out = render(h(AccountsBoard, { payload: { accounts: pool }, nowMs: NOW }));
  const accounts = tableOf(out, S.accounts);

  test('names its columns once, with one cell per column in every row, grouped by provider by default', () => {
    expect(accounts.names).toEqual([W.account, W.plan, W.state, W.short, W.long, W.heads, W.next]);
    expect(accounts.rows).toHaveLength(pool.length);
    for (const cells of accounts.cells) expect(cells).toHaveLength(accounts.names.length);
  });

  test('each window is a meter with its share, and an account that reports none draws no meter', () => {
    const near = accounts.rows.find((row) => row.includes('>near<')) ?? '';
    expect((near.match(/role="meter"/g) ?? []).length).toBe(2);
    expect(near).toContain('>95%<');
    const quiet = accounts.rows.find((row) => row.includes('>quiet<')) ?? '';
    expect(quiet).not.toContain('role="meter"');
    expect(quiet).toContain(`>${ABSENT}<`);
    expect(quiet).not.toContain('>0%<');
  });

  test('a long window names its own length where it is not the slot\'s', () => {
    const grok = accounts.rows.find((row) => row.includes('>grok<')) ?? '';
    expect(grok).toContain('>42%<');
    expect(grok).toContain('30d');
  });

  test('a spent account takes the danger tint and a near one the warn tint', () => {
    expect((out.match(/myx-dt-tone-danger/g) ?? []).length).toBe(1);
    expect((out.match(/myx-dt-tone-warn/g) ?? []).length).toBe(1);
  });

  test('the nearest limit is the fullest account that can serve a turn, not the spent one beside it', () => {
    // `spent` sits at 100% and the pool has stepped past it; `near` at 95% is the limit ahead.
    expect(statOf(out, S.nearestLimit)).toEqual({ value: '95%', sub: 'near 5h' });
    expect(out).toContain('myx-stat-warn');
    // with nothing left that can serve, the fullest of the rest is the limit reached
    const allSpent = render(h(AccountsBoard, { payload: { accounts: [pool[0] as AccountRow] }, nowMs: NOW }));
    expect(statOf(allSpent, S.nearestLimit)?.value).toBe('100%');
    expect(allSpent).toContain('myx-stat-danger');
  });
});

describe('the next reset', () => {
  const at = (seconds: number) => NOW / 1000 + seconds;
  // the review's capture: work's five-hour window resets in 41m 24s, its weekly one in 3d 10h
  const pool = [
    account({ label: 'work', windows: [
      { seconds: HOUR_5, used_percent: 12, reset_epoch_seconds: at(41 * 60 + 24) },
      { seconds: DAY_7, used_percent: 64, reset_epoch_seconds: at(3 * 86_400 + 10 * 3600) },
    ] }),
    account({ label: 'primary', windows: [
      { seconds: HOUR_5, used_percent: 38, reset_epoch_seconds: at(2 * 3600 + 4 * 60) },
      { seconds: DAY_7, used_percent: 21, reset_epoch_seconds: at(5 * 86_400) },
    ] }),
  ];

  test('is the soonest reset of any window, a five-hour one ahead of the weekly one nearest its limit', () => {
    expect(nextReset(pool, NOW)).toBe(at(41 * 60 + 24));
    const out = render(h(AccountsBoard, { payload: { accounts: pool }, nowMs: NOW }));
    expect(statOf(out, S.nextReset)?.value).toBe(countdown(at(41 * 60 + 24), NOW));
    expect(statOf(out, S.nearestLimit)?.value).toBe('64%');
  });

  test('a reset already past is not the next one, and no reset ahead prints the absence', () => {
    const passed = account({ label: 'old', windows: [{ seconds: HOUR_5, used_percent: 50, reset_epoch_seconds: at(-60) }] });
    expect(nextReset([passed, ...pool], NOW)).toBe(at(41 * 60 + 24));
    expect(nextReset([passed], NOW)).toBeNull();
    expect(statOf(render(h(AccountsBoard, { payload: { accounts: [passed] }, nowMs: NOW })), S.nextReset)?.value).toBe(ABSENT);
  });
});

describe('a window read before its reset', () => {
  // muse on 2026-09-24: its seven-day window read 99%, read 6.5 days earlier, and reset 91 hours ago
  const reset = NOW / 1000 - 91 * 3600;
  const stale = { seconds: 7 * 86_400, used_percent: 99, reset_epoch_seconds: reset };
  const muse = account({ kind: 'muse-oauth', label: null, single_login: true, windows: [stale], observed_at_epoch_seconds: NOW / 1000 - 6.5 * 86_400 });

  test('is no figure at all: no warn edge, and the row reads stale in its slot', () => {
    expect(accountState(muse, NOW).label).toBe(NOT_REPORTED);
    expect(accountState(muse, NOW).edge).toBe('grey');
    const out = accountRow(muse, ['provider']);
    expect(out).not.toContain('99%');
    expect(out).toContain(`>${W.stale}<`);
    // the same window before its reset is the figure it was
    expect(accountState(muse, reset * 1000 - 1).label).toBe('warn 99%');
  });

  test('the page draws it as stale, its state is unknown, and its old share is nowhere', () => {
    const out = render(h(AccountsBoard, { payload: { accounts: [muse] }, nowMs: NOW }));
    expect(stateOf(muse, NOW)).toBe('unknown');
    expect(out).toContain(`>${W.stale}<`);
    expect(out).not.toContain('99%');
    expect(out).toContain(`>${W.stateName.unknown}<`);
  });

  test('ranks with the unknown, last in nearest exhaustion', () => {
    const nearest: View = { id: 'n', name: 'nearest exhaustion', layout: 'bay', filter: {}, sort: { field: 'exhaustion', dir: 'desc' }, group: null, fields: [] };
    const mild = account({ label: 'mild', windows: [{ seconds: 5 * 3600, used_percent: 20, reset_epoch_seconds: NOW / 1000 + 3600 }] });
    const order = arrangeAccounts([muse, mild], nearest, NOW)[0]?.accounts.map((row) => row.label);
    expect(order).toEqual(['mild', null]);
  });

  test('the opened account says when it was read, and which window reset since', () => {
    expect(readAgeText(muse, NOW)).toBe('windows read 6d ago; the 7d window has reset since, so its figure is unknown until the next reading');
    expect(readAgeText({ ...muse, windows: [] }, NOW)).toBe('windows read 6d ago');
    expect(readAgeText({ ...muse, observed_at_epoch_seconds: null }, NOW)).toBeNull();
  });
});

