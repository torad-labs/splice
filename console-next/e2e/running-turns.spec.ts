// NEW: V4-444 — indistinguishable live siblings retain separate identities and disappear independently.
import { expect, test } from '@playwright/test';
import type { ControlStatusPayload, HeadsPayload } from '../src/types/core';
import type { EconomicsPayload } from '../src/types/economics';
import type { TurnRowWire } from '../src/types/perf';
import type { TeamRow } from '../src/types/teams';
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
  const faults = await open(page, 'requests');
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
      const waiting = page.getByRole('region', { name: 'Needs you', exact: true });
      await expect(waiting.getByRole('link', { name: STACK.peer.name, exact: true })).toBeVisible();
      await expect(waiting.getByRole('link', { name: STACK.sender.name, exact: true })).toHaveCount(0);
      await page.getByRole('link', { name: STACK.sender.name, exact: true }).click();
      await expect(page.locator('header.top')).toContainText('Working');
      await expect(page.getByRole('button', { name: 'Stop the turn', exact: true })).toBeVisible();
      await page.getByRole('navigation', { name: 'Pages', exact: true }).getByRole('link', { name: 'Requests', exact: true }).click();
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

for (const [authKind, family, colour] of [['api-key', 'local', 'local'], ['bearer', 'openrouter', 'router'], ['api-key', 'anthropic', 'claude']] as const) {
  test(`the registry ${family} family keeps one colour with auth ${authKind} across every head view`, async ({ page }) => {
    const at = Date.now();
    const headKey = STACK.keyHead;
    const hue = new RegExp('\\b' + colour + '\\b');
    const turn: TurnRowWire = { ts: at, model: STACK.model, outcome: 'ok', compact: false, session: STACK.sender.id.slice(0, 8), session_id: STACK.sender.id, account: null, cache_cold: null, turn: null, response_message_id: null, recv: 0, parse: 1, build: 2, headers: 3, first_byte: 10, stream_end: 99, finish: 100, total: 100 };
    const team: TeamRow = { id: 'colour-team', name: 'Synthetic colour team', goal: 'Keep provider colours consistent.', features: [], repo: '/synthetic/e2e-repo', archived: false, created_epoch_millis: at, updated_epoch_millis: at, idempotency_key: null, create_fingerprint: null, slots: [{ id: 'builder', role: 'Builder', head: headKey, model: STACK.model, account: null, lead: true, instructions: null, session: STACK.sender.id, instructions_updated_epoch_millis: null, sessions_history: [STACK.sender.id] }] };
    await page.route('**/api/status', async (route) => {
      const response = await route.fetch();
      const body = await response.json() as ControlStatusPayload;
      const head = body.registry.find((row) => row.key === headKey);
      if (head === undefined) throw new Error('synthetic registry head missing');
      Object.assign(head, { authKind, family });
      await route.fulfill({ response, json: body });
    });
    await page.route('**/api/heads', async (route) => {
      const response = await route.fetch();
      const body = await response.json() as HeadsPayload;
      const head = body.heads.find((row) => row.key === headKey);
      if (head?.gate == null) throw new Error('synthetic key gate missing');
      head.authKind = authKind;
      head.gate.live = [{ label: STACK.sender.id.slice(0, 8) + ' ' + STACK.model, compact: false, phase: 'streaming', age_ms: 5000, idle_ms: 50 }];
      head.gate.inflight = 1;
      await route.fulfill({ response, json: body });
    });
    await page.route('**/api/sessions', async (route) => {
      const response = await route.fetch();
      const body = await response.json() as SessionsPayload;
      const sender = body.sessions.find((row) => row.session_id === STACK.sender.id);
      if (sender === undefined) throw new Error('synthetic sender missing');
      Object.assign(sender, { head: headKey, status: 'busy', availability: 'live' });
      await route.fulfill({ response, json: body });
    });
    await page.route((url) => url.pathname === '/api/perf/turns' && url.searchParams.get('head') === headKey, (route) => route.fulfill({ json: { heads: [{ key: headKey, label: headKey, count: 1, returned: 1, rows: [turn] }] } }));
    await page.route('**/api/economics', async (route) => {
      const response = await route.fetch();
      const body = await response.json() as EconomicsPayload;
      const sample = body.heads.find((head) => head.key === STACK.oauthHead);
      if (sample === undefined) throw new Error('synthetic economics sample missing');
      body.heads = [...body.heads.filter((head) => head.key !== headKey), { ...sample, key: headKey }];
      await route.fulfill({ response, json: body });
    });
    await page.route('**/api/teams', (route) => route.fulfill({ json: { teams: [team] } }));
    await page.route('**/api/teams/colour-team/*', (route) => {
      const leaf = new URL(route.request().url()).pathname.split('/').at(-1);
      const base = { team_id: team.id, day_start_epoch_millis: at };
      const body = leaf === 'chat' ? { ...base, packet_note: 'Synthetic capture.', messages: [] }
        : leaf === 'activity' ? { ...base, sample_interval_note: 'Synthetic capture.', upstream_label_queries: 0, entries: [] }
          : { team_id: team.id, heads_read: [], unattributed_turns: 0, oldest_turn_epoch_millis: null, roles: [], slots: [] };
      return route.fulfill({ json: body });
    });
    const faults = await open(page, 'models');
    const nav = page.getByRole('navigation', { name: 'Pages', exact: true });
    const fleet = page.locator('li.card').filter({ has: page.getByRole('link', { name: headKey, exact: true }) });
    await expect(fleet).toHaveClass(hue);
    await fleet.getByRole('link', { name: headKey, exact: true }).click();
    await expect(page.getByRole('region', { name: 'Plan windows', exact: true })).toHaveClass(hue);
    await nav.getByRole('link', { name: 'Sessions', exact: true }).click();
    const session = page.locator('li.card').filter({ has: page.getByRole('link', { name: STACK.sender.name, exact: true }) });
    await expect(session).toHaveClass(hue);
    await session.getByRole('link', { name: STACK.sender.name, exact: true }).click();
    await expect(page.getByRole('region', { name: 'Conversation', exact: true })).toHaveClass(hue);
    await nav.getByRole('link', { name: 'Requests', exact: true }).click();
    await expect(page.getByRole('listitem', { name: STACK.sender.name + ', ' + headKey, exact: true })).toHaveClass(hue);
    await page.locator('.turn').filter({ hasText: headKey }).getByRole('link', { name: STACK.sender.name, exact: true }).click();
    await expect(page.getByRole('region', { name: 'Where the time went', exact: true }).locator('..')).toHaveClass(hue);
    await nav.getByRole('link', { name: 'Usage', exact: true }).click();
    await expect(page.locator('li.uplan').filter({ hasText: headKey })).toHaveClass(hue);
    await nav.getByRole('link', { name: 'Sessions', exact: true }).click();
    await page.goto(new URL('/#/teams/' + team.id, page.url()).href);
    await expect(page.getByRole('listitem', { name: 'Builder', exact: true })).toHaveClass(hue);
    await assertHealthy(page, faults);
    await page.unrouteAll({ behavior: 'wait' });
  });
}
