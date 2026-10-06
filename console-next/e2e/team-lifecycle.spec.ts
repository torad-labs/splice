// NEW: V4-444 — real team composition, handoff, economics, unbind and unopened peers.
import { expect, test, type Locator, type Page } from '@playwright/test';
import type { TeamEconomicsPayload, TeamRow, TeamsPayload } from '../src/types/teams';
import type { SessionsPayload } from '../src/types/sessions';
import { repoNameOf } from '../src/lib/repo';
import { env, open, read } from './support';
import { sendHandOff, STACK } from './stack';

async function select(page: Page, scope: Locator, label: string, value: string): Promise<void> {
  await scope.getByRole('button', { name: label, exact: true }).click();
  await page.getByRole('menuitemradio', { name: value, exact: true }).click();
}

test('team composition picks a named repository and discloses its unchanged folder identity on request', async ({ page }) => {
  const root = env('CONSOLE_E2E_REPO');
  await page.route(url => url.pathname === '/api/sessions', async route => {
    const response = await route.fetch();
    const body = await response.json() as SessionsPayload;
    body.sessions = body.sessions.map(row => ({ ...row, repo: { root, remote: 'https://github.com/synthetic-labs/named-project.git' } }));
    await route.fulfill({ response, json: body });
  });
  await open(page, 'sessions?group=team');
  await page.getByRole('button', { name: 'New team', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('textbox', { name: 'Repository', exact: true })).toHaveCount(0);
  await select(page, dialog, 'Repository', 'named-project');
  await expect(dialog.getByRole('button', { name: 'Repository', exact: true })).toContainText('named-project');
  await expect(dialog.getByRole('textbox', { name: 'Folder location', exact: true })).toHaveCount(0);
  await dialog.getByText('Show or change the folder', { exact: true }).click();
  await expect(dialog.getByRole('textbox', { name: 'Folder location', exact: true })).toHaveValue(root);
  await dialog.getByRole('textbox', { name: 'Name', exact: true }).fill('Synthetic named repository team');
  await dialog.getByRole('group', { name: 'Seat 1', exact: true }).getByRole('textbox', { name: 'Role', exact: true }).fill('lead');
  await select(page, dialog, 'Seat 1 Command', STACK.oauthHead);
  const saved = page.waitForResponse(response => new URL(response.url()).pathname === '/api/teams' && response.request().method() === 'PUT');
  await dialog.getByRole('button', { name: 'Save the team', exact: true }).click();
  const response = await saved;
  expect(response.ok()).toBe(true);
  expect(response.request().postDataJSON().repo).toBe(root);
});

test('a team created through the console binds real sessions, joins handoff and economics and explicitly opens a seat', async ({ page }) => {
  const faults = await open(page, 'sessions?group=team');
  const sessions = await read<SessionsPayload>(page, '/api/sessions');
  const sender = sessions.sessions.find((row) => row.session_id === STACK.sender.id);
  const peer = sessions.sessions.find((row) => row.session_id === STACK.peer.id);
  expect(sender?.name).not.toBeNull();
  expect(peer?.name).not.toBeNull();
  // Earlier journeys may bind these same synthetic seats. The daemon deliberately selects the first
  // unarchived binding, so release only this fixture's prior seats before testing the new team.
  const existing = await read<TeamsPayload>(page, '/api/teams');
  for (const prior of existing.teams.filter(team => !team.archived)) {
    const bindings = Object.fromEntries(prior.slots
      .filter(slot => slot.session === STACK.sender.id || slot.session === STACK.peer.id)
      .map(slot => [slot.id, null]));
    if (Object.keys(bindings).length === 0) continue;
    const released = await page.request.put(env('CONSOLE_E2E_BASE') + '/api/teams/' + encodeURIComponent(prior.id) + '/sessions', {
      headers: { Authorization: 'Bearer ' + env('CONSOLE_E2E_KEY') }, data: { bindings },
    });
    expect(released.ok(), 'synthetic fixture bindings must be released').toBe(true);
  }
  await page.getByRole('button', { name: 'New team', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('textbox', { name: 'Name', exact: true }).fill('Synthetic handoff team');
  await dialog.getByRole('textbox', { name: 'Goal', exact: true }).fill('Deliver the synthetic review');
  await select(page, dialog, 'Repository', repoNameOf(env('CONSOLE_E2E_REPO'), sender?.repo?.remote));
  const seat = (n: number) => dialog.getByRole('group', { name: 'Seat ' + n, exact: true });
  await seat(1).getByRole('textbox', { name: 'Role', exact: true }).fill('lead');
  await select(page, dialog, 'Seat 1 Command', STACK.oauthHead);
  await select(page, dialog, 'Seat 1 Session', sender?.name ?? '');
  await dialog.getByRole('button', { name: 'Add a seat', exact: true }).click();
  await seat(2).getByRole('textbox', { name: 'Role', exact: true }).fill('builder');
  await select(page, dialog, 'Seat 2 Command', STACK.oauthHead);
  await select(page, dialog, 'Seat 2 Session', peer?.name ?? '');
  for (const group of [seat(1), seat(2)]) {
    await expect.soft(group).toHaveCSS('border-top-width', '0px');
    await expect(group.getByRole('textbox', { name: 'Role', exact: true })).not.toHaveCSS('border-top-width', '0px');
  }
  const created = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/teams' && response.request().method() === 'PUT');
  await dialog.getByRole('button', { name: 'Save the team', exact: true }).click();
  const response = await created;
  expect(response.ok()).toBe(true);
  const team = await response.json() as TeamRow;
  expect(team.slots.map((slot) => slot.session)).toEqual([STACK.sender.id, STACK.peer.id]);
  expect(response.request().headers()['idempotency-key']).toBeTruthy();
  // The response precedes the awaited refetch and the dialog's saved-team navigation.
  await expect.poll(() => new URL(page.url()).hash).toBe('#/teams/' + team.id);
  await open(page, 'sessions?group=team');
  const savedGroup = page.getByRole('region', { name: 'Synthetic handoff team', exact: true });
  await expect(savedGroup.getByRole('listitem')).toHaveCount(2);
  await expect(savedGroup).toContainText(sender?.name ?? '');
  await expect(savedGroup).toContainText(peer?.name ?? '');
  const savedLink = savedGroup.getByRole('link', { name: 'Open the team', exact: true });
  await expect(savedLink).toHaveAttribute('href', '#/teams/' + team.id);
  await savedLink.click();
  await expect(page.getByRole('heading', { name: 'Synthetic handoff team', exact: true })).toBeVisible();
  const lead = page.getByRole('listitem', { name: 'lead', exact: true });
  const builder = page.getByRole('listitem', { name: 'builder', exact: true });
  await expect(lead).toContainText(sender?.name ?? '');
  await expect(builder).toContainText(peer?.name ?? '');
  for (const row of [lead, builder]) await expect.soft(row).toHaveCSS('border-top-width', '0px');
  await sendHandOff(Number(env('CONSOLE_E2E_OAUTH_PORT')), env('CONSOLE_E2E_KEY'), env('CONSOLE_E2E_PEER_ADDRESS'), 'toolu_synthetic_team_' + Date.now());
  await expect(page.getByRole('main')).toContainText('lead to builder', { timeout: 20_000 });
  const economic = await read<TeamEconomicsPayload>(page, '/api/teams/' + team.id + '/economics');
  const tally = economic.slots.find((slot) => slot.slot === team.slots[0]?.id);
  expect(tally?.turns).toBeGreaterThanOrEqual(1);
  await expect(lead).toContainText(new RegExp(String(tally?.turns) + ' turns?'), { timeout: 20_000 });
  expect(tally?.cost_usd).toBeNull();
  await expect(lead.locator('.stat')).toContainText(' · –');
  await expect(builder.locator('.stat')).toHaveText('No turns are listed for this seat');
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
  await expect(lead).toContainText('No session is listed for this seat');
  await expect(lead).toContainText('Drive only the synthetic packet');
  await expect(builder).toContainText(peer?.name ?? '');
  expect(faults.pageErrors).toEqual([]);
  expect(faults.failedReads).toEqual([]);
});

test('Team grouping files unbound sessions by the shared project identity, retains ended rows and opens the project', async ({ page }) => {
  const root = env('CONSOLE_E2E_REPO');
  await page.route(url => url.pathname === '/api/sessions', async route => {
    const response = await route.fetch();
    const body = await response.json() as SessionsPayload;
    const source = body.sessions.find(row => row.session_id === STACK.sender.id);
    if (source === undefined) throw new Error('isolated fixture must contain the synthetic sender');
    body.sessions = [
      ...(['live', 'stale', 'gone'] as const).map((availability, index) => ({
        ...source, session_id: `synthetic-project-${index}`, name: `Synthetic project seat ${index}`, availability,
        team: null, cwd: `${root}/seat-${index}`, repo: { root, ...(index === 1 ? { remote: 'https://github.com/synthetic-labs/project-cohort.git' } : {}) },
      })),
      { ...source, session_id: 'synthetic-unfiled', name: 'Synthetic unfiled seat', team: null, cwd: null, repo: { root: '' } },
    ];
    await route.fulfill({ response, json: body });
  });
  const faults = await open(page, 'sessions?group=team');
  const group = page.getByRole('region', { name: 'project-cohort', exact: true });
  await expect(group.getByRole('listitem')).toHaveCount(3);
  for (let index = 0; index < 3; index++) await expect(group).toContainText(`Synthetic project seat ${index}`);
  await expect(page.getByRole('region', { name: 'Not filed', exact: true }).getByRole('listitem')).toHaveCount(1);
  await expect(page.getByRole('main')).not.toContainText(`project:${root}`);
  for (const width of [1536, 393]) {
    await page.setViewportSize({ width, height: 980 });
    await page.screenshot({ path: test.info().outputPath(`session-project-group-${width}.png`), fullPage: true });
  }
  const projectLink = group.getByRole('link', { name: 'Open the project', exact: true });
  await expect(projectLink).toHaveAttribute('href', '#/projects/' + encodeURIComponent(root));
  await projectLink.click();
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  await open(page, 'teams');
  await expect(page.getByRole('heading', { name: 'project-cohort', exact: true })).toBeVisible();
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
