// NEW: V4-444 — replacement-console journeys over real daemon payloads and explicit synthetic source controls.
import { expect, test, type Page } from '@playwright/test';
import { fingerprint } from '../src/lib/stale-page';
import { STACK } from './stack';
import type { SessionsPayload } from '../src/types/sessions';
import type { ProjectsPayload } from '../src/types/projects';
import { FINISHED } from './setup';
import { assertHealthy, env, open, read, watch } from './support';

async function staleFixture(page: Page, untagged = false) {
  const state = { boot: 1, different: false, rereads: 0, healthReads: 0 };
  await page.route(url => url.pathname === '/health', async route => {
    const response = await route.fetch();
    const health = await response.json() as Record<string, unknown>;
    await route.fulfill({ response, json: { ...health, bootedAtEpochMillis: state.boot } });
    state.healthReads++;
  });
  await page.route(url => url.pathname === '/', async route => {
    const response = await route.fetch();
    const served = await response.text();
    const loaded = untagged ? served.replace(/<meta name="splice-page-fingerprint" content="[a-f0-9]{64}">/, '') : served;
    const reread = route.request().resourceType() !== 'document';
    await route.fulfill({ response, body: reread && state.different ? loaded + '\n<!-- synthetic different page B -->' : loaded });
    if (reread) state.rereads++;
  });
  return state;
}

const painted = (page: Page) => page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));

for (const width of [1440, 390]) {
  test(`the packaged document matches its runtime fingerprint and opens without a stale banner at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1024 });
    const initialRead = page.waitForResponse(response => new URL(response.url()).pathname === '/' && response.request().resourceType() !== 'document');
    const faults = await open(page, 'accounts');
    await (await initialRead).finished();
    const response = await page.request.get(env('CONSOLE_E2E_BASE') + '/');
    expect(response.ok()).toBe(true);
    const served = await response.text();
    await expect(page.locator('meta[name="splice-page-fingerprint"]')).toHaveCount(1);
    const loaded = await page.locator('meta[name="splice-page-fingerprint"]').getAttribute('content');
    expect(loaded).toBe(await fingerprint(served));
    expect(await fingerprint(served + '<!-- synthetic changed bytes with a copied tag -->')).not.toBe(loaded);
    await painted(page);
    await expect(page.locator('.stale-page')).toHaveCount(0);
    expect(faults.pageErrors).toEqual([]);
    expect(faults.failedReads).toEqual([]);
  });
  test(`the loaded document stays current after a different first reread and its own page returns at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1024 });
    const state = await staleFixture(page);
    state.different = true;
    const faults = await open(page, 'accounts');
    await expect.poll(() => state.rereads).toBe(1);
    state.different = false;
    state.boot++;
    await expect.poll(() => state.rereads, { timeout: 25_000 }).toBe(2);
    await painted(page);
    await expect(page.locator('.stale-page')).toHaveCount(0);
    expect(faults.pageErrors).toEqual([]);
    expect(faults.failedReads).toEqual([]);
  });
  test(`a changed served document offers reload and clears when the loaded page returns at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1024 });
    const state = await staleFixture(page);
    const faults = await open(page, 'accounts');
    await expect.poll(() => state.rereads).toBe(1);
    await painted(page);
    await expect(page.locator('.stale-page')).toHaveCount(0);
    state.different = true;
    state.boot++;
    await expect(page.locator('.stale-page')).toHaveCount(1, { timeout: 25_000 });
    await expect(page.locator('.stale-page')).toContainText('The served page changed after this tab opened');
    await expect(page.getByRole('button', { name: 'Reload the page', exact: true })).toBeVisible();
    state.different = false;
    state.boot++;
    await expect.poll(() => state.rereads, { timeout: 25_000 }).toBe(3);
    await expect(page.locator('.stale-page')).toHaveCount(0);
    expect(faults.pageErrors).toEqual([]);
    expect(faults.failedReads).toEqual([]);
  });
  test(`an unstamped loaded document never claims stale after another boot at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1024 });
    const state = await staleFixture(page, true);
    const faults = await open(page, 'accounts');
    await expect.poll(() => state.healthReads).toBe(1);
    state.different = true;
    state.boot++;
    await expect.poll(() => state.healthReads, { timeout: 25_000 }).toBe(2);
    await expect.poll(() => state.healthReads, { timeout: 25_000 }).toBe(3);
    await painted(page);
    await expect(page.locator('.stale-page')).toHaveCount(0);
    expect(state.rereads).toBe(0);
    expect(faults.pageErrors).toEqual([]);
    expect(faults.failedReads).toEqual([]);
  });
}

test('the key-unlock address leaves no key in the address or history entry', async ({ page }) => {
  const faults = watch(page);
  const key = env('CONSOLE_E2E_KEY');
  await page.goto('about:blank#synthetic-before-unlock');
  await page.goto(env('CONSOLE_E2E_BASE') + '/#k=' + encodeURIComponent(key));
  await expect(page.getByRole('heading', { name: 'Accounts', exact: true })).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Pages', exact: true })).toBeVisible();
  expect(new URL(page.url()).hash).toBe('#/accounts');
  expect(page.url()).not.toContain(key);
  expect(await page.evaluate(() => location.href)).not.toContain(key);
  expect(await page.evaluate(() => localStorage.getItem('myx-mgmt-key'))).toBe(key);
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Accounts', exact: true })).toBeVisible();
  await page.goBack();
  expect(page.url()).toBe('about:blank#synthetic-before-unlock');
  await page.goForward();
  await expect(page.getByRole('heading', { name: 'Accounts', exact: true })).toBeVisible();
  expect(page.url()).not.toContain(key);
  await assertHealthy(page, faults);
});

test('real working waiting and finished rows group and a session opens on its own page', async ({ page }) => {
  const faults = await open(page, 'sessions');
  const payload = await read<SessionsPayload>(page, '/api/sessions');
  expect(payload.sessions.find((row) => row.session_id === STACK.sender.id)?.status).toBe('busy');
  expect(payload.sessions.find((row) => row.session_id === STACK.peer.id)?.status).toBe('waiting');
  expect(payload.sessions.find((row) => row.session_id === FINISHED.id)?.status).toBe('idle');
  await expect(page.getByRole('region', { name: 'Working', exact: true }).getByRole('link', { name: STACK.sender.name, exact: true })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Needs you', exact: true }).getByRole('link', { name: STACK.peer.name, exact: true })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Idle', exact: true }).getByRole('link', { name: FINISHED.name, exact: true })).toBeVisible();
  await page.getByRole('link', { name: STACK.sender.name, exact: true }).click();
  await expect(page).toHaveURL(new RegExp('#/sessions/' + STACK.sender.id + '$'));
  await expect(page.getByRole('heading', { name: STACK.sender.name, level: 1, exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Sessions', level: 1, exact: true })).toHaveCount(0);
  await expect(page.getByRole('region', { name: 'Conversation', exact: true })).toContainText('Synthetic answer');
  await expect(page.getByRole('region', { name: 'Conversation', exact: true }).getByText('code', { exact: true })).toBeVisible();
  await assertHealthy(page, faults);
});

test('Repo separates same-name roots with distinct titles and links while a single root keeps its name', async ({ page }) => {
  const roots = ['/synthetic/a/cohort', '/synthetic/b/cohort'] as const;
  let shared = false;
  await page.route(url => url.pathname === '/api/sessions', async route => {
    const response = await route.fetch();
    const body = await response.json() as SessionsPayload;
    body.sessions = body.sessions.filter(row => row.session_id === STACK.sender.id || row.session_id === STACK.peer.id).map(row => ({
      ...row, repo: { root: roots[shared || row.session_id === STACK.sender.id ? 0 : 1] },
    }));
    await route.fulfill({ response, json: body });
  });
  await page.route(url => url.pathname === '/api/projects', async route => {
    const response = await route.fetch();
    const body = await response.json() as ProjectsPayload;
    const base = body.projects[0];
    if (base === undefined) throw new Error('the isolated stack must provide a synthetic project');
    body.projects = (shared ? roots.slice(0, 1) : roots).map(root => ({ ...base, id: root, root }));
    await route.fulfill({ response, json: body });
  });
  const faults = await open(page, 'sessions?group=repo');
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: 1024 });
    for (const [root, name] of [[roots[0], STACK.sender.name], [roots[1], STACK.peer.name]] as const) {
      const group = page.getByRole('region', { name: 'cohort · ' + root, exact: true });
      await expect(group).toBeVisible();
      await expect(group.locator('li.card')).toHaveCount(1);
      await expect(group.getByRole('link', { name, exact: true })).toBeVisible();
      await expect(group.getByRole('link', { name: 'Open the project', exact: true })).toHaveAttribute('href', '#/projects/' + encodeURIComponent(root));
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  }
  shared = true;
  await page.reload();
  const single = page.getByRole('region', { name: 'cohort', exact: true });
  await expect(single.locator('li.card')).toHaveCount(2);
  await expect(single.getByRole('heading', { name: 'cohort', exact: true })).toBeVisible();
  await expect(single.getByRole('link', { name: 'Open the project', exact: true })).toHaveAttribute('href', '#/projects/' + encodeURIComponent(roots[0]));
  await expect(page.getByRole('heading', { name: /cohort · / })).toHaveCount(0);
  await assertHealthy(page, faults);
});

test('a session opens at its newest message, scrolled to the bottom', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 420 });
  const faults = await open(page, 'sessions/' + STACK.sender.id);
  await expect(page.getByText('Synthetic answer')).toBeVisible();
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollHeight - (window.scrollY + window.innerHeight)), { timeout: 5000 }).toBeLessThan(4);
  expect(await page.evaluate(() => document.documentElement.scrollHeight), 'the page is taller than the window').toBeGreaterThan(420);
  await assertHealthy(page, faults);
});

// The retired global feed is split between Sessions and the whole Health report in diagnostics.spec.ts.
test('Sessions includes waiting sessions but not working or finished sessions in Needs you', async ({ page }) => {
  const faults = await open(page, 'sessions');
  const waiting = page.getByRole('region', { name: 'Needs you', exact: true });
  await expect(waiting.getByRole('link', { name: STACK.peer.name, exact: true })).toBeVisible();
  await expect(waiting.getByRole('link', { name: STACK.sender.name, exact: true })).toHaveCount(0);
  await expect(waiting.getByRole('link', { name: FINISHED.name, exact: true })).toHaveCount(0);
  await assertHealthy(page, faults);
});

test('Settings keys are hidden until the row or Advanced switch is asked for', async ({ page }) => {
  const faults = await open(page, 'settings');
  const reveal = page.getByRole('button', { name: 'Show this setting’s key', exact: true });
  await expect(reveal.first()).toBeVisible();
  await expect(page.getByRole('main').getByText('usageWarnPct', { exact: true })).toHaveCount(0);
  await reveal.first().click();
  await expect(reveal.first()).toHaveAttribute('aria-pressed', 'true');
  await expect(reveal.first().getByText('usageWarnPct', { exact: true })).toBeVisible();
  await reveal.first().click();
  await expect(reveal.first()).toHaveAttribute('aria-pressed', 'false');
  const sections = page.getByRole('navigation', { name: 'Settings sections', exact: true });
  await sections.getByRole('link', { name: 'Advanced', exact: true }).click();
  await page.getByRole('switch', { name: 'Show setting keys', exact: true }).click();
  await sections.getByRole('link', { name: 'General', exact: true }).click();
  await expect(reveal.first().getByText('usageWarnPct', { exact: true })).toBeVisible();
  await expect(reveal.filter({ has: page.getByText('debug', { exact: true }) })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('button', { name: 'Show this setting’s key', exact: true }).first()).toHaveAttribute('aria-pressed', 'true');
  await assertHealthy(page, faults);
});

test('Fleet opens a plan on its own page', async ({ page }) => {
  const faults = await open(page, 'models');
  const plan = page.getByRole('main').getByRole('link', { name: STACK.oauthHead, exact: true });
  await expect(plan).toBeVisible();
  await plan.click();
  await expect(page).toHaveURL(new RegExp('#/models/' + STACK.oauthHead + '$'));
  await expect(page.getByRole('heading', { name: STACK.oauthHead, level: 1, exact: true })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Plan windows', exact: true })).toBeVisible();
  await assertHealthy(page, faults);
});

for (const method of ['keyboard', 'pointer'] as const) {
  test(method + ' dragging plan order survives reload', async ({ page }) => {
    const faults = await open(page, 'models');
    const main = page.getByRole('main');
    const cards = main.locator('li.card');
    await expect(cards).toHaveCount(3);
    const links = main.getByRole('link').filter({ hasText: /^e2e-/ });
    const before = await links.allTextContents();
    if (method === 'keyboard') {
      await cards.first().focus();
      await page.keyboard.press('Space');
      await expect(page.getByRole('status')).toContainText('over droppable area e2e-codex.');
      // KeyboardSensor attaches on a deferred task; let the real document finish activation/layout.
      await page.evaluate(() => new Promise<void>((resolve) => {
        requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
      }));
      const first = await cards.first().boundingBox();
      const next = await cards.nth(1).boundingBox();
      if (first === null || next === null) throw new Error('plan cards have no layout');
      const x = next.x + next.width / 2 - first.x - first.width / 2;
      const y = next.y + next.height / 2 - first.y - first.height / 2;
      await page.keyboard.press(Math.abs(x) > Math.abs(y) ? x > 0 ? 'ArrowRight' : 'ArrowLeft' : y > 0 ? 'ArrowDown' : 'ArrowUp');
      await expect(page.getByRole('status')).toContainText('over droppable area e2e-codex-solo');
      await page.keyboard.press('Space');
    } else {
      const startField = cards.first().locator('.quiet-meta').first();
      const finishField = cards.nth(1).locator('.quiet-meta').first();
      await finishField.scrollIntoViewIfNeeded();
      await startField.scrollIntoViewIfNeeded();
      const start = await startField.boundingBox();
      const finish = await finishField.boundingBox();
      if (start === null || finish === null) throw new Error('plan card bodies have no layout');
      await page.mouse.move(start.x + start.width / 2, start.y + start.height / 2);
      await page.mouse.down();
      await page.mouse.move(finish.x + finish.width / 2, finish.y + finish.height / 2, { steps: 12 });
      await expect(page.getByRole('status')).toContainText('over droppable area e2e-codex-solo');
      await page.mouse.up();
    }
    await expect.poll(() => links.allTextContents()).not.toEqual(before);
    const after = await links.allTextContents();
    expect([...after].sort()).toEqual([...before].sort());
    await page.reload();
    await expect.poll(() => links.allTextContents()).toEqual(after);
    await assertHealthy(page, faults);
  });
}
