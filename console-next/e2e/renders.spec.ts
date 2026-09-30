// NEW: V4-444 — every canonical page is observed through two slow poll ticks.
import { test } from '@playwright/test';
import { PAGES, assertHealthy, open } from './support';

for (const path of PAGES) {
  test(path + ' renders against the live daemon', async ({ page }) => {
    const faults = await open(page, path);
    await page.waitForTimeout(11_000);
    await assertHealthy(page, faults);
  });
}
