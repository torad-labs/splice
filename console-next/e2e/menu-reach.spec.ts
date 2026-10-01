// NEW: V4-444 — a menu taller than the space beside its trigger scrolls inside itself, so every option can be reached by the pointer.
import { expect, test } from '@playwright/test';
import type { HeadsPayload } from '../src/types/core';
import { STACK } from './stack';
import { open } from './support';

const EXTRA = 14;

test('a Select with more options than the window holds lets the last one be clicked from a trigger at the bottom edge', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 600 });
  await page.route('**/api/heads', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as HeadsPayload;
    const first = body.heads.find((head) => head.key === STACK.oauthHead);
    if (first === undefined) throw new Error('synthetic OAuth head missing');
    const extra = Array.from({ length: EXTRA }, (_, at) => ({ ...first, key: 'synthetic-plan-' + at, label: 'Synthetic plan ' + String(at).padStart(2, '0') }));
    await route.fulfill({ response, json: { ...body, heads: [...body.heads, ...extra] } });
  });
  const faults = await open(page, 'settings/health');
  const health = page.getByRole('region', { name: 'Health', exact: true });
  const trigger = health.getByRole('button', { name: 'Command', exact: true });
  await trigger.evaluate((element) => element.scrollIntoView({ block: 'end' }));
  await trigger.click();
  const last = 'Synthetic plan ' + String(EXTRA - 1).padStart(2, '0');
  const menu = page.getByRole('menu');
  await expect(menu).toBeVisible();
  await expect.poll(async () => {
    const box = await menu.boundingBox();
    return box !== null && box.y >= 0 && box.y + box.height <= 600;
  }, { message: 'the positioned menu must fit inside the window' }).toBe(true);
  const beforeScroll = await page.evaluate(() => window.scrollY);
  await page.mouse.move(20, 300);
  await page.mouse.wheel(0, 120);
  await expect.poll(() => page.evaluate(() => window.scrollY), {
    message: 'a choice menu must not lock the Settings page scroll',
  }).toBeGreaterThan(beforeScroll);
  await page.getByRole('menuitemradio', { name: last, exact: true }).click();
  await expect(trigger).toHaveText(last);
  expect(faults.pageErrors).toEqual([]);
  // The page re-reads /api/heads on its own timer; a read still in flight when the test ends would throw "route.fetch: Test ended"
  // into whichever test runs next.
  await page.unrouteAll({ behavior: 'ignoreErrors' });
});
