// NEW: V4-444 — the daemon's offered model ids are the exact write and restart answer.
import { expect, test } from '@playwright/test';
import type { AddModelOffers } from '../src/types/add';
import { open, read } from './support';
import type { ModelsPayload } from '../src/types/models';
import { STACK } from './stack';

test('Add models preserves offered upstream ids and prints the persisted restart answer', async ({ page }) => {
  const ids = ['synthetic/provider-model-one', 'synthetic/provider-model-two'];
  const offers: AddModelOffers = { path: '/synthetic/splice.toml', heads: [{
    head: STACK.keyHead, provider: 'openrouter',
    models: ids.map((id) => ({ id, label: id, context_window: 123456, slots: [] })),
  }] };
  await page.route('**/api/add-model', (route) => {
    if (route.request().method() === 'GET') return route.fulfill({ json: offers });
    expect(route.request().postDataJSON()).toEqual({ head: STACK.keyHead, models: ids });
    return route.fulfill({ json: { head: STACK.keyHead, path: offers.path, added: ids, restart: { status: 'draining' } } });
  });
  const faults = await open(page, 'fleet/' + STACK.keyHead);
  await page.getByRole('button', { name: 'Models', exact: true }).click();
  await page.getByRole('button', { name: 'Add models', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('listitem')).toHaveCount(ids.length);
  for (const id of ids) await dialog.getByRole('checkbox', { name: 'Add ' + id, exact: true }).check();
  await expect(dialog).not.toContainText(STACK.model);
  const write = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/add-model' && response.request().method() === 'POST');
  await dialog.getByRole('button', { name: 'Add 2 models and restart', exact: true }).click();
  expect((await write).ok()).toBe(true);
  await expect(dialog).toContainText('Added ' + ids.join(', ') + '.');
  await expect(dialog).toContainText('Written to ' + offers.path + '.');
  await expect(dialog.getByRole('status')).toHaveText('splice is restarting; the command appears once it is back.');
  expect(faults.pageErrors).toEqual([]);
  await page.unrouteAll({ behavior: 'wait' });
});

test('the real catalogue offers only models outside the declared roster and writes the selected upstream id', async ({ page }) => {
  const offers = await read<AddModelOffers>(page, '/api/add-model');
  const offer = offers.heads.find((head) => head.head === STACK.keyHead);
  expect(offer).toBeDefined();
  expect(offer?.models.length).toBeGreaterThan(0);
  const models = await read<ModelsPayload>(page, '/api/models');
  const roster = models.heads.find((head) => head.head === STACK.keyHead)?.models.map((model) => model.id) ?? [];
  expect(roster.length).toBeGreaterThan(0);
  for (const model of offer?.models ?? []) expect(roster).not.toContain(model.id);
  const picked = offer?.models[0]?.id ?? '';
  const writes: unknown[] = [];
  await page.route('**/api/add-model', (route) => {
    if (route.request().method() !== 'POST') return route.fallback();
    writes.push(route.request().postDataJSON());
    return route.fulfill({ json: { head: STACK.keyHead, path: offers.path, added: [picked], restart: { status: 'draining' } } });
  });
  const faults = await open(page, 'fleet/' + STACK.keyHead + '?tab=models');
  await page.getByRole('button', { name: 'Add models', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('listitem')).toHaveCount(offer?.models.length ?? 0);
  for (const id of roster) await expect(dialog.getByRole('checkbox', { name: 'Add ' + id, exact: true })).toHaveCount(0);
  await dialog.getByRole('checkbox', { name: 'Add ' + picked, exact: true }).check();
  await dialog.getByRole('button', { name: 'Add 1 model and restart', exact: true }).click();
  await expect.poll(() => writes).toEqual([{ head: STACK.keyHead, models: [picked] }]);
  await expect(dialog).toContainText('Added ' + picked + '.');
  await expect(dialog).toContainText('Written to ' + offers.path + '.');
  await expect(dialog.getByRole('status')).toHaveText('splice is restarting; the command appears once it is back.');
  expect(faults.pageErrors).toEqual([]);
  await page.unrouteAll({ behavior: 'wait' });
});

test('each plan keeps its actual pinned identity and displays its separately declared windows', async ({ page }) => {
  const catalog = await read<ModelsPayload>(page, '/api/models');
  for (const head of [STACK.oauthHead, STACK.soloHead]) {
    const declared = catalog.heads.find((entry) => entry.head === head);
    expect(declared).toBeDefined();
    const pinned = declared?.models.find((model) => model.pinned);
    expect(pinned?.id).toBe(declared?.pinned_model);
    const faults = await open(page, 'fleet/' + head + '?tab=models');
    const every = page.getByRole('region', { name: 'Every model', exact: true });
    const row = every.getByRole('listitem').filter({ has: page.locator('code').getByText(pinned?.id ?? '', { exact: true }) });
    await expect(row).toContainText(pinned?.label ?? '');
    await expect(row.getByText('Pinned', { exact: true })).toBeVisible();
    expect(pinned?.context_window).toBe(head === STACK.oauthHead ? STACK.headWindow : 400000);
    await expect(row).toContainText((pinned?.context_window ?? 0) / 1000 + 'k tokens');
    if (head === STACK.oauthHead) {
      const windows = page.getByRole('region', { name: 'Windows this command declares', exact: true });
      await expect(windows).toContainText('Plan window');
      await expect(windows).toContainText(STACK.headWindow / 1000 + 'k tokens');
      await expect(row).toContainText('head setting');
      await expect(windows).toContainText('Provider default');
    }
    expect(faults.pageErrors).toEqual([]);
  }
});
