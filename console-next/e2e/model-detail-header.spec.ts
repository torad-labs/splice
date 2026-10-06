import { expect, test } from '@playwright/test';
import { assertHealthy, open } from './support';
import { STACK } from './stack';

test('model detail keeps its heading readable beside the tabs at desktop and narrow widths', async ({ page }, testInfo) => {
  const faults = await open(page, 'models/' + STACK.soloHead);
  const heading = page.getByRole('heading', { name: 'Plan windows', exact: true });
  for (const width of [1536, 393]) {
    await page.setViewportSize({ width, height: 1024 });
    await expect(heading).toBeVisible();
    expect(await heading.evaluate(element => {
      const range = document.createRange();
      range.selectNodeContents(element);
      return range.getClientRects().length;
    })).toBeLessThanOrEqual(2);
    await expect(page.getByRole('button', { name: 'Windows', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Models', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Log', exact: true })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.getByRole('main').screenshot({ path: testInfo.outputPath('model-detail-header-' + width + '.png') });
  }
  await assertHealthy(page, faults);
});
