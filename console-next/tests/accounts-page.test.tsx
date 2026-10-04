// NEW: the Claude group's own controls. A Claude command can hold many subscriptions (operator ruling, Oct 3), so
// the group offers the add every other provider's does, counts SUBSCRIPTIONS rather than login rows, and lets an
// added account be removed. The order control names the policy in force and gives a way back to the default.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import { keys } from '../src/api/queries';
import { AccountCard } from '../src/pages/accounts/AccountCard';
import { AccountsPage } from '../src/pages/accounts/AccountsPage';
import { FailoverOrder } from '../src/pages/accounts/FailoverOrder';
import type { AccountRow } from '../src/types/accounts';

const now = Date.parse('2026-10-04T06:00:00Z');
const SUBSCRIPTION = { uuid: 'one-subscription', email: 'synthetic@example.invalid' };

const claudeLogin = (place: 'claude' | 'claude-splice'): AccountRow => ({
  kind: 'client', label: null, single_login: false, credential_path: `/synthetic/${place}/.credentials.json`,
  primary: true, selected: false, available: true, pinned: false, next_target: false,
  credential_present: true, windows: [], heads: ['claude-splice'],
  login_place: { id: place, command: place }, account: SUBSCRIPTION,
});

const added: AccountRow = {
  kind: 'claude-account', label: 'account-2', single_login: false,
  credential_path: '/synthetic/claude-accounts/claude-splice/account-2/.credentials.json',
  primary: false, selected: false, available: true, pinned: false, next_target: false,
  credential_present: true, windows: [], heads: ['claude-splice'],
  account: { uuid: 'second-subscription', email: 'other@example.invalid' },
};

function page(rows: readonly AccountRow[]): string {
  const client = new QueryClient();
  // The read key is the entity key plus its path (api/queries.ts read()), so a fixture seeded on the bare key
  // leaves the page in its reading state and every assertion below passes for the wrong reason.
  client.setQueryData([...keys.accounts, '/api/accounts'], { accounts: rows });
  client.setQueryData([...keys.status, '/api/status'], { registry: [{ key: 'claude-splice' }] });
  return renderToStaticMarkup(<QueryClientProvider client={client}><AccountsPage /></QueryClientProvider>);
}

function order(head: string, saved: readonly string[], effective: readonly string[], rows: readonly AccountRow[]): string {
  const client = new QueryClient();
  client.setQueryData(['account-order', head], { head, order: saved, effective_order: effective });
  return renderToStaticMarkup(
    <QueryClientProvider client={client}><FailoverOrder head={head} accounts={rows} /></QueryClientProvider>,
  );
}

describe('the Claude group', () => {
  test('offers the add every other provider has, because a command can hold many subscriptions', () => {
    expect(page([claudeLogin('claude'), claudeLogin('claude-splice')])).toContain('Sign in to another account');
  });

  test('counts subscriptions, so one account signed into both places is not two', () => {
    const html = page([claudeLogin('claude'), claudeLogin('claude-splice')]);

    expect(html).toContain('>1<');
    expect(html).not.toContain('>2<');
  });

  test('counts a second subscription as the second account', () => {
    expect(page([claudeLogin('claude'), claudeLogin('claude-splice'), added])).toContain('>2<');
  });
});

describe('removing an account', () => {
  test('an added account can be removed', () => {
    const html = renderToStaticMarkup(<QueryClientProvider client={new QueryClient()}>
      <AccountCard account={added} colour="claude" now={now} />
    </QueryClientProvider>);

    expect(html).toContain('Remove');
  });

  test("the caller's own Claude Code login has no remove, because splice never held it", () => {
    // The fixture carries a label on purpose. A place row with no label is excluded by the label check alone, so a
    // test using one would pass with the place guard deleted, and that guard is what stands between a click and
    // the person's real Claude Code login.
    const html = renderToStaticMarkup(<QueryClientProvider client={new QueryClient()}>
      <AccountCard account={{ ...claudeLogin('claude'), label: 'claude-code' }} place="claude" colour="claude" now={now} />
    </QueryClientProvider>);

    expect(html).not.toContain('Remove');
  });

  test('a command primary login has no remove either', () => {
    const html = renderToStaticMarkup(<QueryClientProvider client={new QueryClient()}>
      <AccountCard account={{ ...added, primary: true }} colour="claude" now={now} />
    </QueryClientProvider>);

    expect(html).not.toContain('Remove');
  });
});

describe('the failover order', () => {
  const rows = [claudeLogin('claude-splice'), added];

  test('names the default policy while no order is saved, and offers no way back to it', () => {
    const html = order('claude-splice', [], ['claude-splice', 'account-2'], rows);

    expect(html).toContain('No order is saved');
    expect(html).toContain('skipping accounts at their limit');
    expect(html).not.toContain('Use the default order');
  });

  test('says the order is yours once one is saved, with the way back', () => {
    const html = order('claude-splice', ['account-2', 'claude-splice'], ['account-2', 'claude-splice'], rows);

    expect(html).toContain('You set this order');
    expect(html).toContain('Use the default order');
    expect(html).not.toContain('No order is saved');
  });
});
