// NEW: V4-444 — /models puts command ordering before the model table, which searches and sorts every served model in place.
// Prices stay visible, and an undeclared price remains distinct from a declared zero.
import { expect, test, type Page } from '@playwright/test';
import { assertHealthy, open } from './support';
import { STACK } from './stack';
import type { ModelsPayload } from '../src/types/models';
import type { HeadsPayload } from '../src/types/core';

/** The stack declares no prices, so one model is given a declared zero and another a real price; the third keeps none. */
async function priced(page: Page): Promise<void> {
  await page.route((url) => url.pathname === '/api/models', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as ModelsPayload;
    for (const head of body.heads) {
      for (const model of head.models) {
        if (model.id === STACK.soloModel) model.rates = { input: 0, output: 0, cache_read: 0 };
        if (model.id === 'e2e/key-model') model.rates = { input: 0.15, output: 0.6, cache_read: 0.003 };
      }
    }
    await route.fulfill({ response, json: body });
  });
}

const hash = (page: Page): string => decodeURIComponent(new URL(page.url()).hash);

for (const width of [1440, 390]) {
  test('Add account names the visible command rather than its internal key at ' + width, async ({ page }) => {
    const label = 'Synthetic visible command';
    await page.setViewportSize({ width, height: 1024 });
    await page.route(url => url.pathname === '/api/heads', async route => {
      const response = await route.fetch();
      const body = await response.json() as HeadsPayload;
      const head = body.heads.find(row => row.key === STACK.oauthHead);
      if (head === undefined) throw new Error('the synthetic OAuth head is missing');
      head.label = label;
      await route.fulfill({ response, json: body });
    });
    const faults = await open(page, 'models/' + STACK.oauthHead);
    await expect(page.getByRole('heading', { level: 1, name: label, exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Add account', exact: true }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('heading')).toHaveText('Add account: ' + label);
    await expect(dialog).toContainText('This signs in the ' + label + ' command.');
    await expect(dialog).toContainText('The new login joins ' + label + '.');
    await expect(dialog).not.toContainText(STACK.oauthHead);
    await expect(dialog.getByRole('textbox', { name: 'Label', exact: true })).toBeFocused();
    expect(await dialog.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await dialog.screenshot({ path: test.info().outputPath('add-account-visible-command-' + width + '.png') });
    await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
    await assertHealthy(page, faults);
    await page.unrouteAll({ behavior: 'wait' });
  });
}

test('command ordering leads the model table, which keeps undeclared prices distinct from zero', async ({ page }) => {
  await priced(page);
  const faults = await open(page, 'models');
  const table = page.getByRole('region', { name: 'Models table', exact: true });
  const rows = table.locator('tbody tr');
  await expect(rows.filter({ hasText: 'E2E Solo Model' })).toContainText('$0.00');
  await expect(rows.filter({ hasText: 'E2E Key Model' })).toContainText('$0.15');
  await expect(rows.filter({ hasText: 'E2E Model' }).filter({ hasNotText: 'Solo' }).filter({ hasNotText: 'Key' })).toContainText('Not declared');
  await expect(page.getByRole('main')).not.toContainText('$0.000');
  const commands = page.getByRole('region', { name: 'Commands', exact: true });
  await expect(commands.locator('li.card')).toHaveCount(3);
  const [above, below] = await Promise.all([commands.boundingBox(), table.boundingBox()]);
  expect(above !== null && below !== null && above.y < below.y).toBe(true);
  await expect(page.getByRole('button', { name: 'Add a command', exact: true })).toBeVisible();
  await assertHealthy(page, faults);
});

test('a sort and a search live in the address, an undeclared price sorts last, and a reload reads the same view', async ({ page }) => {
  await priced(page);
  const faults = await open(page, 'models');
  const table = page.getByRole('region', { name: 'Models table', exact: true });
  await table.getByRole('button', { name: 'Sort by Input', exact: true }).click();
  await expect.poll(() => hash(page)).toBe('#/models?sort=input');
  await expect(table.getByRole('columnheader', { name: 'Sort by Input' })).toHaveAttribute('aria-sort', 'ascending');
  const names = table.locator('tbody th b');
  await expect.poll(() => names.allTextContents()).toEqual(['E2E Solo Model', 'E2E Key Model', 'E2E Model']);
  await table.getByRole('button', { name: 'Sort by Input', exact: true }).click();
  await expect.poll(() => names.allTextContents()).toEqual(['E2E Key Model', 'E2E Solo Model', 'E2E Model']);
  await page.getByRole('searchbox', { name: 'Search models', exact: true }).fill('solo');
  await expect.poll(() => hash(page)).toBe('#/models?q=solo&sort=input&dir=desc');
  await page.reload();
  await expect(names).toHaveText(['E2E Solo Model']);
  await expect(page.getByRole('main')).toContainText('1 of 3 models match.');
  await assertHealthy(page, faults);
});
