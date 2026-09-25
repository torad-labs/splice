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
  await expect(page.getByRole('navigation', { name: 'pages' }), 'the console shell did not render').toBeVisible();
  return faults;
}

test('the page set comes from the source', () => {
  expect(PAGES, `no page directories under ${PAGES_DIR}`).toContain('fleet');
});

test('the address splice dashboard opens unlocks the console and leaves no key behind', async ({ page }) => {
  // The fragment is what DashboardCommand's redirect page sends the browser to; no init script.
  const key = env('CONSOLE_E2E_KEY');
  await page.goto(`${env('CONSOLE_E2E_BASE')}/#k=${encodeURIComponent(key)}`);
  // The landing page is Needs you (V4-219), and its read time prints only once every input it rests
  // on answered: a locked console, refused on every read, could not print it.
  await expect(page.getByRole('heading', { name: 'Needs you', exact: true })).toBeVisible();
  await expect(page.locator('main').getByText(/^Read \d\d:\d\d:\d\d$/)).toBeVisible({ timeout: 20_000 });
  expect(await page.getByText('management key required').count(), 'the handed-over key did not unlock').toBe(0);
  expect(page.url(), 'the key was left in the address').not.toContain(key);
  expect(await page.evaluate((storage) => localStorage.getItem(storage), KEY_STORAGE), 'the key was not kept').toBe(key);
});

test('with no key handed over, the gate asks and the pasted key unlocks', async ({ page }) => {
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

// THE OPERATOR'S FRAME (ruling 4, 2026-09-25). His panels are 3840 wide at desktop scale 1, where
// the console was a 1920px island of 13px type, 70% of the screen black. At his frame a page uses
// the width and its body reads at 20px; at 1600 the floors of tests/scale.test.ts hold in a real
// browser, where the clamps and the unit resolve.
const FRAMES = [
  { width: 3840, height: 2060, used: 0.8, body: 20, cell: 19 },
  { width: 1600, height: 1000, used: 0.7, body: 17, cell: 16 },
] as const;
for (const frame of FRAMES) {
  for (const name of ['sessions', 'usage', 'turns']) {
    test(`${name} at ${frame.width}x${frame.height} uses the width and reads at the floor`, async ({ page }) => {
      await page.setViewportSize({ width: frame.width, height: frame.height });
      await open(page, name);
      await page.waitForTimeout(SETTLE_MS);
      const read = await page.evaluate(() => {
        // WHAT IS DRAWN, NOT THE BOXES IT IS LAID OUT IN: every visible text run's glyph boxes and
        // every svg, img and canvas, clamped to the window. The element-box extent scored 88.9% on
        // every page at 3840, a doctor of four empty cards included, so it could not fail on content.
        const root = document.querySelector('.myx-console-page');
        const drawn: DOMRect[] = [];
        if (root !== null) {
          const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
          for (let node = walker.nextNode(); node !== null; node = walker.nextNode()) {
            if ((node.textContent ?? '').trim() === '' || node.parentElement === null) continue;
            const style = getComputedStyle(node.parentElement);
            if (style.visibility === 'hidden' || Number(style.opacity) === 0) continue;
            const range = document.createRange();
            range.selectNodeContents(node);
            drawn.push(...[...range.getClientRects()].filter((rect) => rect.width > 1 && rect.height > 1));
          }
          for (const element of root.querySelectorAll('svg, img, canvas')) {
            const rect = element.getBoundingClientRect();
            if (rect.width > 1 && rect.height > 1) drawn.push(rect);
          }
        }
        const cell = document.querySelector('.myx-dt tbody td');
        return {
          left: Math.max(0, Math.min(...drawn.map((rect) => rect.left))),
          right: Math.min(window.innerWidth, Math.max(...drawn.map((rect) => rect.right))),
          body: parseFloat(getComputedStyle(document.body).fontSize),
          cell: cell === null ? null : parseFloat(getComputedStyle(cell).fontSize),
        };
      });
      expect((read.right - read.left) / frame.width, 'share of the window the page draws in').toBeGreaterThanOrEqual(frame.used);
      expect(read.body, 'body text px').toBeGreaterThanOrEqual(frame.body);
      if (read.cell !== null) expect(read.cell, 'table text px').toBeGreaterThanOrEqual(frame.cell);
    });
  }
}

test('turns lists the turn the stack drove through a real head', async ({ page }) => {
  await open(page, 'turns');
  await expect(page.locator('main')).toContainText(STACK.model, { timeout: 15_000 });
  await expect(page.locator('main')).toContainText(STACK.oauthHead);
});

test('the in-flight table holds exactly the turns the gate lists, twins included, as they end', async ({ page }) => {
  // Marlin and Hitstop, 2026-09-25: a session's parallel turns share its label, the table keyed its
  // rows by head and label, and React kept the rows of turns that had ended: 10 streaming rows under
  // a gate holding 4. The rows it kept were twins left behind when an OLDER turn ahead of them
  // ended, so the daemon's answer is rewritten here to hold one turn and two twins, then the twins.
  const TWIN = 'c3d5e7a0 grok-4.6';
  let labels = ['e5b7a0c4 grok-build-latest', TWIN, TWIN];
  await page.route('**/api/heads', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as { heads: { gate: Record<string, unknown> | null }[] };
    const head = body.heads.find((entry) => entry.gate !== null);
    if (head?.gate != null) {
      head.gate = {
        ...head.gate,
        inflight: labels.length,
        live: labels.map((label, at) => ({ label, compact: false, phase: 'streaming', age_ms: 9_000 - at * 1_000, idle_ms: 20 })),
      };
    }
    await route.fulfill({ response, json: body });
  });
  await open(page, 'turns');
  const rows = page.getByRole('table', { name: 'In flight', exact: true }).locator('tbody tr');
  await expect(rows).toHaveCount(3, { timeout: 15_000 });
  labels = [TWIN, TWIN];
  await expect(rows, 'the turn that ended must leave the table, and its twins stay two').toHaveCount(2, { timeout: 15_000 });
  await expect(rows.filter({ hasText: 'grok-build-latest' })).toHaveCount(0);
});

/** Picks `option` in the `nth` picker named `label` inside `scope`: the Choice is a combobox named by
 *  its printed label, and its rack is a listbox of options named by their text (shared/controls). */
async function pick(scope: Locator, label: string, option: string, nth = 0): Promise<void> {
  await scope.getByRole('combobox', { name: label, exact: true }).nth(nth).click();
  await scope.getByRole('option', { name: option, exact: true }).click();
}

/** An account row's `Next` cell: the rule the daemon's next target was chosen by, empty on every
 *  row the daemon did not flag (widgets/account-table). */
async function nextCell(main: Locator, account: string): Promise<Locator> {
  const table = main.getByRole('table', { name: 'Accounts', exact: true });
  const names = await table.getByRole('columnheader').allTextContents();
  // `has` resolves inside each row, so the inner locator starts from the page, not from `main`
  const row = table.getByRole('row').filter({ has: main.page().getByRole('button', { name: `Open account ${account}`, exact: true }) });
  return row.getByRole('cell').nth(names.indexOf('Next'));
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
  await expect(await nextCell(main, 'chatgpt-oauth primary')).toHaveText('Primary');
  await expect(await nextCell(main, `chatgpt-oauth ${STACK.poolLabel}`)).toHaveText('');
  await expect(await nextCell(main, 'chatgpt-oauth Single login')).toHaveText('');
  // The order the daemon walks, the pin first, behind the accounts section's info mark.
  await expect(main.getByRole('button', { name: 'About next', exact: true }))
    .toHaveAccessibleDescription('Pinned, then primary, then last used, then most weekly room.');
});

test('a table cell lets its open tip out, and twelve slots stand six and six', async ({ page }) => {
  // Fleet at 1600, 2026-09-25: a gate of twelve wrapped its pips nine and three, and "streaming 3.2s"
  // ran past the Last turn column's edge. The cell says Running now, with the phase in its tip, and
  // a cell's own clip cut every tip in a table to a sliver until the cell let an open one out.
  await page.setViewportSize({ width: 1600, height: 1000 });
  await page.route('**/api/heads', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as { heads: { gate: Record<string, unknown> | null }[] };
    const head = body.heads.find((entry) => entry.gate !== null);
    if (head?.gate != null) {
      head.gate = { ...head.gate, max: 12, inflight: 1, live: [{ label: 'x', compact: false, phase: 'streaming', age_ms: 3_200, idle_ms: 20 }] };
    }
    await route.fulfill({ response, json: body });
  });
  await open(page, 'fleet');
  const table = page.getByRole('table', { name: 'Heads', exact: true });
  const running = table.getByText('Running', { exact: true }).first();
  await expect(running).toBeVisible({ timeout: 15_000 });
  await running.hover();
  const tip = table.getByRole('tooltip').filter({ hasText: 'streaming 3.2s' });
  await expect(tip).toBeVisible();
  // Seen, not only laid out: the top of the tip, above its cell, is the tip and not what a clip
  // leaves. A tip takes no pointer, so hit-testing would pass through it; it takes one for the probe,
  // and hit-testing still honours every clip.
  const seen = await tip.evaluate((body) => {
    (body as HTMLElement).style.pointerEvents = 'auto';
    const box = body.getBoundingClientRect();
    return body.contains(document.elementFromPoint(box.x + box.width / 2, box.y + 2));
  });
  expect(seen, 'the open tip must not be clipped by its cell').toBe(true);
  const rows = await table.getByRole('img', { name: /: 1 of 12$/ }).first().evaluate((pips) => {
    const tops = [...pips.children].map((pip) => Math.round(pip.getBoundingClientRect().top));
    return [...new Set(tops)].map((top) => tops.filter((at) => at === top).length);
  });
  expect(rows, 'twelve pips in two even rows').toEqual([6, 6]);
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

/** A head's open button in the heads table: the row's primary cell (pages/fleet). */
function headRow(page: Page, head: string) {
  return page.getByRole('button', { name: `Open head ${head}`, exact: true });
}

/** One account's row in an opened head's pool table, found by the account's name cell. A pool
 *  account opens nothing, so its row carries no button; it is found by its cell text. */
function poolRow(detail: Locator, account: string): Locator {
  return detail.getByRole('table', { name: 'Account pool', exact: true }).getByRole('row')
    .filter({ has: detail.page().getByRole('cell', { name: account }) });
}

test('fleet opens a head with its account pool and the next target marked', async ({ page }) => {
  const faults = await open(page, 'fleet');
  await headRow(page, STACK.oauthHead).click();
  const detail = page.getByRole('complementary', { name: 'Head detail' });
  // Both accounts of the pool, as account rows; the primary carries the windows the turn reported.
  const pool = detail.getByRole('table', { name: 'Account pool', exact: true });
  await expect(pool).toContainText(STACK.poolLabel, { timeout: 15_000 });
  const primary = poolRow(detail, 'primary');
  await expect(primary).toContainText(`${STACK.fiveHourUsedPercent}%`);
  // The daemon's own next target (next_target on the primary), one fact above the rows: the account
  // and the rule that chose it. Only that fact's value holds the rule's word.
  await expect(detail.getByRole('definition').filter({ has: page.getByText('Primary', { exact: true }) })).toHaveText(/^primary\s*Primary$/);
  // The solo head's single login rides another head, so it is not in this pool.
  await expect(pool).not.toContainText('Single login');

  // An api-key head has no account pool and says so, rather than printing an empty table.
  await headRow(page, STACK.keyHead).click();
  await expect(detail).toContainText('No pool');
  await expect(detail.getByRole('table', { name: 'Account pool', exact: true })).toHaveCount(0);
  expect(faults.pageErrors, 'opening a head threw').toEqual([]);
});

test('the draining restart confirms inline and prints the daemon\'s refusal verbatim', async ({ page }) => {
  const posts: string[] = [];
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/daemon/restart') posts.push(request.url());
  });
  await open(page, 'fleet');
  await headRow(page, STACK.oauthHead).click();
  const detail = page.getByRole('complementary', { name: 'Head detail' });

  await detail.getByRole('button', { name: 'Restart daemon', exact: true }).click();
  // Armed, in place: the confirm key is in the detail and nothing has been sent.
  const confirm = detail.getByRole('button', { name: 'Drain and restart', exact: true });
  await expect(confirm).toBeVisible();
  expect(posts, 'arming the key sent the restart').toEqual([]);
  await confirm.click();
  await expect(detail).toContainText(RESTART_UNSUPERVISED);
  expect(posts).toHaveLength(1);

  // The doctor's version section mounts the same control, and the daemon answers it the same way.
  await page.goto(`${env('CONSOLE_E2E_BASE')}/#/doctor`);
  const doctor = page.locator('main');
  await doctor.getByRole('button', { name: 'Restart daemon', exact: true }).click();
  await doctor.getByRole('button', { name: 'Drain and restart', exact: true }).click();
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
  // The masked fix is in the row's Fix cell; the row opens from its check cell's button.
  const row = main.getByRole('table', { name: 'Checks', exact: true }).getByRole('row').filter({ hasText: masked });
  await expect(row, 'the masked check is not in the table').toBeVisible({ timeout: 15_000 });
  await expect(main.getByText('Report refused')).toHaveCount(0);
  // The rest of the report prints with it: the report's own fields under the table.
  await expect(main.getByText('schema_version', { exact: true })).toBeVisible();
  // Opening the check prints its detail, the daemon's sentence ahead of the fix.
  await row.getByRole('button').click();
  const detail = page.getByRole('complementary', { name: 'Check detail' });
  await expect(detail).toContainText('CONSOLE_E2E_NO_SUCH_KEY is not set');
  expect(faults.pageErrors, 'the doctor page threw').toEqual([]);
});

test('doctor\'s playground sends one prompt through a head to the upstream and shows both sides', async ({ page }) => {
  const faults = await open(page, 'doctor');
  // The playground is a section of the page, open at rest: one form, no reveal to press first.
  const playground = page.locator('main');
  await pick(playground, 'Head', STACK.oauthHead);
  await playground.getByRole('textbox', { name: /^Prompt/ }).fill('one prompt from the console e2e');
  await playground.getByRole('button', { name: 'Send', exact: true }).click();
  // The mock upstream's own answer text, inside the raw response the daemon relayed.
  await expect(playground).toContainText('console e2e answer', { timeout: 30_000 });
  // The raw request: the upstream URL the head's provider resolves to, and the prompt it carried.
  await expect(playground).toContainText('/responses');
  await expect(playground).toContainText('one prompt from the console e2e');
  expect(faults.pageErrors, 'the playground threw').toEqual([]);
  expect([...new Set(faults.failedReads)], 'the playground send was refused').toEqual([]);
});

test('models opens a model with the head windows its topology declares', async ({ page }) => {
  const faults = await open(page, 'models');
  await page.getByRole('button', { name: `open model ${STACK.model}` }).first().click();
  const detail = page.getByRole('complementary', { name: 'Model detail' });
  await expect(detail).toContainText(STACK.model);
  // 300k is the head's forced window: set in the topology only, never in /api/models.
  await expect(detail).toContainText(`${STACK.headWindow / 1000}k`);
  expect(faults.pageErrors, 'opening a model threw').toEqual([]);
});

test('teams composes the stack\'s two sessions, shows their hand-off and the sender\'s priced turn, and unbinds', async ({ page }) => {
  // The journey drives a real turn bounded at 60 s (stack.ts postTurn), so its budget sits above that
  // bound: a turn that hangs fails as that turn, named, rather than as a bare test timeout.
  test.setTimeout(120_000);
  const faults = await open(page, 'teams');
  const main = page.locator('main');
  // Another run of this test may already have left a team in the stack's daemon: then the key is the
  // page head's; with none, it is the empty's. The page never prints both.
  await expect(main).toContainText(/No teams yet|New team/, { timeout: 15_000 });
  await page.getByRole('button', { name: 'New team', exact: true }).click();

  // Two seats, bound to the two sessions the stack registered: the sender that drove the hand-off
  // turn, and the peer it handed off to.
  const name = `e2e crew ${Date.now()}`;
  const form = page.getByRole('form', { name: 'New team' });
  const field = (label: string, slot = 0) => form.getByRole('textbox', { name: label, exact: true }).nth(slot);
  await field('Name').fill(name);
  await field('Repo').fill(env('CONSOLE_E2E_REPO'));
  await field('Role').fill('lead');
  await pick(form, 'Head', STACK.oauthHead);
  await pick(form, 'Session', STACK.sender.name);
  await form.getByRole('button', { name: 'Add slot' }).click();
  await field('Role', 1).fill('builder');
  await pick(form, 'Head', STACK.oauthHead, 1);
  await pick(form, 'Session', STACK.peer.name, 1);
  await form.getByRole('button', { name: 'Create team' }).click();

  // The page opens the team it made: the editor now edits it rather than creating another, and
  // still prints the daemon's answer to the create; then the team itself.
  const edit = page.getByRole('form', { name: `Edit ${name}` });
  await expect(edit).toBeVisible({ timeout: 15_000 });
  await expect(edit.getByRole('status')).toContainText(`Saved ${name} as team-`);
  // A team's economics run over its own lifetime, so the stack's hand-off turn (driven before this
  // team existed) is not its cost: the sender drives one tagged turn now, inside it.
  await driveOneTurn(Number(env('CONSOLE_E2E_OAUTH_PORT')), env('CONSOLE_E2E_KEY'), STACK.sender.id);
  await expect(page.locator('.myx-tm-team')).toContainText(name);
  await expect(page.getByRole('img', { name: 'Slots bound: 2 of 2' })).toBeVisible();
  // The lanes are the team's default view: both sessions are seated as cards on their head's strand,
  // by the names the registry gives them.
  const lanes = page.getByRole('group', { name: 'Members', exact: true });
  const card = (who: string) => lanes.getByRole('button', { name: new RegExp(` ${who.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}, `) });
  await expect(card(STACK.sender.name)).toBeVisible({ timeout: 15_000 });
  await expect(card(STACK.peer.name)).toBeVisible();
  // The day's chat carries the hand-off, sender to recipient, both resolved to their seats, and the
  // lanes draw it as the one arc between their cards once they are laid out.
  await expect(page.getByRole('listitem', { name: `${STACK.sender.name} to ${STACK.peer.name}` }).first()).toBeVisible();
  await expect(lanes.locator('path.myx-lanes-arc')).toHaveCount(1, { timeout: 15_000 });
  // The table is a view behind the lanes, where each seat's turns are counted.
  await page.getByRole('tab', { name: 'By head' }).click();
  const members = page.getByRole('table', { name: 'Members' });
  await expect(members).toContainText(STACK.sender.name, { timeout: 15_000 });
  await expect(members).toContainText(STACK.peer.name);
  // Activity was READ for this team: the stack runs no client to answer a label query, so it is
  // empty, never unreadable.
  await expect(main).toContainText('Nothing sampled today');

  // The daemon joined the sender's tagged turn to its slot: its seat counts it and the cost per role
  // prices it under the lead role, and the peer's seat has none. The panels are re-read every 10 s.
  const turnsOf = async (who: string) => {
    const at = (await members.locator('thead th').allTextContents()).indexOf('Turns');
    return members.getByRole('row').filter({ hasText: who }).locator('td').nth(at);
  };
  await expect(await turnsOf(STACK.sender.name)).toHaveText('1', { timeout: 15_000 });
  await expect(await turnsOf(STACK.peer.name)).toHaveText('0');
  await expect(page.getByRole('table', { name: 'Cost per role' })).toContainText('lead');
  // The day's timeline lays the sender's turns on its lane, joined on the same session tag.
  await page.getByRole('tab', { name: 'Timeline' }).click();
  await expect(page.getByRole('img', { name: new RegExp(`^${STACK.sender.name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}: [1-9]\\d* turns`) }))
    .toBeVisible({ timeout: 15_000 });

  // Opening the seat is its own write: the replace keeps a binding its body leaves null.
  await edit.getByRole('textbox', { name: 'Instructions', exact: true }).first().fill('drive the e2e packet');
  await pick(edit, 'Session', 'Open seat');
  await edit.getByRole('button', { name: 'Save team' }).click();
  await expect(edit.getByRole('status')).toContainText(`Saved ${name} as team-`);
  await expect(page.getByRole('img', { name: 'Slots bound: 1 of 2' })).toBeVisible({ timeout: 15_000 });

  expect(faults.pageErrors, 'the journey threw').toEqual([]);
  expect([...new Set(faults.failedReads)], 'a write or read the daemon refused').toEqual([]);
});

test('projects opens the stack repository with the detail its own route reports', async ({ page }) => {
  const faults = await open(page, 'projects');
  const repo = env('CONSOLE_E2E_REPO');
  const read = page.waitForResponse((response) =>
    response.request().method() === 'GET' && new URL(response.url()).pathname === `/api/projects/${encodeURIComponent(repo)}`);
  await page.getByRole('button', { name: `projects ${repo}` }).click();
  const response = await read;
  expect(response.status(), 'GET /api/projects/{id}').toBe(200);
  const row = (await response.json()) as { turns_today: number };
  const detail = page.getByRole('complementary', { name: 'Project detail' });
  // The heading prints the root with the home directory as `~`; the stack's repo is under /tmp.
  await expect(detail).toContainText(repo);
  // Two registered sessions work in the repository. Today's turns are the sender's: the stack's
  // hand-off, plus the one the teams journey drives when it runs first, so the count printed is the
  // one this read returned, and at least the hand-off.
  await expect(detail).toContainText(/Sessions running\s*2/);
  expect(row.turns_today, 'the sender\'s hand-off is a turn in this repository today').toBeGreaterThanOrEqual(1);
  await expect(detail).toContainText(new RegExp(`Turns today\\s*${row.turns_today}(?!\\d)`));
  await expect(detail).toContainText(/Cost today\s*–/);
  await expect(detail).toContainText(`${repo}/CLAUDE.md`);
  // What governs the repo (FEATURES.md 4.14), from the same row: the stack's project rule for this
  // repo shadows its model and global rules here, so it is the only one listed; and every head's
  // statusline probes the repo under the daemon's HOME, the stack's temp home.
  const rules = detail.getByRole('table', { name: 'Compaction rules' });
  const rule = (source: string) => rules.getByRole('row').filter({ has: page.getByRole('cell', { name: source, exact: true }) });
  await expect(rule(`project:${repo}`)).toContainText(String(STACK.compactProject.length));
  await expect(rule('global')).toHaveCount(0);
  await expect(rule(`model:${STACK.model}`)).toHaveCount(0);
  const statusline = detail.getByRole('table', { name: 'Statusline roots' }).getByRole('row')
    .filter({ has: page.getByText(STACK.oauthHead, { exact: true }) });
  await expect(statusline).toContainText(dirname(repo));
  await expect(statusline).toContainText('Home');
  expect(faults.pageErrors, 'opening a project threw').toEqual([]);
  expect([...new Set(faults.failedReads)], 'reads the daemon refused').toEqual([]);
});

test('sessions draws the stack\'s hand-off as an arc from the sender\'s card to the peer\'s, on their head\'s lane', async ({ page }) => {
  const faults = await open(page, 'sessions');
  // The lanes are the page's default view: each session a card on its head's strand, named by its
  // lane (the head) and then its own name.
  const lanes = page.getByRole('group', { name: 'Sessions', exact: true });
  await expect(lanes.getByRole('button', { name: new RegExp(` ${STACK.sender.name}, `) })).toBeVisible({ timeout: 15_000 });
  await expect(lanes.getByRole('button', { name: new RegExp(` ${STACK.peer.name}, `) })).toBeVisible();
  // The arc is measured from the two cards once they are laid out, so it is drawn after the first paint.
  await expect(page.locator(`path.myx-lanes-arc[data-arc="${STACK.sender.id}>${STACK.peer.id}"]`)).toHaveCount(1, { timeout: 15_000 });
  expect(faults.pageErrors, 'the lanes threw').toEqual([]);
  expect([...new Set(faults.failedReads)], 'reads the daemon refused').toEqual([]);
});

test('sessions prints each session\'s peer from the fleet-wide edges read, unopened', async ({ page }) => {
  const faults = await open(page, 'sessions');
  // The board is a view behind the lanes. Neither row is opened: the peer column comes from GET
  // /api/sessions/edges for every row. Each row is found by its opener, and the peer is printed in
  // the row beside it.
  await page.getByRole('tab', { name: 'By head' }).click();
  const row = (name: string) => page.getByRole('row').filter({ has: page.getByRole('button', { name: `sessions ${name}` }) });
  const sender = row(STACK.sender.name);
  const peer = row(STACK.peer.name);
  await expect(sender).toContainText(STACK.peer.name, { timeout: 15_000 });
  // The received edge names its SENDER by session id; the console resolves it to the session's name.
  await expect(peer).toContainText(STACK.sender.name);
  expect(faults.pageErrors, 'the sessions board threw').toEqual([]);
  expect([...new Set(faults.failedReads)], 'reads the daemon refused').toEqual([]);
});

test('compaction lists the instruction rules the daemon has in effect, with their lengths', async ({ page }) => {
  const faults = await open(page, 'compaction');
  // One row of the rules table per rule, found by its source cell.
  const rule = (source: string) => page.getByRole('table', { name: 'Rules' }).getByRole('row')
    .filter({ has: page.getByRole('cell', { name: source, exact: true }) });
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

  const turns = page.getByRole('button', { name: `Turn detail ${STACK.oauthHead} ${STACK.model}` });
  await expect(turns.first()).toBeVisible({ timeout: 15_000 });
  await turns.first().click();
  const detail = page.getByRole('complementary', { name: 'Turn detail' });
  const toggle = detail.getByRole('switch', { name: 'Body capture' });
  // Off by default, and saying so (PRODUCT.md: nothing is recorded that the operator did not ask for).
  await expect(toggle).toHaveAttribute('aria-checked', 'false');
  await expect(detail).not.toContainText('Recording bodies');

  await toggle.click();
  await expect.poll(() => wroteThenReread(true), { message: 'the switch never wrote enabled=true and re-read' }).toBe(true);
  await expect(toggle).toHaveAttribute('aria-checked', 'true');
  // The daemon answers restart_required and its re-read still runs capture off: both are printed.
  await expect(detail).toContainText('Restart to apply');
  await expect(detail).not.toContainText('Recording bodies');
  const toml = readFileSync(env('CONSOLE_E2E_CONFIG'), 'utf8');
  expect(toml, 'the write did not reach splice.toml').toMatch(new RegExp(`\\[heads\\.${STACK.oauthHead}\\.overrides\\][^[]*trace = "true"`));

  // A turn driven AFTER the write: its bodies are not recorded until the daemon restarts, and no
  // route serves a body, so opening it must not print one. The table lists the newest turn first.
  const before = await turns.count();
  await driveOneTurn(Number(env('CONSOLE_E2E_OAUTH_PORT')), env('CONSOLE_E2E_KEY'));
  await expect(turns).toHaveCount(before + 1, { timeout: 15_000 });
  await turns.first().click();
  await expect(detail).toContainText('Restart to apply');
  await expect(detail).not.toContainText('Recording bodies');
  await expect(detail).not.toContainText(TURN_PROMPT);

  // And back off: the write and its re-read agree, so nothing is pending.
  await toggle.click();
  await expect.poll(() => wroteThenReread(false), { message: 'the switch never wrote enabled=false and re-read' }).toBe(true);
  await expect(toggle).toHaveAttribute('aria-checked', 'false');
  await expect(detail).not.toContainText('Restart to apply');
  expect(readFileSync(env('CONSOLE_E2E_CONFIG'), 'utf8')).toMatch(/trace = "false"/);
  expect(faults.pageErrors, 'the capture journey threw').toEqual([]);
  expect([...new Set(faults.failedReads)], 'reads the daemon refused').toEqual([]);
});
