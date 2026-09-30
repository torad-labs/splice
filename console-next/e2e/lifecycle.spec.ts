// NEW: V4-444 — the replacement console owns the real unsupervised daemon-restart journey.
import { expect, test } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';
import type { HeadsPayload } from '../src/types/core';
import { env, open, read } from './support';

interface Health {
  ok: boolean;
  topologyStale: boolean;
  readyHeads: number;
  bootedAtEpochMillis: number;
}

async function health(): Promise<Health | null> {
  try {
    const response = await fetch(env('CONSOLE_E2E_BASE') + '/health');
    return response.ok ? await response.json() as Health : null;
  } catch {
    // A stopped listener is the expected middle of this real restart, never a successful observation.
    return null;
  }
}

test('an unsupervised restart returns the replacement console and applies the changed configuration', async ({ page }) => {
  test.setTimeout(120_000);
  const config = env('CONSOLE_E2E_CONFIG');
  const original = readFileSync(config, 'utf8');
  await open(page, 'needs-you');
  const before = await read<HeadsPayload>(page, '/api/heads');
  const originalHealth = await health();
  expect(originalHealth?.ok, 'the isolated daemon must be healthy before restarting').toBe(true);
  const boot = originalHealth?.bootedAtEpochMillis;
  expect(boot).toBeGreaterThan(0);
  try {
    const changed = original.replace('discovery_prefix = "claude-e2e--"', 'discovery_prefix = "claude-e2e-restarted--"');
    expect(changed, 'the fixture must change a boot-time field, not a comment or live window').not.toBe(original);
    writeFileSync(config, changed);
    await expect.poll(async () => (await health())?.topologyStale, { timeout: 20_000 }).toBe(true);
    await page.reload();
    const restart = page.getByRole('button', { name: 'Restart splice', exact: true });
    await expect(restart).toBeVisible();
    let posts = 0;
    page.on('request', (request) => {
      if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/daemon/restart') posts += 1;
    });
    await restart.click();
    const confirm = page.getByRole('button', { name: 'Drain and restart', exact: true });
    await expect(confirm).toBeVisible();
    expect(posts, 'arming restart must not send a POST').toBe(0);
    const accepted = page.waitForResponse((response) => response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/daemon/restart');
    await confirm.click();
    expect((await accepted).status()).toBe(202);
    await expect(page.getByRole('status').filter({ hasText: 'splice is draining' })).toBeVisible();
    expect(posts).toBe(1);
    await expect.poll(async () => {
      const state = await health();
      return state !== null && state.ok && state.bootedAtEpochMillis !== boot &&
        state.topologyStale === false && state.readyHeads === before.heads.length;
    }, { timeout: 60_000 }).toBe(true);
    await page.reload();
    await expect(page.getByRole('navigation', { name: 'Pages', exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Needs you', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Restart splice', exact: true })).toHaveCount(0);
    const authorized = await page.request.get(env('CONSOLE_E2E_BASE') + '/api/status', {
      headers: { Authorization: 'Bearer ' + env('CONSOLE_E2E_KEY') },
    });
    expect(authorized.status(), 'the successor must accept the original management key').toBe(200);
    const after = await read<HeadsPayload>(page, '/api/heads');
    expect(after.heads.every((head) => head.running)).toBe(true);
    expect(after.heads.every((head) => head.healthy)).toBe(true);
  } finally {
    const restoredBoot = (await health())?.bootedAtEpochMillis;
    writeFileSync(config, original);
    // Restore the synthetic stack's boot identity for subsequent journeys, not only its file contents.
    const response = await fetch(env('CONSOLE_E2E_BASE') + '/api/daemon/restart', {
      method: 'POST', headers: { Authorization: 'Bearer ' + env('CONSOLE_E2E_KEY') },
    });
    expect(response.status).toBe(202);
    await expect.poll(async () => {
      const state = await health();
      return state !== null && state.ok && state.bootedAtEpochMillis !== restoredBoot &&
        state.topologyStale === false && state.readyHeads === before.heads.length;
    }, { timeout: 60_000 }).toBe(true);
  }
});
