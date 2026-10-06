// NEW: V4-444 — replacement-console journeys over real daemon payloads, never mocked API rows.
import { expect, test } from '@playwright/test';
import { STACK } from './stack';
import type { SessionsPayload } from '../src/types/sessions';
import { FINISHED } from './setup';
import { assertHealthy, env, open, read, watch } from './support';

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
