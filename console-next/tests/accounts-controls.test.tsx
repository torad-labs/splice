import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { modelsKey } from '../src/api/models';
import type { HeadCatalog } from '../src/types/models';
import { expect, test, vi } from 'vitest';
import { budgetsKey } from '../src/api/usage';
import { AccountBudget } from '../src/pages/accounts/AccountBudget';
import { FailoverOrder } from '../src/pages/accounts/FailoverOrder';
import type { BudgetsPayload } from '../src/types/budget';
import type { AccountRow } from '../src/types/accounts';

const catalog = (priced: boolean): HeadCatalog => ({
  head: 'synthetic-command', provider: 'synthetic', pinned_model: 'synthetic-model', models: [{
    id: 'synthetic-model', label: 'Synthetic model', description: '', slot: null, context_window: 1000, context_window_source: 'synthetic', pinned: true, resolved: true,
    ...(priced ? { rates: { input: 1, cache_read: 0, output: 2 } } : {}),
  }],
});

function budget(data: BudgetsPayload, priced: boolean | null = true): string {
  const client = new QueryClient();
  client.setQueryData(budgetsKey, data);
  if (priced !== null) client.setQueryData(modelsKey, { heads: [catalog(priced)] });
  return renderToStaticMarkup(<QueryClientProvider client={client}><MemoryRouter><AccountBudget head="synthetic-command" /></MemoryRouter></QueryClientProvider>);
}

test('an unsupported account source shows the plain state, never daemon internals', () => {
  const client = new QueryClient();
  client.setQueryData(['account-order', 'synthetic-command'], { unavailable: "head 'synthetic-command' has no selectable account source" });
  const html = renderToStaticMarkup(<QueryClientProvider client={client}><FailoverOrder head="synthetic-command" accounts={[]} /></QueryClientProvider>);
  expect(html).toContain('Account ordering is unavailable for this command.');
  expect(html).not.toContain('selectable account source');
  expect(html).not.toContain('head');
});

test('one proven account in two login places explains why its order cannot change', () => {
  const client = new QueryClient();
  client.setQueryData(['account-order', 'synthetic-command'], {
    head: 'synthetic-command', order: [], effective_order: [], single_account: true,
  });
  const html = renderToStaticMarkup(<QueryClientProvider client={client}><FailoverOrder head="synthetic-command" accounts={[]} /></QueryClientProvider>);
  expect(html).toContain('This command uses one account. There is no account order to change.');
  expect(html).not.toContain('drag-handle');
});

test.each([
  [false, 'This command uses an API key. There is no account order to change.'],
  [true, 'This command uses a runtime on this computer. There are no provider accounts to order.'],
])('a key or local command does not claim a login or read a nonexistent order route: local=%s', (localRuntime, sentence) => {
  const accounts: AccountRow[] = [{
    kind: 'api-key', label: null, single_login: true, credential_path: null,
    credential_present: true, windows: [], heads: ['synthetic-command'],
    primary: false, selected: null, available: null, pinned: null, next_target: null,
  }];
  const html = renderToStaticMarkup(<QueryClientProvider client={new QueryClient()}>
    <FailoverOrder head="synthetic-command" accounts={accounts} localRuntime={localRuntime} />
  </QueryClientProvider>);
  expect(html).toContain(sentence);
  expect(html).not.toContain('login');
  expect(html).not.toContain('Reading failover');
  expect(html).not.toContain('drag-handle');
});

test('order controls show the shared display name and only verified email, not stable keys', () => {
  const common: AccountRow = { kind: 'chatgpt-oauth', label: 'stable-work', display_name: 'Work login', identity_verified: true, account: { uuid: 'synthetic-work', email: 'verified@example.invalid' }, single_login: false, credential_path: null, credential_present: true, windows: [], heads: ['synthetic-command'], primary: false, selected: false, available: true, pinned: false, next_target: false };
  const client = new QueryClient();
  client.setQueryData(['account-order', 'synthetic-command'], { head: 'synthetic-command', order: ['stable-work', 'stable-home'], effective_order: ['stable-work', 'stable-home'], single_account: false });
  const html = renderToStaticMarkup(<QueryClientProvider client={client}><FailoverOrder head="synthetic-command" accounts={[common, { ...common, label: 'stable-home', display_name: 'Home login', identity_verified: false, account: { uuid: 'synthetic-home', email: 'unverified@example.invalid' } }]} /></QueryClientProvider>);
  expect(html).toContain('Work login · verified@example.invalid');
  expect(html).toContain('Home login');
  expect(html).not.toContain('stable-work');
  expect(html).not.toContain('stable-home');
  expect(html).not.toContain('unverified@example.invalid');
});

test.each([false, true])('first-available wording describes only a saved account order: saved=%s', saved => {
  const client = new QueryClient();
  const order = ['synthetic-first', 'synthetic-second'];
  client.setQueryData(['account-order', 'synthetic-command'], {
    head: 'synthetic-command', order: saved ? order : [], effective_order: order, single_account: false,
  });
  const html = renderToStaticMarkup(<QueryClientProvider client={client}><FailoverOrder head="synthetic-command" accounts={[]} /></QueryClientProvider>);
  if (saved) {
    expect(html).toContain('Splice tries the first available account in this order');
    expect(html).toContain('You set this order.');
    expect(html).not.toContain('No order is saved.');
  } else {
    expect(html).toContain('No order is saved.');
    expect(html).toContain('Each session keeps its account while it remains available');
    expect(html).not.toContain('Splice tries the first available account in this order');
  }
});

test('native selector keys name the order while edit ids and visible names stay independent', () => {
  const rows: AccountRow[] = ['claude', 'claude-splice'].map(id => ({
    kind: 'client', label: id, selector_key: 'native:' + id, display_name: id === 'claude' ? 'Personal login' : 'Separate login',
    login_place: { id: id === 'claude' ? 'claude' : 'claude-splice', command: id },
    edit_target: { kind: 'native', id }, single_login: true, credential_path: null, credential_present: true,
    windows: [], heads: ['synthetic-command'], primary: false, selected: false, available: true, pinned: false, next_target: false,
  }));
  const client = new QueryClient();
  client.setQueryData(['account-order', 'synthetic-command'], {
    head: 'synthetic-command', order: ['native:claude-splice', 'native:claude'], effective_order: ['native:claude-splice', 'native:claude'],
  });
  const html = renderToStaticMarkup(<QueryClientProvider client={client}><FailoverOrder head="synthetic-command" accounts={rows} /></QueryClientProvider>);
  expect(html).toContain('Separate login');
  expect(html).toContain('Personal login');
  expect(html).not.toContain('native:claude');
  expect(html).toContain('You set this order.');
});

test('no cap explains how to set one without unknown used or remaining amounts', () => {
  const html = budget({ budgets: [] });
  expect(html).toContain('No daily cap is set.');
  expect(html).toContain('Set daily cap');
  expect(html).not.toContain('Budget used');
  expect(html).not.toContain('Left in budget');
});

test('a command without declared prices cannot promise its cap will count spending', () => {
  const html = budget({ budgets: [] }, false);
  expect(html).toContain('budget cannot count spending yet');
  expect(html).not.toContain('Set a daily cap to limit spending');
  expect(html).toContain('href="/usage?prices=synthetic-command"');
  expect(html).toContain('Prices for synthetic-command');
  expect(html).not.toContain('public prices');
});

test('the declared-price capability is not invented while its catalog is pending', () => {
  const html = budget({ budgets: [] }, null);
  expect(html).toContain('Reading the model prices');
  expect(html).not.toContain('Set a daily cap to limit spending');
  expect(html).not.toContain('has no declared token prices');
});

test('a cap without priced spend does not invent zero or a remaining balance', () => {
  const html = budget({ budgets: [{ head: 'synthetic-command', daily_usd: 10, action: 'warn', used_usd: null, remaining_usd: null }] });
  expect(html).toContain('$10');
  expect(html).toContain('Spending is not reported');
  expect(html).not.toContain('Budget used');
  expect(html).not.toContain('Left in budget');
  expect(html).not.toContain('$0');
});

test.each([
  ['America/Chicago', '2026-10-05T23:59:59.999Z', '2026-10-06T00:00:00Z'],
  ['America/Chicago', '2026-10-06T00:00:00Z', '2026-10-07T00:00:00Z'],
  ['America/New_York', '2026-03-08T05:00:00Z', '2026-03-09T00:00:00Z'],
  ['America/New_York', '2026-11-01T04:00:00Z', '2026-11-02T00:00:00Z'],
  ['Asia/Kathmandu', '2026-10-05T12:00:00Z', '2026-10-06T00:00:00Z'],
])('Accounts states the actual UTC budget reset in viewer zone %s at %s', (zone, at, reset) => {
  vi.stubEnv('TZ', zone);
  vi.useFakeTimers();
  vi.setSystemTime(new Date(at));
  try {
    const html = budget({ budgets: [{ head: 'synthetic-command', daily_usd: 10, action: 'warn', used_usd: 3, remaining_usd: 7 }] });
    const local = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', timeZoneName: 'short', timeZone: zone }).format(new Date(reset));
    expect(html).toContain(`Daily budgets reset at ${local}.`);
    expect(html).toContain('Budget used: $3');
    expect(html).toContain('Left in budget: $7');
  } finally {
    vi.useRealTimers();
    vi.unstubAllEnvs();
  }
});

test('a priced command shows the daemon measured budget values', () => {
  const html = budget({ budgets: [{ head: 'synthetic-command', daily_usd: 10, action: 'warn', used_usd: 3, remaining_usd: 7 }] });
  expect(html).toContain('Budget used: $3');
  expect(html).toContain('Left in budget: $7');
  expect(html).toContain('Shared by all accounts on this command.');
});
