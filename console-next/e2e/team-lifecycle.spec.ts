// NEW: V4-444 — real team composition, handoff, economics, unbind and unopened peers.
import { expect, test, type Locator, type Page } from '@playwright/test';
import type { TeamEconomicsPayload, TeamRow } from '../src/types/teams';
import type { SessionsPayload } from '../src/types/sessions';
import { env, open, read } from './support';
import { sendHandOff, STACK } from './stack';

async function select(page: Page, scope: Locator, label: string, value: string): Promise<void> {
  await scope.getByRole('button', { name: label, exact: true }).click();
  await page.getByRole('menuitemradio', { name: value, exact: true }).click();
}

test('a team created through the console binds real sessions, joins handoff and economics and explicitly opens a seat', async ({ page }) => {
  const faults = await open(page, 'sessions?group=team');
  const sessions = await read<SessionsPayload>(page, '/api/sessions');
  const sender = sessions.sessions.find((row) => row.session_id === STACK.sender.id);
  const peer = sessions.sessions.find((row) => row.session_id === STACK.peer.id);
  expect(sender?.name).not.toBeNull();
  expect(peer?.name).not.toBeNull();
  await page.getByRole('button', { name: 'New team', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('textbox', { name: 'Name', exact: true }).fill('Synthetic handoff team');
  await dialog.getByRole('textbox', { name: 'Goal', exact: true }).fill('Deliver the synthetic review');
  await dialog.getByRole('combobox', { name: /^Repository/ }).fill(env('CONSOLE_E2E_REPO'));
  const seat = (n: number) => dialog.getByRole('group', { name: 'Seat ' + n, exact: true });
  await seat(1).getByRole('textbox', { name: 'Role', exact: true }).fill('lead');
  await select(page, dialog, 'Seat 1 Plan', STACK.oauthHead);
  await select(page, dialog, 'Seat 1 Session', sender?.name ?? '');
  await dialog.getByRole('button', { name: 'Add a seat', exact: true }).click();
  await seat(2).getByRole('textbox', { name: 'Role', exact: true }).fill('builder');
  await select(page, dialog, 'Seat 2 Plan', STACK.oauthHead);
  await select(page, dialog, 'Seat 2 Session', peer?.name ?? '');
  const created = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/teams' && response.request().method() === 'PUT');
  await dialog.getByRole('button', { name: 'Save the team', exact: true }).click();
  const response = await created;
  expect(response.ok()).toBe(true);
  const team = await response.json() as TeamRow;
  expect(team.slots.map((slot) => slot.session)).toEqual([STACK.sender.id, STACK.peer.id]);
  expect(response.request().headers()['idempotency-key']).toBeTruthy();
  await expect(page.getByRole('heading', { name: 'Synthetic handoff team', exact: true })).toBeVisible();
  const lead = page.getByRole('listitem', { name: 'lead', exact: true });
  const builder = page.getByRole('listitem', { name: 'builder', exact: true });
  await expect(lead).toContainText(sender?.name ?? '');
  await expect(builder).toContainText(peer?.name ?? '');
  await sendHandOff(Number(env('CONSOLE_E2E_OAUTH_PORT')), env('CONSOLE_E2E_KEY'), env('CONSOLE_E2E_PEER_ADDRESS'), 'toolu_synthetic_team_' + Date.now());
  await expect(page.getByRole('main')).toContainText('lead to builder', { timeout: 20_000 });
  const economic = await read<TeamEconomicsPayload>(page, '/api/teams/' + team.id + '/economics');
  const tally = economic.slots.find((slot) => slot.slot === team.slots[0]?.id);
  expect(tally?.turns).toBeGreaterThanOrEqual(1);
  await expect(lead).toContainText(new RegExp(String(tally?.turns) + ' turns?'), { timeout: 20_000 });
  expect(tally?.cost_usd).toBeNull();
  await expect(lead.locator('.stat')).toContainText(' · –');
  await expect(builder.locator('.stat')).toHaveText('No turns yet');
  await expect(page.getByRole('main')).toContainText('Messaging a peer session', { timeout: 20_000 });
  await page.getByRole('button', { name: 'Edit the team', exact: true }).click();
  await seat(1).getByRole('textbox', { name: /^Standing instructions/ }).fill('Drive only the synthetic packet');
  await select(page, dialog, 'Seat 1 Session', 'Open seat');
  const unbound = page.waitForResponse((answer) => new URL(answer.url()).pathname === '/api/teams/' + team.id + '/sessions' && answer.request().method() === 'PUT');
  await dialog.getByRole('button', { name: 'Save the team', exact: true }).click();
  const unbind = await unbound;
  expect(unbind.ok()).toBe(true);
  expect(unbind.request().postDataJSON()).toEqual({ bindings: { [team.slots[0]?.id ?? '']: null } });
  const saved = await unbind.json() as TeamRow;
  expect(saved.slots[0]?.session).toBeNull();
  expect(saved.slots[0]?.instructions).toBe('Drive only the synthetic packet');
  expect(saved.slots[0]?.sessions_history).toContain(STACK.sender.id);
  await expect(lead).toContainText('Nobody is in this seat');
  await expect(lead).toContainText('Drive only the synthetic packet');
  await expect(builder).toContainText(peer?.name ?? '');
  expect(faults.pageErrors).toEqual([]);
  expect(faults.failedReads).toEqual([]);
});

test('unopened session cards resolve both handoff peers and never print a raw recipient socket', async ({ page }) => {
  const faults = await open(page, 'sessions');
  const payload = await read<SessionsPayload>(page, '/api/sessions');
  const name = (id: string) => payload.sessions.find((row) => row.session_id === id)?.name ?? '';
  expect(name(STACK.sender.id)).not.toBe('');
  expect(name(STACK.peer.id)).not.toBe('');
  const card = (id: string) => page.getByRole('listitem').filter({ has: page.locator('a[href="#/sessions/' + id + '"]') });
  await expect(card(STACK.sender.id)).toContainText(name(STACK.peer.id));
  await expect(card(STACK.peer.id)).toContainText(name(STACK.sender.id));
  await expect(page.getByRole('main')).not.toContainText(env('CONSOLE_E2E_PEER_ADDRESS'));
  await card(STACK.peer.id).getByRole('link', { name: name(STACK.peer.id), exact: true }).click();
  await expect(page.getByRole('main')).toContainText(name(STACK.sender.id));
  await expect(page.getByRole('main')).not.toContainText(env('CONSOLE_E2E_PEER_ADDRESS'));
  expect(faults.pageErrors).toEqual([]);
});
