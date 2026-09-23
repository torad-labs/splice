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
import { expect, test, type Locator, type Page } from '@playwright/test';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { STACK, TURN_PROMPT, driveOneTurn } from './stack';

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

/** An account strip's `next` cell: the rule the daemon's next target was chosen by, empty on every
 *  strip the daemon did not flag (widgets/account-strip). */
function nextCell(strip: Locator): Locator {
  return strip
    .locator('.myx-sfield', { has: strip.page().locator('.myx-sfield-label', { hasText: /^next$/ }) })
    .locator('.myx-sfield-text');
}

test('accounts shows the OAuth account with the windows its provider reported', async ({ page }) => {
  await open(page, 'accounts');
  const main = page.locator('main');
  await expect(main).toContainText(STACK.oauthHead, { timeout: 15_000 });
  await expect(main).toContainText(`${STACK.fiveHourUsedPercent}%`);
  await expect(main).toContainText(`${STACK.sevenDayUsedPercent}%`);
  await expect(main).toContainText(STACK.plan);

  // The daemon's own next target in the pooled head's pool: next_target on the primary, marked with
  // the rule that chose it, and on no other strip of the pool or of the page (M4-08).
  await expect(nextCell(main.getByRole('button', { name: 'chatgpt-oauth primary', exact: true }))).toHaveText('primary');
  await expect(nextCell(main.getByRole('button', { name: `chatgpt-oauth ${STACK.poolLabel}`, exact: true }))).toHaveText('');
  await expect(nextCell(main.getByRole('button', { name: 'chatgpt-oauth single login', exact: true }))).toHaveText('');
  // The order the daemon walks, the pin first.
  await expect(main).toContainText('pinned then primary then sticky then lowest 7-day used');
});

test('fleet shows each head\'s pinned model from the catalog', async ({ page }) => {
  await open(page, 'fleet');
  const main = page.locator('main');
  await expect(main).toContainText(STACK.model, { timeout: 15_000 });
  await expect(main).toContainText(STACK.soloModel);
});

/** The daemon's refusal on a daemon nothing restarts, verbatim (DaemonRoutes.kt:64-65). The stack's
 *  daemon is unsupervised by construction: stack.ts spawns it with an explicit environment that
 *  carries no INVOCATION_ID, which is the one thing DrainingRestartAdapter reads. */
const RESTART_UNSUPERVISED =
  'nothing will restart this daemon: it was not started by systemd, so a drain would leave it down';

/** A head strip's accessible name: its label and its auth kind (widgets/head-strip). */
function headStrip(page: Page, head: string, authKind: string) {
  return page.getByRole('button', { name: `${head} ${authKind}`, exact: true });
}

test('fleet opens a head with its account pool and the next target marked', async ({ page }) => {
  const faults = await open(page, 'fleet');
  await headStrip(page, STACK.oauthHead, 'chatgpt-oauth').click();
  const detail = page.getByRole('complementary', { name: 'head detail' });
  // Both accounts of the pool, as account strips; the primary carries the windows the turn reported.
  await expect(detail.getByRole('button', { name: `chatgpt-oauth ${STACK.poolLabel}`, exact: true })).toBeVisible({ timeout: 15_000 });
  const primary = detail.getByRole('button', { name: 'chatgpt-oauth primary', exact: true });
  await expect(primary).toContainText(`${STACK.fiveHourUsedPercent}%`);
  await expect(primary).toContainText(STACK.plan);
  // The daemon's own next target (next_target on the primary), printed where the column shows it.
  await expect(detail).toContainText('next target primary');
  // The solo head's single login rides another head, so it is not in this pool.
  await expect(detail.getByRole('button', { name: 'chatgpt-oauth single login', exact: true })).toHaveCount(0);

  // An api-key head has no OAuth pool and says so, rather than printing an empty rack.
  await headStrip(page, STACK.keyHead, 'api-key').click();
  await expect(detail).toContainText('no oauth pool');
  expect(faults.pageErrors, 'opening a head threw').toEqual([]);
});

test('the draining restart confirms inline and prints the daemon\'s refusal verbatim', async ({ page }) => {
  const posts: string[] = [];
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/daemon/restart') posts.push(request.url());
  });
  await open(page, 'fleet');
  await headStrip(page, STACK.oauthHead, 'chatgpt-oauth').click();
  const detail = page.getByRole('complementary', { name: 'head detail' });

  await detail.getByRole('button', { name: 'restart daemon', exact: true }).click();
  // Armed, in place: the confirm key is on the strip and nothing has been sent.
  const confirm = detail.getByRole('button', { name: 'drain and restart', exact: true });
  await expect(confirm).toBeVisible();
  expect(posts, 'arming the key sent the restart').toEqual([]);
  await confirm.click();
  await expect(detail).toContainText(RESTART_UNSUPERVISED);
  expect(posts).toHaveLength(1);

  // The doctor's upgrade section mounts the same control, and the daemon answers it the same way.
  await page.goto(`${env('CONSOLE_E2E_BASE')}/#/doctor`);
  const doctor = page.getByRole('complementary', { name: 'check detail' });
  await doctor.getByRole('button', { name: 'restart daemon', exact: true }).click();
  await doctor.getByRole('button', { name: 'drain and restart', exact: true }).click();
  await expect(doctor).toContainText(RESTART_UNSUPERVISED);
  expect(posts).toHaveLength(2);
});

test('doctor renders the stack\'s report, the CLI\'s own masked values included, rather than refusing it', async ({ page }) => {
  const faults = await open(page, 'doctor');
  const main = page.locator('main');
  // The api-key head's env var is absent, so its credential check fails with a fix whose value the
  // CLI masks: `export CONSOLE_E2E_NO_SUCH_KEY=<redacted>`. The console read that mask as a leak and
  // refused the whole report ('key-value at checks[28].detail') until M4-07.
  const masked = 'CONSOLE_E2E_NO_SUCH_KEY=<redacted>';
  const strip = main.getByRole('button').filter({ hasText: masked });
  await expect(strip, 'the masked check is not in the rack').toBeVisible({ timeout: 15_000 });
  await expect(main.getByText('report refused')).toHaveCount(0);
  // The rest of the report prints with it: the report's own facts beside the rack.
  await expect(main.getByText('schema_version', { exact: true })).toBeVisible();
  // Opening the check prints its detail, the daemon's sentence ahead of the fix.
  await strip.click();
  const detail = page.getByRole('complementary', { name: 'check detail' });
  await expect(detail).toContainText('CONSOLE_E2E_NO_SUCH_KEY is not set');
  expect(faults.pageErrors, 'the doctor page threw').toEqual([]);
});

test('doctor\'s playground sends one prompt through a head to the upstream and shows both sides', async ({ page }) => {
  const faults = await open(page, 'doctor');
  const detail = page.getByRole('complementary', { name: 'check detail' });
  await detail.getByRole('button', { name: 'playground', exact: true }).click();
  await detail.getByRole('textbox', { name: /^head/ }).fill(STACK.oauthHead);
  await detail.getByRole('textbox', { name: /^prompt/ }).fill('one prompt from the console e2e');
  await detail.getByRole('button', { name: 'send', exact: true }).click();
  // The mock upstream's own answer text, inside the raw response the daemon relayed.
  await expect(detail).toContainText('console e2e answer', { timeout: 30_000 });
  // The raw request: the upstream URL the head's provider resolves to, and the prompt it carried.
  await expect(detail).toContainText('/responses');
  await expect(detail).toContainText('one prompt from the console e2e');
  expect(faults.pageErrors, 'the playground threw').toEqual([]);
  expect([...new Set(faults.failedReads)], 'the playground send was refused').toEqual([]);
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

test('teams composes the stack\'s two sessions, shows their hand-off and the sender\'s priced turn, and unbinds', async ({ page }) => {
  const faults = await open(page, 'teams');
  const main = page.locator('main');
  // Another run of this test may already have left a team in the stack's daemon, in which case the
  // composer opens on it and the create form is one key away.
  await expect(main).toContainText(/no teams yet|new team/, { timeout: 15_000 });
  const fresh = page.getByRole('button', { name: 'new team' });
  if ((await fresh.count()) > 0) await fresh.click();

  // Two seats, bound to the two sessions the stack registered: the sender that drove the hand-off
  // turn, and the peer it handed off to.
  const name = `e2e crew ${Date.now()}`;
  const form = page.getByRole('form', { name: 'compose team' });
  const field = (label: string, slot = 0) => form.getByRole('textbox', { name: label, exact: true }).nth(slot);
  await field('name').fill(name);
  await field('repo').fill(env('CONSOLE_E2E_REPO'));
  await field('role').fill('lead');
  await field('head').fill(STACK.oauthHead);
  await field('session').fill(STACK.sender.id);
  await form.getByRole('button', { name: 'add slot' }).click();
  await field('role', 1).fill('builder');
  await field('head', 1).fill(STACK.oauthHead);
  await field('session', 1).fill(STACK.peer.id);
  await form.getByRole('button', { name: 'create team' }).click();

  // The page opens the team it made: the composer now edits it rather than creating another, and
  // still prints the daemon's answer to the create; then its board and its row.
  const edit = page.getByRole('form', { name: `edit ${name}` });
  await expect(edit).toBeVisible({ timeout: 15_000 });
  await expect(edit.getByRole('status')).toContainText(`saved ${name} as team-`);
  await expect(page.locator('.myx-board-header')).toContainText(name);
  await expect(main).toContainText('2 slots, 2 bound');
  // Both sessions are racked under their head by the names the registry gives them.
  await expect(page.locator('.myx-board-bay-0')).toContainText(STACK.sender.name, { timeout: 15_000 });
  await expect(page.locator('.myx-board-bay-0')).toContainText(STACK.peer.name);
  // The day's chat carries the hand-off, sender to recipient, both resolved to their seats.
  await expect(page.getByRole('button', { name: `${STACK.sender.name} to ${STACK.peer.name}` }).first()).toBeVisible();
  // Activity was READ for this team: the stack runs no client to answer a label query, so it is
  // empty, never unreadable.
  await expect(main).toContainText('nothing sampled today');

  // The daemon joined the sender's tagged turn to its slot: the timeline's economics price it under
  // the lead role, and the peer's seat has none.
  await page.getByRole('tab', { name: 'timeline' }).click();
  await expect(main).toContainText('lifetime, joined by the daemon', { timeout: 15_000 });
  const bar = (who: string) => page.locator('.myx-board-bar').filter({ hasText: who }).locator('.myx-board-bar-figure');
  await expect(bar(STACK.sender.name)).toHaveText('1');
  await expect(bar(STACK.peer.name)).toHaveText('0');
  await expect(page.locator('.myx-board-table')).toContainText('lead');

  // Opening the seat is its own write: the replace keeps a binding its body leaves null.
  await edit.getByRole('textbox', { name: 'role instructions', exact: true }).first().fill('drive the e2e packet');
  await edit.getByRole('button', { name: 'unbind' }).first().click();
  await edit.getByRole('button', { name: 'save team' }).click();
  await expect(edit.getByRole('status')).toContainText(`saved ${name} as team-`);
  await expect(main).toContainText('2 slots, 1 bound', { timeout: 15_000 });

  expect(faults.pageErrors, 'the journey threw').toEqual([]);
  expect([...new Set(faults.failedReads)], 'a write or read the daemon refused').toEqual([]);
});

test('projects opens the stack repository with the detail its own route reports', async ({ page }) => {
  const faults = await open(page, 'projects');
  const repo = env('CONSOLE_E2E_REPO');
  const read = page.waitForResponse((response) =>
    response.request().method() === 'GET' && new URL(response.url()).pathname === `/api/projects/${encodeURIComponent(repo)}`);
  await page.getByRole('button', { name: `projects ${repo}` }).click();
  expect((await read).status(), 'GET /api/projects/{id}').toBe(200);
  const detail = page.getByRole('complementary', { name: 'project detail' });
  await expect(detail).toContainText(repo);
  // Two registered sessions work in the repository, and the sender's hand-off is today's one turn.
  await expect(detail).toContainText(/live sessions\s*2/);
  await expect(detail).toContainText(/turns today\s*1/);
  await expect(detail).toContainText(/cost today\s*n\/r/);
  await expect(detail).toContainText(`${repo}/CLAUDE.md`);
  expect(faults.pageErrors, 'opening a project threw').toEqual([]);
  expect([...new Set(faults.failedReads)], 'reads the daemon refused').toEqual([]);
});

test('sessions prints each session\'s peer from the fleet-wide edges read, unopened', async ({ page }) => {
  const faults = await open(page, 'sessions');
  // Neither strip is opened: the peer column comes from GET /api/sessions/edges for every row.
  const sender = page.getByRole('button', { name: `sessions ${STACK.sender.name}` });
  const peer = page.getByRole('button', { name: `sessions ${STACK.peer.name}` });
  await expect(sender).toContainText(STACK.peer.name, { timeout: 15_000 });
  // The received edge names its SENDER by session id; the console resolves it to the session's name.
  await expect(peer).toContainText(STACK.sender.name);
  expect(faults.pageErrors, 'the sessions board threw').toEqual([]);
  expect([...new Set(faults.failedReads)], 'reads the daemon refused').toEqual([]);
});

test('compaction lists the instruction rules the daemon has in effect, with their lengths', async ({ page }) => {
  const faults = await open(page, 'compaction');
  const rule = (source: string) => page.getByRole('button', { name: `instruction ${source}` });
  await expect(rule('global')).toContainText(String(STACK.compactGlobal.length), { timeout: 15_000 });
  await expect(rule(`model:${STACK.model}`)).toContainText(String(STACK.compactModel.length));
  await expect(rule(`project:${env('CONSOLE_E2E_REPO')}`)).toContainText(String(STACK.compactProject.length));
  // A model rule applies to the heads whose roster carries the model, and to no other.
  await expect(rule(`model:${STACK.model}`)).toContainText(STACK.oauthHead);
  await expect(rule(`model:${STACK.model}`)).not.toContainText(STACK.keyHead);
  expect(faults.pageErrors, 'the compaction page threw').toEqual([]);
  expect([...new Set(faults.failedReads)], 'reads the daemon refused').toEqual([]);
});

/** The capture route of the stack's OAuth head. */
const CAPTURE_PATH = `/api/heads/${STACK.oauthHead}/capture`;

// LAST ON PURPOSE: it writes splice.toml (and turns the write back), so every journey above reads a
// topology nothing in this run has edited.
test('turns writes body capture for a head through the daemon, re-reads it, and prints no body', async ({ page }) => {
  const faults = await open(page, 'turns');
  // Every call on the capture route, in order, with the body a write sent.
  const calls: { method: string; body: string | null }[] = [];
  page.on('request', (request) => {
    if (new URL(request.url()).pathname === CAPTURE_PATH) calls.push({ method: request.method(), body: request.postData() });
  });
  const wroteThenReread = (enabled: boolean): boolean => {
    const at = calls.findIndex((call) => call.method === 'PUT' && call.body !== null && (JSON.parse(call.body) as { enabled?: unknown }).enabled === enabled);
    return at >= 0 && calls.slice(at + 1).some((call) => call.method === 'GET');
  };

  const turns = page.getByRole('button', { name: `turns ${STACK.oauthHead} ${STACK.model}` });
  await expect(turns.first()).toBeVisible({ timeout: 15_000 });
  await turns.first().click();
  const detail = page.getByRole('complementary', { name: 'turn detail' });
  const toggle = detail.getByRole('switch', { name: 'body capture' });
  // Off by default, and saying so (PRODUCT.md: nothing is recorded that the operator did not ask for).
  await expect(toggle).toHaveAttribute('aria-checked', 'false');
  await expect(detail).toContainText('capture off for this head');

  await toggle.click();
  await expect.poll(() => wroteThenReread(true), { message: 'the switch never wrote enabled=true and re-read' }).toBe(true);
  await expect(toggle).toHaveAttribute('aria-checked', 'true');
  // The daemon answers restart_required and its re-read still runs capture off: both are printed.
  await expect(detail).toContainText('written to splice.toml');
  await expect(detail).toContainText('capture off for this head');
  const toml = readFileSync(env('CONSOLE_E2E_CONFIG'), 'utf8');
  expect(toml, 'the write did not reach splice.toml').toMatch(new RegExp(`\\[heads\\.${STACK.oauthHead}\\.overrides\\][^[]*trace = "true"`));

  // A turn driven AFTER the write: its bodies are not recorded until the daemon restarts, and no
  // route serves a body, so opening it must not print one.
  const before = await turns.count();
  await driveOneTurn(Number(env('CONSOLE_E2E_OAUTH_PORT')), env('CONSOLE_E2E_KEY'));
  await expect(turns).toHaveCount(before + 1, { timeout: 15_000 });
  await turns.last().click();
  await expect(detail).toContainText('written to splice.toml');
  await expect(detail).toContainText('capture off for this head');
  await expect(detail).not.toContainText(TURN_PROMPT);

  // And back off: the write and its re-read agree, so nothing is pending.
  await toggle.click();
  await expect.poll(() => wroteThenReread(false), { message: 'the switch never wrote enabled=false and re-read' }).toBe(true);
  await expect(toggle).toHaveAttribute('aria-checked', 'false');
  await expect(detail).not.toContainText('written to splice.toml');
  expect(readFileSync(env('CONSOLE_E2E_CONFIG'), 'utf8')).toMatch(/trace = "false"/);
  expect(faults.pageErrors, 'the capture journey threw').toEqual([]);
  expect([...new Set(faults.failedReads)], 'reads the daemon refused').toEqual([]);
});
