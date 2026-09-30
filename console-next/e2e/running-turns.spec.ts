// NEW: V4-444 — indistinguishable live siblings retain separate identities and disappear independently.
import { expect, test } from '@playwright/test';
import type { HeadsPayload } from '../src/types/core';
import { open } from './support';
import { STACK } from './stack';

test('same-label same-age live siblings do not collapse and one removal leaves its twin', async ({ page }) => {
  let count = 2;
  await page.route('**/api/heads', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as HeadsPayload;
    const head = body.heads.find((entry) => entry.key === STACK.oauthHead);
    if (head === undefined || head.gate === null) throw new Error('synthetic gate missing');
    head.gate.live = Array.from({ length: count }, () => ({
      label: 'synthetic-twin', compact: false, phase: 'streaming', age_ms: 5000, idle_ms: 50,
    }));
    head.gate.inflight = count;
    await route.fulfill({ response, json: body });
  });
  const faults = await open(page, 'turns');
  const twins = page.getByRole('listitem', { name: 'synthetic-twin, ' + STACK.oauthHead, exact: true });
  await expect(twins).toHaveCount(2);
  await expect(twins.first()).toContainText('Streaming its answer');
  count = 1;
  await expect(twins).toHaveCount(1, { timeout: 20_000 });
  count = 0;
  await expect(twins).toHaveCount(0, { timeout: 20_000 });
  expect(faults.pageErrors).toEqual([]);
  expect(faults.consoleErrors.filter((line) => /same key|unique.*key/i.test(line))).toEqual([]);
  await page.unrouteAll({ behavior: 'wait' });
});
