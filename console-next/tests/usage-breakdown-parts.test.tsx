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
const item = { id: 'synthetic', head: 'synthetic', key: row.model, turns: 1, cost: 0.25, input: 200, output: 50, unpriced: 0, missingInput: 0, missingOutput: 0, gaps: { uncounted: 0, plan: 0, undeclared: 0, unknown: 0 }, cut: 0 };
const items = [item];
const namedCatalog = { head: 'synthetic', provider: 'synthetic', pinned_model: '', models: [{ id: 'model / a', label: 'Synthetic readable model', description: '', slot: null, context_window: 1000, context_window_source: 'synthetic', pinned: false, resolved: true }] };
const savedLogin = { kind: 'synthetic', label: 'work & spare', display_name: 'Synthetic saved login', selector_key: 'stable-synthetic-login', single_login: false, credential_path: null, primary: false, selected: false, available: true, pinned: false, next_target: false, credential_present: true, windows: [], heads: ['synthetic'] };

test('Usage resolves the recorded login on its own command without changing the account drill-down identity', () => {
  const metadata = { accounts: [{ ...savedLogin, display_name: 'Wrong command login', heads: ['other'] }, savedLogin], catalogs: [] };
  const html = renderToStaticMarkup(<MemoryRouter><UsageValues items={[{ ...item, key: 'stable-synthetic-login' }]} by="account" since={0} until={200} labelOf={() => 'Synthetic command'} {...metadata} /></MemoryRouter>);
  expect(html).toContain('Synthetic saved login · Synthetic command');
  expect(html).not.toContain('Wrong command login');
  expect(html).toContain('account=stable-synthetic-login');
  expect(html).toContain('head=synthetic');
});

test('Usage names the primary fallback without changing its drill-down selector', () => {
  const html = renderToStaticMarkup(<MemoryRouter><UsageValues items={[{ ...item, key: 'primary' }]} by="account" since={0} until={200} labelOf={() => 'Synthetic command'} accounts={[]} /></MemoryRouter>);
  expect(html).toContain('Primary account · Synthetic command');
  expect(html).toContain('account=primary');
  expect(html).toContain('head=synthetic');
  expect(html).not.toContain('>primary ·');
});

test('Usage uses an unambiguous catalog label without changing the model drill-down ID', () => {
  const metadata = { accounts: [], catalogs: [namedCatalog] };
  const html = renderToStaticMarkup(<MemoryRouter><UsageValues items={items} by="model" since={0} until={200} labelOf={key => key} {...metadata} /></MemoryRouter>);
  expect(html).toContain('Synthetic readable model');
  expect(html).toContain('model=model+%2F+a');
});

test('historical IDs and conflicting catalog names remain literal instead of being attributed to a different model', () => {
  const conflicting = { ...namedCatalog, head: 'other', models: namedCatalog.models.map(model => ({ ...model, label: 'Different provider model' })) };
  const metadata = { accounts: [], catalogs: [namedCatalog, conflicting] };
  const html = renderToStaticMarkup(<MemoryRouter><UsageValues items={items} by="model" since={0} until={200} labelOf={key => key} {...metadata} /></MemoryRouter>);
  expect(html).toContain('model / a');
  expect(html).not.toContain('Synthetic readable model');
  expect(html).not.toContain('Different provider model');
  const historical = renderToStaticMarkup(<MemoryRouter><UsageValues items={[{ ...item, key: 'synthetic-removed-model' }]} by="model" since={0} until={200} labelOf={key => key} {...metadata} /></MemoryRouter>);
  expect(historical).toContain('synthetic-removed-model');
  expect(historical).not.toContain('Synthetic readable model');
  const unknownLogin = renderToStaticMarkup(<MemoryRouter><UsageValues items={[{ ...item, key: 'synthetic-removed-login' }]} by="account" since={0} until={200} labelOf={key => key} accounts={[savedLogin]} /></MemoryRouter>);
  expect(unknownLogin).toContain('synthetic-removed-login');
  expect(unknownLogin).not.toContain('Synthetic saved login');
});
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

test.each([24, 168])('known model rows remain under the incomplete notice for a %s-hour window', hours => {
  const until = 1_791_151_200_000;
  const since = until - hours * 3_600_000;
  const read = reading({
    matched: null,
    matchedBy: { synthetic: 2502 },
    unread: [{ head: 'unreadable', reason: 'Three synthetic old records could not be read' }],
    window: { since, until },
  });
  const html = renderToStaticMarkup(<MemoryRouter><UsageBreakdown read={read} labelOf={key => key} /></MemoryRouter>);
  expect(html).toContain('This breakdown is incomplete');
  expect(html).toContain('unreadable: Three synthetic old records could not be read');
  expect(html).toContain('earlier model');
  expect(html).toContain('2,502 requests');
  expect(html).toContain('At least $1.48');
  expect(html).toContain(`since=${since}&amp;until=${until}`);
  expect(html.indexOf('This breakdown is incomplete')).toBeLessThan(html.indexOf('earlier model'));
  expect(html).not.toContain('does not report full-window usage');
});

test('known rows remain visible while an unread sibling is still being read', () => {
  const read = reading({ pendingHeads: ['other'] });
  const html = renderToStaticMarkup(<MemoryRouter><UsageBreakdown read={read} labelOf={key => key} /></MemoryRouter>);
  expect(html).toContain('Reading the request usage');
  expect(html).toContain('This breakdown is incomplete');
  expect(html).toContain('earlier model');
  expect(html).toContain('At least $1.48');
});

test('no reporting command shows unavailable under the incomplete notice, never fabricated zero', () => {
  const read = reading({ usageBy: {}, unread: [{ head: 'other', reason: 'Synthetic unreadable history' }] });
  const html = renderToStaticMarkup(<MemoryRouter><UsageBreakdown read={read} labelOf={key => key} /></MemoryRouter>);
  expect(html).toContain('This breakdown is incomplete');
  expect(html).toContain('does not report full-window usage');
  expect(html).not.toContain('No requests in this window');
  expect(html).not.toContain('$0');
});

test('an empty settled command keeps the breakdown reading until its sibling settles', () => {
  const empty = { ...complete, totals: { ...complete.totals, requests: 0 }, models: [] };
  const state = { matched: 0, matchedBy: { synthetic: 0 }, usageBy: { synthetic: empty } };
  const render = (over: Partial<TurnsState>) => renderToStaticMarkup(<MemoryRouter><UsageBreakdown read={reading({ ...state, ...over })} labelOf={key => key} /></MemoryRouter>);
  const pending = render({ pendingHeads: ['other'] });
  expect(pending).toContain('Reading the request usage');
  expect(pending).not.toContain('No requests in this window');
  expect(pending).not.toContain('does not report full-window usage');
  const unread = render({ unread: [{ head: 'other', reason: 'Synthetic unavailable history' }] });
  expect(unread).not.toContain('No requests in this window');
  expect(unread).not.toContain('Reading the request usage');
  expect(unread).toContain('This breakdown is incomplete');
  expect(unread).not.toContain('does not report full-window usage');
  expect(render({ pendingHeads: [] })).toContain('No requests in this window');
});

test('an empty settled command cannot hide an unread sibling behind no requests', () => {
  const empty = { ...complete, totals: { ...complete.totals, requests: 0 }, models: [] };
  const read = reading({ matched: 0, matchedBy: { synthetic: 0 }, usageBy: { synthetic: empty }, unread: [{ head: 'other', reason: 'Synthetic unavailable history' }] });
  const html = renderToStaticMarkup(<MemoryRouter><UsageBreakdown read={read} labelOf={key => key} /></MemoryRouter>);
  expect(html).toContain('This breakdown is incomplete');
  expect(html).toContain('other: Synthetic unavailable history');
  expect(html).not.toContain('No requests in this window');
  expect(html).not.toContain('Reading the request usage');
});

test('the bar guidance appears only with a visible spend bar', () => {
  const guidance = 'A bar opens the matching Requests.';
  const unavailable = renderToStaticMarkup(<MemoryRouter><UsageBreakdown read={reading({ usageBy: {} })} labelOf={key => key} /></MemoryRouter>);
  expect(unavailable).not.toContain(guidance);
  for (const cost of [null, 0]) {
    const usage = { ...complete, models: complete.models.map(model => ({ ...model, cost_usd: cost })) };
    const html = renderToStaticMarkup(<MemoryRouter><UsageBreakdown read={reading({ usageBy: { synthetic: usage } })} labelOf={key => key} /></MemoryRouter>);
    expect(html).not.toContain(guidance);
  }
  const priced = renderToStaticMarkup(<MemoryRouter><UsageValues items={items} by="model" since={0} until={200} labelOf={key => key} /></MemoryRouter>);
  expect(priced).toContain(guidance);
});

test('a group with replies cut off by a new message says their tokens are not reported', () => {
  const cut = renderToStaticMarkup(<MemoryRouter><UsageValues items={[{ ...item, cut: 2 }]} by="model" since={0} until={200} labelOf={key => key} /></MemoryRouter>);
  expect(cut).toContain('2 replies were cut off by a new message, so their tokens are not reported.');
  const plain = renderToStaticMarkup(<MemoryRouter><UsageValues items={items} by="model" since={0} until={200} labelOf={key => key} /></MemoryRouter>);
  expect(plain).not.toContain('cut off');
});

test('an unanswered failure explains its cause without making recorded spend a lower bound', () => {
  const gaps = { ...item.gaps, unanswered: 1 };
  const html = renderToStaticMarkup(<MemoryRouter><UsageValues items={[{ ...item, turns: 2, gaps }]} by="model" since={0} until={200} labelOf={key => key} /></MemoryRouter>);
  expect(html).toContain('1 request failed without a recorded answer or token usage.');
  expect(html).not.toContain('missing-spend estimate');
  expect(html).toContain('$0.25');
  expect(html).not.toContain('At least');
  expect(html).not.toContain('no recorded price');
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

test('declared prices do not hide missing input and output counters or suggest another price declaration', () => {
  const catalog = { head: 'synthetic', provider: 'synthetic', pinned_model: 'model.1', models: [{ id: 'model.1', label: 'Model one', description: '', slot: null, context_window: 1000, context_window_source: 'synthetic', pinned: true, resolved: true, rates: { input: 1, cache_read: 0.1, output: 2 } }] };
  const html = renderToStaticMarkup(<MemoryRouter><CommandPricing command="synthetic" label="Synthetic" catalog={catalog} usedModels={['model.1']} unpriced={2} missingInput={2} missingOutput={1} path={undefined} /></MemoryRouter>);
  expect(html).toContain('2 requests have no input token count');
  expect(html).toContain('1 request has no output token count');
  expect(html).toContain('Declared prices do not supply missing token counts');
  expect(html).not.toContain('No price is declared');
  expect(html).not.toContain('INPUT_USD');
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

// Marlin's pass 5: the spend cell read "no recorded price" for a priced model whose failed requests had no token
// count, and for requests a plan covers. Each cause has its own sentence.
test('the spend cell says why each request has no price', () => {
  const gaps = { uncounted: 15, plan: 2, undeclared: 1, unknown: 0 };
  const html = renderToStaticMarkup(<MemoryRouter><UsageValues items={[{ ...item, unpriced: 18, gaps }]} by="model" since={0} until={200} labelOf={key => key} /></MemoryRouter>);
  expect(html).toContain('15 requests have no token count, so they cannot be priced.');
  expect(html).toContain('2 requests are covered by a plan, so they have no price.');
  expect(html).toContain('1 request has no recorded price.');
  expect(html.match(/no recorded price/g)).toHaveLength(1);
});

test('a null cost shows its precise explanation instead of also claiming Not reported', () => {
  const gaps = { uncounted: 0, plan: 1, undeclared: 0, unknown: 0 };
  const html = renderToStaticMarkup(<MemoryRouter><UsageValues items={[{ ...item, cost: null, unpriced: 1, gaps }]} by="model" since={0} until={200} labelOf={key => key} /></MemoryRouter>);
  expect(html).toContain('1 request is covered by a plan, so it has no price.');
  expect(html).not.toContain('Not reported');
  expect(html).not.toContain('$0');
  expect(html).toContain('since=0&amp;until=200');
  const unknown = renderToStaticMarkup(<MemoryRouter><UsageValues items={[{ ...item, cost: null }]} by="model" since={0} until={200} labelOf={key => key} /></MemoryRouter>);
  expect(unknown).toContain('Not reported');
});

test('a completely read empty budget day displays its measured zero and full remaining balance', () => {
  const html = renderToStaticMarkup(<BudgetBalance budget={{ head: 'synthetic', daily_usd: 50, action: 'warn', used_usd: 0, remaining_usd: 50 }} />);
  expect(html).toContain('$0.00 spent');
  expect(html).toContain('$50.00 left');
  expect(html).not.toContain('$0.000');
  expect(html).not.toContain('not reported');
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
