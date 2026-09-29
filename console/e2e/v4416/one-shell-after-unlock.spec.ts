// V4-416: after a key is handed over behind the gate, moving by hash change leaves one sidebar and one
// page column. Marlin's walk of 3839fcc06: unlock, then open a turn's detail on Turns (and Teams), and the
// console drew two sidebars and a main column about 280 px wide, with 'Activity' wrapping one letter per
// line, until a reload. V4-401 keyed the sidebar and the page column with the same `unlocks` count, and
// React keeps or duplicates siblings that share a key.
//
// TWO UNLOCKS, BECAUSE ONE DID NOT SHOW IT: a wrong key is an unlock too (it moves the count and the
// reads run again, then the 401 puts the gate back), so the walk hands over the wrong key first and the
// right one second. Every page is then visited by hash change, and the shell is counted after each.
//
// THE DENOMINATOR IS THE SOURCE: the pages are the directories the router globs (src/app/pages.ts),
// read here the way unlock-rereads.spec.ts reads them.
//
// The duplicate-key warning itself is React's development build only; the bundle the suite serves is
// the production one, where the observable is the doubled shell counted below. The warning is watched
// as well, so a run against a development bundle (CONSOLE_E2E_BUNDLE=dist after `NODE_ENV=development
// vite build`) fails on the sentence React prints.
import { expect, test } from '@playwright/test';
import { existsSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const PAGES_DIR = join(dirname(fileURLToPath(import.meta.url)), '../../src/pages');
const PAGES = readdirSync(PAGES_DIR, { withFileTypes: true })
  .filter((entry) => entry.isDirectory() && existsSync(join(PAGES_DIR, entry.name, 'index.tsx')))
  .map((entry) => entry.name)
  .sort();

const DUPLICATE_KEY = /two children with the same key/i;

function env(name: string): string {
  const value = process.env[name];
  if (value === undefined || value === '') throw new Error(`${name} is unset — the global setup did not start the stack`);
  return value;
}

test('two unlocks, then every page by hash change: one sidebar, one page column, no duplicate-key warning', async ({ browser }) => {
  const context = await browser.newContext();
  const page = await context.newPage();
  const warnings: string[] = [];
  page.on('console', (message) => {
    if (DUPLICATE_KEY.test(message.text())) warnings.push(message.text());
  });
  await page.goto(`${env('CONSOLE_E2E_BASE')}/#/${PAGES[0]}`);
  const dialog = page.getByRole('dialog', { name: 'Management key required' });
  await expect(dialog).toBeVisible();

  await dialog.getByRole('textbox', { name: 'Key' }).fill('not-the-key');
  await dialog.getByRole('button', { name: 'Unlock' }).click();
  await expect(dialog.getByText('That key was refused.')).toBeVisible();

  await dialog.getByRole('textbox', { name: 'Key' }).fill(env('CONSOLE_E2E_KEY'));
  await dialog.getByRole('button', { name: 'Unlock' }).click();
  await expect(dialog).toBeHidden();

  const shell = async (name: string) => {
    await page.evaluate((hash) => { window.location.hash = hash; }, `#/${name}`);
    await expect(page.locator('main.myx-console-page')).toBeVisible();
    await expect.poll(() => page.evaluate(() => window.location.hash)).toBe(`#/${name}`);
    expect(warnings, `${name}: React reported siblings sharing a key`).toEqual([]);
    await expect(page.locator('aside.myx-side'), `${name} drew a second sidebar`).toHaveCount(1);
    await expect(page.locator('.myx-console-main'), `${name} drew a second page column`).toHaveCount(1);
    await expect(page.locator('main.myx-console-page'), `${name} drew a second page`).toHaveCount(1);
    const column = await page.locator('.myx-console-main').boundingBox();
    expect(column?.width ?? 0, `${name}: the page column is squeezed`).toBeGreaterThan(600);
  };

  for (const name of [...PAGES, ...PAGES.slice().reverse()]) await shell(name);
  await context.close();
});
