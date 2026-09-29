// V4-410: a pool credential splice refuses (a symlinked <label>.json, V4-405) reaches the console as REFUSED,
// in the daemon's words, and never as a renewable account. Before this the row read credential_present false
// like an orphan (a quota whose credential is gone), so Needs you offered "Sign in again" for a label the
// writer refuses. The orphan keeps its renewal; the refused row shows the sentence and offers none.
//
// A .ts file holds no JSX (CONTRACTS.md section 4), so the elements are built with createElement.
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import { accountsFromWire } from '../src/entities/account';
import type { AccountRow, AccountWire } from '../src/entities/account';
import { FixCell, needsOf } from '../src/pages/needs-you';
import type { Need, NeedInputs, Read } from '../src/pages/needs-you';
import { H } from '../src/pages/needs-you/strings';
import { AccountFacts, AccountStateBadge, stateOf, stateParts } from '../src/widgets/account-table';

const NOW = 1_790_000_000_000;
const REASON = 'is a symbolic link, and splice does not load a linked credential';
const SENTENCE = `'linked' ${REASON}; remove the link and sign in again, or sign in under a different label`;

/** A row whose reader is present, so only the fields a test names differ. */
function account(over: Partial<AccountRow> = {}): AccountRow {
  return {
    kind: 'chatgpt-oauth', label: 'work', single_login: false, credential_path: null, plan: null, primary: false,
    selected: false, available: true, pinned: false, next_target: false, credential_present: true,
    auth_excluded_until_epoch_millis: null, auth_exclusion_reason: null, windows: [], heads: ['claudex'],
    ...over,
  };
}

const refused = (): AccountRow => account({ label: 'linked', credential_present: false, available: false, refusal: SENTENCE });
const orphan = (): AccountRow => account({ label: 'lost', credential_present: false, available: false });

function wireRow(over: Partial<AccountWire> = {}): AccountWire {
  return {
    credential_path: '/pool/linked.json.refused', kind: 'chatgpt-oauth', label: 'linked', primary: false, single_login: false,
    plan: null, five_hour_used_percent: null, five_hour_reset_epoch_seconds: null, five_hour_window_seconds: null,
    seven_day_used_percent: null, seven_day_reset_epoch_seconds: null, seven_day_window_seconds: null, available: false,
    credential_present: false, auth_excluded_until_epoch_millis: null, auth_exclusion_reason: null, selected: false,
    pinned: false, next_target: false, heads: ['claudex'], observed_at_epoch_seconds: null,
    five_hour_current: false, seven_day_current: false,
    ...over,
  };
}

const unread: Read<never> = { data: null, error: null, lastUpdated: null };

/** Only the accounts input is answered: the items under test come from it alone. */
function inputs(accounts: AccountRow[]): NeedInputs {
  return {
    heads: unread, auth: unread, accounts: { data: { accounts }, error: null, lastUpdated: NOW - 1_000 }, usage: unread,
    sessions: unread, teams: unread, doctor: unread, topology: unread, restartPending: [],
  };
}

const accountNeeds = (accounts: AccountRow[]) => needsOf(inputs(accounts), NOW).needs.filter((need) => need.source === 'accounts');

/** The account item for [subject], or a failure that names it. */
function needFor(subject: string, accounts: AccountRow[]): Need {
  const found = accountNeeds(accounts).find((need) => need.subject === subject);
  if (found === undefined) throw new Error(`no accounts item for ${subject}`);
  return found;
}

describe('the wire', () => {
  test('a refusal on the wire reaches the row, and a daemon without one reads as none', () => {
    const [carried] = accountsFromWire({ accounts: [wireRow({ refusal: SENTENCE })] }).accounts;
    const [older] = accountsFromWire({ accounts: [wireRow()] }).accounts;

    expect(carried?.refusal).toBe(SENTENCE);
    expect(older?.refusal).toBeNull();
  });
});

describe('the state', () => {
  test('a refused credential is refused, and a gone one is still signed out', () => {
    expect(stateOf(refused(), NOW)).toBe('refused');
    expect(stateOf(orphan(), NOW)).toBe('signedOut');
  });

  test('the refusal outranks the missing credential and any exclusion it carries too', () => {
    const excluded = account({
      ...refused(), auth_excluded_until_epoch_millis: NOW + 60_000, auth_exclusion_reason: 'cooling down',
    });

    expect(stateOf(excluded, NOW)).toBe('refused');
  });

  test('a blank refusal is no refusal', () => {
    expect(stateOf(account({ credential_present: false, refusal: '  ' }), NOW)).toBe('signedOut');
  });

  test('the badge says Refused, the facts print the sentence, and a healthy row prints neither', () => {
    const render = (element: ReturnType<typeof createElement>): string => renderToStaticMarkup(element);

    expect(render(createElement(AccountStateBadge, { account: refused(), nowMs: NOW }))).toContain('>Refused<');
    expect(render(createElement(AccountStateBadge, { account: orphan(), nowMs: NOW }))).toContain('>Signed out<');
    // The sentence's quotes are escaped in markup, so the assertion reads the clause after them.
    expect(render(createElement(AccountFacts, { account: refused(), nowMs: NOW }))).toContain(REASON);
    expect(render(createElement(AccountFacts, { account: account(), nowMs: NOW }))).not.toContain(REASON);
  });

  test('the bar counts refused and signed out apart', () => {
    const parts = stateParts([refused(), orphan(), account()], NOW);

    // The bare account reports no window, so it counts as unknown; the two credential states never merge.
    expect(parts.filter((part) => part.value > 0).map((part) => [part.key, part.value])).toEqual([
      ['unknown', 1], ['signedOut', 1], ['refused', 1],
    ]);
  });
});

describe('Needs you', () => {
  test('a refused account says why and opens Accounts, and a gone one still offers the renewal', () => {
    const gone = needFor('lost', [orphan(), refused()]);
    const linked = needFor('linked', [orphan(), refused()]);

    expect(gone).toMatchObject({ finding: H.accountSignedOut, fix: { kind: 'login', head: 'claudex', label: 'lost' } });
    expect(linked).toMatchObject({ finding: SENTENCE, fix: { kind: 'open', href: '#/accounts' } });
    expect(renderToStaticMarkup(createElement(FixCell, { fix: linked.fix }))).not.toContain('Sign in again');
    expect(renderToStaticMarkup(createElement(FixCell, { fix: gone.fix }))).toContain('Sign in again');
  });
});
