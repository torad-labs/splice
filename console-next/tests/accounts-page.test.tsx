// NEW: the Claude group's own controls. A Claude command can hold many subscriptions (operator ruling, Oct 3), so
// the group offers the add every other provider's does, counts SUBSCRIPTIONS rather than login rows, and lets an
// added account be removed. The order control names the policy in force and gives a way back to the default.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import { keys } from '../src/api/queries';
import { budgetsKey } from '../src/api/usage';
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
  display_name: 'account-2', identity_verified: true, can_remove: true, can_rename: true, edit_target: { kind: 'pool', id: 'account-2' },
  credential_path: '/synthetic/claude-accounts/claude-splice/account-2/.credentials.json',
  primary: false, selected: false, available: true, pinned: false, next_target: false,
  credential_present: true, windows: [], heads: ['claude-splice'],
  account: { uuid: 'second-subscription', email: 'other@example.invalid' },
};

function page(rows: readonly AccountRow[], registry: readonly { key: string; label: string; family?: string | null }[] = [{ key: 'claude-splice', label: 'claude-splice' }]): string {
  const client = new QueryClient();
  // The read key is the entity key plus its path (api/queries.ts read()), so a fixture seeded on the bare key
  // leaves the page in its reading state and every assertion below passes for the wrong reason.
  client.setQueryData([...keys.accounts, '/api/accounts'], { accounts: rows });
  client.setQueryData([...keys.status, '/api/status'], { registry });
  client.setQueryData(budgetsKey, { budgets: [] });
  for (const head of new Set(rows.flatMap(row => row.heads))) {
    client.setQueryData(['account-order', head], { head, order: [], effective_order: rows.filter(row => row.heads.includes(head)).map(row => row.label ?? row.login_place?.id ?? 'primary') });
  }
  return renderToStaticMarkup(<QueryClientProvider client={client}><AccountsPage /></QueryClientProvider>);
}

function order(head: string, saved: readonly string[], effective: readonly string[], rows: readonly AccountRow[]): string {
  const client = new QueryClient();
  client.setQueryData(['account-order', head], { head, order: saved, effective_order: effective });
  return renderToStaticMarkup(
    <QueryClientProvider client={client}><FailoverOrder head={head} accounts={rows} /></QueryClientProvider>,
  );
}

test('Accounts names colliding native and pool logins from their display contract and shows only verified email', () => {
  const native = { ...claudeLogin('claude'), label: 'claude', display_name: 'Personal login', identity_verified: true, can_remove: true, can_rename: true, edit_target: { kind: 'native' as const, id: 'claude' } };
  const pool = { ...added, label: 'claude', display_name: 'Work login', identity_verified: false, account: { uuid: 'other', email: 'unverified@example.invalid' }, can_remove: true, can_rename: false, edit_target: { kind: 'pool' as const, id: 'claude' } };
  const html = page([native, pool]);
  expect(html).toContain('<h3>Personal login</h3>');
  expect(html).toContain('<h3>Work login</h3>');
  expect(html).toContain('synthetic@example.invalid');
  expect(html).not.toContain('unverified@example.invalid');
  expect(html.match(/>Remove<\/button>/g)).toHaveLength(2);
  expect(html.match(/>Rename<\/button>/g)).toHaveLength(1);
});

test('native cards mark only the login whose credential carried the newest matched request', () => {
  const card = (carrying_request: boolean | null | undefined): string => renderToStaticMarkup(<QueryClientProvider client={new QueryClient()}>
    <AccountCard account={{ ...claudeLogin('claude-splice'), ...(carrying_request === undefined ? {} : { carrying_request }) }} place="claude-splice" commandLabels={['Synthetic command']} colour="claude" now={now} />
  </QueryClientProvider>);
  expect(card(true)).toContain('The last Synthetic command request with a known login used this login.');
  expect(card(false)).toContain('The last Synthetic command request with a known login used another login.');
  expect(card(null)).toContain('The daemon has not identified a login for any Synthetic command request since it started.');
  expect(card(undefined)).toContain('The daemon has not reported which login carried this command’s requests.');
  for (const carrying of [true, false, null]) {
    expect(card(carrying)).not.toContain('matched');
    expect(card(carrying)).not.toContain('latest Synthetic command request');
  }
  expect(card(false)).not.toContain('known login used this login.');
  expect(card(null)).not.toContain('known login used this login.');
});

test('an unidentified Claude folder login states the missing identity while keeping its one sign-in remedy', () => {
  const card = (identity_verified: boolean) => renderToStaticMarkup(<QueryClientProvider client={new QueryClient()}>
    <AccountCard account={{ ...claudeLogin('claude-splice'), identity_verified, account: null, available: false,
      refusal: 'Access token expired. Sign in again on claude-splice in the console.' }} place="claude-splice" colour="claude" now={now} />
  </QueryClientProvider>);
  expect(card(false)).toContain('Login not identified yet.');
  expect(card(false)).toContain('Sign in again on claude-splice in the console.');
  expect(card(true)).not.toContain('Login not identified yet.');
});

test('native takeover status follows availability, not a credential file’s presence', () => {
  const card = (available: boolean | null, refusal?: string): string => renderToStaticMarkup(<QueryClientProvider client={new QueryClient()}>
    <AccountCard account={{ ...claudeLogin('claude-splice'), selector_key: 'native:claude-splice', available, ...(refusal === undefined ? {} : { refusal }) }} place="claude-splice" colour="claude" now={now} />
  </QueryClientProvider>);
  expect(card(true)).toContain('Can take over');
  expect(card(false, 'Sign-in expired. Sign in again on this login.')).toContain('Can’t take over: Sign-in expired. Sign in again on this login.');
  expect(card(false, 'Sign-in expired. Sign in again on this login.')).not.toContain('>Signed in<');
  expect(card(false, 'Sign-in expired. Sign in again on this login.')).not.toMatch(/disabled=""[^>]*>Sign in again/);
  expect(card(null)).toContain('Takeover status not reported');
});

describe('the Claude group', () => {
  test('offers the add every other provider has, because a command can hold many subscriptions', () => {
    expect(page([claudeLogin('claude'), claudeLogin('claude-splice')])).toContain('Sign in to another account');
  });

  test('counts subscriptions, so one account signed into both places is not two', () => {
    const html = page([claudeLogin('claude'), claudeLogin('claude-splice')]);

    expect(html).toContain('<span class="n">1</span>');
    expect(html).not.toContain('<span class="n">2</span>');
  });

  test('counts a second subscription as the second account', () => {
    expect(page([claudeLogin('claude'), claudeLogin('claude-splice'), added])).toContain('>2<');
  });
});

test('Accounts waits for command kinds rather than briefly claiming a local runtime has an API key', () => {
  const client = new QueryClient();
  client.setQueryData([...keys.accounts, '/api/accounts'], {
    accounts: [{ ...added, kind: 'api-key', heads: ['synthetic-local'] }],
  });
  const html = renderToStaticMarkup(<QueryClientProvider client={client}><AccountsPage /></QueryClientProvider>);
  expect(html).toContain('Reading provider accounts');
  expect(html).not.toContain('API key configured');
  expect(html).not.toContain('one login');
});

test('a failed Accounts read is not hidden by the still-pending command-kind read', () => {
  const client = new QueryClient({ defaultOptions: { queries: { retryOnMount: false } } });
  const queryKey = [...keys.accounts, '/api/accounts'];
  client.setQueryData(queryKey, { accounts: [] });
  const query = client.getQueryCache().find({ queryKey });
  if (query === undefined) throw new Error('seeded Accounts query must exist');
  query.setState({ data: undefined, status: 'error', fetchStatus: 'idle', error: new Error('Synthetic accounts read failed') });
  const html = renderToStaticMarkup(<QueryClientProvider client={client}><AccountsPage /></QueryClientProvider>);
  expect(html).toContain('Synthetic accounts read failed');
  expect(html).not.toContain('Reading provider accounts');
});

describe('the local runtime on Accounts', () => {
  test('the daemon family selects local-runtime wording without inventing a provider sign-in', () => {
    const rows = [{ ...added, kind: 'api-key' as const, heads: ['local-internal'] }];
    const registry = [{ key: 'local-internal', label: 'claude-synthetic-local', family: 'local' }];
    const local = page(rows, registry);
    expect(local).toContain('This command uses a runtime on this computer. There is no provider sign-in to change.');
    expect(local).not.toContain('not a browser login');
    const remote = page(rows, [{ ...registry[0], key: 'local-internal', label: 'claude-synthetic-remote', family: 'openai' }]);
    expect(remote).toContain('This command uses an API key, not a browser login.');
    expect(remote).not.toContain('a runtime on this computer');
  });
});

describe('command labels on Accounts', () => {
  const registry = [{ key: 'internal-primary', label: 'claude-synthetic' }, { key: 'internal-secondary', label: 'claude-backup' }];
  test('group headings name the commands rather than the provider or routing id', () => {
    const rows = [{ ...added, kind: 'api-key' as const, provider: 'openrouter', heads: registry.map(row => row.key) }];
    const html = page(rows, registry);
    expect(html).toMatch(/<h2>claude-synthetic · claude-backup/);
    expect(html).not.toMatch(/<h2>openrouter/);
  });
  test('an API key card names each wrapper command, never its internal routing key', () => {
    const html = page([{ ...added, kind: 'api-key', heads: registry.map(row => row.key) }], registry);
    expect(html).toContain('<h3>claude-synthetic · claude-backup</h3>');
    expect(html).toContain('<p>claude-synthetic · claude-backup</p>');
    expect(html).not.toContain('<h3>internal-primary');
  });
  test('login associations, failover headings and budget labels use the same visible command name', () => {
    const rows = [{ ...added, heads: ['internal-primary'] }, { ...added, label: 'reserve', heads: ['internal-primary'] }];
    const html = page(rows, registry);
    expect(html).toContain('<p>claude-synthetic</p>');
    expect(html).toContain('<h3>claude-synthetic · When an account reaches its limit</h3>');
    expect(html).toContain('<b>claude-synthetic daily budget</b>');
    expect(html).not.toContain('<p>internal-primary</p>');
    expect(html).not.toContain('<h3>internal-primary');
    expect(html).not.toContain('<b>internal-primary daily budget</b>');
  });
});

describe('removing an account', () => {
  test('an added account can be removed', () => {
    const html = renderToStaticMarkup(<QueryClientProvider client={new QueryClient()}>
      <AccountCard account={added} colour="claude" now={now} />
    </QueryClientProvider>);

    expect(html).toContain('Remove');
  });

  test("an older native reply cannot grant removal merely by carrying a label", () => {
    const html = renderToStaticMarkup(<QueryClientProvider client={new QueryClient()}>
      <AccountCard account={{ ...claudeLogin('claude'), label: 'claude-code' }} place="claude" colour="claude" now={now} />
    </QueryClientProvider>);

    expect(html).not.toContain('Remove');
  });

  test('a command primary login obeys a refused removal capability', () => {
    const allowed = renderToStaticMarkup(<QueryClientProvider client={new QueryClient()}>
      <AccountCard account={{ ...added, primary: true }} colour="claude" now={now} />
    </QueryClientProvider>);
    expect(allowed).toContain('Remove');
    const html = renderToStaticMarkup(<QueryClientProvider client={new QueryClient()}>
      <AccountCard account={{ ...added, primary: true, can_remove: false }} colour="claude" now={now} />
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
