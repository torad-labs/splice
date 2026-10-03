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
