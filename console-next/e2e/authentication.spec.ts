// NEW: V4-444 — pasted-key recovery, event continuity and one shell across source-derived routes.
import { expect, test } from '@playwright/test';
import { ROUTES, env, routePath } from './support';

for (const route of ROUTES) {
  test(route + ': a refused key recovers every shell read and events in place', async ({ page }) => {
    const errors: string[] = [];
    const reads = new Set<string>();
    let streams = 0;
    let documents = 0;
    page.on('pageerror', (error) => errors.push(error.message));
    page.on('request', (request) => {
      if (request.isNavigationRequest() && request.frame() === page.mainFrame()) documents += 1;
      if (new URL(request.url()).pathname === '/api/events') streams += 1;
    });
    page.on('response', (response) => {
      if (response.status() === 200) reads.add(new URL(response.url()).pathname);
    });
    // Detail routes are resolved through isolated-daemon data without pre-unlocking this document.
    const probe = await page.context().newPage();
    const path = await routePath(probe, route);
    await probe.close();
    await page.goto(env('CONSOLE_E2E_BASE') + '/#/' + path);
    await expect(page.getByText('Paste the management key to open the console.', { exact: true })).toBeVisible();
    expect(await page.evaluate(() => localStorage.getItem('myx-mgmt-key'))).toBeNull();
    expect(streams, 'events must not start without a management key').toBe(0);
    const originalDocuments = documents;
    const field = page.getByLabel('Management key', { exact: true });
    const refused = page.waitForResponse((response) => response.status() === 401 &&
      new URL(response.url()).pathname.startsWith('/api/'));
    await field.fill('synthetic-refused-management-key');
    await page.getByRole('button', { name: 'Unlock', exact: true }).click();
    await refused;
    await expect(field).toBeVisible();
    reads.clear();
    const beforeStreams = streams;
    await field.fill(env('CONSOLE_E2E_KEY'));
    await page.getByRole('button', { name: 'Unlock', exact: true }).click();
    await expect(page.getByRole('navigation', { name: 'Pages', exact: true })).toHaveCount(1);
    await expect(page.getByRole('main')).toHaveCount(1);
    const endpoints = ['/api/status', '/api/config', ...(path === 'accounts' ? ['/api/accounts'] : [])];
    for (const endpoint of endpoints) {
      await expect.poll(() => reads.has(endpoint), { message: endpoint + ' must recover after the refused key' }).toBe(true);
    }
    await expect.poll(() => streams).toBeGreaterThan(beforeStreams);
    await expect(page.getByText('Daemon running', { exact: true })).toBeVisible();
    await expect(page.getByRole('main')).not.toContainText('management key required');
    expect(documents, 'unlock must not reload the document').toBe(originalDocuments);
    expect(await page.evaluate(() => localStorage.getItem('myx-mgmt-key'))).toBe(env('CONSOLE_E2E_KEY'));
    expect(errors, 'unlock must not throw in the document').toEqual([]);
  });
}

test('two unlocks leave one shell through forward and reverse source-derived routes', async ({ page }) => {
  const warnings: string[] = [];
  page.on('console', (message) => {
    if (/two children with the same key/i.test(message.text())) warnings.push(message.text());
  });
  await page.goto(env('CONSOLE_E2E_BASE') + '/#/accounts');
  const field = page.getByLabel('Management key', { exact: true });
  await expect(field).toBeVisible();
  const refused = page.waitForResponse((response) => response.status() === 401);
  await field.fill('synthetic-refused-management-key');
  await page.getByRole('button', { name: 'Unlock', exact: true }).click();
  await refused;
  await expect(field).toBeVisible();
  await field.fill(env('CONSOLE_E2E_KEY'));
  await page.getByRole('button', { name: 'Unlock', exact: true }).click();
  await expect(page.getByRole('navigation', { name: 'Pages', exact: true })).toBeVisible();
  for (const route of [...ROUTES, ...ROUTES.slice().reverse()]) {
    const path = await routePath(page, route);
    await page.evaluate((hash) => { location.hash = hash; }, '#/' + path);
    await expect(page).toHaveURL(new RegExp('#/' + path.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '$'));
    await expect(page.getByRole('navigation', { name: 'Pages', exact: true })).toHaveCount(1);
    await expect(page.locator('aside.side')).toHaveCount(1);
    await expect(page.getByRole('main')).toHaveCount(1);
    const column = await page.getByRole('main').boundingBox();
    expect(column?.width ?? 0, route + ': page column must not be squeezed by a duplicate shell').toBeGreaterThan(600);
    expect(warnings, route + ': duplicate React keys').toEqual([]);
  }
});
