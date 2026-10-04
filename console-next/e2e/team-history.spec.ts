// NEW: V4-444 — local-day team reads, retention boundaries and immediate entry.
import { expect, test } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import type { TeamRow } from '../src/types/teams';
import { env, FIRST_READ_MS, open, read } from './support';
import { sendHandOff, STACK } from './stack';

const TEAM: TeamRow = {
  id: 'synthetic-history-team', name: 'Synthetic history team', goal: 'Read earlier handoffs',
  features: ['history'], repo: '/synthetic/history', archived: false,
  created_epoch_millis: 1, updated_epoch_millis: 1, idempotency_key: null, create_fingerprint: null,
  slots: [
    { id: 'lead', role: 'lead', head: STACK.oauthHead, model: null, account: null, lead: true,
      instructions: null, session: STACK.sender.id, sessions_history: [STACK.sender.id], instructions_updated_epoch_millis: null },
    { id: 'peer', role: 'builder', head: STACK.oauthHead, model: null, account: null, lead: false,
      instructions: null, session: STACK.peer.id, sessions_history: [STACK.peer.id], instructions_updated_epoch_millis: null },
  ],
};

function days() {
  const now = new Date();
  const midnight = (offset: number) => new Date(now.getFullYear(), now.getMonth(), now.getDate() + offset).getTime();
  return { today: midnight(0), yesterday: midnight(-1), older: midnight(-2), tomorrow: midnight(1) };
}

test('team entry reads immediately and yesterday chat and activity remain selectable independently of empty today', async ({ page }) => {
  const { today, yesterday, older, tomorrow } = days();
  const asked: { from: number; to: number }[] = [];
  const activity: number[] = [];
  await page.route('**/api/teams', (route) => route.fulfill({ json: { teams: [TEAM] } }));
  await page.route((url) => url.pathname === '/api/teams/' + TEAM.id + '/chat', (route) => {
    const query = new URL(route.request().url()).searchParams;
    const from = Number(query.get('from'));
    const to = Number(query.get('to'));
    asked.push({ from, to });
    return route.fulfill({ json: {
      team_id: TEAM.id, day_start_epoch_millis: from, packet_note: 'Synthetic packet note',
      messages: from === yesterday && to === today ? [{
        at: yesterday + 12 * 3600_000, from: STACK.sender.id, from_slot: 'lead', from_head: STACK.oauthHead,
        to: STACK.peer.id, to_slot: 'peer', packet: null, text: 'Yesterday synthetic handoff reached the builder.',
        text_source: '/synthetic/transcript.jsonl', missing_reason: null,
      }] : [],
    } });
  });
  await page.route((url) => url.pathname === '/api/teams/' + TEAM.id + '/activity', (route) => {
    const from = Number(new URL(route.request().url()).searchParams.get('from'));
    activity.push(from);
    return route.fulfill({ json: {
      team_id: TEAM.id, day_start_epoch_millis: from, sample_interval_note: '30 s', upstream_label_queries: 0,
      entries: from === yesterday ? [{
        at: yesterday + 12 * 3600_000, session: STACK.sender.id, slot: 'lead', head: STACK.oauthHead,
        label: 'Yesterday synthetic activity sample', detail: null,
      }] : [],
    } });
  });
  await page.route('**/api/teams/' + TEAM.id + '/economics', (route) => route.fulfill({ json: {
    team_id: TEAM.id, heads_read: [STACK.oauthHead], unattributed_turns: 0,
    oldest_turn_epoch_millis: null, roles: [], slots: [],
  } }));
  const started = Date.now();
  const faults = await open(page, 'teams/' + TEAM.id);
  await expect.poll(() => asked.some((range) => range.from === today && range.to === tomorrow), { timeout: 4000 }).toBe(true);
  await expect.poll(() => activity.includes(today), { timeout: 4000 }).toBe(true);
  expect(Date.now() - started, 'entry must read before the ten-second poll').toBeLessThan(10_000);
  await expect(page.getByText('The seats sent each other nothing.', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Earlier', exact: true }).click();
  await expect.poll(() => asked.some((range) => range.from === yesterday && range.to === today)).toBe(true);
  await expect.poll(() => activity.includes(yesterday)).toBe(true);
  const message = page.getByRole('listitem').filter({ hasText: 'Yesterday synthetic handoff' });
  await expect(message).toContainText('lead to builder');
  await expect(message).toContainText('Yesterday synthetic handoff reached the builder.');
  await expect(page.getByRole('listitem').filter({ hasText: 'Yesterday synthetic activity sample' })).toBeVisible();
  await expect(page.getByText('The seats sent each other nothing.', { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Earlier', exact: true }).click();
  await expect.poll(() => activity.includes(older)).toBe(true);
  await expect(page.getByText('The seats sent each other nothing.', { exact: true })).toBeVisible();
  await expect(page.getByText('Nothing was sampled.', { exact: true })).toBeVisible();
  await expect(message).toHaveCount(0);
  await expect(page.getByRole('listitem').filter({ hasText: 'Yesterday synthetic activity sample' })).toHaveCount(0);
  await page.getByRole('button', { name: 'Later', exact: true }).click();
  await expect(message).toBeVisible();
  await expect(page.getByRole('listitem').filter({ hasText: 'Yesterday synthetic activity sample' })).toBeVisible();
  await page.getByRole('button', { name: 'Later', exact: true }).click();
  await expect(page.getByRole('group', { name: 'Day', exact: true })).toContainText('Today');
  await expect(page.getByText('The seats sent each other nothing.', { exact: true })).toBeVisible();
  await expect(message).toHaveCount(0);
  expect(faults.pageErrors).toEqual([]);
});

test('an expired team day prints whole retention reasons and prevents browsing beyond the retained boundary', async ({ page }) => {
  const { yesterday, older } = days();
  const chatReason = 'Synthetic message history past activityRetentionDays is not kept.';
  const activityReason = 'Synthetic activity labels for this local day are no longer kept.';
  await page.route('**/api/teams', (route) => route.fulfill({ json: { teams: [TEAM] } }));
  await page.route((url) => url.pathname === '/api/teams/' + TEAM.id + '/chat', (route) => {
    const from = Number(new URL(route.request().url()).searchParams.get('from'));
    return route.fulfill({ json: {
      team_id: TEAM.id, day_start_epoch_millis: from, packet_note: 'Synthetic packet note', messages: [],
      ...(from === older ? { state: 'not_kept', oldest_kept_epoch_millis: yesterday, reason: chatReason } : {}),
    } });
  });
  const asked: number[] = [];
  await page.route((url) => url.pathname === '/api/teams/' + TEAM.id + '/activity', (route) => {
    const from = Number(new URL(route.request().url()).searchParams.get('from'));
    asked.push(from);
    return route.fulfill({ json: {
      team_id: TEAM.id, day_start_epoch_millis: from, sample_interval_note: '30 s', upstream_label_queries: 0, entries: [],
      ...(from === older ? { state: 'not_kept', reason: activityReason } : {}),
    } });
  });
  await page.route('**/api/teams/' + TEAM.id + '/economics', (route) => route.fulfill({ json: {
    team_id: TEAM.id, heads_read: [STACK.oauthHead], unattributed_turns: 0,
    oldest_turn_epoch_millis: null, roles: [], slots: [],
  } }));
  await open(page, 'teams/' + TEAM.id);
  await page.getByRole('button', { name: 'Earlier', exact: true }).click();
  await expect.poll(() => asked.includes(yesterday)).toBe(true);
  await page.getByRole('button', { name: 'Earlier', exact: true }).click();
  await expect.poll(() => asked.includes(older)).toBe(true);
  await expect(page.getByRole('main')).toContainText(chatReason);
  await expect(page.getByRole('main')).toContainText(activityReason);
  await expect(page.getByRole('button', { name: 'Earlier', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: 'Later', exact: true }).click();
  await expect(page.getByRole('main')).not.toContainText(chatReason);
  await expect(page.getByRole('button', { name: 'Earlier', exact: true })).toBeEnabled();
});

test('unfiled socket handoffs say another session and filed handoffs name the role without exposing either socket', async ({ page }) => {
  const socket = 'uds:/synthetic/700.sock';
  const at = Date.now() - 60_000;
  await page.route('**/api/teams', (route) => route.fulfill({ json: { teams: [TEAM] } }));
  await page.route((url) => url.pathname === '/api/teams/' + TEAM.id + '/chat', (route) => route.fulfill({ json: {
    team_id: TEAM.id, day_start_epoch_millis: days().today, packet_note: 'Synthetic packet note',
    messages: [null, 'lead'].map((toSlot, offset) => ({
      at: at + offset, from: STACK.peer.id, from_slot: 'peer', from_head: STACK.oauthHead,
      to: socket, to_slot: toSlot, packet: null, text: null, text_source: null,
      missing_reason: 'The synthetic sender transcript is no longer on this machine.',
    })),
  } }));
  await page.route((url) => url.pathname === '/api/teams/' + TEAM.id + '/activity', (route) => route.fulfill({ json: {
    team_id: TEAM.id, day_start_epoch_millis: days().today, sample_interval_note: '30 s', upstream_label_queries: 0, entries: [],
  } }));
  await page.route('**/api/teams/' + TEAM.id + '/economics', (route) => route.fulfill({ json: {
    team_id: TEAM.id, heads_read: [STACK.oauthHead], unattributed_turns: 0, oldest_turn_epoch_millis: null, roles: [],
    slots: TEAM.slots.map((slot) => ({
      slot: slot.id, turns: slot.lead ? 2 : 1, tokens: { input: 0, cache_read: 0, cache_write: 0, output: 0 },
      cost_usd: slot.lead ? 12.34 : null, unpriced_turns: slot.lead ? 0 : 1,
      last_turn_at_epoch_millis: at, checks: null, checks_source: 'synthetic',
    })),
  } }));
  const faults = await open(page, 'teams/' + TEAM.id);
  await expect(page.getByRole('listitem', { name: 'lead', exact: true })).toContainText('$12.34');
  await expect(page.getByRole('listitem', { name: 'builder', exact: true })).toContainText('1 turn · –');
  const messages = page.locator('ul.talk').first().getByRole('listitem');
  await expect(messages).toHaveCount(2);
  await expect(messages.filter({ hasText: 'builder to lead' })).toHaveCount(1);
  await expect(messages.filter({ hasText: 'builder to Another session' })).toHaveCount(1);
  await expect(messages).toContainText(['The synthetic sender transcript is no longer on this machine.', 'The synthetic sender transcript is no longer on this machine.']);
  await expect(page.getByRole('main')).not.toContainText('uds:');
  await expect(page.getByRole('main')).not.toContainText('.sock');
  expect(faults.pageErrors).toEqual([]);
  await page.unrouteAll({ behavior: 'wait' });
});

test('reentering a team after its empty read shows a newly landed real turn before the panel poll', async ({ page, request }) => {
  const made = await request.put(env('CONSOLE_E2E_BASE') + '/api/teams', {
    headers: { Authorization: 'Bearer ' + env('CONSOLE_E2E_KEY'), 'Idempotency-Key': randomUUID() },
    data: {
      name: 'Synthetic fresh entry', goal: '', features: [], repo: env('CONSOLE_E2E_REPO'), archived: false,
      slots: TEAM.slots.map((slot) => ({ ...slot, model: STACK.model })),
    },
  });
  expect(made.ok()).toBe(true);
  const team = await made.json() as TeamRow;
  const boundary = Date.now();
  let chats = 0;
  let activities = 0;
  for (const panel of ['chat', 'activity']) {
    await page.route((url) => url.pathname === '/api/teams/' + team.id + '/' + panel, async (route) => {
      const initial = panel === 'chat' ? chats++ === 0 : activities++ === 0;
      const url = new URL(route.request().url());
      if (initial) url.searchParams.set('from', String(boundary));
      const response = await route.fetch({ url: url.toString() });
      await route.fulfill({ response });
    });
  }
  const faults = await open(page, 'teams/' + team.id);
  await expect(page.getByText('The seats sent each other nothing.', { exact: true })).toBeVisible({ timeout: FIRST_READ_MS });
  await page.getByRole('main').getByRole('link', { name: 'Teams', exact: true }).click();
  await sendHandOff(Number(env('CONSOLE_E2E_OAUTH_PORT')), env('CONSOLE_E2E_KEY'), env('CONSOLE_E2E_PEER_ADDRESS'), 'toolu_synthetic_entry_' + randomUUID());
  const { today, tomorrow } = days();
  const activity = await read<{ entries: { session: string }[] }>(page, '/api/teams/' + team.id + '/activity?from=' + today + '&to=' + tomorrow);
  expect(activity.entries.some((row) => row.session === STACK.sender.id)).toBe(true);
  const started = Date.now();
  await page.evaluate((id) => { location.hash = '#/teams/' + id; }, team.id);
  await expect(page.getByRole('main')).toContainText('lead to builder', { timeout: 4000 });
  await expect(page.getByRole('main')).toContainText('Messaging a peer session', { timeout: 4000 });
  expect(Date.now() - started).toBeLessThan(10_000);
  expect(chats).toBeGreaterThan(1);
  expect(activities).toBeGreaterThan(1);
  expect(faults.pageErrors).toEqual([]);
  await page.unrouteAll({ behavior: 'wait' });
});
