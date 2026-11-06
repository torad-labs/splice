import { chromium } from 'playwright';
import { mkdir } from 'node:fs/promises';
const output = process.env.SPLICE_CONSOLE_CAPTURE_DIR || '/tmp/splice-instrumentation-run/B08-draft';
await mkdir(output, { recursive: true });
const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ viewport: { width: 3840, height: 2050 }, deviceScaleFactor: 1, permissions: [] });
const page = await context.newPage();
const errors = []; page.on('pageerror', (e) => errors.push(e.stack));
await page.goto('http://127.0.0.1:4318/preview.html');
await page.waitForTimeout(1200);
await page.screenshot({ path: `${output}/draft-3840-night.png` });
console.log(JSON.stringify({ errors, title: await page.title(), width: await page.evaluate(() => ({ viewport: innerWidth, scroll: document.documentElement.scrollWidth })) }));
for (const tab of ['distribution', 'history', 'timeline', 'baselines', 'profiles', 'objectives', 'alerts', 'runtime']) {
  await page.locator(`[data-pc-tab="${tab}"]`).click();
  await page.screenshot({ path: `${output}/draft-3840-${tab}.png` });
}
for (const theme of ['night', 'day']) {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.evaluate((t) => document.documentElement.dataset.theme = t, theme);
  await page.locator('[data-pc-tab="overview"]').click();
  await page.screenshot({ path: `${output}/draft-1440-${theme}.png` });
}
await page.setViewportSize({ width: 390, height: 844 });
await page.screenshot({ path: `${output}/draft-390-day.png` });
console.log(JSON.stringify({ errors, mobile: await page.evaluate(() => ({ viewport: innerWidth, scroll: document.documentElement.scrollWidth })) }));
await page.setViewportSize({ width: 3840, height: 2050 });
for (const name of ['usage','requests']) {
  await page.goto('http://127.0.0.1:4318/' + name + '.html?win=5h&project=splice');
  await page.waitForTimeout(1400);
  await page.screenshot({ path: output + '/draft-native-' + name + '.png' });
  console.log(JSON.stringify({ surface: name, errors }));
}
await context.close(); await browser.close();
