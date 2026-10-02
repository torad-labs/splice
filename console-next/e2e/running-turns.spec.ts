// NEW: V4-444 — indistinguishable live siblings retain separate identities and disappear independently.
import { expect, test } from '@playwright/test';
import type { HeadsPayload } from '../src/types/core';
import type { SessionsPayload } from '../src/types/sessions';
import { assertHealthy, open } from './support';
import { STACK } from './stack';

test('same-label same-age live siblings do not collapse and one removal leaves its twin', async ({ page }) => {
  let count = 2;
  await page.route('**/api/heads', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as HeadsPayload;
    const head = body.heads.find((entry) => entry.key === STACK.oauthHead);
    if (head === undefined || head.gate === null) throw new Error('synthetic gate missing');
    head.gate.live = Array.from({ length: count }, () => ({
      label: 'synthetic-twin', compact: false, phase: 'streaming', age_ms: 5000, idle_ms: 50,
    }));
    head.gate.inflight = count;
    await route.fulfill({ response, json: body });
  });
  const faults = await open(page, 'turns');
  const twins = page.getByRole('listitem', { name: 'synthetic-twin, ' + STACK.oauthHead, exact: true });
  await expect(twins).toHaveCount(2);
  await expect(twins.first()).toContainText('Streaming its answer');
  count = 1;
  await expect(twins).toHaveCount(1, { timeout: 20_000 });
  count = 0;
  await expect(twins).toHaveCount(0, { timeout: 20_000 });
  expect(faults.pageErrors).toEqual([]);
  expect(faults.consoleErrors.filter((line) => /same key|unique.*key/i.test(line))).toEqual([]);
  await page.unrouteAll({ behavior: 'wait' });
});

for (const status of ['busy', 'shell']) {
  for (const phase of ['connect', 'streaming']) {
    test(`a quiet ${status} session stays Working during provider ${phase}, never needs intervention and still offers Stop`, async ({ page }) => {
      const age = 40 * 60_000;
      const idle = 7 * 60_000;
      await page.route('**/api/sessions', async (route) => {
        const response = await route.fetch();
        const body = await response.json() as SessionsPayload;
        const sender = body.sessions.find((row) => row.session_id === STACK.sender.id);
        if (sender === undefined) throw new Error('synthetic session missing');
        Object.assign(sender, { status, head: STACK.oauthHead, availability: 'live', status_updated_at: Date.now() - age });
        await route.fulfill({ response, json: body });
      });
      await page.route('**/api/heads', async (route) => {
        const response = await route.fetch();
        const body = await response.json() as HeadsPayload;
        const head = body.heads.find((row) => row.key === STACK.oauthHead);
        if (head === undefined || head.gate === null) throw new Error('synthetic gate missing');
        head.gate.live = [{ label: STACK.sender.id.slice(0, 8) + ' ' + STACK.model, compact: false, phase, age_ms: age, idle_ms: idle }];
        head.gate.inflight = 1;
        head.gate.stream_idle_ms = 90_000;
        await route.fulfill({ response, json: body });
      });
      await page.route('**/api/heads/' + STACK.oauthHead + '/turns/live', (route) => route.fulfill({ json: {
        head: STACK.oauthHead,
        turns: [{ id: 'synthetic-quiet-provider', session: STACK.sender.id, model: STACK.model, compact: false, age_ms: age, idle_ms: idle, stopped: false }],
      } }));
      const liveRead = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/heads/' + STACK.oauthHead + '/turns/live');
      const faults = await open(page, 'sessions');
      await (await liveRead).finished();
      await page.evaluate(() => new Promise<void>((resolve) => requestAnimationFrame(() => resolve())));
      await expect(page.getByRole('region', { name: 'Working', exact: true }).getByRole('link', { name: STACK.sender.name, exact: true })).toBeVisible();
      await expect(page.getByRole('region', { name: 'Needs you', exact: true }).getByRole('link', { name: STACK.peer.name, exact: true })).toBeVisible();
      await expect(page.getByRole('main')).not.toContainText('Stuck');
      await page.getByRole('navigation', { name: 'Pages', exact: true }).getByRole('link', { name: 'Needs you', exact: true }).click();
      await expect(page.getByRole('listitem', { name: 'Waiting on you: ' + STACK.peer.name, exact: true })).toBeVisible();
      await expect(page.getByRole('listitem', { name: new RegExp(': ' + STACK.sender.name + '$') })).toHaveCount(0);
      await page.getByRole('navigation', { name: 'Pages', exact: true }).getByRole('link', { name: 'Sessions', exact: true }).click();
      await page.getByRole('link', { name: STACK.sender.name, exact: true }).click();
      await expect(page.locator('header.top')).toContainText('Working');
      await expect(page.getByRole('button', { name: 'Stop the turn', exact: true })).toBeVisible();
      await page.getByRole('navigation', { name: 'Pages', exact: true }).getByRole('link', { name: 'Turns', exact: true }).click();
      const running = page.getByRole('listitem', { name: STACK.sender.name + ', ' + STACK.oauthHead, exact: true });
      await expect(running).toContainText('No word from the model for 7 min; splice is keeping the turn open.');
      await expect(running).toContainText('Working');
      await expect(running).not.toContainText('Stuck');
      await expect(running).not.toHaveClass(/attn/);
      await expect(running.getByRole('button', { name: 'Stop the turn', exact: true })).toBeVisible();
      await assertHealthy(page, faults);
      await page.unrouteAll({ behavior: 'wait' });
    });
  }
}
