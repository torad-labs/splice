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
  const faults = await open(page, 'usage');
  const total = page.getByRole('region', { name: 'Usage', exact: true });
  await expect(total.getByRole('heading', { name: 'Requests', exact: true }).locator('..')).toContainText('2,502');
  const idle = await page.locator('.idle-plans').textContent();
  expect((idle?.split(' on ')[1] ?? '').replace(/\.$/, '').split(', ')).not.toContain(STACK.oauthHead);
  const chart = page.getByRole('region', { name: 'Spend and tokens', exact: true });
  await expect(chart).toContainText('2,502 requests');
  await expect(chart).toContainText('At least $1.48');
  await expect(chart).not.toContainText('This breakdown is incomplete');
  await expect(total).toContainText('90% came from the cache');
  await expect(page.locator('.uplan').filter({ hasText: STACK.oauthHead })).toContainText('90% cached');
  await chart.getByRole('button', { name: 'Account', exact: true }).click();
  const href = await chart.getByRole('link', { name: /Open Requests for synthetic & account/ }).getAttribute('href');
  const bounds = windows.get(STACK.oauthHead);
  expect(bounds).toBeDefined();
  const query = new URLSearchParams(href?.split('?')[1]);
  expect(query.get('since')).toBe(String(bounds?.since));
  expect(query.get('until')).toBe(String(bounds?.until));
  expect(query.get('head')).toBe(STACK.oauthHead);
  expect(query.get('account')).toBe('synthetic & account');
  await chart.getByRole('button', { name: 'Show the values as a table', exact: true }).click();
  await expect(chart.getByRole('table')).toBeVisible();
  await expect(chart.getByRole('columnheader', { name: 'Input tokens', exact: true })).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
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
  await expect(dialog).toContainText('A dollar budget cannot measure its spending');
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
