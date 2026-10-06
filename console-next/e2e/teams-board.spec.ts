import { expect, test } from '@playwright/test';
import { appendFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import type { SessionsPayload } from '../src/types/sessions';
import { env, open, read } from './support';
import { sendHandOff, STACK } from './stack';

test('Teams groups real registry seats without saved membership and reads a message only when opened', async ({ page }) => {
  const requestedMessages: string[] = [];
  page.on('request', request => {
    if (/\/api\/sessions\/[^/]+\/edges$/.test(new URL(request.url()).pathname)) requestedMessages.push(request.url());
  });
  const faults = await open(page, 'teams');
  const payload = await read<SessionsPayload>(page, '/api/sessions');
  const sender = payload.sessions.find(row => row.session_id === STACK.sender.id);
  const peer = payload.sessions.find(row => row.session_id === STACK.peer.id);
  expect(sender?.repo?.root).toBeTruthy();
  expect(sender?.repo?.root).toBe(peer?.repo?.root);
  const project = page.locator('.project-team').filter({ has: page.locator('a[href="#/sessions/' + STACK.sender.id + '"]') });
  await expect(project).toHaveCount(1);
  await expect(project.locator('a[href="#/sessions/' + STACK.peer.id + '"]')).toBeVisible();
  await expect(project).not.toContainText(env('CONSOLE_E2E_PEER_ADDRESS'));
  expect(requestedMessages).toEqual([]);
  await expect(project.getByRole('region', { name: 'Messages and handoffs', exact: true })).toContainText('Recorded messages between these sessions, newest first.');
  await expect(project).not.toContainText('Recent messages');
  const newTeam = page.getByRole('button', { name: 'New team', exact: true });
  const projectSettings = project.getByRole('link', { name: 'Project settings', exact: true });
  for (const width of [1536, 393]) {
    await page.setViewportSize({ width, height: 980 });
    for (const action of [newTeam, projectSettings]) {
      const appearance = await action.evaluate(node => {
        const style = getComputedStyle(node);
        return {
          edge: parseFloat(style.borderTopWidth) > 0 && style.borderTopColor !== 'rgba(0, 0, 0, 0)',
          ground: style.backgroundColor !== 'rgba(0, 0, 0, 0)',
          decoration: style.textDecorationLine,
        };
      });
      expect(appearance).toEqual({ edge: true, ground: true, decoration: 'none' });
      const bounds = await action.boundingBox();
      expect(bounds).not.toBeNull();
      expect((bounds?.x ?? Infinity) + (bounds?.width ?? Infinity)).toBeLessThanOrEqual(width);
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.locator('.teams-page').screenshot({ path: test.info().outputPath('team-actions-' + width + '.png') });
  }
  await newTeam.click();
  await expect(page.getByRole('dialog', { name: 'New team', exact: true })).toBeVisible();
  await page.getByRole('dialog').getByRole('button', { name: 'Cancel', exact: true }).click();
  const call = 'toolu_synthetic_project_team_' + Date.now();
  const dir = join(env('CONSOLE_E2E_TRANSCRIPT_ROOT'), 'projects', 'console-e2e');
  mkdirSync(dir, { recursive: true, mode: 0o700 });
  // The HTTP fixture is not Claude Code, so persist its own synthetic tool call as the client would.
  appendFileSync(join(dir, STACK.sender.id + '.jsonl'), JSON.stringify({
    type: 'assistant', message: { role: 'assistant', content: [{
      type: 'tool_use', id: call, name: 'SendMessage',
      input: { to: env('CONSOLE_E2E_PEER_ADDRESS'), message: 'review ready' },
    }] },
  }) + '\n', { mode: 0o600 });
  const sentAt = Date.now();
  await sendHandOff(Number(env('CONSOLE_E2E_OAUTH_PORT')), env('CONSOLE_E2E_KEY'), env('CONSOLE_E2E_PEER_ADDRESS'), call);
  // Other journeys retain older handoffs with these same peers. Wait for this send's board entry,
  // not merely an already-visible matching summary, before opening the newest observed message.
  await expect.poll(() => project.locator('.project-handoffs time').evaluateAll((times, after) =>
    times.some(time => Date.parse(time.getAttribute('datetime') ?? '') >= after), sentAt,
  ), { timeout: 20_000 }).toBe(true);
  const handoff = project.locator('.project-handoffs summary').filter({ hasText: (sender?.name ?? '') + ' to ' + (peer?.name ?? '') }).first();
  await expect(handoff).toBeVisible({ timeout: 20_000 });
  expect(requestedMessages).toEqual([]);
  await handoff.click();
  await expect(project.locator('.team-message')).toContainText('review ready');
  await expect(project.locator('.team-message .prose')).toContainText('review ready');
  expect(requestedMessages.some(url => new URL(url).pathname === '/api/sessions/' + STACK.sender.id + '/edges')).toBe(true);
  expect(faults.pageErrors).toEqual([]);
  expect(faults.failedReads).toEqual([]);
});

test('an idle card shows the human failure before the retained splice diagnostic', async ({ page }) => {
  const sentence = "splice could not complete this session's code-mode step; start a new session, and if it repeats read the daemon log";
  await page.route(url => url.pathname === '/api/sessions', async route => {
    const response = await route.fetch();
    const body = await response.json() as SessionsPayload;
    const source = body.sessions.find(row => row.session_id === STACK.sender.id);
    if (source === undefined) throw new Error('isolated fixture must contain the synthetic sender');
    body.sessions = [{ ...source, status: 'idle', name: 'Synthetic local failure', team: null,
      last: { role: 'assistant', tool: null, ts: Date.now(),
        text: sentence + '.\n\n⚠ splice [SPLICE-INVALID-REQUEST] IllegalStateException at SyntheticCell.kt:83; accepted results=0; source was not rerun' },
    }];
    await route.fulfill({ response, json: body });
  });
  const faults = await open(page, 'sessions');
  const card = page.getByRole('listitem').filter({ hasText: 'Synthetic local failure' });
  await expect(card).toContainText(sentence);
  await expect(card).not.toContainText('SPLICE-INVALID-REQUEST');
  await expect(card).not.toContainText('IllegalStateException');
  await expect(card).not.toContainText('provider rejected');
  expect(faults.pageErrors).toEqual([]);
  expect(faults.failedReads).toEqual([]);
});

test('idle sessions use one live-process rule on Sessions and Teams while only an exited process is ended', async ({ page }) => {
  await page.route(url => url.pathname === '/api/sessions', async route => {
    const response = await route.fetch();
    const body = await response.json() as SessionsPayload;
    const source = body.sessions.find(row => row.session_id === STACK.sender.id);
    if (source === undefined) throw new Error('isolated fixture must contain the synthetic sender');
    body.sessions = (['live', 'stale', 'gone'] as const).map(availability => ({
      ...source, session_id: `synthetic-${availability}-idle`, name: `Synthetic ${availability} idle`,
      status: 'idle', availability, team: null, last: null, status_updated_at: Date.now() - 3_600_000,
    }));
    await route.fulfill({ response, json: body });
  });
  const faults = await open(page, 'sessions');
  await expect(page.getByRole('main')).toContainText('Two are idle. One session ended earlier.');
  const idle = page.getByRole('region', { name: 'Idle', exact: true });
  await expect(idle.getByRole('listitem')).toHaveCount(2);
  await expect(idle).toContainText('Waiting for your next message');
  await expect(idle).not.toContainText('Finished');
  await expect(page.getByRole('region', { name: 'Ended', exact: true }).getByRole('listitem')).toHaveCount(1);
  await open(page, 'teams');
  const project = page.locator('.project-team').filter({ has: page.locator('a[href="#/sessions/synthetic-live-idle"]') });
  await expect(project.locator('.project-seat')).toHaveCount(2);
  for (const availability of ['live', 'stale']) {
    const member = project.locator('.project-seat').filter({ has: page.locator(`a[href="#/sessions/synthetic-${availability}-idle"]`) });
    await expect(member.getByText('Idle', { exact: true })).toBeVisible();
    await expect(member).toContainText('waiting for your next message');
    await expect(member).not.toContainText('last status update is old');
  }
  await expect(project).not.toContainText('status old');
  await expect(project.locator('a[href="#/sessions/synthetic-gone-idle"]')).toHaveCount(0);
  expect(faults.pageErrors).toEqual([]);
  expect(faults.failedReads).toEqual([]);
});
