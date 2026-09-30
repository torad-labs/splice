// Renders every comp page to HTML and PNG: node docs/design/comps/build.mjs [page ...]
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { chromium } from '/home/marcos/Documents/dev/projects/mythos/repo/node_modules/@playwright/test/index.mjs';
import { shell } from './shell.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const PAGES = ['sessions', 'session', 'needs', 'settings', 'fleet', 'turns', 'turn', 'usage', 'team', 'project'];
const WIDTHS = [1440, 1920];
const THEMES = ['day', 'night'];
const pick = process.argv.slice(2);
const todo = pick.length > 0 ? pick : PAGES;

mkdirSync(join(here, 'png'), { recursive: true });
const browser = await chromium.launch({ args: ['--no-sandbox'] });
for (const name of todo) {
  const mod = await import(pathToFileURL(join(here, 'pages', `${name}.mjs`)).href);
  const nav = mod.nav ?? name;
  for (const theme of THEMES) {
    const html = shell({ current: nav, theme, body: mod.body(), css: mod.css ?? '' });
    const file = join(here, `${name}-${theme}.html`);
    writeFileSync(file, html);
    for (const width of WIDTHS) {
      const page = await browser.newPage({ viewport: { width, height: 1000 } });
      await page.goto(pathToFileURL(file).href);
      await page.evaluate(() => document.fonts.ready);
      await page.screenshot({ path: join(here, 'png', `${name}-${theme}-${width}.png`), fullPage: true });
      await page.close();
    }
  }
}
await browser.close();
