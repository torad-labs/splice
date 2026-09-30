// NEW: V4-444 — replacement-console journeys over real daemon payloads, never mocked API rows.
import { expect, test } from '@playwright/test';
import { STACK } from './stack';
import type { DoctorPayload } from '../src/types/doctor';
import { checkTitle } from '../src/lib/words-needs';
import type { SessionsPayload } from '../src/types/sessions';
import { FINISHED } from './setup';
import { assertHealthy, env, open, read, watch } from './support';

test('the key-unlock address leaves no key in the address or history entry', async ({ page }) => {
  const faults = watch(page);
  const key = env('CONSOLE_E2E_KEY');
  await page.goto('about:blank#synthetic-before-unlock');
  await page.goto(env('CONSOLE_E2E_BASE') + '/#k=' + encodeURIComponent(key));
  await expect(page.getByRole('heading', { name: 'Needs you', exact: true })).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Pages', exact: true })).toBeVisible();
  expect(new URL(page.url()).hash).toBe('#/needs-you');
  expect(page.url()).not.toContain(key);
  expect(await page.evaluate(() => location.href)).not.toContain(key);
  expect(await page.evaluate(() => localStorage.getItem('myx-mgmt-key'))).toBe(key);
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Needs you', exact: true })).toBeVisible();
  await page.goBack();
  expect(page.url()).toBe('about:blank#synthetic-before-unlock');
  await page.goForward();
  await expect(page.getByRole('heading', { name: 'Needs you', exact: true })).toBeVisible();
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

test('a session opens at its newest message, scrolled to the bottom, with no dead message box', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 420 });
  const faults = await open(page, 'sessions/' + STACK.sender.id);
  await expect(page.getByText('Synthetic answer')).toBeVisible();
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollHeight - (window.scrollY + window.innerHeight)), { timeout: 5000 }).toBeLessThan(4);
  expect(await page.evaluate(() => document.documentElement.scrollHeight), 'the page is taller than the window').toBeGreaterThan(420);
  await expect(page.getByPlaceholder(/Message the session/)).toHaveCount(0);
  await assertHealthy(page, faults);
});

test('Needs you includes the waiting session but not working or finished sessions and only warning or failed doctor checks', async ({ page }) => {
  const faults = await open(page, 'needs-you');
  const doctor = await read<DoctorPayload>(page, '/api/doctor');
  const main = page.getByRole('main');
  await expect(main.getByRole('listitem', { name: 'Waiting on you: ' + STACK.peer.name, exact: true })).toBeVisible();
  await expect(main.getByRole('listitem', { name: new RegExp(': ' + STACK.sender.name + '$') })).toHaveCount(0);
  await expect(main.getByRole('listitem', { name: new RegExp(': ' + FINISHED.name + '$') })).toHaveCount(0);
  // Actual check ids/statuses are the denominator, not a fixture shaped to the view.
  await expect(main.getByRole('listitem', { name: /^Doctor: / }).first()).toBeVisible();
  const labels = await main.getByRole('listitem', { name: /^Doctor: / }).evaluateAll((items) =>
    items.map((item) => (item.getAttribute('aria-label') ?? '').replace(/^Doctor: /, '').replace(/ \(\d+\)$/, '')));
  for (const label of labels) {
    const members = doctor.checks.filter((check) => checkTitle(check.id) === label || checkTitle(check.id.split(':')[0] ?? check.id) === label);
    expect(members.length, 'visible doctor item must come from a real check: ' + label).toBeGreaterThan(0);
    expect(members.every((check) => check.status === 'warn' || check.status === 'fail'), label + ' must need a person').toBe(true);
  }
  for (const check of doctor.checks.filter((check) => check.status === 'ok' || check.status === 'info')) {
    if (!doctor.checks.some((other) => checkTitle(other.id) === checkTitle(check.id) && (other.status === 'warn' || other.status === 'fail'))) {
      await expect(main.getByRole('listitem', { name: 'Doctor: ' + checkTitle(check.id), exact: true })).toHaveCount(0);
    }
  }
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
  await page.getByRole('switch', { name: 'Show setting keys', exact: true }).click();
  await expect(reveal.first().getByText('usageWarnPct', { exact: true })).toBeVisible();
  await expect(reveal.filter({ has: page.getByText('debug', { exact: true }) })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('button', { name: 'Show this setting’s key', exact: true }).first()).toHaveAttribute('aria-pressed', 'true');
  await assertHealthy(page, faults);
});

test('Fleet opens a plan on its own page', async ({ page }) => {
  const faults = await open(page, 'fleet');
  const plan = page.getByRole('main').getByRole('link', { name: STACK.oauthHead, exact: true });
  await expect(plan).toBeVisible();
  await plan.click();
  await expect(page).toHaveURL(new RegExp('#/fleet/' + STACK.oauthHead + '$'));
  await expect(page.getByRole('heading', { name: STACK.oauthHead, level: 1, exact: true })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Plan windows', exact: true })).toBeVisible();
  await assertHealthy(page, faults);
});

for (const method of ['keyboard', 'pointer'] as const) {
  test(method + ' dragging plan order survives reload', async ({ page }) => {
    const faults = await open(page, 'fleet');
    const main = page.getByRole('main');
    const handles = main.getByRole('button', { name: 'Drag to reorder', exact: true });
    await expect(handles).toHaveCount(3);
    const links = main.getByRole('link').filter({ hasText: /^e2e-/ });
    const before = await links.allTextContents();
    if (method === 'keyboard') {
      await handles.first().focus();
      await page.keyboard.press('Space');
      await expect(page.getByRole('status')).toContainText('over droppable area e2e-codex.');
      // KeyboardSensor attaches on a deferred task; let the real document finish activation/layout.
      await page.evaluate(() => new Promise<void>((resolve) => {
        requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
      }));
      await page.keyboard.press('ArrowRight');
      await expect(page.getByRole('status')).toContainText('over droppable area e2e-codex-solo');
      await page.keyboard.press('Space');
    } else {
      const start = await handles.first().boundingBox();
      const finish = await handles.nth(1).boundingBox();
      if (start === null || finish === null) throw new Error('plan drag handles have no layout');
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
