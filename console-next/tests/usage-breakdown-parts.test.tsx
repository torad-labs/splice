import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { expect, test } from 'vitest';
import { BudgetBalance } from '../src/pages/usage/Budgets';
import { CommandPricing } from '../src/pages/usage/UsagePricing';
import { UsageBreakdown, UsageValues, requestsFor } from '../src/pages/usage/UsageBreakdown';
import type { TurnRow, TurnsState, TurnUsageWire } from '../src/types/perf';

const complete: TurnUsageWire = {
  totals: { requests: 2502, input_tokens: 2501000, cached_tokens: 2250900, output_tokens: 250100, cost_usd: 1.47559, cache_share: 0.9, unpriced_requests: 1, missing_input_requests: 1, missing_output_requests: 1, missing_cache_requests: 0 },
  models: [{ key: 'earlier model', requests: 2502, input_tokens: 2501000, cached_tokens: 2250900, output_tokens: 250100, cost_usd: 1.47559, cache_share: 0.9, unpriced_requests: 1, missing_input_requests: 1, missing_output_requests: 1, missing_cache_requests: 0 }],
  accounts: [], days: [],
};
const row: TurnRow = { head: 'synthetic', ts: 100, model: 'model / a', account: 'work & spare', compact: false, outcome: 'ok', cost_usd: 0.25, in_tokens: 200, out_tokens: 50 };
const item = { id: 'synthetic', head: 'synthetic', key: row.model, turns: 1, cost: 0.25, input: 200, output: 50, unpriced: 0, missingInput: 0, missingOutput: 0 };
const items = [item];
const reading = (over: Partial<TurnsState> = {}) => ({
  isError: false, isPending: false,
  data: { landed: [row], inflight: [], unread: [], truncated: [], matched: 2502, matchedBy: { synthetic: 2502 }, usageBy: { synthetic: complete }, window: { since: 100, until: 200 }, ...over },
}) as unknown as Parameters<typeof UsageBreakdown>[0]['read'];

test('a capped request slice cannot shrink full-window model facts or claim aggregate truncation', () => {
  const read = reading({ truncated: [{ head: 'synthetic', count: 2502, returned: 1 }], completeFrom: 150 });
  const html = renderToStaticMarkup(<MemoryRouter><UsageBreakdown read={read} labelOf={key => key} /></MemoryRouter>);
  expect(html).toContain('earlier model');
  expect(html).toContain('2,502 requests');
  expect(html).toContain('At least $1.48');
  expect(html).not.toContain('This breakdown is incomplete');
  expect(html).not.toContain('requests returned');
});

test('an older daemon never substitutes its display slice for an aggregate', () => {
  const html = renderToStaticMarkup(<MemoryRouter><UsageBreakdown read={reading({ usageBy: {} })} labelOf={key => key} /></MemoryRouter>);
  expect(html).toContain('does not report full-window usage');
  expect(html).not.toContain('$0.25');
});

test('price declarations name a model actually used, not an unused pinned model', () => {
  const catalog = { head: 'synthetic', provider: 'synthetic', pinned_model: 'unused', models: [{ id: 'unused', label: 'Unused', description: '', slot: null, context_window: 1000, context_window_source: 'synthetic', pinned: true, resolved: true }] };
  const html = renderToStaticMarkup(<MemoryRouter><CommandPricing command="synthetic" label="Synthetic" catalog={catalog} unpriced={1} path={undefined} usedModels={['earlier model']} subscription="Pro" /></MemoryRouter>);
  expect(html).toContain('&quot;earlier model&quot; =');
  expect(html).not.toContain('&quot;unused&quot; =');
  expect(html).toContain('runs on its Pro plan');
  expect(html).toContain('equivalent API cost');
});

test('missing catalog prices have a reason and an exact supported command-specific declaration', () => {
  const catalog = { head: 'synthetic.command', provider: 'synthetic', pinned_model: 'model.1', models: [{ id: 'model.1', label: 'Model one', description: '', slot: null, context_window: 1000, context_window_source: 'synthetic', pinned: true, resolved: true }] };
  const html = renderToStaticMarkup(<MemoryRouter><CommandPricing command="synthetic.command" label="Synthetic command" catalog={catalog} usedModels={['model.1']} unpriced={2} path="/synthetic/splice.toml" /></MemoryRouter>);
  expect(html).toContain('No price is declared for: model.1');
  expect(html).toContain('[heads.&quot;synthetic.command&quot;.rates]');
  expect(html).toContain('&quot;model.1&quot; =');
  expect(html).toContain('INPUT_USD');
  expect(html).not.toContain('$0');
});

test('requests without counters never manufacture a dollar estimate', () => {
  const html = renderToStaticMarkup(<MemoryRouter><CommandPricing command="synthetic" label="Synthetic" catalog={{ head: 'synthetic', provider: 'synthetic', pinned_model: '', models: [] }} unpriced={3} path={undefined} /></MemoryRouter>);
  expect(html).toContain('no dollar estimate');
  expect(html).not.toContain('$0');
});

test('the breakdown links carry the captured window, not a later render clock', () => {
  const html = renderToStaticMarkup(<MemoryRouter><UsageBreakdown read={reading()} labelOf={key => key} /></MemoryRouter>);
  expect(html).toContain('since=100&amp;until=200');
});

test('day links intersect the viewer calendar day with the captured window', () => {
  const start = new Date(2026, 9, 2).getTime();
  const next = new Date(2026, 9, 3).getTime();
  const url = new URL(requestsFor({ ...item, key: '2026-10-02' }, 'day', start + 100, next + 100), 'http://synthetic.invalid');
  expect(url.searchParams.get('since')).toBe(String(start + 100));
  expect(url.searchParams.get('until')).toBe(String(next));
});

test('an account link retains exact attribution, command and bounds', () => {
  const account = { ...item, key: row.account ?? null };
  const url = new URL(requestsFor(account, 'account', 100, 200), 'http://synthetic.invalid');
  expect(url.searchParams.get('head')).toBe('synthetic');
  expect(url.searchParams.get('account')).toBe('work & spare');
  expect(url.searchParams.get('since')).toBe('100');
  expect(url.searchParams.get('until')).toBe('200');
});

test('unknown attribution is explicit instead of becoming an arbitrary named filter', () => {
  expect(requestsFor({ ...item, key: null }, 'model', 100, 200)).toContain('unattributed=model');
});

test('a lone sub-dollar amount still spans the full magnitude scale', () => {
  const html = renderToStaticMarkup(<MemoryRouter><UsageValues items={items} by="model" since={0} until={200} labelOf={key => key} /></MemoryRouter>);
  expect(html).toContain('width:100%');
});

test('daily budgets use their own measured window and warn before exhaustion', () => {
  const html = renderToStaticMarkup(<BudgetBalance budget={{ head: 'synthetic', daily_usd: 10, action: 'warn', used_usd: 8, remaining_usd: 2 }} />);
  expect(html).toContain('Budget running low');
  expect(html).toContain('$2.00 remains');
});

test('unpriced daily spending is unknown, never a zero balance or a false early warning', () => {
  const html = renderToStaticMarkup(<BudgetBalance budget={{ head: 'synthetic', daily_usd: 10, action: 'warn', used_usd: null }} />);
  expect(html).toContain('Spending is not reported');
  expect(html).not.toContain('$0');
  expect(html).not.toContain('Budget running low');
});
