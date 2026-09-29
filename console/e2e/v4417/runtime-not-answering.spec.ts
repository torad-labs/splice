// V4-417: Fleet reads a running local head Down, with the runtime's own sentence, while its runtime does
// not answer, and the Plans card counts it Down. Marlin's walk of f7f1e9308: `splice status` said
// "runtime not answering on :8099-:8102" for four local heads while Fleet read them OK and counted 11 OK,
// 0 Down. The daemon marks such a head on /api/heads (`runtimeNotAnswering`, from its background probe);
// this stack has no local runtime, so the mark is put on one real head of the live /api/heads answer, and
// the daemon's own probe and payloads are pinned by app/src/test/kotlin/splice/app/v4417.
import { expect, test } from '@playwright/test';
import { STACK } from '../stack';

function env(name: string): string {
  const value = process.env[name];
  if (value === undefined || value === '') throw new Error(`${name} is unset — the global setup did not start the stack`);
  return value;
}

test('a running head whose runtime is silent reads Down on Fleet, with the sentence, and the Plans card counts it', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.addInitScript(([storage, value]) => localStorage.setItem(storage, value), ['myx-mgmt-key', env('CONSOLE_E2E_KEY')]);
  await page.route((url) => url.pathname === '/api/heads', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as { heads: { key: string; running: boolean; runtimeNotAnswering?: string }[] };
    const marked = body.heads.find((head) => head.key === STACK.oauthHead);
    if (marked === undefined) throw new Error(`the stack has no head ${STACK.oauthHead}`);
    expect(marked.running, 'the head under test is running: it is its runtime that is silent').toBe(true);
    marked.runtimeNotAnswering = ':8099';
    await route.fulfill({ response, json: body });
  });
  await page.goto(`${env('CONSOLE_E2E_BASE')}/#/fleet`);
  const table = page.getByRole('table', { name: 'Plans', exact: true });
  const row = table.locator('tbody tr', { hasText: STACK.oauthHead }).first();
  await expect(row).toBeVisible({ timeout: 15_000 });
  await expect(row.getByText('Down', { exact: true })).toBeVisible({ timeout: 15_000 });
  const others = table.locator('tbody tr', { hasNotText: STACK.oauthHead });
  await expect(others.first()).toBeVisible();
  expect(await others.getByText('Down', { exact: true }).count(), 'a head whose runtime is not marked does not read Down').toBe(0);
  const card = page.getByLabel(/^Plans: OK \d+, Attention \d+, Failing \d+, Down \d+/).first();
  await expect(card).toHaveAttribute('aria-label', /Down 1\b/);
  await row.click();
  await expect(page.getByText('runtime not answering on :8099')).toBeVisible();
});
