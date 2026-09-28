// V4-402: the team chat never prints a socket path. A hand-off to a socket no member is known to have
// held reads as another session; one filed under a member reads as that member's role.
import { expect, test } from '@playwright/test';
import type { TeamRow } from '../../src/entities/team';
import { STACK } from '../stack';

const SOCKET = 'uds:/run/cc-socks/700.sock';
const TEAM: TeamRow = {
  id: 'socket-team', name: 'Socket team', goal: 'Read the hand-offs', features: ['history'],
  repo: '/work/socket', archived: false, created_epoch_millis: 1, updated_epoch_millis: 1,
  idempotency_key: null, create_fingerprint: null,
  slots: [
    { id: 'lead', role: 'lead', head: STACK.oauthHead, model: null, account: null, lead: true,
      instructions: null, session: STACK.sender.id, sessions_history: [STACK.sender.id], instructions_updated_epoch_millis: null },
    { id: 'peer', role: 'builder', head: STACK.oauthHead, model: null, account: null, lead: false,
      instructions: null, session: STACK.peer.id, sessions_history: [STACK.peer.id], instructions_updated_epoch_millis: null },
  ],
};

test('a hand-off to an unfiled socket reads as another session, and a filed one as its role', async ({ page }) => {
  const base = process.env.CONSOLE_E2E_BASE;
  const key = process.env.CONSOLE_E2E_KEY;
  if (!base || !key) throw new Error('the isolated console stack did not start');
  await page.addInitScript(([storage, value]) => localStorage.setItem(storage, value), ['myx-mgmt-key', key]);
  const at = Date.now() - 60_000;
  const line = (to: string, toSlot: string | null, offset: number) => ({
    at: at + offset, from: STACK.peer.id, from_slot: 'peer', from_head: STACK.oauthHead, to, to_slot: toSlot,
    packet: null, text: null, text_source: null, missing_reason: 'The sender transcript is no longer on this machine.',
  });
  await page.route((url) => url.pathname === '/api/teams', (route) => route.fulfill({ json: { teams: [TEAM] } }));
  await page.route((url) => url.pathname === '/api/teams/socket-team/chat', (route) => route.fulfill({ json: {
    team_id: TEAM.id, day_start_epoch_millis: at, packet_note: 'No packet',
    messages: [line(SOCKET, null, 0), line(SOCKET, 'lead', 1)],
  } }));
  await page.route((url) => url.pathname === '/api/teams/socket-team/activity', (route) => route.fulfill({ json: {
    team_id: TEAM.id, day_start_epoch_millis: at, sample_interval_note: '30 s', upstream_label_queries: 0, entries: [],
  } }));
  await page.route((url) => url.pathname === '/api/teams/socket-team/economics', (route) => route.fulfill({ json: {
    team_id: TEAM.id, heads_read: [STACK.oauthHead], unattributed_turns: 0,
    oldest_turn_epoch_millis: null, roles: [], slots: [],
  } }));

  await page.goto(`${base}/#/teams`);
  const rows = page.locator('.myx-chat-row');
  await expect(rows).toHaveCount(2, { timeout: 15_000 });
  await expect(rows.nth(0)).toContainText('Another session');
  await expect(rows.nth(1)).toContainText('lead');
  const shown = await page.locator('body').innerText();
  expect(shown).not.toContain('uds:');
  expect(shown).not.toContain('.sock');
});
