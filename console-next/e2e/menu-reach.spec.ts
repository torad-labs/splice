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
  const trigger = health.getByRole('button', { name: 'Plan', exact: true });
  await trigger.evaluate((element) => element.scrollIntoView({ block: 'end' }));
  await trigger.click();
  const last = 'Synthetic plan ' + String(EXTRA - 1).padStart(2, '0');
  const menu = page.getByRole('menu');
  await expect(menu).toBeVisible();
  const box = await menu.boundingBox();
  expect(box, 'the open menu has a box').not.toBeNull();
  expect((box?.y ?? 0) + (box?.height ?? Infinity), 'the menu ends inside the window').toBeLessThanOrEqual(600);
  expect(box?.y ?? -1, 'the menu starts inside the window').toBeGreaterThanOrEqual(0);
  await page.getByRole('menuitemradio', { name: last, exact: true }).click();
  await expect(trigger).toHaveText(last);
  expect(faults.pageErrors).toEqual([]);
});
