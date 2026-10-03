// NEW: V4-444 — /models opens on one table of every model the commands serve, searched and sorted in place, with the command cards
// below it. The persona walk of 36218a37c found the prices behind each command's second tab, and "No price declared" beside "$0.000".
import { expect, test, type Page } from '@playwright/test';
import { assertHealthy, open } from './support';
import { STACK } from './stack';
import type { ModelsPayload } from '../src/types/models';

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

test('the table leads the page, says an undeclared price in words, and keeps the command cards below it', async ({ page }) => {
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
  const [above, below] = await Promise.all([table.boundingBox(), commands.boundingBox()]);
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
