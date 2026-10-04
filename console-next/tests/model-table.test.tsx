// V4-444: the Models page is one table of every model, with its price per million input and output tokens, its context window,
// and the command and account that serve it, searched and sorted in place. The persona walk of 36218a37c found the prices behind
// each command's second tab, with "No price declared" and "$0.000" side by side: a price nobody declared never reads as a number.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { describe, expect, test } from 'vitest';
import { keys } from '../src/api/queries';
import { modelsKey } from '../src/api/models';
import { modelRows, priceText, searchRows, sortRows, sortedBy, tableSearchOf, tableViewOf } from '../src/lib/model-table';
import type { ModelTableRow } from '../src/lib/model-table';
import { ModelTable } from '../src/pages/models/ModelTable';
import type { AccountRow } from '../src/types/accounts';
import type { HeadStatus } from '../src/types/core';
import type { CatalogModel, HeadCatalog } from '../src/types/models';

const head = (key: string, authKind: string, label = key): HeadStatus => ({
  key, label, name: key, port: 0, authKind, wantVersion: '', running: true, healthy: true, version: null, versionMatch: null,
  mode: null, gate: null, maxInflight: null, health: {} as HeadStatus['health'], pids: [],
});

const model = (id: string, over: Partial<CatalogModel> = {}): CatalogModel => ({
  id, label: id.toUpperCase(), description: '', slot: null, context_window: 200_000, context_window_source: 'exact', pinned: false, resolved: true, ...over,
});

const login = (headKey: string, over: Partial<AccountRow> = {}): AccountRow => ({
  kind: 'chatgpt-oauth', label: null, single_login: true, credential_path: null, primary: true, selected: null, available: null,
  pinned: null, next_target: null, credential_present: true, windows: [], heads: [headKey], ...over,
} as AccountRow);

const CATALOGS: HeadCatalog[] = [
  { head: 'claudex', provider: 'codex', pinned_model: 'gpt-6-sol', models: [model('gpt-6-sol', { pinned: true, context_window: 400_000 }), model('gpt-6-luna', { context_window: 272_000 })] },
  { head: 'openrouter', provider: 'openrouter', pinned_model: '', models: [
    model('free/one', { rates: { input: 0, output: 0, cache_read: 0 } }),
    model('pro/two', { context_window: 1_050_000, rates: { input: 2, output: 10, cache_read: 0.1, long_context: { over_input_tokens: 272_000, input: 4, output: 15 } } as NonNullable<CatalogModel['rates']> }),
    model('cheap/three', { rates: { input: 0.15, output: 0.6, cache_read: 0.003 } }),
  ] },
  { head: 'bonsai', provider: 'bonsai', pinned_model: 'bonsai-2', models: [model('bonsai-2', { context_window: null, resolved: false })] },
];
const HEADS = [head('claudex', 'chatgpt-oauth'), head('openrouter', 'api-key', 'claudeor'), head('bonsai', 'api-key')];
const FAMILIES = new Map([['bonsai', 'local']]);
const rows = (accounts: AccountRow[] = [login('claudex', { account: { uuid: 'u', email: 'ava@x.io' } })]): ModelTableRow[] =>
  modelRows(CATALOGS, HEADS, accounts, FAMILIES);
const ids = (list: readonly ModelTableRow[]): string[] => list.map((row) => row.id);

describe('the rows', () => {
  test('one row per command and model, named by the command a person types, with who pays for it', () => {
    const all = rows();
    expect(ids(all)).toEqual(['gpt-6-sol', 'gpt-6-luna', 'free/one', 'pro/two', 'cheap/three', 'bonsai-2']);
    expect(all.map((row) => row.command)).toEqual(['claudex', 'claudex', 'claudeor', 'claudeor', 'claudeor', 'bonsai']);
    expect(all[0]?.servedBy).toEqual({ kind: 'login', name: 'ava@x.io', plan: null, others: 0 });
    expect(all[2]?.servedBy).toEqual({ kind: 'key' });
    expect(all[5]?.servedBy).toEqual({ kind: 'local' });
  });

  test('a pool names the login it selected and counts the rest; an unnamed login keeps its plan; none, or one signed out, says so', () => {
    const pool = [login('claudex', { label: 'work', selected: false }), login('claudex', { label: 'home', selected: true }), login('claudex', { label: 'spare', selected: false })];
    expect(rows(pool)[0]?.servedBy).toEqual({ kind: 'login', name: 'home', plan: null, others: 2 });
    expect(rows([login('claudex', { plan: 'pro' })])[0]?.servedBy).toEqual({ kind: 'login', name: null, plan: 'pro', others: 0 });
    expect(rows([login('claudex', { credential_present: false })])[0]?.servedBy).toEqual({ kind: 'signedOut' });
    expect(rows([])[0]?.servedBy).toEqual({ kind: 'unreported' });
  });

  test('two native places name their one proved account without a selected flag', () => {
    const native = login('claudex', { kind: 'client', label: 'Native place', account: { uuid: 'synthetic-subscription', email: 'proved@example.invalid' } });
    const wrapped = { ...native, label: 'Separate place' };
    expect(rows([native, wrapped])[0]?.servedBy).toEqual({ kind: 'login', name: 'proved@example.invalid', plan: null, others: 0 });
    expect(rows([native, { ...wrapped, account: { uuid: 'other-subscription', email: 'proved@example.invalid' } }])[0]?.servedBy).toEqual({ kind: 'unreported' });
    expect(rows([{ ...native, account: null }, { ...wrapped, account: null }])[0]?.servedBy).toEqual({ kind: 'unreported' });
  });

  test('an undeclared price stays null, a declared zero is zero, and a long-context tier is kept', () => {
    const [sol, , free, pro] = rows();
    expect([sol?.input, sol?.output]).toEqual([null, null]);
    expect([free?.input, free?.output]).toEqual([0, 0]);
    expect(pro?.longContext).toEqual({ over: 272_000, input: 4, output: 15 });
  });

  test('a price reads in cents, two figures under a cent, and a declared zero as $0.00', () => {
    expect([priceText(2), priceText(0.15), priceText(0.003), priceText(0.00045), priceText(0)]).toEqual(['$2.00', '$0.15', '$0.003', '$0.00045', '$0.00']);
  });
});

describe('search and sort', () => {
  test('every word must match the model, its id, its command or its account', () => {
    expect(ids(searchRows(rows(), 'claudeor PRO'))).toEqual(['pro/two']);
    expect(ids(searchRows(rows(), 'ava'))).toEqual(['gpt-6-sol', 'gpt-6-luna']);
    expect(ids(searchRows(rows(), '  '))).toHaveLength(6);
  });

  test('a price nobody declared sorts last in both directions, never as zero', () => {
    expect(ids(sortRows(rows(), 'input', 'asc'))).toEqual(['free/one', 'cheap/three', 'pro/two', 'gpt-6-sol', 'gpt-6-luna', 'bonsai-2']);
    expect(ids(sortRows(rows(), 'input', 'desc'))).toEqual(['pro/two', 'cheap/three', 'free/one', 'gpt-6-sol', 'gpt-6-luna', 'bonsai-2']);
    expect(ids(sortRows(rows(), 'window', 'desc')).at(-1)).toBe('bonsai-2');
  });

  test('command order is the catalogue\'s own, and descending reverses the commands, not the models in each', () => {
    expect(ids(sortRows(rows(), 'command', 'desc'))).toEqual(['bonsai-2', 'free/one', 'pro/two', 'cheap/three', 'gpt-6-sol', 'gpt-6-luna']);
  });

  test('the view lives in the address: a header flips its own direction, another starts ascending, defaults are left out', () => {
    const bare = tableViewOf(new URLSearchParams(''));
    expect(bare).toEqual({ query: '', sort: 'command', direction: 'asc' });
    expect(tableSearchOf(bare)).toEqual({});
    const input = sortedBy(bare, 'input');
    expect(sortedBy(input, 'input').direction).toBe('desc');
    expect(sortedBy(sortedBy(input, 'input'), 'window').direction).toBe('asc');
    const view = { query: 'gpt', sort: 'output', direction: 'desc' } as const;
    expect(tableViewOf(new URLSearchParams(tableSearchOf(view)))).toEqual(view);
    expect(tableViewOf(new URLSearchParams('sort=price')).sort).toBe('command');
  });
});

/** The table at an address, with the catalogue and the commands read. Its place on /models, above the command cards, is the
 *  e2e journey models-table.spec.ts: the page's saved card order reads a browser store this renderer does not have. */
function modelsPage(path = '/models'): string {
  const client = new QueryClient();
  client.setQueryData([...modelsKey], { heads: CATALOGS });
  client.setQueryData([...keys.heads, '/api/heads'], { heads: HEADS });
  return renderToStaticMarkup(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <ModelTable />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('the Models page', () => {
  test('opens on one table of every model, sortable by each column', () => {
    const html = modelsPage();
    expect(html).toContain('<table class="model-table">');
    for (const column of ['Model', 'Command', 'Account', 'Context window', 'Input', 'Output']) expect(html).toContain(`aria-label="Sort by ${column}"`);
    expect(html).toContain('aria-sort="ascending"');
    expect(html).toContain('6 models on 3 commands.');
  });

  test('a login with no name is said by its plan, capitalised as its command card prints it', () => {
    const client = new QueryClient();
    client.setQueryData([...modelsKey], { heads: CATALOGS });
    client.setQueryData([...keys.heads, '/api/heads'], { heads: HEADS });
    client.setQueryData([...keys.accounts, '/api/accounts'], { accounts: [{ ...login('claudex', { plan: 'pro' }), windows: [] }] });
    client.setQueryData([...keys.status, '/api/status'], { registry: [{ key: 'bonsai', family: 'local' }] });
    const html = renderToStaticMarkup(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/models']}><ModelTable /></MemoryRouter></QueryClientProvider>);
    expect(html).toContain('<td>Signed in, Pro plan</td>');
    expect(html).toContain('<td>API key</td>');
    expect(html).toContain('<td>This computer</td>');
  });

  test('says an undeclared price in words and a declared zero as $0.00, and never prints $0.000', () => {
    const html = modelsPage();
    expect(html).toContain('<span class="none">Not declared</span>');
    expect(html).toContain('$0.00<');
    expect(html).toContain('$4.00 above 272k');
    expect(html).not.toContain('$0.000');
  });

  test('a search in the address narrows the rows and says how many of the whole match', () => {
    const html = modelsPage('/models?q=claudeor');
    expect(html).toContain('3 of 6 models match.');
    expect(html).not.toContain('GPT-6-SOL');
    expect(modelsPage('/models?q=nothing-like-it')).toContain('No model matches &quot;nothing-like-it&quot;.');
  });
});
