// NEW: V4-444 — every own route, including real-data detail pages, observed through two slow poll ticks.
import { test } from '@playwright/test';
import { ROUTES, assertHealthy, open, routePath } from './support';

for (const route of ROUTES) {
  test(route + ' renders against the live daemon', async ({ page }) => {
    const faults = await open(page, await routePath(page, route));
    await page.waitForTimeout(11_000);
    await assertHealthy(page, faults);
  });
}
