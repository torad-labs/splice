// V4-391: a team day is selectable, and today's empty chat never erases yesterday's hand-off.
import { expect, test } from '@playwright/test';
import type { TeamRow } from '../../src/entities/team';
import { STACK } from '../stack';

const TEAM: TeamRow = {
  id: 'history-team', name: 'History team', goal: 'Read previous hand-offs', features: ['history'],
  repo: '/work/history', archived: false, created_epoch_millis: 1, updated_epoch_millis: 1,
  idempotency_key: null, create_fingerprint: null,
  slots: [
    { id: 'lead', role: 'lead', head: STACK.oauthHead, model: null, account: null, lead: true,
      instructions: null, session: STACK.sender.id, sessions_history: [STACK.sender.id], instructions_updated_epoch_millis: null },
    { id: 'peer', role: 'builder', head: STACK.oauthHead, model: null, account: null, lead: false,
      instructions: null, session: STACK.peer.id, sessions_history: [STACK.peer.id], instructions_updated_epoch_millis: null },
  ],
};

test('a team can read yesterday without treating today’s empty chat as lifetime empty', async ({ page }) => {
  const base = process.env.CONSOLE_E2E_BASE;
  const key = process.env.CONSOLE_E2E_KEY;
  if (!base || !key) throw new Error('the isolated console stack did not start');
  await page.addInitScript(([storage, value]) => localStorage.setItem(storage, value), ['myx-mgmt-key', key]);
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1).getTime();
  const tomorrow = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1).getTime();
  const asked: Array<{ from: number; to: number }> = [];
  const activityAsked: number[] = [];

  await page.route((url) => url.pathname === '/api/teams', (route) => route.fulfill({ json: { teams: [TEAM] } }));
  await page.route((url) => url.pathname === '/api/teams/history-team/chat', (route) => {
    const url = new URL(route.request().url());
    const from = Number(url.searchParams.get('from'));
    const to = Number(url.searchParams.get('to'));
    asked.push({ from, to });
    return route.fulfill({ json: {
      team_id: TEAM.id, day_start_epoch_millis: from, packet_note: 'No packet',
      messages: from === yesterday && to === today ? [{
        at: yesterday + 12 * 60 * 60_000, from: STACK.sender.id, from_slot: 'lead', from_head: STACK.oauthHead,
        to: STACK.peer.id, to_slot: 'peer', packet: null, text: 'Yesterday hand-off reached the peer.',
        text_source: '/synthetic/transcript.jsonl', missing_reason: null,
      }] : [],
    } });
  });
  await page.route((url) => url.pathname === '/api/teams/history-team/activity', (route) => {
    const from = Number(new URL(route.request().url()).searchParams.get('from'));
    activityAsked.push(from);
    return route.fulfill({ json: { team_id: TEAM.id, day_start_epoch_millis: from,
      sample_interval_note: '30 s', upstream_label_queries: 0,
      entries: from === yesterday ? [{ at: yesterday + 12 * 60 * 60_000, session: STACK.sender.id,
        slot: 'lead', head: STACK.oauthHead, label: 'Yesterday activity sample', detail: null }] : [] } });
  });
  await page.route((url) => url.pathname === '/api/teams/history-team/economics', (route) => route.fulfill({ json: {
    team_id: TEAM.id, heads_read: [STACK.oauthHead], unattributed_turns: 0,
    oldest_turn_epoch_millis: null, roles: [], slots: [],
  } }));

  await page.goto(`${base}/#/teams`);
  await expect(page.getByText('No messages today')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByRole('button', { name: 'Previous day' })).toBeVisible();
  await page.getByRole('button', { name: 'Previous day' }).click();
  await expect.poll(() => asked.some((range) => range.from === yesterday && range.to === today),
    { message: 'the previous local day was not read from /chat' }).toBe(true);
  const message = page.locator('.myx-chat-row').filter({ hasText: 'lead' });
  await expect(message).toContainText('builder');
  await expect.poll(() => activityAsked.includes(yesterday)).toBe(true);
  await expect(page.getByText('Yesterday activity sample')).toBeVisible();
  await message.getByRole('button', { name: 'Show message' }).click();
  await expect(message).toContainText('Yesterday hand-off reached the peer.');
  await expect(page.getByText('No messages today')).toHaveCount(0);
  await page.getByRole('button', { name: 'Previous day' }).click();
  await expect(page.getByText('No messages', { exact: true })).toBeVisible();
  await expect(page.getByText('Yesterday activity sample')).toHaveCount(0);
  await expect(page.getByText('Nothing sampled', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Next day' }).click();
  await expect(message).toContainText('builder');
  await expect(page.getByText('Yesterday activity sample')).toBeVisible();
  await message.getByRole('button', { name: 'Show message' }).click();
  await expect(message).toContainText('Yesterday hand-off reached the peer.');
  await page.getByRole('button', { name: 'Today' }).click();
  await expect.poll(() => asked.some((range) => range.from === today && range.to === tomorrow)).toBe(true);
  await expect(page.getByText('No messages today')).toBeVisible();
  await expect(message).toHaveCount(0);
});

test('an expired chat day names retention while Activity follows that same day', async ({ page }) => {
  const base = process.env.CONSOLE_E2E_BASE;
  const key = process.env.CONSOLE_E2E_KEY;
  if (!base || !key) throw new Error('the isolated console stack did not start');
  await page.addInitScript(([storage, value]) => localStorage.setItem(storage, value), ['myx-mgmt-key', key]);
  const now = new Date();
  const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1).getTime();
  const older = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 2).getTime();
  await page.route((url) => url.pathname === '/api/teams', (route) => route.fulfill({ json: { teams: [TEAM] } }));
  await page.route((url) => url.pathname === '/api/teams/history-team/chat', (route) => {
    const from = Number(new URL(route.request().url()).searchParams.get('from'));
    return route.fulfill({ json: { team_id: TEAM.id, day_start_epoch_millis: from,
      packet_note: 'No packet', messages: [],
      ...(from !== older ? {} : { state: 'not_kept', oldest_kept_epoch_millis: yesterday,
        reason: 'Message history past activityRetentionDays is not kept.' }) } });
  });
  await page.route((url) => url.pathname === '/api/teams/history-team/activity', (route) => {
    const from = Number(new URL(route.request().url()).searchParams.get('from'));
    return route.fulfill({ json: { team_id: TEAM.id, day_start_epoch_millis: from,
      sample_interval_note: '30 s', upstream_label_queries: 0, entries: [],
      ...(from !== older ? {} : { state: 'not_kept', reason: 'Activity labels for this day are no longer kept.' }) } });
  });
  await page.route((url) => url.pathname === '/api/teams/history-team/economics', (route) => route.fulfill({ json: {
    team_id: TEAM.id, heads_read: [STACK.oauthHead], unattributed_turns: 0,
    oldest_turn_epoch_millis: null, roles: [], slots: [],
  } }));
  await page.goto(`${base}/#/teams`);
  await expect(page.getByText('No messages today')).toBeVisible({ timeout: 15_000 });
  await page.getByRole('button', { name: 'Previous day' }).click();
  await page.getByRole('button', { name: 'Previous day' }).click();
  await expect(page.getByText('History not kept')).toBeVisible();
  await page.getByRole('status').filter({ hasText: 'History not kept' }).getByRole('button', { name: 'Why' }).hover();
  await expect(page.getByRole('tooltip').filter({ hasText: 'activityRetentionDays' })).toBeVisible();
  await expect(page.getByText('Activity not kept')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Previous day' })).toBeDisabled();
});
