// NEW: V4-444 — synthetic key storage/removal on a real api-key head, with no secret echoed into the page.
import { expect, test } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { STACK } from './stack';
import { env, open } from './support';

test('an API-key plan stores and removes its key while reporting its read source without echoing the value', async ({ page }) => {
  const name = 'CONSOLE_E2E_NO_SUCH_KEY';
  const secret = 'sk-synthetic-' + randomUUID();
  await open(page, 'fleet/' + STACK.keyHead);
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
