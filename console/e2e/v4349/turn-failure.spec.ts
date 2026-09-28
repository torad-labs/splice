import { expect, test } from '@playwright/test';
import type { PerfTurnsWire, TurnRowWire } from '../../src/entities/perf';
import { STACK } from '../stack';

function env(name: string): string {
  const value = process.env[name];
  if (value === undefined || value === '') throw new Error(`${name} is unset`);
  return value;
}

test('a failed turn reads one trace and keeps its sentence under its own status', async ({ page }) => {
  const now = Date.now();
  const synthetic: TurnRowWire = {
    ts: now, model: STACK.soloModel, outcome: 'error:conn-reset', compact: false,
    session: null, account: null, cache_cold: null, turn: 'v4349-a',
    session_id: null, response_message_id: null,
    recv: 1, first_byte: 10, finish: 20, total: 20,
  };
  await page.addInitScript((key) => localStorage.setItem('myx-mgmt-key', key), env('CONSOLE_E2E_KEY'));
  await page.route('**/api/perf/turns?*', async (route) => {
    const query = new URL(route.request().url()).searchParams;
    if (query.get('head') !== STACK.soloHead) {
      await route.continue();
      return;
    }
    const body: PerfTurnsWire = {
      since: Number(query.get('since') ?? now - 86_400_000),
      n: Number(query.get('n') ?? 200),
      heads: [{
        key: STACK.soloHead, label: STACK.soloHead, count: 2, returned: 2,
        truncated: false, oldest_held_ts: now - 1,
        rows: [synthetic, { ...synthetic, ts: now - 1, turn: 'v4349-b' }],
      }],
    };
    await route.fulfill({ json: body });
  });
  const reads: string[] = [];
  const settled: string[] = [];
  let releaseA: (() => void) | undefined;
  await page.route('**/api/heads/*/trace?turn=*', async (route) => {
    const id = new URL(route.request().url()).searchParams.get('turn');
    if (id === null) throw new Error('missing trace turn');
    reads.push(id);
    if (id === 'v4349-a') await new Promise<void>((resolve) => { releaseA = resolve; });
    await route.fulfill({ json: {
      head: STACK.soloHead,
      turn: {
        id, ts: Date.now(), session: null, model: STACK.soloModel, compact: false, open: false,
        outcome: 'error:conn-reset', failure_sentence: `the connection for ${id} closed mid-request; retry`,
        rounds: 1, attempts: 1, total_ms: 20,
      },
      records: [],
    } });
    settled.push(id);
  });
  try {
    await page.goto(`${env('CONSOLE_E2E_BASE')}/#/turns`);
    const openers = page.getByRole('button', { name: `Turn detail ${STACK.soloHead} ${STACK.soloModel}` });
    await expect(openers).toHaveCount(2, { timeout: 15_000 });
    await openers.first().click();
    await expect.poll(() => reads).toContain('v4349-a');
    await openers.last().click();
    const detail = page.getByRole('complementary', { name: 'Turn detail' });
    await expect(detail.locator('.myx-tn-failure')).toHaveText('the connection for v4349-b closed mid-request; retry');
    releaseA?.();
    await expect.poll(() => settled).toContain('v4349-a');
    await expect(detail.locator('.myx-tn-failure')).toHaveText('the connection for v4349-b closed mid-request; retry');
    expect(reads).toEqual(['v4349-a', 'v4349-b']);
  } finally {
    releaseA?.();
  }
});
