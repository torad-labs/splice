// CI run 36646964572: entering Timeline within its minute must read a turn that landed after
// the initial day read. At midnight the stack's boot turn is outside that read altogether.
import { expect, test } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { STACK, sendHandOff } from '../stack';

function env(name: string): string {
  const value = process.env[name];
  if (value === undefined || value === '') throw new Error(`${name} is unset`);
  return value;
}

test('entering the team timeline reads a newly landed turn before its next minute poll', async ({ page, request }, info) => {
  const base = env('CONSOLE_E2E_BASE');
  const key = env('CONSOLE_E2E_KEY');
  const made = await request.put(`${base}/api/teams`, {
    headers: { Authorization: `Bearer ${key}`, 'Idempotency-Key': randomUUID() },
    data: {
      name: 'e2e timeline boundary', goal: '', features: [], repo: env('CONSOLE_E2E_REPO'), archived: false,
      slots: [{
        id: 'e2e-timeline-lead', role: 'lead', head: STACK.oauthHead,
        model: STACK.model, account: null, lead: true, instructions: null, session: STACK.sender.id,
      }],
    },
  });
  expect(made.ok()).toBe(true);
  const team = await made.json() as { id: string };
  const reads: { heads: { rows: { session: string | null }[] }[] }[] = [];
  // A real route with a cutoff after boot gives the same empty initial day as crossing midnight.
  // Only this first cutoff is substituted; subsequent reads use the page's own day and live rows.
  const boundary = Date.now();
  await page.route('**/api/perf/turns?**', async (route) => {
    const url = new URL(route.request().url());
    if (url.searchParams.get('head') !== STACK.oauthHead) return route.continue();
    if (reads.length === 0) url.searchParams.set('since', String(boundary));
    const response = await route.fetch({ url: url.toString() });
    reads.push(await response.json());
    await route.fulfill({ response });
  });
  await page.addInitScript((token) => localStorage.setItem('myx-mgmt-key', token), key);
  try {
    await page.goto(`${base}/#/teams?open=${encodeURIComponent(team.id)}`);
    await expect.poll(() => reads.length).toBe(1);
    expect(reads[0].heads.flatMap((head) => head.rows)).toEqual([]);
    await expect(page.locator('.myx-tm-team')).toContainText('e2e timeline boundary');
    await sendHandOff(Number(env('CONSOLE_E2E_OAUTH_PORT')), key, env('CONSOLE_E2E_PEER_ADDRESS'), `toolu_timeline_${randomUUID()}`);

    // The server already has the correctly attributed turn while the browser holds the older day.
    const live = await request.get(`${base}/api/perf/turns?head=${STACK.oauthHead}&since=${boundary}`, {
      headers: { Authorization: `Bearer ${key}` },
    });
    expect(live.ok()).toBe(true);
    const payload = await live.json() as typeof reads[number];
    expect(payload.heads.flatMap((head) => head.rows).some((row) => row.session === STACK.sender.id.slice(0, 8))).toBe(true);
    await info.attach('landed-turns', { body: JSON.stringify(payload), contentType: 'application/json' });

    expect(reads.length, 'the new turn landed before the background minute poll').toBe(1);
    await page.getByRole('tab', { name: 'Timeline' }).click();
    await expect(page.getByRole('img', { name: /^e2e-sender: [1-9]\d* turns?/ })).toBeVisible({ timeout: 15_000 });
    expect(reads.length, 'entering Timeline must re-read without waiting for a minute').toBeGreaterThan(1);
  } finally {
    await info.attach('browser-perf-reads', { body: JSON.stringify(reads), contentType: 'application/json' });
  }
});
