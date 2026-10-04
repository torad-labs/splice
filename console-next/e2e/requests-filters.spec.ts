// NEW: V4-444 — the Requests filters run on the daemon and live in the address, so a link opens the same rows. A filter
// run in the browser over the newest slice listed no failure while the header counted 480 (the persona walk of 36218a37c).
import { expect, test, type Page } from '@playwright/test';
import { assertHealthy, env, open } from './support';
import { driveOneTurn, STACK } from './stack';

/** The query of every turns read the page sends from now on, across reloads. */
function turnsAsks(page: Page): URLSearchParams[] {
  const asks: URLSearchParams[] = [];
  page.on('request', (request) => {
    const url = new URL(request.url());
    if (url.pathname === '/api/perf/turns') asks.push(url.searchParams);
  });
  return asks;
}

const hash = (page: Page): string => decodeURIComponent(new URL(page.url()).hash);

test('Failed asks the daemon for the failed requests, and the address opens the same view after a reload', async ({ page }) => {
  const asks = turnsAsks(page);
  const faults = await open(page, 'requests');
  const show = page.getByRole('group', { name: 'Show', exact: true });
  await show.getByRole('button', { name: 'Failed', exact: true }).click();
  await expect.poll(() => hash(page)).toBe('#/requests?status=failed');
  await expect.poll(() => asks.some((ask) => ask.get('outcome') === 'failed' && ask.get('local') === '0')).toBe(true);
  await page.reload();
  await expect(show.getByRole('button', { name: 'Failed', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByRole('main')).not.toContainText('Reading the requests.');
  await assertHealthy(page, faults);
});

test('stops stay out of failure counts and have a separate reloadable view with the whole recorded reason', async ({ page }) => {
  const at = Date.now();
  const outcomes = ['ok', '?', 'client_abort', 'error:cancelled', 'error:stopped', 'error:rate-limited'];
  const rows = outcomes.map((outcome, index) => ({
    ts: at - index, model: STACK.soloModel, outcome, compact: false,
    session: null, account: null, cache_cold: null, turn: outcome === 'error:rate-limited' ? 'synthetic-limit' : null,
    session_id: null, response_message_id: null, total: 20,
  }));
  await page.route('**/api/perf/summary?*', route => route.fulfill({ json: { window: '1h', heads: [{
    key: STACK.soloHead, label: STACK.soloHead, window: '1h', count: rows.length, empty: false,
    coverage_known: true, clamped: false, covers_ms: 3_600_000,
    outcomes: Object.fromEntries(outcomes.map(outcome => [outcome, 1])),
  }] } }));
  const asks = turnsAsks(page);
  await page.route(url => url.pathname === '/api/perf/turns', route => {
    const query = new URL(route.request().url()).searchParams;
    const key = query.get('head') ?? '';
    const matching = key !== STACK.soloHead ? [] : query.get('outcome') === 'stopped'
      ? [rows[2], rows[4]] : query.get('outcome') === 'failed' ? [rows[3], rows[5]] : rows;
    return route.fulfill({ json: { since: Number(query.get('since')), n: 200,
      heads: [{ key, label: key, count: matching.length, rows: matching }],
    } });
  });
  const sentence = 'rate limit reached; retry after the named reset, with the same session.';
  await page.route('**/api/heads/*/trace?turn=*', route => route.fulfill({ json: {
    head: STACK.soloHead,
    turn: { id: 'synthetic-limit', ts: at - 5, session: null, model: STACK.soloModel, compact: false,
      open: false, outcome: 'error:rate-limited', failure_sentence: sentence, rounds: 1, attempts: 1, total_ms: 20 },
    records: [],
  } }));
  const faults = await open(page, 'requests');
  await expect(page.locator('.page-head .lede')).toContainText('Two failed');
  await expect(page.locator('.plan .bad')).toHaveText('2');
  await expect(page.locator('.turn')).toHaveCount(6);
  await expect(page.locator('.turn.failed')).toHaveCount(2);
  const show = page.getByRole('group', { name: 'Show', exact: true });
  await show.getByRole('button', { name: 'Stopped', exact: true }).click();
  await expect.poll(() => hash(page)).toBe('#/requests?status=stopped');
  await expect.poll(() => asks.some(ask => ask.get('outcome') === 'stopped' && ask.get('local') === '0')).toBe(true);
  await expect(page.locator('.turn')).toHaveCount(2);
  await expect(page.locator('.turn.failed')).toHaveCount(0);
  await expect(page.locator('.turn .state')).toHaveText(['Stopped', 'Stopped']);
  await page.reload();
  await expect(show.getByRole('button', { name: 'Stopped', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('.turn')).toHaveCount(2);
  await show.getByRole('button', { name: 'Failed', exact: true }).click();
  await expect(page.locator('.turn')).toHaveCount(2);
  await expect(page.locator('.turn .state')).toHaveText(['Cancelled', 'Rate limited']);
  await page.locator('.turn').filter({ hasText: 'Rate limited' }).locator('h3 a').click();
  await expect(page.locator('.failure-sentence')).toHaveText('Rate limit reached; retry after the named reset, with the same session.');
  await assertHealthy(page, faults);
});

test('the headline reads the pooled request timing while command bars keep their own percentiles', async ({ page }) => {
  const commands = [
    { key: 'busy', count: 100, first: 100 },
    { key: 'sparse', count: 1, first: 10_000 },
    { key: 'slower', count: 1, first: 20_000 },
  ];
  await page.route('**/api/perf/summary?*', route => route.fulfill({ json: {
    window: '1h',
    time_before_first_byte_ms: { count: 102, p50: 100, p95: 100, max: 20_000 },
    heads: commands.map(command => ({
      key: command.key, label: command.key, window: '1h', count: command.count, empty: false,
      coverage_known: true, clamped: false, covers_ms: 3_600_000, outcomes: { ok: command.count },
      time_before_first_byte_ms: { count: command.count, p50: command.first, p95: command.first, max: command.first },
    })),
  } }));
  const faults = await open(page, 'requests');
  await expect(page.locator('.page-head .lede')).toHaveText('102 requests in the last hour. None failed, and the typical first word came back in 100 ms.');
  await expect(page.locator('.plan-first span')).toHaveText([
    '100 ms typical · 100 ms slowest', '20.0 s typical · 20.0 s slowest', '10.0 s typical · 10.0 s slowest',
  ]);
  await assertHealthy(page, faults);
});

test('a request\'s model opens the requests on that model, and its chip returns to them all', async ({ page }) => {
  const asks = turnsAsks(page);
  const faults = await open(page, 'requests');
  await driveOneTurn(Number(env('CONSOLE_E2E_SOLO_PORT')), env('CONSOLE_E2E_KEY'), undefined, STACK.soloModel);
  const row = page.locator('.turn').filter({ has: page.getByRole('link', { name: STACK.soloModel, exact: true }) }).first();
  await expect(row).toBeVisible({ timeout: 15_000 });
  await row.getByRole('link', { name: STACK.soloModel, exact: true }).click();
  await expect.poll(() => hash(page)).toBe('#/requests?model=' + STACK.soloModel);
  await expect.poll(() => asks.some((ask) => ask.get('model') === STACK.soloModel)).toBe(true);
  await expect(page.locator('.turn').first()).toBeVisible();
  for (const model of await page.locator('.turn .sub').allInnerTexts()) expect(model).toContain(STACK.soloModel);
  await page.getByRole('list', { name: 'Showing only', exact: true }).getByRole('link').click();
  await expect.poll(() => hash(page)).toBe('#/requests');
  await assertHealthy(page, faults);
});

test('Usage\'s link with since, until and a command reads that span on that command alone, and says how many it holds', async ({ page }) => {
  await driveOneTurn(Number(env('CONSOLE_E2E_SOLO_PORT')), env('CONSOLE_E2E_KEY'), undefined, STACK.soloModel);
  const until = Date.now() + 120_000;
  const since = until - 3_720_000;
  const asks = turnsAsks(page);
  const faults = await open(page, `requests?since=${since}&until=${until}&head=${STACK.soloHead}`);
  await expect(page.locator('.page-head .lede')).toHaveText(/^[\d,]+ requests? from /, { timeout: 15_000 });
  expect(asks.length).toBeGreaterThan(0);
  for (const ask of asks) expect([ask.get('head'), ask.get('since'), ask.get('until')]).toEqual([STACK.soloHead, String(since), String(until)]);
  await assertHealthy(page, faults);
});

test('a link the page cannot read says which part, and reads no requests', async ({ page }) => {
  const asks = turnsAsks(page);
  await open(page, 'requests?day=2026-02-31');
  await expect(page.getByRole('main').getByRole('alert')).toHaveText('This link\'s day, "2026-02-31", is not one this page can read.');
  expect(asks).toEqual([]);
});
