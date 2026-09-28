// V4-401: a key pasted into the gate re-runs every read the missing key refused, on every page, without
// a reload. Marlin's walk of V4-399 on bb54736ea: /api/auth, /api/accounts and /api/usage were asked once
// before the unlock (401) and not again, so Needs you said 'Open items 0' beside six sources reading
// 'Failed · management key required' until a reload.
//
// THE DENOMINATOR IS THE SOURCE: the pages are the directories the router globs (src/app/pages.ts), read
// here the same way console.spec.ts reads them, so a page added to the console is walked with no edit
// to this file.
import { expect, test } from '@playwright/test';
import { existsSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const PAGES_DIR = join(dirname(fileURLToPath(import.meta.url)), '../../src/pages');
const PAGES = readdirSync(PAGES_DIR, { withFileTypes: true })
  .filter((entry) => entry.isDirectory() && existsSync(join(PAGES_DIR, entry.name, 'index.tsx')))
  .map((entry) => entry.name)
  .sort();

/** The reads the status strip makes on every page, and the three Marlin saw stay refused. */
const STRIP_READS = ['/api/auth', '/api/accounts', '/api/usage'];

/** What a read's failure says when the key was missing. */
const REFUSED = 'management key required';

function env(name: string): string {
  const value = process.env[name];
  if (value === undefined || value === '') throw new Error(`${name} is unset — the global setup did not start the stack`);
  return value;
}

for (const name of PAGES) {
  test(`${name}: unlocking through the form re-runs every read the missing key refused`, async ({ browser }) => {
    const context = await browser.newContext();
    const page = await context.newPage();
    const seen = new Map<string, number>();
    page.on('request', (request) => {
      const path = new URL(request.url()).pathname;
      seen.set(path, (seen.get(path) ?? 0) + 1);
    });
    await page.goto(`${env('CONSOLE_E2E_BASE')}/#/${name}`);
    const dialog = page.getByRole('dialog', { name: 'Management key required' });
    await expect(dialog).toBeVisible();
    expect(await page.evaluate(() => localStorage.getItem('myx-mgmt-key') === null)).toBe(true);
    await expect.poll(() => STRIP_READS.every((path) => (seen.get(path) ?? 0) > 0), {
      message: 'the strip asked for its reads before the unlock',
    }).toBe(true);

    const before = new Map(seen);
    let navigations = 0;
    const frame = page.mainFrame();
    page.on('framenavigated', (navigated) => { if (navigated === frame) navigations += 1; });
    await dialog.getByRole('textbox', { name: 'Key' }).fill(env('CONSOLE_E2E_KEY'));
    await dialog.getByRole('button', { name: 'Unlock' }).click();

    for (const path of STRIP_READS) {
      await expect.poll(() => (seen.get(path) ?? 0) - (before.get(path) ?? 0), {
        message: `${path} was not read again after the unlock`,
      }).toBeGreaterThan(0);
    }
    await expect.poll(() => page.locator('body').innerText(), {
      message: `${name} still says a read failed for want of the key`,
    }).not.toContain(REFUSED);
    expect(navigations, 'unlock reloaded the page instead of re-reading in place').toBe(0);
    await context.close();
  });
}
