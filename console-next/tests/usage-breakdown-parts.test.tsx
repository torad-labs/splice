import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { expect, test } from 'vitest';
import { BudgetBalance } from '../src/pages/usage/Budgets';
import { CommandPricing } from '../src/pages/usage/UsagePricing';
import { UsageBreakdown, UsageValues, requestsFor } from '../src/pages/usage/UsageBreakdown';
import { usageBreakdown } from '../src/lib/usage-breakdown';
import type { TurnRow } from '../src/types/perf';

const row: TurnRow = { head: 'synthetic', ts: 100, model: 'model / a', account: 'work & spare', compact: false, outcome: 'ok', cost_usd: 0.25, in_tokens: 200, out_tokens: 50 };

test('missing catalog prices have a reason and an exact supported command-specific declaration', () => {
  const catalog = { head: 'synthetic.command', provider: 'synthetic', pinned_model: 'model.1', models: [{ id: 'model.1', label: 'Model one', description: '', slot: null, context_window: 1000, context_window_source: 'synthetic', pinned: true, resolved: true }] };
  const html = renderToStaticMarkup(<MemoryRouter><CommandPricing command="synthetic.command" label="Synthetic command" catalog={catalog} unpriced={2} path="/synthetic/splice.toml" /></MemoryRouter>);
  expect(html).toContain('No per-token prices are declared');
  expect(html).toContain('Spending is unknown, not free');
  expect(html).toContain('[heads.&quot;synthetic.command&quot;.rates]');
  expect(html).toContain('&quot;model.1&quot; =');
  expect(html).toContain('INPUT_USD');
  expect(html).not.toContain('$0');
});

test('current prices do not manufacture estimates for missing request history', () => {
  const html = renderToStaticMarkup(<MemoryRouter><CommandPricing command="synthetic" label="Synthetic" catalog={{ head: 'synthetic', provider: 'synthetic', pinned_model: '', models: [] }} unpriced={3} path={undefined} /></MemoryRouter>);
  expect(html).toContain('did not record a dollar estimate');
  expect(html).not.toContain('$0');
});

test('the breakdown links carry the captured window, not a later render clock', () => {
  const read = { isError: false, isPending: false, data: { landed: [row], inflight: [], unread: [], truncated: [], matched: 1, matchedBy: { synthetic: 1 }, window: { since: 100, until: 200 } } } as unknown as Parameters<typeof UsageBreakdown>[0]['read'];
  const props = { read, since: 150, until: 250, labelOf: (key: string) => key };
  const html = renderToStaticMarkup(<MemoryRouter><UsageBreakdown {...props} /></MemoryRouter>);
  expect(html).toContain('since=100&amp;until=200');
  expect(html).not.toContain('since=150');
});

test('the selected request interval includes since but excludes until', () => {
  const html = renderToStaticMarkup(<MemoryRouter><UsageValues rows={[row, { ...row, ts: 200, model: 'outside' }]} by="model" since={100} until={200} labelOf={key => key} /></MemoryRouter>);
  expect(html).toContain('model / a');
  expect(html).not.toContain('outside');
});

test('an account link retains exact attribution, command and bounds', () => {
  const item = usageBreakdown([row], 'account')[0];
  if (item === undefined) throw new Error('synthetic attribution must exist');
  const url = new URL(requestsFor(item, 'account', 100, 200), 'http://synthetic.invalid');
  expect(url.searchParams.get('head')).toBe('synthetic');
  expect(url.searchParams.get('account')).toBe('work & spare');
  expect(url.searchParams.get('since')).toBe('100');
  expect(url.searchParams.get('until')).toBe('200');
});

test('unknown attribution is explicit instead of becoming an arbitrary named filter', () => {
  const item = usageBreakdown([{ ...row, model: null }], 'model')[0];
  if (item === undefined) throw new Error('synthetic request must exist');
  expect(requestsFor(item, 'model', 100, 200)).toContain('unattributed=model');
});

test('a lone sub-dollar amount still spans the full magnitude scale', () => {
  const html = renderToStaticMarkup(<MemoryRouter><UsageValues rows={[row]} by="model" since={0} until={200} labelOf={key => key} /></MemoryRouter>);
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
