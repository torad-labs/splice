// NEW: V4-444 — synthetic key storage/removal on a real api-key head, with no secret echoed into the page.
import { expect, test } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import type { HeadsPayload } from '../src/types/core';
import { STACK } from './stack';
import { assertHealthy, env, open } from './support';

for (const width of [1440, 390]) {
  for (const source of ['file', 'environment', 'store', 'missing'] as const) {
    test('the configured key_file stays exact and visible beside read source ' + source + ' at ' + width, async ({ page }) => {
      const keyFile = '/synthetic/provider configuration/' + 'long-key-directory-'.repeat(12) + '/provider.key';
      const storePath = '/synthetic/distinct-key-store/keys.json';
      await page.setViewportSize({ width, height: 1024 });
      await page.route(url => url.pathname === '/api/auth', route => route.fulfill({ json: {
        [STACK.keyHead]: { kind: 'api-key', login: 'synthetic', present: source !== 'missing', env_var: 'SYNTHETIC_API_KEY', key_file: keyFile, api_key_masked: 'synthetic-mask-not-for-rendering' },
      } }));
      await page.route(url => url.pathname === '/api/keys', route => route.fulfill({ json: {
        path: storePath, keys: [{ name: 'SYNTHETIC_API_KEY', stored: source === 'store', heads: [{ head: STACK.keyHead, source }] }],
      } }));
      const faults = await open(page, 'models/' + STACK.keyHead);
      const key = page.locator('.head-key');
      await expect(key).toHaveCount(1);
      await expect(key.locator('.key-source dd')).toHaveText(({ file: 'Key file', environment: 'Environment', store: 'Key store', missing: 'Nowhere' })[source] ?? source);
      const path = key.getByText(keyFile, { exact: true });
      await expect(path).toBeVisible();
      await expect(key).toContainText('Configured key file');
      await expect(key).not.toContainText(storePath);
      await expect(page.getByRole('main')).not.toContainText('synthetic-mask-not-for-rendering');
      expect(await path.evaluate(element => {
        const range = document.createRange();
        range.selectNodeContents(element);
        return [...range.getClientRects()].every(part => part.left >= -1 && part.right <= innerWidth + 1);
      })).toBe(true);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      await key.screenshot({ path: test.info().outputPath('configured-key-file-' + source + '-' + width + '.png') });
      await assertHealthy(page, faults);
      await page.unrouteAll({ behavior: 'wait' });
    });
  }
}

for (const authKind of ['api-key', 'bearer']) {
  test(`a ${authKind} plan stores and removes its key while reporting its read source without echoing the value`, async ({ page }) => {
    await page.route('**/api/heads', async (route) => {
      const response = await route.fetch();
      const body = await response.json() as HeadsPayload;
      const head = body.heads.find((row) => row.key === STACK.keyHead);
      if (head === undefined) throw new Error('isolated stack has no key head');
      head.authKind = authKind;
      await route.fulfill({ response, json: body });
    });
    const name = 'CONSOLE_E2E_NO_SUCH_KEY';
    const secret = 'sk-synthetic-' + randomUUID();
    await open(page, 'models/' + STACK.keyHead);
    const source = page.locator('dl.key-source dd');
    try {
      await expect(source).toHaveText('Nowhere');
      await expect(page.getByRole('button', { name: 'Remove key', exact: true })).toHaveCount(0);
      await page.getByRole('button', { name: 'Store key', exact: true }).click();
      const dialog = page.getByRole('dialog', { name: 'The key for ' + name, exact: true });
      const field = dialog.getByLabel('New key', { exact: true });
      await field.fill(secret);
      const stored = page.waitForResponse((response) => response.request().method() === 'PUT' &&
        new URL(response.url()).pathname === '/api/keys/' + name);
      await dialog.getByRole('button', { name: 'Save', exact: true }).click();
      expect((await stored).status()).toBe(200);
      await expect(dialog).toHaveCount(0);
      await expect(source).toHaveText('Key store');
      await expect(page.getByRole('status')).toContainText(STACK.keyHead + ' uses the stored key from its next request.');
      expect(await page.content()).not.toContain(secret);
      await page.getByRole('button', { name: 'Replace key', exact: true }).click();
      await expect(page.getByRole('dialog').getByLabel('New key', { exact: true })).toHaveValue('');
      await page.getByRole('dialog').getByRole('button', { name: 'Cancel', exact: true }).click();
      await page.getByRole('button', { name: 'Remove key', exact: true }).click();
      const removed = page.waitForResponse((response) => response.request().method() === 'DELETE' &&
        new URL(response.url()).pathname === '/api/keys/' + name);
      await page.getByRole('dialog').getByRole('button', { name: 'Remove ' + name, exact: true }).click();
      expect((await removed).status()).toBe(200);
      await expect(source).toHaveText('Nowhere');
      await expect(page.getByRole('status')).toContainText(STACK.keyHead + ' has no key now.');
    } finally {
      const response = await page.request.delete(env('CONSOLE_E2E_BASE') + '/api/keys/' + name, {
        headers: { Authorization: 'Bearer ' + env('CONSOLE_E2E_KEY') },
      });
      expect([200, 404], 'cleanup accepts an already removed synthetic key').toContain(response.status());
    }
  });
}
