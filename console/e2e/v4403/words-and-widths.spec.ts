// V4-403: Settings never says topology or daemon, and Fleet and Turns show every value whole at
// 1440x900. Marlin's walk of V4-348 on bb54736ea: Settings read 'Topology', 'Write topology', 'Raw
// topology' and a 'daemon' section; Fleet cut 'Signed ou[t]', 'claude-fable-…' and 'muse-spark-1.…'
// and ran its Window column off the table's edge; Turns cut 'error:rate-limite[d]'.
import { expect, test, type Locator, type Page } from '@playwright/test';

function env(name: string): string {
  const value = process.env[name];
  if (value === undefined || value === '') throw new Error(`${name} is unset — the global setup did not start the stack`);
  return value;
}

async function open(page: Page, name: string): Promise<void> {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.addInitScript(([storage, value]) => localStorage.setItem(storage, value), ['myx-mgmt-key', env('CONSOLE_E2E_KEY')]);
  await page.goto(`${env('CONSOLE_E2E_BASE')}/#/${name}`);
  await expect(page.getByRole('navigation', { name: 'pages' })).toBeVisible();
}

/** What a cell shows against what it has room for: clipped by its own box or by the table's edge. */
async function overflow(cells: Locator): Promise<string[]> {
  return cells.evaluateAll((all) => all.flatMap((cell) => {
    const table = cell.closest('table')?.parentElement ?? document.body;
    const edge = table.getBoundingClientRect().right;
    const range = document.createRange();
    range.selectNodeContents(cell);
    const text = range.getBoundingClientRect();
    const clipped = [...cell.querySelectorAll('*'), cell].some((el) => el.scrollWidth > el.clientWidth + 1
      && getComputedStyle(el).overflow !== 'visible');
    const past = text.right > cell.getBoundingClientRect().right + 1 || text.right > edge + 1;
    return clipped || past ? [`${(cell.textContent ?? '').trim()} (text ends ${Math.round(text.right)}, cell ${Math.round(cell.getBoundingClientRect().right)}, edge ${Math.round(edge)})`] : [];
  }));
}

test('Settings says neither topology nor daemon in anything the operator reads', async ({ page }) => {
  await open(page, 'settings');
  await expect(page.locator('.myx-settings-section').first()).toBeVisible({ timeout: 15_000 });
  // The operator's own file, its path and the names inside it are theirs; the words around them are ours.
  await page.locator('main').evaluate((main) => {
    main.querySelectorAll<HTMLElement>('pre, textarea, code, input, .myx-settings-path').forEach((el) => { el.style.display = 'none'; });
  });
  const read = (await page.locator('main').innerText()).split('\n').map((line) => line.trim()).filter((line) => line !== '');
  expect(read.filter((line) => /\b(topology|daemon)\b/i.test(line))).toEqual([]);
  // Two bays or regions of one name read as one to a person and to a screen reader (V4-403 follow-up).
  const named = await page.locator('main .myx-bay-label').allTextContents();
  const regions = await page.locator('main section[aria-label], main [role="region"]').evaluateAll((all) => all.map((el) => el.getAttribute('aria-label') ?? ''));
  const repeated = (names: string[]) => names.filter((name, at) => name !== '' && names.indexOf(name) !== at);
  expect({ bays: repeated(named), regions: repeated(regions) }).toEqual({ bays: [], regions: [] });
});

test('Fleet shows each plan\'s state, model and window whole at desktop width', async ({ page }) => {
  await page.route('**/api/models', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as { heads: { pinned_model: string }[] };
    const names = ['claude-fable-5-1', 'muse-spark-1.3[1m]', 'claude-opus-5-5-20260928'];
    body.heads.forEach((head, at) => { head.pinned_model = names[at % names.length]; });
    await route.fulfill({ response, json: body });
  });
  await open(page, 'fleet');
  const table = page.getByRole('table', { name: 'Plans', exact: true });
  await expect(table.locator('tbody tr').first()).toBeVisible({ timeout: 15_000 });
  await expect(table.getByText('claude-fable-5-1')).toBeVisible({ timeout: 15_000 });
  const heads = await table.locator('thead th').allTextContents();
  const cut: string[] = [];
  for (const column of ['State', 'Model', 'Window']) {
    const at = heads.findIndex((label) => label.trim() === column);
    expect(at, `${column} column present`).toBeGreaterThanOrEqual(0);
    cut.push(...(await overflow(table.locator(`tbody tr td:nth-child(${at + 1})`))).map((row) => `${column}: ${row}`));
  }
  expect(cut, 'these Fleet cells lose text or run past the table').toEqual([]);
});

test('Turns shows an outcome whole at desktop width', async ({ page }) => {
  await page.route('**/api/perf/turns?*', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as { heads: { rows?: { outcome: string }[] }[] };
    const row = body.heads.flatMap((head) => head.rows ?? [])[0];
    if (row !== undefined) row.outcome = 'error:rate-limited';
    await route.fulfill({ response, json: body });
  });
  await open(page, 'turns');
  const table = page.locator('.myx-tn-table').first();
  // .first(): an earlier journey in the shared stack can leave a real rate-limited turn beside the one rewritten here
  await expect(table.getByText('error:rate-limited').first()).toBeVisible({ timeout: 15_000 });
  const heads = await table.locator('thead th').allTextContents();
  const at = heads.findIndex((label) => label.trim() === 'Outcome');
  expect(at, 'Outcome column present').toBeGreaterThanOrEqual(0);
  expect(await overflow(table.locator(`tbody tr td:nth-child(${at + 1})`))).toEqual([]);
});
