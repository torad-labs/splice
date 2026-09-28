// V4-399: a management key pasted into the gate starts the live connection in this tab.
import { expect, test } from '@playwright/test';

test('unlocking through the key form opens events and makes the header Live without reloading', async ({ page }) => {
  const base = process.env.CONSOLE_E2E_BASE;
  const key = process.env.CONSOLE_E2E_KEY;
  if (!base || !key) throw new Error('the isolated console stack did not start');

  let streams = 0;
  page.on('request', (request) => {
    if (new URL(request.url()).pathname === '/api/events') streams += 1;
  });
  await page.goto(`${base}/#/fleet`);
  expect(await page.evaluate(() => localStorage.getItem('myx-mgmt-key') === null)).toBe(true);
  const dialog = page.getByRole('dialog', { name: 'Management key required' });
  await expect(dialog).toBeVisible();
  await expect(page.locator('.myx-rule-connection')).toContainText('Offline');
  expect(streams, 'the stream must not start before a key exists').toBe(0);

  const frame = page.mainFrame();
  let navigations = 0;
  page.on('framenavigated', (navigated) => { if (navigated === frame) navigations += 1; });
  await dialog.getByRole('textbox', { name: 'Key' }).fill(key);
  await dialog.getByRole('button', { name: 'Unlock' }).click();
  await expect.poll(() => streams, { message: 'unlock did not request /api/events' }).toBeGreaterThan(0);
  await expect(page.locator('.myx-rule-connection')).toContainText('Live');
  expect(navigations, 'unlock reloaded the page instead of connecting in place').toBe(0);
});
