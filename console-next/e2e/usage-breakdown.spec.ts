import { expect, test } from '@playwright/test';
import type { HeadsPayload } from '../src/types/core';
import type { TurnRowWire, TurnUsageStats, TurnUsageWire } from '../src/types/perf';

const fullStats: TurnUsageStats = { requests: 2502, input_tokens: 2501000, cached_tokens: 2250900, output_tokens: 250100, cost_usd: 1.47559, cache_share: 0.9, unpriced_requests: 1, missing_input_requests: 1, missing_output_requests: 1, missing_cache_requests: 0 };
const fullUsage: TurnUsageWire = { totals: fullStats, models: [{ key: 'earlier synthetic model', ...fullStats }], accounts: [{ key: 'synthetic & account', ...fullStats }], days: [{ key: '2026-10-02', ...fullStats }], sessions: [] };
const emptyUsage: TurnUsageWire = { totals: { ...fullStats, requests: 0, input_tokens: null, cached_tokens: null, output_tokens: null, cost_usd: null, cache_share: null, unpriced_requests: 0, missing_input_requests: 0, missing_output_requests: 0, missing_cache_requests: 0 }, models: [], accounts: [], days: [], sessions: [] };
import { open, assertHealthy } from './support';
import { STACK } from './stack';

const wire = (ts: number, priced: boolean): TurnRowWire => ({
  ts, model: 'synthetic.model / one', outcome: priced ? 'ok' : 'quota-refused', compact: false,
  session: null, session_id: null, account: 'synthetic & account', cache_cold: null,
  turn: null, response_message_id: null,
  ...(priced ? { cost_usd: 0.25, in_tokens: 200, out_tokens: 50 } : { cost_usd: null }),
});

test('Usage counts refused requests, keeps exact drill-down bounds, and offers a real table without narrow overflow', async ({ page }) => {
  const windows = new Map<string, { since: number; until: number }>();
  const awaitedZone = await page.evaluate(() => Intl.DateTimeFormat().resolvedOptions().timeZone);
  await page.route(url => url.pathname === '/api/economics', route => route.fulfill({ json: { retention_hours: 24, heads: [] } }));
  await page.route(url => url.pathname === '/api/perf/turns', route => {
    const query = new URL(route.request().url()).searchParams;
    expect(query.get('local')).toBe('0');
    expect(query.get('n')).toBe('1');
    expect(query.get('time_zone')).toBe(awaitedZone);

    const head = query.get('head') ?? '';
    const since = Number(query.get('since'));
    const until = Number(query.get('until'));
    windows.set(head, { since, until });
    return route.fulfill({ json: { since, n: 10_000, heads: [{ key: head, label: head, count: head === STACK.oauthHead ? 2502 : 0, truncated: head === STACK.oauthHead, usage: head === STACK.oauthHead ? fullUsage : emptyUsage, rows: head === STACK.oauthHead ? [wire(until - 1, false)] : [] }] } });
  });
  await page.route(url => url.pathname === '/api/models', route => route.fulfill({ json: { heads: [{ head: STACK.oauthHead, provider: 'synthetic', pinned_model: '', models: [{ id: 'earlier synthetic model', label: 'Synthetic readable model' }] }] } }));
  await page.route(url => url.pathname === '/api/accounts', async route => {
    const response = await route.fetch();
    const body = await response.json() as { accounts: Record<string, unknown>[] };
    body.accounts = [...body.accounts, { kind: 'synthetic', label: 'synthetic & account', display_name: 'Synthetic saved login', selector_key: 'synthetic & account', credential_present: true, heads: [STACK.oauthHead] }];
    await route.fulfill({ response, json: body });
  });
  const faults = await open(page, 'usage');
  const total = page.getByRole('region', { name: 'Usage', exact: true });
  await expect(total.getByRole('heading', { name: 'Requests', exact: true }).locator('..')).toContainText('2,502');
  const idle = await page.locator('.idle-plans').textContent();
  expect((idle?.split(' on ')[1] ?? '').replace(/\.$/, '').split(', ')).not.toContain(STACK.oauthHead);
  const chart = page.getByRole('region', { name: 'Spend and tokens', exact: true });
  await expect(chart).toContainText('2,502 requests');
  await expect(chart).toContainText('Synthetic readable model');
  const modelHref = await chart.getByRole('link', { name: /Open Requests for Synthetic readable model/ }).getAttribute('href');
  expect(new URLSearchParams(modelHref?.split('?')[1]).get('model')).toBe('earlier synthetic model');
  await expect(chart).toContainText('At least $1.48');
  await expect(chart.locator('.usage-spend-track')).toBeVisible();
  await expect(chart).toContainText('A bar opens the matching Requests.');
  await expect(chart).not.toContainText('This breakdown is incomplete');
  await expect(total).toContainText('90% came from the cache');
  const command = page.locator('.uplan').filter({ has: page.getByText(STACK.oauthHead, { exact: true }) });
  await expect(command).toHaveCount(1);
  await expect(command).toContainText('90% cached');
  await chart.getByRole('button', { name: 'Account', exact: true }).click();
  await expect(chart).toContainText('Synthetic saved login');
  const href = await chart.getByRole('link', { name: /Open Requests for Synthetic saved login/ }).getAttribute('href');
  const bounds = windows.get(STACK.oauthHead);
  expect(bounds).toBeDefined();
  const query = new URLSearchParams(href?.split('?')[1]);
  expect(query.get('since')).toBe(String(bounds?.since));
  expect(query.get('until')).toBe(String(bounds?.until));
  expect(query.get('head')).toBe(STACK.oauthHead);
  expect(query.get('account')).toBe('synthetic & account');
  await chart.getByRole('button', { name: 'Show the values as a table', exact: true }).click();
  await expect(chart.getByRole('table')).toBeVisible();
  await expect(chart).not.toContainText('A bar opens the matching Requests.');
  await chart.getByRole('button', { name: 'Show spend bars', exact: true }).click();
  await expect(chart).toContainText('A bar opens the matching Requests.');
  await chart.getByRole('button', { name: 'Show the values as a table', exact: true }).click();
  await expect(chart.getByRole('columnheader', { name: 'Input tokens', exact: true })).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await assertHealthy(page, faults);
});

for (const hours of [24, 168]) {
  test(`an unread command cannot hide a reporting command's Usage rows in the ${hours}-hour window`, async ({ page }) => {
    await page.route(url => url.pathname === '/api/economics', route => route.fulfill({ json: { retention_hours: 168, heads: [] } }));
    await page.route(url => url.pathname === '/api/perf/turns', route => {
      const query = new URL(route.request().url()).searchParams;
      const head = query.get('head') ?? '';
      const span = Number(query.get('until')) - Number(query.get('since'));
      expect(span).toBe(hours * 3_600_000);
      return route.fulfill({ json: { since: Number(query.get('since')), n: 1, heads: [{
        key: head, label: head, count: head === STACK.oauthHead ? 2502 : 0, rows: [],
        usage: head === STACK.oauthHead ? fullUsage : emptyUsage,
        ...(head === STACK.soloHead ? { read_error: 'Three synthetic old records could not be read' } : {}),
      }] } });
    });
    const faults = await open(page, hours === 168 ? 'usage?window=168' : 'usage');
    const chart = page.getByRole('region', { name: 'Spend and tokens', exact: true });
    await expect(chart).toContainText('This breakdown is incomplete');
    await expect(chart).toContainText('Three synthetic old records could not be read');
    await expect(chart).toContainText('earlier synthetic model');
    await expect(chart).toContainText('2,502 requests');
    await expect(chart).toContainText('At least $1.48');
    await chart.getByRole('button', { name: 'Account', exact: true }).click();
    await expect(chart).toContainText('synthetic & account');
    await expect(chart).toContainText('This breakdown is incomplete');
    await chart.getByRole('button', { name: 'Show the values as a table', exact: true }).click();
    await expect(chart.getByRole('table')).toBeVisible();
    await expect(chart.getByRole('table')).toContainText('synthetic & account');
    await assertHealthy(page, faults);
  });
}

test('daemon-reported local and unanswered causes stay in words across the full Usage breakdown', async ({ page }) => {
  const stats = { ...emptyUsage.totals, requests: 2, cost_usd: 0.25, unpriced_requests: 0, unanswered_requests: 1 };
  const local = { ...emptyUsage.totals, requests: 1, unpriced_requests: 1, unpriced_local_requests: 1 };
  const plan = { ...emptyUsage.totals, requests: 1, unpriced_requests: 1, unpriced_plan_requests: 1 };
  const totals = { ...stats, requests: 4, unpriced_requests: 2, unpriced_local_requests: 1, unpriced_plan_requests: 1 };
  const groups = [{ key: 'synthetic answered and failed', ...stats }, { key: 'synthetic runtime', ...local }, { key: 'synthetic subscription', ...plan }];
  const usage = { totals, models: groups, accounts: groups, days: groups.map((group, index) => ({ ...group, key: '2026-10-0' + (index + 1) })), sessions: [] };
  await page.route(url => url.pathname === '/api/economics', route => route.fulfill({ json: { retention_hours: 24, heads: [] } }));
  await page.route(url => url.pathname === '/api/perf/turns', route => {
    const head = new URL(route.request().url()).searchParams.get('head') ?? '';
    return route.fulfill({ json: { since: 0, n: 1, heads: [{ key: head, label: head, count: head === STACK.oauthHead ? 4 : 0, rows: [], usage: head === STACK.oauthHead ? usage : emptyUsage }] } });
  });
  const faults = await open(page, 'usage');
  const chart = page.getByRole('region', { name: 'Spend and tokens', exact: true });
  for (const dimension of ['Model', 'Account', 'Day']) {
    await chart.getByRole('button', { name: dimension, exact: true }).click();
    await expect(chart).toContainText('1 request ran its model on this computer, so it has no provider price.');
    await expect(chart).toContainText('1 request is covered by a plan, so it has no price.');
    await expect(chart).toContainText('1 request failed without a recorded answer or token usage.');
    await expect(chart).not.toContainText('missing-spend estimate');
    const covered = chart.getByRole('listitem').filter({ hasText: 'covered by a plan' });
    await expect(covered.locator('.usage-spend')).not.toContainText('Not reported');
    await expect(chart).not.toContainText('no recorded price');
    const answered = chart.getByRole('listitem').filter({ hasText: 'failed without a recorded answer' });
    await expect(answered).toContainText('$0.25');
    await expect(answered).not.toContainText('At least');
  }
  await assertHealthy(page, faults);
});

test('a pending empty-day budget finishes on Usage without a page change and agrees with Accounts', async ({ page }) => {
  let reads = 0;
  await page.route(url => url.pathname === '/api/economics', route => route.fulfill({ json: { retention_hours: 24, heads: [] } }));
  await page.route(url => url.pathname === '/api/perf/turns', route => {
    const head = new URL(route.request().url()).searchParams.get('head') ?? '';
    return route.fulfill({ json: { since: 0, n: 1, heads: [{ key: head, label: head, count: 0, rows: [], usage: emptyUsage }] } });
  });
  await page.route(url => url.pathname === '/api/budgets', route => {
    reads++;
    const pending = reads === 1;
    return route.fulfill({ json: { budgets: [{ head: STACK.soloHead, daily_usd: 50, action: 'warn',
      used_usd: pending ? null : 0, remaining_usd: pending ? null : 50,
      spend_complete: !pending, spend_pending: pending, unpriced_turns: 0 }] } });
  });
  const faults = await open(page, 'usage');
  const budgets = page.getByRole('region', { name: 'Budgets', exact: true });
  await expect(budgets).toContainText('$0.00 spent', { timeout: 5000 });
  await expect(budgets).toContainText('$50.00 left');
  await expect(budgets).not.toContainText('$0.000');
  await expect(budgets).not.toContainText('Spending is not reported');
  await expect.poll(() => reads).toBeGreaterThanOrEqual(2);
  await page.getByRole('link', { name: 'Accounts', exact: true }).click();
  const balance = page.locator('.account-budget').filter({ hasText: 'Left in budget: $50.00' });
  await expect(balance).toHaveCount(1);
  await expect(balance).toContainText('Budget used: $0.00');
  await expect(balance).not.toContainText('$0.000');
  await assertHealthy(page, faults);
});

test('eleven budget commands stay in the viewport and an outside menu click never discards typed input', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.route(url => url.pathname === '/api/models', route => route.fulfill({ json: { heads: [{ head: 'synthetic-command-10', provider: 'synthetic', pinned_model: '', models: [{ id: 'synthetic-priced-model', rates: { input: 1, cache_read: 0.1, output: 2 } }] }] } }));
  await page.route(url => url.pathname === '/api/heads', async route => {
    const response = await route.fetch();
    const body = await response.json() as HeadsPayload;
    const head = body.heads[0];
    if (head === undefined) throw new Error('isolated fixture needs one command');
    body.heads = Array.from({ length: 11 }, (_, index) => ({ ...head, key: 'synthetic-command-' + index, label: 'Synthetic command ' + index }));
    await route.fulfill({ response, json: body });
  });
  await page.route(url => url.pathname === '/api/budgets', route => route.fulfill({ json: { budgets: [] } }));
  await page.route(url => url.pathname === '/api/perf/turns', route => route.fulfill({ json: { since: Date.now() - 86400_000, n: 10_000, heads: [] } }));
  await open(page, 'usage');
  await page.getByRole('button', { name: 'Add a budget', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('spinbutton', { name: 'Dollars a day', exact: true }).fill('2.50');
  await dialog.getByRole('button', { name: 'Command', exact: true }).click();
  const menu = page.getByRole('menu');
  const bounds = await menu.boundingBox();
  expect(bounds).not.toBeNull();
  expect((bounds?.y ?? 0) + (bounds?.height ?? 0)).toBeLessThanOrEqual(900);
  await expect(dialog).toContainText('Scroll the command menu to see every command.');
  await expect(menu.getByRole('menuitemradio', { name: 'Choose a command', exact: true })).toHaveCount(0);
  await expect(menu.getByRole('menuitemradio').first()).toHaveText('Synthetic command 10');
  const last = page.getByRole('menuitemradio', { name: 'Synthetic command 9', exact: true });
  await last.scrollIntoViewIfNeeded();
  await expect(last).toBeVisible();
  await last.click();
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole('spinbutton', { name: 'Dollars a day', exact: true })).toHaveValue('2.50');
  await dialog.getByRole('button', { name: 'Command', exact: true }).click();
  await page.mouse.click(20, 450);
  await expect(menu).toHaveCount(0);
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole('spinbutton', { name: 'Dollars a day', exact: true })).toHaveValue('2.50');
  await page.mouse.click(20, 450);
  await expect(dialog).toBeVisible();
});

test('a new budget starts blank and its Command menu stays inside the dialog under pointer selection', async ({ page }) => {
  await page.route(url => url.pathname === '/api/models', async route => {
    const response = await route.fetch();
    const body = await response.json();
    for (const head of body.heads) for (const model of head.models) if (head.head === STACK.keyHead) model.rates = { input: 1, cache_read: 0.1, output: 2 };
    await route.fulfill({ response, json: body });
  });
  await page.route(url => url.pathname === '/api/budgets', route => route.fulfill({ json: { budgets: [] } }));
  const faults = await open(page, 'usage');
  await page.getByRole('button', { name: 'Add a budget', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole('textbox', { name: 'Dollars a day', exact: true }).or(dialog.getByRole('spinbutton', { name: 'Dollars a day', exact: true }))).toHaveValue('');
  await expect(dialog.getByRole('button', { name: 'Save', exact: true })).toBeDisabled();
  await expect(dialog.getByRole('button', { name: 'Command', exact: true })).toContainText('Choose a command');
  await dialog.getByRole('button', { name: 'Command', exact: true }).click();
  await page.getByRole('menuitemradio', { name: STACK.oauthHead, exact: true }).click();
  await dialog.getByRole('spinbutton', { name: 'Dollars a day', exact: true }).fill('2.50');
  await expect(dialog).toContainText('Its budget cannot count spending yet. Declare prices in its price card first.');
  await expect(dialog.getByRole('button', { name: 'Save', exact: true })).toBeDisabled();
  await dialog.getByRole('button', { name: 'Command', exact: true }).click();
  await page.getByRole('menuitemradio', { name: STACK.keyHead, exact: true }).click();
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Command', exact: true })).toContainText(STACK.keyHead);
  await dialog.getByRole('spinbutton', { name: 'Dollars a day', exact: true }).fill('2.50');
  await expect(dialog.getByRole('button', { name: 'Save', exact: true })).toBeEnabled();
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(dialog.getByRole('button', { name: 'Discard changes', exact: true })).toBeVisible();
  await dialog.getByRole('button', { name: 'Discard changes', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await assertHealthy(page, faults);
});
