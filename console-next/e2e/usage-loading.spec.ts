// NEW: V4-444 — Usage publishes settled command totals and distinguishes loading from partial or failed reads.
import { expect, test } from '@playwright/test';
import type { HeadsPayload } from '../src/types/core';
import type { TurnUsageStats, TurnUsageWire } from '../src/types/perf';
import { open, read } from './support';
import { STACK } from './stack';

const stats: TurnUsageStats = {
  requests: 2502, input_tokens: 2501000, cached_tokens: 2250900, output_tokens: 250100,
  cost_usd: 1.47559, cache_share: 0.9, unpriced_requests: 0, missing_input_requests: 0,
  missing_output_requests: 0, missing_cache_requests: 0,
};
const usage: TurnUsageWire = { totals: stats, models: [], accounts: [], days: [], sessions: [] };
const empty: TurnUsageWire = {
  totals: { ...stats, requests: 0, input_tokens: null, cached_tokens: null, output_tokens: null, cost_usd: null, cache_share: null },
  models: [], accounts: [], days: [], sessions: [],
};

test('a seven-day settled command publishes while its sibling remains loading', async ({ page }) => {
  const heads = await read<HeadsPayload>(page, '/api/heads');
  heads.heads = heads.heads.filter(head => head.key === STACK.oauthHead || head.key === STACK.soloHead);
  await page.route('**/api/heads', route => route.fulfill({ json: heads }));
  await page.route('**/api/economics', route => route.fulfill({ json: { retention_hours: 168, heads: [] } }));
  let release!: () => void;
  const released = new Promise<void>(resolve => { release = resolve; });
  const spans: number[] = [];
  await page.route(url => url.pathname === '/api/perf/turns', async route => {
    const query = new URL(route.request().url()).searchParams;
    const key = query.get('head') ?? '';
    const since = Number(query.get('since'));
    const until = Number(query.get('until'));
    spans.push(until - since);
    if (key === STACK.soloHead) await released;
    await route.fulfill({ json: {
      since, n: 1, heads: [{ key, label: key, count: key === STACK.oauthHead ? 2502 : 0,
        usage: key === STACK.oauthHead ? usage : empty, rows: [], skipped_lines: key === STACK.oauthHead ? 2 : 0 }],
    } });
  });
  try {
    await open(page, 'usage?window=168');
    const total = page.getByRole('region', { name: 'Usage', exact: true });
    await expect(total.getByRole('heading', { name: 'Requests', exact: true }).locator('..')).toContainText('2,502');
    const ready = page.locator('.uplan').filter({ has: page.getByText(STACK.oauthHead, { exact: true }) });
    await expect(ready).toContainText('90% cached');
    const pending = page.locator('.uplan').filter({ has: page.getByText(STACK.soloHead, { exact: true }) });
    await expect(pending).toContainText('Reading requests');
    await expect(page.getByText('Reading the usage.', { exact: true })).toHaveCount(0);
    expect(spans.length).toBeGreaterThan(0);
    expect(spans.every(span => span === 168 * 3_600_000)).toBe(true);
  } finally {
    release();
  }
});

test('the request headline counts commands with requests, not every configured command', async ({ page }) => {
  await page.route('**/api/economics', route => route.fulfill({ json: { retention_hours: 168, heads: [] } }));
  await page.route(url => url.pathname === '/api/perf/turns', route => {
    const query = new URL(route.request().url()).searchParams;
    const key = query.get('head') ?? '';
    return route.fulfill({ json: { since: Number(query.get('since')), n: 1,
      heads: [{ key, label: key, count: key === STACK.oauthHead ? 2502 : 0, usage: key === STACK.oauthHead ? usage : empty, rows: [] }],
    } });
  });
  await open(page, 'usage?window=168');
  const total = page.getByRole('region', { name: 'Usage', exact: true });
  const requests = total.getByRole('heading', { name: 'Requests', exact: true }).locator('..');
  await expect(requests).toContainText('2,502');
  await expect(requests).toContainText('Across 1 command with requests');
  await expect(requests).not.toContainText('Across 3 commands');
});

test('Prices explains missing token counts independently of declared model prices', async ({ page }) => {
  await page.route('**/api/economics', route => route.fulfill({ json: { retention_hours: 168, heads: [] } }));
  const reported = { ...stats, missing_input_requests: 2, missing_output_requests: 1, unpriced_requests: 2 };
  await page.route('**/api/models', route => route.fulfill({ json: { heads: [{ head: STACK.oauthHead, provider: 'synthetic', pinned_model: STACK.model,
    models: [{ id: STACK.model, label: 'Synthetic model', description: '', slot: null, context_window: 1000, context_window_source: 'synthetic', pinned: true, resolved: true, rates: { input: 1, cache_read: 0.1, output: 2 } }],
  }] } }));
  await page.route(url => url.pathname === '/api/perf/turns', route => {
    const query = new URL(route.request().url()).searchParams;
    const key = query.get('head') ?? '';
    return route.fulfill({ json: { since: Number(query.get('since')), n: 1,
      heads: [{ key, label: key, count: key === STACK.oauthHead ? 2502 : 0,
        usage: key === STACK.oauthHead ? { ...usage, totals: reported, models: [{ key: STACK.model, ...reported }] } : empty, rows: [] }],
    } });
  });
  await open(page, 'usage?window=168');
  const disclosure = page.locator('details').filter({ has: page.getByText('Prices for ' + STACK.oauthHead, { exact: true }) });
  await disclosure.locator('summary').click();
  await expect(disclosure).toContainText('2 requests have no input token count');
  await expect(disclosure).toContainText('1 request has no output token count');
  await expect(disclosure).toContainText('Declared prices do not supply missing token counts');
  await expect(disclosure).not.toContainText('No price is declared');
  await expect(disclosure).not.toContainText('INPUT_USD');
});

test('headline metrics keep loading after an early failure while a readable sibling is pending', async ({ page }) => {
  const heads = await read<HeadsPayload>(page, '/api/heads');
  heads.heads = heads.heads.filter(head => head.key === STACK.oauthHead || head.key === STACK.soloHead);
  await page.route('**/api/heads', route => route.fulfill({ json: heads }));
  await page.route('**/api/economics', route => route.fulfill({ json: { retention_hours: 168, heads: [] } }));
  let release!: () => void;
  const released = new Promise<void>(resolve => { release = resolve; });
  await page.route(url => url.pathname === '/api/perf/turns', async route => {
    const query = new URL(route.request().url()).searchParams;
    const key = query.get('head') ?? '';
    if (key === STACK.oauthHead) return route.fulfill({ status: 503, json: { error: 'Synthetic unavailable history' } });
    await released;
    return route.fulfill({ json: { since: Number(query.get('since')), n: 1, heads: [{ key, label: key, count: 2502, usage, rows: [] }] } });
  });
  try {
    await open(page, 'usage?window=168');
    const failed = page.locator('.uplan').filter({ has: page.getByText(STACK.oauthHead, { exact: true }) });
    await expect(failed).toContainText('Request history unavailable');
    await expect(failed).not.toContainText('Reading');
    await expect(page.locator('.totals .n')).toHaveText(['Reading…', 'Reading…', 'Reading…', 'Reading…']);
    release();
    await expect(page.locator('.totals .n').first()).toContainText('2,502');
  } finally {
    release();
  }
});

test('an economics failure stays in hourly history while independent Usage readings still load', async ({ page }) => {
  await page.route('**/api/economics', route => route.fulfill({ status: 500, json: {
    error: 'IllegalStateException: synthetic economics failure',
  } }));
  await page.route(url => url.pathname === '/api/perf/turns', route => {
    const query = new URL(route.request().url()).searchParams;
    const key = query.get('head') ?? '';
    return route.fulfill({ json: { since: Number(query.get('since')), n: 1,
      heads: [{ key, label: key, count: key === STACK.oauthHead ? 2502 : 0, usage: key === STACK.oauthHead ? usage : empty, rows: [] }],
    } });
  });
  const faults = await open(page, 'usage');
  const total = page.getByRole('region', { name: 'Usage', exact: true });
  await expect(total.getByRole('heading', { name: 'Requests', exact: true }).locator('..')).toContainText('2,502');
  await expect(total.getByRole('heading', { name: 'Tokens read in', exact: true }).locator('..')).toContainText('2.50M');
  await expect(total.getByRole('heading', { name: 'API cost, estimated', exact: true }).locator('..')).toContainText('$1.48');
  await expect(page.getByRole('alert')).toContainText('Hourly usage history could not load');
  await expect(page.getByRole('main')).not.toContainText('IllegalStateException');
  await expect(page.locator('.usage-pricing')).toBeVisible();
  await expect(page.locator('[aria-labelledby="usage-budgets"]')).toBeVisible();
  expect(faults.pageErrors).toEqual([]);
  expect(faults.failedReads.every(path => path.includes('/api/economics'))).toBe(true);
});

test('a command whose hours cannot be shown says why in its own row while its sibling draws its hours', async ({ page }) => {
  const hour = Math.floor(Date.now() / 3_600_000) * 3_600_000;
  const why = 'Hourly history is not shown because it does not match this command\'s request log.';
  await page.route('**/api/economics', route => route.fulfill({ json: { retention_hours: 168, generated_at: Date.now(), heads: [
    { key: STACK.oauthHead, label: STACK.oauthHead, ceiling_tokens: null, buckets: [], unavailable: why },
    { key: STACK.keyHead, label: STACK.keyHead, ceiling_tokens: null, buckets: [{
      hour, turns: 3, in_tokens: 9000, cached_tokens: 0, cache_write_tokens: 0, out_tokens: 300, req_bytes: 0,
      upstream_req_bytes: 0, tools_eager: 0, tools_deferred: 0, deferral_turns: 0, rate_limited: 0, cost_usd: 0.01, unpriced_turns: 0,
    }] },
  ] } }));
  await page.route(url => url.pathname === '/api/perf/turns', route => {
    const query = new URL(route.request().url()).searchParams;
    const key = query.get('head') ?? '';
    const used = key === STACK.oauthHead || key === STACK.keyHead;
    return route.fulfill({ json: { since: Number(query.get('since')), n: 1,
      heads: [{ key, label: key, count: used ? 2502 : 0, usage: used ? usage : empty, rows: [] }],
    } });
  });
  const faults = await open(page, 'usage');
  const rows = page.locator('li.uplan');
  const spark = page.getByRole('img', { name: 'Tokens per hour, last 24 hours' });
  await expect(rows.filter({ hasText: why })).toHaveCount(1);
  await expect(rows.filter({ hasText: why }).locator('svg')).toHaveCount(0);
  await expect(rows.filter({ has: spark })).toHaveCount(1);
  await expect(rows.filter({ has: spark })).not.toContainText(why);
  await expect(page.getByRole('main')).not.toContainText('Hourly usage history could not load');
  expect(faults.pageErrors).toEqual([]);
});

test('a cold seven-day page waits for retention before starting a history read', async ({ page }) => {
  const heads = await read<HeadsPayload>(page, '/api/heads');
  let release!: () => void;
  const released = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/api/heads', route => route.fulfill({ json: heads }));
  await page.route('**/api/economics', async route => {
    await released;
    await route.fulfill({ json: { retention_hours: 168, heads: [] } });
  });
  const spans: number[] = [];
  await page.route(url => url.pathname === '/api/perf/turns', route => {
    const query = new URL(route.request().url()).searchParams;
    const key = query.get('head') ?? '';
    const since = Number(query.get('since'));
    const until = Number(query.get('until'));
    spans.push(until - since);
    return route.fulfill({ json: {
      since, n: 1, heads: [{ key, label: key, count: key === STACK.oauthHead ? 2502 : 0,
        usage: key === STACK.oauthHead ? usage : empty, rows: [] }],
    } });
  });
  const headReply = page.waitForResponse(response => new URL(response.url()).pathname === '/api/heads');
  try {
    await open(page, 'usage?window=168');
    await (await headReply).finished();
    await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
    expect(spans, 'retention is pending, so no default day read may start').toEqual([]);
    release();
    const total = page.getByRole('region', { name: 'Usage', exact: true });
    await expect(total.getByRole('heading', { name: 'Requests', exact: true }).locator('..')).toContainText('2,502');
    expect(spans.every(span => span === 168 * 3_600_000)).toBe(true);
  } finally {
    release();
  }
});

test('a settled command keeps fleet totals when its sibling has no counts or aggregates', async ({ page }) => {
  await page.route('**/api/economics', route => route.fulfill({ json: { retention_hours: 168, heads: [] } }));
  await page.route(url => url.pathname === '/api/perf/turns', route => {
    const query = new URL(route.request().url()).searchParams;
    const key = query.get('head') ?? '';
    return route.fulfill({ json: {
      since: Number(query.get('since')), n: 1,
      heads: [{ key, label: key, rows: [], ...(key === STACK.oauthHead ? { count: 2502, usage } : {}) }],
    } });
  });
  await open(page, 'usage?window=168');
  const total = page.getByRole('region', { name: 'Usage', exact: true });
  await expect(total.getByRole('heading', { name: 'Requests', exact: true }).locator('..')).toContainText('At least 2,502');
  await expect(total.getByRole('heading', { name: 'Tokens read in', exact: true }).locator('..')).toContainText('At least 2.50M');
  await expect(total.getByRole('heading', { name: 'API cost, estimated', exact: true }).locator('..')).toContainText('At least $1.48');
  const unavailable = page.locator('.uplan').filter({ has: page.getByText(STACK.soloHead, { exact: true }) });
  await expect(unavailable).toContainText('Request history unavailable');
  await expect(unavailable).not.toContainText('Reading');
});

test('a settled partial history keeps its numbers and never calls itself loading', async ({ page }) => {
  await page.route('**/api/economics', route => route.fulfill({ json: { retention_hours: 168, heads: [] } }));
  await page.route(url => url.pathname === '/api/perf/turns', route => {
    const query = new URL(route.request().url()).searchParams;
    const key = query.get('head') ?? '';
    return route.fulfill({ json: {
      since: Number(query.get('since')), n: 1,
      heads: [{ key, label: key, count: key === STACK.oauthHead ? 2502 : 0,
        usage: key === STACK.oauthHead ? usage : empty, rows: [], skipped_lines: key === STACK.oauthHead ? 2 : 0 }],
    } });
  });
  await open(page, 'usage?window=168');
  const total = page.getByRole('region', { name: 'Usage', exact: true });
  await expect(total.getByRole('heading', { name: 'Requests', exact: true }).locator('..')).toContainText('2,502');
  const ready = page.locator('.uplan').filter({ has: page.getByText(STACK.oauthHead, { exact: true }) });
  await expect(ready).toContainText('90% cached');
  await expect(page.getByText('Reading the usage.', { exact: true })).toHaveCount(0);
  await expect(page.getByText('2 request records could not be read.', { exact: false }).first()).toBeVisible();
});
