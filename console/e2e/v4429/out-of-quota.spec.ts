// V4-429: Fleet reads a running head OK while splice says it is out of quota. The daemon carries the
// instant its provider refuses turns until on /api/heads (`quotaResetAtEpochSeconds`, pinned by
// app/src/test/kotlin/splice/app/v4429); this stack's mock provider never refuses, so the field is put on
// one real head of the live /api/heads answer. Fleet's State cell and the opened panel then read
// `Out of quota until <reset>` in the browser's zone, and the Plans card counts the head outside OK.
import { expect, test } from '@playwright/test';
import { STACK } from '../stack';

const THREE_DAYS_S = 3 * 86_400;

function env(name: string): string {
  const value = process.env[name];
  if (value === undefined || value === '') throw new Error(`${name} is unset — the global setup did not start the stack`);
  return value;
}

test('a head out of quota reads Out of quota until its reset on Fleet, and the Plans card counts it outside OK', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.addInitScript(([storage, value]) => localStorage.setItem(storage, value), ['myx-mgmt-key', env('CONSOLE_E2E_KEY')]);
  const resetAt = Math.floor(Date.now() / 1000) + THREE_DAYS_S;
  let marking = false;
  await page.route((url) => url.pathname === '/api/heads', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as { heads: { key: string; running: boolean; quotaResetAtEpochSeconds?: number }[] };
    const marked = body.heads.find((head) => head.key === STACK.oauthHead);
    if (marked === undefined) throw new Error(`the stack has no head ${STACK.oauthHead}`);
    expect(marked.running, 'the head under test is running: it is its provider that refuses').toBe(true);
    if (marking) marked.quotaResetAtEpochSeconds = resetAt;
    await route.fulfill({ response, json: body });
  });
  // The stack's own counts first, with the head unmarked: it is what the marked head must move out of OK.
  await page.goto(`${env('CONSOLE_E2E_BASE')}/#/fleet`);
  const card = page.getByLabel(/^Plans: OK \d+, Attention \d+, Failing \d+, Down \d+/).first();
  await expect(card).toBeVisible({ timeout: 15_000 });
  const counts = async (): Promise<{ ok: number; rest: number }> => {
    const label = await card.getAttribute('aria-label') ?? '';
    const [ok = 0, ...rest] = [...label.matchAll(/(\d+)/g)].map((match) => Number(match[1]));
    return { ok, rest: rest.reduce((sum, n) => sum + n, 0) };
  };
  const before = await counts();
  marking = true;
  await page.reload();
  // The reset as this browser's machine zone prints it: the same words the page must say, never an ISO form.
  const local = await page.evaluate((seconds) => new Intl.DateTimeFormat('en-US', {
    month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit',
  }).format(new Date(seconds * 1000)), resetAt);
  const sentence = `Out of quota until ${local}`;
  const table = page.getByRole('table', { name: 'Plans', exact: true });
  const row = table.locator('tbody tr', { hasText: STACK.oauthHead }).first();
  await expect(row).toBeVisible({ timeout: 15_000 });
  await expect(row.getByText(sentence, { exact: true })).toBeVisible({ timeout: 15_000 });
  const others = table.locator('tbody tr', { hasNotText: STACK.oauthHead });
  await expect(others.first()).toBeVisible();
  expect(await others.getByText(/^Out of quota/).count(), 'a head the daemon marks nothing on reads as it did').toBe(0);
  await expect.poll(counts).toEqual({ ok: before.ok - 1, rest: before.rest + 1 });
  await row.click();
  await expect(page.getByLabel('Plan detail').getByText(sentence).first()).toBeVisible();
});
