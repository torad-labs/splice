// Every console page, in a browser, against the live stack e2e/stack.ts booted.
//
// THE DENOMINATOR IS THE SOURCE: the pages are the directories the router globs
// (src/app/pages.ts reads pages/*/index.tsx at build time), read here the same way, so a page added
// to the console is visited with no edit to this file.
//
// EACH PAGE FAILS ON WHAT THE OPERATOR WOULD SEE GO WRONG, measured 2026-09-22 against the live
// daemon: React Router's error screen in place of the page (accounts, `e.windows is not iterable`),
// a read the daemon refused (turns, 400 on every poll), and a value the console never received
// printed as `undefined` (doctor's rollback). Soft assertions, so one run names every fault class on
// every page instead of stopping at the first.
import { expect, test, type Page } from '@playwright/test';
import { existsSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { STACK } from './stack';

const PAGES_DIR = join(dirname(fileURLToPath(import.meta.url)), '../src/pages');
const PAGES = readdirSync(PAGES_DIR, { withFileTypes: true })
  .filter((entry) => entry.isDirectory() && existsSync(join(PAGES_DIR, entry.name, 'index.tsx')))
  .map((entry) => entry.name)
  .sort();

/** Where the console keeps the management key (src/shared/api, KEY_STORAGE). A rename there leaves
 *  every page on the lock screen, which the lock-screen assertion below reports by name. */
const KEY_STORAGE = 'myx-mgmt-key';

/** Long enough for the slowest page poll (turns, every 5 s) to fire twice: a read that fails on its
 *  second tick, not its first, is still seen. */
const SETTLE_MS = 11_000;

/** Text that only appears when a value the console expected never arrived. */
const LEAKED_VALUE = /\bundefined\b|\bNaN\b|\[object Object\]/;

function env(name: string): string {
  const value = process.env[name];
  if (value === undefined || value === '') throw new Error(`${name} is unset — the global setup did not start the stack`);
  return value;
}

interface Faults {
  pageErrors: string[];
  consoleErrors: string[];
  failedReads: string[];
}

function watch(page: Page): Faults {
  const faults: Faults = { pageErrors: [], consoleErrors: [], failedReads: [] };
  page.on('pageerror', (error) => faults.pageErrors.push(error.message));
  page.on('console', (message) => {
    if (message.type() === 'error') faults.consoleErrors.push(message.text());
  });
  page.on('response', (response) => {
    const url = new URL(response.url());
    if (url.pathname.startsWith('/api/') && response.status() >= 400) {
      faults.failedReads.push(`${response.status()} ${response.request().method()} ${url.pathname}${url.search}`);
    }
  });
  return faults;
}

async function unlock(page: Page): Promise<void> {
  await page.addInitScript(([storage, key]) => localStorage.setItem(storage, key), [KEY_STORAGE, env('CONSOLE_E2E_KEY')]);
}

async function open(page: Page, name: string): Promise<Faults> {
  const faults = watch(page);
  await unlock(page);
  await page.goto(`${env('CONSOLE_E2E_BASE')}/#/${name}`);
  await expect(page.getByRole('navigation', { name: 'bays' }), 'the console shell did not render').toBeVisible();
  return faults;
}

test('the page set comes from the source', () => {
  expect(PAGES, `no page directories under ${PAGES_DIR}`).toContain('fleet');
});

test('the management key unlocks the console', async ({ page }) => {
  await page.goto(`${env('CONSOLE_E2E_BASE')}/#/fleet`);
  await expect(page.getByText('management key required').first()).toBeVisible();
  await page.getByRole('textbox', { name: 'key' }).fill(env('CONSOLE_E2E_KEY'));
  await page.getByRole('button', { name: 'unlock' }).click();
  await expect(page.locator('main')).toContainText(STACK.oauthHead);
  await expect(page.locator('main')).toContainText(STACK.keyHead);
});

for (const name of PAGES) {
  test(`${name} renders against the live daemon`, async ({ page }) => {
    const faults = await open(page, name);
    await page.waitForTimeout(SETTLE_MS);
    const main = page.locator('main');
    expect.soft(await page.getByText('Unexpected Application Error').count(), 'the router error screen replaced the page').toBe(0);
    expect.soft(await page.getByText('management key required').count(), 'the console is still locked').toBe(0);
    const text = (await main.count()) > 0 ? await main.innerText() : '';
    expect.soft(text.trim().length, 'main printed nothing').toBeGreaterThan(0);
    expect.soft(text.match(LEAKED_VALUE)?.[0] ?? null, 'a value the console never received was printed').toBeNull();
    expect.soft(faults.pageErrors, 'uncaught page errors').toEqual([]);
    expect.soft([...new Set(faults.failedReads)], 'reads the daemon refused').toEqual([]);
    expect.soft(faults.consoleErrors, 'console errors').toEqual([]);
  });
}

test('turns lists the turn the stack drove through a real head', async ({ page }) => {
  await open(page, 'turns');
  await expect(page.locator('main')).toContainText(STACK.model, { timeout: 15_000 });
  await expect(page.locator('main')).toContainText(STACK.oauthHead);
});

test('accounts shows the OAuth account with the windows its provider reported', async ({ page }) => {
  await open(page, 'accounts');
  const main = page.locator('main');
  await expect(main).toContainText(STACK.oauthHead, { timeout: 15_000 });
  await expect(main).toContainText(`${STACK.fiveHourUsedPercent}%`);
  await expect(main).toContainText(`${STACK.sevenDayUsedPercent}%`);
  await expect(main).toContainText(STACK.plan);
});

test('fleet shows each head\'s pinned model from the catalog', async ({ page }) => {
  await open(page, 'fleet');
  const main = page.locator('main');
  await expect(main).toContainText(STACK.model, { timeout: 15_000 });
  await expect(main).toContainText(STACK.soloModel);
});

test('models opens a model with the head windows its topology declares', async ({ page }) => {
  const faults = await open(page, 'models');
  await page.getByRole('button', { name: `open model ${STACK.model}` }).first().click();
  const detail = page.getByRole('complementary', { name: 'catalog' });
  await expect(detail).toContainText(STACK.model);
  // 300k is the head's forced window: set in the topology only, never in /api/models.
  await expect(detail).toContainText(`${STACK.headWindow / 1000}k`);
  expect(faults.pageErrors, 'opening a model threw').toEqual([]);
});

test('teams creates a team through the composer, binds the driven session, prices its turn, and unbinds it', async ({ page }) => {
  const faults = await open(page, 'teams');
  const main = page.locator('main');
  // Another run of this test may already have left a team in the stack's daemon, in which case the
  // composer opens on it and the create form is one key away.
  await expect(main).toContainText(/no teams yet|new team/, { timeout: 15_000 });
  const fresh = page.getByRole('button', { name: 'new team' });
  if ((await fresh.count()) > 0) await fresh.click();

  const name = `e2e crew ${Date.now()}`;
  const form = page.getByRole('form', { name: 'compose team' });
  await form.getByRole('textbox', { name: 'name', exact: true }).fill(name);
  await form.getByRole('textbox', { name: 'repo', exact: true }).fill('/tmp/console-e2e');
  await form.getByRole('textbox', { name: 'role', exact: true }).fill('lead');
  await form.getByRole('textbox', { name: 'head', exact: true }).fill(STACK.oauthHead);
  await form.getByRole('textbox', { name: 'session', exact: true }).fill(STACK.session);
  await form.getByRole('button', { name: 'create team' }).click();

  // The page opens the team it made: the composer now edits it rather than creating another, and
  // still prints the daemon's answer to the create; then its board and its row.
  const edit = page.getByRole('form', { name: `edit ${name}` });
  await expect(edit).toBeVisible({ timeout: 15_000 });
  await expect(edit.getByRole('status')).toContainText(`saved ${name} as team-`);
  await expect(page.locator('.myx-board-header')).toContainText(name);
  await expect(main).toContainText('1 slots, 1 bound');
  // The bound session is racked under its head, and the registry does not list it (the stack runs
  // no Claude Code), which the strip says rather than inventing a state.
  await expect(page.locator('.myx-board-bay-0')).toContainText(STACK.session);
  await expect(page.locator('.myx-board-bay-0')).toContainText('unlisted');
  // The chat and activity were READ for this team: empty, never unreadable.
  await expect(main).toContainText('no messages today');
  await expect(main).toContainText('nothing sampled today');

  // The daemon joined the stack's one turn to the slot on the session tag: the timeline's economics
  // price it under the lead role, and the day's turn log racks it in the session's column.
  await page.getByRole('tab', { name: 'timeline' }).click();
  await expect(main).toContainText('lifetime, joined by the daemon', { timeout: 15_000 });
  const bars = page.locator('.myx-board-bars');
  await expect(bars).toContainText(STACK.session);
  await expect(bars.locator('.myx-board-bar-figure')).toHaveText('1');
  await expect(page.locator('.myx-board-table')).toContainText('lead');

  // Opening the seat is its own write: the replace keeps a binding its body leaves null.
  await edit.getByRole('textbox', { name: 'role instructions' }).fill('drive the e2e packet');
  await edit.getByRole('button', { name: 'unbind' }).click();
  await edit.getByRole('button', { name: 'save team' }).click();
  await expect(edit.getByRole('status')).toContainText(`saved ${name} as team-`);
  await expect(main).toContainText('1 slots, 0 bound', { timeout: 15_000 });

  expect(faults.pageErrors, 'the journey threw').toEqual([]);
  expect([...new Set(faults.failedReads)], 'a write or read the daemon refused').toEqual([]);
});
