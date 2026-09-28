import { expect, test } from '@playwright/test';
import { STACK, driveOneTurn } from '../stack';

function env(name: string): string {
  const value = process.env[name];
  if (value === undefined || value === '') throw new Error(`${name} is unset`);
  return value;
}

test('a failed turn reads one trace and keeps its sentence under its own status', async ({ page }) => {
  await driveOneTurn(Number(env('CONSOLE_E2E_SOLO_PORT')), env('CONSOLE_E2E_KEY'), undefined, STACK.soloModel);
  await page.addInitScript((key) => localStorage.setItem('myx-mgmt-key', key), env('CONSOLE_E2E_KEY'));
  await page.route('**/api/perf/turns?*', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as {
      heads: { key: string; rows?: { ts: number; model: string; turn?: string; outcome: string }[] }[];
    };
    const head = body.heads.find((entry) => entry.key === STACK.soloHead);
    if (head === undefined) {
      await route.fulfill({ response, json: body });
      return;
    }
    const source = head.rows?.[0];
    if (source === undefined) throw new Error('the solo turn did not land');
    head.rows = [
      { ...source, ts: source.ts + 2, turn: 'v4349-a', outcome: 'error:conn-reset' },
      { ...source, ts: source.ts + 1, turn: 'v4349-b', outcome: 'error:conn-reset' },
    ];
    await route.fulfill({ response, json: body });
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
