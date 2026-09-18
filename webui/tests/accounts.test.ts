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
  resetText,
  windowUsedText,
} from '../src/entities/account';
import type { AccountRow, AccountWindow } from '../src/entities/account';
import { LOGIN_PENDING_EMPTY, IDLE, canStart, next, stepMessage } from '../src/features/account-login/model';
import { AccountStrip, windowFieldLabel } from '../src/widgets/account-strip';
import { EMPTIES, arrangeAccounts, columnsOf, fixtureName } from '../src/pages/accounts/model';
import { dispositions } from '../src/pages/accounts/coverage';
import { Empty } from '../src/shared/ui';
import type { View } from '../src/features/views';

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
    primary: false,
    selected: false,
    available: true,
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
    expect(windowFieldLabel({ seconds: DAY_30, used_percent: 10, reset_epoch_seconds: null })).toBe('30d');
    expect(windowFieldLabel({ seconds: HOUR_5, used_percent: 10, reset_epoch_seconds: null })).toBe('5h');
  });

  test('a model-scoped window says which model', () => {
    expect(windowFieldLabel({ seconds: DAY_7, used_percent: 10, reset_epoch_seconds: null, model: 'opus' }))
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
    expect(exclusionText(account({ available: false }))).toBe('excluded by the pool');
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
    expect(SELECTOR_ORDER_TEXT).toBe('primary then sticky then lowest 7-day used');
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
    expect(stepMessage(state)).toBe('code printed, finish in the browser');
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
    expect(stepMessage(state)).toBe('signed in, live after restart');
  });

  test('a landed login with no restart needed is simply added', () => {
    const state = next(
      next(IDLE, { kind: 'started', payload: { login_id: 'L1', head: 'claudex', label: 'work', flow: 'browser' } }),
      {
        kind: 'status',
        payload: { login_id: 'L1', head: 'claudex', label: 'work', state: 'landed', restart_required: false },
      },
    );
    expect(stepMessage(state)).toBe('account added');
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

describe('pending routes render an empty naming their row', () => {
  test('the pooled empty names V4-132 and says what is missing', () => {
    const out = render(h(Empty, EMPTIES.pooledPending));
    expect(out).toContain('pooled accounts not built');
    expect(out).toContain('V4-132');
  });

  test('the login empty names its row too', () => {
    const out = render(h(Empty, LOGIN_PENDING_EMPTY('V4-132')));
    expect(out).toContain('login not built');
    expect(out).toContain('V4-132');
  });

  test('the no-accounts empty names the source it looked in', () => {
    const out = render(h(Empty, EMPTIES.noAccounts));
    expect(out).toContain('no accounts pooled');
    expect(out).toContain('GET /api/accounts');
  });
});

describe('what one strip prints', () => {
  test('the edge label, the window length and the not-reported text all reach the page', () => {
    const out = render(h(AccountStrip, {
      account: account({ label: 'quiet', windows: [window5h(null)] }),
      isNext: false,
      nextRule: '',
      columns: ['provider', 'account'],
      nowMs: NOW,
    }));
    expect(out).toContain('quiet');
    expect(out).toContain('>5h<');
    expect(out).toContain(NOT_REPORTED);
    expect(out).toContain('not reported by provider'); // and it is PRINTED, not only labelled
  });

  test('the next target prints the rule that chose it', () => {
    const out = render(h(AccountStrip, {
      account: account({ label: 'primary', primary: true }),
      isNext: true,
      nextRule: 'primary',
      columns: ['next'],
      nowMs: NOW,
    }));
    expect(out).toContain('>primary<');
  });

  test('an excluded strip is aria-disabled and prints its reason', () => {
    const out = render(h(AccountStrip, {
      account: account({ label: 'gone', available: false, auth_exclusion_reason: 'cooling down' }),
      isNext: false,
      nextRule: '',
      columns: ['account'],
      nowMs: NOW,
    }));
    expect(out).toContain('aria-disabled="true"');
    expect(out).toContain('cooling down');
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
    ], byProvider);
    expect(groups.map((group) => group.key)).toEqual(['chatgpt-oauth', 'grok-oauth']);
  });

  test('by head puts one login in every bay that rides it, rather than hiding it from one', () => {
    const groups = arrangeAccounts([account({ label: 'shared', heads: ['a', 'b'] })], byHead);
    expect(groups.map((group) => group.key)).toEqual(['a', 'b']);
    expect(groups.every((group) => group.accounts[0]?.label === 'shared')).toBe(true);
  });

  test('an account no head rides still gets a bay', () => {
    const groups = arrangeAccounts([account({ label: 'loose', heads: [] })], byHead);
    expect(groups).toHaveLength(1);
    expect(groups[0]?.accounts[0]?.label).toBe('loose');
  });

  test('nearest exhaustion orders by the reported figure, and the unknown goes LAST', () => {
    const groups = arrangeAccounts([
      account({ label: 'unknown', windows: [window5h(null)] }),
      account({ label: 'mild', windows: [window5h(20)] }),
      account({ label: 'spent', windows: [window5h(95)] }),
    ], nearest);
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
    // /api/accounts is `pending V4-132`, not `read-only`: the route does not exist yet. The two
    // auth routes below DO exist and are the point of the test — that the manifest distinguishes
    // a route the page reads from one it writes through.
    expect(byName.get('/api/accounts')).toBe('pending');
    expect(byName.get('/api/auth')).toBe('read-only');
    expect(byName.get('/api/auth/{head}/switch')).toBe('editable');
  });
});
