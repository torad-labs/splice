// NEW: V4-444 — every own route, including real-data detail pages, observed through two slow poll ticks.
import { expect, test } from '@playwright/test';
import { ROUTES, assertHealthy, open, routePath } from './support';

for (const route of ROUTES) {
  test(route + ' renders against the live daemon', async ({ page }) => {
    const path = await routePath(page, route);
    const faults = await open(page, path);
    await page.waitForTimeout(11_000);
    expect(new URL(page.url()).hash, 'canonical page must stay rendered, not redirect').toBe('#/' + path);
    await assertHealthy(page, faults);
  });
}
